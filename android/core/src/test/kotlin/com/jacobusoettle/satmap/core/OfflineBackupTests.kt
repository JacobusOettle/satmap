package com.jacobusoettle.satmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineBackupTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    private val layers = listOf(OfflineBackup.Layer(fileName = "imagery.mbtiles", key = "imagery", label = "satellite"),
                                OfflineBackup.Layer(fileName = "terrain.mbtiles", key = "terrain", label = "elevation"))

    @Test
    fun testExportedFilesAreCompleteOnTheirOwnAndCarryTheArea() {
        val area = tempDir()
        val writer = MBTiles(File(area, "imagery.mbtiles"), create = true)
        val tiles = (0 until 300).map { TileID(z = 18, x = 1000 + it, y = 2000) }
        for (t in tiles) assertTrue(writer.put(t, data = "$t".toByteArray()))
        assertTrue(writer.flush()) // committed to the write-ahead log, not yet to the file itself
        val reader = MBTiles(File(area, "imagery.mbtiles"), create = false)
        assertNotNull(reader.tile(tiles[0])) // the map has the file open too

        val out = File(tempDir(), "Export")
        val used = mutableSetOf<String>()
        val files = OfflineBackup.export(areaFolder = area, layers = layers, name = "Farm: north/south",
                                         areaJSON = """{"name":"Farm"}""", into = out, usedNames = used)
        // No elevation downloaded yet: only the satellite file.
        assertEquals(listOf("Farm- north-south - satellite.mbtiles"), files.map { it.name })

        // Copy just the file (as Files or AirDrop would) and open it elsewhere: every tile is there.
        val copy = File(tempDir(), "copy.mbtiles")
        files[0].copyTo(copy)
        val restored = OfflineBackup.inspect(copy)
        assertEquals("imagery", restored.layerKey)
        assertEquals("""{"name":"Farm"}""", String(restored.areaJSON, Charsets.UTF_8))
        val opened = MBTiles(copy, create = false)
        assertEquals(tiles.size.toLong(), opened.tileCount())
        assertArrayEquals("${tiles[299]}".toByteArray(), opened.tile(tiles[299]))

        // A second area with the same name gets its own file names.
        val files2 = OfflineBackup.export(areaFolder = area, layers = layers, name = "Farm: north/south",
                                          areaJSON = "{}", into = out, usedNames = used)
        assertEquals(listOf("Farm- north-south (2) - satellite.mbtiles"), files2.map { it.name })
        reader.close()
        writer.close()
    }

    @Test
    fun testOtherMBTilesAreNotBackups() {
        val url = File(tempDir(), "other.mbtiles")
        val store = MBTiles(url, create = true)
        store.setMetadata(mapOf("name" to "Somebody's map"))
        store.close()
        assertThrows(Exception::class.java) { OfflineBackup.inspect(url) }
    }

    @Test
    fun testFileSafeNames() {
        assertEquals("Block A1 - B2-", OfflineBackup.fileSafe("  ..Block A1 / B2?  "))
        assertEquals("---", OfflineBackup.fileSafe("///"))
        assertEquals("Offline area", OfflineBackup.fileSafe("  "))
    }
}
