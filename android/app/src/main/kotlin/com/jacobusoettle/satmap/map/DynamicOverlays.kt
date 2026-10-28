package com.jacobusoettle.satmap.map

import android.content.Context
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** The dotted cyan track: the finished part (`track`) and the live part (`track-live`, the last ≤ 512 points of the
 *  current segment), so the whole track isn't re-sent every 2 s (the Swift chunk rule). */
class TrackLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** Redraws from scratch (the track was cleared, or the map is new). */
    fun reset(track: List<TrackPoint>) {
        // M1: both sources from the track.
    }

    fun append(p: TrackPoint) {
        // M1: the live part, moved into the finished part at 512 points.
    }
}

/** The measuring line (placed: A to you; path: A → B → …; closed: back to A, filled). */
class MeasureLineLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    fun update(points: List<GeoPoint>, closed: Boolean, from: GeoPoint?) {
        // M3: the `measure` source.
    }
}
