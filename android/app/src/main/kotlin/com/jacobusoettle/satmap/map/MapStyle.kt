package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconPadding
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.symbolZOrder
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.util.UUID
import kotlin.math.pow

// Android only: the MapLibre style SatMap builds in code (no style, glyph or sprite URL exists), DESIGN.md §3.8.

/** Every source and layer id of the style. */
object MapIds {
    const val BACKGROUND = "background"

    /** A hidden background layer above the terrain rasters that anchors the overlays. */
    const val TERRAIN_TOP = "terrain-top"
    const val TRACK = "track"
    const val TRACK_LIVE = "track-live"
    const val MEASURE = "measure"
    const val MEASURE_FILL = "measure-fill"
    const val MEASURE_LINE = "measure-line"
    const val DRAFT = "draft"
    const val DRAFT_FILL = "draft-fill"
    const val DRAFT_LINE = "draft-line"
    const val AVIATION = "aviation"
    const val AVIATION_RINGS = "aviation-rings"
    const val SELECTED = "selected"
    const val MEASURE_POINTS = "measure-points"
    const val MEASURE_MARKERS = "measure-markers"
    const val MEASURE_LETTERS = "measure-letters"
    const val USER = "user"
    const val USER_ARROW = "user-arrow"
    const val USER_DOT = "user-dot"

    /** A KML file's shapes and their fill and line layers. */
    fun kml(file: UUID) = "kml/$file"
    fun kmlFill(file: UUID) = "kml/$file/fill"
    fun kmlLine(file: UUID) = "kml/$file/line"

    /** A KML file's names and points (app-drawn images) and their symbol layers. */
    fun kmlLabels(file: UUID) = "kml/$file/labels"
    fun kmlNames(file: UUID) = "kml/$file/names"
    fun kmlPoints(file: UUID) = "kml/$file/points"
}

object MapStyle {
    /** The dark grey where there is no imagery at all. */
    const val BACKGROUND_COLOUR = 0xFF202020.toInt()

    /** The base style: the background, the terrain rasters (`imagery.addTo`), the `terrain-top` anchor, and no
     *  transitions. */
    fun builder(imagery: DrawnImagery): Style.Builder {
        val builder = Style.Builder()
            .fromJson("""{"version":8,"sources":{},"layers":[]}""")
            .withLayer(BackgroundLayer(MapIds.BACKGROUND).withProperties(backgroundColor(BACKGROUND_COLOUR)))
        imagery.addTo(builder)
        return builder
            .withLayer(BackgroundLayer(MapIds.TERRAIN_TOP).withProperties(visibility(Property.NONE)))
            .withTransition(TransitionOptions(0, 0, false))
    }

    /** The overlay sources, all empty at first; the tiny live ones are updated in the same frame (a moved point shows
     *  at once). The phone and the car install them all; those a map doesn't use stay empty. */
    private val sources = listOf(
        MapIds.TRACK to false,
        MapIds.TRACK_LIVE to false,
        MapIds.MEASURE to true,
        MapIds.DRAFT to false,
        MapIds.AVIATION to false,
        MapIds.SELECTED to true,
        MapIds.MEASURE_POINTS to true,
        MapIds.USER to true,
    )

    /** Every fixed overlay source (empty GeoJSON) and layer, bottom to top (DESIGN.md §3.8), and the base images. Run in
     *  `OnStyleLoaded`; idempotent (a missing layer goes back where it belongs, above the one before it). */
    fun installOverlays(style: Style, images: Map<String, Bitmap>) {
        if (images.isNotEmpty()) style.addImages(HashMap(images))
        for ((id, synchronous) in sources) {
            if (style.getSource(id) != null) continue
            style.addSource(
                if (synchronous) GeoJsonSource(id, empty(), GeoJsonOptions().withSynchronousUpdate(true))
                else GeoJsonSource(id, empty()),
            )
        }
        var below = MapIds.TERRAIN_TOP
        for ((id, layer) in overlayLayers) {
            if (style.getLayer(id) == null) style.addLayerAbove(layer(), below)
            below = id
        }
    }

    /** The fixed overlay sources and layers, bottom to top, as `installOverlays` adds them. */
    val overlaySourceIds: List<String> get() = sources.map { it.first }
    val overlayLayerIds: List<String> get() = overlayLayers.map { it.first }

    /** No features: what every overlay source starts with, and what it is set to when it has nothing to show. */
    fun empty(): FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

