package com.jacobusoettle.satmap.offline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.RoadPreset
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.TileCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val roadLog = "roads"

/** Road sets shipped with the app (assets/<id>.json, from SatMap/Resources), loaded once. */
object RoadPresets {
    const val kruger = "KrugerRoads"
    private val loaded = HashMap<String, RoadPreset>()

    /** Opens a shipped file: the app's assets, from `init`. */
    private var open: ((String) -> InputStream)? = null

    /** Called by `OfflineManager` (made at startup, before anything asks for a road set). */
    fun init(context: Context) {
        val assets = context.applicationContext.assets
        open = { name -> assets.open(name) }
    }

    @Synchronized
    fun load(id: String?): RoadPreset? {
        if (id == null) return null
        loaded[id]?.let { return it }
        val p = try {
            val opened = open ?: throw IOException("no assets")
            opened("$id.json").use { RoadPreset(json = it.readBytes()) }
        } catch (e: Exception) {
            Log.e(roadLog, "road preset $id missing or unreadable")
            return null
        }
        loaded[id] = p
        return p
    }

    /** The shipped road set whose place holds an area (nearly all of it: compared tile by tile at a coarse zoom, as
     *  the boundaries are simplified differently), so its roads can be used there instead of asking the road server
     *  (for all of Kruger, a very large question). */
    fun covering(area: CoverageShape): RoadPreset? {
        val preset = load(kruger) ?: return null
        val z = 14
        val wanted = TileCoverage(shape = area, zoom = z)
        if (wanted.tileCount <= 0) return null
        val outside = wanted.count(TileCoverage(shape = CoverageShape(polygons = preset.boundary), zoom = z))
        return if (outside * 10 <= wanted.tileCount) preset else null
    }
}

/** Roads and tracks in an area from OpenStreetMap, through the public Overpass API (no account needed). */
object RoadFetcher {
    /** The main server, then mirrors for when it is busy (public Overpass servers are often overloaded). The main
     *  server's "busy" errors pass quickly, so it gets a second turn. (A test sets its own.) */
    internal var servers: List<HttpUrl> = listOf(
        "https://overpass-api.de/api/interpreter".toHttpUrl(),
        "https://overpass.private.coffee/api/interpreter".toHttpUrl(),
        "https://overpass-api.de/api/interpreter".toHttpUrl(),
        "https://overpass.kumi.systems/api/interpreter".toHttpUrl(),
    )

    /** The lookup client (User-Agent) with iOS's 60 s request timeout. (A test sets its own.) */
    internal var client: OkHttpClient = DownloadHttp.lookups.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Whether the phone has a network: `Connectivity`'s snapshot (a test sets its own). */
    internal var isOnline: () -> Boolean = { Connectivity.shared.isOnlineNow }

    /** Where answers are read and roads clipped, off the caller's thread (iOS runs `roads(in:)` off the main actor).
     *  (A test sets its own.) */
    internal var work: CoroutineDispatcher = Dispatchers.Default

    /** The Swift `Failure` cases, with its texts. */
    sealed class Failure(message: String) : Exception(message) {
        class Offline : Failure("No internet connection. Roads are looked up online; try again when connected.")

        class Unavailable : Failure("The OpenStreetMap road server is busy or unreachable. Try again in a minute.")
    }

    /** How long to wait for a server before also asking the next one, in ms. */
    private const val nextServerAfter = 12_000L

    private sealed interface Attempt {
        class Roads(val lines: List<List<GeoPoint>>) : Attempt
        class Failed(val offline: Boolean) : Attempt
        data object NextServerDue : Attempt
    }

    /** Road centre lines inside the area (clipped to it). Asks the main server first; if it hasn't answered after
     *  a few seconds, or fails, the next one too, and so on. The first good answer wins and the rest are cancelled. */
    suspend fun roads(area: CoverageShape): List<List<GeoPoint>> {
        val query = Roads.overpassQuery(area) ?: return emptyList()
        // (Bytes, so OkHttp adds no charset to the type.)
        val body = ("data=" + percentEncode(query)).toByteArray(Charsets.UTF_8)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val lines: List<List<GeoPoint>> = coroutineScope {
            val results = Channel<Attempt>(Channel.UNLIMITED)
            val jobs = mutableListOf<Job>()
            var started = 0
            var running = 0
            var offline = false
            var startNext = true
            try {
                while (true) {
                    if (startNext && started < servers.size) {
                        val server = servers[started]
                        started += 1
                        running += 1
                        jobs += launch { results.send(attempt(server, body)) }
                        if (started < servers.size) {
                            jobs += launch {
                                delay(nextServerAfter)
                                results.send(Attempt.NextServerDue)
                            }
                        }
                    }
                    startNext = false
                    if (running <= 0) break
                    when (val result = results.receive()) {
                        is Attempt.Roads -> return@coroutineScope result.lines
                        is Attempt.Failed -> {
                            running -= 1
                            offline = offline || result.offline
                            startNext = true
                        }
                        Attempt.NextServerDue -> startNext = true
                    }
                }
                throw if (offline) Failure.Offline() else Failure.Unavailable()
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
        return withContext(work) { Roads.clip(lines, area) }
    }

    /** Swift's `addingPercentEncoding(withAllowedCharacters: alphanumerics + "-._~")`: everything else as %XX of its
     *  UTF-8 bytes. (Swift's alphanumerics also keep non-ASCII letters; the query is plain ASCII, so it makes no
     *  difference.) */
    internal fun percentEncode(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                out.append(c.toChar())
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return out.toString()
    }

    private suspend fun attempt(server: HttpUrl, body: RequestBody): Attempt {
        val host = server.host
        val start = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(server).post(body).build()
        try {
            val answer = client.newCall(request).answer()
            val ms = SystemClock.elapsedRealtime() - start
            if (answer.code != 200) {
                Log.e(roadLog, "$host: HTTP ${answer.code} after $ms ms")
                return Attempt.Failed(offline = false)
            }
            val lines = try {
                withContext(work) { Roads.parse(answer.data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(roadLog, "$host: unreadable answer (${answer.data.size} bytes) after $ms ms")
                return Attempt.Failed(offline = false)
            }
            Log.i(roadLog, "$host: ${lines.size} roads, ${answer.data.size} bytes in $ms ms")
            return Attempt.Roads(lines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = SystemClock.elapsedRealtime() - start
            Log.e(roadLog, "$host: ${e.message ?: e} after $ms ms")
            // iOS's notConnectedToInternet and dataNotAllowed: no network, or no name lookup.
            return Attempt.Failed(offline = e is IOException && (!isOnline() || e is UnknownHostException))
        }
    }
}
