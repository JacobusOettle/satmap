package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.RecentCache
import com.jacobusoettle.satmap.core.TileFallback
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.core.rounded
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

// Satellite imagery, so the map never goes blank: MapLibre asks for every tile through `TileInterceptor`, and
// `SatelliteRenderer` answers each with its own imagery from memory, the offline areas or the network, else with the
// nearest coarser tile in memory enlarged (asked for again when that one's retry is due), else with nothing (MapLibre
// then shows a coarser tile of its own). `ImageryPrefetch` keeps coarse tiles around the screen in memory for it. The
// style has no map of its own under the imagery, so nothing else is downloaded.

/** Imagery tiles loaded for the screen (JPEG data, 15–60 KB each), decoded as needed. */
object ImageryMemory {
    val tiles = RecentCache<TileID, ByteArray>(900)

    fun enteredBackground() {
        tiles.trim(150)
        SatelliteRenderer.trim(0)
    }

    fun trimMemory() {
        tiles.trim(100)
        SatelliteRenderer.trim(32)
    }
}

/** One map's side of the imagery (the phone's map, the car's with Android Auto, and the loupe): where it looks and the
 *  levels there (set by its `DrawnImagery` as the map moves, read by the interceptor on any thread), and who to tell
 *  when tiles arrive. Only tiles in focus are improved by a new layer (`DrawnImagery.reload`). The tiles in memory are
 *  shared by all maps. */
class DrawnMap {
    /** The rect (world units, 0…1; null: none yet) and level a map loads at; level −1 when unknown. */
    data class Focus(val rect: WorldRect?, val level: Int)

    /** The focus for imagery tiles, and for contour (and colour) tiles over the same rect. Immutable, swapped whole. */
    data class Focuses(val imagery: Focus, val contours: Focus)

    @Volatile
    var focuses: Focuses = Focuses(Focus(null, -1), Focus(null, -1))
        private set

    /** Called on the main thread when a tile has arrived in memory (loaded for a map or by `ImageryPrefetch`), or its
     *  contour lines or heights. */
    var onArrival: ((TileID, SatelliteRenderer.Content) -> Unit)? = null

    init {
        synchronized(all) {
            all.removeAll { it.get() == null }
            all.add(WeakReference(this))
        }
    }

    fun setFocus(rect: WorldRect, level: Int, contourLevel: Int) {
        focuses = Focuses(Focus(rect, level), Focus(rect, contourLevel))
    }

    companion object {
        private val all = ArrayList<WeakReference<DrawnMap>>()
        private val main by lazy { Handler(Looper.getMainLooper()) }

        /** Tells every map a tile has arrived (on the main thread). */
        fun announce(t: TileID, content: SatelliteRenderer.Content) {
            val maps = synchronized(all) { all.mapNotNull { it.get() } }
            main.post {
                for (m in maps) m.onArrival?.invoke(t, content)
            }
        }
    }
}

/** Answers MapLibre's tile requests (DESIGN.md §3.2 answer table) with the best there is for each tile, and loads what
 *  it lacks; draws the imagery for the loupe. The loads, and the tiles to leave for a while (failed, or deeper than
 *  the imagery goes), are shared by every map and layer, so a new layer doesn't ask again for what its predecessor is
 *  loading. Thread-safe. */
object SatelliteRenderer {
    enum class Content { imagery, contours, colours }

    /** The land heights on screen (`heightExtremes`), and whether every tile under it has its heights. */
    data class Extremes(val low: Double, val high: Double, val complete: Boolean)

    /** Decoded tiles (256 KB each) for the stand-in crops and the loupe (MapLibre decodes what it shows itself). */
    val decoded = RecentCache<TileID, Bitmap>(64)

    /** The stand-in crops made, by (spot, ancestor). */
    private val standInJpegs = RecentCache<Pair<TileID, TileID>, ByteArray>(256)

    private val loads = Any()
    private val loading = HashMap<TileID, Deferred<TileRepository.NearestImagery?>>()

    /** Until when (s) not to ask again, and whether the tile is deeper than the imagery goes. */
    private val retryAfter = HashMap<TileID, Pair<Double, Boolean>>()

    /** Seconds on the clock of the gates (`CACurrentMediaTime` in Swift); a test may set its own. */
    @Volatile
    internal var now: () -> Double = { SystemClock.uptimeMillis() / 1000.0 }

    /** The answer to one tile request. The imagery: the tile's own from memory, else loaded (shared with any load of it
     *  under way, unless it is to be left for a while), else the nearest coarser tile in memory enlarged, else nothing;
     *  both asked for again once the tile's retry is due. Contours and colours come with M2. */
    suspend fun answer(route: TileRoute, layer: TileLayer): TileAnswer = when (route) {
        is TileRoute.Imagery -> imagery(route.tile, layer.map)
        is TileRoute.Contours, is TileRoute.Colours -> TileAnswer.empty(maxAge = 30) // M2: rendering spec §2.6.
    }

