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
            setOf(MapImages.USER_DOT, MapImages.USER_DOT_GREY, MapImages.USER_ARROW, MapImages.USER_ARROW_GREY),
            images.keys,
        )
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
}
