package com.jacobusoettle.satmap.offline

import android.database.sqlite.SQLiteDatabase
import com.jacobusoettle.satmap.core.SqlDatabase
import com.jacobusoettle.satmap.core.SqlDriver
import com.jacobusoettle.satmap.core.SqlException
import com.jacobusoettle.satmap.core.SqlResult

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
 *  can restore. PRAGMAs that return rows (`wal_checkpoint`, `journal_mode`) go through `rawQuery`. */
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
        throw SqlException(SqlResult.CANTOPEN, "Not available yet") // M1 (reading) and M4 (writing): openDatabase(path, null, flags(create)).
    }
}
