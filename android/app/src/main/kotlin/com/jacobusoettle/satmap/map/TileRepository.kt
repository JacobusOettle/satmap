package com.jacobusoettle.satmap.map

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.DEMSampler
import com.jacobusoettle.satmap.core.DEMTile
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.Terrarium
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WebMercator
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where map tiles come from: downloaded offline areas first, then the network (if online), and for zooms
 *  deeper than the imagery goes, the nearest ancestor tile (which the imagery layer enlarges).
 *
 *  The logic is the Swift's, line for line; the lock is `synchronized`, requests in flight are shared `Deferred`s in
 *  `scope`. The internal constructor takes the network, the clock and the offline files, so tests can give their own
 *  (DESIGN.md §7.3). */
class TileRepository internal constructor(
    /** Tiles from the network, with a 1 GiB disk cache (OkHttp has no memory cache: `ImageryMemory` is that). */
    val browseClient: OkHttpClient,
    /** One request (`HTTPTileFetcher.fetch` on `browseClient`). */
    private val fetchURL: suspend (String) -> TileFetchResult = { HTTPTileFetcher.fetch(it, browseClient) },
    /** `Connectivity.isOnlineNow`. */
    private val isOnlineNow: () -> Boolean = { Connectivity.shared.isOnlineNow },
    /** Seconds on the clock of the tile gates (`SystemClock.uptimeMillis`, DESIGN.md §2.4). */
    private val now: () -> Double = { SystemClock.uptimeMillis() / 1000.0 },
    /** Opens an area's file for reading, or null (an `MBTiles` reader through `MBTiles.driver`). */
    private val openStore: (File) -> OfflineStore? = { openMBTiles(it) },
) : ElevationSource {
    private constructor(context: Context) : this(TileInterceptor.browseClient(context))

    companion object {
        /** Set by `init` at startup. Settable, so a test can install its own. */
        lateinit var shared: TileRepository

        fun init(context: Context) {
            shared = TileRepository(context.applicationContext)
        }

        /** An area's file as `MBTiles` (opened for reading and writing, as Swift does, so the map reads while a download
         *  writes), or null if it doesn't exist or can't be opened. */
        private fun openMBTiles(file: File): OfflineStore? {
            if (!file.exists()) return null
            return try {
                MBTilesStore(MBTiles(file, create = false))
            } catch (e: Exception) {
                // Swift `try?`. Also a driver not set yet (`MBTiles.driver`).
                null
            }
        }
    }

    enum class Layer(val fileName: String) { imagery("imagery.mbtiles"), terrain("terrain.mbtiles") }

    data class AreaRef(
        val id: UUID,
        val directory: File,
        /** Web-mercator world bounds of the area (including its buffer). */
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    )

    /** The best imagery for a spot: its own tile, or the nearest coarser one (`beyondDepth`: the place has no imagery
     *  that deep). */
    class NearestImagery(val tile: TileID, val data: ByteArray, val beyondDepth: Boolean)

    /** What the map reads from an area's file: an `MBTiles` reader, or a fake in tests. */
    interface OfflineStore : Closeable {
        fun tile(t: TileID): ByteArray?

        /** Whether the downloader recorded that the server has no tile here. */
        fun isMissing(t: TileID): Boolean
        fun contains(t: TileID): Boolean
    }

    /** `MBTiles` reads never throw in Swift; a failing read here is a tile not had. */
    private class MBTilesStore(private val m: MBTiles) : OfflineStore {
        override fun tile(t: TileID): ByteArray? = try {
            m.tile(t)
        } catch (e: RuntimeException) {
            null
        }

        override fun isMissing(t: TileID): Boolean = try {
            m.isMissing(t)
        } catch (e: RuntimeException) {
            false
        }

        override fun contains(t: TileID): Boolean = try {
            m.contains(t)
        } catch (e: RuntimeException) {
            false
        }

        override fun close() {
            try {
                m.close()
            } catch (e: RuntimeException) {
                Log.e("tiles", "closing ${m.file.path} failed", e)
            }
        }
    }

    private class OpenArea(
        /** Replaced when the area grows (the area along your track is updated as the track gets longer). */
        var ref: AreaRef,
    ) {
        val stores = HashMap<String, OfflineStore>()
        val lastAttempt = HashMap<String, Double>()
    }

    private val lock = Any()
    private var areas: List<OpenArea> = emptyList()

    /** Tile and DEM loads shared between callers: they outlive a cancelled MapLibre request, as iOS's detached loads do. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** MapLibre asks for dozens of tiles at once. Requests queued inside OkHttp time out while they wait,
     *  so only a few are started at a time per server, newest first (the tiles for where the map is now).
     *  Imagery and elevation have separate queues so elevation (contours) isn't starved while the map moves. */
    private val imageryLimiter = RequestLimiter(10)
    private val terrainLimiter = RequestLimiter(4)

    /** A network that is "connected" but can't reach the internet (e.g. a drone controller's Wi-Fi) makes every
     *  request time out. After several consecutive failures the network is skipped for a while. */
    private var consecutiveFailures = 0
    private var networkPausedUntil = Double.NEGATIVE_INFINITY

    // Elevation tiles decoded in memory (each 256 KB); a screen of contours at zoom 11–15 reads ~100 of them.
    private val demCache = LRUCache<TileID, DEMTile>(160)

    /** A tile could not be loaded for a temporary reason (timeout, no internet): MapLibre is told to ask again later
     *  (`Cache-Control`), and the map reloads its tiles once the network is back. */
    private var retryNeeded = false
    private var retryNeededSince = Double.POSITIVE_INFINITY

    /** A request that failed for a temporary reason, used to test whether the network has recovered. */
    private var lastFailedURL: String? = null
    private var lastSuccessAt = Double.NEGATIVE_INFINITY
    private val demInFlight = HashMap<TileID, Deferred<DEMTile?>>()

    /** Elevation tiles not to request again before the given time (after a failure or a missing tile). */
    private val demRetryAfter = HashMap<TileID, Double>()

    /** Imagery tiles the server said don't exist (beyond the imagery depth), to skip asking again. */
    private val imageryMissing = HashSet<TileID>()

    /** Imagery requests under way; `started` once one has a connection (then it is shared whatever the priority). */
    private class InFlight(val task: Deferred<TileFetchResult>, val priority: RequestPriority, val id: Int) {
        var started = false
    }

    private val imageryInFlight = HashMap<TileID, InFlight>()
    private var imageryRequests = 0

    // Offline areas

    fun setAreas(refs: List<AreaRef>) {
        synchronized(lock) {
            val keep = refs.map { it.id }.toSet()
            for (a in areas) {
                if (a.ref.id !in keep) a.stores.values.forEach { it.close() }
            }
            val next = ArrayList<OpenArea>()
            for (r in refs) {
                val open = areas.firstOrNull { it.ref.id == r.id }
                if (open != null) {
                    open.ref = r // bounds may have changed; the open stores stay valid
                    next.add(open)
                } else {
                    next.add(OpenArea(r))
                }
            }
            areas = next
        }
    }

    /** Closes the readers of an area (before its files are deleted). */
    fun closeArea(id: UUID) {
        synchronized(lock) {
            areas.firstOrNull { it.ref.id == id }?.let { a ->
                a.stores.values.forEach { it.close() }
                a.stores.clear()
            }
            areas = areas.filter { it.ref.id != id }
        }
    }

    fun offlineTile(t: TileID, layer: Layer): ByteArray? {
        val b = t.worldBounds
        val stores = synchronized(lock) {
            areas.filter { it.ref.minX < b.maxX && it.ref.maxX > b.minX && it.ref.minY < b.maxY && it.ref.maxY > b.minY }
                .mapNotNull { store(it, layer) }
        }
        for (s in stores) {
            s.tile(t)?.let { return it }
        }
        return null
    }

    /** True if a downloaded area recorded this tile, or an ancestor of it, as not existing on the server. */
    private fun offlineKnowsMissing(t: TileID): Boolean {
        val b = t.worldBounds
        val stores = synchronized(lock) {
            areas.filter { it.ref.minX < b.maxX && it.ref.maxX > b.minX && it.ref.minY < b.maxY && it.ref.maxY > b.minY }
                .mapNotNull { store(it, Layer.imagery) }
        }
        for (s in stores) {
            for (k in 0..min(4, t.z)) {
                val a = t.ancestor(levels = k)
                if (s.isMissing(a)) return true
                if (k > 0 && s.contains(a)) break
            }
        }
        return false
    }

    /** Opens an area's store lazily (call with lock held). The file appears once its download starts. */
    private fun store(a: OpenArea, layer: Layer): OfflineStore? {
        a.stores[layer.fileName]?.let { return it }
        val last = a.lastAttempt[layer.fileName]
        if (last != null && now() - last < 10) return null
        a.lastAttempt[layer.fileName] = now()
        val s = openStore(File(a.ref.directory, layer.fileName)) ?: return null
        a.stores[layer.fileName] = s
        return s
    }

    // Imagery for display

    /** The tile's own imagery, or if it can't be had (deeper than the imagery goes, or not saved offline and not
     *  loaded) the nearest coarser tile that exists, as it is: the interceptor enlarges what it has itself.
     *  `beyondDepth`: the tile itself doesn't exist. */
    suspend fun nearestImagery(t: TileID, urgent: Boolean = false): NearestImagery? {
        val priority = if (urgent) RequestPriority.urgent else RequestPriority.normal
        offlineTile(t, Layer.imagery)?.let { return NearestImagery(t, it, false) }
        // Inside a downloaded area the downloader already learnt where the imagery ends: don't wait on the
        // network for tiles it knows don't exist.
        var beyondDepth = offlineKnowsMissing(t)
        var online = networkUsable && !beyondDepth
        // Not tried because of a pause after failures: reloaded once the network is back (as for elevation).
        if (!online && !beyondDepth && isOnlineNow()) noteRetryNeeded()
        if (online) {
            when (val r = onlineImagery(t, priority)) {
                is TileFetchResult.Data -> return NearestImagery(t, r.data, false)
                TileFetchResult.Missing -> beyondDepth = true // deeper than the imagery goes here: an ancestor below
                TileFetchResult.Failed, TileFetchResult.Offline -> {
                    // Don't show (and have MapLibre keep) a blurry stand-in for a tile that exists but failed
                    // to load; only offline ancestors are used, and the tile is asked for again later.
                    online = false
                    noteRetryNeeded()
                }
            }
        }
        // The nearest ancestor that exists.
        for (k in 1..max(1, min(8, t.z))) {
            if (t.z - k < 0) continue
            val a = t.ancestor(levels = k)
            var data = offlineTile(a, Layer.imagery)
            if (data == null && online) {
                when (val r = onlineImagery(a, priority)) {
                    is TileFetchResult.Data -> data = r.data
                    TileFetchResult.Missing -> {}
                    TileFetchResult.Failed, TileFetchResult.Offline -> {
                        // Same rule as above: no coarser stand-in for an ancestor that failed to load.
                        online = false
                        noteRetryNeeded()
                    }
                }
            }
            if (data != null) return NearestImagery(a, data, beyondDepth)
        }
        return null
    }

    /** A tile's imagery only if it is at hand (offline areas, or online in the background, after on-screen tiles):
     *  for getting ready for the next zoom or pan. Null rather than a stand-in. */
    suspend fun imageryInBackground(t: TileID): ByteArray? {
        offlineTile(t, Layer.imagery)?.let { return it }
        if (!networkUsable || offlineKnowsMissing(t)) return null
        return (onlineImagery(t, RequestPriority.background) as? TileFetchResult.Data)?.data
    }

    /** One network request per imagery tile at a time (many deep tiles share the same ancestor), unless a tile wanted
     *  sooner is still waiting its turn behind on-screen ones: then it is asked for again, at its own priority, and
     *  the request it overtook fetches nothing and answers with the new one's result. Tiles the server doesn't have
     *  are remembered. */
    private suspend fun onlineImagery(t: TileID, priority: RequestPriority): TileFetchResult {
        val task = synchronized(lock) {
            if (t in imageryMissing) return@synchronized null
            val existing = imageryInFlight[t]
            if (existing != null && (existing.started || existing.priority >= priority)) return@synchronized existing.task
            imageryRequests += 1
            val id = imageryRequests
            val url = TileSources.imageryURL(t)
            // Created and entered under the lock, so the request's own checks below see it.
            val task = scope.async {
                val fetched = limitedFetch(url, priority) {
                    synchronized(lock) {
                        val f = imageryInFlight[t]
                        if (f == null || f.id != id) {
                            false
                        } else {
                            f.started = true
                            true
                        }
                    }
                }
                val result = fetched
                    // Overtaken while waiting.
                    ?: synchronized(lock) { imageryInFlight[t]?.task }?.await()
                    ?: limitedFetch(url, priority)
                // Swift does this in each caller after the result; here in the request, so a caller that is cancelled
                // meanwhile (MapLibre dropped the tile) doesn't leave it behind.
                synchronized(lock) {
                    if (imageryInFlight[t]?.id == id) imageryInFlight.remove(t)
                    if (result == TileFetchResult.Missing) {
                        if (imageryMissing.size > 20_000) imageryMissing.clear()
                        imageryMissing.add(t)
                    }
                }
                result
            }
            imageryInFlight[t] = InFlight(task, priority, id)
            task
        } ?: return TileFetchResult.Missing
        return task.await()
    }

    /** Whether to try the network for on-screen tiles right now. */
    private val networkUsable: Boolean
        get() = isOnlineNow() && synchronized(lock) { now() >= networkPausedUntil }

    private suspend fun limitedFetch(url: String, priority: RequestPriority = RequestPriority.normal): TileFetchResult =
        limitedFetch(url, priority) { true } ?: TileFetchResult.Failed

    /** A request when a connection is free. `start` is asked then: if it says no, nothing is fetched (null). Failures
     *  in the background don't count towards pausing the network: only on-screen ones say it is down. */
    private suspend fun limitedFetch(url: String, priority: RequestPriority, start: () -> Boolean): TileFetchResult? {
        val parsed = url.toHttpUrlOrNull()
        val elevation = parsed?.host?.startsWith("elevation") == true
        val path = parsed?.encodedPath ?: url
        val limiter = if (elevation) terrainLimiter else imageryLimiter
        val asked = now()
        limiter.acquire(priority)
        try {
            if (!start()) return null
            if (!networkUsable) return TileFetchResult.Offline
            val started = now()
            val result = fetchURL(url)
            val ms = ((now() - started) * 1000).toInt()
            val outcome = describe(result).take(7)
            if (!elevation) {
                val waited = ((started - asked) * 1000).toInt()
                Log.d("tiles", "imagery $priority waited $waited ms, $outcome in $ms ms: $path")
            }
            synchronized(lock) {
                when (result) {
                    is TileFetchResult.Data, TileFetchResult.Missing -> {
                        consecutiveFailures = 0
                        lastSuccessAt = now()
                    }
                    TileFetchResult.Failed, TileFetchResult.Offline -> {
                        Log.e("tiles", "fetch failed (${describe(result)}) after $ms ms: $path")
                        if (priority != RequestPriority.background) {
                            lastFailedURL = url
                            consecutiveFailures += 1
                            if (consecutiveFailures >= 8) {
                                Log.e("tiles", "network paused for 45 s after repeated failures")
                                networkPausedUntil = now() + 45
                                consecutiveFailures = 0
                            }
                        }
                    }
                }
            }
            if (elevation) Log.d("tiles", "elevation fetch $outcome in $ms ms: $path")
            return result
        } finally {
            limiter.release(priority)
        }
    }

    /** Swift's `String(describing:)` of a result. */
    private fun describe(r: TileFetchResult): String = when (r) {
        is TileFetchResult.Data -> "data(${r.data.size} bytes)"
        TileFetchResult.Missing -> "missing"
        TileFetchResult.Failed -> "failed"
        TileFetchResult.Offline -> "offline"
    }

    private fun noteRetryNeeded() {
        synchronized(lock) { markRetryNeededLocked() }
    }

    private fun markRetryNeededLocked() {
        if (!retryNeeded) retryNeededSince = now()
        retryNeeded = true
    }

    /** True if tiles failed for temporary reasons and the network has recovered (a request that failed before now
     *  succeeds), so reloading the map's tiles is worthwhile. On a still-poor connection this stays false. */
    suspend fun probeRecovery(): Boolean {
        if (!networkUsable) return false
        val (needed, since, probe, success) = synchronized(lock) {
            Probe(retryNeeded, retryNeededSince, lastFailedURL, lastSuccessAt)
        }
        if (!needed) return false
        // Some request has already succeeded since the failures: recovered. Otherwise ask again for one that failed.
        var recovered = success > since
        if (!recovered && probe != null) {
            recovered = when (limitedFetch(probe)) {
                is TileFetchResult.Data, TileFetchResult.Missing -> true
                TileFetchResult.Failed, TileFetchResult.Offline -> false
            }
        }
        if (recovered || probe == null) {
            synchronized(lock) {
                retryNeeded = false
                retryNeededSince = Double.POSITIVE_INFINITY
                lastFailedURL = null
            }
        }
        return recovered
    }

    private data class Probe(val needed: Boolean, val since: Double, val url: String?, val success: Double)

    /** Forget temporary failures (when the network comes back). What the server said doesn't exist is kept. */
    fun resetFailureState() {
        synchronized(lock) {
            demRetryAfter.clear()
            consecutiveFailures = 0
            networkPausedUntil = Double.NEGATIVE_INFINITY
        }
    }

    /** Free memory while in the background, where large apps are the first to be terminated
     *  (which would also stop track recording). */
    fun trimForBackground() {
        demCache.removeAll()
        // (iOS also empties URLCache's memory here: OkHttp's cache is on disk only.)
        synchronized(lock) {
            imageryMissing.clear()
            for (a in areas) {
                a.stores.values.forEach { it.close() }
                a.stores.clear()
                a.lastAttempt.clear()
            }
        }
    }

    /** iOS gives URLCache its memory back here. OkHttp has no memory cache (`ImageryMemory` plays that part), so there
     *  is nothing to do. */
    fun restoreAfterBackground() {}

    // Elevation

    suspend fun demTile(t: TileID): DEMTile? {
        demCache[t]?.let { return it }
        offlineTile(t, Layer.terrain)?.let { d ->
            val tile = withContext(Work.contours) { Terrarium.decode(d, id = t) }
            if (tile != null) {
                demCache[t] = tile
                return tile
            }
        }
        if (!networkUsable) {
            if (isOnlineNow()) noteRetryNeeded()
            return null
        }

        // One network request per tile, shared by every caller that needs it meanwhile.
        val task = synchronized(lock) {
            val after = demRetryAfter[t]
            if (after != null && now() < after) return@synchronized null
            demInFlight[t] ?: scope.async {
                val result = limitedFetch(TileSources.terrainURL(t))
                val tile = if (result is TileFetchResult.Data) withContext(Work.contours) { Terrarium.decode(result.data, id = t) } else null
                // In the request rather than in each caller, as for imagery.
                synchronized(lock) {
                    demInFlight.remove(t)
                    when {
                        tile != null -> demRetryAfter.remove(t)
                        result == TileFetchResult.Failed || result == TileFetchResult.Offline -> {
                            // Temporary: try again soon, and reload the map once the network works.
                            demRetryAfter[t] = now() + 30
                            markRetryNeededLocked()
                        }
                        // Missing on the server or undecodable: retrying won't help for a while.
                        else -> demRetryAfter[t] = now() + 600
                    }
                }
                if (tile != null) demCache[t] = tile
                tile
            }.also { demInFlight[t] = it }
        } ?: return null
        return task.await()
    }

    /** Fetches (concurrently) every elevation tile a sampler at `zoom` needs for the world rectangle.
     *  `second` (complete) is false if any of them couldn't be loaded. */
    suspend fun demTiles(zoom: Int, minX: Double, minY: Double, maxX: Double, maxY: Double): Pair<Map<TileID, DEMTile>, Boolean> {
        val n = 1 shl zoom
        val scale = (256 shl zoom).toDouble()
        val worldPixels = 256 * n
        val px0 = max(0, floor(minX * scale - 0.5).toInt())
        val py0 = max(0, floor(minY * scale - 0.5).toInt())
        val px1 = min(worldPixels - 1, floor(maxX * scale - 0.5).toInt() + 1)
        val py1 = min(worldPixels - 1, floor(maxY * scale - 0.5).toInt() + 1)
        if (!(px0 <= px1 && py0 <= py1)) return Pair(emptyMap(), false)
        val ids = ArrayList<TileID>()
        for (ty in (py0 / 256)..(py1 / 256)) {
            for (tx in (px0 / 256)..(px1 / 256)) ids.add(TileID(z = zoom, x = tx, y = ty))
        }
        val tiles = coroutineScope {
            ids.map { id -> async { Pair(id, demTile(id)) } }.awaitAll()
        }.mapNotNull { (id, tile) -> tile?.let { Pair(id, it) } }.toMap()
        return Pair(tiles, tiles.size == ids.size)
    }

    /** Ground elevation in metres (bilinear on the finest available terrain zoom), or null without data. Safe to call
     *  from the main thread. */
    override suspend fun elevation(p: GeoPoint): Double? = withContext(Dispatchers.IO) {
        val w = WebMercator.world(p)
        var z = Terrarium.maxZoom
        while (z >= 10) {
            val tiles = demTiles(zoom = z, minX = w.x, minY = w.y, maxX = w.x, maxY = w.y).first
            if (tiles.isNotEmpty()) {
                DEMSampler(zoom = z, tile = { tiles[it] }).elevation(worldX = w.x, worldY = w.y)?.let { return@withContext it }
            }
            z -= 1
        }
        null
    }

    fun trimMemory() {
        demCache.removeAll()
    }
}

