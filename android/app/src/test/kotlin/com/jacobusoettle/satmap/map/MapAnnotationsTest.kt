package com.jacobusoettle.satmap.map

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Point
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Your dot on the map: which images it uses (blue, grey while the GPS sleeps), the compass arrow's heading and when it
 *  glides, and the images themselves as iOS's `UserDotView` draws them. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapAnnotationsTest {
    private val here = GeoPoint(-33.921765, 18.923779)

    @Test
    fun theDotIsBlueWhileTheGpsIsAwakeAndGreyWhileItSleeps() {
        val awake = UserDotLayer.feature(here, sleeping = false, heading = null)
        assertEquals(MapImages.USER_DOT, awake.getStringProperty("dot"))
        assertEquals(MapImages.USER_ARROW, awake.getStringProperty("arrow"))
        val asleep = UserDotLayer.feature(here, sleeping = true, heading = null)
        assertEquals(MapImages.USER_DOT_GREY, asleep.getStringProperty("dot"))
        assertEquals(MapImages.USER_ARROW_GREY, asleep.getStringProperty("arrow"))
        val p = awake.geometry() as Point
        assertEquals(here.lon, p.longitude(), 0.0)
        assertEquals(here.lat, p.latitude(), 0.0)
    }

    @Test
    fun theArrowShowsOnlyWithAHeadingFromNorth() {
        assertFalse(UserDotLayer.feature(here, false, null).hasProperty("heading"))
        assertEquals(0.0, UserDotLayer.feature(here, false, 0.0).getNumberProperty("heading").toDouble(), 0.0)
        assertEquals(10.0, UserDotLayer.feature(here, false, 370.0).getNumberProperty("heading").toDouble(), 1e-12)
        assertEquals(330.0, UserDotLayer.feature(here, true, -30.0).getNumberProperty("heading").toDouble(), 1e-12)
    }

    @Test
    fun smallTurnsGlideAndLargeOnesOrTheArrowAppearingJump() {
        assertTrue(UserDotLayer.glides(10.0, 50.0, showing = true))
        assertTrue(UserDotLayer.glides(10.0, 55.0, showing = true)) // 45°
        assertFalse(UserDotLayer.glides(10.0, 56.0, showing = true))
        assertTrue(UserDotLayer.glides(350.0, 20.0, showing = true)) // 30° across north
        assertTrue(UserDotLayer.glides(20.0, 350.0, showing = true))
        assertFalse(UserDotLayer.glides(0.0, 180.0, showing = true))
        assertFalse(UserDotLayer.glides(null, 20.0, showing = true)) // appearing
        assertFalse(UserDotLayer.glides(10.0, 20.0, showing = false))
    }

    @Test
    fun theArrowTurnsTheShortestWayRound() {
        assertEquals(20.0, UserDotLayer.turn(350.0, 10.0), 1e-12)
        assertEquals(-20.0, UserDotLayer.turn(10.0, 350.0), 1e-12)
        assertEquals(40.0, UserDotLayer.turn(10.0, 50.0), 1e-12)
        assertEquals(-40.0, UserDotLayer.turn(50.0, 370.0), 1e-12)
        assertEquals(180.0, kotlin.math.abs(UserDotLayer.turn(0.0, 180.0)), 1e-12)
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun theImagesAreDrawnAtTheMapsDensity() {
        val images = MapImages.base(ApplicationProvider.getApplicationContext())
        assertEquals(
            setOf(
                MapImages.USER_DOT, MapImages.USER_DOT_GREY, MapImages.USER_ARROW, MapImages.USER_ARROW_GREY,
                MapImages.MEASURE_MARKER, MapImages.SELECTED,
            ),
            images.keys,
        )
        assertEquals(60, images.getValue(MapImages.MEASURE_MARKER).width) // 30 dp
        assertEquals(56, images.getValue(MapImages.SELECTED).height) // 28 dp: 22 and the shadow
        for (id in listOf(MapImages.USER_DOT, MapImages.USER_DOT_GREY)) {
            val b = images.getValue(id)
            assertEquals(56, b.width) // 28 dp at 2 px per dp
            assertEquals(56, b.height)
            assertEquals(320, b.density) // so MapLibre shows it 28 dp across
        }
        for (id in listOf(MapImages.USER_ARROW, MapImages.USER_ARROW_GREY)) {
            val b = images.getValue(id)
            assertEquals(128, b.width)
            assertEquals(128, b.height)
            assertEquals(320, b.density)
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun theDotIsItsColourInsideAWhiteRingAndTheArrowPointsNorth() {
        val images = MapImages.base(ApplicationProvider.getApplicationContext())
        val d = 2f // px per dp
        fun pixel(id: String, dx: Float, dy: Float): Int {
            val b = images.getValue(id)
            return b.getPixel((b.width / 2f + dx * d).roundToInt(), (b.height / 2f + dy * d).roundToInt())
        }
        // The colour fills 8 dp around the centre; the white ring is 8–11 dp out.
        assertEquals(MapColours.systemBlue, pixel(MapImages.USER_DOT, 0f, 0f))
        assertEquals(MapColours.systemBlue, pixel(MapImages.USER_DOT, 5f, 0f))
        assertEquals(MapColours.systemGray, pixel(MapImages.USER_DOT_GREY, 0f, -5f))
        assertEquals(Color.WHITE, pixel(MapImages.USER_DOT, 9.5f, 0f))
        assertEquals(Color.WHITE, pixel(MapImages.USER_DOT_GREY, 0f, -9.5f))
        // Outside the ring only the shadow: black, partly transparent.
        val shadow = pixel(MapImages.USER_DOT, 12f, 0f)
        assertTrue(Color.alpha(shadow) in 1..0x65)
        assertEquals(0, Color.red(shadow))
        // The arrow: filled between 13.5 and 25.5 dp north of the dot's centre, nothing around the dot itself.
        assertEquals(MapColours.systemBlue, pixel(MapImages.USER_ARROW, 0f, -17.5f))
        assertEquals(MapColours.systemGray, pixel(MapImages.USER_ARROW_GREY, 0f, -17.5f))
        assertEquals(Color.WHITE, pixel(MapImages.USER_ARROW, 0f, -25.5f)) // the tip's outline
        assertEquals(0, Color.alpha(pixel(MapImages.USER_ARROW, 0f, 0f)))
        assertEquals(0, Color.alpha(pixel(MapImages.USER_ARROW, 0f, 17.5f)))
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun theMarkerIsAPinkRingWithAWhiteCentreAndThePinAWhiteRing() {
        val images = MapImages.base(ApplicationProvider.getApplicationContext())
        val d = 2f
        fun pixel(id: String, dx: Float, dy: Float): Int {
            val b = images.getValue(id)
            return b.getPixel((b.width / 2f + dx * d).roundToInt(), (b.height / 2f + dy * d).roundToInt())
        }
        // The marker: white within 3 dp, pink 25 % inside the ring (10.5 dp), the ring pink from 10.5 to 13.5 dp.
        assertEquals(Color.WHITE, pixel(MapImages.MEASURE_MARKER, 0f, 0f))
        assertEquals(Color.WHITE, pixel(MapImages.MEASURE_MARKER, 2f, 0f))
        assertEquals(MapColours.measurePink, pixel(MapImages.MEASURE_MARKER, 12f, 0f))
        assertEquals(MapColours.measurePink, pixel(MapImages.MEASURE_MARKER, 0f, -12.5f))
        val inside = pixel(MapImages.MEASURE_MARKER, 7f, 0f)
        assertEquals(0x40, Color.alpha(inside))
        assertEquals(0, Color.alpha(pixel(MapImages.MEASURE_MARKER, 14.5f, 0f)))
        // The pin: a white centre of radius 2, black 25 % inside, white from 7.5 to 10.5 dp.
        assertEquals(Color.WHITE, pixel(MapImages.SELECTED, 0f, 0f))
        assertEquals(Color.WHITE, pixel(MapImages.SELECTED, 9f, 0f))
        assertEquals(Color.WHITE, pixel(MapImages.SELECTED, 0f, 9f))
        // Black 25 % over its own faint shadow (iOS: the shadow at half the fill's alpha), about 34 %.
        val dark = pixel(MapImages.SELECTED, 5f, 0f)
        assertEquals(0, Color.red(dark))
        assertTrue(Color.alpha(dark) in 0x50..0x70)
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun aMeasuringLetterIs34By18AndWhiteWithItsShadow() {
        val m = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics
        val b = MapImages.measureLetter(m, "A")
        assertEquals(68, b.width)
        assertEquals(36, b.height)
        assertEquals(320, b.density)
        // Left aligned: the letter is in the left part, nothing at the right.
        val columns = (0 until b.width).filter { x -> (0 until b.height).any { y -> Color.alpha(b.getPixel(x, y)) > 0 } }
        assertTrue(columns.first() < 4)
        assertTrue(columns.last() < 34)
        val white = (0 until b.width).sumOf { x -> (0 until b.height).count { y -> b.getPixel(x, y) == Color.WHITE } }
        assertTrue(white > 20)
        assertEquals("measure-letter/AB", MapImages.letterId("AB"))
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun kmlLabelsAreTheirTextsFrameWithTheDotOnTopForPoints() {
        val m = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics
        val name = MapImages.kmlLabel(m, BlockLayers.Label(here, "A1 Shiraz", isPoint = false))
        // Whole dp, as UILabel.sizeToFit, and 1 dp more in height for the shadow.
        val nameText = MapImages.textPaint(13 * 2f, MapImages.SEMIBOLD)
        val nameMetrics = nameText.fontMetrics
        assertEquals(kotlin.math.ceil(nameText.measureText("A1 Shiraz") / 2), name.width, 0f)
        assertEquals(kotlin.math.ceil((nameMetrics.descent - nameMetrics.ascent) / 2) + 1, name.height, 0f)
        assertEquals((name.width * 2).roundToInt(), name.bitmap.width)
        assertEquals((name.height * 2).roundToInt(), name.bitmap.height)
        val point = MapImages.kmlLabel(m, BlockLayers.Label(here, "Launch 1", isPoint = true, colour = Color.RED))
        val pointText = MapImages.textPaint(15 * 2f, MapImages.BOLD).fontMetrics
        assertEquals(12 + kotlin.math.ceil((pointText.descent - pointText.ascent) / 2) + 1, point.height, 0f)
        // The dot: its colour in the middle at the top, 5 dp down, with a black border.
        val b = point.bitmap
        assertEquals(Color.RED, b.getPixel(b.width / 2, 10))
        assertEquals(Color.BLACK, b.getPixel(b.width / 2, 1))
        // No name: just the dot, 10 dp across.
        val dot = MapImages.kmlLabel(m, BlockLayers.Label(here, "", isPoint = true))
        assertEquals(10f, dot.width)
        assertEquals(13f, dot.height)
        assertEquals(Color.WHITE, dot.bitmap.getPixel(10, 10))
    }
}
