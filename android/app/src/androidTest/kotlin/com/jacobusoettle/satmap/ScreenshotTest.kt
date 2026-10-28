package com.jacobusoettle.satmap

import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The screenshot walkthrough: `SatMapUITests/ScreenshotTests.swift` step for step, with the same snapshot names
 *  (01-map … 27-place-area), waits and asserts (DESIGN.md §8.2). Run by .github/workflows/android-screenshots.yml. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The app opens on the map and keeps running. M1 replaces this with the walkthrough, from step 01-map. */
    @Test
    fun opensOnTheMap() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.waitForIdle()
            Thread.sleep(3000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            snapshot("00-launch")
        }
    }

    private fun snapshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.getExternalFilesDir("screenshots") ?: return
        folder.mkdirs()
        device.takeScreenshot(File(folder, "$name.png"))
    }
}
