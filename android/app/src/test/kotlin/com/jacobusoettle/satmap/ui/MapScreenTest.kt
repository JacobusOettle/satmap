package com.jacobusoettle.satmap.ui

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.map.ContourInterval
import com.jacobusoettle.satmap.map.TerrainKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

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
    fun theContourKeyIsAboveTheScaleBarAndTheColourKeyBelowIt() {
        compose.onNodeWithTag("contourKey").assertDoesNotExist()
        compose.onNodeWithTag("colourKey").assertDoesNotExist()
        compose.runOnUiThread {
            model.scale.update(2.0)
            model.legend.update(TerrainKey(ContourInterval(20.0, 100.0), ElevationRange(300.0, 900.0)))
        }
        // Screenshot 22.
        val contours = compose.onNodeWithTag("contourKey")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "every 20 m, bold every 100 m"))
            .getUnclippedBoundsInRoot()
        val colours = compose.onNodeWithTag("colourKey")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "blue 300 m to red 900 m"))
            .getUnclippedBoundsInRoot()
        val scale = compose.onNodeWithContentDescription("Scale").getUnclippedBoundsInRoot()
        assertTrue(contours.bottom <= scale.top)
        assertTrue(colours.top >= scale.bottom)
        assertEquals(12.dp, contours.left)
        assertEquals(12.dp, colours.left)
        assertEquals(110.dp, colours.right - colours.left)

        // Contours only (and in feet, heights elsewhere: the legends stay in metres, as on iOS).
        compose.runOnUiThread {
            model.nextHeightUnit()
            model.legend.update(TerrainKey(ContourInterval(20.0, 100.0), null))
        }
        compose.onNodeWithTag("colourKey").assertDoesNotExist()
        compose.onNodeWithTag("contourKey")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "every 20 m, bold every 100 m"))
    }

    @Test
    fun blocksOpensInASheetAndDoneClosesIt() {
        compose.onNodeWithTag("blocksButton").performClick()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Blocks")
        compose.onNodeWithTag("importKml").assertContentDescriptionEquals("Import KML or GPX")
        compose.onNode(hasText("Done") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertNull(model.sheet.value)
        compose.onNodeWithTag("screenTitle").assertDoesNotExist()
    }

    @Test
    fun goToCoordinatesOpensWithItsFieldAndGoesWhenTheModelClearsTheSheet() {
        compose.onNodeWithTag("goToButton").performClick()
        compose.onNodeWithTag("screenTitle").assertTextEquals("Go to coordinates")
        compose.onNodeWithTag("coordinateText").assertIsFocused()
        // As `measure(from:)` and the sheet's own buttons do.
        compose.runOnUiThread { model.setSheet(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("screenTitle").assertDoesNotExist()
        compose.onNodeWithTag("coordinateText").assertDoesNotExist()
    }

    @Test
    fun theLocationHintsSayWhyYourPositionIsMissing() {
        val off = "Location is off for SatMap. Turn it on in Settings › Apps › SatMap › Permissions › Location."
        val servicesOff = "Location is off. Turn it on in Settings › Location."
        val approximate = "Precise location is off, so your track can't be recorded. Turn it on in Settings › Apps › " +
            "SatMap › Permissions › Location (Use precise location)."
        // Not asked yet: nothing to say.
        compose.onNodeWithText(off).assertDoesNotExist()
        compose.runOnUiThread {
            Defaults.putBoolean("locationPermissionAsked", true)
            model.recorder.authorizationChanged()
        }
        compose.onNode(hasText(off) and hasClickAction()).assertExists()
        compose.runOnUiThread {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            model.recorder.authorizationChanged()
        }
        compose.onNodeWithText(off).assertDoesNotExist()
        compose.onNode(hasText(approximate) and hasClickAction()).assertExists()
        compose.runOnUiThread {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            model.recorder.authorizationChanged()
        }
        compose.onNodeWithText(approximate).assertDoesNotExist()
        compose.runOnUiThread {
            shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
            model.recorder.authorizationChanged()
        }
        compose.onNode(hasText(servicesOff) and hasClickAction()).assertExists()
    }

    @Test
    fun aLongPressOnBlocksSwitchesFilesAndColoursWithoutTheList() {
        val file = File(app.cacheDir, "Demo farm.kml").apply {
            writeText(
                """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>Launch 1</name>""" +
                    """<Point><coordinates>18.924,-33.9175,0</coordinates></Point></Placemark></Document></kml>""",
            )
        }
        compose.runOnUiThread { model.kml.importFile(Uri.fromFile(file)) }
        // The file is read on another thread; its result comes back through the main looper.
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.kml.files.value.isNotEmpty()
        }
        compose.onNodeWithTag("blocksButton").assert(
            SemanticsMatcher("long-press label") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Show or hide files"
            },
        )
        compose.onNodeWithTag("blocksButton").performTouchInput { longClick() }
        compose.onNodeWithText("Demo farm").performClick()
        assertFalse(model.kml.files.value.single().isShown)
        compose.onNodeWithText("Demo farm").assertDoesNotExist() // the menu closes after a choice

        compose.onNodeWithTag("blocksButton").performTouchInput { longClick() }
        compose.onNodeWithText("Colours from the files").performClick()
        assertFalse(model.kml.useFileColours.value)
        // Nothing opened.
        assertNull(model.sheet.value)
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
