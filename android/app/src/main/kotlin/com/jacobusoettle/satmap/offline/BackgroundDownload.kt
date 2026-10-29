package com.jacobusoettle.satmap.offline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.R
import com.jacobusoettle.satmap.app.AppLifecycle
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Keeps an offline download running while SatMap is in the background.
 *
 *  iOS 26 makes a download started with SatMap on screen a continued processing task, with its progress on the Lock
 *  Screen and a button to stop it. Android's counterpart is a foreground service of type dataSync
 *  (`OfflineDownloadService`), started while SatMap is on screen: it keeps the download going in the background, with
 *  its progress in a notification and a Stop button. If Android doesn't allow the service, the download goes on while
 *  SatMap stays in memory, and as iOS's half minute for a just-left app, `onExpire` is called 25 s after SatMap is left.
 *  When the time ends (or the download is stopped from the notification with SatMap in the background), the download
 *  pauses and continues when SatMap is opened again. Used on the main thread only.
 *
 *  `start` starts the service (a test passes its own). */
class BackgroundDownload(
    private val context: Context,
    private val start: (Context, Intent) -> Unit = { c, i -> ContextCompat.startForegroundService(c, i) },
) {
    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2
        const val READY_NOTIFICATION_ID = 3

        /** The texts of `status` (DESIGN.md D12, offline spec §2.5). */
        const val keepsGoing = "Downloads keep going in the background, with their progress in the notification."
        const val keepsGoingWithoutNotifications =
            "Downloads keep going in the background. Allow notifications for SatMap to see their progress there."
        const val notAllowed =
            "In the background a download stops, and continues when you open SatMap: Android didn't allow it to keep going in the background."

        /** The instance whose service runs (or was asked to start): the service reports to it. Main thread only. */
        internal var current: BackgroundDownload? = null
    }

    /** The background time ended: the download has to stop. */
    var onExpire: (() -> Unit)? = null

    /** Stop was pressed in the notification. */
    var onStop: (() -> Unit)? = null

    /** What happens to downloads in the background, in a few words for the Offline maps screen (null until the first
     *  download). Also says when Android didn't allow more, as there is no other way to see that on the phone. */
    var status: String? = null
        private set(value) {
            val old = field
            field = value
            if (value != old) onStatusChange?.invoke(value)
        }
    var onStatusChange: ((String?) -> Unit)? = null

    /** The service is wanted: asked to start and not told to stop (iOS: the task, or its request pending). */
    private var wanted = false

    /** The service once it runs in the foreground (iOS: the task once iOS has started it). */
    private var service: OfflineDownloadService? = null

    /** Stop was pressed in the background: nothing more runs until SatMap is opened. */
    private var stopping = false

    /** iOS's extra time for a just-left app: here only when the service didn't start, a timer that ends it. */
    private val main = Handler(Looper.getMainLooper())
    private var backgroundTime: Runnable? = null

    private var title = ""
    private var subtitle = ""

    /** Progress for the notification's bar, over every area downloaded so far: the areas before the current one count
     *  as done. */
    private var areaID: UUID? = null
    private var carried = 0L
    private var done = 0L
    private var total = 0L
    private var shownDone = 0L

    /** The notification is posted at most once a second (Android drops updates that come faster); one due meanwhile
     *  waits for the rest of the second. */
    private var postedAt = Long.MIN_VALUE / 2
    private val postLater = Runnable { post() }
    private var postPending = false

    /** Whether a download may run on with SatMap in the background. */
    val allowsBackgroundWork: Boolean get() = (service != null && !stopping) || backgroundTime != null

    /** A download is running with SatMap on screen: ask Android to carry it on in the background with a progress
     *  notification. Only possible while SatMap is on screen, so it is asked when a download starts and when SatMap
     *  is opened. */
    fun begin(title: String, subtitle: String) {
        this.title = title
        this.subtitle = subtitle
        if (wanted) {
            post()
            return
        }
        if (!AppLifecycle.isForeground) return
        current = this
        try {
            start(context, Intent(context, OfflineDownloadService::class.java).setAction(OfflineDownloadService.ACTION_START))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+), or the refusal of a start in the background before.
            status = notAllowed
            return
        } catch (e: SecurityException) {
            status = notAllowed
            return
        }
        wanted = true
        stopping = false
        status = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) keepsGoing else keepsGoingWithoutNotifications
    }

    /** The download's progress (tiles done and planned in the area being downloaded), for the notification's bar. */
    fun update(area: UUID, title: String, subtitle: String, done: Long, total: Long) {
        if (area != areaID) {
            if (areaID != null) carried = max(this.done, this.total) // the area before was finished
            areaID = area
        }
        this.title = title
        this.subtitle = subtitle
        this.done = carried + max(0L, done)
        this.total = carried + max(0L, total)
        post()
    }

    /** SatMap went to the background with a download running. With the service, Android keeps it going (unlike iOS
     *  there is no half minute to arm, as the service keeps the process going); without, the download uses the time
     *  until `onExpire`. */
    fun enteredBackground() {
        if (service != null || backgroundTime != null) return
        val expire = Runnable {
            backgroundTime = null
            if (service == null) onExpire?.invoke()
        }
        backgroundTime = expire
        main.postDelayed(expire, 25_000)
    }

    /** SatMap is on screen again. */
    fun enteredForeground() {
        endBackgroundTime()
    }

    /** No download is running any more (all done, waiting for a network, paused or stopped). On `success` a "Ready
     *  offline" notification stands for the closing state of iOS's progress bar. */
    fun end(success: Boolean) {
        // (iOS completes only a task it has started.)
        val wasRunning = service != null
        wanted = false
        stopping = false
        service?.let { s ->
            ServiceCompat.stopForeground(s, ServiceCompat.STOP_FOREGROUND_REMOVE)
            s.stopSelf()
        }
        service = null
        main.removeCallbacks(postLater)
        postPending = false
        if (wasRunning && success) notifyReady()
        areaID = null
        carried = 0
        done = 0
        total = 0
        shownDone = 0
        endBackgroundTime()
    }

    private fun endBackgroundTime() {
        val t = backgroundTime ?: return
        main.removeCallbacks(t)
        backgroundTime = null
    }

    // The service

    /** The service is in the foreground. Returns false when it isn't wanted any more (the download ended before it
     *  started): it then stops at once. */
    internal fun serviceStarted(s: OfflineDownloadService): Boolean {
        if (!wanted) return false
        service = s
        postedAt = SystemClock.uptimeMillis()
        return true
    }

    /** Android refused the service after all (e.g. dataSync's time is used up). */
    internal fun serviceRefused() {
        wanted = false
        service = null
        status = notAllowed
    }

    /** The service ended without being told to (Android ended it). */
    internal fun serviceEnded(s: OfflineDownloadService) {
        if (service !== s) return
        service = null
        wanted = false
    }

    /** Stop in the notification. Before it reaches the manager, nothing more may run in the background. */
    internal fun stopPressed() {
        if (!AppLifecycle.isForeground) stopping = true
        onStop?.invoke()
    }

    /** dataSync's time is used up (API 35): as iOS ending the task. The service has to stop now; in the background the
     *  download stops too (`onExpire`), with SatMap on screen it goes on without the service. */
    internal fun serviceTimedOut(s: OfflineDownloadService) {
        if (service === s) {
            service = null
            wanted = false
            onExpire?.invoke()
        }
        ServiceCompat.stopForeground(s, ServiceCompat.STOP_FOREGROUND_REMOVE)
        s.stopSelf()
    }

    // The notification

    /** The progress notification (the service's foreground notification): the area's name, `progressText`, and a bar
     *  over every area downloaded since the service started, never going back. */
    fun notification(): Notification {
        createChannel()
        // Whole tiles, never going back. The bar takes an Int: large totals are scaled down.
        val total = max(this.total, 0L)
        shownDone = max(shownDone, min(done, total))
        val scale = if (total > Int.MAX_VALUE) ceil(total.toDouble() / Int.MAX_VALUE).toLong() else 1L
        val max = (total / scale).toInt()
        val shown = (shownDone / scale).toInt()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, OfflineDownloadService::class.java).setAction(OfflineDownloadService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setColor(0xFFFFBD29.toInt()) // the AccentColor
            .setContentTitle(title)
            .setContentText(subtitle)
            .setProgress(max, shown, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .build()
    }

    /** Shows the progress, at most once a second. */
    @SuppressLint("MissingPermission") // without the permission Android drops it, and the service runs all the same
    private fun post() {
        if (service == null) return
        val now = SystemClock.uptimeMillis()
        val wait = postedAt + 1000 - now
        if (wait > 0) {
            if (!postPending) {
                postPending = true
                main.postDelayed(postLater, wait)
            }
            return
        }
        postPending = false
        postedAt = now
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification())
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyReady() {
        createChannel()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setColor(0xFFFFBD29.toInt())
            .setContentTitle(title)
            .setContentText("Ready offline")
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(8000)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(READY_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    private fun createChannel() {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_downloads))
                .setShowBadge(false)
                .build(),
        )
    }
}
