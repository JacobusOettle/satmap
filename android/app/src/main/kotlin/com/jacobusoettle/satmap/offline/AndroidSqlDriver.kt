package com.jacobusoettle.satmap.offline

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteAbortException
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDatatypeMismatchException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteStatement
import android.database.sqlite.SQLiteTableLockedException
import android.os.Build
import com.jacobusoettle.satmap.core.SqlDatabase
import com.jacobusoettle.satmap.core.SqlDriver
import com.jacobusoettle.satmap.core.SqlException
import com.jacobusoettle.satmap.core.SqlResult
import com.jacobusoettle.satmap.core.SqlStatement
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Core's `SqlDriver` on `android.database.sqlite`, in one of two modes (DESIGN.md D17):
 *
 *  - **Writers and map readers** (the default, set as `MBTiles.driver`): `OPEN_READWRITE or NO_LOCALIZED_COLLATORS or
 *    ENABLE_WRITE_AHEAD_LOGGING`, plus `CREATE_IF_NECESSARY` when creating. Without the WAL flag Android may set the
 *    file's journal mode back to its own default when it opens it, and the map could then no longer read while a
 *    download writes.
 *  - **One-off readers** (`readOnly = true`, e.g. `OfflineManager.savedImageryDepth`): `OPEN_READONLY or
 *    NO_LOCALIZED_COLLATORS`. Core's `SqlDriver.open` has no read-only option, hence the mode here. Such a driver
 *    never creates a file.
 *
 *  `NO_LOCALIZED_COLLATORS` in both, so no `android_metadata` table is added and the file stays one the iPhone app
 *  can restore. PRAGMAs that return rows (`wal_checkpoint`, `journal_mode`) go through `rawQuery`.
 *
 *  SQLite's C API, which core is written against, ties a transaction to its connection; Android ties it to the thread
 *  that began it (and a write from another thread waits for that thread's connection for ever). A tile store is used
 *  from whichever thread a download's coroutine resumes on, so each database runs every call on a thread of its own,
 *  and the caller waits for the answer, as it would for the C function. */
class AndroidSqlDriver(private val readOnly: Boolean = false) : SqlDriver {
    /** The `SQLiteDatabase.openDatabase` flags for this mode. */
    fun flags(create: Boolean): Int =
        if (readOnly) {
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        } else {
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        }

    override fun open(path: String, create: Boolean): SqlDatabase {
        if (readOnly && create) throw SqlException(SqlResult.CANTOPEN, "A read-only database can't be created")
        val thread = SqlThread(File(path).name)
        val db = try {
            // Android's default error handler deletes a database it finds damaged: never the user's maps.
            thread.call { SQLiteDatabase.openDatabase(path, null, flags(create), keepDamagedFiles) }
        } catch (e: SQLiteException) {
            thread.shutdown()
            val code = resultCode(e)
            // sqlite3_open_v2 reads nothing, so a file that isn't a database (or is damaged) opens, and each statement
            // on it fails with SQLite's message. Android reads the file as it opens it: do as the C API does.
            if (code == CORRUPT || code == NOTADB) return DamagedDatabase(code, message(e))
            throw SqlException(code, message(e))
        }
        return AndroidDatabase(db, thread)
    }

    companion object {
        // SQLite's primary result codes that `SqlResult` doesn't name.
        private const val PERM = 3
        private const val ABORT = 4
        private const val NOMEM = 7
        private const val READONLY = 8
        private const val CORRUPT = 11
        private const val TOOBIG = 18
        private const val CONSTRAINT = 19
        private const val MISMATCH = 20
        private const val MISUSE = 21
        private const val RANGE = 25
        private const val NOTADB = 26

        private val keepDamagedFiles = DatabaseErrorHandler { }

        /** Android's message ends with " (code 13 SQLITE_FULL)" and what it was doing. */
        private val codeInMessage = Regex("""\(code (\d+)""")

        /** SQLite's primary result code for a failure: from Android's message, else from the exception's class. */
        fun resultCode(e: Exception): Int {
            e.message?.let { m -> codeInMessage.find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { return it and 0xFF } }
            return when (e) {
                is SQLiteFullException -> SqlResult.FULL
                is SQLiteDatabaseLockedException -> SqlResult.BUSY
                is SQLiteTableLockedException -> SqlResult.LOCKED
                is SQLiteDiskIOException -> SqlResult.IOERR
                is SQLiteCantOpenDatabaseException -> SqlResult.CANTOPEN
                is SQLiteDatabaseCorruptException -> CORRUPT
                is SQLiteConstraintException -> CONSTRAINT
                is SQLiteReadOnlyDatabaseException -> READONLY
                is SQLiteAccessPermException -> PERM
                is SQLiteAbortException -> ABORT
                is SQLiteOutOfMemoryException -> NOMEM
                is SQLiteBlobTooBigException -> TOOBIG
                is SQLiteDatatypeMismatchException -> MISMATCH
                is SQLiteBindOrColumnIndexOutOfRangeException, is IllegalArgumentException -> RANGE
                is SQLiteMisuseException, is IllegalStateException -> MISUSE
                else -> SqlResult.ERROR
            }
        }

        /** SQLite's own message (`sqlite3_errmsg`), without what Android adds. */
        fun message(e: Exception): String =
            e.message?.substringBefore(" (code ")?.trim()?.ifEmpty { null } ?: "unknown error"

        /** Statements whose rows are read with a cursor; the others are executed. PRAGMAs go this way whether they
         *  return a row or not (Android refuses to execute one that does). */
        fun returnsRows(sql: String): Boolean = when (DatabaseUtils.getSqlStatementType(sql)) {
            DatabaseUtils.STATEMENT_SELECT, DatabaseUtils.STATEMENT_PRAGMA -> true
            else -> false
        }
    }
}

/** The thread one database's calls run on (see `AndroidSqlDriver`). */
private class SqlThread(name: String) {
    @Volatile
    private var thread: Thread? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SatMap SQLite $name").apply { isDaemon = true }
    }

    /** Runs `body` on the thread and waits for it, as for a C function (an interrupt doesn't cut the wait short). */
    fun <T> call(body: () -> T): T {
        if (Thread.currentThread() === thread) return body()
        val future = executor.submit(Callable {
            thread = Thread.currentThread()
            body()
        })
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}

/** An open database (`sqlite3*`). Every call runs on its `SqlThread`. */
private class AndroidDatabase(private val db: SQLiteDatabase, private val thread: SqlThread) : SqlDatabase {
    override var errorMessage = "not an error"
        private set

    /** Runs `body` on the database's thread, giving its result code, or the failure's (whose message is kept for
     *  `errorMessage`). */
    fun call(body: () -> Int): Int = thread.call {
        try {
            body()
        } catch (e: SQLiteException) {
            failed(e)
        } catch (e: IllegalStateException) {
            failed(e)
        } catch (e: IllegalArgumentException) {
            failed(e)
        }
    }

    fun failed(e: Exception): Int {
        errorMessage = AndroidSqlDriver.message(e)
        return AndroidSqlDriver.resultCode(e)
    }

    fun failed(code: Int, message: String): Int {
        errorMessage = message
        return code
    }

    fun <T> onThread(body: () -> T): T = thread.call(body)

    /** Android gives each of its connections a 2.5 s timeout as it opens them; this sets every connection's, from
     *  API 30. Before, it reaches the connection the pragma runs on. */
    override fun busyTimeout(milliseconds: Int) {
        call {
            if (Build.VERSION.SDK_INT >= 30) {
                db.execPerConnectionSQL("PRAGMA busy_timeout=$milliseconds", null)
            } else {
                db.rawQuery("PRAGMA busy_timeout=$milliseconds", null).use { it.moveToFirst() }
            }
            SqlResult.OK
        }
    }

    override fun exec(sql: String): Int = call {
        if (AndroidSqlDriver.returnsRows(sql)) {
            db.rawQuery(sql, null).use { it.moveToFirst() }
        } else {
            db.execSQL(sql)
        }
        SqlResult.OK
    }

    /** `BEGIN IMMEDIATE`. */
    override fun begin(): Int = call {
        if (db.inTransaction()) return@call failed(SqlResult.ERROR, "cannot start a transaction within a transaction")
        db.beginTransactionNonExclusive()
        SqlResult.OK
    }

    override fun commit(): Int = call {
        if (!db.inTransaction()) return@call failed(SqlResult.ERROR, "cannot commit - no transaction is active")
        db.setTransactionSuccessful()
        db.endTransaction()
        SqlResult.OK
    }

    override fun rollback(): Int = call {
        if (!db.inTransaction()) return@call failed(SqlResult.ERROR, "cannot rollback - no transaction is active")
        db.endTransaction()
        SqlResult.OK
    }

    override fun prepare(sql: String): SqlStatement? = thread.call {
        // Compiling checks the SQL as sqlite3_prepare_v2 does. A statement with rows runs as a query when stepped.
        val compiled = try {
            db.compileStatement(sql)
        } catch (e: SQLiteException) {
            failed(e)
            return@call null
        }
        if (AndroidSqlDriver.returnsRows(sql)) {
            compiled.close()
            AndroidStatement(this, db, sql, null)
        } else {
            AndroidStatement(this, db, sql, compiled)
        }
    }

    override fun checkpointTruncate(): Int = call {
        // The pragma's first column is 1 when a reader kept the checkpoint from finishing (the C function's BUSY).
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) != 0) failed(SqlResult.BUSY, "database is locked") else SqlResult.OK
        }
    }

    override fun close() {
        try {
            thread.call { db.close() }
        } finally {
            thread.shutdown()
        }
    }
}

