package com.jacobusoettle.satmap.offline

import android.content.Context
import android.net.Uri
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The offline areas: the list (`Offline/areas.json`), the download queue and its runs, backup export and restore.
 *  Main thread only, as the Swift. */
class OfflineManager(private val context: Context) : AppLifecycle.Observer {
    companion object {
        /** Downloads stop with less than this free (500 MiB). */
        const val minimumFreeSpace: Long = 500L shl 20
    }

    /** Keeps downloads going in the background (the foreground service and its notification). */
    val background = BackgroundDownload(context)

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()
    private val _activeID = MutableStateFlow<UUID?>(null)
    val activeID: StateFlow<UUID?> = _activeID.asStateFlow()

    /** +1 after each run that stored tiles, and after a restore: the map reloads its tiles. */
    private val _completedRevision = MutableStateFlow(0)
    val completedRevision: StateFlow<Int> = _completedRevision.asStateFlow()

    /** What happens to downloads in the background (the footer of "Offline areas"); null until the first download. */
    private val _backgroundStatus = MutableStateFlow<String?>(null)
    val backgroundStatus: StateFlow<String?> = _backgroundStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()
    private val _backupProgress = MutableStateFlow<Double?>(null)
    val backupProgress: StateFlow<Double?> = _backupProgress.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** A new area, appended to the list as queued. */
    fun add(
        name: String,
        shape: CoverageShape,
        roads: List<List<GeoPoint>>?,
        preset: String?,
        maxZoom: Int,
        allowCellular: Boolean,
    ): OfflineArea {
        // M4: appended, saved, published to the map, and the queue started.
        return OfflineArea(
            id = UUID.randomUUID(), name = name, created = System.currentTimeMillis() / 1000.0, shape = shape,
            roads = roads, preset = preset, maxZoom = maxZoom, allowCellular = allowCellular,
            status = OfflineArea.Status.queued, failedTiles = 0, bytesOnDisk = 0,
        )
    }

    fun updateTrackArea(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean) {
        // M4: offline spec §1.9 item 5.
    }

    fun pause(id: UUID) {
        // M4: paused at once; a running download is cancelled.
    }

    fun resume(id: UUID) {
        // M4: queued, failures cleared, the queue started.
    }

    fun delete(id: UUID) {
        // M4: removed, the map's readers closed, the folder deleted (after its run ends).
    }

    fun changeMaxZoom(id: UUID, zoom: Int) {
        // M4: offline spec §1.2.
    }

    fun setAllowCellular(id: UUID, allowed: Boolean) {
        // M4: offline spec §1.2.
    }

    /** Measures each area's folder off the main thread, then saves once. */
    fun refreshSizes() {
        // M4: AppPaths.size per area.
    }

    /** The deepest imagery zoom stored for the area (a read-only store, off the main thread). */
    suspend fun savedImageryDepth(id: UUID): Int? = null // M4: MBTiles.maxStoredZoom.

    /** The network allows this area's download now. */
    fun canDownloadNow(a: OfflineArea): Boolean {
        val net = Connectivity.shared.state.value
        return net.isOnline && (a.allowCellular || !net.isExpensive)
    }

    fun export(ids: List<UUID>) {
        // M4: offline spec §1.13 and §2.8 (zero-copy share through BackupFileProvider).
    }

    fun restore(uris: List<Uri>) {
        // M4: offline spec §1.13 and §2.8.
    }

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    /** The share flow returned: downloads go on (an area paused for the export resumes). */
    fun finishExport() {
        // M4: holdDownloads = false, resume or startNextIfIdle.
    }

    /** The notification's Stop: pauses the active area in the foreground, else as backgroundTimeEnded. */
    fun stopFromNotification() {
        // M4: offline spec §2.5.
    }

    /** The background time is used up (or Stop in the background): the area goes back to queued and continues when
     *  SatMap is opened. */
    fun backgroundTimeEnded() {
        // M4: offline spec §1.6.
    }

    override fun willEnterForeground() {
        // M4: ends the signal wait and the extra background time, networkChanged().
    }

    override fun didBecomeActive() {
        // M4: background time for a run already going on.
    }

    override fun didEnterBackground() {
        // M4: background.enteredBackground().
    }
}
