package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.TransitionOptions
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

    /** Every fixed overlay source (empty GeoJSON) and layer, bottom to top (DESIGN.md §3.8), and the base images. Run in
     *  `OnStyleLoaded`; idempotent. */
    fun installOverlays(style: Style, images: Map<String, Bitmap>) {
        // M1: user and track; M3: measure, draft, selected, the KML anchors; M5: aviation.
    }

    /** A KML file's fill, line, names and points layers, for `BlockLayers` to insert against the anchors. */
    fun kmlLayers(file: UUID): List<Layer> = emptyList() // M3: DESIGN.md §3.8 rows 6, 10 and 13.
}
