package com.jacobusoettle.satmap.offline

import com.jacobusoettle.satmap.core.SqlDatabase
import com.jacobusoettle.satmap.core.SqlDriver
import com.jacobusoettle.satmap.core.SqlException
import com.jacobusoettle.satmap.core.SqlResult

/** Core's `SqlDriver` on `android.database.sqlite`: writers and map readers open with `OPEN_READWRITE or
 *  NO_LOCALIZED_COLLATORS or ENABLE_WRITE_AHEAD_LOGGING` (plus `CREATE_IF_NECESSARY` when creating), so no
 *  `android_metadata` table is added and the file stays in WAL mode for the map to read while a download writes
 *  (DESIGN.md D17). PRAGMAs that return rows go through `rawQuery`. */
class AndroidSqlDriver : SqlDriver {
    override fun open(path: String, create: Boolean): SqlDatabase =
        throw SqlException(SqlResult.CANTOPEN, "Not available yet") // M1 (reading) and M4 (writing): DESIGN.md D17.
}
