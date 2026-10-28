package com.jacobusoettle.satmap.core

import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A tile store in the MBTiles layout (SQLite; tile_row uses the TMS convention, flipped from XYZ).
 *  One file per layer per offline area. Thread-safe: every operation is serialized by a lock.
 *  Also keeps a `missing_tiles` table of tiles the server reported as non-existent, so resumed
 *  downloads don't ask again.
 *
 *  SQLite comes from `driver` (set once before the first store is opened). Close a store when done with it (the
 *  Swift class also closes when its last reference goes). */
class MBTiles(val file: File, create: Boolean) : Closeable {
    /** The Swift `StoreError` cases; the message is the Swift `errorDescription`. */
    sealed class StoreError(val errorDescription: String) : Exception(errorDescription) {
        class Open(val m: String) : StoreError("Could not open tile database: $m")
        class Sql(val m: String) : StoreError("Tile database error: $m")
    }

    /** The last write error (e.g. disk full). Writes after an error are not committed. */
    var writeError: String? = null
        private set
    private var db: SqlDatabase? = null
    private val lock = ReentrantLock()
    private val statements = HashMap<String, SqlStatement>()
    private var uncommittedWrites = 0
    private val writesPerTransaction = 256

    /** Opens the database. The downloader opens it with `create = true` (creating file and schema);
     *  map readers open their own instance with `create = false`. WAL journaling lets the map read
     *  while a download writes. */
    init {
        // On iOS the file is also opened so it can be read and written while the phone is locked (after the first
        // unlock since boot); an Android app's own storage already can be.
        val opened = try {
            driver.open(file.path, create)
        } catch (e: SqlException) {
            throw StoreError.Open(e.message ?: "code ${e.code}")
        }
        db = opened
        opened.busyTimeout(10_000)
        if (create) {
            try {
                // One statement at a time (the Swift runs them as one script).
                for (sql in schema) exec(sql)
            } catch (e: StoreError) {
                close()
                throw e
            }
        }
    }

    override fun close() {
        lock.withLock {
            val db = db ?: return
            if (uncommittedWrites > 0) {
                if (db.commit() != SqlResult.OK) db.rollback()
                uncommittedWrites = 0
            }
            for (s in statements.values) s.close()
            statements.clear()
            db.close()
            this.db = null
        }
    }

    // Reading

    fun tile(t: TileID): ByteArray? = lock.withLock {
        val s = statement("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?") ?: return null
        try {
            s.bindInt(1, t.z)
            s.bindInt(2, t.x)
            s.bindInt(3, tmsRow(t))
            if (s.step() != SqlResult.ROW) return null
            val data = s.columnBlob(0)
            if (data == null || data.isEmpty()) return null
            data
        } finally {
            s.reset()
        }
    }

    /** Whether the downloader recorded that the server has no tile here. */
    fun isMissing(t: TileID): Boolean = missingColumns(z = t.z, y = t.y, xs = t.x..t.x).isNotEmpty()

    fun contains(t: TileID): Boolean = existingColumns(z = t.z, y = t.y, xs = t.x..t.x).isNotEmpty()

    /** XYZ columns in `xs` that have a stored tile in XYZ row `y`. */
    fun existingColumns(z: Int, y: Int, xs: IntRange): Set<Int> =
        columns(sql = "SELECT tile_column FROM tiles WHERE zoom_level=? AND tile_row=? AND tile_column BETWEEN ? AND ?", z = z, y = y, xs = xs)

    /** XYZ columns in `xs` that the server reported as non-existent in XYZ row `y`. */
    fun missingColumns(z: Int, y: Int, xs: IntRange): Set<Int> =
        columns(sql = "SELECT tile_column FROM missing_tiles WHERE zoom_level=? AND tile_row=? AND tile_column BETWEEN ? AND ?", z = z, y = y, xs = xs)

    private fun columns(sql: String, z: Int, y: Int, xs: IntRange): Set<Int> = lock.withLock {
        val s = statement(sql) ?: return emptySet()
        try {
            s.bindInt(1, z)
            s.bindInt(2, (1 shl z) - 1 - y)
            s.bindInt(3, xs.first)
            s.bindInt(4, xs.last)
            val out = HashSet<Int>()
            while (s.step() == SqlResult.ROW) out.add(s.columnInt(0))
            out
        } finally {
            s.reset()
        }
    }

    fun tileCount(): Long = lock.withLock {
        val s = statement("SELECT COUNT(*) FROM tiles") ?: return 0
        try {
            if (s.step() == SqlResult.ROW) s.columnLong(0) else 0
        } finally {
            s.reset()
        }
    }

    /** Highest zoom level that has at least one stored tile. */
    fun maxStoredZoom(): Int? = lock.withLock {
        val s = statement("SELECT MAX(zoom_level) FROM tiles") ?: return null
        try {
            if (s.step() != SqlResult.ROW || s.columnIsNull(0)) return null
            s.columnInt(0)
        } finally {
            s.reset()
        }
    }

