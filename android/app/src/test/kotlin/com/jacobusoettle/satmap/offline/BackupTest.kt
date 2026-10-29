package com.jacobusoettle.satmap.offline

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.OfflineArea.Status
import com.jacobusoettle.satmap.core.OfflineBackup
import com.jacobusoettle.satmap.core.SqlResult
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.map.TileRepository
import com.jacobusoettle.satmap.offline.AndroidSqlDriverTest.Fixture
import com.jacobusoettle.satmap.ui.SharePresenter
import com.jacobusoettle.satmap.ui.ShareRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlin.test.assertFailsWith

/** Backups through the real Android path (offline spec §1.13, §2.8, §2.14): the export writes the area into its own
 *  files through `AndroidSqlDriver` and shares them through `BackupFileProvider`, as files the iPhone app reads
 *  (`Tests/SatMapCoreTests/BackupCompatibilityTests.swift`); readers hold their area's download; and a restore takes the
 *  iPhone's backup, a satellite file alone, an area already there and files that aren't backups. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val fixtures = AndroidSqlDriverTest.fixtures
    private lateinit var downloads: File
    private val published = mutableListOf<List<TileRepository.AreaRef>>()

    /** What the fake tile servers answer. */
    private var answer: suspend (TileID) -> TileFetchResult = { TileFetchResult.Data(byteArrayOf(1, 2, 3)) }
    private lateinit var manager: OfflineManager

    /** About 100 m across, in Mpumalanga. */
    private val farm = CoverageShape(polygons = listOf(listOf(
        GeoPoint(lat = -25.4700, lon = 30.9700), GeoPoint(lat = -25.4700, lon = 30.9710),
        GeoPoint(lat = -25.4709, lon = 30.9710), GeoPoint(lat = -25.4709, lon = 30.9700),
    )))

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        MBTiles.driver = AndroidSqlDriver()
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        ExportShares.resetForTests()
        forgetFileProviderRoots()
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        AppLifecycle.init()
        process.currentState = Lifecycle.State.RESUMED
        SharePresenter.attach() // the map screen is up: the chooser can be shown
        downloads = File(app.cacheDir, "downloads-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        idle()
        SharePresenter.finished()
        SharePresenter.detach()
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
        ExportShares.resetForTests()
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        downloads.deleteRecursively()
    }

    /** `io` is the main looper unless a test passes its own. */
    private fun makeManager(io: CoroutineDispatcher = Dispatchers.Main): OfflineManager {
        manager = OfflineManager(
            app, network = MutableStateFlow(Connectivity.State(isOnline = true, isExpensive = false)),
            freeDiskSpace = { 20L shl 30 },
            fetcher = { _, _ -> object : TileFetching {
                override suspend fun fetch(tile: TileID) = answer(tile)
            } },
            io = io,
            readers = object : OfflineManager.AreaReaders {
                override fun setAreas(refs: List<TileRepository.AreaRef>) {
                    published.add(refs)
                }

                override fun closeArea(id: UUID) {}
            },
        )
        idle()
        return manager
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** `FileProvider` keeps its roots, worked out from the first test's files folder, in a static cache: each test has a
     *  folder of its own. */
    private fun forgetFileProviderRoots() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
        synchronized(cache) { (cache as MutableMap<*, *>).clear() }
    }

    /** Runs the main looper until `done`, while `io` works on its own threads. */
    private fun idleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!done()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    private fun area(id: UUID) = manager.areas.value.single { it.id == id }

    /** An area downloaded earlier, as the fixture: its two files, and the list holding it. */
    private fun saveArea(a: OfflineArea, terrain: Boolean = true) {
        val dir = AppPaths.offlineArea(a.id)
        MBTiles(File(dir, "imagery.mbtiles"), create = true).apply {
            for ((t, data) in Fixture.imageryTiles) assertTrue(put(t, data))
            assertTrue(putMissing(Fixture.missingImagery))
            assertTrue(flush())
            close()
        }
        if (terrain) {
            MBTiles(File(dir, "terrain.mbtiles"), create = true).apply {
                assertTrue(put(Fixture.terrainTile, File(fixtures, "terrarium_12_2390_2342.png").readBytes()))
                assertTrue(flush())
                close()
            }
        }
    }

    private fun saveList(areas: List<OfflineArea>) {
        File(AppPaths.offline, "areas.json").writeText(areas.joinToString(",", "[", "]") { it.toJson().toString() })
    }

    /** The share the export asks for. */
    private fun shared(): ShareRequest = SharePresenter.request.value ?: throw AssertionError("nothing shared: ${manager.backupMessage.value}")

    /** A shared file as the receiving app sees it: its name, and its bytes read through the provider. */
    private fun receive(uri: Uri): Pair<String, File> {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        val copy = File(downloads, name)
        app.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        idle() // the reader's close
        return name to copy
    }

    /** The first column of every row. */
    private fun rows(file: File, sql: String): List<String?> {
        val db = AndroidSqlDriver(readOnly = true).open(file.path, create = false)
        try {
            val s = db.prepare(sql) ?: throw AssertionError(db.errorMessage)
            try {
                val out = mutableListOf<String?>()
                while (s.step() == SqlResult.ROW) out.add(s.columnText(0))
                return out
            } finally {
                s.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun anExportedAreaIsWhatTheIPhoneAppReads() {
        saveArea(Fixture.area)
        saveList(listOf(Fixture.area))
        val m = makeManager()
        m.export(listOf(Fixture.area.id))
        assertEquals("Preparing export…", m.backupStatus.value)
        idle()
        assertNull(m.backupStatus.value)
        val share = shared()
        assertEquals(2, share.uris.size)
        val received = share.uris.map { uri ->
            assertEquals("application/octet-stream", app.contentResolver.getType(uri))
            receive(uri)
        }
        assertEquals(listOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles"), received.map { it.first })

        val dir = AppPaths.offlineArea(Fixture.area.id)
        for (name in listOf("imagery.mbtiles", "terrain.mbtiles")) {
            // The write-ahead log is folded in: the file is complete by itself.
            assertFalse(name, File(dir, "$name-wal").exists())
        }
        val iphone = File(fixtures, "backup/iphone/Fixture farm - satellite.mbtiles").readBytes()
        val layers = HashMap<String, File>()
        for ((name, file) in received) {
            // Plain SQLite in WAL mode, as the iPhone's (header bytes 18 and 19 are 2).
            val bytes = file.readBytes()
            assertArrayEquals(name, iphone.copyOfRange(0, 16), bytes.copyOfRange(0, 16))
            assertEquals(name, iphone[18], bytes[18])
            assertEquals(name, iphone[19], bytes[19])
            // Nothing of Android's own.
            assertEquals(name, emptyList<String?>(), rows(file, "SELECT name FROM sqlite_master WHERE name = 'android_metadata'"))
            assertEquals(name, listOf("metadata", "tiles", "missing_tiles"),
                         rows(file, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY rowid"))

            val info = OfflineBackup.inspect(file)
            layers[info.layerKey] = file
            // The area as Swift's JSONEncoder writes it (BackupCompatibilityTests.testAreaJSONIsTheSharedFormat).
            val json = Json.parseToJsonElement(String(info.areaJSON, Charsets.UTF_8)).jsonObject
            assertEquals(setOf("id", "name", "created", "shape", "roads", "maxZoom", "allowCellular", "status", "failedTiles",
                               "bytesOnDisk"), json.keys)
            assertEquals("6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", json["id"]!!.jsonPrimitive.content)
            assertEquals("783388800", json["created"]!!.jsonPrimitive.content) // since 1 January 2001
            assertEquals("complete", json["status"]!!.jsonPrimitive.content)
            assertEquals("2345678901", json["bytesOnDisk"]!!.jsonPrimitive.content)
            assertEquals("18", json["maxZoom"]!!.jsonPrimitive.content)
            assertEquals("0", json["failedTiles"]!!.jsonPrimitive.content)
            val shape = json["shape"]!!.jsonObject
            assertEquals(setOf("polygons", "lines", "points", "bufferMeters"), shape.keys)
            assertEquals(Json.parseToJsonElement("""{"lat":-33.9245,"lon":18.9232}"""),
                         shape["polygons"]!!.jsonArray[0].jsonArray[0])
            assertEquals(Fixture.area, OfflineArea.fromJson(json))

            val store = MBTiles(file, create = false)
            try {
                when (info.layerKey) {
                    "imagery" -> {
                        assertTrue(name.endsWith("satellite.mbtiles"))
                        for ((t, data) in Fixture.imageryTiles) assertArrayEquals("$t", data, store.tile(t))
                        assertTrue(store.isMissing(Fixture.missingImagery))
                        assertEquals(Fixture.imageryTiles.size.toLong(), store.tileCount())
                    }
                    "terrain" -> assertArrayEquals(File(fixtures, "terrarium_12_2390_2342.png").readBytes(),
                                                   store.tile(Fixture.terrainTile))
                    else -> throw AssertionError("unknown layer ${info.layerKey}")
                }
            } finally {
                store.close()
            }
        }
        assertEquals(setOf("imagery", "terrain"), layers.keys)

        // The share sheet closes; then the files go back in, on this phone or another.
        SharePresenter.finished()
        idle()
        assertNull(m.backupMessage.value)
        m.delete(Fixture.area.id)
        m.restore(received.map { Uri.fromFile(it.second) })
        idle()
        assertEquals("Restored Fixture farm.", m.backupMessage.value)
        assertEquals(Fixture.area.copy(bytesOnDisk = AppPaths.size(dir)), area(Fixture.area.id))
    }

    @Test
    fun exportedFilesAreNamedAfterTheirAreas() {
        val first = Fixture.area.copy(id = UUID.randomUUID(), name = "Farm")
        val second = Fixture.area.copy(id = UUID.randomUUID(), name = "farm", status = Status.incomplete, failedTiles = 3)
        val third = Fixture.area.copy(id = UUID.randomUUID(), name = " Farm: north/south. ")
        val nothing = Fixture.area.copy(id = UUID.randomUUID(), name = "Not yet")
        saveArea(first)
        saveArea(second, terrain = false)
        saveArea(third)
        saveList(listOf(first, second, third, nothing))
        val m = makeManager()
        m.export(m.areas.value.map { it.id })
        idle()
        val share = shared()
        val names = share.uris.map { receive(it).first }
        assertEquals(listOf("Farm - satellite.mbtiles", "Farm - elevation.mbtiles", "farm (2) - satellite.mbtiles",
                            "Farm- north-south - satellite.mbtiles", "Farm- north-south - elevation.mbtiles"), names)
        // Saved as paused unless complete, so a restore continues it.
        val json = OfflineBackup.inspect(File(downloads, "farm (2) - satellite.mbtiles")).areaJSON
        assertEquals(second.copy(status = Status.paused), OfflineArea.fromJson(Json.parseToJsonElement(String(json))))
        SharePresenter.finished()
        idle()

        // Nothing downloaded: nothing to share.
        m.export(listOf(nothing.id))
        idle()
        assertNull(SharePresenter.request.value)
        assertEquals("Nothing has been downloaded yet to export.", m.backupMessage.value)
        assertNull(m.backupStatus.value)
        m.clearBackupMessage()
        assertNull(m.backupMessage.value)
    }

    @Test
    fun withNoScreenToShowItTheShareEndsAtOnce() {
        saveArea(Fixture.area)
        saveList(listOf(Fixture.area))
        val m = makeManager()
        SharePresenter.detach()
        m.export(listOf(Fixture.area.id))
        idle()
        assertNull(SharePresenter.request.value)
        assertNull(m.backupStatus.value)
        SharePresenter.attach()
    }

    @Test
    fun theProviderSharesOnlyWhatWasExportedAndOnlyForReading() {
        saveArea(Fixture.area)
        saveList(listOf(Fixture.area))
        val m = makeManager()
        m.export(listOf(Fixture.area.id))
        idle()
        val uri = shared().uris[0]
        assertFailsWith<SecurityException> { app.contentResolver.openFileDescriptor(uri, "w") }
        assertFailsWith<SecurityException> { app.contentResolver.openFileDescriptor(uri, "rw") }
        // A file of the app that wasn't exported (under another name).
        val other = BackupFileProvider.uriFor(app, File(AppPaths.offlineArea(Fixture.area.id), "terrain.mbtiles"), "x.mbtiles")
        assertTrue(other.toString(), other.toString().startsWith("content://${app.packageName}.backup/offline/"))
        assertNull(ExportShares[other])
        assertFailsWith<FileNotFoundException> { app.contentResolver.openFileDescriptor(other, "r") }
        app.contentResolver.query(other, null, null, null, null)!!.use { assertEquals(0, it.count) }
        // Deleting an area forgets its files.
        SharePresenter.finished()
        m.delete(Fixture.area.id)
        assertNull(ExportShares[uri])
        assertFailsWith<FileNotFoundException> { app.contentResolver.openFileDescriptor(uri, "r") }
    }

    @Test
    fun anAppReadingTheFilesHoldsTheirAreasDownload() {
        val m = makeManager()
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = m.add(name = "A", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        assertEquals(a, m.activeID.value)

        // Exporting it pauses it, and the export waits for its run to end.
        m.export(listOf(a))
        assertEquals(Status.paused, area(a).status)
        idle()
        assertNull(m.activeID.value)
        val uris = shared().uris
        assertEquals(2, uris.size)

        // Drive starts reading while the share sheet is still open …
        val reader = app.contentResolver.openFileDescriptor(uris[0], "r")!!
        assertEquals(1, ExportShares.readers(a))
        // … and goes on after it closes: the area is queued again, but doesn't run while it is read.
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        SharePresenter.finished()
        idle()
        assertEquals(Status.queued, area(a).status)
        assertNull(m.activeID.value)
        val b = m.add(name = "B", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        assertEquals(Status.complete, area(b).status) // others do
        assertEquals(Status.queued, area(a).status)

        // The last close lets it go on.
        reader.close()
        idle()
        assertEquals(0, ExportShares.readers(a))
        assertEquals(Status.complete, area(a).status)

        // A reader that opens a file while its area downloads pauses it, and its close resumes it.
        val gate2 = CompletableDeferred<Unit>()
        answer = {
            gate2.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        m.changeMaxZoom(a, 17)
        idle()
        assertEquals(a, m.activeID.value)
        val late = app.contentResolver.openFileDescriptor(uris[1], "r")!!
        idle()
        assertEquals(Status.paused, area(a).status)
        assertNull(m.activeID.value)
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        late.close()
        idle()
        assertEquals(Status.complete, area(a).status)
        assertEquals(17, area(a).maxZoom)
        gate.complete(Unit)
        gate2.complete(Unit)
    }

    @Test
    fun theIPhonesBackupRestores() {
        // The files are copied in on a thread of their own, as on the phone.
        val m = makeManager(io = Dispatchers.IO)
        val before = m.completedRevision.value
        // Picked in Files from Google Drive: the provider tells the names and sizes.
        val satellite = File(fixtures, "backup/iphone/Fixture farm - satellite.mbtiles")
        val elevation = File(fixtures, "backup/iphone/Fixture farm - elevation.mbtiles")
        Drive.files = mapOf("1" to satellite, "2" to elevation)
        Robolectric.buildContentProvider(Drive::class.java).create("com.example.drive")
        val statuses = mutableListOf<String?>()
        val progress = mutableListOf<Double?>()
        val watch = CoroutineScope(Dispatchers.Main.immediate).launch {
            launch { m.backupStatus.collect { statuses += it } }
            m.backupProgress.collect { progress += it }
        }
        m.restore(listOf(Uri.parse("content://com.example.drive/1"), Uri.parse("content://com.example.drive/2")))
        assertEquals("Restoring…", m.backupStatus.value)
        idleUntil { m.backupMessage.value != null }
        watch.cancel()
        assertNull(m.backupStatus.value)
        assertNull(m.backupProgress.value)
        val total = Format.bytes(satellite.length() + elevation.length())
        assertTrue(statuses.toString(), statuses.contains("Copying $total of $total…"))
        assertEquals(listOf(null, "Restoring…"), statuses.take(2))
        assertEquals(null, statuses.last())
        assertTrue(progress.toString(), progress.contains(1.0))
        assertEquals("Restored Fixture farm.", m.backupMessage.value)

        val restored = area(Fixture.area.id)
        val dir = AppPaths.offlineArea(Fixture.area.id)
        assertEquals(Fixture.area.copy(bytesOnDisk = AppPaths.size(dir)), restored)
        assertEquals(Status.complete, restored.status)
        assertEquals(before + 1, m.completedRevision.value)
        assertTrue(published.last().any { it.id == Fixture.area.id })
        assertFalse(AppPaths.restoreFolder.exists())
        val saved = Json.parseToJsonElement(File(AppPaths.offline, "areas.json").readText()).jsonArray.map(OfflineArea::fromJson)
        assertEquals(m.areas.value, saved)
        // The files in place, read as the map reads them.
        val imagery = MBTiles(File(dir, "imagery.mbtiles"), create = false)
        for ((t, data) in Fixture.imageryTiles) assertArrayEquals(data, imagery.tile(t))
        imagery.close()
        val terrain = MBTiles(File(dir, "terrain.mbtiles"), create = false)
        assertArrayEquals(File(fixtures, "terrarium_12_2390_2342.png").readBytes(), terrain.tile(Fixture.terrainTile))
        terrain.close()
        // The originals aren't changed.
        assertFalse(File(satellite.path + "-wal").exists())

        // Restored again: already there.
        m.clearBackupMessage()
        m.restore(listOf(Uri.parse("content://com.example.drive/1")))
        idleUntil { m.backupMessage.value != null }
        assertEquals("Fixture farm is already in SatMap.", m.backupMessage.value)
        assertEquals(1, m.areas.value.size)
        assertEquals(before + 1, m.completedRevision.value)
    }

    @Test
    fun theSatelliteFileAloneRestoresAPausedArea() {
        val m = makeManager()
        val copy = File(downloads, "Fixture farm - satellite.mbtiles")
        File(fixtures, "backup/android/Fixture farm - satellite.mbtiles").copyTo(copy)
        m.restore(listOf(Uri.fromFile(copy)))
        idle()
        assertEquals("Restored Fixture farm.\nTap ↻ on an area marked Paused to download what its backup didn't include.",
                     m.backupMessage.value)
        val a = area(Fixture.area.id)
        assertEquals(Status.paused, a.status)
        assertNull(a.problem)
        assertTrue(File(a.directory, "imagery.mbtiles").exists())
        assertFalse(File(a.directory, "terrain.mbtiles").exists())
        // (A restore starts nothing; ↻ downloads the elevation.)
        assertNull(m.activeID.value)
    }

    @Test
    fun filesThatArentBackupsAreNamed() {
        val m = makeManager()
        val photo = File(downloads, "holiday.jpg").apply { writeBytes(ByteArray(8192) { 0x42 }) }
        // Another app's MBTiles, without SatMap's settings in it.
        val other = File(downloads, "Other app.mbtiles")
        MBTiles(other, create = true).apply {
            setMetadata(mapOf("name" to "Other"))
            flush()
            close()
        }
        val gone = Uri.fromFile(File(downloads, "gone.mbtiles"))
        m.restore(listOf(Uri.fromFile(photo), Uri.fromFile(other), gone))
        idle()
        assertEquals(
            "holiday.jpg isn't a SatMap backup.\nOther app.mbtiles isn't a SatMap backup.\n" +
                "Couldn't copy gone.mbtiles (is there enough free space?).",
            m.backupMessage.value,
        )
        assertEquals(emptyList<OfflineArea>(), m.areas.value)
        assertFalse(AppPaths.restoreFolder.exists())
        // Nothing is restored while a backup task runs, nor from no files.
        m.restore(emptyList())
        assertNull(m.backupStatus.value)
    }

    @Test
    fun saveToAFolderCopiesTheExport() {
        saveArea(Fixture.area)
        saveList(listOf(Fixture.area))
        val m = makeManager()
        m.export(listOf(Fixture.area.id))
        idle()
        val share = shared()
        SharePresenter.finished() // the chooser closes as "Save to a folder" is chosen

        // A USB drive, picked with the folder picker.
        val tree = usbDrive()
        share.saveToFolder!!(tree)
        assertTrue(m.backupStatus.value!!.startsWith("Copying "))
        idle()
        assertNull(m.backupStatus.value)
        assertNull(m.backupProgress.value)
        assertNull(m.backupMessage.value)
        assertEquals(setOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles"), Folder.root.list()!!.toSet())
        val dir = AppPaths.offlineArea(Fixture.area.id)
        assertArrayEquals(File(dir, "imagery.mbtiles").readBytes(), File(Folder.root, "Fixture farm - satellite.mbtiles").readBytes())
        assertArrayEquals(File(dir, "terrain.mbtiles").readBytes(), File(Folder.root, "Fixture farm - elevation.mbtiles").readBytes())
        assertEquals(0, ExportShares.readers(Fixture.area.id)) // held only while copied

        // A file that can't be written there.
        Folder.root.listFiles()!!.forEach { it.delete() }
        Folder.refuse = setOf("Fixture farm - elevation.mbtiles")
        share.saveToFolder!!(tree)
        idle()
        assertEquals("Couldn't save Fixture farm - elevation.mbtiles.", m.backupMessage.value)
        assertEquals(listOf("Fixture farm - satellite.mbtiles"), Folder.root.list()!!.toList())
    }

    /** A USB drive with an empty folder, as the folder picker gives it. */
    private fun usbDrive(): Uri {
        Folder.root = File(downloads, "usb").apply { mkdirs() }
        Folder.refuse = emptySet()
        Folder.created = {}
        Robolectric.buildContentProvider(Folder::class.java).create(ProviderInfo().apply {
            authority = "com.example.usb"
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
        return DocumentsContract.buildTreeDocumentUri("com.example.usb", "root")
    }

    @Test
    fun saveToAFolderHoldsDownloadsUntilTheCopyEnds() {
        val m = makeManager()
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = m.add(name = "A", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        m.export(listOf(a))
        idle()
        val share = shared()
        assertEquals(2, share.uris.size)

        // The chooser's result comes before "Save to a folder" reaches SatMap: the export's hold ends, and the area
        // downloads again while the folder is picked; another waits behind it.
        SharePresenter.finished()
        idle()
        assertEquals(a, m.activeID.value)
        val b = m.add(name = "B", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        assertEquals(Status.queued, area(b).status)

        // The folder is picked: the area pauses, the copy waits for its run to end, and nothing downloads until the
        // copy has ended; the area counts as read for the whole copy.
        val tree = usbDrive()
        val seen = mutableListOf<String>()
        Folder.created = { name -> seen += "$name: ${m.activeID.value} ${area(a).status} ${area(b).status} ${ExportShares.readers(a)}" }
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        share.saveToFolder!!(tree)
        assertEquals(Status.paused, area(a).status)
        assertTrue(m.backupStatus.value!!.startsWith("Copying "))
        idle()
        assertEquals(listOf("A - satellite.mbtiles: null paused queued 1", "A - elevation.mbtiles: null paused queued 1"), seen)
        assertNull(m.backupStatus.value)
        assertNull(m.backupMessage.value)
        assertEquals(0, ExportShares.readers(a))
        // The copy is the area's file as the run left it.
        val dir = AppPaths.offlineArea(a)
        assertEquals(OfflineBackup.inspect(File(dir, "imagery.mbtiles")).areaJSON.toList(),
                     OfflineBackup.inspect(File(Folder.root, "A - satellite.mbtiles")).areaJSON.toList())

        // Then both go on.
        assertEquals(Status.complete, area(a).status)
        assertEquals(Status.complete, area(b).status)
        gate.complete(Unit)
    }

    @Test
    fun saveToAFolderChosenInTheChooserHoldsDownloadsUntilTheFolderPickerCloses() {
        val m = makeManager()
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = m.add(name = "A", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        m.export(listOf(a))
        idle()
        shared()

        // "Save to a folder" is chosen (SaveToFolderActivity tells SharePresenter first), then the chooser closes:
        // the area stays paused while the folder is picked.
        SharePresenter.requestFolder()
        SharePresenter.chooserClosed()
        idle()
        assertEquals(Status.paused, area(a).status)
        assertNull(m.activeID.value)

        // A folder is picked: the copy, with downloads held; then the area goes on.
        val tree = usbDrive()
        val seen = mutableListOf<String>()
        Folder.created = { name -> seen += "$name: ${m.activeID.value} ${area(a).status}" }
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        SharePresenter.folderChosen(tree)
        idle()
        assertEquals(listOf("A - satellite.mbtiles: null paused", "A - elevation.mbtiles: null paused"), seen)
        assertEquals(Status.complete, area(a).status)

        // Again, with the folder picker cancelled: the area goes on when it closes.
        val gate2 = CompletableDeferred<Unit>()
        answer = {
            gate2.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        m.changeMaxZoom(a, 17)
        idle()
        assertEquals(a, m.activeID.value)
        m.export(listOf(a))
        idle()
        shared()
        SharePresenter.requestFolder()
        SharePresenter.chooserClosed()
        idle()
        assertEquals(Status.paused, area(a).status)
        assertNull(m.activeID.value)
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        SharePresenter.folderChosen(null)
        idle()
        assertEquals(Status.complete, area(a).status)
        assertEquals(17, area(a).maxZoom)
        gate.complete(Unit)
        gate2.complete(Unit)
    }

    @Test
    fun aLateCloseOfAnEarlierShareSheetDoesntEndTheNextExportsHold() {
        val m = makeManager()
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = m.add(name = "A", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        m.export(listOf(a))
        idle()
        val first = shared()

        // The first chooser's result never comes (its activity went), and the area is exported again: the first share
        // ends as the second opens, but the area stays paused for the second.
        answer = { TileFetchResult.Data(byteArrayOf(1)) }
        m.export(listOf(a))
        idle()
        val second = shared()
        assertTrue(first !== second)
        assertEquals(Status.paused, area(a).status)
        assertNull(m.activeID.value)
        // Nor does the first one's completion, run late.
        first.completion()
        idle()
        assertEquals(Status.paused, area(a).status)
        assertNull(m.activeID.value)

        // The second share sheet closes: the area goes on.
        SharePresenter.finished()
        idle()
        assertEquals(Status.complete, area(a).status)
        gate.complete(Unit)
    }

    @Test
    fun anExportWhileAnotherAppReadsTheFilesDoesntWriteIntoThem() {
        saveArea(Fixture.area)
        saveList(listOf(Fixture.area))
        val m = makeManager()
        val id = Fixture.area.id
        m.export(listOf(id))
        idle()
        val uris = shared().uris
        SharePresenter.finished()
        idle()
        // Drive still uploads the first export.
        val reader = app.contentResolver.openFileDescriptor(uris[0], "r")!!
        val dir = AppPaths.offlineArea(id)
        val imagery = File(dir, "imagery.mbtiles").readBytes()
        val terrain = File(dir, "terrain.mbtiles").readBytes()

        // Exported again, with only the size measured since: the files are shared as they are.
        m.refreshSizes()
        idle()
        assertTrue(area(id).bytesOnDisk != Fixture.area.bytesOnDisk)
        m.export(listOf(id))
        idle()
        assertEquals(2, shared().uris.size)
        assertNull(m.backupMessage.value)
        assertArrayEquals(imagery, File(dir, "imagery.mbtiles").readBytes())
        assertArrayEquals(terrain, File(dir, "terrain.mbtiles").readBytes())
        SharePresenter.finished()
        idle()

        // Its settings changed meanwhile: they can't be written while the files are read.
        m.setAllowCellular(id, true)
        m.export(listOf(id))
        idle()
        assertNull(SharePresenter.request.value)
        assertEquals("Couldn't prepare Fixture farm for export. Try again in a moment.", m.backupMessage.value)
        assertNull(m.backupStatus.value)
        assertArrayEquals(imagery, File(dir, "imagery.mbtiles").readBytes())
        assertArrayEquals(terrain, File(dir, "terrain.mbtiles").readBytes())
        assertEquals(Status.complete, area(id).status) // (nothing held)

        // Once Drive has finished, they are.
        m.clearBackupMessage()
        reader.close()
        idle()
        m.export(listOf(id))
        idle()
        val json = OfflineBackup.inspect(File(dir, "imagery.mbtiles")).areaJSON
        assertTrue(OfflineArea.fromJson(Json.parseToJsonElement(String(json))).allowCellular)
        assertEquals(2, shared().uris.size)
    }

    /** A folder picked with the Storage Access Framework (a USB drive): files are made by name in `root`. */
    class Folder : DocumentsProvider() {
        companion object {
            lateinit var root: File
            var refuse: Set<String> = emptySet()

            /** Told of each file made, by name, as its copy starts. */
            var created: (String) -> Unit = {}
        }

        override fun onCreate() = true

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                .apply { addRow(arrayOf(documentId, documentId)) }

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

        override fun isChildDocument(parentDocumentId: String, documentId: String) = true

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (displayName in refuse) throw IllegalStateException("No space left")
            created(displayName)
            File(root, displayName).createNewFile()
            return displayName
        }

        override fun deleteDocument(documentId: String) {
            File(root, documentId).delete()
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(root, documentId), ParcelFileDescriptor.parseMode(mode))
    }

    /** Google Drive's files, by number: their names and sizes, as a provider tells them. */
    class Drive : ContentProvider() {
        companion object {
            var files: Map<String, File> = emptyMap()
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                           sortOrder: String?): Cursor {
            val f = files.getValue(uri.lastPathSegment!!)
            return MatrixCursor(arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any>(f.length(), f.name)) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(files.getValue(uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

        override fun getType(uri: Uri) = "application/vnd.sqlite3"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
