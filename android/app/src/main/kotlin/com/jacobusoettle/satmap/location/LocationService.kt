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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        /** From `LocationRecorder.stopForLock` (a test build switched off): the service and its notification go, in
         *  the foreground or the background; nothing happens when it isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }

    /** The groups the notification names, and their collector while the service runs. */
    private var sharing: List<String> = emptyList()
    private var watch: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // On screen, the recorder carries on until SatMap goes to the background. Sharing goes off in every group
            // too (an Android-only difference, groups §11.6): Stop means nobody follows you any more.
            AppModel.shared.recorder.stop()
            AppModel.sharedOrNull?.groups?.stopSharingEverywhere()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        sharing = AppModel.sharedOrNull?.groups?.sharingNames?.value.orEmpty()
        try {
            ServiceCompat.startForeground(this, ID, notification(this, sharing), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Refused (started too late, or the permission went): recording carries on while SatMap is visible.
            stopSelf()
            return START_NOT_STICKY
        }
        // The groups shared with change while recording: the notification says so (§11.6).
        val names = AppModel.sharedOrNull?.groups?.sharingNames
        if (watch == null && names != null) {
            watch = scope.launch {
                names.collect { names ->
                    if (names == sharing) return@collect
                    sharing = names
                    try {
                        NotificationManagerCompat.from(this@LocationService).notify(ID, notification(this@LocationService, names))
                    } catch (e: SecurityException) {
                        // No notification permission: the service runs without its notification in the drawer.
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        scope.cancel()
        watch = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The recording notification, shown without sound or time; without the notification permission the service runs all
 *  the same, its notification only missing from the drawer. While groups share (`sharing`, their names) a big-text
 *  view adds "Sharing your location with {group}." or "… with {n} groups." under "Recording your track", which stays
 *  the collapsed text (groups §11.6). */
internal fun LocationService.Companion.notification(context: Context, sharing: List<String>): Notification {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(LocationService.CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_recording))
            .setShowBadge(false)
            .build(),
    )
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
        context, 1, Intent(context, LocationService::class.java).setAction(LocationService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(context, LocationService.CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_location)
        .setContentTitle("SatMap")
        .setContentText(RECORDING)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(open)
        .addAction(R.drawable.ic_stop, "Stop", stop)
    sharingLine(sharing)?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText("$RECORDING\n$it")) }
    return builder.build()
}

private const val RECORDING = "Recording your track"

/** The second line of the notification while groups share, or null when none does. */
internal fun sharingLine(sharing: List<String>): String? = when (sharing.size) {
    0 -> null
    1 -> "Sharing your location with ${sharing[0]}."
    else -> "Sharing your location with ${sharing.size} groups."
}
