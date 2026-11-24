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
        if (intent?.action == STOP) {
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
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(query, false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        tracking?.cancel()
        services.liveTracking.value = LiveTrackingState(query)
        tracking = scope.launch {
            try {
                while (isActive) {
                    // Keep the CPU available for the requested live cadence while the screen is off.
                    wakeLock.acquire(60_000)
                    val arrived = try {
                        withContext(Dispatchers.IO) {
                            services.networkLock.withLock {
                                if (!services.signedIn.value) throw FoisError.Authentication()
                                val previous = services.store.cached(query.storageKey)
                                val rows = try {
                                    services.client.enquiry(query)
                                } catch (error: FoisError.Authentication) {
                                    services.forgetSession()
                                    throw error
                                }
                                ensureActive()
                                services.persistSession()
                                val updated = services.store.record(query.storageKey, rows)
                                Tracking.notifyChanges(this@LiveTrackingService, services, query.storageKey, previous, updated)
                                services.store.clearAlert(query.storageKey, Alert.ERROR.name)
                                val completed = updated.isNotEmpty() && updated.all { it.arrived != null }
                                if (completed && query.isFnr) services.store.pause(query.storageKey)
                                completed
                            }
                        }
                    } catch (_: FoisError.Authentication) {
                        withContext(Dispatchers.IO) {
                            Tracking.notify(this@LiveTrackingService, services, query.storageKey, Alert.SESSION,
                                "Sign in to ECBS to resume live tracking.")
                        }
                        break
                    } catch (_: IOException) {
                        if (services.liveTracking.value?.paused != true) {
                            services.liveTracking.value = LiveTrackingState(query, paused = true)
                            manager.notify(NOTIFICATION_ID, notification(query, true))
                        }
                        if (wakeLock.isHeld) wakeLock.release()
                        delay(30_000)
                        continue
                    }
                    if (arrived) break
                    if (services.liveTracking.value?.paused == true) {
                        services.liveTracking.value = LiveTrackingState(query)
                        manager.notify(NOTIFICATION_ID, notification(query, false))
                    }
                    delay(1_000)
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

    private fun notification(query: Query, paused: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra("enquiry", query.storageKey)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, LiveTrackingService::class.java).setAction(STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rail)
            .setContentTitle("FOIS Tracker • Live")
            .setContentText(if (paused) "Waiting to retry ${query.key}" else "Tracking ${query.key}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val QUERY = "live_query"
        private const val STOP = "dev.foistracker.STOP_LIVE"
        private const val CHANNEL = "freight-live-tracking"
        private const val NOTIFICATION_ID = 194050
    }
}
