package com.jacobusoettle.satmap.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WebMercator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater

/** `TileRepository` with a fake network, fake offline areas and its own clock: where imagery comes from (rendering spec
 *  §1.3), the network pause (§1.5), and the elevation tiles. */
@RunWith(AndroidJUnit4::class)
class TileRepositoryTest {
    private var online = true
    private var clock = 1000.0

    /** What the server answers, by tile; anything else is `otherwise`. */
    private val imagery = ConcurrentHashMap<TileID, TileFetchResult>()
    private val terrain = ConcurrentHashMap<TileID, TileFetchResult>()
    private var otherwise: TileFetchResult = TileFetchResult.Missing

    /** The tiles asked of the server, in order. */
    private val asked: MutableList<TileID> = Collections.synchronizedList(mutableListOf())
    private val opened: MutableList<File> = Collections.synchronizedList(mutableListOf())

    private val imageryURL = Regex("/tile/(\\d+)/(\\d+)/(\\d+)")
    private val terrainURL = Regex("/terrarium/(\\d+)/(\\d+)/(\\d+)\\.png")

    private fun fetch(url: String): TileFetchResult {
        imageryURL.find(url)?.destructured?.let { (z, y, x) ->
            val t = TileID(z.toInt(), x.toInt(), y.toInt())
            asked.add(t)
            return imagery[t] ?: otherwise
        }
        val (z, x, y) = terrainURL.find(url)!!.destructured
        val t = TileID(z.toInt(), x.toInt(), y.toInt())
        asked.add(t)
        return terrain[t] ?: otherwise
    }

    private class FakeStore(val tiles: Map<TileID, ByteArray> = emptyMap(), val missing: Set<TileID> = emptySet()) :
        TileRepository.OfflineStore {
        var closed = false
        override fun tile(t: TileID) = tiles[t]
        override fun isMissing(t: TileID) = t in missing
        override fun contains(t: TileID) = t in tiles
        override fun close() {
            closed = true
        }
    }

    private val area = File("/offline/area")

    /** `phoneClock`: the repository's own clock (Robolectric's `SystemClock`), instead of `clock`. */
    private fun repository(store: FakeStore? = null, terrainStore: FakeStore? = null, phoneClock: Boolean = false): TileRepository {
        val openStore = { f: File ->
            opened.add(f)
            when (f.name) {
                "imagery.mbtiles" -> store
                "terrain.mbtiles" -> terrainStore
                else -> null
            }
        }
        val r = if (phoneClock) {
            TileRepository(browseClient = OkHttpClient(), fetchURL = { fetch(it) }, isOnlineNow = { online }, openStore = openStore)
        } else {
            TileRepository(browseClient = OkHttpClient(), fetchURL = { fetch(it) }, isOnlineNow = { online }, now = { clock }, openStore = openStore)
        }
        r.setAreas(listOf(TileRepository.AreaRef(UUID.randomUUID(), area, 0.0, 0.0, 1.0, 1.0)))
        return r
    }

