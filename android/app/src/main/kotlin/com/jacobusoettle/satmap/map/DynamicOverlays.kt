package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The whole recorded track in chunks, as iOS's `TrackOverlay` keeps it: up to ~512 points each; a new segment (after
 *  a gap) always starts a new chunk. Main thread only. */
class TrackChunks {
    /** A chunk's points, and whether it goes on with the segment of the chunk before (it then starts at that chunk's
     *  last point). */
    class Chunk(val points: MutableList<GeoPoint>, val continues: Boolean)

    private val list = ArrayList<Chunk>()
    val chunks: List<Chunk> get() = list

    fun reset(track: List<TrackPoint>) {
        list.clear()
        for (p in track) append(p)
    }

    /** Appends a point; true when it started a new chunk, so the chunks before the last one have changed. */
    fun append(p: TrackPoint): Boolean {
        val m = p.geo
        if (p.startsSegment || list.isEmpty()) {
            list.add(Chunk(mutableListOf(m), continues = false))
            return true
        }
        val last = list[list.size - 1]
        val prev = last.points[last.points.size - 1]
        if (last.points.size >= chunkLimit) {
            // Continue the same segment in a new chunk that starts at the previous point.
            list.add(Chunk(mutableListOf(prev, m), continues = true))
            return true
        }
        last.points.add(m)
        return false
    }

    /** The finished part (every chunk but the last), each segment one line again: a chunk that goes on from the one
     *  before is joined to it, so the dot pattern doesn't restart at every chunk (iOS went on with it from each chunk's
     *  start distance; MapLibre still restarts it at tile edges, see `TrackLayer`). A lone point draws nothing, as iOS
     *  skips chunks of fewer than two points. */
    fun finishedLines(): List<List<GeoPoint>> {
        val lines = ArrayList<MutableList<GeoPoint>>()
        for (i in 0 until list.size - 1) {
            val c = list[i]
            if (c.continues && lines.isNotEmpty()) {
                lines[lines.size - 1].addAll(c.points.subList(1, c.points.size))
            } else {
                lines.add(c.points.toMutableList())
            }
        }
        return lines.filter { it.size >= 2 }
    }

    /** The live part: the last chunk, if it is a line yet. */
    fun liveLines(): List<List<GeoPoint>> {
        val last = list.lastOrNull() ?: return emptyList()
        return if (last.points.size >= 2) listOf(last.points.toList()) else emptyList()
    }

    companion object {
        const val chunkLimit = 512
    }
}

/** The dotted cyan track: the finished part (`track`) and the live part (`track-live`, the last ≤ 512 points of the
 *  current segment), so the whole track isn't re-sent every 2 s (the Swift chunk rule): the live part is sent on each
 *  new point, the finished part only when a chunk is done or a segment starts. MapLibre draws the dots (zero-length
 *  dashes with round caps) and simplifies the line itself (its GeoJSON tolerance, in place of iOS's 1.5 point skip).
 *
 *  Android difference: MapLibre starts the dot pattern afresh on each line, so where the live part joins the finished
 *  part (one place, moving on every 512 points) the spacing between two dots may be off once. It also starts it afresh
 *  in every tile the line crosses (each tile measures the dashes from where the line enters it, a little outside the
 *  tile), so the spacing may be off at tile edges too, where the edge can cut a dot in half. iOS kept the dots evenly
 *  spaced across its chunks and tiles (each chunk's start distance sets the dash phase); short of drawing the dots
 *  another way, MapLibre can't. */
class TrackLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    private val chunks = TrackChunks()

    /** Redraws from scratch (the track was cleared, or the map is new). */
    fun reset(track: List<TrackPoint>) {
        chunks.reset(track)
        show(MapIds.TRACK, chunks.finishedLines())
        show(MapIds.TRACK_LIVE, chunks.liveLines())
    }

    /** Appends new points, sending the finished part only when it changed. */
    fun append(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        var finished = false
        for (p in points) {
            if (chunks.append(p)) finished = true
        }
        if (finished) show(MapIds.TRACK, chunks.finishedLines())
        show(MapIds.TRACK_LIVE, chunks.liveLines())
    }

    fun append(p: TrackPoint) = append(listOf(p))

    private fun show(id: String, lines: List<List<GeoPoint>>) {
        val source = style.getSourceAs<GeoJsonSource>(id) ?: return
        source.setGeoJson(geoJson(lines))
    }

    companion object {
        /** The lines as one `MultiLineString` feature, or no feature at all. */
        fun geoJson(lines: List<List<GeoPoint>>): FeatureCollection {
            if (lines.isEmpty()) return MapStyle.empty()
            val coordinates = lines.map { line -> line.map { Point.fromLngLat(it.lon, it.lat) } }
            return FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLngLats(coordinates)))
        }
    }
}

