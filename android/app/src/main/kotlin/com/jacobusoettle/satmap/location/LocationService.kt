package com.jacobusoettle.satmap.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Recording in the background with "While using the app" access (iOS `UIBackgroundModes: location`): a foreground
 *  service of type location, started only from the foreground, keeps the app's location access while it runs, so
 *  `ACCESS_BACKGROUND_LOCATION` is never requested. Its notification says "Recording your track", with a Stop action
 *  (iOS's force-quit). Swiping SatMap away from Recents keeps recording (`stopWithTask` stays false). */
class LocationService : Service() {
    companion object {
        const val CHANNEL = "recording"
        const val ID = 1
        const val ACTION_STOP = "com.jacobusoettle.satmap.STOP_RECORDING"

        fun start(context: Context) {
            // M1: ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java)).
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M1: ACTION_STOP → recorder.stop(); otherwise startForeground with the "recording" notification.
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
