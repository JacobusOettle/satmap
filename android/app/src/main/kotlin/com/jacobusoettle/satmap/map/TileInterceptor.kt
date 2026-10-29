package com.jacobusoettle.satmap.map

import android.content.Context
import android.util.Log
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.ContourPolicy
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TileID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Android only: MapLibre 13.7.1 has no custom raster source, so the style's sources ask for
// https://satmap.local/<kind>/<generation>/… and this interceptor answers them from TileRepository and
// SatelliteRenderer (DESIGN.md §3.2). Other requests pass through untouched.

/** The three drawn kinds of tile, each a raster source of its own. */
enum class TileKind {
    imagery,
    contours,
    colours;

    /** The URL path segment and the source/layer id prefix. */
    val id: String get() = name
}

/** The URL scheme:
 *  `https://satmap.local/imagery/{gen}/{z}/{x}/{y}`,
 *  `https://satmap.local/contours/{gen}/{scale}/{z}/{x}/{y}` (scale = round(s·100)),
 *  `https://satmap.local/colours/{gen}/{range}/{z}/{x}/{y}` (range = "none" or "{low}_{high}"). */
object TileUrls {
    const val HOST = "satmap.local"
    private val generation = AtomicInteger(0)

    /** A new source generation (from 1), shared by the phone and the car. */
    fun nextGeneration(): Int = generation.incrementAndGet()

    fun imagery(gen: Int): String = "https://$HOST/${TileKind.imagery.id}/$gen/{z}/{x}/{y}"

    fun contours(gen: Int, scale: Double): String =
        "https://$HOST/${TileKind.contours.id}/$gen/${(scale * 100).roundToInt()}/{z}/{x}/{y}"

    fun colours(gen: Int, range: ElevationRange?): String =
        "https://$HOST/${TileKind.colours.id}/$gen/${range?.let { "${it.low}_${it.high}" } ?: "none"}/{z}/{x}/{y}"

