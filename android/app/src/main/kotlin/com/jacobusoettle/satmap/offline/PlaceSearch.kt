package com.jacobusoettle.satmap.offline

import android.util.Log
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.PlaceSources
import com.jacobusoettle.satmap.core.Places
import com.jacobusoettle.satmap.core.stringFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val placeLog = "places"

/** Finding a place by name for an offline area: Photon (komoot's OpenStreetMap search, forgiving of spelling) for the
 *  names, or Nominatim (OpenStreetMap's own) while Photon is down; then Nominatim for the chosen place's boundary. Both
 *  are public services: one search per tap of Search, one boundary per place chosen, Nominatim at most once a second.
 *  Which service, how long Photon has, and how long its failure is remembered: core's `PlaceSources`. */
object PlaceSearch {
    /** The Swift `Failure` cases, with its texts. */
    sealed class Failure(message: String) : Exception(message) {
        class Offline : Failure("No internet connection. Places are looked up online; try again when connected.")

        class Unavailable : Failure("The place search didn't answer. Try again in a moment.")
    }

    /** The services (a test sets its own). */
    internal var photon: HttpUrl = "https://photon.komoot.io/api/".toHttpUrl()
    internal var nominatim: HttpUrl = "https://nominatim.openstreetmap.org/lookup".toHttpUrl()
    internal var nominatimSearch: HttpUrl = "https://nominatim.openstreetmap.org/search".toHttpUrl()

    /** 20 s timeouts, with the User-Agent that identifies the app to the OpenStreetMap services, as their usage
     *  policies ask (`DownloadHttp.lookupUserAgent`). (A test sets its own.) */
    internal var client: OkHttpClient = DownloadHttp.lookups

    /** How long Photon has to answer a search (a test sets a shorter one). */
    internal var photonTimeoutMillis: Long = PlaceSources.photonTimeoutMillis

    /** Photon or Nominatim, and Nominatim's once a second (a test sets its own, with its own clock). */
    internal var sources = PlaceSources()

    /** Whether the phone has a network: `Connectivity`'s snapshot (a test sets its own). */
    internal var isOnline: () -> Boolean = { Connectivity.shared.isOnlineNow }

    /** Where answers are read, off the caller's thread (iOS runs these off the main actor). (A test sets its own.) */
    internal var work: CoroutineDispatcher = Dispatchers.Default

