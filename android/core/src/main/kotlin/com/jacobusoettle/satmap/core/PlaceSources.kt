package com.jacobusoettle.satmap.core

import kotlin.math.max

/** Which service finds places by name (Swift's `PlaceSearchFallback`): Photon first (forgiving of spelling, near the
 *  map's middle), Nominatim's own search when Photon fails or doesn't answer within [photonTimeoutMillis]. A failure
 *  is remembered for [photonRestMillis], so the searches in between go straight to Nominatim; then Photon is tried
 *  again. A working Photon that finds nothing has found nothing. Nominatim is asked at most once a second
 *  ([nominatimSpacingMillis]), as its usage policy asks: searches and boundaries alike, and only per search, never per
 *  keystroke. `now` is a monotonic clock in milliseconds (a test sets its own). */
class PlaceSources(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        /** How long Photon has to answer a search (the same in both apps: 5 s). */
        const val photonTimeoutMillis = 5_000L

        /** How long a Photon failure is remembered (10 minutes). */
        const val photonRestMillis = 10 * 60_000L

        /** The least time between two Nominatim requests. */
        const val nominatimSpacingMillis = 1_000L

        /** At most this many results per search, from either service. */
        const val searchLimit = 20

        /** The countries Nominatim searches first for a map in southern Africa (South Africa and its neighbours, as far
         *  as Mozambique's north): Nominatim, unlike Photon, doesn't rank by nearness, and misses "Sabi Sand" among the
         *  whole world's names. Null elsewhere, or with no map. */
        fun nominatimCountries(near: GeoPoint?): List<String>? {
            if (near == null || near.lat !in -35.5..-10.3 || near.lon !in 11.5..41.0) return null
            return listOf("za", "ls", "sz", "na", "bw", "zw", "mz")
        }

        /** The Nominatim searches for one text, each only if the one before found no place: within the countries
         *  around the map, then the whole world. */
        fun nominatimCountryPasses(near: GeoPoint?): List<List<String>?> {
            val countries = nominatimCountries(near) ?: return listOf(null)
            return listOf(countries, null)
        }

        /** The query of Nominatim's search for the same text as Photon's: the same limit, each result's county, state
         *  and country (`addressdetails`) for the same line under its name, Photon's nearness to the map's middle as a
         *  preferred box of 1 degree around it, and the `countries` of this pass. No language is asked for, as Photon
         *  is asked for none. Numbers always with a decimal point. The same requests as the iPhone app's. */
        fun nominatimQuery(text: String, near: GeoPoint?, countries: List<String>?): List<Pair<String, String>> {
            val query = mutableListOf("q" to text, "format" to "jsonv2", "addressdetails" to "1", "limit" to "$searchLimit")
            if (near != null) {
                val box = listOf(near.lon - 1, near.lat + 1, near.lon + 1, near.lat - 1)
                query.add("viewbox" to box.joinToString(",") { stringFormat("%.4f", it) })
            }
            if (!countries.isNullOrEmpty()) query.add("countrycodes" to countries.joinToString(","))
            return query
        }
    }

    private var photonFailedAt: Long? = null
    private var nextNominatimAt = Long.MIN_VALUE

    /** Whether to ask Photon: not within [photonRestMillis] of its last failure. */
    @Synchronized
    fun usePhoton(): Boolean {
        val failed = photonFailedAt ?: return true
        val since = now() - failed
        return !(since >= 0 && since < photonRestMillis)
    }

    /** Photon failed or didn't answer in time: Nominatim for the next [photonRestMillis]. */
    @Synchronized
    fun photonFailed() {
        photonFailedAt = now()
    }

    /** Photon answered: it's asked again from now on. */
    @Synchronized
    fun photonWorked() {
        photonFailedAt = null
    }

    /** How long to wait before the next Nominatim request, which takes its turn (the one after waits a second more). */
    @Synchronized
    fun nominatimTurn(): Long {
        val t = now()
        val at = max(t, nextNominatimAt)
        nextNominatimAt = at + nominatimSpacingMillis
        return at - t
    }
}
