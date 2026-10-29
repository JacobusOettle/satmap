package com.jacobusoettle.satmap.map

import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.MeasureMode
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot

/** What the map shows of the measuring tool: the markers and their letters, the line (and the closed shape's fill),
 *  the pin, and the aim line from you to the crosshair (its start and dash phase, iOS `MapController.entry`). */
@RunWith(AndroidJUnit4::class)
class MeasureLayersTest {
    private val a = GeoPoint(-33.9245, 18.9232)
    private val b = GeoPoint(-33.9248, 18.9221)
    private val c = GeoPoint(-33.9232, 18.9219)

    @Test
    fun measuringPointsAreLabelledAToZThenAA() {
        assertEquals(listOf("A", "B", "C"), (0..2).map { MeasureMode.label(it) })
        assertEquals("Z", MeasureMode.label(25))
        assertEquals("AA", MeasureMode.label(26))
        assertEquals("AZ", MeasureMode.label(51))
        assertEquals("BA", MeasureMode.label(52))
        assertEquals("ZZ", MeasureMode.label(701))
        assertEquals("AAA", MeasureMode.label(702))
    }

    @Test
    fun aSinglePointHasNoLetterTwoOrMoreHaveTheirs() {
        val one = MeasureMarkerLayer.features(listOf(a)).features()!!
        assertEquals(1, one.size)
        assertFalse(one[0].hasProperty("letter"))
        assertEquals(Point.fromLngLat(a.lon, a.lat), one[0].geometry())
        val three = MeasureMarkerLayer.features(listOf(a, b, c)).features()!!
        assertEquals(
            listOf("measure-letter/A", "measure-letter/B", "measure-letter/C"),
            three.map { it.getStringProperty("letter") },
        )
        assertEquals(Point.fromLngLat(c.lon, c.lat), three[2].geometry())
        assertTrue(MeasureMarkerLayer.features(emptyList()).features()!!.isEmpty())
    }

    @Test
    fun theLineGoesThroughThePointsAndBackToAWhenClosed() {
        val path = MeasureLineLayer.geoJson(listOf(a, b, c), closed = false).features()!!
        assertEquals(1, path.size)
        assertEquals(3, (path[0].geometry() as LineString).coordinates().size)
        val closed = MeasureLineLayer.geoJson(listOf(a, b, c), closed = true).features()!!
        assertEquals(listOf("LineString", "Polygon"), closed.map { it.geometry()!!.type() })
        val line = (closed[0].geometry() as LineString).coordinates()
        assertEquals(4, line.size)
        assertEquals(line.first(), line.last())
        assertEquals(line, (closed[1].geometry() as Polygon).coordinates().single())
        // From A to you, while one point is placed.
        val placed = MeasureLineLayer.geoJson(listOf(a, GeoPoint(-33.9240, 18.9230)), closed = false).features()!!
        assertEquals(2, (placed[0].geometry() as LineString).coordinates().size)
    }

    @Test
    fun fewerThanTwoPointsDrawNoLineAndTwoNoShape() {
        assertTrue(MeasureLineLayer.geoJson(emptyList(), closed = false).features()!!.isEmpty())
        assertTrue(MeasureLineLayer.geoJson(listOf(a), closed = true).features()!!.isEmpty())
        // Closed needs three points to be a shape.
        assertEquals(listOf("LineString"), MeasureLineLayer.geoJson(listOf(a, b), closed = true).features()!!.map { it.geometry()!!.type() })
    }

    @Test
    fun thePinIsOnePointOrNone() {
        assertEquals(Point.fromLngLat(a.lon, a.lat), SelectedPointLayer.features(a).features()!!.single().geometry())
        assertTrue(SelectedPointLayer.features(null).features()!!.isEmpty())
    }

    /** A 412 × 915 dp screen at 2.625 pixels per dp. */
    private val d = 2.625f
    private val screen = RectF(0f, 0f, 412 * d, 915 * d)
    private val centre = PointF(screen.centerX(), screen.centerY())

    @Test
    fun theAimLineStarts11dpOffYourDot() {
        val me = PointF(centre.x, centre.y + 200 * d)
        val line = MapController.aimLine(me, centre, screen, d)
        assertNotNull(line)
        line!!
        assertEquals(centre.x, line.start.x, 1e-3f)
        assertEquals(me.y - 11 * d, line.start.y, 1e-3f)
        assertEquals(centre, line.end)
        assertEquals(0f, line.phase, 0f) // you on the screen: the dashes start at your dot
    }

    @Test
    fun theAimLineIsDrawnOnlyWhenLongerThan15dp() {
        assertNull(MapController.aimLine(PointF(centre.x + 15 * d, centre.y), centre, screen, d))
        assertNull(MapController.aimLine(centre, centre, screen, d))
        assertNotNull(MapController.aimLine(PointF(centre.x + 15.1f * d, centre.y), centre, screen, d))
    }

    @Test
    fun withYouOffTheScreenItStartsWhereItComesOnWithTheDashesWhereTheydBe() {
        // You 3000 dp to the left: the line comes on 20 dp outside the left edge.
        val me = PointF(centre.x - 3000 * d, centre.y)
        val line = MapController.aimLine(me, centre, screen, d)!!
        assertEquals(-20 * d, line.start.x, 1e-2f)
        assertEquals(centre.y, line.start.y, 1e-3f)
        // From the dot's edge to there, in whole dash patterns of 17 dp, the rest is the phase.
        val skipped = (line.start.x - (me.x + 11 * d))
        assertEquals(skipped % (17 * d), line.phase, 1e-2f)
        assertTrue(line.phase >= 0 && line.phase < 17 * d)
    }

    @Test
    fun theEntryIsWhereTheLineCrossesIntoTheRect() {
        val r = RectF(0f, 0f, 100f, 200f)
        val inside = PointF(50f, 50f)
        assertEquals(inside, MapController.entry(inside, PointF(50f, 100f), r))
        // From the left, the top, the right and the bottom.
        assertEquals(PointF(0f, 100f), MapController.entry(PointF(-100f, 100f), PointF(50f, 100f), r))
        assertEquals(PointF(50f, 0f), MapController.entry(PointF(50f, -300f), PointF(50f, 100f), r))
        assertEquals(PointF(100f, 100f), MapController.entry(PointF(300f, 100f), PointF(50f, 100f), r))
        assertEquals(PointF(50f, 200f), MapController.entry(PointF(50f, 400f), PointF(50f, 100f), r))
        // Off a corner: the later of the two crossings.
        val p = MapController.entry(PointF(-100f, -300f), PointF(50f, 100f), r)
        assertEquals(0f, p.y, 1e-4f)
        assertEquals(-100f + 150f * 0.75f, p.x, 1e-4f)
        assertTrue(p.x >= r.left && hypot(p.x - 50f, p.y - 100f) > 0)
    }
}
