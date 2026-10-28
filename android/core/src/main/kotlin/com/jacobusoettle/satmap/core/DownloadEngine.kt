package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.nanoseconds

sealed interface TileFetchResult {
    class Data(val data: ByteArray) : TileFetchResult

    /** The server has no tile at this address (e.g. HTTP 404 with Esri `blankTile=false`). */
    data object Missing : TileFetchResult

    /** A transient failure worth retrying (timeout, 5xx, 429). */
    data object Failed : TileFetchResult

    /** The device has no network; the job stops and resumes later. */
    data object Offline : TileFetchResult
}

interface TileFetching {
    /** A request cancelled with the download may throw `CancellationException`: it counts as `Failed`, as a
     *  cancelled request does on iOS. */
    suspend fun fetch(tile: TileID): TileFetchResult
}

/** One layer of an offline download (satellite imagery or elevation). */
data class DownloadLayer(
    val name: String,
    val store: MBTiles,
    val fetcher: TileFetching,
    val zooms: IntRange,
    /** Only request a tile if its parent exists. Imagery providers stop at different zooms in different
     *  places (rural areas often at z18, towns at z20+); a missing parent means every child is missing too,
     *  so this avoids millions of pointless requests when downloading "up to the maximum". */
    val pruneByParent: Boolean,
    /** Download this shape instead of the job's (imagery only along roads); null = the job's shape. */
    val shape: CoverageShape? = null,
)

data class DownloadProgress(
    var layer: String = "",
    var zoom: Int = 0,
    var maxZoom: Int = 0,
    /** Tiles handled at the current zoom level / tiles to handle at this level. */
    var levelDone: Int = 0,
    var levelTotal: Int = 0,
    /** Tiles fetched during this run. */
    var fetched: Int = 0,
    var bytes: Long = 0,
    var missing: Int = 0,
    var failed: Int = 0,
    /** The whole run as one number, for a single progress bar: the tiles planned over every layer and level (an upper
     *  bound, as deeper levels shrink where the imagery stops), and how many of them are behind. */
    var overallDone: Long = 0,
    var overallTotal: Long = 0,
    /** Requests running at once at the moment. */
    var parallel: Int = 0,
)

sealed interface DownloadOutcome {
    /** Every level was processed. `failed` tiles (and their children) are retried by running the job again. */
    data class Completed(val failed: Int) : DownloadOutcome
    data object Cancelled : DownloadOutcome
    data object Offline : DownloadOutcome

    /** Tiles could not be saved (usually the phone is full). */
    data class StorageError(val message: String) : DownloadOutcome
}

/** How many tile requests to keep going at once. Tile servers answer each request in about a quarter of a second
 *  however many run together, so more at once is faster, until the connection is full: then answers queue up behind
 *  each other (which also slows the map on screen) or time out. So it starts in the middle, adds one for every `limit`
 *  quick answers (up to `max`), and drops by a quarter (down to `min`) at most once per round of requests when one
 *  fails or is slow: over `slowSeconds`, or well over the connection's own answer time (the fastest seen lately). */
class ConcurrencyController(start: Int, min: Int, max: Int, val slowSeconds: Double = 5.0) {
    val min: Int = kotlin.math.max(1, min)
    val max: Int = kotlin.math.max(this.min, max)
    var limit: Int = kotlin.math.min(this.max, kotlin.math.max(this.min, start))
        private set
    private var quick = 0
    private var finished = 0L
    private var lastDrop = Long.MIN_VALUE / 2

    /** The fastest answer seen lately (it creeps up slowly, so a change of network is noticed). */
    var baseSeconds: Double? = null
        private set

    fun record(seconds: Double, ok: Boolean) {
        finished += 1
        val base = kotlin.math.min(baseSeconds?.let { it * 1.002 } ?: seconds, seconds)
        baseSeconds = base
        if (!ok || seconds > slowSeconds || seconds > kotlin.math.max(2 * base, base + 1)) {
            quick = 0
            // The requests already under way were sent at the old rate: one drop per round.
            if (finished - lastDrop >= limit) {
                limit = kotlin.math.max(min, limit * 3 / 4)
                lastDrop = finished
            }
            return
        }
        quick += 1
        if (quick >= limit) {
            limit = kotlin.math.min(max, limit + 1)
            quick = 0
        }
    }
}

