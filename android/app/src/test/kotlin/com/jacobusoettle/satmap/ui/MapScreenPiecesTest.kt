package com.jacobusoettle.satmap.ui

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color.WHITE
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.location.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** The map screen's pieces on their own (UI spec §2.3–2.4; DESIGN.md §8.1): the height pill's spoken value and units,
 *  the coordinates' "Copied", side by side or stacked, the scale bar, the crosshair's and the bar's shadow, the sizes of
 *  the buttons' symbols, the pills and the colour key as iOS's, the compass and the hint. */
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
            SatMapTheme { CoordinateBar(SatIcons.MapPin, text, id = "tappedCoordinates", iconSlot = 19.7.dp, onClose = {}) }
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
        compose.setContent { SatMapTheme { CoordinateBar(SatIcons.MapPin, text, id = "myCoordinates", iconSlot = 19.7.dp) } }
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
            SatMapTheme {
                CoordinateBar(SatIcons.MapPin, "1.000000, 2.000000", id = "tappedCoordinates", iconSlot = 19.7.dp, onClose = { closed++ })
            }
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
                        CoordinateBar(SatIcons.MapPin, "-33.916591, 18.920568", id = "tappedCoordinates", iconSlot = 19.7.dp)
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

    // The sizes iOS draws them at (UI spec §1.3, §2.3.7, §2.3.8, §2.4.3, §2.4.5), in real pixels, 3 a dp.

    /** What `content` draws in white over black (the map), 10 dp in from the window's corner, as MapScreen draws it. */
    private fun drawn(content: @Composable () -> Unit): Bitmap {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            SatMapTheme {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.background(Color.Black).padding(10.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
        return Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888)
            .also { view!!.draw(android.graphics.Canvas(it)) }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun lum(argb: Int) =
        0.299f * android.graphics.Color.red(argb) + 0.587f * android.graphics.Color.green(argb) +
            0.114f * android.graphics.Color.blue(argb)

    /** How wide and tall (dp) the pixels `isInk` picks reach, among those `within` takes (px). */
    private fun Bitmap.inkSize(within: (Int, Int) -> Boolean, isInk: (Int) -> Boolean): Pair<Float, Float> {
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (within(x, y) && isInk(getPixel(x, y))) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return (right - left + 1) / 3f to (bottom - top + 1) / 3f
    }

    private fun assertSize(
        what: String,
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
        across: Float = 1f,
        down: Float = 1f,
    ) {
        assertEquals("$what wide", expected.first, actual.first, across)
        assertEquals("$what tall", expected.second, actual.second, down)
    }

    // iOS's symbols at 20 pt semibold, half-way extents in screenshots 01, 02 and 19: mountain.2.fill 35.5 × 18.1,
    // scope 23.4 × 24.1, xmark 15.4 × 16.1, location(.fill) 19.4 × 18.8, and arrow.down.circle.fill a disc 20.1 across,
    // 85 % of it dark within 9 pt of its centre (screenshot 08).
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun theMapButtonsDrawTheirSymbolsAsBigAsIOS() {
        val buttons = listOf(
            Icons.Filled.Landscape to false, Icons.Filled.GpsFixed to true, Icons.Filled.Close to true,
            Icons.Filled.NearMe to false, Icons.Outlined.NearMe to false, SatIcons.ArrowDownCircleFill to true,
        )
        val screen = drawn {
            Column {
                buttons.forEachIndexed { i, (icon, active) ->
                    MapButton(icon, "$i", active = active, onClick = {}, modifier = Modifier.testTag("button$i"))
                }
            }
        }
        fun centre(i: Int) = bounds("button$i").let {
            (it.left + it.right).value * 1.5f to (it.top + it.bottom).value * 1.5f
        }
        fun symbol(i: Int): Pair<Float, Float> {
            val (cx, cy) = centre(i)
            // Inside the 1 dp ring; black on the yellow of an active button, else white.
            return screen.inkSize({ x, y -> hypot(x - cx, y - cy) < 19 * 3 }) {
                if (buttons[i].second) lum(it) < 100 else lum(it) > 128
            }
        }
        // Landscape is wider for its height than mountain.2 (1.83 to 2), so it can't match both.
        assertSize("Landscape", 35.5f to 18.1f, symbol(0), across = 2f, down = 1.5f)
        assertSize("GpsFixed", 23.4f to 24.1f, symbol(1))
        assertSize("Close", 15.4f to 16.1f, symbol(2))
        assertSize("NearMe", 19.4f to 18.8f, symbol(3))
        assertSize("Outlined NearMe", 19.4f to 18.8f, symbol(4))
        assertSize("ArrowDownCircleFill", 20.1f to 20.1f, symbol(5))
        // A disc, not a ring: the arrow cut out of it shows the yellow.
        val (cx, cy) = centre(5)
        var dark = 0
        var all = 0
        for (y in (cy - 27).toInt()..(cy + 27).toInt()) for (x in (cx - 27).toInt()..(cx + 27).toInt()) {
            if (hypot(x - cx, y - cy) > 27) continue
            all++
            if (lum(screen.getPixel(x, y)) < 100) dark++
        }
        assertTrue("dark: $dark of $all", dark > 0.8 * all)
        assertTrue("yellow at the centre", lum(screen.getPixel(cx.toInt(), cy.toInt())) > 180)
    }

    // The pills are as tall as iOS's: the height pill as its text line (mountain.2.fill at caption size is shorter), a
    // coordinate pill as its symbol's frame at body size where that is taller (location.fill 18.7, mappin 19.7).
    // doc.on.doc is 12.1 × 14.7 pt and mountain.2.fill 20.1 × 10.7 (screenshot 15).
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun thePillsAreAsTallAsIOSsAndTheirSymbolsAsBig() {
        val screen = drawn {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("431 m", style = Subheadline.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("line"))
                CoordinateBar(Icons.Filled.NearMe, "-33.921765, 18.923779", id = "mine", iconSlot = 18.7.dp)
                CoordinateBar(SatIcons.MapPin, "-33.916591, 18.920568", id = "pin", iconSlot = 19.7.dp)
                HeightBar(431.0, unit = HeightUnit.metres, id = "height", onTap = {}, online = true)
            }
        }
        val line = bounds("line").height.value
        val px = 1 / 3f
        assertEquals(line + 16, bounds("height").height.value, px)
        // The copy buttons, inside the pills' 8 dp above and below.
        assertEquals(maxOf(18.7f, line), bounds("mine").height.value, px)
        assertEquals(maxOf(19.7f, line), bounds("pin").height.value, px)

        fun inside(left: Dp, right: Dp, top: Dp, bottom: Dp): (Int, Int) -> Boolean =
            { x, y -> x >= left.value * 3 && x < right.value * 3 && y >= top.value * 3 && y < bottom.value * 3 }
        val mine = bounds("mine")
        // The copy symbol ends the copy button; the mountain starts the height pill, 10 dp in.
        val copy = screen.inkSize(inside(mine.right - 16.dp, mine.right, mine.top, mine.bottom)) { lum(it) > 128 }
        assertSize("ContentCopy", 12.1f to 14.7f, copy)
        val height = bounds("height")
        val mountain = screen.inkSize(inside(height.left + 10.dp, height.left + 32.dp, height.top, height.bottom)) {
            lum(it) > 128
        }
        assertSize("Landscape", 20.1f to 10.7f, mountain)
    }

    // SwiftUI's stroke is centred on the bar's edge: half of the 1 dp white line is outside the 110 × 8 bar, so the key
    // is 111 × 9 dp over all with 7 dp of colours inside the line (screenshot 23). `Modifier.border` keeps it inside.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun theColourKeysBorderIsCentredOnTheBarsEdge() {
        val screen = drawn { ColourKeyView(ElevationRange(300.0, 900.0)) }
        fun at(x: Int, y: Int) = lum(screen.getPixel(x, y))
        // The bar from (30, 30) px, 330 × 24: the line reaches 1.5 px either side of its edges.
        val mid = 30 + 165
        assertTrue(at(mid, 27) < 10) // the black round it
        assertTrue(at(mid, 28) in 60f..160f) // half covered
        assertTrue(at(mid, 29) > 200) // outside the bar
        assertTrue(at(mid, 30) > 200)
        assertTrue(at(mid, 30 + 24) > 200)
        assertTrue(at(mid, 30 + 26) < 10)
        assertTrue(at(27, 42) < 10)
        assertTrue(at(29, 42) > 200)
        assertTrue(at(30 + 330, 42) > 200)
        assertTrue(at(30 + 332, 42) < 10)
        // The colours from 1.5 px in: green in the middle.
        val c = screen.getPixel(mid, 33)
        assertTrue(Integer.toHexString(c), android.graphics.Color.green(c) > android.graphics.Color.red(c) + 60)
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
