package com.jacobusoettle.satmap.map

import android.content.Context
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.kml.KMLLibrary
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import java.util.UUID

/** The shown KML files on the map: per file a shapes source (fills and lines) and a labels source (names and points,
 *  app-drawn images), each newly shown file above the earlier ones and below the track. */
class BlockLayers(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** A KML point on the map, for the tap test. */
    data class Point(val file: UUID, val index: Int, val coordinate: GeoPoint, val name: String?)

    /** The points of every shown file. */
    var points: List<Point> = emptyList()
        private set

    /** The points layers, for `queryRenderedFeatures`. */
    var pointLayerIds: Array<String> = emptyArray()
        private set

    fun update(files: List<KMLLibrary.File>, useFileColours: Boolean) {
        // M3: adds, replaces and removes the per-file sources and layers.
    }

    companion object {
        /** A file's shape features (fill, stroke, width) and label features (names and points). */
        fun shapes(f: KMLLibrary.File, useFileColours: Boolean): Pair<List<Feature>, List<Feature>> =
            Pair(emptyList(), emptyList()) // M3: interaction spec §1.8 and §2.5.
    }
}