/** A prepared statement (`sqlite3_stmt*`), stepped as in C. The bindings are kept here and applied when it runs: to a
 *  query's `SQLiteQuery` as it is created (with their types, which `rawQuery` would turn into text), or to the compiled
 *  statement. The current row is in the cursor's window, so its columns are read on the calling thread. */
private class AndroidStatement(
    private val owner: AndroidDatabase,
    private val db: SQLiteDatabase,
    private val sql: String,
    /** Null for a statement with rows. */
    private val compiled: SQLiteStatement?,
) : SqlStatement {
    /** Parameter i at [i - 1]: Long, Double, String, ByteArray or null. */
    private val args = ArrayList<Any?>()
    private var cursor: Cursor? = null
    private var done = false

    private fun bind(index: Int, value: Any?) {
        while (args.size < index) args.add(null)
        args[index - 1] = value
    }

    override fun bindInt(index: Int, value: Int) = bind(index, value.toLong())
    override fun bindLong(index: Int, value: Long) = bind(index, value)
    override fun bindDouble(index: Int, value: Double) = bind(index, value)
    override fun bindText(index: Int, value: String) = bind(index, value)
    override fun bindBlob(index: Int, value: ByteArray) = bind(index, value)
    override fun bindNull(index: Int) = bind(index, null)

    private fun applyTo(p: SQLiteProgram) {
        p.clearBindings()
        for ((i, v) in args.withIndex()) {
            when (v) {
                null -> p.bindNull(i + 1)
                is Long -> p.bindLong(i + 1, v)
                is Double -> p.bindDouble(i + 1, v)
                is String -> p.bindString(i + 1, v)
                is ByteArray -> p.bindBlob(i + 1, v)
            }
        }
    }

    override fun step(): Int = owner.call {
        if (done) return@call SqlResult.DONE
        if (compiled == null) {
            val c = cursor ?: db.rawQueryWithFactory({ _, driver, editTable, query ->
                applyTo(query)
                SQLiteCursor(driver, editTable, query)
            }, sql, null, "").also { cursor = it }
            if (c.moveToNext()) return@call SqlResult.ROW
        } else {
            applyTo(compiled)
            compiled.execute()
        }
        done = true
        SqlResult.DONE
    }

    private fun <T> column(default: T, read: (Cursor) -> T): T {
        val c = cursor ?: return default
        return try {
            if (c.isBeforeFirst || c.isAfterLast) default else read(c)
        } catch (e: RuntimeException) {
            owner.failed(e)
            default
        }
    }

    override fun columnIsNull(index: Int): Boolean = column(true) { it.isNull(index) }
    override fun columnInt(index: Int): Int = column(0) { it.getInt(index) }
    override fun columnLong(index: Int): Long = column(0L) { it.getLong(index) }
    override fun columnDouble(index: Int): Double = column(0.0) { it.getDouble(index) }
    override fun columnText(index: Int): String? = column(null) { if (it.isNull(index)) null else it.getString(index) }
    override fun columnBlob(index: Int): ByteArray? = column(null) { if (it.isNull(index)) null else it.getBlob(index) }

    override fun reset() {
        val c = cursor
        cursor = null
        done = false
        if (c != null) owner.onThread { c.close() }
    }

    override fun clearBindings() {
        args.clear()
    }

    override fun close() {
        reset()
        compiled?.let { s -> owner.onThread { s.close() } }
    }
}

/** A file that opened but isn't a database, or is damaged: every call fails with SQLite's code and message, as each
 *  statement on it does in C. */
private class DamagedDatabase(private val code: Int, override val errorMessage: String) : SqlDatabase {
    override fun busyTimeout(milliseconds: Int) {}
    override fun exec(sql: String): Int = code
    override fun begin(): Int = code
    override fun commit(): Int = code
    override fun rollback(): Int = code
    override fun prepare(sql: String): SqlStatement? = null
    override fun checkpointTruncate(): Int = code
    override fun close() {}
}
