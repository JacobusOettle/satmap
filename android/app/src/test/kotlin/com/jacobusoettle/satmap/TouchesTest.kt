package com.jacobusoettle.satmap

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.gestures.AndroidGesturesManager
import org.maplibre.android.gestures.MoveGestureDetector
import org.robolectric.annotation.Config

/** Where the screenshot walk touches the screen: the iPhone walk's taps and drags as far from the map's centre in dp
 *  as on the iPhone in points, and map drags that move the map by the whole drag and leave it there, as MapLibre's own
 *  gesture detector takes them. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-420dpi") // the walk's Pixel 7 emulator: 2.625 pixels per dp
class TouchesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density
    private val centre = PointF(540f, 1200f)

    /** The walk's `screenPoint`, with the map's centre at `centre`. */
    private fun point(x: Double, y: Double) = Touches.iphonePoint(x, y, centre, density)

    /** Pixels from `centre`, in dp. */
    private fun dp(p: PointF, from: PointF = centre) = PointF((p.x - from.x) / density, (p.y - from.y) / density)

    @Test
    fun tapsAreAsFarFromTheCentreAsOnTheIPhone() {
        assertEquals(2.625f, density, 0f)
        assertPoint(0f, 0f, dp(point(0.5, 0.5)))
        // The drone-mode pin: (0.7 × 402, 0.62 × 874) pt on the iPhone, whose window's middle is at (201, 437).
        assertPoint(80.4f, 104.88f, dp(point(0.7, 0.62)))
        // The spot tapped for its coordinates, and where its pin is dragged to.
        assertPoint(-60.3f, -43.7f, dp(point(0.35, 0.45)))
        assertPoint(-28.14f, -43.7f, dp(point(0.43, 0.45)))
        // A form scrolled a third of the screen: the iPhone's 306 pt.
        assertPoint(0f, -305.9f, dp(point(0.5, 0.4), from = point(0.5, 0.75)))
    }

    @Test
    fun mapDragsMoveTheMapByTheWholeDrag() {
        // The walk's three drags (`dragMap`): a quarter of the iPhone's width, a fifth of its height, a quarter back.
        val from = point(0.5, 0.45)
        for ((dx, dy) in listOf(0.25 to 0.0, 0.0 to 0.2, -0.25 to 0.0)) {
            val to = point(0.5 + dx, 0.45 + dy)
            val x = (dx * 402).toFloat()
            val y = (dy * 874).toFloat()
            assertPoint(x, y, dp(mapMoved(Touches.mapDrag(from, to, 200, 500, density)), from = PointF()))
            // A plain press and drag would leave out the first of its 20 steps.
            assertPoint(x * 19 / 20, y * 19 / 20, dp(mapMoved(Touches.pressAndDrag(from, to, 200)), from = PointF()))
        }
    }

    @Test
    fun aMapDragHoldsStillBeforeTheFingerIsLifted() {
        val touches = Touches.mapDrag(point(0.5, 0.45), point(0.75, 0.45), 200, 500, density)
        assertEquals(MotionEvent.ACTION_DOWN, touches.first().action)
        // Held, then a first move a little past MapLibre's threshold; the map follows from there.
        assertEquals(MotionEvent.ACTION_MOVE, touches[1].action)
        assertEquals(200L, touches[1].afterMs)
        assertEquals(Touches.FIRST_MOVE_DP * density, touches[1].at.x - touches[0].at.x, 0.01f)
        val moves = touches.subList(2, touches.size - 1)
        assertEquals(20, moves.size)
        assertTrue(moves.all { it.action == MotionEvent.ACTION_MOVE && it.afterMs == 16L })
        // Lifted where the last move left it, half a second later. (Android's VelocityTracker takes a finger still for
        // over 40 ms as stopped when it is lifted, so there is no fling. Robolectric's doesn't, so that isn't tried.)
        val up = touches.last()
        assertEquals(MotionEvent.ACTION_UP, up.action)
        assertEquals(500L, up.afterMs)
        assertEquals(moves.last().at, up.at)
    }

    @Test
    fun pressAndDragIsAsBefore() {
        // Moving a point with the loupe and scrolling a form: 20 steps 16 ms apart, lifted at once at `to`.
        val from = PointF(100f, 200f)
        val to = PointF(300f, 200f)
        val touches = Touches.pressAndDrag(from, to, 900)
        assertEquals(22, touches.size)
        assertEquals(Touch(MotionEvent.ACTION_DOWN, from, 0), touches.first())
        assertEquals(Touch(MotionEvent.ACTION_MOVE, PointF(110f, 200f), 900), touches[1])
        assertTrue(touches.subList(2, 21).all { it.action == MotionEvent.ACTION_MOVE && it.afterMs == 16L })
        assertEquals(Touch(MotionEvent.ACTION_UP, to, 16), touches.last())
    }

    @Test
    fun theThresholdIsMapLibres() {
        assertEquals(Touches.MOVE_THRESHOLD_DP * density, gestures().moveGestureDetector.moveThreshold, 0.01f)
    }

    /** A gestures manager as MapGestureDetector sets it up for the map (MapLibre's move threshold). */
    private fun gestures() = AndroidGesturesManager(context).apply {
        moveGestureDetector.setMoveThresholdResource(org.maplibre.android.R.dimen.maplibre_minimum_move_threshold)
    }

    /** How far the map moves for `touches`, in pixels: MapView hands every event to its gestures manager, and
     *  MapGestureDetector moves the map by each `onMove`'s distance. */
    private fun mapMoved(touches: List<Touch>): PointF {
        val manager = gestures()
        val moved = PointF()
        manager.setMoveGestureListener(object : MoveGestureDetector.SimpleOnMoveGestureListener() {
            override fun onMoveBegin(detector: MoveGestureDetector) = true

            override fun onMove(detector: MoveGestureDetector, distanceX: Float, distanceY: Float): Boolean {
                moved.offset(-distanceX, -distanceY) // MapGestureDetector: transform.moveBy(-distanceX, -distanceY)
                return true
            }
        })
        var time = 1000L
        var down = time
        for (t in touches) {
            time += t.afterMs
            if (t.action == MotionEvent.ACTION_DOWN) down = time
            val e = MotionEvent.obtain(down, time, t.action, t.at.x, t.at.y, 0)
            manager.onTouchEvent(e)
            e.recycle()
        }
        return moved
    }

    private fun assertPoint(x: Float, y: Float, actual: PointF) {
        assertEquals("x", x, actual.x, 0.01f)
        assertEquals("y", y, actual.y, 0.01f)
    }
}
