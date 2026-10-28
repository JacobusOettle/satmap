package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/** The saved camera ("camera": the visible region as four numbers) and the camera regions `MapController` sets, on
 *  the plain JVM (MapLibre's native map can't run in unit tests). */
class MapControllerTest {
    private val region = MapController.Region(GeoPoint(-33.921765, 18.923779), 0.012_345_678_9, 0.014_8)

    @Test
    fun theSavedCameraReadsBackTheSame() {
        val saved = region.saved()
        assertEquals("-33.921765,18.923779,0.0123456789,0.0148", saved)
        assertEquals(region, MapController.Region.parse(saved))
        // Tiny and huge numbers too (Double.toString may write an exponent).
        val far = MapController.Region(GeoPoint(85.0, -179.999_999), 1.0e-6, 360.0)
        assertEquals(far, MapController.Region.parse(far.saved()))
    }

    @Test
    fun theSavedCameraIsTheSameInEveryLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // decimal comma
            val saved = region.saved()
            assertEquals("-33.921765,18.923779,0.0123456789,0.0148", saved)
            assertEquals(region, MapController.Region.parse(saved))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun somethingElseSavedIsNoCamera() {
        assertNull(MapController.Region.parse(null)) // first launch
        assertNull(MapController.Region.parse(""))
        assertNull(MapController.Region.parse("-33.9,18.9,0.01"))
        assertNull(MapController.Region.parse("-33.9,18.9,0.01,0.01,0.01"))
        assertNull(MapController.Region.parse("a,b,c,d"))
        assertNull(MapController.Region.parse("-33,9;18,9;0,01;0,01"))
        assertNull(MapController.Region.parse("NaN,18.9,0.01,0.01"))
        assertNull(MapController.Region.parse("-33.9,Infinity,0.01,0.01"))
        assertNull(MapController.Region.parse("-95.0,18.9,0.01,0.01"))
        assertNull(MapController.Region.parse("-33.9,18.9,0.0,0.01"))
        assertNull(MapController.Region.parse("-33.9,18.9,0.01,-0.01"))
    }

    @Test
    fun firstLaunchShowsSouthAfrica() {
        assertEquals(MapController.Region(GeoPoint(-28.5, 24.7), 14.0, 14.0), MapController.Region.southAfrica)
        val b = MapController.Region.southAfrica.bounds
        assertEquals(-35.5, b.minLat, 1e-12)
        assertEquals(-21.5, b.maxLat, 1e-12)
        assertEquals(17.7, b.minLon, 1e-12)
        assertEquals(31.7, b.maxLon, 1e-12)
    }

    @Test
    fun theBoundsStayOnTheMap() {
        val b = MapController.Region(GeoPoint(80.0, 0.0), 40.0, 400.0).bounds
        assertEquals(WebMercator.maxLatitude, b.maxLat, 0.0)
        assertEquals(60.0, b.minLat, 1e-12)
        // Longitudes may go round the world: MapLibre takes them unwrapped.
        assertEquals(-200.0, b.minLon, 1e-12)
        assertEquals(200.0, b.maxLon, 1e-12)
    }

    @Test
    fun theFirstFixShows1500MetresAroundYou() {
        val p = GeoPoint(-33.9183, 18.9212)
        val r = MapController.Region.around(p, 1500.0)
        assertEquals(p, r.center)
        assertEquals(1500 / 111_320.0, r.latDelta, 1e-15)
        assertEquals(1500 / 111_320.0 / cos(p.lat * PI / 180), r.lonDelta, 1e-15)
        val b = r.bounds
        assertEquals(p.lat + 750 / 111_320.0, b.maxLat, 1e-12)
        assertEquals(p.lon - 750 / 111_320.0 / cos(p.lat * PI / 180), b.minLon, 1e-12)
    }

    @Test
    fun zoomingToBoundsShowsAQuarterMoreAndAtLeast0002Degrees() {
        val r = MapController.Region.fitting(GeoBounds(-34.0, 18.0, -33.0, 18.0005))
        assertEquals(-33.5, r.center.lat, 1e-12)
        assertEquals(18.00025, r.center.lon, 1e-12)
        assertEquals(1.25, r.latDelta, 1e-12)
        assertEquals(0.002, r.lonDelta, 0.0)
        val point = MapController.Region.fitting(GeoBounds(-34.0, 18.0, -34.0, 18.0))
        assertEquals(0.002, point.latDelta, 0.0)
        assertEquals(0.002, point.lonDelta, 0.0)
    }

    @Test
    fun theVisibleRegionSpansItsCorners() {
        // Turned a little: the corners aren't the edges' middles.
        val r = MapController.Region.of(
            GeoPoint(-33.92, 18.92),
            listOf(GeoPoint(-33.90, 18.90), GeoPoint(-33.91, 18.95), GeoPoint(-33.93, 18.89), GeoPoint(-33.94, 18.94)),
        )
        assertEquals(GeoPoint(-33.92, 18.92), r.center)
        assertEquals(0.04, r.latDelta, 1e-12)
        assertEquals(0.06, r.lonDelta, 1e-12)
        assertEquals(r, MapController.Region.parse(r.saved()))
    }

    @Test
    fun aViewAcrossTheDateLineSpansItNotTheWorld() {
        val east = MapController.Region.of(
            GeoPoint(-17.0, 179.95),
            listOf(GeoPoint(-16.9, 179.85), GeoPoint(-16.9, -179.95), GeoPoint(-17.1, 179.85), GeoPoint(-17.1, -179.95)),
        )
        assertEquals(0.2, east.lonDelta, 1e-9)
        val west = MapController.Region.of(
            GeoPoint(-17.0, -179.95),
            listOf(GeoPoint(-16.9, 179.85), GeoPoint(-16.9, -179.85), GeoPoint(-17.1, 179.85), GeoPoint(-17.1, -179.85)),
        )
        assertEquals(0.3, west.lonDelta, 1e-9)
        assertTrue(west.bounds.minLon < -180)
        assertFalse(west.bounds.maxLon > 180)
    }

    @Test
    fun zoomingOntoAPointScalesTheScreenToTheMetresAsked() {
        // Each level halves the ground across the screen.
        assertEquals(17.0, MapController.zoomOnto(15.0, 1200.0, 300.0), 1e-12)
        assertEquals(14.0, MapController.zoomOnto(15.0, 150.0, 300.0), 1e-12)
        assertEquals(15.0, MapController.zoomOnto(15.0, 300.0, 300.0), 1e-12)
    }
}
