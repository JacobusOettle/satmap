package com.jacobusoettle.satmap.core

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteException
import org.sqlite.SQLiteOpenMode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** `SqlDriver` on sqlite-jdbc, for the tests on the JVM (the app has its own on android.database.sqlite). Tests that
 *  open an `MBTiles` set `MBTiles.driver = JdbcSqlDriver` first. */
object JdbcSqlDriver : SqlDriver {
    override fun open(path: String, create: Boolean): SqlDatabase {
        // SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX (| SQLITE_OPEN_CREATE), as MBTiles.swift opens it.
        val config = SQLiteConfig()
        config.resetOpenMode(SQLiteOpenMode.OPEN_URI)
        config.setOpenMode(SQLiteOpenMode.READWRITE)
        config.setOpenMode(SQLiteOpenMode.NOMUTEX)
        if (create) config.setOpenMode(SQLiteOpenMode.CREATE) else config.resetOpenMode(SQLiteOpenMode.CREATE)
        val connection = try {
            config.createConnection("jdbc:sqlite:$path")
        } catch (e: SQLException) {
            throw SqlException(resultCode(e), e.message ?: "")
        }
        return JdbcDatabase(connection as SQLiteConnection)
    }

    /** SQLite's primary result code for a failure. */
    fun resultCode(e: SQLException): Int {
        val code = if (e is SQLiteException) e.resultCode.code else e.errorCode
        return if (code <= 0) SqlResult.ERROR else code and 0xFF
    }
}

/** A connection in autocommit mode: transactions are begun and ended by the SQL statements, as with the C API. */
private class JdbcDatabase(private val connection: SQLiteConnection) : SqlDatabase {
    override var errorMessage = "not an error"
        private set

    /** Runs `body`, giving its result code, or the failure's (whose message is kept for `errorMessage`). */
    fun attempt(body: () -> Int): Int = try {
        body()
    } catch (e: SQLException) {
        failed(e)
    }

    fun failed(e: SQLException): Int {
        errorMessage = e.message ?: "unknown"
        return JdbcSqlDriver.resultCode(e)
    }

    override fun busyTimeout(milliseconds: Int) {
        attempt {
            connection.busyTimeout = milliseconds
            SqlResult.OK
        }
    }

    override fun exec(sql: String): Int = attempt {
        connection.createStatement().use { it.execute(sql) }
        SqlResult.OK
    }

    override fun begin(): Int = exec("BEGIN IMMEDIATE")

    override fun commit(): Int = exec("COMMIT")

    override fun rollback(): Int = exec("ROLLBACK")

    override fun prepare(sql: String): SqlStatement? = try {
        JdbcStatement(this, connection.prepareStatement(sql))
    } catch (e: SQLException) {
        failed(e)
        null
    }

    override fun checkpointTruncate(): Int = attempt {
        // The pragma's first column is 1 when a reader kept the checkpoint from finishing (the C function's BUSY).
        connection.createStatement().use { s ->
            s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                if (rows.next() && rows.getInt(1) != 0) SqlResult.BUSY else SqlResult.OK
            }
        }
    }

    override fun close() {
        attempt {
            connection.close()
            SqlResult.OK
        }
    }
}

/** A prepared statement stepped as in C: the first `step` runs it, a query's rows come one per `step`. */
private class JdbcStatement(private val db: JdbcDatabase, private val statement: PreparedStatement) : SqlStatement {
    private var rows: ResultSet? = null
    private var done = false

    private fun bind(body: () -> Unit) {
        db.attempt {
            body()
            SqlResult.OK
        }
    }

    override fun bindInt(index: Int, value: Int) = bind { statement.setInt(index, value) }
    override fun bindLong(index: Int, value: Long) = bind { statement.setLong(index, value) }
    override fun bindDouble(index: Int, value: Double) = bind { statement.setDouble(index, value) }
    override fun bindText(index: Int, value: String) = bind { statement.setString(index, value) }
    override fun bindBlob(index: Int, value: ByteArray) = bind { statement.setBytes(index, value) }
    override fun bindNull(index: Int) = bind { statement.setNull(index, Types.NULL) }

    override fun step(): Int = db.attempt {
        if (done) return@attempt SqlResult.DONE
        val r = rows ?: if (statement.execute()) statement.resultSet.also { rows = it } else null
        if (r != null && r.next()) {
            SqlResult.ROW
        } else {
            done = true
            SqlResult.DONE
        }
    }

    private fun <T> column(default: T, read: (ResultSet) -> T): T = try {
        rows?.let(read) ?: default
    } catch (e: SQLException) {
        db.failed(e)
        default
    }

    override fun columnIsNull(index: Int): Boolean = column(true) { it.getObject(index + 1) == null }
    override fun columnInt(index: Int): Int = column(0) { it.getInt(index + 1) }
    override fun columnLong(index: Int): Long = column(0L) { it.getLong(index + 1) }
    override fun columnDouble(index: Int): Double = column(0.0) { it.getDouble(index + 1) }
    override fun columnText(index: Int): String? = column(null) { it.getString(index + 1) }
    override fun columnBlob(index: Int): ByteArray? = column(null) { it.getBytes(index + 1) }

    override fun reset() {
        // Closing the rows resets the statement in SQLite.
        db.attempt {
            rows?.close()
            SqlResult.OK
        }
        rows = null
        done = false
    }

    override fun clearBindings() = bind { statement.clearParameters() }

    override fun close() {
        reset()
        db.attempt {
            statement.close()
            SqlResult.OK
        }
    }
}
