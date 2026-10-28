package com.jacobusoettle.satmap.map

import android.content.Context
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.DEMTile
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/** Where every tile the map shows comes from: memory → offline areas (MBTiles) → network, with the DEM (elevation)
 *  tiles, the height of a spot, and the network pause after repeated failures. The logic is the Swift's, line for
 *  line; locks are `synchronized`, requests in flight are shared `Deferred`s. */
class TileRepository private constructor(context: Context) : ElevationSource {
    companion object {
        /** Set by `init` at startup. Settable, so a test can install its own. */
        lateinit var shared: TileRepository

        fun init(context: Context) {
            shared = TileRepository(context.applicationContext)
        }
    }

    enum class Layer(val fileName: String) { imagery("imagery.mbtiles"), terrain("terrain.mbtiles") }

    /** An offline area for the map: its folder and its world bounds (0…1). */
    data class AreaRef(val id: UUID, val directory: File, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    /** The best imagery for a spot: its own tile, or the nearest coarser one (`beyondDepth`: the place has no imagery
     *  that deep). */
    class NearestImagery(val tile: TileID, val data: ByteArray, val beyondDepth: Boolean)

    /** Tile and DEM loads shared between callers: they outlive a cancelled MapLibre request, as iOS's detached loads do. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tiles from the network, with a 1 GiB disk cache (OkHttp has no memory cache: `ImageryMemory` is that). */
    val browseClient: OkHttpClient = TileInterceptor.browseClient(context)

    fun setAreas(refs: List<AreaRef>) {
        // M4: one OpenArea per area, its stores opened lazily through AndroidSqlDriver.
    }

    fun closeArea(id: UUID) {
        // M4: closes the area's readers.
    }

    fun offlineTile(t: TileID, layer: Layer): ByteArray? = null // M4: the first area holding the tile.

    suspend fun nearestImagery(t: TileID, urgent: Boolean = false): NearestImagery? = null // M1: the Swift fallback walk.

    suspend fun imageryInBackground(t: TileID): ByteArray? = null // M1: offline, then the network at background priority.

    /** True when the network is usable again after failures (the retry timer asks every 30 s). */
    suspend fun probeRecovery(): Boolean = false // M2: as Swift.

    fun resetFailureState() {
        // M2: clears demRetryAfter, the failure count and the pause.
    }

    fun trimForBackground() {
        // M1: clears the DEM cache and imageryMissing, closes the MBTiles readers.
    }

    /** OkHttp has no memory cache to give back (iOS restores URLCache's memory here), so there is nothing to do. */
    fun restoreAfterBackground() {}

    fun trimMemory() {
        // M1: clears the DEM cache.
    }

    suspend fun demTile(t: TileID): DEMTile? = null // M1: LRU of 160, offline, network; 30 s and 600 s retry gates.

    suspend fun demTiles(zoom: Int, minX: Double, minY: Double, maxX: Double, maxY: Double): Pair<Map<TileID, DEMTile>, Boolean> =
        Pair(emptyMap(), false) // M2: the DEM tiles covering the rect, and whether all loaded.

    override suspend fun elevation(p: GeoPoint): Double? = null // M1: the height from the DEM tile at z15.
}

/** Fetches tiles over HTTP with the Swift result classification (200 with an image → data, empty 200/204/404/410 →
 *  missing, other statuses → failed; no network → offline). `wifiOnly`: also offline while the network is metered.
 *  Used by the map's repository and by the offline downloader. */
class HTTPTileFetcher(
    private val client: OkHttpClient,
    private val url: (TileID) -> String,
    private val wifiOnly: Boolean = false,
) : TileFetching {
    override suspend fun fetch(tile: TileID): TileFetchResult = fetch(url(tile), client)

    companion object {
        suspend fun fetch(url: String, client: OkHttpClient): TileFetchResult =
            TileFetchResult.Failed // M1: the request, User-Agent "SatMap/1.0 (Android)", the classification.
    }
}

object ImageTools {
    /** JPEG (FF D8) or PNG (89 50 4E 47). */
    fun looksLikeImage(d: ByteArray): Boolean {
        if (d.size < 4) return false
        val b = d
        return (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte())
    }
}

/** The worker pools (Swift `OperationQueue`s). */
object Work {
    /** DEM decode, grids, marching squares, contour and colour bitmaps, PNG encoding: on-screen content. */
    val contours: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(3, "satmap.contours")

    /** Imagery decode and stand-in JPEG encoding. */
    val prepare: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2, "satmap.prepare")
}

enum class RequestPriority {
    /** Getting ready for the next zoom or pan: after everything on screen. */
    background,
    normal,

    /** The coarse tile three levels up, and the loupe. */
    urgent,
}

/** At most `slots` requests at once: waiting callers are served urgent first, then normal, then background, newest
 *  first within each; background requests hold at most 2 slots. */
class RequestLimiter(private val slots: Int) {
    suspend fun acquire(priority: RequestPriority = RequestPriority.normal) {
        // M1: waits for a slot (a coroutine port of the Swift class; a waiter cancelled while queued is removed).
    }

    fun release(priority: RequestPriority = RequestPriority.normal) {
        // M1: hands the slot to the next waiter.
    }
}

/** A thread-safe least-recently-used cache. */
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