/** Downloads every tile of a coverage shape, level by level, skipping tiles already stored, so
 *  running it again resumes where it left off. */
class TileDownloadJob(
    val shape: CoverageShape,
    val layers: List<DownloadLayer>,
    concurrency: Int = 8,
    maxAttempts: Int = 4,
    outageThreshold: Int = 16,
    /** Seconds to wait before retry n (n = 1, 2, ...). */
    val retryDelay: (Int) -> Double = { doubleArrayOf(1.0, 3.0, 8.0, 15.0)[min(it - 1, 3)] },
) {
    /** The tiles to fetch at one level, x and y packed into one `Long` each (a level can have millions). */
    private class Packed {
        var size = 0
            private set
        private var items = LongArray(256)

        fun add(x: Int, y: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size] = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
            size += 1
        }

        fun x(i: Int): Int = (items[i] shr 32).toInt()
        fun y(i: Int): Int = items[i].toInt()
    }

    /** A request's answer: the tile, the result, how long its last attempt took, how many attempts it took, and the
     *  order it was sent in. */
    private class Answer(val tile: TileID, val result: TileFetchResult, val seconds: Double, val attempts: Int, val seq: Int)

    /** The most requests at once; how many really run adapts to the connection (`ConcurrencyController`). */
    val concurrency: Int = max(1, concurrency)
    val maxAttempts: Int = max(1, maxAttempts)

    /** This many failed tiles in a row (after retries) with no success means the connection is down even if the
     *  phone says it is online (e.g. Wi-Fi up but the internet behind it is not): stop as `Offline` instead of
     *  marking the rest of the area failed. */
    val outageThreshold: Int = max(1, outageThreshold)

    /** Runs in the calling coroutine's context; call it off the main thread (e.g. on `Dispatchers.IO`), as it reads and
     *  writes the stores as it goes. Cancelling that coroutine ends the run with `Cancelled`: as in the Swift, it
     *  returns rather than throws, once the requests under way have answered. */
    suspend fun run(report: (DownloadProgress) -> Unit): DownloadOutcome {
        val context = currentCoroutineContext()

        /** Swift's `Task.isCancelled`. */
        fun isCancelled() = !context.isActive

        val progress = DownloadProgress()
        var lastReport = Double.NEGATIVE_INFINITY
        var failuresInARow = 0
        // Tiles sent so far, and the count when the current streak of failures began.
        var launched = 0
        var streakStart = 0
        var failedSinceStreak = false
        fun emit(force: Boolean = false) {
            val now = System.nanoTime() / 1e9
            if (force || now - lastReport >= 0.25) {
                lastReport = now
                report(progress.copy())
            }
        }

        // Overall progress, in tiles: the levels done, the one under way, and an estimate of the rest. Where imagery
        // stops (most of the deepest levels on farms), a level only has tiles under the ones that exist one level up,
        // so deeper levels are estimated from the share of tiles found at this one (starting from the share found one
        // level up); levels not started yet count at their full size.
        val planned = layers.map { layer -> layer.zooms.map { TileCoverage(shape = layer.shape ?: shape, zoom = it).tileCount } }
        var behind = 0L
        var currentLayer = 0
        var currentLevel = -1
        var pruning = false
        var levelStored = 0
        var previousShare = 1.0
        fun updateOverall() {
            var rest = 0L
            for (li in currentLayer until planned.size) {
                for (zi in planned[li].indices) {
                    if (!(li > currentLayer || zi > currentLevel)) continue
                    if (!(li == currentLayer && pruning)) {
                        rest += planned[li][zi]
                        continue
                    }
                    // Tiles found at this level so far, weighed against the share found one level up.
                    val share = (levelStored.toDouble() + previousShare * 8) / (progress.levelDone.toDouble() + 8)
                    val expected = progress.levelTotal.toDouble() * share * 4.0.pow((zi - currentLevel).toDouble())
                    rest += min(planned[li][zi], min(expected, (Long.MAX_VALUE / 4).toDouble()).toLong())
                }
            }
            progress.overallDone = behind + progress.levelDone
            progress.overallTotal = behind + progress.levelTotal + rest
        }
        progress.levelTotal = 0
        updateOverall()

        for ((li, layer) in layers.withIndex()) {
            progress.layer = layer.name
            progress.maxZoom = layer.zooms.last
            val zoomCount = layer.zooms.last - layer.zooms.first + 1
            try {
                // Each layer comes from its own server.
                val parallel = ConcurrencyController(start = min(16, concurrency), min = min(4, concurrency), max = concurrency)
                currentLayer = li
                previousShare = 1.0

                for ((zi, z) in layer.zooms.withIndex()) {
                    if (isCancelled()) return DownloadOutcome.Cancelled
                    val coverage = TileCoverage(shape = layer.shape ?: shape, zoom = z)
                    val prune = layer.pruneByParent && z > layer.zooms.first && z > 0

                    val todo = Packed()
                    var total = 0
                    var alreadyDone = 0
                    var alreadyStored = 0
                    for (row in coverage.rows) {
                        if (isCancelled()) return DownloadOutcome.Cancelled
                        for (range in row.ranges) {
                            val existing = layer.store.existingColumns(z = z, y = row.y, xs = range)
                            val missing = layer.store.missingColumns(z = z, y = row.y, xs = range)
                            val parents: Set<Int>? = if (prune) {
                                layer.store.existingColumns(z = z - 1, y = row.y shr 1, xs = (range.first shr 1)..(range.last shr 1))
                            } else {
                                null
                            }
                            for (x in range) {
                                if (parents != null && (x shr 1) !in parents) continue
                                total += 1
                                if (x in existing) {
                                    alreadyDone += 1
                                    alreadyStored += 1
                                } else if (x in missing) {
                                    alreadyDone += 1
                                } else {
                                    todo.add(x = x, y = row.y)
                                }
                            }
                        }
                    }

                    progress.zoom = z
                    progress.levelTotal = total
                    progress.levelDone = alreadyDone
                    currentLevel = zi
                    pruning = layer.pruneByParent
                    levelStored = alreadyStored
                    updateOverall()
                    emit(force = true)

                    // Nothing exists at this level, so nothing can exist deeper either.
                    if (prune && total == 0) {
                        currentLevel = zoomCount - 1
                        progress.levelTotal = 0
                        progress.levelDone = 0
                        updateOverall()
                        break
                    }
                    if (todo.size == 0) {
                        behind += total
                        previousShare = if (total > 0) levelStored.toDouble() / total.toDouble() else previousShare
                        progress.levelTotal = 0
                        progress.levelDone = 0
                        continue
                    }

                    var wentOffline = false
                    var storeFailed = false
                    try {
                        // The Swift task group: a coroutine per request, their answers taken as they come.
                        coroutineScope {
                            val answers = Channel<Answer>(Channel.UNLIMITED)
                            var next = 0
                            var inFlight = 0
                            fun launchNext() {
                                val tile = TileID(z = z, x = todo.x(next), y = todo.y(next))
                                next += 1
                                inFlight += 1
                                val seq = launched
                                launched += 1
                                var answer: Answer? = null
                                // Every request answers, even one cancelled before it started.
                                launch {
                                    val (result, seconds, attempts) = fetchWithRetry(layer.fetcher, tile)
                                    answer = Answer(tile, result, seconds, attempts, seq)
                                }.invokeOnCompletion {
                                    answers.trySend(answer ?: Answer(tile, TileFetchResult.Failed, 0.0, 1, seq))
                                }
                            }
                            while (next < todo.size && inFlight < parallel.limit) launchNext()
                            while (inFlight > 0) {
                                // As the Swift group's `next()`: every answer is taken, also after a cancellation.
                                val a = withContext(NonCancellable) { answers.receive() }
                                inFlight -= 1
                                when (a.result) {
                                    // A tile that needed another try counts as trouble too.
                                    is TileFetchResult.Data, TileFetchResult.Missing -> parallel.record(seconds = a.seconds, ok = a.attempts == 1)
                                    TileFetchResult.Failed -> parallel.record(seconds = a.seconds, ok = false)
                                    TileFetchResult.Offline -> {}
                                }
                                progress.parallel = parallel.limit
                                if (a.result == TileFetchResult.Failed) {
                                    if (failuresInARow == 0) streakStart = launched
                                    failuresInARow += 1
                                    // Many requests run at once, so one short gap fails a whole round of them together: the
                                    // connection counts as down only when tiles sent after the failures began fail as well.
                                    if (a.seq >= streakStart) failedSinceStreak = true
                                    if (failuresInARow >= outageThreshold && failedSinceStreak) wentOffline = true
                                } else {
                                    failuresInARow = 0
                                    failedSinceStreak = false
                                }
                                when (val result = a.result) {
                                    is TileFetchResult.Data -> {
                                        if (layer.store.put(a.tile, data = result.data)) {
                                            progress.fetched += 1
                                            progress.bytes += result.data.size.toLong()
                                            levelStored += 1
                                        } else {
                                            storeFailed = true
                                        }
                                    }
                                    TileFetchResult.Missing -> if (layer.store.putMissing(a.tile)) progress.missing += 1 else storeFailed = true
                                    TileFetchResult.Failed -> progress.failed += 1
                                    TileFetchResult.Offline -> wentOffline = true
                                }
                                progress.levelDone += 1
                                updateOverall()
                                emit()
                                if (wentOffline || storeFailed || isCancelled()) {
                                    coroutineContext.cancelChildren()
                                    continue
                                }
                                while (next < todo.size && inFlight < parallel.limit) launchNext()
                            }
                        }
                    } catch (e: CancellationException) {
                        // The run was cancelled (every request has answered by now): it ends below, as in the Swift.
                        if (!isCancelled()) throw e
                    }
                    if (!layer.store.flush() || storeFailed) {
                        return DownloadOutcome.StorageError(layer.store.writeError ?: "Couldn't save map tiles.")
                    }
                    if (wentOffline || isCancelled()) {
                        emit(force = true)
                        return if (wentOffline) DownloadOutcome.Offline else DownloadOutcome.Cancelled
                    }
                    behind += total
                    previousShare = if (total > 0) levelStored.toDouble() / total.toDouble() else previousShare
                    progress.levelTotal = 0
                    progress.levelDone = 0
                    updateOverall()
                    emit(force = true)
                }
                // The layer is done (or stopped where its imagery stops): nothing more of it is to come.
                currentLevel = zoomCount - 1
                pruning = false
            } finally {
                layer.store.checkpoint()
            }
        }
        currentLayer = layers.size
        progress.overallTotal = behind
        progress.overallDone = behind
        emit(force = true)
        return DownloadOutcome.Completed(failed = progress.failed)
    }

    /** The result, how long the last attempt took and how many attempts it took. */
    private suspend fun fetchWithRetry(fetcher: TileFetching, tile: TileID): Triple<TileFetchResult, Double, Int> {
        var attempt = 1
        while (true) {
            if (!currentCoroutineContext().isActive) return Triple(TileFetchResult.Failed, 0.0, attempt)
            val started = System.nanoTime()
            val r = try {
                fetcher.fetch(tile)
            } catch (e: CancellationException) {
                TileFetchResult.Failed
            }
            val seconds = (System.nanoTime() - started) / 1e9
            if (r != TileFetchResult.Failed || attempt >= maxAttempts) return Triple(r, seconds, attempt)
            val delay = retryDelay(attempt)
            if (delay > 0) {
                try {
                    kotlinx.coroutines.delay((delay * 1_000_000_000).toLong().nanoseconds)
                } catch (e: CancellationException) {
                    // Swift's `try?`: a cancelled wait just ends.
                }
            }
            attempt += 1
        }
    }
}
