package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline map backups move between the iPhone and Android apps: both write and read the same files. These tests
 *  pin the format on the Android side and read backups made by both apps (Fixtures/backup/iphone and
 *  Fixtures/backup/android; the Swift tests read the same files). The expected area is `BackupFixture.area`, in the Swift tests too
 *  (Tests/SatMapCoreTests/BackupCompatibilityTests.swift). */
class BackupCompatibilityTests {
    init {
        MBTiles.driver = JdbcSqlDriver
    }

    @Test
    fun testAreaJSONIsTheSharedFormat() {
        val json = BackupFixture.area.toJson().toString()
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "name", "created", "shape", "roads", "maxZoom", "allowCellular", "status",
                           "failedTiles", "bytesOnDisk"), obj.keys) // preset, isTrack and problem not set: left out
        assertEquals("6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", obj["id"]?.jsonPrimitive?.contentOrNull) // in capitals
        // Seconds since 1 January 2001 (Swift's own date encoding), not since 1970.
        assertEquals((1_761_696_000 - 978_307_200).toDouble(), obj.getValue("created").jsonPrimitive.double, 1e-6)
        assertEquals("complete", obj["status"]?.jsonPrimitive?.contentOrNull)
        // A whole number (Swift reads no other kind into an Int).
        assertEquals(2_345_678_901L, obj["bytesOnDisk"]?.jsonPrimitive?.contentOrNull?.toLongOrNull())
        val shape = obj.getValue("shape").jsonObject
        assertEquals(setOf("polygons", "lines", "points", "bufferMeters"), shape.keys)
        val ring = shape.getValue("polygons").jsonArray.first().jsonArray
        assertEquals(mapOf("lat" to -33.9245, "lon" to 18.9232), ring.first().jsonObject.mapValues { it.value.jsonPrimitive.double })
        // Numbers as Swift's JSONEncoder writes them: whole ones without ".0".
        assertTrue(json, json.contains(""""created":783388800,""") && json.contains(""""bufferMeters":150}"""))
    }

    @Test
    fun testAreaJSONReadsNumbersOnlyAsSwiftDoes() {
        // Swift's JSONDecoder refuses a date written as a string, or one a Double can't hold.
        val obj = BackupFixture.area.toJson()
        assertEquals(BackupFixture.area, OfflineArea.fromJson(Json.parseToJsonElement(obj.toString())))
        assertThrows(Exception::class.java) { OfflineArea.fromJson(JsonObject(obj + ("created" to JsonPrimitive("783388800")))) }
        val huge = Json.parseToJsonElement(obj.toString().replace(""""created":783388800""", """"created":1e400"""))
        assertThrows(Exception::class.java) { OfflineArea.fromJson(huge) }
    }

    @Test
    fun testReadsBackupsMadeOnTheIPhone() {
        checkBackup(folder = "iphone")
    }

    @Test
    fun testReadsBackupsMadeOnAndroid() {
        checkBackup(folder = "android")
    }

    /** Writes the Android fixture with the app's own export (run with SATMAP_WRITE_BACKUP_FIXTURES=<folder>): the
     *  Swift test of the same name writes the iPhone one. */
    @Test
    fun testWriteAndroidBackupFixture() {
        val out = System.getenv("SATMAP_WRITE_BACKUP_FIXTURES")
        assumeTrue("Writes the fixture only when asked", out != null)
        val area = File(System.getProperty("java.io.tmpdir"), "backup-fixture-" + UUID.randomUUID())
        assertTrue(area.mkdirs())
        try {
            val imagery = MBTiles(File(area, "imagery.mbtiles"), create = true)
            for ((t, data) in BackupFixture.imageryTiles) assertTrue(imagery.put(t, data = data))
            assertTrue(imagery.putMissing(BackupFixture.missingImagery))
            assertTrue(imagery.flush())
            imagery.close()
            val terrain = MBTiles(File(area, "terrain.mbtiles"), create = true)
            assertTrue(terrain.put(BackupFixture.terrainTile, data = BackupFixture.terrainData))
            assertTrue(terrain.flush())
            terrain.close()
            val used = mutableSetOf<String>()
            val json = BackupFixture.area.toJson().toString()
            val files = OfflineBackup.export(areaFolder = area, layers = BackupFixture.layers, name = BackupFixture.area.name,
                                             areaJSON = json, into = File(out!!), usedNames = used)
            assertEquals(listOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles"), files.map { it.name })
        } finally {
            area.deleteRecursively()
        }
    }

    /** Restores a backup as the app does (`OfflineBackup.inspect`, then the area's JSON) and checks every part. */
    private fun checkBackup(folder: String) {
        val dir = File(System.getProperty("satmap.fixtures"), "backup/$folder")
        val layers = HashMap<String, File>()
        for (name in listOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles")) {
            // A copy, as the app restores (a store may write to its file when opened).
            val source = File(dir, name)
            assertFalse("$folder: $name left a write-ahead log", File(source.path + "-wal").exists())
            val copy = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString() + ".mbtiles")
            source.copyTo(copy)
            try {
                val info = OfflineBackup.inspect(copy)
                val area = OfflineArea.fromJson(Json.parseToJsonElement(String(info.areaJSON, Charsets.UTF_8)))
                assertEquals("$folder: $name", BackupFixture.area, area)
                layers[info.layerKey] = source
                val store = MBTiles(copy, create = false)
                try {
                    when (info.layerKey) {
                        "imagery" -> {
                            for ((t, data) in BackupFixture.imageryTiles) assertArrayEquals("$folder: $t", data, store.tile(t))
                            assertTrue(folder, store.isMissing(BackupFixture.missingImagery))
                            assertEquals(folder, BackupFixture.imageryTiles.size.toLong(), store.tileCount())
                        }
                        "terrain" -> assertArrayEquals(folder, BackupFixture.terrainData, store.tile(BackupFixture.terrainTile))
                        else -> fail("$folder: unknown layer ${info.layerKey}")
                    }
                } finally {
                    store.close()
                }
            } finally {
                copy.delete()
            }
        }
        assertEquals(folder, setOf("imagery", "terrain"), layers.keys)
    }
}

