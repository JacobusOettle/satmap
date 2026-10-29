package com.jacobusoettle.satmap.map

import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** The loupe's geometry and motion (iOS `PointAdjuster`): where the square goes, the finger's way on the ground, the
 *  5 m the point moves freely and how fast the map scrolls beyond it. (Under Robolectric for `PointF` and `RectF`.) */
@RunWith(AndroidJUnit4::class)
class PointAdjusterTest {
    /** A phone in portrait, in dp, inside its bars: 412 × 915 less the status bar (24) and the gesture bar (24). */
    private val portrait = RectF(0f, 24f, 412f, 891f)

    private fun assertPoint(x: Float, y: Float, p: PointF) {
        assertEquals(x, p.x, 1e-4f)
        assertEquals(y, p.y, 1e-4f)
    }

    @Test
    fun theSquareGoes44AboveTheFinger() {
        // Its centre: the gap and half the square above the finger.
        assertPoint(200f, 600f - 44 - 116, PointAdjuster.place(232f, 44f, PointF(200f, 600f), portrait))
        // Just enough room above: the square's top on the area's edge inset by 8.
        val y = 24f + 8 + 232 + 44
        assertPoint(200f, y - 44 - 116, PointAdjuster.place(232f, 44f, PointF(200f, y), portrait))
    }

    @Test
    fun nearTheTopItGoesBelow() {
        assertPoint(200f, 100f + 44 + 116, PointAdjuster.place(232f, 44f, PointF(200f, 100f), portrait))
        val y = 24f + 8 + 232 + 44 - 1
        assertPoint(200f, y + 44 + 116, PointAdjuster.place(232f, 44f, PointF(200f, y), portrait))
    }

    @Test
    fun itStaysOnTheScreenSideways() {
        // Near the left edge: its left side on the edge inset by 8.
        assertPoint(8f + 116, 440f, PointAdjuster.place(232f, 44f, PointF(10f, 600f), portrait))
        assertPoint(412f - 8 - 116, 440f, PointAdjuster.place(232f, 44f, PointF(405f, 600f), portrait))
    }

    @Test
    fun inLandscapeItGoesBesideTheFingerOnTheSideWithMoreRoom() {
        val landscape = RectF(48f, 0f, 915f, 388f)
        // Neither above nor below fits: beside, the gap and half the square away, the height clamped.
        assertPoint(300f + 44 + 116, 200f, PointAdjuster.place(232f, 44f, PointF(300f, 200f), landscape))
        assertPoint(700f - 44 - 116, 200f, PointAdjuster.place(232f, 44f, PointF(700f, 200f), landscape))
        assertPoint(700f - 44 - 116, 8f + 116, PointAdjuster.place(232f, 44f, PointF(700f, 110f), landscape))
        assertPoint(700f - 44 - 116, 388f - 8 - 116, PointAdjuster.place(232f, 44f, PointF(700f, 280f), landscape))
        // Near the top or the bottom it fits below or above after all.
        assertPoint(700f, 20f + 44 + 116, PointAdjuster.place(232f, 44f, PointF(700f, 20f), landscape))
        assertPoint(700f, 380f - 44 - 116, PointAdjuster.place(232f, 44f, PointF(700f, 380f), landscape))
        // As much room either side: to the left, as iOS.
        val middle = (48f + 915f) / 2
        assertPoint(middle - 44 - 116, 200f, PointAdjuster.place(232f, 44f, PointF(middle, 200f), landscape))
    }

    /** The square's scale: 15 m across 216 dp. */
    private val perMetre = 1 / Geo.metersPerWorldUnit(-33.92)
    private val perLoupePoint = 15 * perMetre / 216

    @Test
    fun theFingerMovesThePointAtTheSquaresScale() {
        // North up: the finger 72 dp to the right is 5 m east.
        val (dir, metres) = PointAdjuster.fingerGround(XY(72.0, 0.0), XY(1.0, 0.0), XY(0.0, 1.0), perLoupePoint, perMetre)
        assertEquals(5.0, metres, 1e-9)
        assertEquals(1.0, dir.x, 1e-12)
        assertEquals(0.0, dir.y, 1e-12)
        // 216 dp down and to the left: 15 m at 45°, south-west.
        val diagonal = 216 / sqrt(2.0)
        val (d2, m2) =
            PointAdjuster.fingerGround(XY(-diagonal, diagonal), XY(1.0, 0.0), XY(0.0, 1.0), perLoupePoint, perMetre)
        assertEquals(15.0, m2, 1e-9)
        assertEquals(-cos(PI / 4), d2.x, 1e-12)
        assertEquals(sin(PI / 4), d2.y, 1e-12)
    }

