package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TerrainMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** What the legends show: the contour interval and the elevation colours' range (each null when not shown). */
data class TerrainKey(val contours: ContourInterval? = null, val colours: ElevationRange? = null)

/** Metres between contour lines, and between the bold ones (labelled with their height). */
data class ContourInterval(val step: Double, val index: Double)

/** The satellite imagery with the contour lines and elevation colours of one map view: the style's raster sources and
 *  layers (each a generation of `TileUrls`), the levels and focus, prefetch when the map stops, the colour range, the
 *  replacement of a layer without flashing, and the legend key. The phone's map has one, and the car's map another.
 *  `MapController` (or `CarMapController`) calls the hooks; this registers no listeners (DESIGN.md D18). */
class DrawnImagery(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val isVisible: () -> Boolean,
    private val terrain: () -> TerrainMode,
) {
    val drawnMap = DrawnMap()

    var colourRange: ElevationRange? = null
        private set

    var onKey: ((TerrainKey) -> Unit)? = null

    /** Adds imagery, colours (visibility from the mode) and contours, each a fresh generation, in that order. */
    fun addTo(builder: Style.Builder) {
        // M1: the imagery source and layer; M2: colours and contours.
    }

    fun regionWillChange() {
        // M1: moving = true (M2: swap timing).
    }

    fun regionDidChange() {
        // M1: mapStopped() after 400 ms (focus, prefetch, M2: colour range).
    }

    fun visibleRegionChanged() {
        // M1: the focus; M2: the legend key.
    }

    fun reload() {
        // M2: a new generation for each kind with stand-ins in focus.
    }

    fun terrainChanged(mode: TerrainMode) {
        // M2: colours and contours visibility, the colour range, the key.
    }

    fun becameVisible() {
        // M2: the owed replacement and the colour range.
    }
}
