package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.core.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The saved camera ("camera": the visible region as four numbers) and the camera regions and centres `MapController`
 *  sets, on the plain JVM (MapLibre's native map can't run in unit tests). */
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
    fun aRegionIsShownAroundItsCentreAsMapKitShowsIt() {
        // MapLibre centres a fit of South Africa's edges on their middle in Mercator, 26 km south of the centre.
        val b = MapController.Region.southAfrica.bounds
        val middle = (WebMercator.world(GeoPoint(b.minLat, 24.7)).y + WebMercator.world(GeoPoint(b.maxLat, 24.7)).y) / 2
        assertEquals(-28.734, WebMercator.geo(0.5, middle).lat, 0.001)
        val fitted = CameraPosition.Builder()
            .target(LatLng(WebMercator.geo(0.5, middle).lat, 24.7))
            .zoom(4.6)
            .bearing(30.0)
            .tilt(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        // The camera keeps the fitted zoom and heading, centred where iOS centres it.
        val c = MapController.centred(fitted, MapController.Region.southAfrica.center)
        assertEquals(-28.5, c.target!!.latitude, 0.0)
        assertEquals(24.7, c.target!!.longitude, 0.0)
        assertEquals(4.6, c.zoom, 0.0)
        assertEquals(30.0, c.bearing, 0.0)
        assertEquals(0.0, c.tilt, 0.0)
        assertTrue(doubleArrayOf(0.0, 0.0, 0.0, 0.0).contentEquals(c.padding))
    }

    @Test
    fun theSafeAreasMiddleIsBelowTheViewsUnderTheStatusBar() {
        // iPhone 16 Pro: 62 pt at the top, 34 at the bottom; the screenshots show you 14 pt below the crosshair.
        assertEquals(XY(0.0, 14.0), MapController.safeOffset(0f, 186f, 0f, 102f, 3f))
        // The Pixel 7 emulator: 52 dp at the top, 24 at the bottom, the same 14.
        assertEquals(0.0, MapController.safeOffset(0f, 136.5f, 0f, 63f, 2.625f).x, 0.0)
        assertEquals(14.0, MapController.safeOffset(0f, 136.5f, 0f, 63f, 2.625f).y, 1e-9)
        // Turned: the cutout on the left, the navigation bar on the right.
        assertEquals(XY(15.0, 0.0), MapController.safeOffset(100f, 0f, 40f, 0f, 2f))
        assertEquals(XY(0.0, 0.0), MapController.safeOffset(0f, 0f, 0f, 0f, 2f))
    }

    @Test
    fun theCameraShowsAPointAtTheSafeAreasMiddle() {
        val you = GeoPoint(-33.921765, 18.923779)
        val zoom = 16.0
        val size = 512 * 2.0.pow(zoom) // MapLibre's world in dp
        val w = WebMercator.world(you)
        // North up: the view's middle (the crosshair) is 14 dp north of you.
        val north = WebMercator.world(MapController.targetShowing(you, XY(0.0, 14.0), zoom, 0.0))
        assertEquals(w.x, north.x, 1e-15)
        assertEquals(14 / size, w.y - north.y, 1e-15)
        // That is 13.9 m there (MapLibre's metres per dp: the equator's 40,075 km over its world, by cos latitude).
        assertEquals(13.87, (w.y - north.y) * 40_075_016.686 * cos(you.lat * PI / 180), 0.01)
        // East up (turned 90°): what is down on the screen is west, so the middle is 14 dp east of you.
        val east = WebMercator.world(MapController.targetShowing(you, XY(0.0, 14.0), zoom, 90.0))
        assertEquals(14 / size, east.x - w.x, 1e-15)
        assertEquals(w.y, east.y, 1e-15)
        // South up: the middle is south of you.
        val south = WebMercator.world(MapController.targetShowing(you, XY(0.0, 14.0), zoom, 180.0))
        assertEquals(-14 / size, w.y - south.y, 1e-15)
        // Any turn and offset: you are that far right of and below the middle on the screen.
        for (bearing in listOf(0.0, 30.0, 135.0, 300.0)) {
            val t = WebMercator.world(MapController.targetShowing(you, XY(5.0, 14.0), zoom, bearing))
            val b = bearing * PI / 180
            val dx = (w.x - t.x) * size
            val dy = (w.y - t.y) * size
            assertEquals(5.0, dx * cos(b) + dy * sin(b), 1e-6)
            assertEquals(14.0, -dx * sin(b) + dy * cos(b), 1e-6)
        }
        // A step of a whole zoom level halves the offset on the ground.
        val closer = WebMercator.world(MapController.targetShowing(you, XY(0.0, 14.0), zoom + 1, 0.0))
        assertEquals((w.y - north.y) / 2, w.y - closer.y, 1e-15)
        // No offset: the point itself.
        assertEquals(you, MapController.targetShowing(you, XY(0.0, 0.0), zoom, 30.0))
    }

    @Test
    fun aRegionIsCentredInTheSafeAreaWithoutPadding() {
        // MapLibre fitted the region within the safe area and returns that padding with the camera.
        val fitted = CameraPosition.Builder()
            .target(LatLng(-33.95, 18.9))
            .zoom(13.2)
            .bearing(30.0)
            .tilt(0.0)
            .padding(0.0, 186.0, 0.0, 102.0)
            .build()
        val centre = GeoPoint(-33.92, 18.92)
        val c = MapController.centred(fitted, centre, XY(0.0, 14.0))
        val t = MapController.targetShowing(centre, XY(0.0, 14.0), 13.2, 30.0)
        assertEquals(t.lat, c.target!!.latitude, 0.0)
        assertEquals(t.lon, c.target!!.longitude, 0.0)
        assertEquals(13.2, c.zoom, 0.0)
        assertEquals(30.0, c.bearing, 0.0)
        // The camera's target stays the view's middle (the crosshair, the scale): no padding left on the camera.
        assertTrue(doubleArrayOf(0.0, 0.0, 0.0, 0.0).contentEquals(c.padding))
    }

    @Test
    fun zoomingOntoAPointScalesTheScreenToTheMetresAsked() {
        // Each level halves the ground across the screen.
        assertEquals(17.0, MapController.zoomOnto(15.0, 1200.0, 300.0), 1e-12)
        assertEquals(14.0, MapController.zoomOnto(15.0, 150.0, 300.0), 1e-12)
        assertEquals(15.0, MapController.zoomOnto(15.0, 300.0, 300.0), 1e-12)
    }
}
