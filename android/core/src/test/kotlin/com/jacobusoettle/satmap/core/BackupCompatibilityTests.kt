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
    fun testCreatedIsWrittenBackAsTheIPhoneWroteIt() {
        // Kept as Swift's Date keeps it, in seconds since 2001, so restoring an iPhone backup and writing it again
        // changes no digit (in seconds since 1970 it moved by 2^-23 s: 756995516.654808, 0.10000002384185791).
        for (created in listOf("756995516.6548079", "0.1", "783388800", "1e-07", "-0")) {
            val text = BackupFixture.area.toJson().toString().replace(""""created":783388800""", """"created":$created""")
            val written = OfflineArea.decode(text).toJson().toString()
            assertEquals(created, written.substringAfter(""""created":""").substringBefore(","))
        }
        assertEquals(783388800.0, BackupFixture.area.created, 0.0)
    }

    @Test
    fun testAndroidFixtureIsWhatExportWrites() {
        // Numbers as Swift's JSONEncoder writes them ("bufferMeters":150, as in the iPhone's fixture), not "150.0".
        for (name in listOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles")) {
            val copy = File(tempDir(), "copy.mbtiles")
            File(System.getProperty("satmap.fixtures"), "backup/android/$name").copyTo(copy)
            val json = String(OfflineBackup.inspect(copy).areaJSON, Charsets.UTF_8)
            assertEquals(name, BackupFixture.area.toJson().toString(), json)
            assertTrue(json, json.contains(""""bufferMeters":150}"""))
        }
    }

    @Test
    fun testAreaJSONTextIsReadAsTheIPhoneReadsIt() {
        // Each result is Swift's JSONDecoder's for the same text.
        val json = BackupFixture.area.toJson().toString()
        fun with(old: String, new: String) = json.replace(old, new).also { assertTrue(old, it != json) }
        fun refused(text: String) = assertThrows(text, Exception::class.java) { OfflineArea.decode(text) }
        assertEquals(BackupFixture.area, OfflineArea.decode(json))
        // One byte order mark, trailing commas, and of repeated keys the first.
        assertEquals(BackupFixture.area, OfflineArea.decode("\uFEFF" + json))
        refused("\uFEFF\uFEFF" + json)
        assertEquals(BackupFixture.area, OfflineArea.decode(with(""""lon":18.9312}]]""", """"lon":18.9312},],]""").dropLast(1) + ",}"))
        refused(with(""""lines":[]""", """"lines":[,]"""))
        assertEquals(18, OfflineArea.decode(with(""""maxZoom":18""", """"maxZoom":18,"maxZoom":19""")).maxZoom)
        refused(with(""""maxZoom":18""", """"maxZoom":"x","maxZoom":18"""))
        // No number or boolean written as a string, nor a number a Double can't hold.
        for ((old, new) in listOf(""""created":783388800""" to """"created":"783388800"""", """"allowCellular":false""" to """"allowCellular":"false"""",
                                  """"bufferMeters":150""" to """"bufferMeters":"150"""", """"lat":-33.9245""" to """"lat":"-33.9245"""",
                                  """"maxZoom":18""" to """"isTrack":"true","maxZoom":18""", """"created":783388800""" to """"created":1e400""",
                                  """"maxZoom":18""" to """"maxZoom":018""", """"maxZoom":18""" to """"maxZoom":+18""",
                                  """"allowCellular":false""" to """"allowCellular":FALSE""", """"name":"Fixture farm"""" to """"name":"\ud800"""")) {
            refused(with(old, new))
            assertThrows(new, Exception::class.java) { OfflineArea.fromJson(Json.parseToJsonElement(with(old, new))) }
        }
        refused(with("Fixture farm", "Fixture\tfarm")) // a raw tab (kotlinx's parser reads it as "\t")
        // A value that isn't read is checked only as far as Swift's scan checks it: words and nesting, not strings or numbers.
        for (value in listOf("018", "1e400", "\"a\tb\"", "\"\\ud800\"", "{\"a\tb\":1}", "[".repeat(511) + "]".repeat(511))) {
            assertEquals(value, BackupFixture.area, OfflineArea.decode(with(""""maxZoom":18""", """"zz":$value,"maxZoom":18""")))
        }
        for (value in listOf("NaN", "TRUE", "tru", "+1", ".5", "\"\\u12\"", "[".repeat(512) + "]".repeat(512))) {
            refused(with(""""maxZoom":18""", """"zz":$value,"maxZoom":18"""))
        }
        // Whole numbers: digits, or a Double that is whole (below 2^53), or else Foundation's Decimal.
        fun read(old: String, new: String) = OfflineArea.decode(with(old, new))
        assertEquals(18, read(""""maxZoom":18""", """"maxZoom":18.000000000000001""").maxZoom)
        assertEquals(18, read(""""maxZoom":18""", """"maxZoom":1.8e1""").maxZoom)
        assertEquals(0L, read(""""failedTiles":0""", """"failedTiles":1e-400""").failedTiles)
        refused(with(""""maxZoom":18""", """"maxZoom":18.5"""))
        assertEquals(9_007_199_254_740_993L, read(""""bytesOnDisk":2345678901""", """"bytesOnDisk":9007199254740993.0""").bytesOnDisk)
        assertEquals(Long.MAX_VALUE, read(""""bytesOnDisk":2345678901""", """"bytesOnDisk":9223372036854775807""").bytesOnDisk)
        refused(with(""""bytesOnDisk":2345678901""", """"bytesOnDisk":9223372036854775807.0"""))
    }

    @Test
    fun testCountsBeyondAnIntAreNotWrapped() {
        // Swift's Int is 64 bits: failedTiles 2147483648 is read as it is. A zoom past an Int is refused (the iPhone
        // keeps it, but no zoom is that deep), not wrapped round to zoom 18.
        val json = BackupFixture.area.toJson().toString()
        assertEquals(2_147_483_648L, OfflineArea.decode(json.replace(""""failedTiles":0""", """"failedTiles":2147483648""")).failedTiles)
        assertThrows(Exception::class.java) { OfflineArea.decode(json.replace(""""maxZoom":18""", """"maxZoom":4294967314""")) }
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
        created = 1_761_696_000.0 - OfflineArea.referenceDate, // Swift's Date(timeIntervalSince1970: 1_761_696_000)
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
