package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Bitmap
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLGeometry
import com.jacobusoettle.satmap.core.KMLStyle
import com.jacobusoettle.satmap.core.PolygonLabel
import com.jacobusoettle.satmap.kml.KMLLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import org.maplibre.geojson.Point as GeoJsonPoint

/** The blocks of the imported KML files on a map (the phone's, and the car's with Android Auto): those of the files
 *  shown, in the files' own colours or, with those switched off, all in the app's white. Per file a shapes source (its
 *  fills and lines, below the track, each newly shown file above the earlier ones) and a labels source (the blocks'
 *  names and the points, app-drawn images, as the style has no glyphs). The label images are drawn off the main
 *  thread; a file shows once they are ready. Main thread only. */
class BlockLayers(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** A KML point on the map, for the tap test: its file, its place in the file's points, where it is and its name
     *  ("" when it has none). */
    data class Point(val file: UUID, val index: Int, val coordinate: GeoPoint, val name: String)

    /** A label: a named block's name at a spot inside it, or a point with its name (`colour`: its icon's, if any). */
    data class Label(val coordinate: GeoPoint, val title: String, val isPoint: Boolean, val colour: Int? = null)

    /** A file's shapes (fills and lines with their colours and widths) and its labels, in the order iOS adds them. */
    class Shapes(val features: List<Feature>, val labels: List<Label>)

    /** A file on the map: its points, and the label images it added. */
    private class Shown(val points: List<Point>, val images: List<String>)

    private val shown = LinkedHashMap<UUID, Shown>()
    private var fileColours = true

    /** Draws the label images of newly shown files, then shows them in order. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adding: Job? = null

    /** The points of every shown file. */
    var points: List<Point> = emptyList()
        private set

    /** The points layers, for `queryRenderedFeatures`. */
    var pointLayerIds: Array<String> = emptyArray()
        private set

    /** Told when a file came onto the map or went. */
    var onChanged: (() -> Unit)? = null

    /** Shows the blocks of the files that are switched on and takes away the others. */
    fun update(files: List<KMLLibrary.File>, useFileColours: Boolean) {
        if (useFileColours != fileColours) {
            // Every block drawn again, in the other colours.
            for (id in shown.keys.toList()) remove(id)
            fileColours = useFileColours
        }
        val wanted = files.filter { it.isShown }
        val ids = wanted.map { it.id }.toSet()
        val before = shown.size
        for (id in shown.keys.toList()) {
            if (id !in ids) remove(id)
        }
        published()
        if (shown.size != before) onChanged?.invoke()
        // Those not on the map yet, in the files' order (each goes above the ones before). A file still being drawn
        // for the last update is drawn again, as it may have changed meanwhile.
        adding?.cancel()
        val add = wanted.filter { it.id !in shown }
        if (add.isEmpty()) return
        val metrics = context.resources.displayMetrics
        val colours = useFileColours
        adding = scope.launch {
            for (f in add) {
                val drawn = withContext(Dispatchers.Default) { draw(f, colours, metrics) }
                show(f.id, drawn)
            }
        }
    }

    /** The map is going: nothing more is added. */
    fun stop() {
        scope.cancel()
    }

    /** A file's sources, images and points, ready for the map. */
    private class Drawn(
        val shapes: FeatureCollection,
        val labels: FeatureCollection,
        val images: HashMap<String, Bitmap>,
        val points: List<Point>,
    )

    private fun draw(f: KMLLibrary.File, useFileColours: Boolean, metrics: android.util.DisplayMetrics): Drawn {
        val s = shapes(f, useFileColours)
        val images = HashMap<String, Bitmap>()
        val labels = ArrayList<Feature>()
        val points = ArrayList<Point>()
        for ((n, label) in s.labels.withIndex()) {
            val id = MapImages.kmlLabelId(f.id, n)
            val image = MapImages.kmlLabel(metrics, label)
            images[id] = image.bitmap
            val i = if (label.isPoint) points.size else null
            if (i != null) points += Point(f.id, i, label.coordinate, label.title)
            labels += labelFeature(f.id, label, id, image.width, image.height, i)
        }
        return Drawn(FeatureCollection.fromFeatures(s.features), FeatureCollection.fromFeatures(labels), images, points)
    }

    private fun show(id: UUID, d: Drawn) {
        if (style.getSource(MapIds.kml(id)) != null) remove(id) // (not expected: removed before it is drawn again)
        if (d.images.isNotEmpty()) style.addImages(d.images)
        style.addSource(GeoJsonSource(MapIds.kml(id), d.shapes))
        style.addSource(GeoJsonSource(MapIds.kmlLabels(id), d.labels))
        val layers = MapStyle.kmlLayers(id)
        style.addLayerBelow(layers.fill, MapIds.TRACK)
        style.addLayerBelow(layers.line, MapIds.TRACK)
        style.addLayerBelow(layers.names, MapIds.AVIATION_RINGS)
        // Over the selection ring, as on the iPhone: MapKit raises a tapped point's own view to the ring's height,
        // and draws its dot and name on top (iPhone screenshot 24).
        style.addLayerBelow(layers.points, MapIds.MEASURE_MARKERS)
        shown[id] = Shown(d.points, d.images.keys.toList())
        published()
        onChanged?.invoke()
    }

    private fun remove(id: UUID) {
        val s = shown.remove(id) ?: return
        style.removeLayer(MapIds.kmlPoints(id))
        style.removeLayer(MapIds.kmlNames(id))
        style.removeLayer(MapIds.kmlLine(id))
        style.removeLayer(MapIds.kmlFill(id))
        style.removeSource(MapIds.kmlLabels(id))
        style.removeSource(MapIds.kml(id))
        for (image in s.images) style.removeImage(image)
    }

    private fun published() {
        points = shown.values.flatMap { it.points }
        pointLayerIds = shown.keys.map { MapIds.kmlPoints(it) }.toTypedArray()
    }

    companion object {
        /** The app's own look, where a file leaves it unset: white (yellow/orange is used by contours, cyan by the
         *  track and pink by the measuring line), with a faint fill. */
        const val defaultStroke = "rgba(255,255,255,0.95)"
        const val defaultFill = "rgba(255,255,255,0.07)"

        /** No colour: a stroke or fill that is switched off. */
        const val clear = "rgba(0,0,0,0)"

        /** A KML file's shapes for a map, and the labels of named blocks and points. Without the file's colours, every
         *  shape has the app's own look. iOS makes one overlay for each style's polygons, then one for each style's
         *  lines (files often give every block a style of its own, with the same colours); the features come in that
         *  order, so blocks stack as they did. (MapLibre draws the file's fills in one layer under its lines in
         *  another, so with the file's colours all of a file's fills are under all of its outlines, where iOS drew fill
         *  and outline per style.) */
        fun shapes(f: KMLLibrary.File, useFileColours: Boolean): Shapes {
            val polygons = LinkedHashMap<KMLStyle?, MutableList<Feature>>()
            val lines = LinkedHashMap<KMLStyle?, MutableList<Feature>>()
            val labels = ArrayList<Label>()
            for (feature in f.document.features) {
                val style = if (useFileColours) feature.style else null
                when (val g = feature.geometry) {
                    is KMLGeometry.Polygon -> {
                        val rings = (listOf(g.outer) + g.holes).map { ring(it) }
                        polygons.getOrPut(style) { ArrayList() } +=
                            styled(Feature.fromGeometry(Polygon.fromLngLats(rings)), style, polygon = true)
                        val name = feature.name
                        if (name != null) {
                            PolygonLabel.interiorPoint(g.outer, g.holes)?.let { labels += Label(it, name, isPoint = false) }
                        }
                    }
                    is KMLGeometry.Line -> {
                        val pts = g.points.map { GeoJsonPoint.fromLngLat(it.lon, it.lat) }
                        lines.getOrPut(style) { ArrayList() } +=
                            styled(Feature.fromGeometry(LineString.fromLngLats(pts)), style, polygon = false)
                    }
                    is KMLGeometry.Point ->
                        labels += Label(g.point, feature.name ?: "", isPoint = true, colour = style?.iconColor?.argb())
                }
            }
            return Shapes(polygons.values.flatten() + lines.values.flatten(), labels)
        }

        /** Blocks in their file's colours and line widths (KML styles), the rest in the app's own look: `stroke` (none
         *  when the width is 0 or less, or a polygon has no outline), `width` (2.5 unless set, kept within 1.5–12) and,
         *  for polygons, `fill` (none when the style says so). */
        private fun styled(feature: Feature, style: KMLStyle?, polygon: Boolean): Feature {
            val s = style ?: KMLStyle()
            val width = s.lineWidth ?: 2.5
            val outline = width > 0 && (!polygon || s.outline != false)
            feature.addStringProperty("stroke", if (outline) s.lineColor?.rgba() ?: defaultStroke else clear)
            feature.addNumberProperty("width", min(12.0, max(1.5, width)))
            if (polygon) feature.addStringProperty("fill", if (s.fill == false) clear else s.fillColor?.rgba() ?: defaultFill)
            return feature
        }

        /** A ring as GeoJSON wants it, ending where it starts (MapKit closes a polygon by itself; MapLibre's outline
         *  would leave out the last side). */
        private fun ring(points: List<GeoPoint>): List<GeoJsonPoint> {
            val ring = points.map { GeoJsonPoint.fromLngLat(it.lon, it.lat) }
            if (points.size >= 2 && points.first() != points.last()) return ring + ring[0]
            return ring
        }

        /** A label's point in the labels source: `kind` ("name" or "point", for the two layers), its image `img` and
         *  that image's size in dp (`w`, `h`), its latitude `lat` (the sort key: of two that collide, the southern one
         *  shows), its name, its file, and for a point `i`, its place in the file's points. */
        fun labelFeature(file: UUID, label: Label, image: String, width: Float, height: Float, i: Int?): Feature =
            Feature.fromGeometry(GeoJsonPoint.fromLngLat(label.coordinate.lon, label.coordinate.lat)).apply {
                addStringProperty("kind", if (label.isPoint) "point" else "name")
                addStringProperty("img", image)
                addNumberProperty("w", width)
                addNumberProperty("h", height)
                addNumberProperty("lat", label.coordinate.lat)
                addStringProperty("name", label.title)
                addStringProperty("file", file.toString())
                if (i != null) addNumberProperty("i", i)
            }
    }
}