/** The measuring line, dashed pink (placed: A to you; path: A → B → …; closed: back to A, filled pink 15 %). MapLibre
 *  lays out the dashes itself along the whole line (iOS's `MeasureLineRenderer` clipped each segment to the tile and
 *  kept the dash phase from the line's start; MapLibre restarts it in each tile the line crosses, see `TrackLayer`).
 *  While a measuring point is moved the line is drawn over the map instead (`DragLineView`), as on iOS. */
class MeasureLineLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    private var shown: Pair<List<GeoPoint>, Boolean>? = null

    /** Sets the line (fewer than two points hides it). Unchanged (it is set again on every GPS fix): nothing sent. */
    fun set(line: List<GeoPoint>, closed: Boolean) {
        val new = (if (line.size >= 2) line else emptyList()) to (closed && line.size >= 3)
        if (new == shown) return
        val source = style.getSourceAs<GeoJsonSource>(MapIds.MEASURE) ?: return
        shown = new
        source.setGeoJson(geoJson(new.first, new.second))
    }

    companion object {
        /** The line through the points, back to the first when the shape is closed, plus the closed shape's area for
         *  the fill; nothing for fewer than two points. */
        fun geoJson(line: List<GeoPoint>, closed: Boolean): FeatureCollection {
            if (line.size < 2) return MapStyle.empty()
            val ring = closed && line.size >= 3
            val points = line.map { Point.fromLngLat(it.lon, it.lat) }
            val path = if (ring) points + points[0] else points
            val features = mutableListOf(Feature.fromGeometry(LineString.fromLngLats(path)))
            if (ring) features += Feature.fromGeometry(Polygon.fromLngLats(listOf(path)))
            return FeatureCollection.fromFeatures(features)
        }
    }
}

/** While a measuring point is moved: the measuring line over the map rather than in it (iOS's `dragLine` in
 *  `MapController`), through the markers as they are now: from you for a single point, back to A and filled pink 15 %
 *  for a closed shape. Pink, 3 dp, dashes 10 and 7 dp from its start, butt caps and mitred corners: drawn on the screen,
 *  so the same at every zoom, where the map's own line has MapKit's tile scale (`MapStyle.zoomScaled`). Takes no
 *  touches.
 *
 *  Only the part on the view and 20 dp around it is drawn (as the aim line), its dashes where they fall along the whole
 *  line: Android 8's OpenGL renderer draws a path from a texture as big as the whole path, and leaves out one bigger
 *  than the GPU's largest texture (a line to a point a few km away, zoomed in), where Core Animation draws what is on
 *  the screen. */
