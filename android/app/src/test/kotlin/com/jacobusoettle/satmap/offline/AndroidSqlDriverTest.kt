package com.jacobusoettle.satmap.offline

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY
import android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
import android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.core.OfflineBackup
import com.jacobusoettle.satmap.core.SqlDatabase
import com.jacobusoettle.satmap.core.SqlException
import com.jacobusoettle.satmap.core.SqlResult
import com.jacobusoettle.satmap.core.TileID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/** The tile store on Android's SQLite (DESIGN.md D17), under Robolectric's native SQLite: core's `MBTiles` and
 *  `OfflineBackup` through `AndroidSqlDriver`, and the backups made by both apps. */
@RunWith(AndroidJUnit4::class)
class AndroidSqlDriverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before
    fun setUp() {
        MBTiles.driver = AndroidSqlDriver()
        dir = File(app.cacheDir, "sql-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writersAndMapReadersKeepTheFileInWALMode() {
        val driver = AndroidSqlDriver()
        assertEquals(OPEN_READWRITE or NO_LOCALIZED_COLLATORS or ENABLE_WRITE_AHEAD_LOGGING, driver.flags(create = false))
        assertEquals(
            OPEN_READWRITE or NO_LOCALIZED_COLLATORS or ENABLE_WRITE_AHEAD_LOGGING or CREATE_IF_NECESSARY,
            driver.flags(create = true),
        )
    }

    @Test
    fun oneOffReadersOpenReadOnly() {
        val driver = AndroidSqlDriver(readOnly = true)
        assertEquals(OPEN_READONLY or NO_LOCALIZED_COLLATORS, driver.flags(create = false))
        assertFailsWith<SqlException> { driver.open("/nonexistent/imagery.mbtiles", create = true) }
    }

    @Test
    fun aTileStoreRoundTrips() {
        val file = File(dir, "imagery.mbtiles")
        val tiles = (0 until 300).map { TileID(z = 18, x = 1000 + it, y = 2000) }
        val missing = TileID(z = 19, x = 2000, y = 4000)
        val store = MBTiles(file, create = true)
        store.setMetadata(mapOf("name" to "Farm", "format" to "jpg"))
        for (t in tiles) assertTrue(store.put(t, data = "$t".toByteArray()))
        assertTrue(store.putMissing(missing))
        assertTrue(store.flush())
        store.close()

        val again = MBTiles(file, create = false)
        try {
            assertArrayEquals("${tiles[7]}".toByteArray(), again.tile(tiles[7]))
            assertNull(again.tile(TileID(z = 18, x = 999, y = 2000)))
            assertTrue(again.contains(tiles[299]))
            assertTrue(again.isMissing(missing))
            assertFalse(again.isMissing(tiles[0]))
            assertEquals((1000 until 1300).toSet(), again.existingColumns(z = 18, y = 2000, xs = 0..5000))
            assertEquals(setOf(2000), again.missingColumns(z = 19, y = 4000, xs = 1990..2010))
            assertEquals(300L, again.tileCount())
            assertEquals(18, again.maxStoredZoom())
            assertEquals("Farm", again.metadata("name"))
            assertNull(again.metadata("attribution"))
            // Replaced, not added.
            assertTrue(again.put(tiles[0], data = byteArrayOf(1, 2, 3)))
            assertTrue(again.flush())
            assertArrayEquals(byteArrayOf(1, 2, 3), again.tile(tiles[0]))
            assertEquals(300L, again.tileCount())
        } finally {
            again.close()
        }
    }

    @Test(timeout = 60_000)
    fun aTransactionCarriesOnFromWhicheverThreadWritesNext() {
        // A download's coroutine resumes on any IO thread, while MBTiles keeps one transaction open for 256 writes.
        val file = File(dir, "imagery.mbtiles")
        val store = MBTiles(file, create = true)
        for (i in 0 until 100) {
            var ok = false
            thread { ok = store.put(TileID(z = 16, x = i, y = 7), data = byteArrayOf(i.toByte())) }.join()
            assertTrue(ok)
        }
        var flushed = false
        thread { flushed = store.flush() }.join()
        assertTrue(flushed)
        assertEquals(100L, store.tileCount())
        store.close()
        val again = MBTiles(file, create = false)
        assertEquals(100L, again.tileCount())
        again.close()
    }

    @Test
    fun theFileStaysInWALModeAndTheMapReadsWhileADownloadWrites() {
        val file = File(dir, "imagery.mbtiles")
        val writer = MBTiles(file, create = true)
        val first = TileID(z = 15, x = 1, y = 1)
        val second = TileID(z = 15, x = 2, y = 1)
        assertTrue(writer.put(first, data = byteArrayOf(1)))
        assertTrue(writer.flush())
        val reader = MBTiles(file, create = false) // the map's own reader
        assertArrayEquals(byteArrayOf(1), reader.tile(first))
        assertTrue(writer.put(second, data = byteArrayOf(2))) // a write transaction is open now
        assertArrayEquals(byteArrayOf(1), reader.tile(first)) // the reader isn't held up …
        assertNull(reader.tile(second)) // … and sees only what is committed
        assertTrue(writer.flush())
        assertArrayEquals(byteArrayOf(2), reader.tile(second))
        assertTrue(File(file.path + "-wal").exists())
        reader.close()
        writer.close()

        // Opened again (as the map and the next download do), the file is still in WAL mode.
        val db = AndroidSqlDriver().open(file.path, create = false)
        try {
            assertEquals(listOf("wal"), rows(db, "PRAGMA journal_mode"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aCheckpointLeavesEverythingInTheFileItself() {
        val file = File(dir, "imagery.mbtiles")
        val wal = File(file.path + "-wal")
        val store = MBTiles(file, create = true)
        val tiles = (0 until 300).map { TileID(z = 18, x = 1000 + it, y = 2000) }
        for (t in tiles) assertTrue(store.put(t, data = "$t".toByteArray()))
        assertTrue(store.flush())
        assertTrue(wal.length() > 0)
        val reader = MBTiles(file, create = false) // the map has the file open too
        assertNotNull(reader.tile(tiles[0]))
        assertTrue(store.checkpoint())
        assertEquals(0L, wal.length())
        reader.close()
        store.close()
        assertFalse("no write-ahead log is left", wal.exists())

        // The file alone (as a backup is sent) holds every tile.
        val copy = File(File(dir, "elsewhere").apply { mkdirs() }, "copy.mbtiles")
        file.copyTo(copy)
        val opened = MBTiles(copy, create = false)
        assertEquals(300L, opened.tileCount())
        assertArrayEquals("${tiles[299]}".toByteArray(), opened.tile(tiles[299]))
        opened.close()
    }

    @Test
    fun noTablesOfAndroidsOwnAreAdded() {
        val file = File(dir, "terrain.mbtiles")
        MBTiles(file, create = true).apply {
            setMetadata(mapOf("format" to "png"))
            flush()
            close()
        }
        MBTiles(file, create = false).close()
        val db = AndroidSqlDriver().open(file.path, create = false)
        try {
            assertEquals(listOf("metadata", "missing_tiles", "tiles"),
                         rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"))
        } finally {
            db.close()
        }
    }

    @Test
    fun readsTheBackupMadeOnTheIPhone() {
        checkBackup("iphone")
    }

    @Test
    fun readsTheBackupMadeOnAndroid() {
        checkBackup("android")
    }

    @Test
    fun aFullDiskIsSQLiteFull() {
        val db = AndroidSqlDriver().open(File(dir, "full.mbtiles").path, create = true)
        try {
            assertEquals(SqlResult.OK, db.exec("CREATE TABLE t (b BLOB)"))
            assertEquals(SqlResult.OK, db.begin())
            // In the transaction the pragma reaches the writing connection: the file may not grow beyond 3 pages.
            assertEquals(SqlResult.OK, db.exec("PRAGMA max_page_count = 3"))
            val insert = db.prepare("INSERT INTO t (b) VALUES (?)")!!
            insert.bindBlob(1, ByteArray(64_000))
            assertEquals(SqlResult.FULL, insert.step()) // MBTiles makes this "The phone is out of storage space."
            insert.close()
            db.rollback() // fails if SQLite has rolled back by itself already, as it may in C
            assertNotEquals(SqlResult.OK, db.rollback()) // no transaction any more
            // And the database carries on.
            assertEquals(SqlResult.OK, db.begin())
            assertEquals(SqlResult.OK, db.exec("INSERT INTO t (b) VALUES (x'01')"))
            assertEquals(SqlResult.OK, db.commit())
            assertEquals(listOf("1"), rows(db, "SELECT COUNT(*) FROM t"))
        } finally {
            db.close()
        }
    }

    @Test
    fun theBusyTimeoutIsSet() {
        val db = AndroidSqlDriver().open(File(dir, "a.mbtiles").path, create = true)
        try {
            db.busyTimeout(10_000)
            assertEquals(listOf("10000"), rows(db, "PRAGMA busy_timeout"))
        } finally {
            db.close()
        }
    }

    @Test
    fun beforeAndroid13TheWriterGetsTheBusyTimeoutAndTheStoreWorks() {
        // Android 11 to 12L can't run the pragma on every connection (busyTimeout's comment): there it goes through
        // rawQuery. Robolectric runs Android 15 only, so only the version the driver reads is changed.
        for (level in listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.S_V2)) {
            val db = asOn(level) {
                AndroidSqlDriver().open(File(dir, "$level.mbtiles").path, create = true).apply { busyTimeout(10_000) }
            }
            try {
                assertEquals(SqlResult.OK, db.begin()) // the transaction holds the writing connection
                assertEquals("$level", listOf("10000"), rows(db, "PRAGMA busy_timeout"))
                assertEquals(SqlResult.OK, db.rollback())
            } finally {
                db.close()
            }

            // A download's store, opened there, saves and reads its tiles.
            val file = File(dir, "imagery-$level.mbtiles")
            val tiles = (0 until 300).map { TileID(z = 18, x = 1000 + it, y = 2000) }
            val store = asOn(level) { MBTiles(file, create = true) }
            for (t in tiles) assertTrue(store.put(t, data = "$t".toByteArray()))
            assertTrue(store.flush())
            store.close()
            val again = asOn(level) { MBTiles(file, create = false) }
            assertEquals(300L, again.tileCount())
            assertArrayEquals("${tiles[7]}".toByteArray(), again.tile(tiles[7]))
            again.close()
        }
    }

    @Test(timeout = 60_000)
    fun aWriterWaitsForAnotherLongerThanAndroidsOwnTimeout() {
        // Android gives a connection 2.5 s; the store waits 10 s, as in C, on every version.
        for (level in listOf(Build.VERSION_CODES.R, Build.VERSION.SDK_INT)) {
            val path = File(dir, "w$level.mbtiles").path
            val first = AndroidSqlDriver().open(path, create = true)
            val second = asOn(level) { AndroidSqlDriver().open(path, create = true).apply { busyTimeout(10_000) } }
            try {
                assertEquals(SqlResult.OK, first.exec("CREATE TABLE t (x INTEGER)"))
                assertEquals(SqlResult.OK, first.begin())
                assertEquals(SqlResult.OK, first.exec("INSERT INTO t (x) VALUES (1)"))
                val release = thread {
                    Thread.sleep(3_500)
                    first.commit()
                }
                val start = System.nanoTime()
                assertEquals("$level", SqlResult.OK, second.begin()) // waits for the first to commit
                assertTrue("$level", System.nanoTime() - start > 3_000_000_000L)
                assertEquals(SqlResult.OK, second.exec("INSERT INTO t (x) VALUES (2)"))
                assertEquals(SqlResult.OK, second.commit())
                release.join()
                assertEquals(listOf("2"), rows(second, "SELECT COUNT(*) FROM t"))
            } finally {
                second.close()
                first.close()
            }
        }
    }

    @Test
    fun aFailureOfAndroidsOwnIsNeverSuccess() {
        // What Android 11 to 12L threw for the busy timeout run per connection, and any version throws for a statement
        // with rows it is asked to execute: code 0, which isn't SQLite's.
        val refused = SQLiteException(
            "unknown error (code 0 SQLITE_OK): Queries can be performed using SQLiteDatabase query or rawQuery methods only."
        )
        assertEquals(SqlResult.ERROR, AndroidSqlDriver.resultCode(refused))
        assertEquals("Queries can be performed using SQLiteDatabase query or rawQuery methods only.",
                     AndroidSqlDriver.message(refused))
        assertEquals(SqlResult.FULL, AndroidSqlDriver.resultCode(SQLiteFullException("unknown error (code 0 SQLITE_OK)")))
        assertEquals("unknown error", AndroidSqlDriver.message(SQLiteFullException("unknown error (code 0 SQLITE_OK)")))
        // SQLite's own keep their primary code and SQLite's message.
        val full = SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")
        assertEquals(SqlResult.FULL, AndroidSqlDriver.resultCode(full))
        assertEquals("database or disk is full", AndroidSqlDriver.message(full))
        val unique = SQLiteConstraintException(
            "UNIQUE constraint failed: metadata.name (code 2067 SQLITE_CONSTRAINT_UNIQUE[2067])"
        )
        assertEquals(19, AndroidSqlDriver.resultCode(unique))
        assertEquals("UNIQUE constraint failed: metadata.name", AndroidSqlDriver.message(unique))

        // Through a database: a statement Android refuses to execute (core never runs one) fails, with Android's reason.
        val db = AndroidSqlDriver().open(File(dir, "a.mbtiles").path, create = true)
        try {
            assertEquals(SqlResult.ERROR, db.exec("VALUES (1)"))
            assertEquals("Queries can be performed using SQLiteDatabase query or rawQuery methods only.", db.errorMessage)
        } finally {
            db.close()
        }
    }

    @Test
    fun aMissingFileDoesntOpenUnlessCreated() {
        val e = assertFailsWith<SqlException> { AndroidSqlDriver().open(File(dir, "none.mbtiles").path, create = false) }
        assertEquals(SqlResult.CANTOPEN, e.code)
        assertFailsWith<MBTiles.StoreError.Open> { MBTiles(File(dir, "none.mbtiles"), create = false) }
        assertFalse(File(dir, "none.mbtiles").exists())
    }

    @Test
    fun aFileThatIsntADatabaseOpensAsInCAndIsKept() {
        val file = File(dir, "holiday.jpg")
        file.writeBytes(ByteArray(8192) { 0x42 })
        val db = AndroidSqlDriver().open(file.path, create = false)
        assertNull(db.prepare("SELECT value FROM metadata"))
        assertEquals("file is not a database", db.errorMessage)
        db.close()
        // Android's default handler would have deleted it.
        assertTrue(file.exists())
        val e = assertFailsWith<OfflineBackup.Failure.NotABackup> { OfflineBackup.inspect(file) }
        assertEquals("holiday.jpg isn't a SatMap backup.", e.errorDescription)
        assertFailsWith<MBTiles.StoreError.Sql> { MBTiles(file, create = true) }
        assertEquals(8192L, file.length())
    }

    @Test
    fun aReadOnlyReaderReadsAndNeverWrites() {
        val file = File(dir, "imagery.mbtiles")
        MBTiles(file, create = true).apply {
            put(TileID(z = 17, x = 5, y = 5), data = byteArrayOf(9))
            put(TileID(z = 19, x = 5, y = 5), data = byteArrayOf(9))
            flush()
            close()
        }
        val db = AndroidSqlDriver(readOnly = true).open(file.path, create = false)
        try {
            assertEquals(listOf("19"), rows(db, "SELECT MAX(zoom_level) FROM tiles"))
            assertNotEquals(SqlResult.OK, db.exec("DELETE FROM tiles"))
            assertEquals(listOf("2"), rows(db, "SELECT COUNT(*) FROM tiles"))
        } finally {
            db.close()
        }
    }

    /** Runs `body` with the driver reading `level` as the Android version (Robolectric runs Android 15 only). */
    private fun <T> asOn(level: Int, body: () -> T): T {
        val real = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", level)
        try {
            return body()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", real)
        }
    }

    /** The first column of every row. */
    private fun rows(db: SqlDatabase, sql: String): List<String?> {
        val s = db.prepare(sql) ?: throw AssertionError(db.errorMessage)
        try {
            val out = mutableListOf<String?>()
            while (s.step() == SqlResult.ROW) out.add(s.columnText(0))
            return out
        } finally {
            s.close()
        }
    }

    /** Restores a backup as the app does (`OfflineBackup.inspect` of a copy, then the area's JSON) and checks every
     *  part, as core's `BackupCompatibilityTests` does on sqlite-jdbc. */
    private fun checkBackup(folder: String) {
        val source = File(fixtures, "backup/$folder")
        val layers = HashMap<String, File>()
        for (name in listOf("Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles")) {
            val original = File(source, name)
            assertTrue("$folder: $name", original.exists())
            val copy = File(dir, UUID.randomUUID().toString() + ".mbtiles")
            original.copyTo(copy)
            val info = OfflineBackup.inspect(copy)
            val area = OfflineArea.fromJson(Json.parseToJsonElement(String(info.areaJSON, Charsets.UTF_8)))
            assertEquals("$folder: $name", Fixture.area, area)
            layers[info.layerKey] = original
            val store = MBTiles(copy, create = false)
            try {
                when (info.layerKey) {
                    "imagery" -> {
                        for ((t, data) in Fixture.imageryTiles) assertArrayEquals("$folder: $t", data, store.tile(t))
                        assertTrue(folder, store.isMissing(Fixture.missingImagery))
                        assertEquals(folder, Fixture.imageryTiles.size.toLong(), store.tileCount())
                    }
                    "terrain" -> assertArrayEquals(folder, File(fixtures, "terrarium_12_2390_2342.png").readBytes(),
                                                   store.tile(Fixture.terrainTile))
                    else -> throw AssertionError("$folder: unknown layer ${info.layerKey}")
                }
            } finally {
                store.close()
            }
            // Nothing of Android's own was added to the restored file.
            val db = AndroidSqlDriver().open(copy.path, create = false)
            assertEquals(emptyList<String>(), rows(db, "SELECT name FROM sqlite_master WHERE name = 'android_metadata'"))
            db.close()
        }
        assertEquals(folder, setOf("imagery", "terrain"), layers.keys)
    }

    /** The backup both apps' tests expect (core's `BackupFixture`, and the Swift tests', have the same values). */
    internal object Fixture {
        val area = OfflineArea(
            id = UUID.fromString("6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"),
            name = "Fixture farm",
            created = 1_761_696_000.0,
            shape = CoverageShape(
                polygons = listOf(listOf(GeoPoint(lat = -33.9245, lon = 18.9232), GeoPoint(lat = -33.9201, lon = 18.9302),
                                         GeoPoint(lat = -33.9263, lon = 18.9355), GeoPoint(lat = -33.9301, lon = 18.9268))),
                lines = emptyList(), points = listOf(GeoPoint(lat = -33.9175, lon = 18.924)), bufferMeters = 150.0,
            ),
            roads = listOf(listOf(GeoPoint(lat = -33.9238, lon = 18.9241), GeoPoint(lat = -33.9259, lon = 18.9312))),
            preset = null, isTrack = null, maxZoom = 18, allowCellular = false, status = OfflineArea.Status.complete,
            failedTiles = 0, bytesOnDisk = 2_345_678_901, problem = null,
        )
        val imageryTiles: List<Pair<TileID, ByteArray>> = listOf(
            Pair(TileID(z = 17, x = 72427, y = 79652), ByteArray(300) { (it % 251).toByte() }),
            Pair(TileID(z = 18, x = 144855, y = 159305), "not really a JPEG".toByteArray()),
        )
        val missingImagery = TileID(z = 19, x = 289710, y = 318610)
        val terrainTile = TileID(z = 12, x = 2390, y = 2342)
    }

    companion object {
        /** Tests/SatMapCoreTests/Fixtures: the `satmap.fixtures` property when the build sets it, else found from the
         *  module folder Gradle runs the tests in. */
        val fixtures: File = System.getProperty("satmap.fixtures")?.let(::File)
            ?: File("../../Tests/SatMapCoreTests/Fixtures").canonicalFile
    }
}
