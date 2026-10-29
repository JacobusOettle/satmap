package com.jacobusoettle.satmap.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.R
import com.jacobusoettle.satmap.app.AppModel

/** Recording in the background with "While using the app" access (iOS `UIBackgroundModes: location`, with
 *  `allowsBackgroundLocationUpdates` and the background location indicator): a foreground service of type location,
 *  started only from the foreground, keeps the app's location access while it runs, so `ACCESS_BACKGROUND_LOCATION`
 *  is never requested. Its notification says "Recording your track", with a Stop action: iOS's force-quit, until
 *  SatMap is next opened. Pressed with SatMap on screen (the notification shade or split screen over the map), the
 *  map keeps its location until SatMap leaves the screen, as on iOS: Stop ends background recording only. Swiping
 *  SatMap away from Recents keeps recording (`stopWithTask` stays false). If the system ends the process, it isn't
 *  restarted in the background (`START_NOT_STICKY`); recording starts again when SatMap is opened, as on iOS. */
class LocationService : Service() {
    companion object {
        const val CHANNEL = "recording"
        const val ID = 1
        const val ACTION_STOP = "com.jacobusoettle.satmap.STOP_RECORDING"

        /** From `LocationRecorder.beginUpdates` (foreground, location allowed). Android refuses it from the
         *  background (`ForegroundServiceStartNotAllowedException`, API 31+, an `IllegalStateException` as the refusal
         *  before it): recording then carries on while SatMap is visible. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background.
            } catch (e: SecurityException) {
                // No location permission for a service of this type.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // On screen, the recorder carries on until SatMap goes to the background.
            AppModel.shared.recorder.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Refused (started too late, or the permission went): recording carries on while SatMap is visible.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Shown without sound or time; without the notification permission the service runs all the same, its
     *  notification only missing from the drawer. */
    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.channel_recording))
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("SatMap")
            .setContentText("Recording your track")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .build()
    }
}
