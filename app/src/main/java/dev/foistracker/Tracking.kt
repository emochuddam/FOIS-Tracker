package dev.foistracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class Alert(val label: String) {
    ETA("ETA changed"), ARRIVED("Consignment arrived"), MOVEMENT("New station movement"),
    STATUS("Freight status changed"), DELAY("Significant delay"), SESSION("Session expired"),
    AUTH("Authentication required"), ERROR("Tracking failure"), RESUMED("Tracking resumed"), REFRESH("Freight refreshed")
}

fun freightAlerts(old: Freight?, next: Freight): List<Pair<Alert, String>> = buildList {
    if (old == null) return@buildList
    if (next.eta != null && next.eta != old.eta) {
        add(Alert.ETA to "${old.eta ?: "Not Reported"} → ${next.eta}")
        val from = freightTime(old.eta)
        val to = freightTime(next.eta)
        if (from != null && to != null && Duration.between(from, to).toMinutes() >= 60) {
            add(Alert.DELAY to "${next.eta} • ETA moved by at least one hour")
        }
    }
    if (next.arrived != null && old.arrived == null) add(Alert.ARRIVED to next.arrived!!)
    val station = next.values["Last reported station"]
    val movement = next.values["Last reported movement"]
    if (station != null && (station != old.values["Last reported station"] ||
            movement != null && movement != old.values["Last reported movement"])) {
        add(Alert.MOVEMENT to listOfNotNull(station, movement).joinToString(" • "))
    }
    val status = next.values["Freight status"]
    if (status != null && status != old.values["Freight status"]) add(Alert.STATUS to status)
}

internal fun notificationArrival(freight: Freight): String = when {
    freight.arrived != null -> "Arrived · ${displayFreightTime(freight.arrived)}"
    else -> "Estimated arrival · ${displayFreightTime(freight.eta)}"
}

internal fun notificationArrivals(freight: List<Freight>): String {
    if (freight.size <= 1) return freight.firstOrNull()?.let(::notificationArrival) ?: "Estimated arrival · Not Reported"
    return freight.take(3).mapIndexed { index, row ->
        "${row.fnr.ifBlank { "Consignment ${index + 1}" }} · ${notificationArrival(row)}"
    }.plus(listOfNotNull("Open app for all ${freight.size} consignments".takeIf { freight.size > 3 })).joinToString("\n")
}

object Tracking {
    fun schedule(context: Context, minutes: Long) {
        val request = PeriodicWorkRequestBuilder<FreightWorker>(minutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("freight-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun sync(context: Context, services: Services) {
        if (services.store.saved().any { it.tracking }) schedule(context, services.settings.getLong("interval", 15))
        else WorkManager.getInstance(context).cancelUniqueWork("freight-refresh")
    }
    fun channel(context: Context, alert: Alert): String {
        val id = "freight-${alert.name.lowercase()}"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(id, alert.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(true)
            })
        }
        return id
    }
    fun notifyChanges(context: Context, services: Services, key: String, previous: List<Freight>, updated: List<Freight>) {
        for (row in updated) {
            val rake = row.values["Rake identification"]
            val old = previous.firstOrNull {
                if (row.fnr.isNotEmpty()) it.fnr == row.fnr else rake != null && it.values["Rake identification"] == rake
            } ?: continue
            freightAlerts(old, row).forEach { (alert, text) ->
                notify(context, services, key, alert, "$text\n${notificationArrival(row)}",
                    "${row.fnr.ifEmpty { rake.orEmpty() }}|${old.retrieved}|$text")
            }
        }
    }

    fun notify(context: Context, services: Services, key: String, alert: Alert, text: String, fingerprint: String = text) {
        if (!services.settings.getBoolean("alert_${alert.name}", true)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = channel(context, alert)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (!services.store.registerAlert(key, alert.name, fingerprint)) return
        val id = "$key-${alert.name}".hashCode()
        val intent = Intent(context, MainActivity::class.java).putExtra("enquiry", key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_rail).setContentTitle(alert.label).setContentText(text.lineSequence().first())
            .setSubText(Query.fromStorage(key).key).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).build())
    }
}

class FreightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { refreshTrackedFreight() }

    private suspend fun refreshTrackedFreight(): Result {
        val services = Services.get(applicationContext)
        val items = services.store.saved().filter { it.tracking }
        if (items.isEmpty()) return Result.success()
        var retry = false
        for (item in items) {
            services.networkLock.withLock {
                if (services.liveTracking.value?.query?.storageKey == item.key) return@withLock
                if (!services.store.saved().any { it.key == item.key && it.tracking }) return@withLock
                if (!services.signedIn.value) {
                    Tracking.notify(applicationContext, services, item.key, Alert.AUTH, "Sign in to continue background tracking.")
                    return Result.success()
                }
                try {
                    val old = services.store.cached(item.key)
                    val query = Query.fromStorage(item.key)
                    if (!query.isFnr) return@withLock
                    val next = services.store.record(item.key, services.client.enquiry(query)).first()
                    services.persistSession()
                    Tracking.notifyChanges(applicationContext, services, item.key, old, listOf(next))
                    if (next.arrived != null) services.store.pause(item.key)
                    services.store.clearAlert(item.key, Alert.ERROR.name)
                } catch (_: FoisError.Authentication) {
                    services.forgetSession()
                    Tracking.notify(applicationContext, services, item.key, Alert.SESSION, "Sign in to continue background tracking.")
                    return Result.success()
                } catch (_: IOException) {
                    Tracking.notify(applicationContext, services, item.key, Alert.ERROR, "FOIS could not refresh this consignment.")
                    retry = true
                }
            }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