class DragLineView(context: Context) : View(context) {
    /** The line in the view's pixels, and whether it is joined back to its start (and filled). */
    data class Line(val points: List<PointF>, val closed: Boolean)

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MapColours.measurePink
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.BUTT
        strokeMiter = 10f // Core Animation's miter limit
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapColours.measurePink15 }
    private val path = Path()
    private val drawn = RectF()
    private var line: Line? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows the line (null, or fewer than two points: none). */
    fun show(line: Line?) {
        val new = line?.takeIf { it.points.size >= 2 }
        if (new == this.line) return
        this.line = new
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = line ?: return
        // Past the view by more than a mitre (10 × 1.5 dp), so the cut ends and the corners outside it don't show.
        val margin = 20 * density
        drawn.set(-margin, -margin, width + margin, height + margin)
        if (l.closed) {
            val area = clipped(l.points, drawn)
            if (area.size >= 3) {
                path.reset()
                path.moveTo(area[0].x, area[0].y)
                for (i in 1 until area.size) path.lineTo(area[i].x, area[i].y)
                path.close()
                canvas.drawPath(path, fill)
            }
        }
        path.reset()
        dashes(l.points, l.closed, drawn, 10 * density, 7 * density, path)
        canvas.drawPath(path, stroke)
    }

    companion object {
        /** The points the line goes through: the markers, from you when there is only one. */
        fun points(markers: List<GeoPoint>, me: GeoPoint?): List<GeoPoint> =
            if (markers.size == 1 && me != null) listOf(me) + markers else markers

        /** The dashes (`on` long, `off` apart, the first from the start) of the line through `points`, back to the
         *  first when `closed`, where it is inside `r`, as contours of `into`. A dash that turns a corner is one
         *  contour, so the corner is mitred. A closed line's first dash is drawn last, going on from the last one when
         *  that reaches the start, as Skia's dashing (`DashPathEffect`) joins them. */
        internal fun dashes(points: List<PointF>, closed: Boolean, r: RectF, on: Float, off: Float, into: Path) {
            val period = (on + off).toDouble()
            var drawing = false // the last dash reaches the point it is at and goes on

            // The parts of dashes `from` until `until` on the segment a → b, `along` the line from its start; returns
            // the segment's length.
            fun segment(a: PointF, b: PointF, along: Double, from: Long, until: Long): Double {
                val dx = b.x.toDouble() - a.x
                val dy = b.y.toDouble() - a.y
                val length = hypot(dx, dy)
                if (length == 0.0) return 0.0 // nothing to draw, and a dash goes on past it
                val (t0, t1) = clip(a, b, r) ?: run { drawing = false; return length }
                if (t0 > 0) drawing = false
                val s0 = along + t0 * length
                val s1 = along + t1 * length
                var reached = false
                var k = max(from, floor(s0 / period).toLong())
                while (k < until && k * period < s1) {
                    val d0 = k * period
                    val u0 = max(d0, s0)
                    val u1 = min(d0 + on, s1)
                    if (u1 > u0) {
                        val f0 = (u0 - along) / length
                        val f1 = (u1 - along) / length
                        if (!drawing || d0 > s0) into.moveTo((a.x + dx * f0).toFloat(), (a.y + dy * f0).toFloat())
                        into.lineTo((a.x + dx * f1).toFloat(), (a.y + dy * f1).toFloat())
                        reached = u1 >= s1
                    }
                    k++
                }
                drawing = reached && t1 >= 1.0
                return length
            }

            val count = if (closed) points.size else points.size - 1
            var along = 0.0
            for (i in 0 until count) {
                along += segment(points[i], points[(i + 1) % points.size], along, if (closed) 1 else 0, Long.MAX_VALUE)
            }
            if (closed) {
                var head = 0.0
                for (i in 0 until count) {
                    if (head >= on) break
                    head += segment(points[i], points[(i + 1) % points.size], head, 0, 1)
                }
            }
        }

        /** The part of the segment a → b inside `r`, as (t0, t1) along it (0 at a, 1 at b), or null when none is
         *  (Liang–Barsky). */
        internal fun clip(a: PointF, b: PointF, r: RectF): Pair<Double, Double>? {
            var t0 = 0.0
            var t1 = 1.0
            for (edge in 0..3) {
                val sa = inside(a, edge, r)
                val sb = inside(b, edge, r)
                if (sa < 0 && sb < 0) return null
                if (sa < 0) t0 = max(t0, sa / (sa - sb)) else if (sb < 0) t1 = min(t1, sa / (sa - sb))
            }
            return if (t0 < t1) t0 to t1 else null
        }

        /** The polygon cut to `r` (Sutherland–Hodgman): inside `r`, it covers what the whole one covers. */
        internal fun clipped(polygon: List<PointF>, r: RectF): List<PointF> {
            var out = polygon
            for (edge in 0..3) {
                if (out.isEmpty()) break
                val cut = ArrayList<PointF>(out.size + 4)
                var p = out[out.size - 1]
                for (c in out) {
                    val sp = inside(p, edge, r)
                    val sc = inside(c, edge, r)
                    if ((sp < 0) != (sc < 0)) { // where p → c crosses the edge
                        val t = sp / (sp - sc)
                        val x = p.x + (c.x.toDouble() - p.x) * t
                        val y = p.y + (c.y.toDouble() - p.y) * t
                        cut += PointF(x.toFloat(), y.toFloat())
                    }
                    if (sc >= 0) cut += c
                    p = c
                }
                out = cut
            }
            return out
        }

        /** How far `p` is inside `r`'s left, right, top or bottom edge (0 to 3); negative outside it. */
        private fun inside(p: PointF, edge: Int, r: RectF): Double = when (edge) {
            0 -> p.x.toDouble() - r.left
            1 -> r.right.toDouble() - p.x
            2 -> p.y.toDouble() - r.top
            else -> r.bottom.toDouble() - p.y
        }
    }
}