    // Writing (batched into transactions)

    /** Stores a tile. Returns false if it could not be written (see `writeError`). */
    fun put(t: TileID, data: ByteArray): Boolean = lock.withLock {
        if (writeError != null || !beginIfNeeded()) return false
        val s = statement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)")
            ?: return false
        try {
            s.bindInt(1, t.z)
            s.bindInt(2, t.x)
            s.bindInt(3, tmsRow(t))
            s.bindBlob(4, data)
            val rc = s.step()
            s.clearBindings()
            if (rc != SqlResult.DONE) return failWrite(rc)
            noteWrite()
        } finally {
            s.reset()
        }
    }

    /** Records that the server has no tile here. Returns false on a write error. */
    fun putMissing(t: TileID): Boolean = lock.withLock {
        if (writeError != null || !beginIfNeeded()) return false
        val s = statement("INSERT OR IGNORE INTO missing_tiles (zoom_level, tile_column, tile_row) VALUES (?,?,?)")
            ?: return false
        try {
            s.bindInt(1, t.z)
            s.bindInt(2, t.x)
            s.bindInt(3, tmsRow(t))
            val rc = s.step()
            if (rc != SqlResult.DONE) return failWrite(rc)
            noteWrite()
        } finally {
            s.reset()
        }
    }

    fun metadata(name: String): String? = lock.withLock {
        val s = statement("SELECT value FROM metadata WHERE name = ?") ?: return null
        try {
            s.bindText(1, name)
            if (s.step() != SqlResult.ROW) return null
            s.columnText(0)
        } finally {
            s.reset()
        }
    }

    fun setMetadata(values: Map<String, String>) {
        lock.withLock {
            if (writeError != null || !beginIfNeeded()) return
            val s = statement("INSERT OR REPLACE INTO metadata (name, value) VALUES (?,?)") ?: return
            for ((k, v) in values) {
                s.bindText(1, k)
                s.bindText(2, v)
                s.step()
                s.reset()
            }
            noteWrite()
        }
    }

    /** Commits any pending writes. Returns false on a write error. */
    fun flush(): Boolean = lock.withLock { commitLocked() }

    /** Folds the write-ahead log back into the database file and truncates it (after each downloaded layer, and
     *  before the file is exported). Returns false if it couldn't finish, e.g. because a reader was busy. */
    fun checkpoint(): Boolean = lock.withLock {
        val db = db ?: return false
        val committed = commitLocked()
        db.checkpointTruncate() == SqlResult.OK && committed
    }

    // Internals (call with lock held)

    private fun beginIfNeeded(): Boolean {
        val db = db ?: return false
        if (uncommittedWrites == 0) {
            val rc = db.begin()
            if (rc != SqlResult.OK) return failWrite(rc)
        }
        return true
    }

    private fun noteWrite(): Boolean {
        uncommittedWrites += 1
        if (uncommittedWrites >= writesPerTransaction) return commitLocked()
        return true
    }

    private fun commitLocked(): Boolean {
        val db = db ?: return false
        if (uncommittedWrites <= 0) return writeError == null
        val rc = db.commit()
        uncommittedWrites = 0
        if (rc != SqlResult.OK) {
            db.rollback()
            return failWrite(rc)
        }
        return true
    }

    /** Records a write failure (disk full, I/O error) and abandons the open transaction. */
    private fun failWrite(rc: Int): Boolean {
        if (writeError == null) {
            // "The iPhone is out of storage space." on iOS.
            writeError = if (rc == SqlResult.FULL) "The phone is out of storage space." else "Couldn't save map tiles (SQLite error $rc)."
        }
        val db = db
        if (db != null && uncommittedWrites > 0) {
            db.rollback()
            uncommittedWrites = 0
        }
        return false
    }

    private fun statement(sql: String): SqlStatement? {
        statements[sql]?.let { return it }
        val db = db ?: return null
        val prepared = db.prepare(sql) ?: return null
        statements[sql] = prepared
        return prepared
    }

    private fun exec(sql: String) {
        val db = db ?: throw StoreError.Sql("closed")
        if (db.exec(sql) != SqlResult.OK) throw StoreError.Sql(db.errorMessage.ifEmpty { "unknown" })
    }

    companion object {
        /** The SQLite every store runs on: android.database.sqlite in the app, sqlite-jdbc in the core tests. */
        @Volatile
        lateinit var driver: SqlDriver

        private val schema = listOf(
            "PRAGMA journal_mode=WAL",
            "PRAGMA synchronous=NORMAL",
            "PRAGMA journal_size_limit=67108864",
            "CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)",
            """CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_row, tile_column)",
            """CREATE TABLE IF NOT EXISTS missing_tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, PRIMARY KEY (zoom_level, tile_row, tile_column)) WITHOUT ROWID""",
        )

        private fun tmsRow(t: TileID): Int = (1 shl t.z) - 1 - t.y
    }
}
