package com.jacobusoettle.satmap.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.app.MeasureMode
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.map.ContourInterval
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The measuring panels' texts in each state, as MapScreen.swift writes them (screenshots 02, 03, 16, 19; UI spec
 *  §2.3.3–2.3.5; DESIGN.md §8.1), their buttons, and the legends beside the scale bar (§2.4.1, §2.4.3). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MeasurePanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val you = GeoPoint(-33.921765, 18.923779)
    private val a = GeoPoint(-33.924524, 18.923242)
    private val b = GeoPoint(-33.924430, 18.922104)
    private val c = GeoPoint(-33.923180, 18.922104)
    private val d = GeoPoint(-33.923180, 18.923489)

    private fun exists(text: String) = compose.onNodeWithText(text).assertExists()

    private fun gone(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    // Aiming

    private var aimYou by mutableStateOf<GeoPoint?>(null)
    private var aimPoint by mutableStateOf<GeoPoint?>(null)
    private var aimElevation by mutableStateOf<Double?>(null)
    private var aimElevationPoint by mutableStateOf<GeoPoint?>(null)
    private var userElevation by mutableStateOf<Double?>(null)
    private var heightUnit by mutableStateOf(HeightUnit.metres)
    private var online by mutableStateOf(true)
    private var stops = 0
    private var unitTaps = 0

    private fun showAimPanel() {
        compose.setContent {
            SatMapTheme {
                AimPanel(
                    aimYou, aimPoint, aimElevation, aimElevationPoint, userElevation, DistanceUnit.metric, heightUnit,
                    online, onNextDistanceUnit = { unitTaps++ }, onStop = { stops++ },
                )
            }
        }
    }

    @Test
    fun aimingWaitsForGPSThenShowsTheDistanceToTheCrosshair() {
        showAimPanel()
        exists("You to the crosshair")
        exists("Waiting for GPS…")
        exists("Height: loading elevation…")
        exists("Move the crosshair onto the spot and tap the map (or the measure button) for point A.")
        // No heights yet: no line with both.
        compose.onNode(hasText("above sea level", substring = true)).assertDoesNotExist()

        aimYou = you
        compose.waitForIdle()
        exists("–")
        aimPoint = a
        compose.waitForIdle()
        exists(Format.distance(Geo.distance(you, a)))
        // A tap on the distance: the next unit.
        compose.onNodeWithText(Format.distance(Geo.distance(you, a)))
            .assert(SemanticsMatcher("hint") { it.config[SemanticsActions.OnClick].label == "Tap to show distances in other units" })
            .performClick()
        assertEquals(1, unitTaps)
        online = false
        compose.waitForIdle()
        exists("Height: no elevation data here offline (download this area)")
    }

    @Test
    fun aimingSaysHowMuchHigherOrLowerTheCrosshairIs() {
        aimYou = you
        aimPoint = a
        aimElevationPoint = a
        aimElevation = 400.0
        userElevation = 409.0
        showAimPanel()
        exists("▼ Crosshair is 9 m lower than you") // screenshot 02
        exists("Crosshair 400 m · You 409 m above sea level")
        aimElevation = 420.0
        compose.waitForIdle()
        exists("▲ Crosshair is 11 m higher than you")
        aimElevation = 409.4
        compose.waitForIdle()
        exists("Crosshair is level with you")
        aimElevation = 400.0
        heightUnit = HeightUnit.feet
        compose.waitForIdle()
        exists("▼ Crosshair is 30 ft lower than you")
        exists("Crosshair 1,312 ft · You 1,342 ft above sea level")
    }

    @Test
    fun theCrossStopsMeasuring() {
        showAimPanel()
        compose.onNodeWithTag("stopMeasuring")
            .assertContentDescriptionEquals("Stop measuring")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, stops)
    }

    // Placed, path and closed

    private var measure by mutableStateOf<MeasureMode>(MeasureMode.Placed(GeoPoint(0.0, 0.0)))
    private var markerDistance by mutableStateOf<Double?>(null)
    private var measuredArea by mutableStateOf<Double?>(null)
    private var markerElevation by mutableStateOf<Double?>(null)
    private var lastElevation by mutableStateOf<Double?>(null)
    private var distanceUnit by mutableStateOf(DistanceUnit.metric)
    private var undos = 0
    private var closes = 0

    private fun showMeasurePanel() {
        compose.setContent {
            SatMapTheme {
                MeasurePanel(
                    measure, markerDistance, measuredArea, markerElevation, lastElevation, userElevation, distanceUnit,
                    heightUnit, online,
                    onNextDistanceUnit = { unitTaps++ },
                    onUndo = { undos++ },
                    onCloseShape = { closes++ },
                    onStop = { stops++ },
                )
            }
        }
    }

    @Test
    fun onePointShowsTheDistanceAndHeightFromYou() {
        measure = MeasureMode.Placed(a)
        showMeasurePanel()
        exists("Waiting for GPS…")
        exists("Height: loading elevation…")
        exists("Move the crosshair to the next spot and tap the map for point B.")
        compose.onNodeWithTag("measuredDistance").assertDoesNotExist()
        compose.onNodeWithText("Undo").assertDoesNotExist()

        // Screenshot 03.
        markerDistance = 361.0
        markerElevation = 400.0
        userElevation = 431.0
        compose.waitForIdle()
        exists("361 m")
        exists("▼ Marker is 31 m lower than you")
        exists("Marker 400 m · You 431 m above sea level")
        markerElevation = 431.4
        compose.waitForIdle()
        exists("Marker is level with you")
        markerElevation = 440.0
        compose.waitForIdle()
        exists("▲ Marker is 9 m higher than you")
        compose.onNodeWithText("361 m").performClick()
        assertEquals(1, unitTaps)
        compose.onNodeWithTag("stopMeasuring").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun twoPointsShowTheDistanceFromAToBAndTheirHeights() {
        // Screenshot 16.
        measure = MeasureMode.Path(listOf(a, b))
        markerDistance = 105.0
        markerElevation = 431.0
        lastElevation = 439.0
        userElevation = 400.0
        showMeasurePanel()
        exists("From A to B")
        compose.onNodeWithTag("measuredDistance").assertTextEquals("105 m")
        exists("▲ B is 8 m higher than A")
        exists("A 431 m · B 439 m above sea level")
        exists("Move the crosshair and tap the map for more points.")
        compose.onNodeWithText("Last leg", substring = true).assertDoesNotExist()
        gone("Close shape")
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        assertEquals(1, undos)

        // A tap on the distance: the next unit (the walkthrough taps it through miles, nautical miles, kilometres).
        compose.onNodeWithTag("measuredDistance").performClick()
        assertEquals(1, unitTaps)
        distanceUnit = DistanceUnit.nautical
        compose.waitForIdle()
        compose.onNodeWithTag("measuredDistance").assertTextEquals("0.057 NM")

        lastElevation = 420.0
        compose.waitForIdle()
        exists("▼ B is 11 m lower than A")
        lastElevation = 431.2
        compose.waitForIdle()
        exists("B is level with A")
        lastElevation = null
        compose.waitForIdle()
        exists("Height: loading elevation…")
        compose.onNode(hasText("above sea level", substring = true)).assertDoesNotExist()
    }

    @Test
    fun threePointsShowTheLastLegAndOfferToCloseTheShape() {
        measure = MeasureMode.Path(listOf(a, b, c))
        markerDistance = Geo.pathLength(listOf(a, b, c))
        markerElevation = 431.0
        showMeasurePanel()
        exists("From A to C · 3 points")
        compose.onNodeWithTag("measuredDistance").assertTextEquals(Format.distance(markerDistance!!))
        exists("Last leg B → C: ${Format.distance(Geo.distance(b, c))}")
        exists("Height: loading elevation…")
        exists("Move the crosshair and tap the map for more points, or tap A to close the shape and see its area.")
        compose.onNode(hasText("Close shape") and hasClickAction()).performClick()
        assertEquals(1, closes)
        online = false
        compose.waitForIdle()
        exists("Height: no elevation data here offline (download this area)")
    }

    @Test
    fun aClosedShapeShowsItsAreaAndPerimeter() {
        // Screenshot 19.
        measure = MeasureMode.Closed(listOf(a, b, c, d))
        measuredArea = 16_171.0
        markerDistance = 513.0
        showMeasurePanel()
        exists("Shape A–D · 4 corners")
        exists("1.6 ha")
        exists("Perimeter 513 m")
        exists("16,171 m²")
        gone("Close shape")
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        assertEquals(1, undos)
        compose.onNodeWithText("Perimeter 513 m").performClick()
        assertEquals(1, unitTaps)
        compose.onNodeWithTag("measuredDistance").assertDoesNotExist()
        compose.onNodeWithText("Move the crosshair", substring = true).assertDoesNotExist()

        // Square metres only under a square kilometre.
        measuredArea = 1_250_000.0
        compose.waitForIdle()
        exists("125 ha")
        compose.onNodeWithText("m²", substring = true).assertDoesNotExist()
        measuredArea = null
        markerDistance = null
        compose.waitForIdle()
        exists("–")
        exists("Perimeter –")
    }

    // The legends

    @Test
    fun theContourKeySaysTheIntervalInMetres() {
        var interval by mutableStateOf<ContourInterval?>(null)
        compose.setContent { SatMapTheme { ContourKeyView(interval) } }
        compose.onNodeWithTag("contourKey").assertDoesNotExist()
        interval = ContourInterval(10.0, 50.0)
        compose.waitForIdle()
        compose.onNodeWithTag("contourKey")
            .assertContentDescriptionEquals("Contour lines")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "every 10 m, bold every 50 m"))
            // The map under it takes the touches.
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun theColourKeySaysWhereTheColoursRunFromAndTo() {
        var range by mutableStateOf<ElevationRange?>(null)
        compose.setContent { SatMapTheme { ColourKeyView(range) } }
        compose.onNodeWithTag("colourKey").assertDoesNotExist()
        range = ElevationRange(300.0, 900.0)
        compose.waitForIdle()
        compose.onNodeWithTag("colourKey")
            .assertContentDescriptionEquals("Elevation colours")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "blue 300 m to red 900 m"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
