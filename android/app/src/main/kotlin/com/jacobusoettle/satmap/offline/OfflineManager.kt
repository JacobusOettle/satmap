package com.jacobusoettle.satmap.offline

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadLayer
import com.jacobusoettle.satmap.core.DownloadOutcome
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.OfflineBackup
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.SqlException
import com.jacobusoettle.satmap.core.SqlResult
import com.jacobusoettle.satmap.core.Terrarium
import com.jacobusoettle.satmap.core.TileDownloadJob
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.map.HTTPTileFetcher
import com.jacobusoettle.satmap.map.TileRepository
import com.jacobusoettle.satmap.ui.SharePresenter
import com.jacobusoettle.satmap.ui.ShareRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Offline areas: each is a shape (lasso or KML) downloaded as satellite imagery up to `maxZoom` plus elevation tiles
 *  for contours and height differences. Downloads run one at a time, resume where they stopped, wait for the network
 *  (or Wi-Fi) and continue after the app restarts. Also backup export and restore. Used on the main thread only.
 *
 *  The parameters after `context` are what the manager uses of the phone and the rest of the app; tests pass their
 *  own. */
class OfflineManager(
    private val context: Context,
    /** The network: whether an area may download now (`canDownloadNow`), and the changes that start waiting ones. */
    private val network: StateFlow<Connectivity.State> = Connectivity.shared.state,
    /** The space free for the app's files, or null when it can't be told. Asked on `io` only (see `freeSpace`). */
    private val freeDiskSpace: () -> Long? = { AppPaths.freeDiskSpace() },
    /** The fetcher of one layer's tiles: their URLs, and whether the run may only use Wi-Fi. */
    private val fetcher: (url: (TileID) -> String, wifiOnly: Boolean) -> TileFetching = ::downloadFetcher,
    /** Where a run downloads and the files are measured, off the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The map's readers of the areas' files. */
    private val readers: AreaReaders = TileRepositoryReaders,
) : AppLifecycle.Observer {
    /** The map's readers of the areas' files (`TileRepository`): told of the areas, and of one deleted. */
    interface AreaReaders {
        fun setAreas(refs: List<TileRepository.AreaRef>)
        fun closeArea(id: UUID)
    }

    companion object {
        /** Downloads stop with less than this free (500 MiB). */
        const val minimumFreeSpace: Long = 500L shl 20

        /** Swift's text, with "iPhone" changed to "phone". */
        const val lowSpaceProblem = "Less than 500 MB free on the phone. Free up space, then tap ↻."

        /** A folder named as Swift's `UUID(uuidString:)` reads one (in either case). */
        private val uuidName = Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")

        /** The download's progress in a few words, for the system's progress bar (the notification). */
        fun progressText(p: DownloadProgress): String {
            val percent = if (p.overallTotal > 0) (p.overallDone.toDouble() / p.overallTotal.toDouble() * 100).toInt() else 0
            return "${p.layer} · zoom ${p.zoom} of ${p.maxZoom} · $percent%"
        }

        /** The restore picker's types (several files at once): any file. MBTiles has no registered type, providers
         *  report octet-stream, vnd.sqlite3 and others, and the files say themselves what they are (DESIGN.md D13). */
        val restoreTypes = arrayOf("*/*")

        private val backupLayers = listOf(
            OfflineBackup.Layer(fileName = TileRepository.Layer.imagery.fileName, key = "imagery", label = "satellite"),
            OfflineBackup.Layer(fileName = TileRepository.Layer.terrain.fileName, key = "terrain", label = "elevation"),
        )

        /** Core's `OfflineBackup.export` without its copy (offline spec §2.8 asks core for this as
         *  `OfflineBackup.prepareExport`; until core has it, it is here): writes the area's settings into its tile
         *  files and folds their write-ahead logs into them, so each file holds everything by itself, and returns the
         *  files with the names they are shared under ("Farm - satellite.mbtiles"). Layers not downloaded yet are
         *  skipped. `usedNames` keeps the names unique (" (2)", compared without case).
         *
         *  `beingRead` (Android only): another app still reads the area's files, from an earlier export. iOS writes
         *  into the area's files and shares clones, which never change under a reader of an earlier export; here the
         *  shared files are the area's own, so nothing is written into them then. They are shared as they are if they
         *  already hold these settings and everything is in the file (`isExported`); otherwise the area is busy. */
        internal fun prepareExport(areaFolder: File, layers: List<OfflineBackup.Layer>, name: String, areaJSON: String,
                                   usedNames: MutableSet<String>, beingRead: Boolean = false): List<Pair<File, String>> {
            var base = OfflineBackup.fileSafe(name)
            var n = 2
            while (base.lowercase() in usedNames) {
                base = "${OfflineBackup.fileSafe(name)} ($n)"
                n += 1
            }
            usedNames.add(base.lowercase())

            val out = mutableListOf<Pair<File, String>>()
            for (layer in layers) {
                val source = File(areaFolder, layer.fileName)
                if (!source.exists()) continue
                val ready = if (beingRead) {
                    isExported(source, layer.key, areaJSON)
                } else {
                    val store = MBTiles(source, create = false)
                    store.setMetadata(mapOf(OfflineBackup.areaKey to areaJSON, OfflineBackup.layerKey to layer.key))
                    val done = store.checkpoint()
                    store.close()
                    done
                }
                if (!ready) throw OfflineBackup.Failure.Busy(name)
                out.add(Pair(source, "$base - ${layer.label}.mbtiles"))
            }
            return out
        }

        /** Whether `file` already holds everything by itself (nothing in a write-ahead log) and the settings `areaJSON`
         *  and `layerKey`, the size aside (a restore measures it again). Read through a read-only connection, which
         *  never writes to the file. */
        private fun isExported(file: File, layerKey: String, areaJSON: String): Boolean {
            if (File(file.path + "-wal").length() > 0) return false
            val db = try {
                AndroidSqlDriver(readOnly = true).open(file.path, create = false)
            } catch (e: SqlException) {
                return false
            }
            try {
                db.busyTimeout(10_000)
                val s = db.prepare("SELECT name, value FROM metadata WHERE name IN (?, ?)") ?: return false
                val stored = HashMap<String, String?>()
                try {
                    s.bindText(1, OfflineBackup.areaKey)
                    s.bindText(2, OfflineBackup.layerKey)
                    while (s.step() == SqlResult.ROW) {
                        val key = s.columnText(0) ?: continue
                        stored[key] = s.columnText(1)
                    }
                } finally {
                    s.close()
                }
                if (stored[OfflineBackup.layerKey] != layerKey) return false
                val old = stored[OfflineBackup.areaKey] ?: return false
                return try {
                    val a = OfflineArea.fromJson(Json.parseToJsonElement(old))
                    val b = OfflineArea.fromJson(Json.parseToJsonElement(areaJSON))
                    a.copy(bytesOnDisk = 0) == b.copy(bytesOnDisk = 0)
                } catch (e: Exception) {
                    false
                }
            } finally {
                db.close()
            }
        }
    }

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()
    private val _activeID = MutableStateFlow<UUID?>(null)
    val activeID: StateFlow<UUID?> = _activeID.asStateFlow()

    /** Bumped whenever a download run ends, so the map can reload tiles that weren't available before. */
    private val _completedRevision = MutableStateFlow(0)
    val completedRevision: StateFlow<Int> = _completedRevision.asStateFlow()
    private var inForeground = AppLifecycle.isForeground

    /** Lets a download carry on in the background (the foreground service, with its progress in a notification). */
    val background = BackgroundDownload(context)

    /** What happens to downloads in the background, for the Offline maps screen (null until the first download). */
    private val _backgroundStatus = MutableStateFlow<String?>(null)
    val backgroundStatus: StateFlow<String?> = _backgroundStatus.asStateFlow()

    /** Whether the last run downloaded its whole area, for the system's progress bar as it closes. */
    private var lastRunComplete = false

    private var task: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val indexFile = File(AppPaths.offline, "areas.json")

    /** What a backup task is doing ("Preparing export…"), or null. One runs at a time. */
    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    /** How far it has got (0…1), when it can tell: copying the files of a restore. */
    private val _backupProgress = MutableStateFlow<Double?>(null)
    val backupProgress: StateFlow<Double?> = _backupProgress.asStateFlow()

    /** The outcome of the last backup task, for an alert; cleared by `clearBackupMessage`. */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** From an export starting until its share sheet closes no download starts: an exported area's files must not
     *  change while they are prepared or copied. Android: the export holding them, so that only its own share sheet's
     *  closing ends the hold, never a late one of an earlier export's. */
    private var exportHold: Any? = null

    /** A "Save to a folder" copy runs, which holds downloads too (Android only: it comes after the chooser closed). */
    private var copyHold = false

    private val holdDownloads: Boolean get() = exportHold != null || copyHold

    /** The download paused for an export (or a copy into a folder), resumed when the hold ends (`releaseHold`). */
    private var pausedForExport: UUID? = null

    /** Downloads paused because another app opened their area's exported files, resumed when the last one closes
     *  (Android only). */
    private val pausedForReaders = HashSet<UUID>()

    private var networkRetryDelay = 15.0

    /** Set while SatMap, in the background, waits for the signal to come back before giving up its background time:
     *  a few seconds without signal (a dip, a dead spot) shouldn't stop the download until SatMap is opened. */
    private var signalWait: UUID? = null

    private var lowSpaceStop: UUID? = null

    /** The free space last measured (null until then, or when it can't be told), for `startNextIfIdle`. The Swift asks
     *  there, on the main thread, as iOS answers at once; Android's answer (`getAllocatableBytes`, DESIGN.md D16) is a
     *  call into the system's storage service, which mustn't hold up the main thread. So it is measured on `io`: at
     *  start, by each run (every 10 s, and as it ends), when SatMap comes to the front, after a delete, and after a
     *  stop for low space. A value that is too high only lets a run start, which then stops at its first check; one
     *  that is too low (space freed by another app while SatMap stayed in front) refuses one ↻ and measures again, so
     *  the next ↻ starts. */
    @Volatile
    private var freeSpace: Long? = null

    init {
        val data = try {
            AtomicFile(indexFile).readFully()
        } catch (e: IOException) {
            null
        }
        if (data != null) {
            val saved = try {
                Json.parseToJsonElement(String(data, Charsets.UTF_8)).jsonArray.map(OfflineArea::fromJson)
            } catch (e: Exception) {
                null
            }
            if (saved != null) {
                // A download that was running when the app was closed continues.
                _areas.value = saved.map { a ->
                    if (a.status == OfflineArea.Status.downloading) a.copy(status = OfflineArea.Status.queued) else a
                }
                removeOrphanedDirectories()
                // Left over from a restore interrupted by the app closing (can be GBs). (iOS also removes its Export
                // folder; Android shares the areas' own files and has none.)
                AppPaths.restoreFolder.deleteRecursively()
            } else {
                // Never overwrite an index we can't read (that would orphan every downloaded area).
                indexFile.renameTo(File(indexFile.path + ".unreadable-${System.currentTimeMillis() / 1000}"))
            }
        }
        publishToMap()
        // The road sets shipped with the app (Kruger) are read from its assets.
        RoadPresets.init(context)
        // Registered here, as the Swift does in its init: AppModel creates the recorder, then this, before itself, so
        // the three are told in that order (DESIGN.md §2.3).
        AppLifecycle.addObserver(this)
        background.onExpire = { backgroundTimeEnded() }
        background.onStop = { stopFromNotification() }
        background.onStatusChange = { _backgroundStatus.value = it }
        measureFreeSpace() // before anything starts
        // On the main thread after the change, as the Swift's hop to the main queue (DESIGN.md §2.4 rule 2). A
        // StateFlow gives each new state once, and the current one first.
        scope.launch(Dispatchers.Main) { network.collect { networkChanged() } }
        // Other apps reading exported files (Android only, see `BackupFileProvider`).
        scope.launch(Dispatchers.Main) {
            var before = emptyMap<UUID, Int>()
            ExportShares.readerCounts.collect { now ->
                val opened = now.keys - before.keys
                val closed = before.keys - now.keys
                before = now
                for (id in opened) readerOpened(id)
                if (closed.isNotEmpty()) readersClosed(closed)
            }
        }
        post { startNextIfIdle() }
    }

    // Public actions

    fun add(
        name: String,
        shape: CoverageShape,
        roads: List<List<GeoPoint>>?,
        preset: String?,
        maxZoom: Int,
        allowCellular: Boolean,
    ): OfflineArea {
        val area = OfflineArea(
            id = UUID.randomUUID(), name = name, created = System.currentTimeMillis() / 1000.0, shape = shape,
            roads = roads, preset = preset, maxZoom = maxZoom, allowCellular = allowCellular,
            status = OfflineArea.Status.queued, failedTiles = 0, bytesOnDisk = 0, problem = null,
        )
        _areas.value = _areas.value + area
        save()
        publishToMap()
        startNextIfIdle()
        return area
    }

    /** Downloads imagery along your recorded track (with `Roads.corridorMeters` each side) and elevation under it,
     *  into one area that is updated each time: the new parts of the track are added and what is saved is skipped. */
    fun updateTrackArea(name: String, lines: List<List<GeoPoint>>, maxZoom: Int, allowCellular: Boolean) {
        val shape = Roads.corridor(lines)
        val existing = _areas.value.firstOrNull { it.isTrack == true }
        if (existing == null) {
            val area = add(name = name, shape = shape, roads = lines, preset = null, maxZoom = maxZoom,
                           allowCellular = allowCellular)
            update(area.id) { it.isTrack = true }
            return
        }
        update(existing.id) {
            it.name = name
            it.shape = shape
            it.roads = lines
            it.maxZoom = maxZoom
            it.allowCellular = allowCellular
            it.status = OfflineArea.Status.queued
            it.failedTiles = 0
            it.problem = null
        }
        publishToMap()
        if (_activeID.value == existing.id) {
            task?.cancel() // the run ends as cancelled; the area stays queued and starts again with the new track
        } else {
            startNextIfIdle()
        }
    }

    /** Lets an area download over mobile data, or makes it wait for Wi-Fi again. A download under way starts again on
     *  the new terms (what is saved is kept); one waiting for Wi-Fi goes on over mobile data. */
    fun setAllowCellular(id: UUID, allowed: Boolean) {
        if (_areas.value.none { it.id == id && it.allowCellular != allowed }) return
        update(id) {
            it.allowCellular = allowed
            if (it.status == OfflineArea.Status.waitingForNetwork) it.status = OfflineArea.Status.queued
        }
        if (_activeID.value == id) {
            // The run's connection was chosen for the old setting.
            update(id) { if (it.status == OfflineArea.Status.downloading) it.status = OfflineArea.Status.queued }
            task?.cancel()
        } else {
            startNextIfIdle()
        }
    }

    /** Downloads an area to a different (deeper) zoom level. Tiles already saved are kept and skipped, so only the new
     *  levels are fetched. */
    fun changeMaxZoom(id: UUID, zoom: Int) {
        if (_areas.value.none { it.id == id && it.maxZoom != zoom }) return
        update(id) {
            it.maxZoom = zoom
            it.status = OfflineArea.Status.queued
            it.failedTiles = 0
            it.problem = null
        }
        if (_activeID.value == id) {
            task?.cancel() // the run ends as cancelled; the area stays queued and starts again with the new level
        } else {
            startNextIfIdle()
        }
    }

    /** The deepest zoom level with satellite imagery saved for an area (null if none yet). Esri's imagery stops at
     *  different levels in different places, so this can be less than the level asked for. A one-off reader: the file
     *  is opened read-only (`AndroidSqlDriver(readOnly = true)`, DESIGN.md D17), not through `MBTiles`, whose one
     *  driver opens for writing. The query is `MBTiles.maxStoredZoom`'s. */
    suspend fun savedImageryDepth(id: UUID): Int? {
        val area = _areas.value.firstOrNull { it.id == id } ?: return null
        val file = File(area.directory, TileRepository.Layer.imagery.fileName)
        return withContext(io) {
            if (!file.exists()) return@withContext null
            val db = try {
                AndroidSqlDriver(readOnly = true).open(file.path, create = false)
            } catch (e: SqlException) {
                return@withContext null
            }
            try {
                db.busyTimeout(10_000)
                val s = db.prepare("SELECT MAX(zoom_level) FROM tiles") ?: return@withContext null
                try {
                    if (s.step() == SqlResult.ROW && !s.columnIsNull(0)) s.columnInt(0) else null
                } finally {
                    s.close()
                }
            } finally {
                db.close()
            }
        }
    }

    fun pause(id: UUID) {
        if (_activeID.value == id) {
            task?.cancel() // the run loop marks it paused when the job stops
        }
        update(id) { it.status = OfflineArea.Status.paused }
    }

    fun resume(id: UUID) {
        update(id) {
            it.status = OfflineArea.Status.queued
            it.failedTiles = 0
            it.problem = null
        }
        startNextIfIdle()
    }

    fun delete(id: UUID) {
        // Another app may be reading the area's files (an export): forget its shared files, as iOS removes its
        // exported copies, which share the area's storage. (An app reading one keeps its open file; the space is
        // freed when it closes it.)
        ExportShares.forget(id)
        pausedForReaders.remove(id)
        val wasActive = _activeID.value == id
        val running = task
        _areas.value = _areas.value.filter { it.id != id }
        save()
        publishToMap()
        readers.closeArea(id)
        val dir = File(AppPaths.offline, id.toString().uppercase())
        // The space it held is free now (see `freeSpace`).
        if (wasActive && running != null) {
            running.cancel()
            scope.launch {
                running.join()
                dir.deleteRecursively()
                measureFreeSpace()
            }
        } else {
            dir.deleteRecursively()
            measureFreeSpace()
        }
    }

    /** Measures each area's folder off the main thread, then saves once. */
    fun refreshSizes() {
        val snapshot = _areas.value
        scope.launch {
            val sizes = withContext(io) { snapshot.associate { it.id to AppPaths.size(it.directory) } }
            for ((id, size) in sizes) update(id, persist = false) { it.bytesOnDisk = size }
            save()
        }
    }

    // Backup

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    /** Exports areas: pauses a download running in one of them, writes each area's settings into its files (satellite
     *  and elevation) and opens the share sheet. Its own presentation, so it works even if the Offline maps sheet is
     *  closed meanwhile. iOS shares clones of the files; Android shares the files themselves, read-only, as copying a
     *  15 GB area isn't possible (`BackupFileProvider`). */
    fun export(ids: List<UUID>) {
        if (_backupStatus.value != null) return
        _backupStatus.value = "Preparing export…"
        val hold = Any()
        exportHold = hold
        prepareExport(ids) { result ->
            _backupStatus.value = null
            result.fold(
                onSuccess = { uris ->
                    if (uris.isNotEmpty()) {
                        SharePresenter.present(ShareRequest(uris, saveToFolder = ::saveToFolder) { finishExport(hold) })
                    } else {
                        finishExport(hold)
                        _backupMessage.value = "Nothing has been downloaded yet to export."
                    }
                },
                onFailure = { e ->
                    finishExport(hold)
                    _backupMessage.value = when (e) {
                        is OfflineBackup.Failure -> e.errorDescription
                        is MBTiles.StoreError -> e.errorDescription
                        else -> "Couldn't export."
                    }
                },
            )
        }
    }

    private fun prepareExport(ids: List<UUID>, completion: (Result<List<Uri>>) -> Unit) {
        val chosen = _areas.value.filter { it.id in ids }
        var waitFor: Job? = null
        val active = _activeID.value
        val running = task
        if (active != null && active in ids && running != null) {
            pause(active)
            pausedForExport = active
            waitFor = running
        }
        class Item(val id: UUID, val folder: File, val name: String, val json: String)
        val items = chosen.map { a ->
            val saved = a.copy(status = if (a.status == OfflineArea.Status.complete) OfflineArea.Status.complete else OfflineArea.Status.paused)
            Item(a.id, a.directory, a.name, saved.toJson().toString())
        }
        // The files of the export before are no longer offered (iOS removes its export folder).
        ExportShares.clear()
        scope.launch {
            waitFor?.join()
            val result = withContext(io) {
                runCatching {
                    val used = HashSet<String>()
                    items.flatMap { item ->
                        // (No new reader can open meanwhile: the shares were cleared above.)
                        val beingRead = ExportShares.readers(item.id) > 0
                        prepareExport(item.folder, backupLayers, item.name, item.json, used, beingRead).map { (file, name) ->
                            ExportShares.Share(area = item.id, file = file, displayName = name)
                        }
                    }
                }
            }
            completion(result.mapCatching { shares ->
                shares.map { s ->
                    val uri = BackupFileProvider.uriFor(context, s.file, s.displayName)
                    ExportShares.register(uri, s)
                    uri
                }
            })
        }
    }

    /** The share sheet of the export holding downloads closed: downloads may run again, including one paused for the
     *  export. The shared files stay readable until the next export, a deleted area or the next launch, in case the
     *  system is still sending them; while another app reads them, their area doesn't download (`readerOpened`). The
     *  closing of an earlier export's share sheet, after a later export has taken the hold, changes nothing. */
    private fun finishExport(hold: Any) {
        if (exportHold !== hold) return
        exportHold = null
        releaseHold()
    }

    /** Neither an export nor a copy holds downloads any more: they may run again, including one paused for them. */
    private fun releaseHold() {
        if (holdDownloads) return
        val id = pausedForExport
        pausedForExport = null
        if (id != null && _areas.value.any { it.id == id && it.status == OfflineArea.Status.paused }) {
            resume(id)
        } else {
            startNextIfIdle()
        }
    }

    /** "Save to a folder" in the share sheet (Android only: stock Android has no "Save to Files"): copies the export's
     *  files into the folder picked, a USB drive or a folder on the phone. Downloads are held during the copy, as iOS
     *  holds them until Save to Files has written the files: the chooser may have closed before the folder was picked
     *  (ending the export's hold), so an exported area that started downloading again is paused, and the copy waits
     *  for its run to end, so nothing writes to the files while they are copied. The copied areas count as read until
     *  the copy ends. */
    private fun saveToFolder(tree: Uri) {
        val files = ExportShares.all()
        if (_backupStatus.value != null || files.isEmpty()) return
        _backupStatus.value = copyingText(0, files.sumOf { it.file.length() })
        _backupProgress.value = 0.0
        copyHold = true
        val areas = files.map { it.area }.toSet()
        var waitFor: Job? = null
        val active = _activeID.value
        val running = task
        if (active != null && active in areas && running != null) {
            pause(active)
            pausedForExport = active
            waitFor = running
        }
        for (id in areas) ExportShares.opened(id)
        scope.launch {
            waitFor?.join()
            val total = files.sumOf { it.file.length() } // as the run left them
            val report = progressReporter(total)
            val failed = withContext(io) { BackupSharing.copyInto(context, tree, files, report) }
            for (id in areas) ExportShares.closed(id)
            _backupStatus.value = null
            _backupProgress.value = null
            if (failed.isNotEmpty()) _backupMessage.value = failed.joinToString("\n") { "Couldn't save $it." }
            copyHold = false
            releaseHold()
        }
    }

    /** Adds the areas in exported files (normally both files of each area; the satellite file alone also works, and ↻
     *  then downloads the elevation). The files can come from any app or folder (Google Drive, a computer, e-mail …),
     *  under any name: they say themselves which area and layer they are. The summary for the user ends up in
     *  `backupMessage`. */
    fun restore(uris: List<Uri>) {
        if (_backupStatus.value != null || uris.isEmpty()) return
        _backupStatus.value = "Restoring…"
        val known = _areas.value.map { it.id }.toSet()
        scope.launch {
            val (restored, problems) = withContext(io) { restoreFiles(uris, known) }
            val added = restored.filter { a -> _areas.value.none { it.id == a.id } }
            _areas.value = _areas.value + added
            save()
            publishToMap()
            if (added.isNotEmpty()) _completedRevision.value += 1
            val lines = mutableListOf<String>()
            if (added.isNotEmpty()) lines += "Restored ${added.joinToString(", ") { it.name }}."
            if (added.any { it.status != OfflineArea.Status.complete }) {
                lines += "Tap ↻ on an area marked Paused to download what its backup didn't include."
            }
            lines += problems
            _backupStatus.value = null
            _backupProgress.value = null
            _backupMessage.value = if (lines.isEmpty()) "Nothing to restore." else lines.joinToString("\n")
        }
    }

    /** `restore`'s work, on `io`: the restored areas (their files in place) and the problems, for the message. */
    private fun restoreFiles(uris: List<Uri>, known: Set<UUID>): Pair<List<OfflineArea>, List<String>> {
        val cr = context.contentResolver
        val staging = AppPaths.restoreFolder
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            // The files' names and sizes, for the messages and the progress bar (they can be gigabytes, from Google
            // Drive or a USB drive). A size the providing app doesn't tell counts as 0.
            val described = uris.map { uri ->
                var name: String? = null
                var size = 0L
                try {
                    cr.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val s = c.getColumnIndex(OpenableColumns.SIZE)
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                            if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        }
                    }
                } catch (e: Exception) {
                    // Neither is told: the name of the address, and no progress for it.
                }
                Pair(name?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment ?: uri.toString(), max(0L, size))
            }
            val total = described.sumOf { it.second }
            var before = 0L
            val report = progressReporter(total)
            report(0)

            val problems = mutableListOf<String>()
            // Copy each file in first: a file of another app can't be opened in place.
            val found = LinkedHashMap<UUID, Pair<OfflineArea, HashMap<String, File>>>()
            for ((i, uri) in uris.withIndex()) {
                val (name, size) = described[i]
                val local = File(staging, "$i.mbtiles")
                val copied = try {
                    val input = cr.openInputStream(uri) ?: throw IOException("No stream")
                    input.use { FileOutputStream(local).use { out -> BackupSharing.copy(input, out) { report(before + it) } } }
                    true
                } catch (e: Exception) {
                    local.delete() // not left taking space while the others are copied
                    false
                }
                before += size
                report(before)
                if (!copied) {
                    problems += "Couldn't copy $name (is there enough free space?)."
                    continue
                }
                try {
                    val info = OfflineBackup.inspect(local)
                    val area = OfflineArea.fromJson(Json.parseToJsonElement(String(info.areaJSON, Charsets.UTF_8)))
                    found.getOrPut(area.id) { Pair(area, HashMap()) }.second[info.layerKey] = local
                } catch (e: Exception) {
                    // Named as the user knows the file (Swift's `inspect` names the copy, "0.mbtiles").
                    problems += (e as? MBTiles.StoreError)?.errorDescription ?: "$name isn't a SatMap backup."
                }
            }

            val restored = mutableListOf<OfflineArea>()
            for ((id, item) in found) {
                val (saved, files) = item
                if (id in known) {
                    problems += "${saved.name} is already in SatMap."
                    continue
                }
                val folder = AppPaths.offlineArea(id)
                var complete = true
                for (layer in backupLayers) {
                    val target = File(folder, layer.fileName)
                    target.delete()
                    val file = files[layer.key]
                    if (file != null && move(file, target)) continue
                    complete = false
                }
                restored += saved.copy(
                    status = if (complete && saved.status == OfflineArea.Status.complete) OfflineArea.Status.complete else OfflineArea.Status.paused,
                    problem = null,
                    bytesOnDisk = AppPaths.size(folder),
                )
            }
            return Pair(restored, problems)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Moves a file within the app's storage (one file system), or copies it when that can't be done. */
    private fun move(file: File, target: File): Boolean {
        if (file.renameTo(target)) return true
        return try {
            file.copyTo(target, overwrite = true)
            file.delete()
            true
        } catch (e: IOException) {
            target.delete()
            false
        }
    }

    /** Shows how far a copy of `total` bytes has got ("Copying 1.2 GB of 3.4 GB…"), at most every 0.1 s and at the end.
     *  Called on `io`; the texts are set on the main thread. */
    private fun progressReporter(total: Long): (Long) -> Unit {
        var shown = SystemClock.elapsedRealtime()
        return report@{ done ->
            val now = SystemClock.elapsedRealtime()
            if (total <= 0 || !(now - shown > 100 || done >= total)) return@report
            shown = now
            val text = copyingText(done, total)
            val share = min(1.0, done.toDouble() / total.toDouble())
            post {
                // (Not once the task has ended.)
                if (_backupStatus.value != null) {
                    _backupStatus.value = text
                    _backupProgress.value = share
                }
            }
        }
    }

    private fun copyingText(done: Long, total: Long) = "Copying ${Format.bytes(min(done, total))} of ${Format.bytes(total)}…"

    // Other apps reading exported files

    /** Another app opened one of an area's shared files: while it reads, the area doesn't download, so the file stays
     *  as it was exported (iOS shares a clone, which a download can't change). */
    private fun readerOpened(id: UUID) {
        if (_activeID.value == id && task != null) {
            pause(id)
            pausedForReaders += id
        }
    }

    /** The last reader of these areas closed: a download paused for them goes on, and the queue moves. */
    private fun readersClosed(ids: Set<UUID>) {
        for (id in ids) {
            if (pausedForReaders.remove(id) && _areas.value.any { it.id == id && it.status == OfflineArea.Status.paused }) {
                resume(id)
            }
        }
        startNextIfIdle()
    }

    /** Deletes area folders that no longer belong to an area (e.g. the app was closed while deleting one). */
    private fun removeOrphanedDirectories() {
        val known = _areas.value.map { it.id.toString().uppercase() }.toSet()
        for (f in AppPaths.offline.listFiles() ?: emptyArray()) {
            if (f.isDirectory && uuidName.matches(f.name) && f.name !in known) f.deleteRecursively()
        }
    }

    // Running downloads

    /** The phone may report "online" while requests fail (router up, internet down), and no network change will be
     *  announced when it recovers, so waiting areas are retried periodically with growing gaps. */
    private fun scheduleNetworkRetry() {
        val wait = networkRetryDelay
        networkRetryDelay = min(networkRetryDelay * 2, 600.0)
        scope.launch {
            delay((wait * 1000).toLong())
            if (_areas.value.none { it.status == OfflineArea.Status.waitingForNetwork }) return@launch
            if (task != null) {
                scheduleNetworkRetry() // another area is downloading; check again later
            } else {
                networkChanged()
            }
        }
    }

    /** Whether an area may download on the current network. */
    fun canDownloadNow(a: OfflineArea): Boolean {
        val net = network.value
        return net.isOnline && (a.allowCellular || !net.isExpensive)
    }

    private fun networkChanged() {
        for (a in _areas.value) {
            if (a.status == OfflineArea.Status.waitingForNetwork && canDownloadNow(a)) {
                update(a.id) { it.status = OfflineArea.Status.queued }
            }
        }
        startNextIfIdle()
    }

    override fun didEnterBackground() {
        inForeground = false
        // A running download carries on, in the foreground service.
        if (_activeID.value != null) background.enteredBackground()
    }

    override fun willEnterForeground() {
        inForeground = true
        signalWait = null
        background.enteredForeground()
        measureFreeSpace() // space may have been freed meanwhile (see `freeSpace`)
        networkChanged()
    }

    /** A download that started or went on while SatMap wasn't fully open gets its background time now. */
    override fun didBecomeActive() {
        val id = _activeID.value ?: return
        val a = _areas.value.firstOrNull { it.id == id } ?: return
        background.begin(title = a.name, subtitle = _progress.value?.let(::progressText) ?: "Starting…")
    }

    /** The background time ended (dataSync's time is used up, or the start of the service was refused), or the
     *  download was stopped from the notification with SatMap in the background: it stops and continues from where
     *  it was when SatMap is opened again. */
    fun backgroundTimeEnded() {
        if (inForeground) return
        val id = _activeID.value ?: return
        update(id) { if (it.status == OfflineArea.Status.downloading) it.status = OfflineArea.Status.queued }
        task?.cancel()
    }

    /** The notification's Stop. Android shows the notification with SatMap open too: there it pauses the area, as the
     *  row's pause button does; in the background it is iOS's Lock Screen stop. */
    fun stopFromNotification() {
        // (In the background `BackgroundDownload` has already stopped allowing background work, so nothing starts again
        // until SatMap is opened.)
        if (inForeground) {
            _activeID.value?.let { pause(it) }
        } else if (task != null) {
            backgroundTimeEnded()
        } else {
            background.end(success = false) // only waiting for a signal
        }
    }

    private fun startNextIfIdle() {
        if (task != null) return
        if (!(inForeground || background.allowsBackgroundWork) || holdDownloads) {
            background.end(success = lastRunComplete)
            return
        }
        // (The Swift's `updateIdleTimer()` calls are left out: MapScreen keeps the screen on while `activeID` is set.)
        // The space last measured, not asked here (see `freeSpace`).
        val free = freeSpace
        if (free != null && free < minimumFreeSpace) {
            for (a in _areas.value) {
                if (a.status == OfflineArea.Status.queued) {
                    update(a.id) {
                        it.status = OfflineArea.Status.incomplete
                        it.problem = lowSpaceProblem
                    }
                }
            }
            measureFreeSpace() // again, for ↻ after space is freed
            background.end(success = false)
            return
        }
        // Areas that can't use the current network wait; the first one that can, runs. On Android not one whose files
        // another app is reading (an export), as they mustn't change meanwhile.
        for (a in _areas.value) {
            if (a.status == OfflineArea.Status.queued && !canDownloadNow(a)) {
                update(a.id) { it.status = OfflineArea.Status.waitingForNetwork }
            }
        }
        val next = _areas.value.firstOrNull {
            it.status == OfflineArea.Status.queued && ExportShares.readers(it.id) == 0
        }
        if (next != null) {
            run(next)
        } else if (!inForeground && background.allowsBackgroundWork &&
            _areas.value.any { it.status == OfflineArea.Status.waitingForNetwork }
        ) {
            waitForSignalInBackground()
        } else {
            background.end(success = lastRunComplete)
        }
    }

    private fun waitForSignalInBackground() {
        if (signalWait != null) return
        val token = UUID.randomUUID()
        signalWait = token
        // Under the half minute after which iOS ends a background task that shows no progress.
        scope.launch {
            delay(20_000)
            if (signalWait != token) return@launch
            signalWait = null
            if (task == null) background.end(success = false)
        }
    }

    private fun run(area: OfflineArea) {
        // Never fall back to the whole area (for a national park, terabytes) if a shipped road list is missing.
        if (area.preset != null && area.roadLines == null) {
            update(area.id) {
                it.status = OfflineArea.Status.incomplete
                it.problem = "This version of SatMap doesn't have the road list for this area."
            }
            post { startNextIfIdle() }
            return
        }
        val dir = area.directory
        val imagery: MBTiles
        val terrain: MBTiles
        var opened: MBTiles? = null
        try {
            imagery = MBTiles(File(dir, TileRepository.Layer.imagery.fileName), create = true)
            opened = imagery
            terrain = MBTiles(File(dir, TileRepository.Layer.terrain.fileName), create = true)
        } catch (e: Exception) {
            opened?.close() // (Swift's is closed when its last reference goes)
            update(area.id) {
                it.status = OfflineArea.Status.incomplete
                it.problem = (e as? MBTiles.StoreError)?.errorDescription ?: "Couldn't open the tile database."
            }
            post { startNextIfIdle() }
            return
        }
        imagery.setMetadata(mapOf(
            "name" to area.name, "format" to "jpg", "type" to "baselayer", "minzoom" to "0",
            "maxzoom" to area.maxZoom.toString(), "attribution" to TileSources.attribution,
        ))
        terrain.setMetadata(mapOf(
            "name" to area.name + " terrain", "format" to "png", "encoding" to "terrarium",
            "minzoom" to "0", "maxzoom" to Terrarium.maxZoom.toString(),
        ))

        val wifiOnly = !area.allowCellular
        val job = TileDownloadJob(shape = area.shape, layers = listOf(
            DownloadLayer(name = "Elevation", store = terrain, fetcher = fetcher(TileSources::terrainURL, wifiOnly),
                          zooms = 0..Terrarium.maxZoom, pruneByParent = false),
            DownloadLayer(name = "Satellite", store = imagery, fetcher = fetcher(TileSources::imageryURL, wifiOnly),
                          zooms = 0..area.maxZoom, pruneByParent = true, shape = area.roadLines?.let(Roads::corridor)),
        ), concurrency = 32)

        val id = area.id
        val name = area.name
        signalWait = null
        _activeID.value = id
        _progress.value = DownloadProgress()
        update(id) { it.status = OfflineArea.Status.downloading }
        background.begin(title = name, subtitle = "Starting…")

        // Set before it starts, so `finished` always finds it.
        val run = scope.launch(start = CoroutineStart.LAZY) {
            var lastSpaceCheck = Double.NEGATIVE_INFINITY
            var outcome: DownloadOutcome = DownloadOutcome.Cancelled
            try {
                withContext(io) {
                    outcome = job.run { p ->
                        post {
                            if (_activeID.value == id) {
                                _progress.value = p
                                background.update(area = id, title = name, subtitle = progressText(p), done = p.overallDone,
                                                  total = p.overallTotal)
                            }
                        }
                        // Never fill the phone completely: the track log and the rest of the phone need room too.
                        val now = SystemClock.elapsedRealtime() / 1000.0
                        if (now - lastSpaceCheck > 10) {
                            lastSpaceCheck = now
                            val free = freeDiskSpace()
                            freeSpace = free
                            if (free != null && free < minimumFreeSpace) post { stopForLowSpace(id) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // The run was cancelled: `outcome` is what it ended with (`Cancelled`, unless it had just finished).
            }
            withContext(NonCancellable) {
                val size = withContext(io) {
                    imagery.close()
                    terrain.close()
                    freeSpace = freeDiskSpace() // for the next area (see `freeSpace`)
                    AppPaths.size(dir)
                }
                finished(id, outcome, size)
            }
        }
        task = run
        run.start()
    }

    private fun stopForLowSpace(id: UUID) {
        if (_activeID.value != id || lowSpaceStop != null) return
        lowSpaceStop = id
        task?.cancel()
    }

    private fun finished(id: UUID, outcome: DownloadOutcome, size: Long) {
        val storedSomething = (_progress.value?.fetched ?: 0) > 0
        if (lowSpaceStop == id) {
            lowSpaceStop = null
            update(id) {
                it.bytesOnDisk = size
                it.status = OfflineArea.Status.incomplete
                it.problem = lowSpaceProblem
            }
            task = null
            _activeID.value = null
            _progress.value = null
            lastRunComplete = false
            if (storedSomething) _completedRevision.value += 1
            startNextIfIdle()
            return
        }
        task = null
        _activeID.value = null
        _progress.value = null
        lastRunComplete = outcome is DownloadOutcome.Completed && outcome.failed == 0
        var retry = false
        update(id) { a ->
            a.bytesOnDisk = size
            // A pause by the user wins over however the run happened to end.
            if (a.status == OfflineArea.Status.paused &&
                (outcome == DownloadOutcome.Cancelled || outcome == DownloadOutcome.Offline)
            ) {
                return@update
            }
            // Queued again during the run (a new zoom level, or the app went to the background): run it again.
            if (a.status == OfflineArea.Status.queued) return@update
            when (outcome) {
                is DownloadOutcome.Completed -> {
                    a.failedTiles = outcome.failed
                    a.status = if (outcome.failed == 0) OfflineArea.Status.complete else OfflineArea.Status.incomplete
                    networkRetryDelay = 15.0
                }
                DownloadOutcome.Offline -> {
                    a.status = OfflineArea.Status.waitingForNetwork
                    retry = true
                }
                DownloadOutcome.Cancelled -> {
                    if (a.status == OfflineArea.Status.downloading) a.status = OfflineArea.Status.paused
                }
                is DownloadOutcome.StorageError -> {
                    a.status = OfflineArea.Status.incomplete
                    a.problem = outcome.message
                }
            }
        }
        if (storedSomething) _completedRevision.value += 1
        if (retry) scheduleNetworkRetry()
        startNextIfIdle()
    }

    // Helpers

    /** Measures the free space on `io`, for the next `startNextIfIdle` (see `freeSpace`). */
    private fun measureFreeSpace() {
        scope.launch(io) { freeSpace = freeDiskSpace() }
    }

    /** Swift's `DispatchQueue.main.async`: `body` runs on the main thread after what is running now. */
    private fun post(body: () -> Unit) {
        scope.launch(Dispatchers.Main) { body() }
    }

    /** Changes an area. A changed copy replaces it, so the list's collectors see the change. */
    private fun update(id: UUID, persist: Boolean = true, change: (OfflineArea) -> Unit) {
        val list = _areas.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val a = list[i].copy()
        change(a)
        _areas.value = list.toMutableList().also { it[i] = a }
        if (persist) save()
    }

    /** The list in the iPhone's format (`OfflineArea` JSON), written whole or not at all. */
    private fun save() {
        val atomic = AtomicFile(indexFile)
        val out = try {
            atomic.startWrite()
        } catch (e: IOException) {
            return
        }
        try {
            out.write(JsonArray(_areas.value.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
        }
    }

    private fun publishToMap() {
        readers.setAreas(_areas.value.mapNotNull { a ->
            val b = a.shape.bounds ?: return@mapNotNull null
            val nw = WebMercator.world(GeoPoint(lat = b.maxLat, lon = b.minLon))
            val se = WebMercator.world(GeoPoint(lat = b.minLat, lon = b.maxLon))
            TileRepository.AreaRef(id = a.id, directory = a.directory, minX = nw.x, minY = nw.y, maxX = se.x, maxY = se.y)
        })
    }
}

val OfflineArea.directory: File get() = AppPaths.offlineArea(id)

/** The roads imagery is downloaded along, or null for the whole area. */
val OfflineArea.roadLines: List<List<GeoPoint>>?
    get() = roads ?: preset?.let { RoadPresets.load(it) }?.roads

/** A download's fetcher: over any network, or, Wi-Fi only, bound to the network the run starts on (so its requests
 *  fail when Wi-Fi goes, instead of moving to mobile data). */
private fun downloadFetcher(url: (TileID) -> String, wifiOnly: Boolean): TileFetching {
    val client = if (wifiOnly) {
        Connectivity.shared.network?.let { DownloadHttp.wifiOnly(it) } ?: DownloadHttp.anyNetwork
    } else {
        DownloadHttp.anyNetwork
    }
    return HTTPTileFetcher(client, url, wifiOnly)
}

/** `TileRepository.shared`, set up before the model at startup. A test that builds the model without it has no map
 *  to tell. */
private object TileRepositoryReaders : OfflineManager.AreaReaders {
    private val repository: TileRepository?
        get() = try {
            TileRepository.shared
        } catch (e: UninitializedPropertyAccessException) {
            null
        }

    override fun setAreas(refs: List<TileRepository.AreaRef>) {
        repository?.setAreas(refs)
    }

    override fun closeArea(id: UUID) {
        repository?.closeArea(id)
    }
}
