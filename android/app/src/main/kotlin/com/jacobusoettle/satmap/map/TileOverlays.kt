package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.jacobusoettle.satmap.core.ContourGeometry
import com.jacobusoettle.satmap.core.ContourLine
import com.jacobusoettle.satmap.core.ContourPolicy
import com.jacobusoettle.satmap.core.DEMSampler
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.ElevationGrid
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.MarchingSquares
import com.jacobusoettle.satmap.core.RecentCache
import com.jacobusoettle.satmap.core.Terrarium
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.core.rounded
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Draws 256×256-pixel transparent contour tiles, which the contours layer shows over the imagery (`SatelliteRenderer`
 *  answers MapLibre with them). Stroke widths and fonts are multiplied by the scale it is given (the screen's, in each
 *  tile's URL), and the contour interval is chosen from the equivalent web-map zoom. Drawing uses `android.graphics` on
 *  `Work.contours`. */
class ContourRenderer {
    /** The PNGs drawn (complete ones only), by "scale/z/x/y": the phone and the car draw at different scales. */
    private val cache = object : LruCache<String, ByteArray>(32 shl 20) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Tiles with nothing to draw (flat ground), so they aren't recomputed. */
    private val emptyCache = RecentCache<String, Boolean>(4000)
    private val lock = Any()
    private var scale = 3.0

    /** A tile asked for again before the first answer shares its work. */
    private val inFlight = HashMap<String, Deferred<Tile>>()

    /** Screen scale; used only when no scale is given. (Each tile's URL gives the scale of the map that asks for it,
     *  so nothing needs to set this on Android.) */
    var screenScale: Double
        get() = synchronized(lock) { scale }
        set(value) {
            synchronized(lock) { scale = max(1.0, value) }
        }

    /** Called when the app goes into the background and when memory runs low (`AppModel`, `SatMapApplication`), as
     *  the Swift class observes both itself. */
    fun clearCaches() {
        cache.evictAll()
        emptyCache.trim(0)
    }

    /** What there is to draw on a tile. */
    sealed interface Tile {
        /** The lines (PNG); not `complete` when an elevation tile around it was missing, so its edges are
         *  approximated. */
        class Lines(val png: ByteArray, val complete: Boolean) : Tile

        /** Nothing: flat ground, or a zoom without contours. */
        data object Blank : Tile

        /** No elevation for it (offline and not downloaded, or the fetch failed). */
        data object Unavailable : Tile
    }

    /** The tile's lines at a drawing scale (the screen's: 1 to 3). */
    suspend fun tile(t: TileID, scale: Double): Tile {
        val s = if (scale >= 1) min(scale, 3.0) else screenScale
        val key = key(t, s)
        val task = synchronized(lock) {
            inFlight[key] ?: TileRepository.shared.scope.async {
                try {
                    renderNow(t, s, key)
                } finally {
                    // In the work rather than in each caller (as Swift does), so a caller that is cancelled meanwhile
                    // (MapLibre dropped the tile) doesn't leave it behind.
                    synchronized(lock) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return task.await()
    }

    private suspend fun renderNow(t: TileID, s: Double, key: String): Tile {
        val interval = ContourPolicy.intervals(ContourPolicy.pointZoom(t.z, s)) ?: return Tile.Blank
        cache.get(key)?.let { return Tile.Lines(it, complete = true) }
        if (emptyCache.contains(key)) return Tile.Blank

        val started = SystemClock.uptimeMillis()
        val dz = ContourPolicy.demZoom(t.z)
        val b = t.worldBounds
        val (tiles, complete) = TileRepository.shared.demTiles(dz, b.minX, b.minY, b.maxX, b.maxY)
        val fetchedMs = SystemClock.uptimeMillis() - started
        if (tiles.isEmpty()) {
            // No elevation data (offline and not downloaded, or the fetch failed).
            Log.e("tiles", "contours ${t.description}: no elevation tiles after $fetchedMs ms")
            return Tile.Unavailable
        }

        val cells = ContourPolicy.gridCells(t.z)
        // Gridding, tracing and drawing are CPU-heavy: keep them on the contour pool.
        var computeMs = 0L
        val result: Drawing? = withContext(Work.contours) {
            val computeStart = SystemClock.uptimeMillis()
            try {
                val sampler = DEMSampler(zoom = dz, tile = { tiles[it] })
                val grid = sampler.grid(b.minX, b.minY, b.maxX, b.maxY, nodesX = cells + 1, nodesY = cells + 1)
                    ?: return@withContext null
                val lines = MarchingSquares.contours(grid, width = cells + 1, height = cells + 1, step = interval.step)
                if (lines.isEmpty()) return@withContext Drawing(null, empty = true)
                // Label every other tile (checkerboard) so index lines aren't over-labelled.
                Drawing(
                    draw(lines, cells, interval, s, smooth = t.z > Terrarium.maxZoom, labels = (t.x + t.y) % 2 == 0),
                    empty = false,
                )
            } finally {
                computeMs = SystemClock.uptimeMillis() - computeStart
            }
        }
        val totalMs = SystemClock.uptimeMillis() - started
        if (result == null) {
            Log.e("tiles", "contours ${t.description}: no grid (${tiles.size} elevation tiles)")
            return Tile.Unavailable
        }
        Log.d(
            "tiles",
            "contours ${t.description} scale $s ${if (result.empty) "empty" else "drawn"} complete=$complete " +
                "elevation $fetchedMs ms compute $computeMs ms total $totalMs ms",
        )
        // With a neighbour missing, edges are approximated: show it, but don't keep it.
        if (complete) {
            if (result.empty) emptyCache.set(true, key)
            result.png?.let { cache.put(key, it) }
        }
        if (result.empty) return Tile.Blank
        val png = result.png ?: return Tile.Unavailable
        return Tile.Lines(png, complete)
    }

    /** A tile's lines drawn (null: the PNG couldn't be made), or none to draw. */
    private class Drawing(val png: ByteArray?, val empty: Boolean)

    /** The lines of a tile (`cells` grid cells across) as a 256×256 PNG: every halo, then every line, then the labels
     *  of the index lines when `labels`. */
    internal fun draw(
        lines: List<ContourLine>,
        cells: Int,
        interval: ContourPolicy.Intervals,
        s: Double,
        smooth: Boolean,
        labels: Boolean,
    ): ByteArray? {
        val size = 256.0
        val k = size / cells

        class Prepared(val points: List<XY>, val isIndex: Boolean, val level: Double, val path: Path)

        val prepared = lines.map { line ->
            var pts = line.points.map { XY(it.x * k, it.y * k) }
            if (smooth) pts = ContourGeometry.chaikin(pts, closed = line.isClosed)
            val path = Path()
            pts.firstOrNull()?.let { first ->
                path.moveTo(first.x.toFloat(), first.y.toFloat())
                for (p in pts.drop(1)) path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            Prepared(pts, isIndex(line.level, interval.index), line.level, path)
        }

        // MapLibre shows a tile enlarged up to 2x until the camera reaches the next zoom level (as MapKit did), so the
        // base sizes are on the thin side.
        val minorWidth = minorWidth(s).toFloat()
        val indexWidth = indexWidth(s).toFloat()
        val haloExtra = haloExtra(s).toFloat()

        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        // Dark halo first so lines stay visible on bright ground, then the lines.
        stroke.color = HALO
        for (p in prepared) {
            stroke.strokeWidth = (if (p.isIndex) indexWidth else minorWidth) + haloExtra
            canvas.drawPath(p.path, stroke)
        }
        for (p in prepared) {
            stroke.color = if (p.isIndex) INDEX else MINOR
            stroke.strokeWidth = if (p.isIndex) indexWidth else minorWidth
            canvas.drawPath(p.path, stroke)
        }
        if (labels) {
            // Elevation labels along index lines.
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = labelSize(s).toFloat()
                typeface = labelTypeface
                color = LABEL_FILL
            }
            // Core Text's stroke width −3: 3 % of the font size, stroked over the fill.
            val outline = Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.03f * fill.textSize
                color = LABEL_STROKE
            }
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val metrics = fill.fontMetrics
            val h = metrics.descent - metrics.ascent
            val padding = (2 * s).toFloat()
            for (p in prepared) {
                if (!p.isIndex) continue
                val text = p.level.rounded().toInt().toString()
                val w = fill.measureText(text)
                val margin = w / 2.0 + 4 * s
                if (!(margin * 2 < size)) continue
                val anchor = ContourGeometry.labelAnchor(
                    p.points,
                    minLength = w + 16 * s,
                    bounds = ContourGeometry.Bounds(size, size),
                    margin = margin,
                ) ?: continue
                canvas.save()
                canvas.translate(anchor.x.toFloat(), anchor.y.toFloat())
                canvas.rotate(Math.toDegrees(anchor.angle).toFloat())
                // Clear the line under the label.
                canvas.drawRect(RectF(-w / 2 - padding, -h / 2, w / 2 + padding, h / 2), clear)
                canvas.drawText(text, -w / 2, -h / 2 - metrics.ascent, fill)
                canvas.drawText(text, -w / 2, -h / 2 - metrics.ascent, outline)
                canvas.restore()
            }
        }
        val png = bitmap.png()
        bitmap.recycle()
        return png
    }

    companion object {
        val shared = ContourRenderer()

        /** The colours of iOS's `UIColor`s (rendering spec §2.8). */
        val MINOR: Int = Color.argb(0.85f, 1.0f, 0.84f, 0.32f)
        val INDEX: Int = Color.argb(1.0f, 1.0f, 0.74f, 0.16f)
        val HALO: Int = Color.argb(0.38f, 0f, 0f, 0f)
        val LABEL_FILL: Int = Color.argb(1.0f, 1.0f, 0.93f, 0.7f)
        val LABEL_STROKE: Int = Color.argb(0.75f, 0f, 0f, 0f)

        /** Line widths in tile pixels at a scale: minor and index lines, and what their halo adds. */
        fun minorWidth(s: Double): Double = 0.7 * s
        fun indexWidth(s: Double): Double = 1.3 * s
        fun haloExtra(s: Double): Double = 0.9 * s

        /** The labels' font size: the system font at 8.5 points, semibold. */
        fun labelSize(s: Double): Double = 8.5 * s

        /** Whether a line at this height is an index line (bold and labelled). */
        fun isIndex(level: Double, index: Double): Boolean = abs(Math.IEEEremainder(level, index)) < 0.01

        /** The system font semibold (weight 600, API 28), else its medium sans-serif. */
        private val labelTypeface: Typeface by lazy {
            if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(Typeface.DEFAULT, 600, false)
            } else {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }

        /** The cache key: a tile at a scale (as in the URL, round(s·100)). */
        private fun key(t: TileID, s: Double): String = "${(s * 100).roundToInt()}/${t.z}/${t.x}/${t.y}"
    }
}

/** The heights of a tile for its elevation colours (`ElevationColours`): a grid of nodes from edge to edge, sampled
 *  from the elevation tiles of its zoom (as its contour lines are), which `colourPng` colours. */
class HeightGrids {
    private val lock = Any()

    /** A tile asked for again before the first answer shares its work. */
    private val inFlight = HashMap<TileID, Deferred<Tile>>()

    sealed interface Tile {
        /** The heights; not `complete` when an elevation tile around it was missing, so its edges are approximated. */
        class Grid(val grid: ElevationGrid, val complete: Boolean) : Tile

        /** No elevation for it (offline and not downloaded, or the fetch failed). */
        data object Unavailable : Tile
    }

    suspend fun tile(t: TileID): Tile {
        val task = synchronized(lock) {
            inFlight[t] ?: TileRepository.shared.scope.async {
                try {
                    load(t)
                } finally {
                    synchronized(lock) { inFlight.remove(t) }
                }
            }.also { inFlight[t] = it }
        }
        return task.await()
    }

    companion object {
        val shared = HeightGrids()

        private suspend fun load(t: TileID): Tile {
            val dz = ContourPolicy.demZoom(t.z)
            val b = t.worldBounds
            val (tiles, complete) = TileRepository.shared.demTiles(dz, b.minX, b.minY, b.maxX, b.maxY)
            if (tiles.isEmpty()) {
                Log.e("tiles", "heights ${t.description}: no elevation tiles")
                return Tile.Unavailable
            }
            val nodes = ElevationColours.cells + 1
            val values = withContext(Work.contours) {
                DEMSampler(zoom = dz, tile = { tiles[it] }).grid(b.minX, b.minY, b.maxX, b.maxY, nodesX = nodes, nodesY = nodes)
            } ?: return Tile.Unavailable
            Log.d("tiles", "heights ${t.description} complete=$complete")
            return Tile.Grid(ElevationGrid(nodes, values), complete)
        }
    }
}

/** A tile's heights in a range's colours, smooth between its nodes, as a 256×256 PNG: a pixel for each node
 *  (`ElevationColours.pixels`, premultiplied, alpha 0.5 included), drawn with bilinear filtering half a cell beyond the
 *  tile all round, so each node's pixel is centred on it and neighbouring tiles meet without a seam. Null if it
 *  couldn't be made. Run on `Work.contours`. */
fun colourPng(grid: ElevationGrid, range: ElevationRange): ByteArray? {
    val n = grid.nodes
    if (n < 2 || grid.values.size != n * n) return null
    val small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    // ARGB_8888 is premultiplied RGBA bytes in memory on Android: the layout of `ElevationColours.pixels`.
    val pixels = ElevationColours.pixels(grid, range)
    if (!rgbaInMemory) {
        for (i in pixels.indices step 4) {
            val r = pixels[i]
            pixels[i] = pixels[i + 2]
            pixels[i + 2] = r
        }
    }
    small.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    val half = 256f / (n - 1) / 2
    Canvas(out).drawBitmap(small, null, RectF(-half, -half, 256 + half, 256 + half), Paint(Paint.FILTER_BITMAP_FLAG))
    small.recycle()
    val png = out.png()
    out.recycle()
    return png
}

/** Whether an ARGB_8888 bitmap holds RGBA bytes in memory, as Android's Skia does (the desktop Skia of the unit tests
 *  holds BGRA): checked once. */
private val rgbaInMemory: Boolean by lazy {
    val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    b.setPixel(0, 0, Color.RED)
    val buffer = ByteBuffer.allocate(4)
    b.copyPixelsToBuffer(buffer)
    b.recycle()
    buffer.get(0) == 0xFF.toByte()
}

/** The bitmap as a PNG (lossless: `compress` ignores the quality), or null if it couldn't be encoded. */
internal fun Bitmap.png(): ByteArray? = ByteArrayOutputStream().use { s ->
    if (compress(Bitmap.CompressFormat.PNG, 100, s)) s.toByteArray() else null
}
