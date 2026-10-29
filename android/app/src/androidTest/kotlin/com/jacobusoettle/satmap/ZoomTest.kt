package com.jacobusoettle.satmap

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Lets the map zoom and pan itself through the same steps each time (ZoomScript, with the `zoomScript` launch extra:
 *  no gestures), in each terrain mode, while .github/workflows/android-zoomtest.yml records the screen, to see where it
 *  shows empty blocks while tiles load. SatMapUITests/ZoomTests.swift's scripted tests; the workflow runs one test at
 *  a time, each from a fresh install (nothing cached). */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    /** Every permission SatMap asks for, so no system dialog covers the map. */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    // The app moves the map itself through the same steps each time (ZoomScript): no gestures.
    @Test
    fun scriptOff() = scripted("off")

    @Test
    fun scriptContours() = scripted("contours")

    @Test
    fun scriptContoursAndColours() = scripted("contoursAndColours")

    @Test
    fun scriptColours() = scripted("colours")

    @Test
    fun scriptDrone() = scripted("drone")

    private fun scripted(terrain: String) {
        // The start of the screenshot walk's farm road, as on iOS (the workflow lets SatMap take mock positions).
        val located = try {
            TestLocations.setUp()
            TestLocations.set(GeoPoint(-33.918292, 18.921204))
            true
        } catch (e: Exception) {
            Log.w(TAG, "No mock positions (appops … android:mock_location allow?): $e")
            false
        }
        try {
            // `adb shell am start … --es terrainMode <mode> --ez zoomScript true`.
            val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
                .putExtra("terrainMode", terrain)
                .putExtra("zoomScript", true)
            ActivityScenario.launch<MainActivity>(intent).use {
                allowPermissionPrompts()
                val map = waitUntil(30) { mapShown() }
                SystemClock.sleep((20 + 12 * 5 + 6 + 54) * 1000L) // the script's steps, with room for a slow start
                assertTrue("No map", map)
            }
        } finally {
            if (located) TestLocations.tearDown()
        }
    }

    /** iOS `app.maps.firstMatch.exists`: the map is on the screen. */
    private fun mapShown(): Boolean {
        var shown = false
        instrumentation.runOnMainSync { shown = AppModel.shared.map?.isOnScreen == true }
        return shown
    }

    /** Location (and motion) permission prompts come from the system's permission controller (none shows when the rule
     *  above has granted the permissions): up to 5 s for one, as on iOS. */
    private fun allowPermissionPrompts() {
        val controller = Pattern.compile(".*permissioncontroller")
        val buttons = listOf("While using the app", "Only this time", "Allow", "OK").map { label ->
            By.pkg(controller).text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
        }
        var shown: BySelector? = null
        waitUntil(5) {
            shown = buttons.firstOrNull { device.hasObject(it) }
            shown != null
        }
        shown?.let { b ->
            try {
                device.findObject(b)?.click()
            } catch (e: Exception) {
                Log.w(TAG, "Prompt button: $e")
            }
        }
    }

    private fun waitUntil(seconds: Int, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= end) return false
            SystemClock.sleep(250)
        }
    }

    private companion object {
        const val TAG = "ZoomTest"
    }
}
