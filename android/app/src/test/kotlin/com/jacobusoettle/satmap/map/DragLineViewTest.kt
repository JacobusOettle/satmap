package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    /** The view drawn 200 × 200 dp (2 px per dp), showing `line` (dp). */
    private fun drawn(line: DragLineView.Line?): Bitmap {
        val view = DragLineView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 400, 400)
        view.show(line?.let { l -> DragLineView.Line(l.points.map { PointF(it.x * 2, it.y * 2) }, l.closed) })
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
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
    }

    @Test
    fun aClosedShapeIsJoinedBackAndFilled() {
        val image = drawn(
            DragLineView.Line(listOf(PointF(20f, 20f), PointF(180f, 20f), PointF(180f, 180f)), closed = true),
        )
        assertColour(MapColours.measurePink15, image.at(140f, 60f))
        // The way back to the start is dashed too: 5 dp into it is 325 dp along, 2 dp into a dash.
        assertEquals(MapColours.measurePink, image.at(180f - 5f / 1.41421f, 180f - 5f / 1.41421f))
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
}
