package com.jacobusoettle.satmap.offline

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/** iOS's `BGContinuedProcessingTask`: a foreground service of type dataSync that keeps a download going when SatMap is
 *  left, with a partial wake lock (at most 6 h), the progress notification and its Stop action. `BackgroundDownload`
 *  starts and stops it, and builds its notification.
 *
 *  Different from iOS: swiping SatMap away does not stop it (`stopWithTask` is false), and the notification stays. No
 *  Wi-Fi lock is taken: the download job's adaptive parallelism absorbs Wi-Fi power-save latency. If the system ends
 *  the process, the service isn't restarted (`START_NOT_STICKY`): the download continues when SatMap is opened, as on
 *  iOS. */
class OfflineDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.jacobusoettle.satmap.START_DOWNLOADS"
        const val ACTION_STOP = "com.jacobusoettle.satmap.STOP_DOWNLOADS"

        /** Whether a service runs in the foreground. */
        @Volatile
        var running: Boolean = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val background = BackgroundDownload.current
            if (running && background != null) background.stopPressed() else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        // Within 5 s of the start, and first: Android ends an app whose foreground service doesn't call this.
        // (`BackgroundDownload` is set before it asks for the start.)
        val background = BackgroundDownload.current ?: return stop()
        try {
            ServiceCompat.startForeground(this, BackgroundDownload.NOTIFICATION_ID, background.notification(),
                                          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Refused (ForegroundServiceStartNotAllowedException: e.g. dataSync's time for the day is used up).
            background.serviceRefused()
            return stop()
        }
        if (!background.serviceStarted(this)) {
            // The download ended before the service started.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return stop()
        }
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SatMap:offlineDownload")
            .apply {
                setReferenceCounted(false)
                acquire(6 * 3600_000L)
            }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    /** API 35: dataSync's 6 hours in 24 are used up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val background = BackgroundDownload.current
        if (background != null) {
            background.serviceTimedOut(this)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (running) {
            running = false
            BackgroundDownload.current?.serviceEnded(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
