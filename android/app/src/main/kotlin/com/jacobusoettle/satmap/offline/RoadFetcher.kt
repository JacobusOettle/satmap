package com.jacobusoettle.satmap.offline

import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.RoadPreset

/** The road lists shipped with the app (assets/<id>.json, from SatMap/Resources), read once each. */
object RoadPresets {
    /** Every tar and gravel road in Kruger National Park. */
    const val kruger = "KrugerRoads"

    fun load(id: String?): RoadPreset? = null // M4: reads and caches assets/<id>.json (thread-safe).

    /** A shipped preset holding the area (compared at zoom 14), if any. */
    fun covering(area: CoverageShape): RoadPreset? = null // M4: offline spec §1.9 item 4.
}

/** The roads and tracks in an area, from OpenStreetMap through Overpass: servers asked in turn, the next one when the
 *  previous fails or after 12 s without an answer; the first good answer wins. */
object RoadFetcher {
    /** The Swift `Failure` cases, with its texts. */
    sealed class Failure(message: String) : Exception(message) {
        class Offline : Failure("No internet connection. Roads are looked up online; try again when connected.")

        class Unavailable : Failure("The OpenStreetMap road server is busy or unreachable. Try again in a minute.")
    }

    suspend fun roads(area: CoverageShape): List<List<GeoPoint>> = emptyList() // M4: offline spec §1.10 and §2.11.
}
