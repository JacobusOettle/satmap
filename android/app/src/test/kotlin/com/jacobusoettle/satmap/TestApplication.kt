package com.jacobusoettle.satmap

import android.app.Application

/** The application under Robolectric (`robolectric.properties`): initialises nothing, so MapLibre's native code is
 *  never loaded. Tests set up what they use (`Defaults.init`, `AppPaths.init`, …) themselves; a test that opens a tile
 *  store sets `MBTiles.driver` (`AndroidSqlDriver()`) first. */
class TestApplication : Application()
