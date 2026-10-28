package com.jacobusoettle.satmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** The zoom test: launches with `--ez zoomScript true` in each terrain mode while the screen is recorded
 *  (.github/workflows/android-zoomtest.yml), as iOS's zoom test. */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    @Ignore("M5: ZoomScript runs from M5")
    @Test
    fun zoomsInEachTerrainMode() {
        // M5: launch MainActivity with zoomScript = true and each terrainMode, wait for the script's 13 steps.
    }
}
