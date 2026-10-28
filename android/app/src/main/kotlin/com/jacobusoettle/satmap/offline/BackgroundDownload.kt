package com.jacobusoettle.satmap.offline

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.jacobusoettle.satmap.R
import java.util.UUID

/** The Swift `BackgroundDownload` API, backed by `OfflineDownloadService` (a foreground service of type dataSync):
 *  downloads keep going when SatMap is left, with their progress in a notification. It owns that notification and
 *  `status`. */
class BackgroundDownload(private val context: Context) {
    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2
        const val READY_NOTIFICATION_ID = 3
    }

    /** Called when the background time ends without the service (the start was refused): iOS's expiration. */
    var onExpire: (() -> Unit)? = null

    /** What happens to downloads in the background (offline spec §2.5 texts). */
    var status: String? = null
        private set
    var onStatusChange: ((String?) -> Unit)? = null

    /** The service is running and not stopping. */
    val allowsBackgroundWork: Boolean get() = false // M4: OfflineDownloadService.running && !stopping.

    private var title = ""
    private var subtitle = ""

    fun begin(title: String, subtitle: String) {
        this.title = title
        this.subtitle = subtitle
        // M4: starts OfflineDownloadService when in the foreground; sets status.
    }

    fun update(area: UUID, title: String, subtitle: String, done: Long, total: Long) {
        this.title = title
        this.subtitle = subtitle
        // M4: the carried-over progress, at most once a second.
    }

    fun enteredBackground() {
        // M4: without the service, a 25 s timer that calls onExpire.
    }

    fun enteredForeground() {
        // M4: cancels that timer.
    }

    fun end(success: Boolean) {
        // M4: stops the service; "Ready offline" on success.
    }

    /** The progress notification (the service's foreground notification). */
    fun notification(): Notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(title)
        .setContentText(subtitle)
        .setOngoing(true)
        .setSilent(true)
        .build() // M4: channel, colour, progress, Stop action, content intent (offline spec §2.5).
}
