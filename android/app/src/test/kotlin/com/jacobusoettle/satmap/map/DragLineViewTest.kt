package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

/** The measuring line over the map while a point is moved (iOS's `dragLine`): through which points, and how it is
 *  drawn (pink, 3 dp, dashes 10 and 7 dp from its start, filled pink 15 % when closed), whatever the map's zoom. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xhdpi")
class DragLineViewTest {
    private val a = GeoPoint(-33.9245, 18.9232)
    private val b = GeoPoint(-33.9248, 18.9221)
    private val me = GeoPoint(-33.9240, 18.9230)

    @Test
    fun itGoesThroughTheMarkersAndFromYouToASinglePoint() {
        assertEquals(listOf(a, b), DragLineView.points(listOf(a, b), me))
        assertEquals(listOf(me, a), DragLineView.points(listOf(a), me))
        assertEquals(listOf(a), DragLineView.points(listOf(a), null))
    }

    /** The view drawn 200 × 200 dp (2 px per dp), showing `line` (dp); the bounds of each path it draws (pixels) go
     *  into `paths`. */
    private fun drawn(line: DragLineView.Line?, paths: MutableList<RectF> = mutableListOf()): Bitmap {
        val view = DragLineView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 400, 400)
        view.show(line?.let { l -> DragLineView.Line(l.points.map { PointF(it.x * 2, it.y * 2) }, l.closed) })
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(object : Canvas(bitmap) {
            override fun drawPath(path: Path, paint: Paint) {
                paths += RectF().also { path.computeBounds(it, true) }
                super.drawPath(path, paint)
            }
        })
        return bitmap
    }

    /** The line as it was drawn before it was cut to the view: one path, dashed by Skia (`DashPathEffect`). */
    private fun uncut(line: DragLineView.Line): Bitmap {
        val path = Path()
        path.moveTo(line.points[0].x * 2, line.points[0].y * 2)
        for (p in line.points.drop(1)) path.lineTo(p.x * 2, p.y * 2)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (line.closed) {
            path.close()
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapColours.measurePink15 })
        }
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = MapColours.measurePink
                strokeWidth = 6f
                strokeMiter = 10f
                pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
            },
        )
        return bitmap
    }

    private fun Bitmap.at(x: Float, y: Float) = getPixel((x * 2).toInt(), (y * 2).toInt())

    /** The same colour, give or take what a translucent pixel loses to premultiplied storage. */
    private fun assertColour(expected: Int, actual: Int) {
        assertEquals(Color.alpha(expected), Color.alpha(actual))
        assertEquals(Color.red(expected).toFloat(), Color.red(actual).toFloat(), 3f)
        assertEquals(Color.green(expected).toFloat(), Color.green(actual).toFloat(), 3f)
        assertEquals(Color.blue(expected).toFloat(), Color.blue(actual).toFloat(), 3f)
    }

    @Test
    fun itIsDashedPink3dpWideFromItsStart() {
        val image = drawn(DragLineView.Line(listOf(PointF(20f, 50f), PointF(180f, 50f)), closed = false))
        // Dashes 10 dp on, 7 off, from the start: on at 5 and 22, off at 13.5 and 30.5 dp along.
        assertEquals(MapColours.measurePink, image.at(25f, 50f))
        assertEquals(0, Color.alpha(image.at(33.5f, 50f)))
        assertEquals(MapColours.measurePink, image.at(42f, 50f))
        assertEquals(0, Color.alpha(image.at(50.5f, 50f)))
        // 3 dp across, butt ends.
        assertEquals(MapColours.measurePink, image.at(25f, 51f))
        assertEquals(0, Color.alpha(image.at(25f, 52.5f)))
        assertEquals(0, Color.alpha(image.at(18.5f, 50f)))
        assertSame(uncut(DragLineView.Line(listOf(PointF(20f, 50f), PointF(180f, 50f)), closed = false)), image)
    }

    @Test
    fun aClosedShapeIsJoinedBackAndFilled() {
        val closed = DragLineView.Line(listOf(PointF(20f, 20f), PointF(180f, 20f), PointF(180f, 180f)), closed = true)
        val image = drawn(closed)
        assertColour(MapColours.measurePink15, image.at(140f, 60f))
        // The way back to the start is dashed too: 5 dp into it is 325 dp along, 2 dp into a dash.
        assertEquals(MapColours.measurePink, image.at(180f - 5f / 1.41421f, 180f - 5f / 1.41421f))
        // Its last dash goes on into the first, round the corner at the start, as Skia's dashing does it.
        assertSame(uncut(closed), image)
        val open = drawn(DragLineView.Line(listOf(PointF(20f, 20f), PointF(180f, 20f), PointF(180f, 180f)), closed = false))
        assertEquals(0, Color.alpha(open.at(140f, 60f)))
    }

    @Test
    fun nothingWithoutALine() {
        val none = drawn(null)
        val single = drawn(DragLineView.Line(listOf(PointF(20f, 50f)), closed = false))
        for (image in listOf(none, single)) {
            for (x in 0 until 400 step 10) for (y in 0 until 400 step 10) assertEquals(0, image.getPixel(x, y))
        }
    }

    /** Where a point `along` dp from the line's start falls in its dashes: true well inside a dash, false well inside a
     *  gap, null within 1 dp of either end (anti-aliased). */
    private fun dash(along: Double): Boolean? {
        val p = along.mod(17.0)
        return if (p in 1.0..9.0) true else if (p in 11.0..16.0) false else null
    }

    /** What is drawn stays within 20 dp of the view (what Android 8's renderer draws, see `DragLineView`). */
    private fun assertCut(paths: List<RectF>) {
        assertTrue(paths.isNotEmpty())
        for (b in paths) assertTrue("$b", RectF(-40f, -40f, 440f, 440f).contains(b))
    }

    @Test
    fun aLineToAPointFarOffTheViewIsDrawnOnlyOnItWithItsDashesWhereTheyFall() {
        // From a point 1,000,000 px left of the view (a few km away, zoomed in) to one on it, or as far right of it.
        for (end in listOf(PointF(180f, 50f), PointF(500_000f, 50f))) {
            val line = DragLineView.Line(listOf(PointF(-500_000f, 50f), end), closed = false)
            val paths = mutableListOf<RectF>()
            val image = drawn(line, paths)
            assertCut(paths)
            // Dashes 10 dp on and 7 off from the far point 500,000 dp away: on 4 to 14 dp across the view, and so on.
            var x = 0.5
            while (x < 179) {
                dash(x + 500_000)?.let { on ->
                    assertEquals("x $x", if (on) MapColours.measurePink else 0, image.at(x.toFloat(), 50f))
                }
                x += 0.5
            }
            assertSame(uncut(line), image)
        }
    }

    @Test
    fun aClosedShapeWithAPointFarOffTheViewIsFilledAndDashedWhereItIsOnIt() {
        // A to B along the top, to C 1,000,000 px below the view and back to A, where the last dash (5 dp into it)
        // goes on into the first.
        val a = PointF(20f, 20f)
        val c = PointF(100f, 500_006f)
        val line = DragLineView.Line(listOf(a, PointF(180f, 20f), c), closed = true)
        val paths = mutableListOf<RectF>()
        val image = drawn(line, paths)
        assertCut(paths)
        // Filled between the long sides down to the bottom of the view, and not outside them.
        assertColour(MapColours.measurePink15, image.at(100f, 100f))
        assertColour(MapColours.measurePink15, image.at(100f, 199f))
        assertEquals(0, image.at(10f, 100f))
        assertEquals(0, image.at(190f, 100f))
        // The way back from C to A: its dashes where they fall along the whole line, AB + BC (as long as CA) and the
        // way from C (looked at 1 dp outside the shape, clear of the fill).
        val ca = hypot(80.0, 499_986.0)
        var y = 25.0
        while (y < 199) {
            val s = (y - 20) / 499_986 // of the way from A to C
            val x = 19 + 80 * s
            dash(160 + ca + ca * (1 - s))?.let { on ->
                assertEquals("y $y", if (on) MapColours.measurePink else 0, image.at(x.toFloat(), y.toFloat()))
            }
            y += 0.5
        }
        assertSame(uncut(line), image)
    }

    /** The same picture as `expected`, give or take a little anti-aliasing at the ends of the dashes. */
    private fun assertSame(expected: Bitmap, actual: Bitmap) {
        var worst = 0
        for (x in 0 until 400) for (y in 0 until 400) {
            worst = maxOf(worst, abs(Color.alpha(expected.getPixel(x, y)) - Color.alpha(actual.getPixel(x, y))))
        }
        assertTrue("alpha off by $worst", worst <= 32)
    }
}