    /** Places matching a name, nearest `near` (the map's middle) first; reserves and parks before the rest, the whole
     *  of one before pieces of it. A name with a plural ("Sabi Sands") is also searched without it. */
    suspend fun search(text: String, near: GeoPoint?): List<FoundPlace> {
        val first = find(text, near)
        val other = Places.singular(text) ?: return Places.merged(listOf(first))
        val second = try {
            find(other, near)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return Places.merged(listOf(first, second))
    }

    /** One search: Photon, unless it failed lately; Nominatim when it fails now (an error, no answer within
     *  [photonTimeoutMillis], or an answer that isn't one), and that failure remembered (`PlaceSources.photonFailed`).
     *  Photon refusing this one query (`PlaceSources.photonRefusedQuery`) sends only this search to Nominatim. No
     *  network on the phone is no service's fault: `Offline` at once, nothing remembered. Photon finding nothing is
     *  an answer: nothing is found. */
    private suspend fun find(text: String, near: GeoPoint?): List<FoundPlace> {
        if (!sources.usePhoton()) return searchNominatim(text, near)
        try {
            return photon(text, near).also { sources.photonWorked() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Failure.Offline) {
            throw e
        } catch (e: PhotonRefused) {
            Log.w(placeLog, "Photon refused the query (HTTP ${e.status}): searching with Nominatim this once")
        } catch (e: Failure) {
            Log.w(placeLog, "Photon failed: searching with Nominatim for a while")
            sources.photonFailed()
        }
        return searchNominatim(text, near)
    }

    /** Photon's HTTP 4xx for one query (`PlaceSources.photonRefusedQuery`). */
    private class PhotonRefused(val status: Int) : Exception("HTTP $status")

    private suspend fun photon(text: String, near: GeoPoint?): List<FoundPlace> {
        val url = photon.newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("limit", "${PlaceSources.searchLimit}")
        if (near != null) {
            // Always with a decimal point (core's `stringFormat`), whatever the phone's language.
            url.addQueryParameter("lat", stringFormat("%.4f", near.lat))
            url.addQueryParameter("lon", stringFormat("%.4f", near.lon))
        }
        val quick = client.newBuilder().callTimeout(photonTimeoutMillis, TimeUnit.MILLISECONDS).build()
        val answer = request(url.build(), quick)
        if (answer.code != 200) {
            if (PlaceSources.photonRefusedQuery(answer.code)) throw PhotonRefused(answer.code)
            throw Failure.Unavailable()
        }
        return withContext(work) { Places.photonAnswer(answer.data) } ?: run {
            Log.e(placeLog, "${photon.host}: not a search answer")
            throw Failure.Unavailable()
        }
    }

    /** Nominatim's search: within the countries around the map first, where it's in southern Africa (Nominatim isn't
     *  forgiving of spelling and doesn't rank by nearness, so the whole world's namesakes crowd out the place), then
     *  the whole world when that finds nothing to keep (`PlaceSources.nominatimCountryPasses`). */
    private suspend fun searchNominatim(text: String, near: GeoPoint?): List<FoundPlace> {
        val passes = PlaceSources.nominatimCountryPasses(near)
        for ((i, countries) in passes.withIndex()) {
            val places = nominatimOnce(text, near, countries)
            if (places.isNotEmpty() || i == passes.lastIndex) return places
        }
        return emptyList()
    }

    private suspend fun nominatimOnce(text: String, near: GeoPoint?, countries: List<String>?): List<FoundPlace> {
        val url = nominatimSearch.newBuilder()
        for ((name, value) in PlaceSources.nominatimQuery(text, near, countries)) url.addQueryParameter(name, value)
        val data = getNominatim(url.build())
        return withContext(work) { Places.nominatimResults(data) } ?: run {
            Log.e(placeLog, "${nominatimSearch.host}: not a search answer")
            throw Failure.Unavailable()
        }
    }

    /** The area to download for a place: its boundary (simplified to suit its size), or for a spot, the circle around
     *  it. A place that has no boundary to be had falls back to its extent; a failed request is an error (try again). */
    suspend fun area(place: FoundPlace): CoverageShape {
        if (!place.hasBoundary) return Places.area(place, rings = emptyList())
        val url = nominatim.newBuilder()
            .addQueryParameter("osm_ids", place.id)
            .addQueryParameter("format", "geojson")
            .addQueryParameter("polygon_geojson", "1")
            .addQueryParameter("polygon_threshold", stringFormat("%.5f", Places.simplification(place)))
            .build()
        val data = getNominatim(url)
        return withContext(work) {
            val rings = Places.outerRings(data)
            Log.d(placeLog, "boundary of ${place.id}: ${rings.size} rings, ${rings.sumOf { it.size }} points")
            Places.area(place, rings)
        }
    }

    /** A Nominatim request in its turn: at most one a second. */
    private suspend fun getNominatim(url: HttpUrl): ByteArray {
        val wait = sources.nominatimTurn()
        if (wait > 0) delay(wait)
        return get(url, client)
    }

    private suspend fun get(url: HttpUrl, client: OkHttpClient): ByteArray {
        val answer = request(url, client)
        if (answer.code != 200) throw Failure.Unavailable()
        return answer.data
    }

    /** A request's answer, any status. A network error is `Offline` only when the phone has no network (iOS's
     *  notConnectedToInternet and dataNotAllowed); anything else, a name that can't be looked up or a connection the
     *  server dropped included, is that service failing: `Unavailable`. */
    private suspend fun request(url: HttpUrl, client: OkHttpClient): HttpAnswer = try {
        client.newCall(Request.Builder().url(url).build()).answer().also {
            if (it.code != 200) Log.e(placeLog, "${url.host}: HTTP ${it.code}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        if (!isOnline()) throw Failure.Offline()
        Log.e(placeLog, "${url.host}: ${e.message ?: e}")
        throw Failure.Unavailable()
    } catch (e: Exception) {
        Log.e(placeLog, "${url.host}: ${e.message ?: e}")
        throw Failure.Unavailable()
    }
}