    private suspend fun imagery(t: TileID, map: DrawnMap): TileAnswer {
        ImageryMemory.tiles.value(t)?.let { return own(it) }
        // The tiles three levels above the map's are MapLibre's prefetch: the coarse tile that fills a whole new place.
        val focus = map.focuses.imagery.level
        val r = load(t, urgent = focus >= 0 && t.z <= focus - 3)?.await()
        if (r != null && r.tile == t) return own(r.data)
        val maxAge = gateLeft(t)
        val a = TileFallback.ancestor(t, maxLevels = 10, has = ::has)
        val crop = a?.let { standIn(t, it) }
        return if (crop != null) {
            TileAnswer(200, crop, "image/jpeg", maxAge, lacking = true)
        } else {
            TileAnswer.empty(maxAge = maxAge, lacking = false)
        }
    }

    /** The tile's own data, as stored. */
    private fun own(data: ByteArray): TileAnswer {
        val png = data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()
        return TileAnswer(200, data, if (png) "image/png" else "image/jpeg", maxAge = null, lacking = null)
    }

    /** Seconds left before a tile is to be asked for again (at least 1): MapLibre asks again then. */
    private fun gateLeft(t: TileID): Int {
        val until = synchronized(loads) { retryAfter[t]?.first } ?: return 1 // forgotten (`reload`): ask again soon
        return max(1.0, ceil(until - now())).toInt()
    }

    /** A spot's part of an ancestor, enlarged to 256 pixels (JPEG q90): drawn from the whole decoded ancestor, so the
     *  filtering crosses the crop's edges and neighbouring crops meet without a seam, as iOS's one enlarged image. */
    private suspend fun standIn(t: TileID, a: TileID): ByteArray? {
        val key = Pair(t, a)
        standInJpegs.value(key)?.let { return it }
        return withContext(Work.prepare) {
            val image = image(a) ?: return@withContext null
            val k = t.z - a.z
            val side = image.width.toFloat() / (1 shl k)
            val left = (t.x - (a.x shl k)) * side
            val top = (t.y - (a.y shl k)) * side
            val m = Matrix().apply {
                setRectToRect(RectF(left, top, left + side, top + side), RectF(0f, 0f, 256f, 256f), Matrix.ScaleToFit.FILL)
            }
            val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(image, m, Paint(Paint.FILTER_BITMAP_FLAG))
            val jpeg = ByteArrayOutputStream().use { s ->
                out.compress(Bitmap.CompressFormat.JPEG, 90, s)
                s.toByteArray()
            }
            out.recycle()
            standInJpegs.set(jpeg, key)
            jpeg
        }
    }

    /** The imagery level drawn at a zoom: tiles about 256 pixels across on screen (the Swift
     *  `level(pixelsPerMapPoint:)`, with the world, 0…1, as the unit). */
    fun level(pixelsPerWorld: Double): Int {
        if (!(pixelsPerWorld > 0) || !pixelsPerWorld.isFinite()) return 0
        return log2(pixelsPerWorld / 256).rounded().toInt().coerceIn(0, TileSources.imageryMaxZoom)
    }

    /** Whether a tile is in a focus: at its level or one either side, and under its rect. */
    internal fun inFocus(t: TileID, f: DrawnMap.Focus): Boolean {
        val r = f.rect ?: return false
        val b = t.worldBounds
        return abs(t.z - f.level) <= 1 && r.minX < b.maxX && r.maxX > b.minX && r.minY < b.maxY && r.maxY > b.minY
    }

    fun heightExtremes(r: WorldRect, level: Int, onScreen: List<XY>): Extremes? = null // M2: as Swift.

    /** The loupe: each spot's own tile, else an ancestor in memory up to 12 levels up; what is missing is loaded
     *  urgently. */
    fun drawImagery(r: WorldRect, level: Int, canvas: Canvas, target: RectF) {
        // M3: as Swift (DESIGN.md D7).
    }

    /** Loads a tile into memory, once at a time; `urgent` ones before the others. If it can't be had, the nearest
     *  coarser tile that exists is loaded instead (as it is: it is enlarged for the answer), which may be sharper than
     *  what is in memory. Null while the tile is to be left for a while. */
    private fun load(t: TileID, urgent: Boolean): Deferred<TileRepository.NearestImagery?>? {
        val time = now()
        return synchronized(loads) {
            val after = retryAfter[t]
            if (after != null && time < after.first) return@synchronized null
            loading[t] ?: TileRepository.shared.scope.async {
                val r = TileRepository.shared.nearestImagery(t, urgent)
                if (r != null) ImageryMemory.tiles.set(r.data, r.tile)
                synchronized(loads) {
                    loading.remove(t)
                    // Not there for now: asked for again in a while, or much later if the imagery doesn't go this deep.
                    if (r?.tile != t) {
                        val deep = r?.beyondDepth == true
                        retryAfter[t] = Pair(now() + if (deep) 600 else 30, deep)
                    }
                }
                if (r != null) DrawnMap.announce(r.tile, Content.imagery)
                r
            }.also { loading[t] = it }
        }
    }

