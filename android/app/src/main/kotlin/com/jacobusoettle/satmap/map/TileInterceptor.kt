package com.jacobusoettle.satmap.map

import android.content.Context
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.ContourPolicy
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TileID
import kotlinx.coroutines.runBlocking
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

    /** The route a tile URL names, or null when it is malformed. */
    fun parse(url: HttpUrl): TileRoute? = null // M1: the inverse of the templates above.
}

/** What one tile request asks for. */
sealed class TileRoute(val gen: Int, val tile: TileID) {
    class Imagery(gen: Int, tile: TileID) : TileRoute(gen, tile)
    class Contours(gen: Int, tile: TileID, val scale: Double) : TileRoute(gen, tile)
    class Colours(gen: Int, tile: TileID, val range: ElevationRange?) : TileRoute(gen, tile)
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
 *  own content (for `DrawnImagery`'s replacement and `reload`). */
class TileLayer(val gen: Int, val kind: TileKind, val map: DrawnMap) {
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

    fun add(layer: TileLayer) {
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
 *  interceptor can be tested with a fake renderer. */
class TileInterceptor(
    private val answer: suspend (TileRoute, TileLayer) -> TileAnswer = SatelliteRenderer::answer,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TileUrls.HOST) return chain.proceed(request)
        val route = TileUrls.parse(request.url) ?: return TileAnswer.empty(maxAge = null).response(request)
        val layer = TileLayers[route.gen] ?: return TileAnswer.empty(maxAge = null).response(request) // source removed
        val a = runBlocking { awaitUnlessCanceled(chain.call()) { answer(route, layer) } } ?: throw IOException("Canceled")
        layer.noteAnswered(route.tile, a.lacking)
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
suspend fun <T> awaitUnlessCanceled(call: Call, block: suspend () -> T): T? {
    if (call.isCanceled()) return null
    return block() // M1: polls call.isCanceled() every 50 ms while block runs.
}
