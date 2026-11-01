package com.jacobusoettle.satmap.core

import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/** A tile store in the MBTiles layout (SQLite; tile_row uses the TMS convention, flipped from XYZ).
 *  One file per layer per offline area. Thread-safe: every operation is serialized by a lock.
 *  Also keeps a `missing_tiles` table of tiles the server reported as non-existent, so resumed
 *  downloads don't ask again.
 *
 *  Tiles are stored sealed (`TileCrypto`), so other apps can't show them. Files from before tiles were encrypted
 *  still hold plain ones, which are read as they are until `encryptPlainTiles` seals them.
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
    private var loggedUnreadable = false

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
        opened.busyTimeout(busyTimeout)
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

    /** The tile's image: opened if it is sealed, as it is if it was stored before tiles were encrypted. A tile that
     *  doesn't open (damaged, or moved from another place) is treated as missing, and logged once. */
    fun tile(t: TileID): ByteArray? {
        val blob = storedTile(t) ?: return null
        if (!TileCrypto.isSealed(blob)) return blob
        return try {
            TileCrypto.open(blob, t)
        } catch (e: TileCrypto.Failure) {
            val first = lock.withLock { !loggedUnreadable.also { loggedUnreadable = true } }
            if (first) log.warning("${file.name}: tile $t doesn't open (${e.message}), treated as missing; others aren't logged")
            null
        }
    }

    /** The tile's bytes as stored: sealed, or plain from before tiles were encrypted. */
    fun storedTile(t: TileID): ByteArray? = lock.withLock {
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

    /** Stores a tile, sealed (`TileCrypto`) unless it already is: a tile server may deliver tiles sealed in the same
     *  format, and they are stored as they come. Returns false if it could not be written (see `writeError`). */
    fun put(t: TileID, data: ByteArray): Boolean {
        val blob = try {
            if (TileCrypto.isSealed(data)) data else TileCrypto.seal(data, t)
        } catch (e: TileCrypto.Failure) {
            return false
        }
        return lock.withLock {
            if (writeError != null || !beginIfNeeded()) return false
            val s = statement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)")
                ?: return false
            try {
                s.bindInt(1, t.z)
                s.bindInt(2, t.x)
                s.bindInt(3, tmsRow(t))
                s.bindBlob(4, blob)
                val rc = s.step()
                s.clearBindings()
                if (rc != SqlResult.DONE) return failWrite(rc)
                noteWrite()
            } finally {
                s.reset()
            }
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
            s.columnText(0)?.substringBefore('\u0000') // up to a NUL, as Swift's String(cString:)
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
    fun checkpoint(): Boolean = lock.withLock { checkpointLocked() }

    // Encrypting files from before tiles were encrypted

    /** Whether every tile is sealed (the file is marked with `encryptedKey`). Files from before tiles were encrypted,
     *  and backups made then, aren't until `encryptPlainTiles` has run on them. */
    val isEncrypted: Boolean get() = metadata(encryptedKey) == "1"

    /** Seals the tiles stored plain in place, `batchSize` rows at a time, each batch in a transaction of its own; the
     *  batch that finds no rows left marks the file (`encryptedKey`). Then a checkpoint (`checkpoint`) folds the sealed
     *  tiles into the database file and empties the write-ahead log: until then the file itself still holds the plain
     *  tiles, and the log earlier copies of their pages. The space a plain tile took is overwritten with zeros
     *  (`secure_delete`), so after the checkpoint its picture isn't left in the file's free space; scraps that SQLite
     *  left in unused parts of pages when the file was written may remain.
     *
     *  Meant to run in the background at low priority, on the area's download store while one is open (its pending
     *  writes are committed before each batch), else on the map's store or one of its own: between batches the map
     *  reads and a download writes as usual. A batch, and the checkpoint, hold the store's lock while they wait for
     *  another connection (a download's transaction, a reader in the middle of a read), so they wait only 0.2 s, not
     *  the usual 10 s: the store's other calls are held up by little more than a batch, and if the other connection is
     *  still busy it stops.
     *
     *  `progress` is told the rows checked so far and the tiles in the file after each batch, and stops the work by
     *  returning false. Returns true when every tile is sealed, the file is marked and the checkpoint has finished (if
     *  the file was marked already, only the checkpoint runs); false if it stopped early: asked to, a write failed
     *  (e.g. storage full), or another connection kept the file busy. It can run again at any time and carries on with
     *  what is left. */
    fun encryptPlainTiles(batchSize: Int = 64, progress: (checked: Long, total: Long) -> Boolean = { _, _ -> true }): Boolean {
        if (!isEncrypted) {
            val total = tileCount()
            var after = 0L
            var checked = 0L
            while (true) {
                val batch = encryptBatch(after = after, limit = maxOf(1, batchSize)) ?: return false
                if (batch.rows == 0) break
                checked += batch.rows
                after = batch.lastRowID
                // Tiles a download adds meanwhile come after the others (and are sealed already).
                if (!progress(checked, maxOf(total, checked))) return false
            }
        }
        // Also when the file was marked already: an earlier run may have stopped at the checkpoint.
        return lock.withLock { waitingBriefly { checkpointLocked() } }
    }

    private class Batch(val rows: Int, val lastRowID: Long)

    private class PlainTile(val rowID: Long, val tile: TileID, val data: ByteArray)

    /** Seals the plain tiles among the next `limit` rows after `after` (a rowid) in one transaction, and marks the file
     *  if there are none. The number of rows checked and the last one's rowid, or null if it failed (and nothing was
     *  changed). */
    private fun encryptBatch(after: Long, limit: Int): Batch? = lock.withLock {
        // A download's writes still open on this connection are committed first: the batch is a transaction of its own.
        val db = db ?: return null
        if (!commitLocked() || waitingBriefly { db.begin() } != SqlResult.OK) return null
        // Freed space is overwritten with zeros. Set inside the transaction: on Android that runs it on the writing
        // connection.
        db.exec("PRAGMA secure_delete=ON")
        val select = statement("SELECT rowid, zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE rowid > ? ORDER BY rowid LIMIT ?")
        val update = statement("UPDATE tiles SET tile_data=? WHERE rowid=?")
        if (select == null || update == null) {
            db.rollback()
            return null
        }
        var rows = 0
        var lastRowID = after
        val plain = ArrayList<PlainTile>()
        var rc: Int
        try {
            select.bindLong(1, after)
            select.bindInt(2, limit)
            rc = select.step()
            while (rc == SqlResult.ROW) {
                rows += 1
                lastRowID = select.columnLong(0)
                val data = select.columnBlob(4) ?: ByteArray(0)
                if (!TileCrypto.isSealed(data)) {
                    val z = select.columnInt(1)
                    plain.add(PlainTile(lastRowID, TileID(z = z, x = select.columnInt(2), y = (1 shl z) - 1 - select.columnInt(3)), data))
                }
                rc = select.step()
            }
        } finally {
            select.reset()
        }
        var ok = rc == SqlResult.DONE
        for (p in plain) {
            if (!ok) break
            val sealed = try {
                TileCrypto.seal(p.data, p.tile)
            } catch (e: TileCrypto.Failure) {
                ok = false
                break
            }
            update.bindBlob(1, sealed)
            update.bindLong(2, p.rowID)
            ok = update.step() == SqlResult.DONE
            update.clearBindings()
            update.reset()
        }
        // None left: every tile is sealed (a download adds them sealed), so the file is marked in the same transaction.
        if (ok && rows == 0) {
            ok = db.exec("INSERT OR REPLACE INTO metadata (name, value) VALUES ('$encryptedKey', '1')") == SqlResult.OK
        }
        if (!ok || db.commit() != SqlResult.OK) {
            db.rollback()
            return null
        }
        Batch(rows, lastRowID)
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

    private fun checkpointLocked(): Boolean {
        val db = db ?: return false
        val committed = commitLocked()
        return db.checkpointTruncate() == SqlResult.OK && committed
    }

    /** Runs `body` with the short busy timeout of `encryptPlainTiles` (`briefBusyTimeout`), then sets the usual one back. */
    private inline fun <T> waitingBriefly(body: () -> T): T {
        val db = db ?: return body()
        db.busyTimeout(briefBusyTimeout)
        try {
            return body()
        } finally {
            db.busyTimeout(busyTimeout)
        }
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

        /** Metadata key set to "1" on a file whose tiles are all sealed: on new files as they are created, and by
         *  `encryptPlainTiles` when it has sealed them all. */
        const val encryptedKey = "satmap_encrypted"

        /** How long a statement waits for another connection's lock before it gives up. */
        private const val busyTimeout = 10_000

        /** The same for `encryptPlainTiles`, which waits holding the lock: briefly, so the store's other calls (the
         *  map's reads, if it runs on the map's store) aren't held up while a download writes through another
         *  connection. */
        private const val briefBusyTimeout = 200

        private val log = Logger.getLogger("tiles")

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
            "INSERT OR IGNORE INTO metadata (name, value) SELECT '$encryptedKey', '1' WHERE NOT EXISTS (SELECT 1 FROM tiles)",
        )

        private fun tmsRow(t: TileID): Int = (1 shl t.z) - 1 - t.y
    }
}