/** The backup both apps' tests expect (Tests/SatMapCoreTests/BackupCompatibilityTests.swift has the same values). */
object BackupFixture {
    val layers = listOf(
        OfflineBackup.Layer(fileName = "imagery.mbtiles", key = "imagery", label = "satellite"),
        OfflineBackup.Layer(fileName = "terrain.mbtiles", key = "terrain", label = "elevation"),
    )

    val area = OfflineArea(
        id = UUID.fromString("6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"),
        name = "Fixture farm",
        created = 1_761_696_000.0,
        shape = CoverageShape(polygons = listOf(listOf(GeoPoint(lat = -33.9245, lon = 18.9232), GeoPoint(lat = -33.9201, lon = 18.9302),
                                                       GeoPoint(lat = -33.9263, lon = 18.9355), GeoPoint(lat = -33.9301, lon = 18.9268))),
                              lines = emptyList(), points = listOf(GeoPoint(lat = -33.9175, lon = 18.924)), bufferMeters = 150.0),
        roads = listOf(listOf(GeoPoint(lat = -33.9238, lon = 18.9241), GeoPoint(lat = -33.9259, lon = 18.9312))),
        preset = null, isTrack = null, maxZoom = 18, allowCellular = false, status = OfflineArea.Status.complete, failedTiles = 0,
        bytesOnDisk = 2_345_678_901, problem = null,
    )

    /** Two imagery tiles (any bytes do; real ones are JPEG) and one known to be missing. */
    val imageryTiles: List<Pair<TileID, ByteArray>> = listOf(
        Pair(TileID(z = 17, x = 72427, y = 79652), ByteArray(300) { (it % 251).toByte() }),
        Pair(TileID(z = 18, x = 144855, y = 159305), "not really a JPEG".toByteArray()),
    )
    val missingImagery = TileID(z = 19, x = 289710, y = 318610)

    val terrainTile = TileID(z = 12, x = 2390, y = 2342)
    val terrainData: ByteArray get() = fixture("terrarium_12_2390_2342.png")
}
