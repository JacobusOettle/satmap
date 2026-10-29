package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.Simplify
import com.jacobusoettle.satmap.core.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Polygon
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/** Choosing an offline area with the lasso: one finger adds a point every 3 dp and hands the loop over on lift; the
 *  loop is simplified (Douglas–Peucker, 4 dp) into the ring the model is given, or none when it isn't an area; the
 *  draft area is that ring as a closed polygon. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "xhdpi") // 2 pixels per dp
class LassoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lasso = LassoView(context).apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, 800, 1600)
    }
    private var finished: List<PointF>? = null
    private var downTime = 0L

    init {
        lasso.onFinish = { finished = it }
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        lasso.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Two fingers down, the first at (x, y) and the second at (x2, y2): `action` with the pointer at `index`. */
    private fun twoFingers(action: Int, index: Int, x: Float, y: Float, x2: Float, y2: Float) {
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y },
            MotionEvent.PointerCoords().apply { this.x = x2; this.y = y2 },
        )
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), masked, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        lasso.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun xs(points: List<PointF>?) = points?.map { it.x }

    @Test
    fun aPointIsAddedEachTimeTheFingerHasMoved3Dp() {
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(MotionEvent.ACTION_MOVE, 105f, 100f) // 2.5 dp
        touch(MotionEvent.ACTION_MOVE, 106f, 100f) // 3 dp
        touch(MotionEvent.ACTION_MOVE, 110f, 100f) // 2 dp from the last point
        touch(MotionEvent.ACTION_MOVE, 112f, 100f) // 3 dp
        touch(MotionEvent.ACTION_MOVE, 112f, 111.9f) // 4.95 dp down
        assertEquals(listOf(100f, 106f, 112f, 112f), xs(lasso.drawn))
        assertNull(finished) // still drawing
        touch(MotionEvent.ACTION_UP, 112f, 112f)
        assertEquals(listOf(100f, 106f, 112f, 112f), xs(finished))
        assertEquals(111.9f, finished!!.last().y)
    }

    @Test
    fun theStepIs3DpAtAnyDensity() {
        assertTrue(LassoView.adds(null, PointF(0f, 0f), 2f))
        assertFalse(LassoView.adds(PointF(0f, 0f), PointF(5.9f, 0f), 2f))
        assertTrue(LassoView.adds(PointF(0f, 0f), PointF(6f, 0f), 2f))
        assertTrue(LassoView.adds(PointF(0f, 0f), PointF(3f, 4f), 1f)) // 5 dp
        assertFalse(LassoView.adds(PointF(10f, 10f), PointF(13f, 15f), 2.625f)) // 2.2 dp
    }

    @Test
    fun aCancelledTouchForgetsTheLoopAndHandsNothingOver() {
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(MotionEvent.ACTION_MOVE, 200f, 100f)
        touch(MotionEvent.ACTION_CANCEL, 200f, 100f)
        assertTrue(lasso.drawn.isEmpty())
        assertNull(finished)
    }

    @Test
    fun onlyTheFirstFingerDraws() {
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        twoFingers(MotionEvent.ACTION_POINTER_DOWN, 1, 100f, 100f, 400f, 400f)
        twoFingers(MotionEvent.ACTION_MOVE, 0, 120f, 100f, 600f, 600f)
        twoFingers(MotionEvent.ACTION_POINTER_UP, 1, 140f, 100f, 600f, 600f) // the second finger lifts: nothing ends
        assertNull(finished)
        touch(MotionEvent.ACTION_MOVE, 160f, 100f)
        touch(MotionEvent.ACTION_UP, 160f, 100f)
        assertEquals(listOf(100f, 120f, 160f), xs(finished))
    }

    @Test
    fun theFirstFingerLiftingFirstEndsTheLoop() {
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        twoFingers(MotionEvent.ACTION_POINTER_DOWN, 1, 100f, 100f, 400f, 400f)
        twoFingers(MotionEvent.ACTION_MOVE, 0, 130f, 100f, 400f, 400f)
        twoFingers(MotionEvent.ACTION_POINTER_UP, 0, 130f, 100f, 400f, 400f)
        assertEquals(listOf(100f, 130f), xs(finished))
        finished = null
        touch(MotionEvent.ACTION_UP, 400f, 400f) // the other finger: not the lasso's
        assertNull(finished)
    }

    @Test
    fun resetForgetsTheLoop() {
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(MotionEvent.ACTION_MOVE, 200f, 100f)
        touch(MotionEvent.ACTION_MOVE, 200f, 200f)
        touch(MotionEvent.ACTION_UP, 200f, 200f)
        lasso.reset()
        assertTrue(lasso.drawn.isEmpty())
    }

    @Test
    fun aDrawnLoopIsSimplifiedWithin4Dp() {
        // A circle of radius 100 dp drawn every 3 dp, with 3 dp of wobble: Douglas–Peucker at 4 dp keeps far fewer
        // points, every drawn point within 4 dp of the ring's edges. In pixels at 2 per dp.
        val d = 2f
        val drawn = (0..209).map { i ->
            val a = i * 3.0 / 100
            val r = 100 + if (i % 2 == 0) 1.5 else -1.5
            PointF(((400 + r * cos(a)) * d).toFloat(), ((400 + r * sin(a)) * d).toFloat())
        }
        val ring = MapController.lassoRing(drawn, d)!!
        assertTrue("${ring.size} points", ring.size in 8..60)
        assertEquals(drawn.first(), ring.first())
        assertEquals(drawn.last(), ring.last())
        // Every point kept is a drawn one, in order, and every drawn point is within 4 dp of the side it was left on.
        val kept = ArrayList<Int>()
        var j = 0
        for (p in ring) {
            while (j < drawn.size && drawn[j] != p) j++
            assertTrue(j < drawn.size)
            kept += j
        }
        fun dp(p: PointF) = XY(p.x / 2.0, p.y / 2.0)
        for (k in 0 until kept.size - 1) {
            for (i in kept[k]..kept[k + 1]) {
                val off = Simplify.segmentDistanceSquared(dp(drawn[i]), dp(drawn[kept[k]]), dp(drawn[kept[k + 1]]))
                assertTrue(off <= 16.0 + 1e-6)
            }
        }
    }

    @Test
    fun aBumpUnder4DpGoesAndOneOver4DpStays() {
        val d = 2.625f
        fun px(x: Double, y: Double) = PointF((x * d).toFloat(), (y * d).toFloat())
        // A triangle with a bump on its first side.
        fun loop(bump: Double) = listOf(px(0.0, 0.0), px(50.0, -bump), px(100.0, 0.0), px(50.0, 80.0), px(1.0, 1.0))
        assertEquals(4, MapController.lassoRing(loop(3.9), d)!!.size)
        assertEquals(5, MapController.lassoRing(loop(4.1), d)!!.size)
    }

    @Test
    fun aTapOrAStraightStrokeIsNoArea() {
        assertNull(MapController.lassoRing(emptyList(), 2f))
        assertNull(MapController.lassoRing(listOf(PointF(10f, 10f)), 2f))
        assertNull(MapController.lassoRing(listOf(PointF(10f, 10f), PointF(200f, 10f)), 2f))
        // Back and forth along a line: simplified to its two ends.
        val stroke = (0..50).map { PointF(10f + it * 6, 10f + (it % 2) * 3) }
        assertNull(MapController.lassoRing(stroke, 2f))
        // Three points of a real loop are an area.
        assertEquals(3, MapController.lassoRing(listOf(PointF(0f, 0f), PointF(200f, 0f), PointF(100f, 200f)), 2f)!!.size)
    }

    @Test
    fun theDraftAreaIsTheRingClosed() {
        val ring = listOf(GeoPoint(-33.9, 18.9), GeoPoint(-33.9, 19.0), GeoPoint(-34.0, 18.95))
        val features = DraftAreaLayer.features(ring).features()!!
        assertEquals(1, features.size)
        val outer = (features[0].geometry() as Polygon).coordinates()[0]
        assertEquals(4, outer.size)
        assertEquals(outer.first(), outer.last())
        assertEquals(18.9, outer[0].longitude(), 0.0)
        assertEquals(-33.9, outer[0].latitude(), 0.0)
        assertEquals(18.95, outer[2].longitude(), 0.0)
        // Already closed: not closed twice.
        assertEquals(4, (DraftAreaLayer.features(ring + ring[0]).features()!![0].geometry() as Polygon).coordinates()[0].size)
        // No ring, or fewer than 3 points: nothing.
        assertTrue(DraftAreaLayer.features(null).features()!!.isEmpty())
        assertTrue(DraftAreaLayer.features(ring.take(2)).features()!!.isEmpty())
    }

    @Test
    fun theLassoIsAWashOverTheMapThatAccessibilityLeavesOut() {
        assertEquals(0x14000000, (lasso.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, lasso.importantForAccessibility)
    }
}
