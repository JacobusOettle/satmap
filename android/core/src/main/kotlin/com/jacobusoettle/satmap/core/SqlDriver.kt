package com.jacobusoettle.satmap.core

// The part of SQLite's C API that `MBTiles` uses (the Swift calls it directly), so the tile store runs on any SQLite:
// android.database.sqlite in the app, sqlite-jdbc in the core tests. (No Swift file of its own.)
//
// Calls return SQLite's result codes (`SqlResult`), as the C functions do. Parameter indexes start at 1 and column
// indexes at 0, as in C. Not thread-safe: `MBTiles` serializes its calls with its lock.

/** Opens database files (`sqlite3_open_v2`). */
interface SqlDriver {
    /** Opens the file for reading and writing, creating it if `create` is set. Throws `SqlException` with SQLite's
     *  message if it can't (e.g. the file doesn't exist and `create` is false). The file must stay a plain SQLite
     *  file that other apps can open: no tables of the driver's own (on Android, open it with
     *  `NO_LOCALIZED_COLLATORS`, so no `android_metadata` table is added). */
    fun open(path: String, create: Boolean): SqlDatabase
}

/** An open database (`sqlite3*`). */
interface SqlDatabase {
    /** How long a statement waits for another connection's lock before it gives up with `BUSY`
     *  (`sqlite3_busy_timeout`). */
    fun busyTimeout(milliseconds: Int)

    /** Runs one statement, ignoring any rows it returns (`sqlite3_exec`). */
    fun exec(sql: String): Int

    /** SQLite's message for the last call that failed (`sqlite3_errmsg`). */
    val errorMessage: String

    /** `BEGIN IMMEDIATE`: starts a transaction that holds the write lock. */
    fun begin(): Int

    /** `COMMIT`. */
    fun commit(): Int

    /** `ROLLBACK`. */
    fun rollback(): Int

    /** A prepared statement (`sqlite3_prepare_v2`), or null if the SQL can't be prepared (see `errorMessage`). */
    fun prepare(sql: String): SqlStatement?

    /** Folds the write-ahead log into the database file and truncates it (`sqlite3_wal_checkpoint_v2` with
     *  `SQLITE_CHECKPOINT_TRUNCATE`; `PRAGMA wal_checkpoint(TRUNCATE)` gives the same). `BUSY` if a reader kept it from
     *  finishing. */
    fun checkpointTruncate(): Int

    /** Closes the database (`sqlite3_close`). Its statements must be closed first. */
    fun close()
}

/** A prepared statement (`sqlite3_stmt*`). */
interface SqlStatement {
    fun bindInt(index: Int, value: Int)
    fun bindLong(index: Int, value: Long)
    fun bindDouble(index: Int, value: Double)
    fun bindText(index: Int, value: String)
    fun bindBlob(index: Int, value: ByteArray)
    fun bindNull(index: Int)

    /** `ROW` for each row, then `DONE`; or an error code (`sqlite3_step`). */
    fun step(): Int

    /** Whether the column of the current row is NULL (`sqlite3_column_type` is `SQLITE_NULL`). */
    fun columnIsNull(index: Int): Boolean
    fun columnInt(index: Int): Int
    fun columnLong(index: Int): Long
    fun columnDouble(index: Int): Double

    /** Null for NULL. */
    fun columnText(index: Int): String?

    /** Null for NULL (an empty blob may also come back as null). */
    fun columnBlob(index: Int): ByteArray?

    /** Ready to run again, keeping the bindings (`sqlite3_reset`). */
    fun reset()

    /** Sets every parameter back to NULL (`sqlite3_clear_bindings`). */
    fun clearBindings()

    /** Frees the statement (`sqlite3_finalize`). */
    fun close()
}

/** SQLite's (primary) result codes. */
object SqlResult {
    const val OK = 0
    const val ERROR = 1
    const val BUSY = 5
    const val LOCKED = 6
    const val IOERR = 10
    const val FULL = 13
    const val CANTOPEN = 14
    const val ROW = 100
    const val DONE = 101
}

/** A failure to open a database, with SQLite's result code and message. */
class SqlException(val code: Int, message: String) : Exception(message)
