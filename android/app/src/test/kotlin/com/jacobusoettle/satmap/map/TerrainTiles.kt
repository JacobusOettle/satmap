package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.floor

/** Elevation for the contour and colour tests: Terrarium PNGs made from a height function, and a fake server for
 *  them behind a `TileRepository` with its own clock. */
internal class TerrainTiles {
    /** What the server answers, by elevation tile; anything else is missing. */
    val server = ConcurrentHashMap<TileID, TileFetchResult>()

    /** Elevation tiles asked of the server. */
    val fetched = AtomicInteger()

    /** Seconds on the repository's and the renderer's gates. */
    @Volatile
    var clock = 5000.0

    private val terrainURL = Regex("/terrarium/(\\d+)/(\\d+)/(\\d+)\\.png")

    /** Installs a repository on this server as `TileRepository.shared`, and the renderer on this clock. */
    fun install() {
        TileRepository.shared = TileRepository(
            browseClient = OkHttpClient(),
            fetchURL = { url ->
                val (z, x, y) = terrainURL.find(url)?.destructured ?: return@TileRepository TileFetchResult.Missing
                fetched.incrementAndGet()
                server[TileID(z.toInt(), x.toInt(), y.toInt())] ?: TileFetchResult.Missing
            },
            isOnlineNow = { true },
            now = { clock },
            openStore = { null },
        )
        SatelliteRenderer.resetForTests()
        SatelliteRenderer.now = { clock }
    }

    /** The elevation tiles at `t`'s level (15 at most, as `ContourPolicy.demZoom`) a tile's contours and colours are
     *  drawn from: its own and the eight around it (half a pixel beyond its edges). */
    fun around(t: TileID): List<TileID> {
        require(t.z == 15)
        return (-1..1).flatMap { dy -> (-1..1).map { dx -> TileID(t.z, t.x + dx, t.y + dy) } }
    }

    /** Puts these tiles on the server with heights from `height(column, row)` (global pixels at their zoom). */
    fun serve(tiles: List<TileID>, height: (Int, Int) -> Double) {
        for (d in tiles) server[d] = TileFetchResult.Data(png(d, height))
    }

    companion object {
        /** A Terrarium PNG (RGB, 8 bits) of a tile, each pixel's height from `height(column, row)` in global pixels. */
        fun png(t: TileID, height: (Int, Int) -> Double): ByteArray {
            val raw = ByteArrayOutputStream()
            for (j in 0 until 256) {
                raw.write(0) // no filter
                for (i in 0 until 256) {
                    val v = height(t.x * 256 + i, t.y * 256 + j) + 32768
                    val r = floor(v / 256).toInt()
                    val g = floor(v - r * 256).toInt()
                    val b = floor((v - r * 256 - g) * 256).toInt()
                    raw.write(r)
                    raw.write(g)
                    raw.write(b)
                }
            }
            val deflater = Deflater(Deflater.BEST_SPEED)
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val idat = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            fun chunk(type: String, data: ByteArray) {
                out.write(int(data.size))
                val body = type.toByteArray() + data
                out.write(body)
                out.write(int(CRC32().apply { update(body) }.value.toInt()))
            }
            chunk("IHDR", int(256) + int(256) + byteArrayOf(8, 2, 0, 0, 0))
            chunk("IDAT", idat.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
    }
}