    /** The fixed overlay layers in stack order, each made only when it is added. The per-file KML layers go in
     *  between: fills and lines below `track`, names below `aviation-rings`, points below `selected`. */
    private val overlayLayers: List<Pair<String, () -> Layer>> = listOf(
        // The dotted track: zero-length dashes with round caps draw dots 2.25 line widths apart.
        MapIds.TRACK to { trackLine(MapIds.TRACK) },
        MapIds.TRACK_LIVE to { trackLine(MapIds.TRACK_LIVE) },
        // The measuring line (pink, 3 wide, dashes 10 and 7, at MapKit's tile scale) and the closed shape's light fill.
        MapIds.MEASURE_FILL to {
            FillLayer(MapIds.MEASURE_FILL, MapIds.MEASURE)
                .withFilter(eq(geometryType(), "Polygon"))
                .withProperties(fillColor(MapColours.measurePink15))
        },
        MapIds.MEASURE_LINE to {
            LineLayer(MapIds.MEASURE_LINE, MapIds.MEASURE)
                .withFilter(eq(geometryType(), "LineString"))
                .withProperties(
                    lineColor(MapColours.measurePink),
                    lineWidth(zoomScaled(3f)),
                    lineDasharray(arrayOf(10f / 3, 7f / 3)),
                    lineCap(Property.LINE_CAP_BUTT),
                )
        },
        // The drawn area waiting to be confirmed: white, 15 % fill, dashes 8 and 6 dp at 2.5 wide.
        MapIds.DRAFT_FILL to {
            FillLayer(MapIds.DRAFT_FILL, MapIds.DRAFT).withProperties(fillColor(0x26FFFFFF))
        },
        MapIds.DRAFT_LINE to {
            LineLayer(MapIds.DRAFT_LINE, MapIds.DRAFT).withProperties(
                lineColor(0xFFFFFFFF.toInt()),
                lineWidth(2.5f),
                lineDasharray(arrayOf(3.2f, 2.4f)),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        },
        // Drone mode: the runway rings turn with the map, only under cards MapLibre shows; the cards collide with each
        // other and with the block names below them, by priority (the sort key, also their drawing order).
        MapIds.AVIATION_RINGS to {
            SymbolLayer(MapIds.AVIATION_RINGS, MapIds.AVIATION)
                .withFilter(aviationRingsFilter(emptyList()))
                .withProperties(
                    iconImage(get("ring")),
                    iconRotate(get("rwy")),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconPadding(0f),
                )
        },
        MapIds.AVIATION to {
            SymbolLayer(MapIds.AVIATION, MapIds.AVIATION).withProperties(
                iconImage(get("img")),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                iconOffset(arrayOf(0f, -11f)),
                iconPadding(0f),
                symbolSortKey(get("key")),
                symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
                iconAllowOverlap(false),
                iconIgnorePlacement(false),
            )
        },
        // The tapped spot's pin: drawn whatever it covers, hiding nothing (a selected KML point keeps its dot and name).
        MapIds.SELECTED to {
            SymbolLayer(MapIds.SELECTED, MapIds.SELECTED).withProperties(
                iconImage(MapImages.SELECTED),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
        },
        // The measuring points always show and hide labels under them and round them; their letters hide nothing.
        MapIds.MEASURE_MARKERS to {
            SymbolLayer(MapIds.MEASURE_MARKERS, MapIds.MEASURE_POINTS).withProperties(
                iconImage(MapImages.MEASURE_MARKER),
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
                iconPadding(LABEL_PADDING),
            )
        },
        MapIds.MEASURE_LETTERS to {
            SymbolLayer(MapIds.MEASURE_LETTERS, MapIds.MEASURE_POINTS)
                .withFilter(has("letter"))
                .withProperties(
                    iconImage(get("letter")),
                    iconAnchor(Property.ICON_ANCHOR_TOP_LEFT),
                    iconOffset(arrayOf(3f, -29f)),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
        },
        // You, on top of everything: the compass arrow (turned against the map's north, so MapLibre turns it with the
        // map), and the dot, which always shows and hides labels under it and round it (its image has 3 dp round the
        // 22 dp dot for the shadow, so 3 dp less room).
        MapIds.USER_ARROW to {
            SymbolLayer(MapIds.USER_ARROW, MapIds.USER)
                .withFilter(has("heading"))
                .withProperties(
                    iconImage(get("arrow")),
                    iconRotate(get("heading")),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconPadding(0f),
                )
        },
        MapIds.USER_DOT to {
            SymbolLayer(MapIds.USER_DOT, MapIds.USER).withProperties(
                iconImage(get("dot")),
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
                iconPadding(LABEL_PADDING - 3),
            )
        },
    )

    /** The track's cyan dots (iOS `TrackRenderer`: 4 points wide, zero-length dashes 9 points apart, round), at
     *  MapKit's tile scale. */
    private fun trackLine(id: String): Layer = LineLayer(id, id).withProperties(
        lineColor(MapColours.trackCyan),
        lineWidth(zoomScaled(4f)),
        lineDasharray(arrayOf(0f, 2.25f)),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )

    /** A line `points` wide as iOS's own renderers draw it (`TrackRenderer`, `MeasureLineRenderer`, their dashes in
     *  points too). MapKit draws them into its tiles one whole zoom further in and shows those shrunk by
     *  k = 2^(z − ⌈z⌉), ½ to 1, so the line and its dashes are k times their size on screen. MapLibre lays out dashes
     *  in widths at the whole zoom below (`floorwidth`) and grows them with the map, by 2^(z − ⌊z⌋): half the width at
     *  each whole zoom, growing with the map until the next, gives iOS's width and dashes at every zoom in between (at
     *  a whole zoom itself iOS shows them twice as big). Up to zoom 26, past MapLibre's 25.5. */
    fun zoomScaled(points: Float): Expression {
        val grown = 2.0.pow(0.999).toFloat()
        val stops = (0..25).flatMap { n -> listOf(stop(n.toFloat(), points / 2), stop(n + 0.999f, points / 2 * grown)) }
        return interpolate(exponential(2f), zoom(), *stops.toTypedArray())
    }

    /** The room MapKit keeps round a KML label's frame (and the measuring points' and yours): two collide once their
     *  frames, each grown by about 5 points on every side, overlap (fitted to the iPhone screenshots, which 4.9 to 5.8
     *  fit). Android's name and point images are 1 dp taller than the frames, for the shadow; 5 fits them too. MapLibre
     *  answers `queryRenderedFeatures` with these grown boxes. The aviation cards keep none: no screenshot shows two
     *  near each other, and their tap test (`AviationLayer.place`) goes by their boxes. */
    const val LABEL_PADDING = 5f

    /** The runway rings of the places whose cards MapLibre shows (`AviationLayer` keeps the ids up to date). */
    fun aviationRingsFilter(visibleIds: Collection<Int>): Expression =
        all(has("ring"), Expression.`in`(get("id"), literal(visibleIds.map { it as Any }.toTypedArray())))

    /** A KML file's layers, for `BlockLayers` to insert against the anchors (DESIGN.md §3.8 rows 6, 10 and 13). */
    class KmlLayers(val fill: Layer, val line: Layer, val names: Layer, val points: Layer)

    /** A KML file's blocks in their colours and widths (`BlockLayers.shapes` gives each shape its `fill`, `stroke` and
     *  `width`; round joins and caps, as MapKit's path renderers), then its labels: the blocks' names, which give way to
     *  everything else (iOS display priority 250), and the points with their names (750, above the aviation cards).
     *  Every label is an app-drawn image, its frame and MapKit's room round it what collides. Of two labels of a layer
     *  that collide, the lower on the screen shows, as MapKit places equal priorities from the bottom up: MapLibre
     *  places them by their sort key, the label's latitude (south first; on a turned map only near the screen's
     *  order). */
    fun kmlLayers(file: UUID): KmlLayers = KmlLayers(
        fill = FillLayer(MapIds.kmlFill(file), MapIds.kml(file))
            .withFilter(eq(geometryType(), "Polygon"))
            .withProperties(fillColor(toColor(get("fill")))),
        line = LineLayer(MapIds.kmlLine(file), MapIds.kml(file)).withProperties(
            lineColor(toColor(get("stroke"))),
            lineWidth(get("width")),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        ),
        // A name's image is 1 dp taller than its text, for the shadow: moved down half of it, so the text is centred.
        names = SymbolLayer(MapIds.kmlNames(file), MapIds.kmlLabels(file))
            .withFilter(eq(get("kind"), "name"))
            .withProperties(
                iconImage(get("img")),
                iconOffset(arrayOf(0f, 0.5f)),
                iconAllowOverlap(false),
                iconIgnorePlacement(false),
                iconPadding(LABEL_PADDING),
                symbolSortKey(get("lat")),
            ),
        // A point's image has the dot at its top, the name under it: the dot's centre (5 dp down) on the point.
        points = SymbolLayer(MapIds.kmlPoints(file), MapIds.kmlLabels(file))
            .withFilter(eq(get("kind"), "point"))
            .withProperties(
                iconImage(get("img")),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                iconOffset(arrayOf(0f, -5f)),
                iconAllowOverlap(false),
                iconIgnorePlacement(false),
                iconPadding(LABEL_PADDING),
                symbolSortKey(get("lat")),
            ),
    )
}