    /** A spot on the Kruger farm (the screenshots' place) at z18, and its ancestors. */
    private val t = TileID.containing(GeoPoint(-24.99, 31.59), 18)
    private val a1 = t.ancestor(1)
    private val a2 = t.ancestor(2)
    private val a3 = t.ancestor(3)

    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte())

    @Test
    fun theTilesOwnImageryFromTheNetwork() = runBlocking {
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val r = repository().nearestImagery(t)!!
        assertEquals(t, r.tile)
        assertFalse(r.beyondDepth)
        assertArrayEquals(jpeg(1), r.data)
        assertEquals(listOf(t), asked)
    }

    @Test
    fun anOfflineAreaFirst() = runBlocking {
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val r = repository(FakeStore(tiles = mapOf(t to jpeg(2)))).nearestImagery(t)!!
        assertEquals(t, r.tile)
        assertArrayEquals(jpeg(2), r.data)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun beyondTheImagerysDepthTheNearestAncestorThatExists() = runBlocking {
        imagery[t] = TileFetchResult.Missing
        imagery[a1] = TileFetchResult.Missing
        imagery[a2] = TileFetchResult.Data(jpeg(2))
        val repository = repository()
        val r = repository.nearestImagery(t)!!
        assertEquals(a2, r.tile)
        assertTrue(r.beyondDepth)
        assertArrayEquals(jpeg(2), r.data)
        assertEquals(listOf(t, a1, a2), asked)
        // What the server doesn't have is remembered: only the ancestor is asked for again.
        assertEquals(a2, repository.nearestImagery(t)!!.tile)
        assertEquals(listOf(t, a1, a2, a2), asked)
    }

    @Test
    fun aTileThatFailedGetsOnlyOfflineAncestors() = runBlocking {
        imagery[t] = TileFetchResult.Failed
        imagery[a1] = TileFetchResult.Data(jpeg(1)) // there, but not asked for: no blurry stand-in from the network
        val r = repository(FakeStore(tiles = mapOf(a3 to jpeg(3)))).nearestImagery(t)!!
        assertEquals(a3, r.tile)
        assertFalse(r.beyondDepth)
        assertArrayEquals(jpeg(3), r.data)
        assertEquals(listOf(t), asked)
    }

    @Test
    fun offlineAncestorsOnlyWithoutANetwork() = runBlocking {
        online = false
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val repository = repository(FakeStore(tiles = mapOf(a2 to jpeg(2))))
        val r = repository.nearestImagery(t)!!
        assertEquals(a2, r.tile)
        assertFalse(r.beyondDepth)
        assertNull(repository.nearestImagery(TileID(18, 0, 0)))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun anAreaThatKnowsTheTileIsMissingSkipsTheNetwork() = runBlocking {
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val r = repository(FakeStore(tiles = mapOf(a2 to jpeg(2)), missing = setOf(a1))).nearestImagery(t)!!
        assertEquals(a2, r.tile)
        assertTrue(r.beyondDepth)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aStoredAncestorEndsTheWalkForMissingOnes() = runBlocking {
        // a1 is stored, so a2 recorded as missing (a deeper download's leftover) doesn't say t is beyond the depth.
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val r = repository(FakeStore(tiles = mapOf(a1 to jpeg(2)), missing = setOf(a2))).nearestImagery(t)!!
        assertEquals(t, r.tile)
        assertEquals(listOf(t), asked)
    }

    @Test
    fun eightFailuresPauseTheNetworkFor45Seconds() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository()
        for (i in 0 until 8) assertNull(repository.nearestImagery(TileID(18, i, 0)))
        assertEquals(8, asked.size)
        // Paused: nothing is asked.
        assertNull(repository.nearestImagery(TileID(18, 8, 0)))
        assertEquals(8, asked.size)
        clock += 44.9
        assertNull(repository.nearestImagery(TileID(18, 9, 0)))
        assertEquals(8, asked.size)
        clock += 0.2
        imagery[t] = TileFetchResult.Data(jpeg(1))
        assertEquals(t, repository.nearestImagery(t)!!.tile)
        assertEquals(9, asked.size)
    }

    @Test
    fun failuresInTheBackgroundDontPauseTheNetwork() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository()
        for (i in 0 until 10) assertNull(repository.imageryInBackground(TileID(18, i, 0)))
        imagery[t] = TileFetchResult.Data(jpeg(1))
        assertEquals(t, repository.nearestImagery(t)!!.tile)
        assertEquals(11, asked.size)
    }

    @Test
    fun aSuccessResetsTheFailureCount() = runBlocking {
        otherwise = TileFetchResult.Failed
        imagery[t] = TileFetchResult.Data(jpeg(1))
        val repository = repository()
        for (i in 0 until 7) repository.nearestImagery(TileID(18, i, 0))
        repository.nearestImagery(t)
        for (i in 7 until 14) repository.nearestImagery(TileID(18, i, 0))
        // 7, a success, 7: not paused.
        assertEquals(t, repository.nearestImagery(t)!!.tile)
        assertEquals(16, asked.size)
    }

    @Test
    fun resetFailureStateEndsThePause() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository()
        for (i in 0 until 8) repository.nearestImagery(TileID(18, i, 0))
        repository.resetFailureState()
        imagery[t] = TileFetchResult.Data(jpeg(1))
        assertEquals(t, repository.nearestImagery(t)!!.tile)
    }

    /** The pause, like the elevation gates and the area files' retry, is on a clock that goes on while the phone
     *  sleeps, as iOS's `Date()` does: begun just before the screen went off, it is over 45 s later however much of
     *  that the phone slept. */
    @Test
    fun thePauseGoesOnWhileThePhoneSleeps() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository(phoneClock = true)
        for (i in 0 until 8) assertNull(repository.nearestImagery(TileID(18, i, 0)))
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(44))
        assertNull(repository.nearestImagery(TileID(18, 8, 0)))
        assertEquals(8, asked.size) // still paused
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(2))
        imagery[t] = TileFetchResult.Data(jpeg(1))
        assertEquals(t, repository.nearestImagery(t)!!.tile)
        assertEquals(9, asked.size)
    }

    @Test
    fun imageryInBackgroundIsOnlyTheTileItself() = runBlocking {
        imagery[t] = TileFetchResult.Missing
        imagery[a1] = TileFetchResult.Data(jpeg(1))
        val repository = repository(FakeStore(tiles = mapOf(a2 to jpeg(2)), missing = setOf(TileID(18, 5, 5))))
        assertNull(repository.imageryInBackground(t))
        assertArrayEquals(jpeg(2), repository.imageryInBackground(a2))
        assertNull(repository.imageryInBackground(TileID(18, 5, 5))) // known missing: not asked
        assertEquals(listOf(t), asked)
    }

    @Test
    fun probeRecoveryAsksAgainForWhatFailed() = runBlocking {
        imagery[t] = TileFetchResult.Failed
        val repository = repository()
        assertFalse(repository.probeRecovery()) // nothing failed yet
        repository.nearestImagery(t)
        assertFalse(repository.probeRecovery()) // still failing
        assertEquals(listOf(t, t), asked)
        imagery[t] = TileFetchResult.Data(jpeg(1))
        assertTrue(repository.probeRecovery())
        assertFalse(repository.probeRecovery()) // nothing needs a retry any more
        assertEquals(listOf(t, t, t), asked)
    }

    @Test
    fun probeRecoveryCountsASuccessSinceTheFailure() = runBlocking {
        imagery[t] = TileFetchResult.Failed
        imagery[a1] = TileFetchResult.Data(jpeg(1))
        val repository = repository()
        repository.nearestImagery(t)
        clock += 1
        repository.nearestImagery(a1)
        assertTrue(repository.probeRecovery())
        assertEquals(listOf(t, a1), asked)
    }

    @Test
    fun noProbeWhileOffline() = runBlocking {
        imagery[t] = TileFetchResult.Failed
        val repository = repository()
        repository.nearestImagery(t)
        online = false
        assertFalse(repository.probeRecovery())
        assertEquals(listOf(t), asked)
    }

    @Test
    fun anAreasFileIsOpenedAtMostEvery10SecondsUntilItIsThere() = runBlocking {
        val repository = repository(store = null)
        repository.offlineTile(t, TileRepository.Layer.imagery)
        repository.offlineTile(t, TileRepository.Layer.imagery)
        clock += 9.9
        repository.offlineTile(t, TileRepository.Layer.imagery)
        assertEquals(listOf(File(area, "imagery.mbtiles")), opened)
        clock += 0.2
        repository.offlineTile(t, TileRepository.Layer.imagery)
        assertEquals(2, opened.size)
    }

    @Test
    fun anAreasFileIsTriedAgainAfter10SecondsOfSleep() = runBlocking {
        val repository = repository(store = null, phoneClock = true)
        repository.offlineTile(t, TileRepository.Layer.imagery)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(9))
        repository.offlineTile(t, TileRepository.Layer.imagery)
        assertEquals(1, opened.size)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(1))
        repository.offlineTile(t, TileRepository.Layer.imagery)
        assertEquals(2, opened.size)
    }

    @Test
    fun closingAnAreaOrTheBackgroundClosesItsReaders() = runBlocking {
        val store = FakeStore(tiles = mapOf(t to jpeg(1)))
        val repository = repository(store)
        assertNotNull(repository.offlineTile(t, TileRepository.Layer.imagery))
        repository.trimForBackground()
        assertTrue(store.closed)
        store.closed = false
        assertNotNull(repository.offlineTile(t, TileRepository.Layer.imagery)) // opened again
        assertEquals(2, opened.size)
        repository.setAreas(emptyList())
        assertTrue(store.closed)
        assertNull(repository.offlineTile(t, TileRepository.Layer.imagery))
    }

    @Test
    fun anAreaOnlyServesTilesInsideIt() = runBlocking {
        val store = FakeStore(tiles = mapOf(t to jpeg(1)))
        val repository = repository(store)
        val b = t.worldBounds
        val id = UUID.randomUUID()
        repository.setAreas(listOf(TileRepository.AreaRef(id, area, b.maxX, b.minY, b.maxX + 0.1, b.maxY)))
        assertNull(repository.offlineTile(t, TileRepository.Layer.imagery)) // only touches it
        repository.setAreas(listOf(TileRepository.AreaRef(id, area, b.minX, b.minY, b.maxX, b.maxY)))
        assertNotNull(repository.offlineTile(t, TileRepository.Layer.imagery))
        store.closed = false
        repository.closeArea(id)
        assertTrue(store.closed)
        assertNull(repository.offlineTile(t, TileRepository.Layer.imagery))
    }

    // Elevation

    /** The middle of a z15 tile, so every zoom's sampler needs one tile. */
    private val spot = WebMercator.geo((18_901 + 0.5) / 32768, (18_501 + 0.5) / 32768)
    private val dem15 = TileID(15, 18_901, 18_501)

    @Test
    fun elevationFromTheFinestTerrainTile() = runBlocking {
        terrain[dem15] = TileFetchResult.Data(terrariumPng(1000.0))
        val repository = repository()
        assertEquals(1000.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(listOf(dem15), asked)
        // Decoded in memory: not asked again.
        assertEquals(1000.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(listOf(dem15), asked)
        repository.trimMemory()
        assertEquals(1000.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(listOf(dem15, dem15), asked)
    }

    @Test
    fun elevationFromAnOfflineAreasTerrain() = runBlocking {
        val repository = repository(terrainStore = FakeStore(tiles = mapOf(dem15 to terrariumPng(412.5))))
        assertEquals(412.5, repository.elevation(spot)!!, 1e-9)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun elevationTilesThatFailedWait30Seconds() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository()
        assertNull(repository.elevation(spot))
        assertEquals((10..15).map { dem15.ancestor(15 - it) }.reversed(), asked) // z15 down to z10
        assertNull(repository.elevation(spot))
        assertEquals(6, asked.size)
        clock += 30
        terrain[dem15] = TileFetchResult.Data(terrariumPng(7.0))
        assertEquals(7.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(7, asked.size)
    }

    @Test
    fun elevationTilesThatFailedWait30SecondsAsleepOrAwake() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository(phoneClock = true)
        assertNull(repository.elevation(spot))
        assertEquals(6, asked.size)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(29))
        assertNull(repository.elevation(spot))
        assertEquals(6, asked.size)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(1))
        terrain[dem15] = TileFetchResult.Data(terrariumPng(7.0))
        assertEquals(7.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(7, asked.size)
    }

    @Test
    fun elevationTilesMissingOnTheServerWait600Seconds() = runBlocking {
        terrain[dem15] = TileFetchResult.Missing
        terrain[dem15.ancestor(1)] = TileFetchResult.Data(terrariumPng(20.0))
        val repository = repository()
        assertEquals(20.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(listOf(dem15, dem15.ancestor(1)), asked)
        clock += 599
        terrain[dem15] = TileFetchResult.Data(terrariumPng(30.0))
        assertEquals(20.0, repository.elevation(spot)!!, 1e-9) // z14 from memory, z15 not asked yet
        assertEquals(2, asked.size)
        clock += 1
        assertEquals(30.0, repository.elevation(spot)!!, 1e-9)
        assertEquals(3, asked.size)
    }

    @Test
    fun resetFailureStateForgetsTheElevationGates() = runBlocking {
        otherwise = TileFetchResult.Failed
        val repository = repository()
        assertNull(repository.elevation(spot))
        repository.resetFailureState()
        terrain[dem15] = TileFetchResult.Data(terrariumPng(5.0))
        assertEquals(5.0, repository.elevation(spot)!!, 1e-9)
    }

    @Test
    fun demTilesSaysWhetherItHasThemAll() = runBlocking {
        terrain[dem15] = TileFetchResult.Data(terrariumPng(1.0))
        val repository = repository()
        val b = dem15.worldBounds
        // The whole tile and half a pixel around it: its eight neighbours too, which the server doesn't have.
        val (tiles, complete) = repository.demTiles(15, b.minX, b.minY, b.maxX, b.maxY)
        assertEquals(setOf(dem15), tiles.keys)
        assertFalse(complete)
        assertEquals(9, asked.size)
        val (inside, all) = repository.demTiles(15, b.minX + 0.3 / 32768, b.minY + 0.3 / 32768, b.maxX - 0.3 / 32768, b.maxY - 0.3 / 32768)
        assertEquals(setOf(dem15), inside.keys)
        assertTrue(all)
    }

    /** A Terrarium PNG (RGB, 8 bits) of one height everywhere. */
    private fun terrariumPng(metres: Double, size: Int = 256): ByteArray {
        val v = metres + 32768
        val r = (v / 256).toInt()
        val g = (v - r * 256).toInt()
        val b = ((v - r * 256 - g) * 256).toInt()
        val raw = ByteArrayOutputStream()
        repeat(size) {
            raw.write(0) // no filter
            repeat(size) {
                raw.write(r)
                raw.write(g)
                raw.write(b)
            }
        }
        val deflater = Deflater()
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
        chunk("IHDR", int(size) + int(size) + byteArrayOf(8, 2, 0, 0, 0))
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
