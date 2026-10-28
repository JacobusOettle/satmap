package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerrainTests {
    private fun loadTiles(): Map<TileID, DEMTile> {
        val tiles = HashMap<TileID, DEMTile>()
        for (x in listOf(2390, 2391)) {
            val id = TileID(z = 12, x = x, y = 2342)
            val tile = Terrarium.decode(fixture("terrarium_12_${x}_2342.png"), id = id)
            assertNotNull(tile)
            tiles[id] = tile!!
        }
        return tiles
    }

    @Test
    fun testSamplerPixelCentresAndSeam() {
        val tiles = loadTiles()
        val sampler = DEMSampler(zoom = 12) { tiles[it] }
        val a = tiles.getValue(TileID(z = 12, x = 2390, y = 2342))
        val b = tiles.getValue(TileID(z = 12, x = 2391, y = 2342))
        val scale = (256 shl 12).toDouble()
        // Exactly at a pixel centre the bilinear sample equals the pixel value.
        val px = 2390 * 256 + 128
        val py = 2342 * 256 + 77
        val e = sampler.elevation(worldX = (px.toDouble() + 0.5) / scale, worldY = (py.toDouble() + 0.5) / scale)
        assertNotNull(e)
        assertEquals(a.values[77 * 256 + 128].toDouble(), e!!, 1e-3)
        // Half-way between the last pixel of tile a and the first of tile b: the mean of both.
        val seamX = (2391 * 256).toDouble() / scale
        val s = sampler.elevation(worldX = seamX, worldY = (py.toDouble() + 0.5) / scale)
        assertNotNull(s)
        assertEquals((a.values[77 * 256 + 255] + b.values[77 * 256 + 0]).toDouble() / 2, s!!, 1e-3)

        // Grids of neighbouring map tiles agree on their shared edge.
        val ta = TileID(z = 12, x = 2390, y = 2342).worldBounds
        val tb = TileID(z = 12, x = 2391, y = 2342).worldBounds
        val ga = sampler.grid(minX = ta.minX, minY = ta.minY, maxX = ta.maxX, maxY = ta.maxY, nodesX = 65, nodesY = 65)
        val gb = sampler.grid(minX = tb.minX, minY = tb.minY, maxX = tb.maxX, maxY = tb.maxY, nodesX = 65, nodesY = 65)
        assertNotNull(ga)
        assertNotNull(gb)
        for (j in 0 until 65) assertEquals(gb!![j * 65], ga!![j * 65 + 64], 1e-3f)
    }

    @Test
    fun testMissingTileReturnsNil() {
        val sampler = DEMSampler(zoom = 12) { null }
        assertNull(sampler.elevation(at = GeoPoint(lat = -25.4, lon = 30.1)))
    }
}