    /** The route a tile URL names, or null when it is malformed (another host, an unknown kind, a missing or
     *  extra segment, a number that isn't one, a tile outside its level, or a level beyond the imagery's). */
    fun parse(url: HttpUrl): TileRoute? {
        if (url.host != HOST) return null
        val s = url.pathSegments
        val kind = TileKind.entries.firstOrNull { it.id == s.firstOrNull() } ?: return null
        val count = if (kind == TileKind.imagery) 5 else 6
        if (s.size != count) return null
        val gen = s[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val z = s[count - 3].toIntOrNull() ?: return null
        val x = s[count - 2].toIntOrNull() ?: return null
        val y = s[count - 1].toIntOrNull() ?: return null
        if (z !in 0..TileSources.imageryMaxZoom || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        val tile = TileID(z = z, x = x, y = y)
        return when (kind) {
            TileKind.imagery -> TileRoute.Imagery(gen, tile)
            TileKind.contours -> s[2].toIntOrNull()?.takeIf { it > 0 }?.let { TileRoute.Contours(gen, tile, it / 100.0) }
            TileKind.colours -> if (s[2] == "none") TileRoute.Colours(gen, tile, null) else range(s[2])?.let { TileRoute.Colours(gen, tile, it) }
        }
    }

    /** "{low}_{high}" (each `Double.toString`, so possibly negative or with an exponent). */
    private fun range(s: String): ElevationRange? {
        val parts = s.split('_')
        if (parts.size != 2) return null
        val low = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val high = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return ElevationRange(low, high)
    }
}

/** What one tile request asks for: the source's generation and the tile, and for contours the drawing scale, for
 *  colours the range (null: none chosen yet). */
sealed class TileRoute {
    abstract val gen: Int
    abstract val tile: TileID

    data class Imagery(override val gen: Int, override val tile: TileID) : TileRoute()
    data class Contours(override val gen: Int, override val tile: TileID, val scale: Double) : TileRoute()
    data class Colours(override val gen: Int, override val tile: TileID, val range: ElevationRange?) : TileRoute()
}

/** The answer to MapLibre: a 200 with the tile, or a 204 (no content: MapLibre draws a loaded parent or the
 *  children); `maxAge` asks for it again after that many seconds. Never an empty 200, which MapLibre would count as a
 *  decode error. `lacking`: imagery answered with a stand-in (true) or nothing (false); contours and colours
 *  incomplete (true) or unavailable (false); null when it is the tile's own complete content. */
class TileAnswer(val code: Int, val body: ByteArray, val type: String?, val maxAge: Int?, val lacking: Boolean?) {
    fun response(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "No Content")
        .apply {
            type?.let { header("Content-Type", it) }
            maxAge?.let { header("Cache-Control", "max-age=$it") }
        }
        .body(body.toResponseBody(type?.toMediaType()))
        .build()

    companion object {
        /** A 1×1 transparent PNG: a blank contour tile (a 204 would show the parent's lines instead). */
        val BLANK_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41, 0x54, 0x78, 0xDA.toByte(), 0x63, 0x60, 0x00,
            0x02, 0x00, 0x00, 0x05, 0x00, 0x01, 0xE9.toByte(), 0xFA.toByte(), 0xDC.toByte(), 0xD8.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        /** A 204, asked again after `maxAge` seconds (never, when null). */
        fun empty(maxAge: Int?, lacking: Boolean? = false): TileAnswer = TileAnswer(204, ByteArray(0), null, maxAge, lacking)
    }
}

/** One raster source of one map (its generation): which tiles MapLibre has had answered, and which of them lack their
 *  own content (for `DrawnImagery`'s replacement and `reload`). A source belongs to its map view and goes with it, as
 *  iOS's overlays and their renderers go with the `MKMapView`: the map is held weakly, so a map view destroyed with its
 *  sources still registered (the activity finished, a car screen gone) isn't kept by them. */
class TileLayer(val gen: Int, val kind: TileKind, map: DrawnMap) {
    private val mapRef = WeakReference(map)

    /** The map's side of the imagery (its `DrawnImagery` holds it); null once that map has gone. */
    val map: DrawnMap? get() = mapRef.get()

    val answered: MutableSet<TileID> = ConcurrentHashMap.newKeySet()
    val lacking = ConcurrentHashMap<TileID, Boolean>()

    fun noteAnswered(tile: TileID, lacking: Boolean?) {
        answered.add(tile)
        if (lacking == null) this.lacking.remove(tile) else this.lacking[tile] = lacking
    }
}

/** The live sources by generation, for the interceptor (any thread). */
object TileLayers {
    private val layers = ConcurrentHashMap<Int, TileLayer>()

    operator fun get(gen: Int): TileLayer? = layers[gen]

    /** Registers a source, and forgets those whose map has gone (and their bookkeeping with them). */
    fun add(layer: TileLayer) {
        layers.values.removeAll { it.map == null }
        layers[layer.gen] = layer
    }

    fun remove(gen: Int) {
        layers.remove(gen)
    }
}

/** MapLibre's raster level is `round(zoom + log2(512 / tileSize))`; these tile sizes make it iOS's level (rendering
 *  spec §2.4). */
object TileLevels {
    const val TERRAIN_TILE_SIZE = 182

    /** 256 *pixels* across: `round(256 / max(1, pixelRatio))` dp (98 at 2.625, 85 at 3). */
    fun imageryTileSize(pixelRatio: Float): Int = (256.0 / max(1.0, pixelRatio.toDouble())).roundToInt()

    /** MapLibre's raster level (std::round, half away from zero), clamped to 0…imageryMaxZoom. */
    fun level(zoom: Double, tileSize: Int): Int {
        val v = zoom + log2(512.0 / tileSize)
        return (if (v >= 0) floor(v + 0.5) else ceil(v - 0.5)).toInt().coerceIn(0, TileSources.imageryMaxZoom)
    }

    /** Contour drawing scale, quantised as in the URL (2.625 → 2.63). */
    fun contourScale(pixelRatio: Float): Double = (min(max(1.0, pixelRatio.toDouble()), 3.0) * 100).roundToInt() / 100.0

    /** The first level with a contour interval at this scale (12 at s = 2, 13 at 2.63 and 3). */
    fun contourMinZoom(s: Double): Int =
        (ContourPolicy.minTileZoom..TileSources.imageryMaxZoom).first { ContourPolicy.intervals(ContourPolicy.pointZoom(it, s)) != null }
}

/** Answers MapLibre's requests for satmap.local on OkHttp's dispatcher threads. `answer` is a parameter so the
 *  interceptor can be tested with a fake renderer.
 *
 *  Every answer is a 200 or a 204 (DESIGN.md D19): the only exception MapLibre sees is `IOException("Canceled")`,
 *  for a request it cancelled itself. Anything else that goes wrong becomes a 204 asked again in 30 s, as a tile
 *  that is unavailable for now: any `Throwable`, so an `Error` too (running out of memory while decoding or cropping
 *  a stand-in, a stack overflow). OkHttp rethrows anything that isn't an `IOException` on its dispatcher thread, and
 *  that ends the process. */
class TileInterceptor(
    private val answer: suspend (TileRoute, TileLayer) -> TileAnswer = SatelliteRenderer::answer,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TileUrls.HOST) return chain.proceed(request)
        val call = chain.call()
        var asked: Pair<TileLayer, TileID>? = null
        val a = try {
            val route = TileUrls.parse(request.url) ?: return TileAnswer.empty(maxAge = null).response(request)
            val layer = TileLayers[route.gen] ?: return TileAnswer.empty(maxAge = null).response(request) // source removed
            asked = Pair(layer, route.tile)
            runBlocking { awaitUnlessCanceled(call) { answer(route, layer) } } ?: throw IOException("Canceled")
        } catch (e: Throwable) {
            // Cancelled by MapLibre (a coroutine CancellationException included): the one failure it is told of.
            // Anything else, an Error included, is a tile unavailable for now.
            if (call.isCanceled()) throw e as? IOException ?: IOException("Canceled", e)
            Log.w("tiles", "Tile request failed, answered with nothing for 30 s: ${request.url.encodedPath}", e)
            TileAnswer.empty(maxAge = 30, lacking = false)
        }
        asked?.let { (layer, tile) -> layer.noteAnswered(tile, a.lacking) }
        return a.response(request)
    }

    companion object {
        /** MapLibre's client (`HttpRequestUtil.setOkHttpClient`): every request goes through the interceptor. */
        val mapClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 }) // MapLibre's own default is 20 per host
                .addInterceptor(TileInterceptor())
                .build()
        }

        /** TileRepository's client for the tile servers: a 1 GiB disk cache honouring the servers' caching headers
         *  (iOS's URLCache with the protocol cache policy), 15 s timeouts, 10 pooled connections. */
        fun browseClient(context: Context): OkHttpClient = OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "tiles-http"), 1L shl 30))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "SatMap/1.0 (Android)").build()) }
            .build()
    }
}

/** Waits for the answer, checking every 50 ms whether MapLibre cancelled the call (then null). Loads into memory go on
 *  in `TileRepository.scope` regardless, as iOS's detached loads do. */
suspend fun <T> awaitUnlessCanceled(call: Call, block: suspend () -> T): T? = coroutineScope {
    if (call.isCanceled()) return@coroutineScope null
    val answer = async { block() }
    while (!answer.isCompleted) {
        withTimeoutOrNull(50) { answer.join() }
        if (!answer.isCompleted && call.isCanceled()) {
            answer.cancel()
            return@coroutineScope null
        }
    }
    answer.await()
}
