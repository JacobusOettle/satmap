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
import com.jacobusoettle.satmap.core.ElevationGrid
import com.jacobusoettle.satmap.core.ElevationRange
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
import kotlin.math.roundToInt

// Satellite imagery, so the map never goes blank: MapLibre asks for every tile through `TileInterceptor`, and
// `SatelliteRenderer` answers each with its own imagery from memory, the offline areas or the network, else with the
// nearest coarser tile in memory enlarged (asked for again when that one's retry is due), else with nothing (MapLibre
// then shows a coarser tile of its own). `ImageryPrefetch` keeps coarse tiles around the screen in memory for it. The
// style has no map of its own under the imagery, so nothing else is downloaded. The elevation colours and the contour
// lines over it are answered the same way, drawn on the phone from the heights (`HeightGrids`, `ContourRenderer`).

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

    /** A tile's contour lines (PNG; `ContourRenderer`), and whether they are complete. */
    private class ContourImage(val png: ByteArray, val complete: Boolean)

    /** A tile's heights (`HeightGrids`), and whether they are complete. */
    internal class HeightGrid(val grid: ElevationGrid, val complete: Boolean)

    /** Decoded tiles (256 KB each) for the stand-in crops and the loupe (MapLibre decodes what it shows itself). */
    val decoded = RecentCache<TileID, Bitmap>(64)

    /** The stand-in crops made, by (spot, ancestor). */
    private val standInJpegs = RecentCache<Pair<TileID, TileID>, ByteArray>(256)

    /** Contour tiles (`ContourRenderer`), and those with nothing to draw, by "scale/z/x/y": the phone and the car draw
     *  them at different scales. */
    private val contourImages = RecentCache<String, ContourImage>(240)
    private val noContours = RecentCache<String, Boolean>(4000)

    /** The heights of tiles for the elevation colours (17 KB each; `HeightGrids`), at the contour tiles' level. */
    internal val heightGrids = RecentCache<TileID, HeightGrid>(400)

    /** Colour tiles made, by "low_high/z/x/y", with the grid each was made from (better heights make a new one). */
    private val colourImages = RecentCache<String, Pair<ElevationGrid, ByteArray>>(200)

    private val loads = Any()
    private val loading = HashMap<TileID, Deferred<TileRepository.NearestImagery?>>()

    /** Until when (s) not to ask again, and whether the tile is deeper than the imagery goes. */
    private val retryAfter = HashMap<TileID, Pair<Double, Boolean>>()
    private val contoursLoading = HashMap<String, Deferred<ContourRenderer.Tile>>()
    private val contoursRetryAfter = HashMap<String, Double>()
    private val gridsLoading = HashMap<TileID, Deferred<Unit>>()
    private val gridsRetryAfter = HashMap<TileID, Double>()

    /** Tiles in memory being decoded for the loupe (`prepare`). */
    private val decoding = HashSet<TileID>()

    /** Seconds on the clock of the gates (`CACurrentMediaTime` in Swift); a test may set its own. */
    @Volatile
    internal var now: () -> Double = { SystemClock.uptimeMillis() / 1000.0 }

    /** The answer to one tile request. The imagery: the tile's own from memory, else loaded (shared with any load of it
     *  under way, unless it is to be left for a while), else the nearest coarser tile in memory enlarged, else nothing;
     *  both asked for again once the tile's retry is due. Contour lines and colours: drawn from the heights. */
    suspend fun answer(route: TileRoute, layer: TileLayer): TileAnswer = when (route) {
        is TileRoute.Imagery -> imagery(route.tile, layer.map)
        is TileRoute.Contours -> contours(route.tile, route.scale)
        is TileRoute.Colours -> colours(route.tile, route.range)
    }

    private suspend fun imagery(t: TileID, map: DrawnMap?): TileAnswer {
        ImageryMemory.tiles.value(t)?.let { return own(it) }
        // The tiles three levels above the map's are MapLibre's prefetch: the coarse tile that fills a whole new place.
        // (A map that has gone: its level unknown.)
        val focus = map?.focuses?.imagery?.level ?: -1
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
    private fun gateLeft(t: TileID): Int = left(synchronized(loads) { retryAfter[t]?.first })

    /** Seconds left until then (at least 1); 1 when the gate was forgotten (`reload`): asked for again soon. */
    private fun left(until: Double?): Int = if (until == null) 1 else max(1.0, ceil(until - now())).toInt()

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

    // Contour lines

    /** A contour tile: its own lines (drawn into memory by `loadContours`); blank where there are none (never a 204,
     *  or MapLibre would show the parent's lines); while they can't be had, or only with their edges approximated,
     *  what there is (else a 204), asked for again once the tile's retry is due. */
    private suspend fun contours(t: TileID, scale: Double): TileAnswer {
        val key = contourKey(t, scale)
        if (noContours.contains(key)) return blank(lacking = null)
        contourImages.value(key)?.let { if (it.complete) return png(it.png, maxAge = null, lacking = null) }
        when (val tile = loadContours(t, scale, key)?.await()) {
            is ContourRenderer.Tile.Lines ->
                return if (tile.complete) png(tile.png, null, null) else png(tile.png, contoursGateLeft(key), lacking = true)
            ContourRenderer.Tile.Blank -> return blank(lacking = null)
            ContourRenderer.Tile.Unavailable, null -> {}
        }
        // Left for a while: what there is, approximated at its edges, else nothing.
        val held = contourImages.peek(key)
        return when {
            held == null -> TileAnswer.empty(contoursGateLeft(key), lacking = false)
            held.complete -> png(held.png, maxAge = null, lacking = null) // (completed meanwhile by another request)
            else -> png(held.png, contoursGateLeft(key), lacking = true)
        }
    }

    private fun contoursGateLeft(key: String): Int = left(synchronized(loads) { contoursRetryAfter[key] })

    /** "scale/z/x/y", the scale as in the URL (round(s·100)). */
    private fun contourKey(t: TileID, scale: Double): String = "${(scale * 100).roundToInt()}/${t.z}/${t.x}/${t.y}"

    /** Draws a spot's contour lines into memory (`ContourRenderer`), once at a time, and announces them. Those that
     *  can't be had, or only with their edges approximated, are asked for again in a while. Null while the tile is to be
     *  left for a while. */
    private fun loadContours(t: TileID, scale: Double, key: String): Deferred<ContourRenderer.Tile>? {
        val time = now()
        return synchronized(loads) {
            val until = contoursRetryAfter[key]
            if (until != null && time < until) return@synchronized null
            contoursLoading[key] ?: TileRepository.shared.scope.async {
                var arrived = false
                var again = true
                try {
                    val tile = ContourRenderer.shared.tile(t, scale)
                    val had = contourImages.contains(key)
                    when (tile) {
                        is ContourRenderer.Tile.Lines -> {
                            noContours.remove(key)
                            contourImages.set(ContourImage(tile.png, tile.complete), key)
                            arrived = tile.complete || !had
                            again = !tile.complete
                        }
                        ContourRenderer.Tile.Blank -> {
                            noContours.set(true, key)
                            contourImages.remove(key)
                            arrived = true
                            again = false
                        }
                        ContourRenderer.Tile.Unavailable -> {}
                    }
                    tile
                } finally {
                    synchronized(loads) {
                        contoursLoading.remove(key)
                        if (again) contoursRetryAfter[key] = now() + 30 else contoursRetryAfter.remove(key)
                    }
                    if (arrived) DrawnMap.announce(t, Content.contours)
                }
            }.also { contoursLoading[key] = it }
        }
    }

    // Elevation colours

    /** A colour tile: the tile's heights (loaded into memory by `loadGrid`, also while there is no range yet: it is
     *  chosen from them) in the layer's range; while they can't be had, or only with their edges approximated, what
     *  there is (else a 204), asked for again once the tile's retry is due. Without a range: blank. */
    private suspend fun colours(t: TileID, range: ElevationRange?): TileAnswer {
        var g = heightGrids.value(t)
        if (g == null || !g.complete) {
            loadGrid(t)?.await()
            g = heightGrids.value(t)
        }
        // The heights the tile lacks, for a new layer (`DrawnImagery.reload`): approximated (true), or none (false).
        val lacking = if (g == null) false else if (g.complete) null else true
        if (range == null) return blank(lacking)
        if (g == null) return TileAnswer.empty(gridsGateLeft(t), lacking = false)
        val png = colourImage(t, g.grid, range) ?: return TileAnswer.empty(gridsGateLeft(t), lacking = false)
        return png(png, if (g.complete) null else gridsGateLeft(t), lacking)
    }

    private fun gridsGateLeft(t: TileID): Int = left(synchronized(loads) { gridsRetryAfter[t] })

    /** A tile's colours in a range, made once for its grid (on the contour pool). */
    private suspend fun colourImage(t: TileID, grid: ElevationGrid, range: ElevationRange): ByteArray? {
        val key = "${range.low}_${range.high}/${t.description}"
        colourImages.value(key)?.let { (g, png) -> if (g === grid) return png }
        val png = withContext(Work.contours) { colourPng(grid, range) } ?: return null
        colourImages.set(Pair(grid, png), key)
        return png
    }

    /** Loads a spot's heights into memory (`HeightGrids`), once at a time, and announces them. Those that can't be had,
     *  or only with their edges approximated, are asked for again in a while. Null while the tile is to be left for a
     *  while. */
    private fun loadGrid(t: TileID): Deferred<Unit>? {
        val time = now()
        return synchronized(loads) {
            val until = gridsRetryAfter[t]
            if (until != null && time < until) return@synchronized null
            gridsLoading[t] ?: TileRepository.shared.scope.async {
                var arrived = false
                var again = true
                try {
                    val tile = HeightGrids.shared.tile(t)
                    if (tile is HeightGrids.Tile.Grid) {
                        val had = heightGrids.contains(t)
                        if (tile.complete || !had) heightGrids.set(HeightGrid(tile.grid, tile.complete), t)
                        arrived = tile.complete || !had
                        again = !tile.complete
                    }
                } finally {
                    synchronized(loads) {
                        gridsLoading.remove(t)
                        if (again) gridsRetryAfter[t] = now() + 30 else gridsRetryAfter.remove(t)
                    }
                    if (arrived) DrawnMap.announce(t, Content.colours)
                }
            }.also { gridsLoading[t] = it }
        }
    }

    /** The lowest and highest heights in memory on screen, from the grids of a level's tiles under points across it
     *  (so a turned map's corners off screen don't count), within the rect around them (`complete`: every one of those
     *  tiles has its heights, or has none to be had for now: offline outside the areas downloaded). Null without any
     *  (or only sea). */
    fun heightExtremes(r: WorldRect, level: Int, onScreen: List<XY>): Extremes? {
        val z = level
        val n = 1 shl z
        val spots = onScreen.mapTo(LinkedHashSet()) { p ->
            TileID(z = z, x = min(n - 1, max(0, floor(p.x * n).toInt())), y = min(n - 1, max(0, floor(p.y * n).toInt())))
        }
        if (spots.isEmpty()) return null
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        var complete = true
        for (t in spots) {
            val g = heightGrids.peek(t)
            if (g == null) {
                if (complete) {
                    complete = synchronized(loads) { gridsRetryAfter[t] != null && t !in gridsLoading }
                }
                continue
            }
            g.grid.extremes(tile = t.worldBounds, within = r)?.let { e ->
                low = min(low, e.low)
                high = max(high, e.high)
            }
        }
        return if (low <= high) Extremes(low.toDouble(), high.toDouble(), complete) else null
    }

    /** A 200 with the 1×1 transparent PNG: nothing drawn here (a 204 would show the parent's). */
    private fun blank(lacking: Boolean?): TileAnswer = TileAnswer(200, TileAnswer.BLANK_PNG, "image/png", maxAge = null, lacking)

    private fun png(data: ByteArray, maxAge: Int?, lacking: Boolean?): TileAnswer =
        TileAnswer(200, data, "image/png", maxAge, lacking)

    // The loupe

    /** For the square that moves a point precisely (`LoupeView`): draws the imagery in memory for a world rect into a
     *  rect of a canvas, each spot's own tile at `level`, else the nearest coarser one in memory enlarged, and loads what
     *  is missing (announced, as for the maps). Main thread. */
    fun drawImagery(r: WorldRect, level: Int, canvas: Canvas, target: RectF) {
        val width = r.maxX - r.minX
        if (!(width > 0)) return
        val scale = target.width() / width
        fun place(t: TileID): RectF {
            val m = t.worldBounds
            return RectF(
                (target.left + (m.minX - r.minX) * scale).toFloat(),
                (target.top + (m.minY - r.minY) * scale).toFloat(),
                (target.left + (m.maxX - r.minX) * scale).toFloat(),
                (target.top + (m.maxY - r.minY) * scale).toFloat(),
            )
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (t in tiles(level, r)) {
            val own = decoded.value(t)
            if (own != null) {
                canvas.drawBitmap(own, null, place(t), paint)
                continue
            }
            if (ImageryMemory.tiles.contains(t)) prepare(listOf(t)) else load(t, urgent = true)
            val a = TileFallback.ancestor(t, maxLevels = 12, has = ::has) ?: continue
            val coarse = image(a) ?: continue
            canvas.save()
            canvas.clipRect(place(t))
            canvas.drawBitmap(coarse, null, place(a), paint)
            canvas.restore()
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
            contoursRetryAfter.clear()
            gridsRetryAfter.clear()
        }
    }

    /** Decodes tiles in memory (off the main thread), so the loupe can draw them at once: each is announced
     *  (`DrawnMap.announce`) when it is ready. */
    private fun prepare(tiles: List<TileID>) {
        val wanted = synchronized(loads) {
            tiles.filter { it !in decoding && !decoded.contains(it) && ImageryMemory.tiles.contains(it) }
                .also { decoding.addAll(it) }
        }
        for (t in wanted) {
            TileRepository.shared.scope.launch(Work.prepare) {
                var ready = false
                try {
                    ready = image(t) != null
                } finally {
                    synchronized(loads) { decoding.remove(t) }
                }
                if (ready) DrawnMap.announce(t, Content.imagery)
            }
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
        contourImages.trim(count)
        heightGrids.trim(count)
        // (Android's own caches: crops and colour tiles are made again when asked for.)
        standInJpegs.trim(count)
        colourImages.trim(count)
    }

    /** The tiles of a level under a world rect (clamped to the world). */
    internal fun tiles(z: Int, r: WorldRect): List<TileID> {
        val n = 1 shl z
        val size = 1.0 / n
        val x0 = max(0, floor(r.minX / size).toInt())
        val x1 = min(n - 1, ceil(r.maxX / size).toInt() - 1)
        val y0 = max(0, floor(r.minY / size).toInt())
        val y1 = min(n - 1, ceil(r.maxY / size).toInt() - 1)
        if (!(x0 <= x1 && y0 <= y1)) return emptyList()
        return (y0..y1).flatMap { y -> (x0..x1).map { TileID(z = z, x = it, y = y) } }
    }

    /** Forgets every load, gate and cache (Robolectric keeps the object from one test to the next). */
    internal fun resetForTests() {
        synchronized(loads) {
            loading.clear()
            retryAfter.clear()
            contoursLoading.clear()
            contoursRetryAfter.clear()
            gridsLoading.clear()
            gridsRetryAfter.clear()
            decoding.clear()
        }
        decoded.trim(0)
        standInJpegs.trim(0)
        contourImages.trim(0)
        noContours.trim(0)
        heightGrids.trim(0)
        colourImages.trim(0)
        ImageryMemory.tiles.trim(0)
        ContourRenderer.shared.clearCaches()
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
