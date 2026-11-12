package com.jacobusoettle.satmap.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.DroneLinkModel
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.DroneLink
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Drone link on the map screen (android/docs/spec/drone-link.md): its button only in drone mode, the settings sheet
 *  (the disclaimer before the first switch-on, the port, the addresses and QGroundControl's steps), and the panels of
 *  a tapped drone and aircraft with the disclaimer, from the demo replay. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenDroneLinkTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var model: AppModel
    private val link get() = model.droneLink

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        Defaults.prefs.edit().clear().apply()
        model = AppModel(app, object : ElevationSource {
            override suspend fun elevation(p: GeoPoint): Double? = 130.0
        })
        link.interfaceAddresses = { listOf("wlan0" to "192.168.1.40", "ap0" to "192.168.43.1") }
        compose.setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder, map = null) } }
    }

    @After
    fun tearDown() {
        link.useReplayForTests(null)
        link.setEnabled(false)
        link.backgrounded()
        AppLifecycle.resetForTests()
        Defaults.prefs.edit().clear().apply()
    }

    private fun run(seconds: Double) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((seconds * 1000).toLong()))
        compose.waitForIdle()
    }

    @Test
    fun theButtonShowsOnlyInDroneModeAndOpensTheSettings() {
        compose.onNodeWithTag("droneLinkButton").assertDoesNotExist()
        model.setTerrainMode(TerrainMode.drone)
        compose.waitForIdle()
        compose.onNodeWithText("Drone link", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("droneLinkButton").performClick()
        compose.waitForIdle()
        assertEquals(ActiveSheet.droneLink, model.sheet.value)
        compose.onNode(hasTestTag("screenTitle") and hasText("Drone link"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun switchingOnAsksTheDisclaimerFirstAndOnlyOnce() {
        link.foregrounded()
        model.setSheet(ActiveSheet.droneLink)
        compose.waitForIdle()
        compose.onNodeWithTag("droneLinkSwitch").assertIsOff().performClick()
        compose.onNodeWithTag("droneLinkDisclaimerAlert").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse(link.isEnabled.value)
        compose.onNodeWithTag("droneLinkSwitch").assertIsOff().performClick()
        compose.onNodeWithTag("droneLinkAccept").performClick()
        compose.waitForIdle()
        assertTrue(link.isEnabled.value)
        compose.onNodeWithTag("droneLinkSwitch").assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(link.isEnabled.value)
        compose.onNodeWithText("Drone link is off", useUnmergedTree = true).assertExists()
        // Asked once: on again at once.
        compose.onNodeWithTag("droneLinkSwitch").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("droneLinkDisclaimerAlert").assertDoesNotExist()
        assertTrue(link.isEnabled.value)
    }

    @Test
    fun theSettingsShowTheAddressesThePortAndQGroundControlsSteps() {
        model.setSheet(ActiveSheet.droneLink)
        compose.waitForIdle()
        compose.onNodeWithText("192.168.1.40:14445", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("192.168.43.1:14445", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("droneLinkPort").performTextReplacement("80")
        compose.waitForIdle()
        assertEquals("below 1024: not taken", 14445, link.port.value)
        compose.onNodeWithText("Use a port from 1024 to 65535.").assertExists()
        compose.onNodeWithTag("droneLinkPort").performTextReplacement("14550")
        compose.waitForIdle()
        assertEquals(14550, link.port.value)
        compose.onNodeWithText("192.168.1.40:14550", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("droneLinkQGC"))
        compose.onNodeWithText(
            "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name 192.168.1.40:14550, restart QGC",
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag("droneLinkCopySteps").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copied", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("droneLinkDisclaimer"))
        compose.onNodeWithText(DroneLink.disclaimer, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aTappedDroneAndAircraftShowTheirPanels() {
        val replay = DroneLink.Replay.parse(File(System.getProperty("satmap.fixtures")!!, "../../../docs/mavlink/demo_flight.json").readText())
        link.useReplayForTests(replay)
        link.foregrounded()
        link.setEnabled(true)
        link.acceptDisclaimer()
        model.setTerrainMode(TerrainMode.drone)
        run(20.0)
        compose.onNodeWithText("Receiving from 1 drone · 3 aircraft", useUnmergedTree = true).assertExists()
        link.select(DroneLinkModel.Selection.Drone(1))
        run(1.0)
        compose.onNodeWithTag("droneLinkPanel").assertExists()
        compose.onNodeWithText("Drone 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Armed", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("≈ above ground", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("3D fix · 17 satellites", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("droneLinkPanelDisclaimer").assertExists()
        // The helicopter: low (2,050 ft over the drone's 380 m take-off ground) and close (2.9 NM) to it.
        link.select(DroneLinkModel.Selection.Aircraft(0x00C5D6))
        run(0.5)
        compose.onNodeWithText("ZSHMB", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Below 2,000 ft within 5 NM", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("From Drone 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("droneLinkPanelClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("droneLinkPanel").assertDoesNotExist()
        // Not outside drone mode.
        link.select(DroneLinkModel.Selection.Drone(1))
        model.setTerrainMode(TerrainMode.contours)
        compose.waitForIdle()
        compose.onNodeWithTag("droneLinkPanel").assertDoesNotExist()
        compose.onNodeWithTag("droneLinkButton").assertDoesNotExist()
    }
}
