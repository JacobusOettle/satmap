package com.jacobusoettle.satmap.map

import android.content.Context
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.AviationPoint
import com.jacobusoettle.satmap.core.GeoPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** The aerodromes and navaids shipped with the app (assets/Aviation.tsv, from SatMap/Resources), read once on
 *  `Dispatchers.IO` the first time drone mode needs them. */
class AviationStore private constructor() {
    /** The index if it has been read. */
    @Volatile
    var index: AviationIndex? = null
        private set

    /** Calls back on the main thread with the index, reading it first if needed. */
    fun load(context: Context, done: (AviationIndex) -> Unit) {
        // M5: reads assets.open("Aviation.tsv") once; every waiting caller is called back on the main thread.
    }

    companion object {
        val shared = AviationStore()
    }
}

/** Drone mode on the map: the places for the region (cards with distances from you and the pin), and the
 *  map-aligned runway rings of the cards MapLibre shows. */
class AviationLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    var isOn: Boolean = false

    /** When the camera goes idle: the places to show. */
    fun regionChanged() {
        // M5: interaction spec §1.10 and §2.9.
    }

    fun distancesChanged(me: GeoPoint?, pin: GeoPoint?) {
        // M5: redraws the cards when you or the pin moved by 20 m or more.
    }
}

/** Paints a place's card (what iOS's `AviationView` drew) into a bitmap. */
object AviationCard {
    /** The card's text lines (code, kind, name; the distances from you and the pin). */
    fun text(place: AviationPoint, me: GeoPoint?, pin: GeoPoint?): CharSequence = place.name // M5: the styled runs.
}
