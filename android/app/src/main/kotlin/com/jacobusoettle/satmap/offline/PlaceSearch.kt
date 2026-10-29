package com.jacobusoettle.satmap.offline

import android.util.Log
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Places
import com.jacobusoettle.satmap.core.stringFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException

private const val placeLog = "places"

/** Finding a place by name for an offline area: Photon (komoot's OpenStreetMap search, forgiving of spelling) for the
 *  names, then Nominatim (OpenStreetMap's own) for the chosen place's boundary. Both are public services: one search
 *  per tap of Search, one boundary per place chosen. */
object PlaceSearch {
    /** The Swift `Failure` cases, with its texts. */
    sealed class Failure(message: String) : Exception(message) {
        class Offline : Failure("No internet connection. Places are looked up online; try again when connected.")

        class Unavailable : Failure("The place search didn't answer. Try again in a moment.")
    }

    /** The services (a test sets its own). */
    internal var photon: HttpUrl = "https://photon.komoot.io/api/".toHttpUrl()
    internal var nominatim: HttpUrl = "https://nominatim.openstreetmap.org/lookup".toHttpUrl()

    /** 20 s timeouts, with the User-Agent that identifies the app to the OpenStreetMap services, as their usage
     *  policies ask (`DownloadHttp.lookupUserAgent`). (A test sets its own.) */
    internal var client: OkHttpClient = DownloadHttp.lookups

    /** Whether the phone has a network: `Connectivity`'s snapshot (a test sets its own). */
    internal var isOnline: () -> Boolean = { Connectivity.shared.isOnlineNow }

    /** Where answers are read, off the caller's thread (iOS runs these off the main actor). (A test sets its own.) */
    internal var work: CoroutineDispatcher = Dispatchers.Default

    /** Places matching a name, nearest `near` (the map's middle) first; reserves and parks before the rest, the whole
     *  of one before pieces of it. A name with a plural ("Sabi Sands") is also searched without it. */
    suspend fun search(text: String, near: GeoPoint?): List<FoundPlace> {
        val first = photon(text, near)
        val other = Places.singular(text) ?: return Places.merged(listOf(first))
        val second = try {
            photon(other, near)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return Places.merged(listOf(first, second))
    }

    private suspend fun photon(text: String, near: GeoPoint?): List<FoundPlace> {
        val url = photon.newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("limit", "20")
        if (near != null) {
            // Always with a decimal point (core's `stringFormat`), whatever the phone's language.
            url.addQueryParameter("lat", stringFormat("%.4f", near.lat))
            url.addQueryParameter("lon", stringFormat("%.4f", near.lon))
        }
        val data = get(url.build())
        return withContext(work) { Places.photonResults(data) }
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
        val data = get(url)
        return withContext(work) {
            val rings = Places.outerRings(data)
            Log.d(placeLog, "boundary of ${place.id}: ${rings.size} rings, ${rings.sumOf { it.size }} points")
            Places.area(place, rings)
        }
    }

    private suspend fun get(url: HttpUrl): ByteArray {
        val answer = try {
            client.newCall(Request.Builder().url(url).build()).answer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // iOS's notConnectedToInternet, networkConnectionLost and dataNotAllowed: no network, or no name lookup.
            if (!isOnline() || e is UnknownHostException) throw Failure.Offline()
            Log.e(placeLog, "${url.host}: ${e.message ?: e}")
            throw Failure.Unavailable()
        } catch (e: Exception) {
            Log.e(placeLog, "${url.host}: ${e.message ?: e}")
            throw Failure.Unavailable()
        }
        if (answer.code != 200) {
            Log.e(placeLog, "${url.host}: HTTP ${answer.code}")
            throw Failure.Unavailable()
        }
        return answer.data
    }
}
