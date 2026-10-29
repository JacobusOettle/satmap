package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The map screen without a map (MapLibre can't load here): the buttons in iOS's order with iOS's labels, their taps
 *  and long presses, the terrain toast, and the tapped spot (DESIGN.md §4.1, §4.5, §8.1). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp") // the iPhone of the screenshots, in points
class MapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
        compose.setContent { SatMapTheme { MapScreen(model, model.offline, model.recorder, map = null) } }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    private val buttons = listOf(
        "terrainButton" to "Contour lines",
        "blocksButton" to "Blocks",
        "offlineButton" to "Offline maps",
        "goToButton" to "Go to coordinates",
        "measureButton" to "Measure",
        "myLocationButton" to "My location",
    )

    @Test
    fun theButtonsAreIOSsInIOSsOrder() {
        var above = Float.NEGATIVE_INFINITY.dp
        for ((tag, label) in buttons) {
            val node = compose.onNodeWithTag(tag)
                .assertContentDescriptionEquals(label)
                .assertHasClickAction()
                .assertWidthIsEqualTo(50.dp)
                .assertHeightIsEqualTo(50.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            // 50 dp circles, 12 dp apart, one under the other.
            if (above.value.isFinite()) assertEquals(above + 12.dp, bounds.top)
            above = bounds.bottom
        }
        // The Android tests find them by label too.
        compose.onNode(hasContentDescription("My location") and hasClickAction()).assertExists()
        // No map, no compass.
        compose.onNodeWithTag("compassButton").assertDoesNotExist()
    }

    @Test
    fun theTerrainButtonSaysItsModeAndATapMovesOnAndSaysTheNextForAMoment() {
        compose.onNodeWithTag("terrainButton")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Contour lines"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("terrainButton").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(TerrainMode.contoursAndColours, model.terrainMode.value)
        compose.onNodeWithTag("terrainButton").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Contour lines and elevation colours"),
        )
        compose.onNodeWithTag("terrainToast").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        // A second tap starts the 1.8 s again.
        compose.onNodeWithTag("terrainButton").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(TerrainMode.colours, model.terrainMode.value)
        compose.mainClock.advanceTimeBy(1700)
        compose.onNodeWithTag("terrainToast").assertExists()
        // 1.8 s after the last tap it fades (0.35 s).
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("terrainToast").assertDoesNotExist()
    }

    @Test
    fun theSheetButtonsOpenTheirSheets() {
        compose.onNodeWithTag("blocksButton").performClick()
        assertEquals(ActiveSheet.blocks, model.sheet.value)
        compose.onNodeWithTag("offlineButton").performClick()
        assertEquals(ActiveSheet.offline, model.sheet.value)
        compose.onNodeWithTag("goToButton").performClick()
        assertEquals(ActiveSheet.coordinates, model.sheet.value)
    }

    @Test
    fun aLongPressOnMyLocationOffersToClearTheTrackAfterAsking() {
        compose.onNodeWithTag("myLocationButton").assert(
            SemanticsMatcher("long-press label") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Clear recorded track"
            },
        )
        compose.onNodeWithTag("myLocationButton").performTouchInput { longClick() }
        compose.onNodeWithText("Clear recorded track").performClick()
        compose.onNodeWithText("Clear the recorded track?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear the recorded track?").assertDoesNotExist()

        compose.onNodeWithTag("myLocationButton").performTouchInput { longClick() }
        compose.onNodeWithText("Clear recorded track").performClick()
        compose.onNodeWithText("Clear track").performClick()
        compose.onNodeWithText("Clear the recorded track?").assertDoesNotExist()
    }

    @Test
    fun blocksHasNoMenuWithoutFiles() {
        compose.onNodeWithTag("blocksButton").assert(
            SemanticsMatcher("no long press") { it.config.getOrNull(SemanticsActions.OnLongClick) == null },
        )
    }

    @Test
    fun aTappedSpotShowsItsCoordinatesAndHeightAtTheBottomUntilClosed() {
        compose.onNodeWithTag("tappedCoordinates").assertDoesNotExist()
        compose.runOnUiThread { model.setSelectedPoint(GeoPoint(-33.916591, 18.920568)) }
        compose.onNodeWithTag("tappedCoordinates")
            .assertContentDescriptionEquals("Copy -33.916591, 18.920568")
        val height = compose.onNodeWithTag("tappedHeight").fetchSemanticsNode().config
        assertEquals("Height", height[SemanticsProperties.ContentDescription].single())
        val expected = if (Connectivity.shared.isOnline) "Loading" else "No elevation data here offline"
        assertEquals(expected, height[SemanticsProperties.StateDescription])
        // Below the buttons: the controls are lifted to leave it room.
        val spot = compose.onNodeWithTag("tappedCoordinates").getUnclippedBoundsInRoot()
        val myLocation = compose.onNodeWithTag("myLocationButton").getUnclippedBoundsInRoot()
        assertTrue(spot.top > myLocation.bottom)

        compose.onNodeWithTag("hideCoordinates").performClick()
        assertNull(model.selectedPoint.value)
        compose.onNodeWithTag("tappedCoordinates").assertDoesNotExist()
    }
}
