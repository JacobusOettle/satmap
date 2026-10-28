package com.jacobusoettle.satmap.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

class ConcurrencyTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    @Test
    fun testMoreAtOnceWhileAnswersComeQuickly() {
        val c = ConcurrencyController(start = 16, min = 4, max = 32)
        assertEquals(16, c.limit)
        repeat(2_000) { c.record(seconds = 0.25, ok = true) }
        assertEquals(32, c.limit)
    }

    @Test
    fun testFewerAtOnceWhenRequestsFailOrSlowDown() {
        val c = ConcurrencyController(start = 32, min = 4, max = 32)
        // A burst of failures from one round of requests drops the limit once.
        repeat(10) { c.record(seconds = 20.0, ok = false) }
        assertEquals(24, c.limit)
        // Still failing, round after round: down to the minimum, not below.
        repeat(500) { c.record(seconds = 20.0, ok = false) }
        assertEquals(4, c.limit)
        // Slow answers count as trouble too.
        val slow = ConcurrencyController(start = 16, min = 4, max = 32)
        repeat(12) { slow.record(seconds = 8.0, ok = true) } // one round at the new limit: one drop
        assertEquals(12, slow.limit)
        slow.record(seconds = 8.0, ok = true) // the next round
        assertEquals(9, slow.limit)
        // A fixed number when min = max (as in tests and small jobs).
        val fixed = ConcurrencyController(start = 16, min = 4, max = 4)
        repeat(100) { fixed.record(seconds = 0.1, ok = true) }
        assertEquals(4, fixed.limit)
    }

    @Test
    fun testJobRunsMoreRequestsAtOnceOnAGoodConnection() = runTest {
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val probe = ParallelProbe()
        val lat = -33.92
        val lon = 18.85
        val square = CoverageShape(polygons = listOf(listOf(GeoPoint(lat = lat, lon = lon), GeoPoint(lat = lat, lon = lon + 0.02),
                                                            GeoPoint(lat = lat + 0.02, lon = lon + 0.02), GeoPoint(lat = lat + 0.02, lon = lon))))
        val job = TileDownloadJob(shape = square, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = probe, zooms = 0..17, pruneByParent = false)),
                                  concurrency = 32, retryDelay = { 0.0 })
        var last = DownloadProgress()
        val outcome = job.run { last = it }
        assertEquals(DownloadOutcome.Completed(failed = 0), outcome)
        assertGreaterThan(probe.most, 16) // grew past the starting 16
        assertLessThanOrEqual(probe.most, 32)
        assertGreaterThan(last.parallel, 16)
    }

    @Test
    fun testFewerAtOnceWhenAnswersQueueUpOnASlowLink() {
        // Answers at the connection's own pace keep it growing; answers well over it (data queueing on a slow link,
        // though under 5 s) bring it down.
        val c = ConcurrencyController(start = 16, min = 4, max = 32)
        repeat(100) { c.record(seconds = 0.33, ok = true) }
        val grown = c.limit
        assertGreaterThan(grown, 16)
        repeat(200) { c.record(seconds = 3.5, ok = true) }
        assertLessThan(c.limit, grown / 2)
    }

    @Test
    fun testOneRoundOfFailuresIsNotAnOutage() = runTest {
        // A short gap fails every request that was under way (16 at the start), and only those: the download goes on
        // (they are retried by the next run), rather than stopping as offline.
        val store = MBTiles(File(tempDir(), "s.mbtiles"), create = true)
        val lat = -33.92
        val lon = 18.85
        val square = CoverageShape(polygons = listOf(listOf(GeoPoint(lat = lat, lon = lon), GeoPoint(lat = lat, lon = lon + 0.01),
                                                            GeoPoint(lat = lat + 0.01, lon = lon + 0.01), GeoPoint(lat = lat + 0.01, lon = lon))))
        val job = TileDownloadJob(shape = square, layers = listOf(DownloadLayer(name = "sat", store = store, fetcher = FirstRoundFails(count = 16),
                                                                                zooms = 17..17, pruneByParent = false)),
                                  concurrency = 32, retryDelay = { 0.0 })
        val outcome = job.run { }
        assertEquals(DownloadOutcome.Completed(failed = 16), outcome)
    }
}

/** Counts how many requests run at the same time. */
private class ParallelProbe : TileFetching {
    private val lock = Any()
    private var running = 0
    var most = 0
        private set

    override suspend fun fetch(tile: TileID): TileFetchResult {
        synchronized(lock) {
            running += 1
            most = max(most, running)
        }
        delay(2)
        synchronized(lock) { running -= 1 }
        return TileFetchResult.Data(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0))
    }
}

/** Fails every request for the first `count` different tiles it is asked for (one round of requests hit by a gap).
 *  None of those answers until all of them have been asked for, as when a connection drops with a round of requests
 *  out: so they are the round sent first, in whatever order the coroutines happen to run (the job must send at least
 *  `count` at once). */
private class FirstRoundFails(private val count: Int) : TileFetching {
    private sealed interface Step {
        data class Answer(val fail: Boolean) : Step
        data object Wait : Step
        class Release(val others: List<Continuation<Unit>>) : Step
    }

    private val lock = Any()
    private val failing = mutableSetOf<TileID>()
    private var waiting = mutableListOf<Continuation<Unit>>()

    override suspend fun fetch(tile: TileID): TileFetchResult {
        val step: Step = synchronized(lock) {
            if (tile in failing) return@synchronized Step.Answer(fail = true)
            if (failing.size >= count) return@synchronized Step.Answer(fail = false)
            failing.add(tile)
            if (failing.size < count) return@synchronized Step.Wait
            val others = waiting
            waiting = mutableListOf()
            Step.Release(others)
        }
        when (step) {
            is Step.Answer -> return if (step.fail) TileFetchResult.Failed else TileFetchResult.Data(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0))
            Step.Wait -> suspendCoroutine { c ->
                val go = synchronized(lock) {
                    if (failing.size >= count) return@synchronized true
                    waiting.add(c)
                    false
                }
                if (go) c.resume(Unit)
            }
            is Step.Release -> step.others.forEach { it.resume(Unit) }
        }
        return TileFetchResult.Failed
    }
}