/** Fetches a single tile over HTTP and classifies the response for the downloader and the map (DESIGN.md D14).
 *  `wifiOnly`: the download may not use a metered network (iOS `allowsExpensiveNetworkAccess = false`). */
class HTTPTileFetcher(
    private val client: OkHttpClient,
    private val url: (TileID) -> String,
    private val wifiOnly: Boolean = false,
) : TileFetching {
    override suspend fun fetch(tile: TileID): TileFetchResult = fetch(url(tile), client, wifiOnly)

    companion object {
        /** Whether the device is online, and whether its network is metered: `Connectivity`'s snapshots (a test sets
         *  its own). */
        internal var network: () -> Connectivity.State =
            { Connectivity.State(isOnline = Connectivity.shared.isOnlineNow, isExpensive = Connectivity.shared.isExpensiveNow) }

        suspend fun fetch(url: String, client: OkHttpClient, wifiOnly: Boolean = false): TileFetchResult {
            // iOS's `dataNotAllowed`: a Wi-Fi-only request on a metered network.
            if (wifiOnly && network().isExpensive) return TileFetchResult.Offline
            return try {
                val request = Request.Builder().url(url).header("User-Agent", "SatMap/1.0 (Android)").build()
                client.newCall(request).executeReading { r ->
                    when (r.code) {
                        200 -> {
                            val data = r.body?.bytes() ?: ByteArray(0)
                            if (data.isEmpty()) {
                                TileFetchResult.Missing
                            } else if (ImageTools.looksLikeImage(data)) {
                                TileFetchResult.Data(data)
                            } else {
                                TileFetchResult.Failed
                            }
                        }
                        204, 404, 410 -> TileFetchResult.Missing
                        else -> TileFetchResult.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // iOS's notConnectedToInternet, dataNotAllowed and internationalRoamingOff, against any other error.
                val n = network()
                if (!n.isOnline || (wifiOnly && n.isExpensive)) TileFetchResult.Offline else TileFetchResult.Failed
            } catch (e: Exception) {
                TileFetchResult.Failed
            }
        }

        /** Runs the call with `execute` on an IO thread, reading the response there (the limiters, not the client's
         *  dispatcher, decide how many run at once). Cancelling the coroutine cancels the call. */
        private suspend fun <T> Call.executeReading(read: (Response) -> T): T = suspendCancellableCoroutine { c ->
            c.invokeOnCancellation { cancel() }
            Dispatchers.IO.asExecutor().execute {
                // Ignored when the coroutine was cancelled meanwhile.
                c.resumeWith(runCatching { execute().use(read) })
            }
        }
    }
}

object ImageTools {
    fun looksLikeImage(d: ByteArray): Boolean {
        if (d.size < 4) return false
        val b = d
        return (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte())
    }
}

/** Dedicated pools for CPU-heavy tile work, so it doesn't occupy the threads the downloads and elevation lookups also
 *  need (Swift's `OperationQueue`s). */
object Work {
    // Contours are on-screen content the user is waiting for, like imagery.
    val contours: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(3, "satmap.contours")

    // Decoding imagery for the stand-in crops and the loupe.
    val prepare: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2, "satmap.prepare")
}

/** Which waiting requests go first. */
enum class RequestPriority {
    /** Getting ready for the next zoom or pan: after everything on screen. */
    background,
    normal,

    /** One coarse tile that fills a whole new place. */
    urgent,
}

/** Limits concurrent requests; waiting callers are served by priority, newest first. Background requests get at most
 *  two connections, so on-screen ones never wait behind a slow batch of them. A caller cancelled while it waits leaves
 *  the queue; one cancelled as it is given a connection passes it on. */
class RequestLimiter(slots: Int) {
    private val lock = Any()
    private var free = slots

    /** Indexed by `RequestPriority.ordinal`. */
    private val waiting = List(RequestPriority.entries.size) { ArrayList<CancellableContinuation<Unit>>() }
    private var background = 0

    private companion object {
        const val backgroundLimit = 2
    }

    suspend fun acquire(priority: RequestPriority = RequestPriority.normal) {
        suspendCancellableCoroutine<Unit> { c ->
            c.invokeOnCancellation { synchronized(lock) { waiting[priority.ordinal].remove(c) } }
            val now = synchronized(lock) {
                if (free > 0 && (priority != RequestPriority.background || background < backgroundLimit)) {
                    free -= 1
                    if (priority == RequestPriority.background) background += 1
                    true
                } else {
                    waiting[priority.ordinal].add(c)
                    false
                }
            }
            if (now) grant(c, priority)
        }
    }

    /** With the priority the connection was acquired with. */
    fun release(priority: RequestPriority = RequestPriority.normal) {
        var next: Pair<CancellableContinuation<Unit>, RequestPriority>? = null
        synchronized(lock) {
            if (priority == RequestPriority.background) background -= 1
            for (p in listOf(RequestPriority.urgent, RequestPriority.normal)) {
                val w = waiting[p.ordinal]
                if (w.isNotEmpty()) {
                    next = Pair(w.removeAt(w.lastIndex), p)
                    break
                }
            }
            if (next == null) {
                val w = waiting[RequestPriority.background.ordinal]
                if (background < backgroundLimit && w.isNotEmpty()) {
                    background += 1
                    next = Pair(w.removeAt(w.lastIndex), RequestPriority.background)
                } else {
                    free += 1
                }
            }
        }
        next?.let { (c, p) -> grant(c, p) }
    }

    /** Callers waiting, for tests. */
    internal val waitingCount: Int get() = synchronized(lock) { waiting.sumOf { it.size } }

    /** Gives a caller its connection; if it was cancelled before it could use it, the connection goes to the next. */
    private fun grant(c: CancellableContinuation<Unit>, priority: RequestPriority) {
        c.resume(Unit) { _, _, _ -> release(priority) }
    }
}

/** A small thread-safe least-recently-used cache. */
class LRUCache<K : Any, V : Any>(private val capacity: Int) {
    private val values = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    operator fun get(key: K): V? = synchronized(values) { values[key] }

    operator fun set(key: K, value: V) {
        synchronized(values) { values[key] = value }
    }

    fun remove(key: K) {
        synchronized(values) { values.remove(key) }
    }

    fun removeAll() {
        synchronized(values) { values.clear() }
    }
}
