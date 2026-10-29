package com.jacobusoettle.satmap.ui

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color.WHITE
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.location.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map screen's pieces on their own (UI spec §2.3–2.4; DESIGN.md §8.1): the height pill's spoken value and units,
 *  the coordinates' "Copied", side by side or stacked, the scale bar, the crosshair's and the bar's shadow, the compass
 *  and the hint. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp") // the iPhone of the screenshots, in points
class MapScreenPiecesTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun state(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun description(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

    @Test
    fun theHeightIsAboveSeaLevelAndATapSwitchesMetresAndFeet() {
        var unit by mutableStateOf(HeightUnit.metres)
        compose.setContent {
            SatMapTheme { HeightBar(409.0, unit = unit, id = "tappedHeight", onTap = { unit = unit.next }, online = true) }
        }
        compose.onNodeWithTag("tappedHeight")
            .assertContentDescriptionEquals("Height")
            .assertHasClickAction()
            .assert(SemanticsMatcher("hint") { it.config[SemanticsActions.OnClick].label == "Shows heights in feet" })
        assertEquals("409 m above sea level", state("tappedHeight"))
        assertTrue(state("tappedHeight")!!.contains(" m "))

        compose.onNodeWithTag("tappedHeight").performClick()
        assertEquals(HeightUnit.feet, unit)
        assertEquals("1,342 ft above sea level", state("tappedHeight"))
        assertTrue(state("tappedHeight")!!.contains(" ft "))
        compose.onNodeWithTag("tappedHeight")
            .assert(SemanticsMatcher("hint") { it.config[SemanticsActions.OnClick].label == "Shows heights in metres" })

        compose.onNodeWithTag("tappedHeight").performClick()
        assertTrue(state("tappedHeight")!!.contains(" m "))
    }

    @Test
    fun withoutElevationDataYourHeightIsTheGPSOneMarked() {
        compose.setContent {
            SatMapTheme { HeightBar(null, gpsAltitude = 412.4, unit = HeightUnit.metres, id = "myHeight", onTap = {}, online = true) }
        }
        assertEquals("412 m above sea level, from GPS", state("myHeight"))
    }

    @Test
    fun withNoHeightItSaysLoadingOnlineAndNoDataOffline() {
        var online by mutableStateOf(true)
        compose.setContent {
            SatMapTheme { HeightBar(null, unit = HeightUnit.metres, id = "tappedHeight", onTap = {}, online = online) }
        }
        assertEquals("Loading", state("tappedHeight"))
        online = false
        compose.waitForIdle()
        assertEquals("No elevation data here offline", state("tappedHeight"))
    }

    @Test
    fun theGPSHeightNeedsAVerticalAccuracyWithin25Metres() {
        fun fix(v: Double?) = GpsFix(-33.9, 18.9, 5.0, 400.0, v, 0.0, 0.0)
        assertEquals(400.0, gpsAltitude(fix(25.0))!!, 0.0)
        assertEquals(400.0, gpsAltitude(fix(3.0))!!, 0.0)
        assertEquals(null, gpsAltitude(fix(25.1)))
        assertEquals(null, gpsAltitude(fix(0.0)))
        assertEquals(null, gpsAltitude(fix(-1.0)))
        assertEquals(null, gpsAltitude(fix(null)))
    }

    @Test
    fun aTapCopiesTheCoordinatesAndSaysCopiedFor2Point5Seconds() {
        val text = "-33.916591, 18.920568"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SatMapTheme { CoordinateBar(SatIcons.MapPin, text, id = "tappedCoordinates", onClose = {}) }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Copy $text", description("tappedCoordinates"))
        compose.onNodeWithTag("tappedCoordinates").assertHasClickAction().performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Copied", description("tappedCoordinates"))
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(text, clip.getItemAt(0).text.toString())

        compose.mainClock.advanceTimeBy(2300)
        assertEquals("Copied", description("tappedCoordinates"))
        compose.mainClock.advanceTimeBy(300)
        assertEquals("Copy $text", description("tappedCoordinates"))
    }

    @Test
    fun eachTapEndsItsOwnCopiedAsOnIOS() {
        val text = "-33.921765, 18.923779"
        compose.mainClock.autoAdvance = false
        compose.setContent { SatMapTheme { CoordinateBar(SatIcons.MapPin, text, id = "myCoordinates") } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("myCoordinates").performClick()
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("myCoordinates").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Copied", description("myCoordinates"))
        // The first tap's 2.5 s are up: Swift's asyncAfter from the first tap sets copied back.
        compose.mainClock.advanceTimeBy(600)
        assertEquals("Copy $text", description("myCoordinates"))
    }

    @Test
    fun theCloseButtonHidesTheTappedSpot() {
        var closed = 0
        compose.setContent {
            SatMapTheme { CoordinateBar(SatIcons.MapPin, "1.000000, 2.000000", id = "tappedCoordinates", onClose = { closed++ }) }
        }
        compose.onNodeWithContentDescription("Hide coordinates").assertHasClickAction()
        compose.onNodeWithTag("hideCoordinates").performClick()
        assertEquals(1, closed)
    }

    // Real text widths: the legacy graphics measure every character 1 px wide.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun coordinatesAndHeightSitSideBySideWhenTheyFitElseStacked() {
        var width by mutableStateOf(400.dp)
        compose.setContent {
            SatMapTheme {
                Box(Modifier.width(width)) {
                    CoordinateRow {
                        CoordinateBar(SatIcons.MapPin, "-33.916591, 18.920568", id = "tappedCoordinates")
                        HeightBar(390.0, unit = HeightUnit.metres, id = "tappedHeight", onTap = {}, online = true)
                    }
                }
            }
        }
        var bar = compose.onNodeWithTag("tappedCoordinates").getUnclippedBoundsInRoot()
        var height = compose.onNodeWithTag("tappedHeight").getUnclippedBoundsInRoot()
        assertTrue("side by side", height.left > bar.right)
        assertTrue("centred on one line", height.top < bar.bottom && height.bottom > bar.top)

        width = 200.dp
        compose.waitForIdle()
        bar = compose.onNodeWithTag("tappedCoordinates").getUnclippedBoundsInRoot()
        height = compose.onNodeWithTag("tappedHeight").getUnclippedBoundsInRoot()
        assertTrue("stacked", height.top > bar.bottom)
    }

    @Test
    fun theScaleLabelSwitchesUnitsAndTheBarShowsOnlyWithAWidth() {
        var taps by mutableIntStateOf(0)
        var width by mutableStateOf(0.0)
        compose.setContent { SatMapTheme { ScaleBarView("200 m", width, onTap = { taps++ }) } }
        compose.onNodeWithContentDescription("Scale").assertDoesNotExist()
        width = 53.4
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Scale")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "200 m"))
            .assert(
                SemanticsMatcher("hint") {
                    it.config[SemanticsActions.OnClick].label == "Tap to show distances in other units"
                },
            )
            .performClick()
        assertEquals(1, taps)
    }

    // Real pixels. The shadow is in the bitmap itself, drawn in software, so it shows on every canvas the bitmap is
    // drawn on (a hardware one before Android 9 draws no shadow layer under lines).
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theCrosshairHasABlackEdgeAndNoShadowOnItself() {
        val density = Density(3f)
        val blur = with(density) { ShadowBlur.toPx() }
        val room = shadowRoom(blur)
        val size = Size(168f, 168f) // 56 dp
        val bitmap = shadowedBitmap(size, blur) { with(density) { drawCrosshair(it, size) } }
        assertEquals(168 + 2 * room, bitmap.width)
        assertEquals(168 + 2 * room, bitmap.height)
        fun at(x: Int, y: Int) = bitmap.getPixel(room + x, room + y)

        assertEquals(SystemRed.toArgb(), at(84, 84))
        // The ring 18 dp out, between the arms.
        assertEquals(WHITE, at(122, 46))
        // 1 dp outside the ring: its black edge.
        assertShadow(at(126, 41))
        // The arm just above the red dot: the dot's shadow doesn't fall on it (the shadow of the whole, as on iOS).
        assertEquals(WHITE, at(84, 73))
        // Room for all of the blur: nothing at the bitmap's edges.
        val last = bitmap.width - 1
        val edges = (0..last).flatMap { i -> listOf(0 to i, last to i, i to 0, i to last) }
        assertTrue(edges.all { (x, y) -> alpha(bitmap.getPixel(x, y)) <= 1 })
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theScaleBarHasABlackEdge() {
        val density = Density(3f)
        val blur = with(density) { ShadowBlur.toPx() }
        val room = shadowRoom(blur)
        val size = Size(160f, 18f) // 53.4 × 6 dp
        val bitmap = shadowedBitmap(size, blur) { with(density) { drawScaleBar(it, size) } }
        fun at(x: Int, y: Int) = bitmap.getPixel(room + x, room + y)

        assertEquals(WHITE, at(80, 18))
        assertEquals(WHITE, at(0, 9))
        assertEquals(WHITE, at(160, 9))
        // Below the line and outside the ticks.
        assertShadow(at(80, 25))
        assertShadow(at(-7, 9))
        assertShadow(at(167, 9))
    }

    // The pieces as drawn on the screen, over white (bright imagery): in place, with their black edge round them.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun theCrosshairAndTheScaleBarHaveTheirBlackEdgeOnBrightImagery() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            Column(Modifier.background(Color.White).padding(10.dp)) {
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { Crosshair() }
                ScaleBarShape(53.4)
            }
        }
        compose.waitForIdle()
        val screen = Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888)
        view!!.draw(android.graphics.Canvas(screen))

        // The crosshair's centre at (150, 150) px: 10 dp in, then 12 dp to the 56 dp square, then 28.
        assertEquals(SystemRed.toArgb(), screen.getPixel(150, 150))
        assertGrey(screen.getPixel(192, 108)) // 1 dp outside the ring
        // The scale bar's ⊔ from (30, 270) px, 160 × 18.
        assertGrey(screen.getPixel(110, 295)) // under the line
        assertGrey(screen.getPixel(23, 279)) // left of the left tick
        assertEquals(WHITE, screen.getPixel(280, 300)) // nothing far from both
    }

    /** Darker than the white under it: the shadow. */
    private fun assertGrey(argb: Int) {
        val r = android.graphics.Color.red(argb)
        val grey = r == android.graphics.Color.green(argb) && r == android.graphics.Color.blue(argb)
        assertTrue("grey: ${Integer.toHexString(argb)}", grey && r < 0xE8)
    }

    private fun alpha(argb: Int) = android.graphics.Color.alpha(argb)

    /** Black, partly transparent. */
    private fun assertShadow(argb: Int) {
        assertEquals("black", 0, argb and 0xFFFFFF)
        assertTrue("partly transparent: ${alpha(argb)}", alpha(argb) in 20..230)
    }

    @Test
    fun theCompassShowsOnlyWhileTheMapIsTurned() {
        var bearing by mutableStateOf(0.0)
        var taps = 0
        compose.setContent { SatMapTheme { CompassButton(bearing, onTap = { taps++ }) } }
        compose.onNodeWithTag("compassButton").assertDoesNotExist()
        bearing = 359.6
        compose.waitForIdle()
        compose.onNodeWithTag("compassButton").assertDoesNotExist()
        bearing = 30.0
        compose.waitForIdle()
        compose.onNodeWithTag("compassButton")
            .assertContentDescriptionEquals("Compass")
            .assert(SemanticsMatcher("hint") { it.config[SemanticsActions.OnClick].label == "Point north up" })
            .performClick()
        assertEquals(1, taps)
        bearing = 0.0
        compose.waitForIdle()
        compose.onNodeWithTag("compassButton").assertDoesNotExist()
    }

    @Test
    fun aLocationHintOpensSettingsWhenTapped() {
        var taps = 0
        compose.setContent { SatMapTheme { Hint("Location is off. Turn it on in Settings › Location.", onTap = { taps++ }) } }
        compose.onNode(hasText("Location is off. Turn it on in Settings › Location.") and hasClickAction()).performClick()
        assertEquals(1, taps)
    }
}
