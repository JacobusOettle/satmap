package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.DroneReport
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** The map screen's offline and drone pieces (UI spec §2.3.1, §2.3.2, §2.3.10): the download banner, the drawing bar
 *  and the drone card. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenOfflineTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    private val area = OfflineArea(
        id = UUID.randomUUID(),
        name = "Demo farm",
        created = 800_000_000.0,
        shape = CoverageShape(polygons = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.92, 18.93), GeoPoint(-33.93, 18.93)))),
        maxZoom = 22,
        allowCellular = false,
        status = OfflineArea.Status.downloading,
        failedTiles = 0,
        bytesOnDisk = 0,
    )

    @Test
    fun theBannerSaysWhatIsDownloadingAndPauses() {
        var progress by mutableStateOf<DownloadProgress?>(null)
        var pauses = 0
        var taps = 0
        compose.setContent {
            SatMapTheme {
                DownloadBanner(
                    area,
                    progress,
                    onPause = { pauses++ },
                    modifier = Modifier.clickable(interactionSource = null, indication = null) { taps++ },
                )
            }
        }
        compose.onNodeWithText("Downloading Demo farm").assertExists()
        compose.onNodeWithText("Starting…").assertExists()
        progress = DownloadProgress(layer = "Satellite", zoom = 21, maxZoom = 22, levelDone = 1615, levelTotal = 5326, fetched = 3550, bytes = 61_200_000)
        compose.onNodeWithText(
            "Satellite · zoom 21 of up to 22: 1,615/5,326 · 3,550 tiles, ${Format.bytes(61_200_000)} saved",
        ).assertExists()
        val bar = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1615f / 5326f, bar.current, 1e-6f)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, pauses)
        // The pause button's tap isn't the banner's.
        assertEquals(0, taps)
        compose.onNodeWithText("Downloading Demo farm").performClick()
        assertEquals(1, taps)
    }

    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }

    @Test
    fun aTapOnTheBannerOpensOfflineMaps() {
        val model = AppModel(app, noHeights)
        compose.setContent {
            SatMapTheme {
                DownloadBanner(
                    area,
                    null,
                    onPause = {},
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        model.setSheet(ActiveSheet.offline)
                    },
                )
            }
        }
        compose.onNodeWithText("Downloading Demo farm").performClick()
        assertEquals(ActiveSheet.offline, model.sheet.value)
    }

    @Test
    fun theDrawingBarSwitchesBetweenMovingAndDrawingThenAsksAboutTheLoop() {
        var lasso by mutableStateOf(true)
        var drawn by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            SatMapTheme {
                DrawingBar(
                    drawn = drawn,
                    lassoActive = lasso,
                    onLassoActive = { lasso = it; calls.add("lasso $it") },
                    onCancel = { calls.add("cancel") },
                    onRedraw = { calls.add("redraw") },
                    onContinue = { calls.add("continue") },
                )
            }
        }
        compose.onNodeWithText("Draw a loop around the area with one finger.").assertExists()
        assertEquals(true, selected("Draw"))
        assertEquals(false, selected("Move map"))
        compose.onNodeWithText("Move map").performClick()
        compose.onNodeWithText("Pan and zoom to the area, then switch to Draw.").assertExists()
        assertEquals(true, selected("Move map"))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("lasso false", "cancel"), calls)

        drawn = true
        compose.onNodeWithText("Download this area?").assertExists()
        compose.onNodeWithText("Move map").assertDoesNotExist()
        compose.onNode(hasText("Redraw") and hasClickAction()).performClick()
        compose.onNode(hasText("Continue") and hasClickAction()).performClick()
        assertEquals(listOf("lasso false", "cancel", "redraw", "continue"), calls)
    }

    private fun selected(title: String): Boolean? =
        compose.onNode(hasText(title) and hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun theDroneCardHasALinePerPartyAsIOS() {
        var report by mutableStateOf(DroneReport(you = "6.1 NM NE of FASH Stellenbosch"))
        compose.setContent { SatMapTheme { DroneCard(report) } }
        // One element for TalkBack, with iOS's identifier.
        compose.onNodeWithTag("droneCard").assertExists()
        compose.onAllNodesWithText("You").assertCountEquals(1)
        compose.onNodeWithText("Pin").assertDoesNotExist()
        report = DroneReport(you = "6.1 NM NE of FASH Stellenbosch", pin = "8.6 NM E of FASH Stellenbosch")
        val texts = compose.onNodeWithTag("droneCard").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf("You", "6.1 NM NE of FASH Stellenbosch", "Pin", "8.6 NM E of FASH Stellenbosch"), texts)
    }
}
