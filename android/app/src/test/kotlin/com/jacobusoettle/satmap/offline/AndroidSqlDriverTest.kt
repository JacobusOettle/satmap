package com.jacobusoettle.satmap.offline

import android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY
import android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
import android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
import com.jacobusoettle.satmap.core.SqlException
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

/** The open flags of DESIGN.md D17 (the constants are compiled in, so this runs on the plain JVM). */
class AndroidSqlDriverTest {
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
}
