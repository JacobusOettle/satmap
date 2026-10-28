package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A fake imagery server: tiles exist everywhere up to `deepest` (and z+1 only inside a small patch). Also used by
 *  RoadsTests. */
internal class FakeServer(val deepest: Int, val patch: TileID) : TileFetching { // tiles under `patch` exist one level deeper
    var failFirstAttemptFor: MutableSet<TileID> = mutableSetOf()
    var offlineAfter: Int? = null
    private val lock = Any()
    private val requested = mutableListOf<TileID>()
    val requests: List<TileID> get() = synchronized(lock) { requested.toList() }

    override suspend fun fetch(tile: TileID): TileFetchResult {
        val (count, failNow) = synchronized(lock) {
            requested.add(tile)
            Pair(requested.size, failFirstAttemptFor.remove(tile))
        }
        val o = offlineAfter
        if (o != null && count > o) return TileFetchResult.Offline
        if (failNow) return TileFetchResult.Failed
        val exists = tile.z <= deepest || (tile.z == deepest + 1 && tile.z > patch.z && tile.ancestor(levels = tile.z - patch.z) == patch)
        return if (exists) TileFetchResult.Data("$tile".toByteArray()) else TileFetchResult.Missing
    }
}

class DownloadEngineTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    private val area = CoverageShape(polygons = listOf(listOf(
        GeoPoint(lat = -25.465, lon = 30.965), GeoPoint(lat = -25.465, lon = 30.975),
        GeoPoint(lat = -25.475, lon = 30.975), GeoPoint(lat = -25.475, lon = 30.965),
    )))

    private suspend fun run(job: TileDownloadJob): Pair<DownloadOutcome, DownloadProgress> {
        var last = DownloadProgress()
        val outcome = job.run { last = it }
        return Pair(outcome, last)
    }

    @Test
    fun testPrunesBelowDeepestAvailableLevel() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val patch = TileID.containing(GeoPoint(lat = -25.47, lon = 30.97), z = 16)
        val server = FakeServer(deepest = 16, patch = patch)
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 0..22, pruneByParent = true)),
                                  concurrency = 4, retryDelay = { 0.0 })
        val (outcome, progress) = run(job)
        assertEquals(DownloadOutcome.Completed(failed = 0), outcome)
        // Every requested tile is inside the coverage, and nothing deeper than z18 was ever requested:
        // z17 is requested for children of stored z16 tiles; z18 only under the patch's z17 tiles (which 404).
        assertLessThanOrEqual(server.requests.maxOf { it.z }, 18)
        val z16 = TileCoverage(shape = area, zoom = 16).tileCount
        assertEquals(z16, server.requests.count { it.z == 16 }.toLong())
        assertGreaterThan(progress.fetched, 0)
        // Patch children at z17 exist; z18 under them are missing; so z18 was requested only for those children.
        val z17Stored = server.requests.count { it.z == 17 && it.parent == patch }
        assertGreaterThan(z17Stored, 0)
        assertTrue(server.requests.filter { it.z == 18 }.all { it.ancestor(levels = 2) == patch })

        // Running again resumes: nothing left to fetch.
        val before = server.requests.size
        val (again, _) = run(job)
        assertEquals(DownloadOutcome.Completed(failed = 0), again)
        assertEquals(before, server.requests.size)
    }

    /** One progress bar for the whole run (the system's, while SatMap is in the background): it only goes forward and
     *  ends full, and where the imagery stops early it doesn't sit near zero until the end. */
    @Test
    fun testOverallProgressOnlyGoesForwardAndEndsFull() = runTest {
        val imagery = MBTiles(File(tempDir(), "i.mbtiles"), create = true)
        val terrain = MBTiles(File(tempDir(), "t.mbtiles"), create = true)
        val patch = TileID.containing(GeoPoint(lat = -25.47, lon = 30.97), z = 16)
        val job = TileDownloadJob(shape = area, layers = listOf(
            DownloadLayer(name = "dem", store = terrain, fetcher = FakeServer(deepest = 12, patch = patch), zooms = 0..14, pruneByParent = false),
            DownloadLayer(name = "sat", store = imagery, fetcher = FakeServer(deepest = 16, patch = patch), zooms = 0..22, pruneByParent = true),
        ), concurrency = 4, retryDelay = { 0.0 })
        val seen = mutableListOf<DownloadProgress>()
        val outcome = job.run { seen.add(it) }
        assertEquals(DownloadOutcome.Completed(failed = 0), outcome)
        val done = seen.map { it.overallDone }
        assertEquals("went backwards", done.sorted(), done)
        assertGreaterThan(seen.last().overallTotal, 0L)
        assertEquals(seen.last().overallTotal, seen.last().overallDone)
        // At the start everything up to zoom 22 counts; once the imagery runs out the deeper levels stop counting.
        val planned = (0..14).fold(0L) { sum, z -> sum + TileCoverage(shape = area, zoom = z).tileCount } +
            (0..22).fold(0L) { sum, z -> sum + TileCoverage(shape = area, zoom = z).tileCount }
        assertEquals(planned, seen.first().overallTotal)
        assertLessThan(seen.last().overallTotal, planned / 100)
        // The level where the imagery stops (z17: all missing but the patch) fills the bar, rather than a jump at the end.
        val lastAt17 = seen.lastOrNull { it.layer == "sat" && it.zoom == 17 }
        assertNotNull(lastAt17)
        assertGreaterThan(lastAt17!!.overallDone.toDouble() / lastAt17.overallTotal.toDouble(), 0.5)
    }

    /** Choosing a deeper level for a downloaded area runs the job again with a higher maximum: only the new level
     *  is fetched (stored tiles and tiles known to be missing are skipped). */
    @Test
    fun testDeeperLevelFetchesOnlyTheNewLevel() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val server = FakeServer(deepest = 19, patch = TileID(z = 0, x = 0, y = 0))
        fun job(maxZoom: Int) =
            TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 0..maxZoom, pruneByParent = true)),
                            concurrency = 4, retryDelay = { 0.0 })
        val firstRun = job(17).run { }
        assertEquals(DownloadOutcome.Completed(failed = 0), firstRun)
        val first = server.requests.size
        val secondRun = job(18).run { }
        assertEquals(DownloadOutcome.Completed(failed = 0), secondRun)
        val again = server.requests.drop(first)
        assertFalse(again.isEmpty())
        assertTrue("only zoom 18 is fetched the second time", again.all { it.z == 18 })
        assertEquals(TileCoverage(shape = area, zoom = 18).tileCount, again.size.toLong())
    }

    @Test
    fun testRetriesTransientFailures() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val server = FakeServer(deepest = 14, patch = TileID(z = 0, x = 0, y = 0))
        val target = TileID.containing(GeoPoint(lat = -25.47, lon = 30.97), z = 14)
        server.failFirstAttemptFor = mutableSetOf(target)
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 10..14, pruneByParent = true)),
                                  retryDelay = { 0.0 })
        val (outcome, _) = run(job)
        assertEquals(DownloadOutcome.Completed(failed = 0), outcome)
        assertNotNull(store.tile(target))
        assertEquals(2, server.requests.count { it == target })
    }

    @Test
    fun testStopsWhenOfflineAndResumes() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val server = FakeServer(deepest = 17, patch = TileID(z = 0, x = 0, y = 0))
        server.offlineAfter = 20
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 0..17, pruneByParent = true)),
                                  concurrency = 2, retryDelay = { 0.0 })
        val (outcome, _) = run(job)
        assertEquals(DownloadOutcome.Offline, outcome)
        server.offlineAfter = null
        val (outcome2, _) = run(job)
        assertEquals(DownloadOutcome.Completed(failed = 0), outcome2)
        var expected = 0L
        for (z in 0..17) expected += TileCoverage(shape = area, zoom = z).tileCount
        assertEquals(expected, store.tileCount())
    }

    @Test
    fun testStopsOnStorageError() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        store.close() // every write now fails, as on a full disk
        val server = FakeServer(deepest = 14, patch = TileID(z = 0, x = 0, y = 0))
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 0..14, pruneByParent = true)),
                                  retryDelay = { 0.0 })
        val (outcome, progress) = run(job)
        if (outcome !is DownloadOutcome.StorageError) fail("expected storage error, got $outcome")
        assertEquals(0, progress.fetched)
        assertLessThan(server.requests.size, 20) // stopped at the first level
    }

    @Test
    fun testStopsAsOfflineWhenEveryRequestFails() = runTest {
        class DeadInternet : TileFetching {
            var count = 0
            override suspend fun fetch(tile: TileID): TileFetchResult {
                count += 1
                return TileFetchResult.Failed
            }
        }
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val server = DeadInternet()
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "dem", store = store, fetcher = server, zooms = 0..16, pruneByParent = false)),
                                  concurrency = 2, maxAttempts = 1, outageThreshold = 5, retryDelay = { 0.0 })
        val (outcome, _) = run(job)
        assertEquals(DownloadOutcome.Offline, outcome)
        assertLessThan(server.count, 10)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun testCancellation() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val server = FakeServer(deepest = 20, patch = TileID(z = 0, x = 0, y = 0))
        val job = TileDownloadJob(shape = area, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = server, zooms = 0..20, pruneByParent = true)),
                                  concurrency = 2, retryDelay = { 0.0 })
        var outcome: DownloadOutcome? = null
        // A Swift task runs even when cancelled before it starts (ATOMIC does that here), and gives the job's outcome.
        val task = launch(start = CoroutineStart.ATOMIC) { outcome = job.run { } }
        task.cancel()
        task.join()
        assertEquals(DownloadOutcome.Cancelled, outcome)
    }
}
