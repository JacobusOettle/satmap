package com.jacobusoettle.satmap.offline

import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoPoint

/** Finds parks, reserves, towns and camps by name (Photon), and a park's boundary (Nominatim). */
object PlaceSearch {
    /** The Swift `Failure` cases, with its texts. */
    sealed class Failure(message: String) : Exception(message) {
        class Offline : Failure("No internet connection. Places are looked up online; try again when connected.")

        class Unavailable : Failure("The place search didn't answer. Try again in a moment.")
    }

    /** The places called `text`, the nearest to `near` first. */
    suspend fun search(text: String, near: GeoPoint?): List<FoundPlace> = emptyList() // M4: offline spec §1.12.

    /** The place's area: within its boundary, or around the spot. */
    suspend fun area(place: FoundPlace): CoverageShape = CoverageShape() // M4: offline spec §1.12.
}
