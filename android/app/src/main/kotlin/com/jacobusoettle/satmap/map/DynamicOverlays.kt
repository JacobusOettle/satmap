package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
 *  touches. */
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
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 7 * density), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapColours.measurePink15 }
    private val path = Path()
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
        path.reset()
        path.moveTo(l.points[0].x, l.points[0].y)
        for (p in l.points.drop(1)) path.lineTo(p.x, p.y)
        if (l.closed) {
            path.close()
            canvas.drawPath(path, fill)
        }
        canvas.drawPath(path, stroke)
    }

    companion object {
        /** The points the line goes through: the markers, from you when there is only one. */
        fun points(markers: List<GeoPoint>, me: GeoPoint?): List<GeoPoint> =
            if (markers.size == 1 && me != null) listOf(me) + markers else markers
    }
}