    @Test
    fun onATurnedMapTheFingerGoesTheWayTheScreenShows() {
        // The map turned 90° (east up): the screen's right is south on the ground, its down is east.
        val right = XY(0.0, 1.0)
        val down = XY(-1.0, 0.0)
        val (dir, metres) = PointAdjuster.fingerGround(XY(0.0, -36.0), right, down, perLoupePoint, perMetre)
        assertEquals(2.5, metres, 1e-9)
        assertEquals(1.0, dir.x, 1e-12) // up the screen: east
        assertEquals(0.0, dir.y, 1e-12)
    }

    @Test
    fun noFingerMoveIsNoWayAtAll() {
        val (dir, metres) = PointAdjuster.fingerGround(XY(0.0, 0.0), XY(1.0, 0.0), XY(0.0, 1.0), perLoupePoint, perMetre)
        assertEquals(XY(0.0, 0.0), dir)
        assertEquals(0.0, metres, 0.0)
        assertNull(PointAdjuster.unit(0.0, 0.0))
        assertEquals(XY(0.6, 0.8), PointAdjuster.unit(3.0, 4.0))
    }

    @Test
    fun thePointGoesNoFurtherThan5Metres() {
        assertEquals(3 * perMetre, PointAdjuster.along(3.0, perMetre), 1e-15)
        assertEquals(5 * perMetre, PointAdjuster.along(5.0, perMetre), 1e-15)
        assertEquals(5 * perMetre, PointAdjuster.along(12.0, perMetre), 1e-15)
    }

    @Test
    fun beyond5MetresTheMapScrolls1point5MetresASecondForEachMetreUpTo20() {
        assertEquals(0.0, PointAdjuster.scrollStep(4.0, 1 / 60.0, perMetre), 0.0)
        assertEquals(0.0, PointAdjuster.scrollStep(5.0, 1 / 60.0, perMetre), 0.0)
        // 2 m beyond: 3 m/s, so 5 cm in a 60th of a second.
        assertEquals(0.05 * perMetre, PointAdjuster.scrollStep(7.0, 1 / 60.0, perMetre), 1e-15)
        assertEquals(1.5 * perMetre * 0.1, PointAdjuster.scrollStep(6.0, 0.1, perMetre), 1e-15)
        // At most 20 m/s (from 18.33 m beyond).
        assertEquals(20 * perMetre / 60, PointAdjuster.scrollStep(30.0, 1 / 60.0, perMetre), 1e-15)
        assertEquals(20 * perMetre / 60, PointAdjuster.scrollStep(5 + 40 / 3.0, 1 / 60.0, perMetre), 1e-15)
    }

    @Test
    fun theFiveMetreRingIsAThirdOfTheSquare() {
        // The square shows 15 m across, so a finger 5 m out (72 of 216 dp) is on the ring.
        val (_, metres) = PointAdjuster.fingerGround(XY(0.0, 72.0), XY(1.0, 0.0), XY(0.0, 1.0), perLoupePoint, perMetre)
        assertEquals(PointAdjuster.freeRadius, metres, 1e-9)
        assertEquals(72.0, PointAdjuster.freeRadius / PointAdjuster.metresAcross * 216, 1e-9)
        assertEquals(216f, PointAdjuster.SIDE - 2 * PointAdjuster.BORDER)
    }

    @Test
    fun linesShowInTheSquareByTheirBoxes() {
        val ground = WorldRect(0.5, 0.5, 0.6, 0.6)
        // A long edge passing the square counts, though its ends are far away.
        val edge = LoupeView.box(listOf(XY(0.0, 0.55), XY(1.0, 0.55)))
        assertTrue(LoupeView.overlap(edge, ground))
        // A flat box (a line along a parallel) still meets what it crosses.
        assertEquals(WorldRect(0.0, 0.55, 1.0, 0.55), edge)
        assertFalse(LoupeView.overlap(LoupeView.box(listOf(XY(0.7, 0.7), XY(0.8, 0.9))), ground))
        assertFalse(LoupeView.overlap(LoupeView.box(emptyList()), ground))
        assertTrue(LoupeView.overlap(LoupeView.box(listOf(XY(0.6, 0.6))), ground)) // touching
    }

    @Test
    fun theFingerAndTheGroundAgree() {
        // A finger moved 3 m worth at 30° below the right on a north-up map ends up 3 m away that way.
        val a = 30 * PI / 180
        val finger = XY(43.2 * cos(a), 43.2 * sin(a))
        val (dir, metres) = PointAdjuster.fingerGround(finger, XY(1.0, 0.0), XY(0.0, 1.0), perLoupePoint, perMetre)
        assertEquals(3.0, metres, 1e-9)
        val along = PointAdjuster.along(metres, perMetre)
        assertEquals(3.0, hypot(dir.x * along, dir.y * along) / perMetre, 1e-9)
    }
}
