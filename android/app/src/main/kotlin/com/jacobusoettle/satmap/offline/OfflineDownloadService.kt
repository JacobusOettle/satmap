package com.jacobusoettle.satmap.offline

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** iOS's `BGContinuedProcessingTask`: a foreground service of type dataSync that keeps a download going when SatMap is
 *  left, with a partial wake lock (at most 6 h), the progress notification and its Stop action. Swiping SatMap away
 *  does not stop it (`stopWithTask` is false). */
class OfflineDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.jacobusoettle.satmap.START_DOWNLOADS"
        const val ACTION_STOP = "com.jacobusoettle.satmap.STOP_DOWNLOADS"

        @Volatile
        var running: Boolean = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M4: ACTION_START → startForeground(DATA_SYNC) first, then the wake lock; ACTION_STOP → stopFromNotification().
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
