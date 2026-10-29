package com.jacobusoettle.satmap.offline

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.OfflineArea.Status
import com.jacobusoettle.satmap.core.OfflineBackup
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.TileCoverage
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.map.TileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** `OfflineManager`'s list and queue (offline spec §1.1–1.5, §2.14): the runs are real (`TileDownloadJob` into
 *  MBTiles files through `AndroidSqlDriver`) with fake tile servers, and run on the main looper, whose clock the tests
 *  move on. */
@RunWith(AndroidJUnit4::class)
class OfflineManagerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val wifi = Connectivity.State(isOnline = true, isExpensive = false)
    private val mobile = Connectivity.State(isOnline = true, isExpensive = true)
    private val net = MutableStateFlow(wifi)
    private var free: Long? = 20L shl 30
    private val published = mutableListOf<List<TileRepository.AreaRef>>()
    private val closed = mutableListOf<UUID>()

    /** What the fake servers answer. */
    private var answer: suspend (Fetcher, TileID) -> TileFetchResult = { _, _ -> TileFetchResult.Data(byteArrayOf(1, 2, 3)) }

    /** Every fetcher made, two per run (elevation first), with the area being downloaded when it was first asked. */
    private val fetchers = mutableListOf<Fetcher>()
    private lateinit var manager: OfflineManager

    private inner class Fetcher(val terrain: Boolean, val wifiOnly: Boolean) : TileFetching {
        var area: UUID? = null

        override suspend fun fetch(tile: TileID): TileFetchResult {
            if (area == null) area = manager.activeID.value
            return answer(this, tile)
        }
    }

    /** The areas run, in order. */
    private val runs: List<UUID?> get() = fetchers.filter { it.terrain }.map { it.area }

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
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        AppLifecycle.init()
        process.currentState = Lifecycle.State.RESUMED // SatMap on screen
    }

    @After
    fun tearDown() {
        idle()
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
    }

    /** The threads the free space was measured on. */
    private val measuredOn = Collections.synchronizedList(mutableListOf<Thread>())

    /** `io` is the main looper too unless a test passes its own. */
    private fun makeManager(io: CoroutineDispatcher = Dispatchers.Main): OfflineManager {
        manager = OfflineManager(
            app, network = net,
            freeDiskSpace = {
                measuredOn += Thread.currentThread()
                free
            },
            fetcher = { url, wifiOnly ->
                Fetcher(terrain = url(TileID(0, 0, 0)).contains("terrarium"), wifiOnly = wifiOnly).also { fetchers += it }
            },
            io = io,
            readers = object : OfflineManager.AreaReaders {
                override fun setAreas(refs: List<TileRepository.AreaRef>) {
                    published.add(refs)
                }

                override fun closeArea(id: UUID) {
                    closed += id
                }
            },
        )
        return manager
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun wait(seconds: Double) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((seconds * 1000).toLong()))

    /** Runs the main looper until `done`, while a test's own `io` thread works. */
    private fun idleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!done()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    private fun add(m: OfflineManager, name: String, maxZoom: Int = 16, allowCellular: Boolean = false, preset: String? = null) =
        m.add(name = name, shape = farm, roads = null, preset = preset, maxZoom = maxZoom, allowCellular = allowCellular).id

    private fun area(id: UUID) = manager.areas.value.single { it.id == id }

    private fun savedAreas(): List<OfflineArea> =
        Json.parseToJsonElement(File(AppPaths.offline, "areas.json").readText()).jsonArray.map(OfflineArea::fromJson)

    @Test
    fun theQueueRunsInListOrder() {
        val m = makeManager()
        idle()
        val a = add(m, "A")
        val b = add(m, "B")
        val c = add(m, "C")
        assertEquals(a, m.activeID.value)
        assertEquals(listOf(Status.downloading, Status.queued, Status.queued), m.areas.value.map { it.status })
        idle()
        assertEquals(listOf(a, b, c), runs)
        assertEquals(listOf(Status.complete, Status.complete, Status.complete), m.areas.value.map { it.status })
        assertNull(m.activeID.value)
        assertNull(m.progress.value)
        assertEquals(3, m.completedRevision.value)
        for (x in m.areas.value) {
            assertEquals(0, x.failedTiles)
            assertEquals(AppPaths.size(x.directory), x.bytesOnDisk) // (Robolectric's lstat counts no blocks: 0 here)
            assertTrue(File(x.directory, "imagery.mbtiles").exists())
            assertTrue(File(x.directory, "terrain.mbtiles").exists())
        }
        assertEquals(m.areas.value, savedAreas())
        // The stores, as the Swift writes them.
        val imagery = MBTiles(File(area(a).directory, "imagery.mbtiles"), create = false)
        assertEquals("16", imagery.metadata("maxzoom"))
        assertEquals("jpg", imagery.metadata("format"))
        assertEquals(16, imagery.maxStoredZoom())
        imagery.close()
        val terrain = MBTiles(File(area(a).directory, "terrain.mbtiles"), create = false)
        assertEquals("A terrain", terrain.metadata("name"))
        assertEquals("terrarium", terrain.metadata("encoding"))
        assertEquals(15, terrain.maxStoredZoom())
        terrain.close()
        var depth: Int? = null
        CoroutineScope(Dispatchers.Main).launch { depth = m.savedImageryDepth(a) }
        idle()
        assertEquals(16, depth)
    }

    @Test
    fun aPauseWinsOverHowTheRunEnded() {
        val m = makeManager()
        idle()
        // The user pauses just as the network goes: the area stays paused, and doesn't wait for the network.
        answer = { f, _ ->
            m.pause(f.area!!)
            TileFetchResult.Offline
        }
        val a = add(m, "A")
        idle()
        assertEquals(Status.paused, area(a).status)
        wait(60.0) // no retry either
        assertEquals(1, runs.size)

        // Paused while downloading: the run is cancelled and the next area runs.
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ ->
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val b = add(m, "B")
        val c = add(m, "C")
        idle()
        assertEquals(b, m.activeID.value)
        m.pause(b)
        assertEquals(Status.paused, area(b).status)
        answer = { _, _ -> TileFetchResult.Data(byteArrayOf(1)) }
        idle()
        assertEquals(Status.paused, area(b).status)
        assertEquals(Status.complete, area(c).status)
        assertEquals(listOf(a, b, c), runs)

        m.resume(b)
        idle()
        assertEquals(Status.complete, area(b).status)
        gate.complete(Unit)
    }

    @Test
    fun anAreaQueuedAgainDuringItsRunRunsAgain() {
        val m = makeManager()
        idle()
        // A deeper level chosen while it downloads.
        var first = true
        answer = { f, _ ->
            if (first) {
                first = false
                m.changeMaxZoom(f.area!!, 17)
                assertEquals(Status.queued, area(f.area!!).status)
            }
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = add(m, "A")
        idle()
        assertEquals(listOf(a, a), runs)
        assertEquals(Status.complete, area(a).status)
        assertEquals(17, area(a).maxZoom)
        val imagery = MBTiles(File(area(a).directory, "imagery.mbtiles"), create = false)
        assertEquals("17", imagery.metadata("maxzoom"))
        assertEquals(17, imagery.maxStoredZoom())
        imagery.close()

        // Mobile data allowed while it downloads: it starts again on any network.
        first = true
        answer = { f, _ ->
            if (first) {
                first = false
                m.setAllowCellular(f.area!!, true)
                assertEquals(Status.queued, area(f.area!!).status)
            }
            TileFetchResult.Data(byteArrayOf(1))
        }
        fetchers.clear()
        val b = add(m, "B")
        idle()
        assertEquals(listOf(b, b), runs)
        assertEquals(listOf(true, true, false, false), fetchers.map { it.wifiOnly })
        assertTrue(area(b).allowCellular)
        assertEquals(Status.complete, area(b).status)

        // A new track while the track area downloads: it runs again along the new track.
        first = true
        val newTrack = listOf(listOf(GeoPoint(lat = -25.48, lon = 30.98), GeoPoint(lat = -25.481, lon = 30.981)))
        answer = { _, _ ->
            if (first) {
                first = false
                m.updateTrackArea("Where I've been", newTrack, maxZoom = 16, allowCellular = false)
            }
            TileFetchResult.Data(byteArrayOf(1))
        }
        fetchers.clear()
        m.updateTrackArea("Track", listOf(listOf(GeoPoint(lat = -25.47, lon = 30.97), GeoPoint(lat = -25.471, lon = 30.971))),
                          maxZoom = 16, allowCellular = false)
        val track = m.areas.value.single { it.isTrack == true }
        idle()
        assertEquals(listOf(track.id, track.id), runs)
        val done = area(track.id)
        assertEquals("Where I've been", done.name)
        assertEquals(newTrack, done.roads)
        assertEquals(Status.complete, done.status)
        assertEquals(3, m.areas.value.size)
    }

    @Test
    fun aWiFiOnlyAreaWaitsForWiFi() {
        net.value = mobile
        val m = makeManager()
        idle()
        val a = add(m, "Wi-Fi only")
        assertEquals(Status.waitingForNetwork, area(a).status)
        assertFalse(m.canDownloadNow(area(a)))
        assertTrue(fetchers.isEmpty())
        // One that may use mobile data goes ahead.
        val b = add(m, "Anywhere", allowCellular = true)
        idle()
        assertEquals(Status.complete, area(b).status)
        assertEquals(Status.waitingForNetwork, area(a).status)
        assertEquals(listOf(false, false), fetchers.map { it.wifiOnly })

        net.value = wifi
        idle()
        assertEquals(Status.complete, area(a).status)
        assertEquals(listOf(b, a), runs)
        assertEquals(listOf(false, false, true, true), fetchers.map { it.wifiOnly })

        // Offline altogether: nothing may download.
        net.value = Connectivity.State(isOnline = false, isExpensive = false)
        assertFalse(m.canDownloadNow(area(b)))
    }

    @Test
    fun theNetworkRetryWaitDoublesTo600sAndACompletedRunResetsIt() {
        val m = makeManager()
        idle()
        var online = false
        answer = { _, _ -> if (online) TileFetchResult.Data(byteArrayOf(1)) else TileFetchResult.Offline }
        val a = add(m, "A")
        idle()
        assertEquals(Status.waitingForNetwork, area(a).status)
        assertEquals(1, runs.size)
        for (gap in listOf(15, 30, 60, 120, 240, 480, 600, 600)) {
            val before = runs.size
            wait(gap - 0.5)
            assertEquals("not before $gap s", before, runs.size)
            wait(0.5)
            assertEquals("after $gap s", before + 1, runs.size)
            assertEquals(Status.waitingForNetwork, area(a).status)
        }
        online = true
        wait(600.0)
        assertEquals(Status.complete, area(a).status)

        // The next wait starts at 15 s again.
        online = false
        val b = add(m, "B")
        idle()
        assertEquals(Status.waitingForNetwork, area(b).status)
        val before = runs.size
        wait(14.5)
        assertEquals(before, runs.size)
        online = true
        wait(0.5)
        assertEquals(Status.complete, area(b).status)
    }

    @Test
    fun lessThan500MBFreeStopsDownloads() {
        // Before a run starts (with the space measured at start).
        free = 400L shl 20
        val m = makeManager()
        idle()
        val a = add(m, "A")
        assertEquals(Status.incomplete, area(a).status)
        assertEquals("Less than 500 MB free on the phone. Free up space, then tap ↻.", area(a).problem)
        assertTrue(fetchers.isEmpty())

        // Space freed in another app: measured again as SatMap comes back, so ↻ starts it.
        free = 20L shl 30
        process.currentState = Lifecycle.State.CREATED
        process.currentState = Lifecycle.State.RESUMED
        idle()
        m.resume(a)
        idle()
        assertEquals(Status.complete, area(a).status)
        assertNull(area(a).problem)

        // During a run (checked at once, then every 10 s).
        val b = add(m, "B")
        val c = add(m, "C")
        assertEquals(b, m.activeID.value)
        free = 100L shl 20
        idle()
        assertEquals(Status.incomplete, area(b).status)
        assertEquals(OfflineManager.lowSpaceProblem, area(b).problem)
        assertEquals(AppPaths.size(area(b).directory), area(b).bytesOnDisk)
        assertEquals(Status.incomplete, area(c).status) // and nothing else starts
        assertNull(m.activeID.value)

        // ↻ with room again, made by deleting an area: measured after the delete.
        free = 20L shl 30
        m.delete(a)
        idle()
        m.resume(b)
        idle()
        assertEquals(Status.complete, area(b).status)
        assertNull(area(b).problem)

        // Space freed with SatMap open (and nothing deleted in it): the first ↻ measures again, the next one starts it.
        free = 100L shl 20
        val d = add(m, "D")
        idle()
        assertEquals(Status.incomplete, area(d).status)
        free = 20L shl 30
        m.resume(d)
        assertEquals(Status.incomplete, area(d).status)
        idle()
        m.resume(d)
        idle()
        assertEquals(Status.complete, area(d).status)
    }

    @Test(timeout = 60_000)
    fun theFreeSpaceIsNeverMeasuredOnTheMainThread() {
        // The phone's answer is a call into the system's storage service (DESIGN.md D16), which mustn't hold up the
        // main thread: the queue goes by the last measure, made on `io` (here a thread of its own, as on the phone).
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test io") }
        val io = executor.asCoroutineDispatcher()
        // Waits for what was started on `io` so far.
        val settle = {
            executor.submit {}.get()
            idle()
        }
        try {
            val m = makeManager(io)
            settle()
            assertEquals(1, measuredOn.size) // at start
            val a = add(m, "A")
            idleUntil { area(a).status == Status.complete } // by the run (at once), and as it ends
            free = 100L shl 20
            val b = add(m, "B")
            idleUntil { area(b).status == Status.incomplete && m.activeID.value == null } // the low-space stop
            settle()
            // (`io` waits until the space is freed, so the measure after the refusal finds it whatever the threads do.)
            val hold = CountDownLatch(1)
            executor.submit { hold.await() }
            val c = add(m, "C")
            assertEquals(Status.incomplete, area(c).status) // refused at once, by the last measure
            free = 20L shl 30
            hold.countDown()
            settle() // (measured again after the refusal)
            val n = measuredOn.size
            process.currentState = Lifecycle.State.CREATED
            process.currentState = Lifecycle.State.RESUMED
            settle()
            assertEquals(n + 1, measuredOn.size) // back in front
            m.delete(a)
            settle()
            assertEquals(n + 2, measuredOn.size) // after a delete
            m.resume(c)
            idleUntil { area(c).status == Status.complete }
            settle()
            val main = Looper.getMainLooper().thread
            assertTrue(measuredOn.toString(), measuredOn.none { it === main })
        } finally {
            io.close()
        }
    }

    @Test
    fun aMissingRoadListNeverDownloadsTheWholeArea() {
        val m = makeManager()
        idle()
        val a = add(m, "Park roads", preset = "NoSuchRoads")
        val b = add(m, "Farm")
        assertEquals(Status.incomplete, area(a).status)
        assertEquals("This version of SatMap doesn't have the road list for this area.", area(a).problem)
        idle()
        assertEquals(listOf(b), runs)
        assertEquals(Status.complete, area(b).status)
    }

    @Test
    fun theKrugerAreaDownloadsAlongItsShippedRoads() {
        val m = makeManager()
        idle()
        // The roads from the app's assets (KrugerRoads.json), not stored with the area.
        val a = add(m, "Kruger National Park roads", maxZoom = 10, preset = RoadPresets.kruger)
        idle()
        val done = area(a)
        assertEquals(Status.complete, done.status)
        assertNull(done.roads)
        val roads = RoadPresets.load(RoadPresets.kruger)!!.roads
        assertEquals(roads, done.roadLines)
        // Imagery along the roads only; elevation for the whole area.
        val corridor = Roads.corridor(roads)
        val imagery = MBTiles(File(done.directory, "imagery.mbtiles"), create = false)
        assertEquals((0..10).sumOf { TileCoverage(shape = corridor, zoom = it).tileCount }, imagery.tileCount())
        imagery.close()
        val terrain = MBTiles(File(done.directory, "terrain.mbtiles"), create = false)
        assertEquals((0..15).sumOf { TileCoverage(shape = farm, zoom = it).tileCount }, terrain.tileCount())
        terrain.close()
    }

    @Test
    fun failedTilesLeaveTheAreaIncomplete() {
        val m = makeManager()
        idle()
        answer = { f, t -> if (!f.terrain && t.z == 16) TileFetchResult.Failed else TileFetchResult.Data(byteArrayOf(1)) }
        val a = add(m, "A")
        wait(60.0) // the job's own retries, 1 + 3 + 8 s
        val done = area(a)
        assertEquals(Status.incomplete, done.status)
        assertTrue(done.failedTiles > 0)
        assertNull(done.problem)
        // ↻ tries them again.
        answer = { _, _ -> TileFetchResult.Data(byteArrayOf(1)) }
        m.resume(a)
        idle()
        assertEquals(Status.complete, area(a).status)
        assertEquals(0, area(a).failedTiles)
    }

    @Test
    fun anUnreadableIndexIsKeptAside() {
        val index = File(AppPaths.offline, "areas.json")
        index.writeText("""[{"id":"not an area"}]""")
        val m = makeManager()
        assertEquals(emptyList<OfflineArea>(), m.areas.value)
        assertFalse(index.exists())
        val aside = AppPaths.offline.listFiles()!!.single { it.name.startsWith("areas.json.unreadable-") }
        assertTrue(aside.name, Regex("""areas\.json\.unreadable-\d+""").matches(aside.name))
        assertEquals("""[{"id":"not an area"}]""", aside.readText())
        idle()
        add(m, "New")
        idle()
        assertTrue(aside.exists())
        assertEquals(1, savedAreas().size)
    }

    @Test
    fun theSavedListIsLoadedAsTheIPhoneWroteIt() {
        // The iPhone's area JSON, from its backup fixture, as its areas.json: one area left downloading, one done.
        val copy = File(app.cacheDir, "fixture-" + UUID.randomUUID() + ".mbtiles")
        File(AndroidSqlDriverTest.fixtures, "backup/iphone/Fixture farm - satellite.mbtiles").copyTo(copy)
        val iphone = String(OfflineBackup.inspect(copy).areaJSON, Charsets.UTF_8)
        copy.delete()
        val stopped = OfflineArea(
            id = UUID.randomUUID(), name = "Stopped", created = 1_761_000_000.0, shape = farm, maxZoom = 18,
            allowCellular = true, status = Status.downloading, failedTiles = 0, bytesOnDisk = 12_345,
        )
        File(AppPaths.offline, "areas.json").writeText("[$iphone,${stopped.toJson()}]")
        val fixture = OfflineArea.fromJson(Json.parseToJsonElement(iphone))
        // Folders: the two areas', one left by an area deleted while SatMap closed, an interrupted restore, and another.
        val kept = listOf(fixture.id, stopped.id).map { File(AppPaths.offline, it.toString().uppercase()).apply { mkdirs() } }
        val orphan = File(AppPaths.offline, UUID.randomUUID().toString().uppercase()).apply { mkdirs() }
        File(orphan, "imagery.mbtiles").writeText("x")
        val restoring = File(AppPaths.offline, "Restoring").apply { mkdirs() }
        val other = File(AppPaths.offline, "Notes").apply { mkdirs() }

        process.currentState = Lifecycle.State.CREATED // in the background: nothing starts
        val m = makeManager()
        idle()
        assertEquals(listOf(fixture, stopped.copy(status = Status.queued)), m.areas.value) // continues when opened
        assertEquals("Fixture farm", m.areas.value[0].name)
        assertEquals(Status.complete, m.areas.value[0].status)
        for (f in kept) assertTrue(f.name, f.exists())
        assertFalse(orphan.exists())
        assertFalse(restoring.exists())
        assertTrue(other.exists())
        assertTrue(fetchers.isEmpty())

        // Opened: the stopped one carries on.
        process.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(listOf(stopped.id), runs)
        assertEquals(Status.complete, area(stopped.id).status)
    }

    @Test
    fun theListIsSavedInTheIPhonesFormat() {
        val m = makeManager()
        idle()
        process.currentState = Lifecycle.State.CREATED // nothing runs
        val a = add(m, "Farm: north", maxZoom = 19)
        val saved = Json.parseToJsonElement(File(AppPaths.offline, "areas.json").readText()).jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "created", "shape", "maxZoom", "allowCellular", "status", "failedTiles", "bytesOnDisk"),
                     saved.keys)
        assertEquals(a.toString().uppercase(), saved["id"]!!.jsonPrimitive.content)
        assertEquals("19", saved["maxZoom"]!!.jsonPrimitive.content)
        assertEquals("queued", saved["status"]!!.jsonPrimitive.content)
        assertEquals("0", saved["bytesOnDisk"]!!.jsonPrimitive.content)
        // Seconds since 1 January 2001.
        val created = saved["created"]!!.jsonPrimitive.content.toDouble() + 978_307_200
        assertTrue(Math.abs(created - System.currentTimeMillis() / 1000.0) < 60)
        assertEquals(m.areas.value, savedAreas())
    }

    @Test
    fun theAreasArePublishedToTheMap() {
        val m = makeManager()
        assertEquals(listOf(emptyList<TileRepository.AreaRef>()), published)
        idle()
        process.currentState = Lifecycle.State.CREATED
        val a = add(m, "A")
        val ref = published.last().single()
        val b = farm.bounds!!
        val nw = WebMercator.world(GeoPoint(lat = b.maxLat, lon = b.minLon))
        val se = WebMercator.world(GeoPoint(lat = b.minLat, lon = b.maxLon))
        assertEquals(TileRepository.AreaRef(a, AppPaths.offlineArea(a), nw.x, nw.y, se.x, se.y), ref)
        m.delete(a)
        assertEquals(emptyList<TileRepository.AreaRef>(), published.last())
        assertEquals(listOf(a), closed)
    }

    @Test
    fun aDeletedAreaGoesWithItsFiles() {
        val m = makeManager()
        idle()
        val a = add(m, "A")
        idle()
        val dir = area(a).directory
        assertTrue(File(dir, "imagery.mbtiles").exists())
        m.delete(a)
        assertFalse(dir.exists())
        assertEquals(emptyList<OfflineArea>(), m.areas.value)
        assertEquals(emptyList<OfflineArea>(), savedAreas())

        // While it downloads: the files go once its run has ended, and the next area runs.
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ ->
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val b = add(m, "B")
        val c = add(m, "C")
        idle()
        assertEquals(b, m.activeID.value)
        val bDir = File(AppPaths.offline, b.toString().uppercase())
        m.delete(b)
        assertEquals(listOf(c), m.areas.value.map { it.id })
        answer = { _, _ -> TileFetchResult.Data(byteArrayOf(1)) }
        idle()
        assertFalse(bDir.exists())
        assertEquals(Status.complete, area(c).status)
        assertEquals(listOf(a, b), closed)
        gate.complete(Unit)
    }

    @Test
    fun sizesAreMeasuredAgainAndSavedOnce() {
        // An area whose saved size is out of date (a restore, a copy from the iPhone).
        val stale = OfflineArea(
            id = UUID.randomUUID(), name = "A", created = 1_761_000_000.0, shape = farm, maxZoom = 16,
            allowCellular = false, status = Status.complete, failedTiles = 0, bytesOnDisk = 2_345_678_901,
        )
        File(AppPaths.offline, "areas.json").writeText("[${stale.toJson()}]")
        File(stale.directory, "imagery.mbtiles").writeBytes(ByteArray(100_000))
        val m = makeManager()
        idle()
        m.refreshSizes()
        assertEquals(2_345_678_901, area(stale.id).bytesOnDisk) // measured off the main thread
        idle()
        // (Robolectric's lstat counts no blocks, so this is 0 here; on a phone it is the allocated size.)
        assertEquals(AppPaths.size(stale.directory), area(stale.id).bytesOnDisk)
        assertEquals(m.areas.value, savedAreas())
    }

    @Test
    fun inTheBackgroundNothingNewStartsUntilSatMapIsOpened() {
        val m = makeManager()
        idle()
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ ->
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        val a = add(m, "A")
        idle()
        assertEquals(Status.downloading, area(a).status)
        // Left without the foreground service (Robolectric doesn't start it), and the background time ends after 25 s:
        // back to queued, to continue when opened.
        process.currentState = Lifecycle.State.CREATED
        wait(24.9)
        assertEquals(Status.downloading, area(a).status)
        wait(0.1)
        assertEquals(Status.queued, area(a).status)
        assertNull(m.activeID.value)
        val b = add(m, "B")
        idle()
        assertEquals(Status.queued, area(b).status)
        assertEquals(listOf(a), runs)

        answer = { _, _ -> TileFetchResult.Data(byteArrayOf(1)) }
        process.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(listOf(a, a, b), runs)
        assertEquals(listOf(Status.complete, Status.complete), m.areas.value.map { it.status })
        // With SatMap open, the time ending changes nothing.
        m.backgroundTimeEnded()
        assertEquals(Status.complete, area(a).status)
        gate.complete(Unit)
    }

    @Test
    fun theProgressTextIsTheSwiftOne() {
        val p = DownloadProgress(layer = "Satellite", zoom = 17, maxZoom = 19, overallDone = 2, overallTotal = 3)
        assertEquals("Satellite · zoom 17 of 19 · 66%", OfflineManager.progressText(p))
        assertEquals("Elevation · zoom 0 of 15 · 0%",
                     OfflineManager.progressText(DownloadProgress(layer = "Elevation", zoom = 0, maxZoom = 15)))
    }
}
