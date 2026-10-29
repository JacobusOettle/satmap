package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
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
        // The dotted track: zero-length dashes with round caps draw 4 dp dots 9 dp apart (2.25 line widths).
        MapIds.TRACK to { trackLine(MapIds.TRACK) },
        MapIds.TRACK_LIVE to { trackLine(MapIds.TRACK_LIVE) },
        // The measuring line (pink, 3 wide, dashes 10 and 7 dp) and the closed shape's light fill.
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
                    lineWidth(3f),
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
        // The measuring points always show and hide labels under them; their letters hide nothing.
        MapIds.MEASURE_MARKERS to {
            SymbolLayer(MapIds.MEASURE_MARKERS, MapIds.MEASURE_POINTS).withProperties(
                iconImage(MapImages.MEASURE_MARKER),
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
                iconPadding(0f),
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
        // map), and the dot, which always shows and hides labels under it.
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
                iconPadding(0f),
            )
        },
    )

    /** The track's cyan dots (iOS `TrackRenderer`: 4 points wide, zero-length dashes 9 points apart, round). */
    private fun trackLine(id: String): Layer = LineLayer(id, id).withProperties(
        lineColor(MapColours.trackCyan),
        lineWidth(4f),
        lineDasharray(arrayOf(0f, 2.25f)),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )

    /** The runway rings of the places whose cards MapLibre shows (`AviationLayer` keeps the ids up to date). */
    fun aviationRingsFilter(visibleIds: Collection<Int>): Expression =
        all(has("ring"), Expression.`in`(get("id"), literal(visibleIds.map { it as Any }.toTypedArray())))

    /** A KML file's fill, line, names and points layers, for `BlockLayers` to insert against the anchors. */
    fun kmlLayers(file: UUID): List<Layer> = emptyList() // M3: DESIGN.md §3.8 rows 6, 10 and 13.
}
