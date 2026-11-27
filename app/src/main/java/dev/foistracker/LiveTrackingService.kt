package dev.foistracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.io.IOException

data class LiveTrackingState(val query: Query, val paused: Boolean = false)

class LiveTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val services by lazy { Services.get(this) }
    private var tracking: Job? = null
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:LiveTracking")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UPDATE_NOTIFICATION) {
            val active = services.liveTracking.value
            if (active != null) scope.launch {
                val rows = withContext(Dispatchers.IO) { services.store.cached(active.query.storageKey) }
                if (services.liveTracking.value?.query == active.query) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(active.query, active.paused, notificationArrivals(rows)))
                }
            } else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            services.settings.edit().putBoolean("metro_tracking", false).apply()
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("freight-refresh")
            stopSelf()
            return START_NOT_STICKY
        }
        val stored = intent?.getStringExtra(QUERY)?.let(Query::fromStorage)
        val query = stored?.let { Query.parse(it.key, it.consignor) }
        if (query == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (services.liveTracking.value?.query == query && tracking?.isActive == true) return START_NOT_STICKY

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background live tracking", NotificationManager.IMPORTANCE_LOW))
        var arrival = intent.getStringExtra(ARRIVAL) ?: notificationArrivals(emptyList())
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(query, false, arrival),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        tracking?.cancel()
        services.liveTracking.value = LiveTrackingState(query)
        tracking = scope.launch {
            var failures = 0
            try {
                while (isActive) {
                    wakeLock.acquire(60_000)
                    val (arrived, latestArrival) = try {
                        withContext(Dispatchers.IO) {
                            services.networkLock.withLock {
                                if (!services.signedIn.value) throw FoisError.Authentication()
                                val previous = services.store.cached(query.storageKey)
                                val rows = try {
                                    services.client.enquiry(query)
                                } catch (error: FoisError.Authentication) {
                                    services.markExpired(query)
                                    throw error
                                }
                                ensureActive()
                                services.persistSession()
                                val updated = services.store.record(query.storageKey, rows)
                                Tracking.notifyChanges(this@LiveTrackingService, services, query.storageKey, previous, updated)
                                services.store.clearAlert(query.storageKey, Alert.ERROR.name)
                                val completed = updated.isNotEmpty() && updated.all { it.arrived != null }
                                if (completed && query.isFnr) services.store.pause(query.storageKey)
                                completed to notificationArrivals(updated)
                            }
                        }
                    } catch (_: FoisError.Authentication) {
                        withContext(Dispatchers.IO) {
                            Tracking.notify(this@LiveTrackingService, services, query.storageKey, Alert.SESSION,
                                "Sign in to ECBS to resume live tracking.")
                        }
                        if (services.recovery.value == null) break
                        services.liveTracking.value = LiveTrackingState(query, paused = true)
                        manager.notify(NOTIFICATION_ID, notification(query, true, arrival))
                        if (wakeLock.isHeld) wakeLock.release()
                        services.signedIn.first { it }
                        continue
                    } catch (error: IOException) {
                        if (services.liveTracking.value?.paused != true) {
                            services.liveTracking.value = LiveTrackingState(query, paused = true)
                            manager.notify(NOTIFICATION_ID, notification(query, true, arrival))
                        }
                        if (wakeLock.isHeld) wakeLock.release()
                        failures = (failures + 1).coerceAtMost(6)
                        delay(maxOf(1000L shl failures, (error as? FoisError.Throttled)?.retryMillis ?: 0))
                        continue
                    }
                    if (arrived) break
                    if (wakeLock.isHeld && services.intervalSeconds.value >= 15) wakeLock.release()
                    if (services.liveTracking.value?.paused == true || latestArrival != arrival) {
                        arrival = latestArrival
                        services.liveTracking.value = LiveTrackingState(query)
                        manager.notify(NOTIFICATION_ID, notification(query, false, arrival))
                    }
                    failures = 0
                    val started = android.os.SystemClock.elapsedRealtime()
                    while (isActive && android.os.SystemClock.elapsedRealtime() <
                        started + services.intervalSeconds.value * 1000) delay(250)
                    yield()
                }
            } finally {
                if (isActive) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        services.liveTracking.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(query: Query, paused: Boolean, arrival: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra("enquiry", query.storageKey)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, LiveTrackingService::class.java).setAction(STOP), flags)
        val privacy = services.settings.getBoolean("notification_privacy", false)
        val body = if (privacy) "1 freight update received" else if (paused) "Waiting to retry\n$arrival" else arrival
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rail)
            .setContentTitle(if (privacy) null else "FOIS Tracker • Live")
            .setSubText(if (privacy) null else query.key)
            .setContentText(body.lineSequence().first())
            .setVisibility(if (privacy) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Open app", open)
            .addAction(0, "Stop tracking", stop)
            .build()
    }

    companion object {
        const val UPDATE_NOTIFICATION = "dev.foistracker.UPDATE_LIVE_NOTIFICATION"
        const val QUERY = "live_query"
        const val ARRIVAL = "live_arrival"
        private const val STOP = "dev.foistracker.STOP_LIVE"
        private const val CHANNEL = "freight-live-tracking"
        private const val NOTIFICATION_ID = 194050
    }
}
