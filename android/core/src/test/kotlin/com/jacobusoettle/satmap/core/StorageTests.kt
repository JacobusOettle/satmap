package com.jacobusoettle.satmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The MBTiles test of StorageTests in CoreTests.swift (its track log test is in TrackLogTests.kt).
class StorageTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    @Test
    fun testMBTilesRoundTrip() {
        val dir = tempDir()
        val url = File(dir, "t.mbtiles")
        val w = MBTiles(url, create = true)
        for (x in 0 until 300) w.put(TileID(z = 10, x = x, y = 5), data = byteArrayOf((x and 0xFF).toByte(), 1, 2))
        w.putMissing(TileID(z = 11, x = 4, y = 10))
        w.setMetadata(mapOf("name" to "test", "format" to "jpg"))
        w.flush()
        val r = MBTiles(url, create = false)
        assertArrayEquals(byteArrayOf(7, 1, 2), r.tile(TileID(z = 10, x = 7, y = 5)))
        assertNull(r.tile(TileID(z = 10, x = 7, y = 6)))
        assertEquals(setOf(5, 6, 7, 8, 9), r.existingColumns(z = 10, y = 5, xs = 5..9))
        assertEquals(setOf(4), r.missingColumns(z = 11, y = 10, xs = 0..10))
        assertEquals(300L, r.tileCount())
        assertEquals(10, r.maxStoredZoom())
        assertTrue(r.contains(TileID(z = 10, x = 299, y = 5)))
        assertTrue(r.isMissing(TileID(z = 11, x = 4, y = 10)))
        assertFalse(r.isMissing(TileID(z = 11, x = 5, y = 10)))
        w.close()
        r.close()
        assertThrows(Exception::class.java) { MBTiles(File(dir, "nope.mbtiles"), create = false) }
    }

    @Test
    fun testMetadataEndsAtANul() {
        // Swift reads a value as a C string (String(cString:)), which ends at the first NUL.
        val url = File(tempDir(), "t.mbtiles")
        val w = MBTiles(url, create = true)
        w.setMetadata(mapOf("k" to "a\u0000b", OfflineBackup.layerKey to "imag\u0000ery",
                            OfflineBackup.areaKey to "{\"a\":1}\u0000tail"))
        w.close()
        val r = MBTiles(url, create = false)
        assertEquals("a", r.metadata("k"))
        r.close()
        val contents = OfflineBackup.inspect(url)
        assertEquals("imag", contents.layerKey)
        assertEquals("{\"a\":1}", String(contents.areaJSON, Charsets.UTF_8))
    }
}