    /** Tiles that failed may be asked for again at once (the network is back); not those deeper than the imagery. */
    fun reload() {
        synchronized(loads) {
            retryAfter.entries.removeAll { !it.value.second }
            // M2: contoursRetryAfter and gridsRetryAfter cleared.
        }
    }

    /** Whether a tile can be drawn from memory (its data, or its decoded image). */
    private fun has(t: TileID): Boolean = ImageryMemory.tiles.contains(t) || decoded.contains(t)

    private fun image(t: TileID): Bitmap? {
        decoded.value(t)?.let {
            ImageryMemory.tiles.touch(t) // drawn from: keep its data too
            return it
        }
        val d = ImageryMemory.tiles.value(t) ?: return null
        val i = BitmapFactory.decodeByteArray(d, 0, d.size) ?: return null
        decoded.set(i, t)
        return i
    }

    fun trim(count: Int) {
        decoded.trim(count)
        standInJpegs.trim(count) // (Android's own cache: crops are made again when asked for)
        // M2: contourImages.trim(count); heightGrids.trim(count).
    }

    /** Forgets every load, gate and cache (Robolectric keeps the object from one test to the next). */
    internal fun resetForTests() {
        synchronized(loads) {
            loading.clear()
            retryAfter.clear()
        }
        decoded.trim(0)
        standInJpegs.trim(0)
        ImageryMemory.tiles.trim(0)
        now = { SystemClock.uptimeMillis() / 1000.0 }
    }
}

/** When the map comes to rest: gets the imagery ready for the next zoom or pan, so its new tiles are answered from
 *  memory at once: coarse tiles around the screen, 3 levels up over twice the screen and 6 levels up over eight times
 *  it, in the background (offline areas first, then online after everything on screen). (iOS also decodes them and the
 *  tiles on screen ahead; MapLibre decodes what it shows.) */
object ImageryPrefetch {
    private val lock = Any()
    private val loading = HashSet<TileID>()

    fun mapStopped(visible: WorldRect, level: Int) {
        val z = level
        val onScreen = tiles(z, visible)
        val surround = ArrayList<TileID>()
        for ((up, grow) in listOf(Pair(3, 0.5), Pair(6, 3.5))) {
            if (z - up < 0) continue
            val dx = (visible.maxX - visible.minX) * grow
            val dy = (visible.maxY - visible.minY) * grow
            val r = WorldRect(visible.minX - dx, visible.minY - dy, visible.maxX + dx, visible.maxY + dy)
            surround += tiles(z - up, r)
        }
        val memory = ImageryMemory.tiles
        val wanted = synchronized(lock) {
            // Those already in memory are marked as just used, so the ones around the screen are kept longest.
            val w = surround.filter { !memory.touch(it) && it !in loading }
            loading.addAll(w)
            w
        }
        Log.d("tiles", "map stopped at z$z: ${onScreen.size} on screen, ${surround.size} around, ${wanted.size} to load")
        val repository = TileRepository.shared
        for (t in wanted) {
            repository.scope.launch {
                try {
                    repository.imageryInBackground(t)?.let { d ->
                        memory.set(d, t)
                        DrawnMap.announce(t, SatelliteRenderer.Content.imagery)
                    }
                } finally {
                    synchronized(lock) { loading.remove(t) }
                }
            }
        }
    }

    /** The tiles of a level covering a world rect (wrapping round the world sideways); none if it needs more than 64
     *  along either axis. */
    internal fun tiles(z: Int, r: WorldRect): List<TileID> {
        val n = 1 shl z
        val size = 1.0 / n
        val x0 = floor(r.minX / size).toInt()
        val x1 = ceil(r.maxX / size).toInt() - 1
        val y0 = max(0, floor(r.minY / size).toInt())
        val y1 = min(n - 1, ceil(r.maxY / size).toInt() - 1)
        if (!(x0 <= x1 && y0 <= y1 && x1 - x0 < 64 && y1 - y0 < 64)) return emptyList()
        val out = LinkedHashSet<TileID>()
        for (y in y0..y1) {
            for (x in x0..x1) out.add(TileID(z = z, x = ((x % n) + n) % n, y = y))
        }
        return out.toList()
    }
}
