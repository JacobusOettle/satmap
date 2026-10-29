package com.jacobusoettle.satmap.car

import android.app.Application
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.MapHost
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.map.MapController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.robolectric.Shadows.shadowOf

/** The car map's camera (DESIGN.md §6, CarPlayMapController.swift): where it starts, following you until moved, the
 *  host's scroll, pinch and visible area, and the template's zoom and follow, against a fake camera (MapLibre's native
 *  map can't run in unit tests). */
@RunWith(AndroidJUnit4::class)
class CarMapControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel
    private lateinit var car: CarMapController

    /** A camera move, as the fake camera records it. */
    sealed interface Move {
        data class Show(val region: MapController.Region) : Move
        data class Restore(val position: CameraPosition) : Move
        data class Center(val on: GeoPoint) : Move
        data class Zoom(val levels: Double, val focus: Pair<Float, Float>?, val animated: Boolean) : Move
        data class Scroll(val dx: Float, val dy: Float) : Move
        data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) : Move
    }

    private class FakeCamera(override val position: CameraPosition? = null) : CarMapController.Camera {
        val moves = ArrayList<Move>()

        override fun restore(position: CameraPosition) {
            moves += Move.Restore(position)
        }

        override fun show(region: MapController.Region) {
            moves += Move.Show(region)
        }

        override fun center(on: GeoPoint) {
            moves += Move.Center(on)
        }

        override fun zoomBy(levels: Double, focus: PointF?, animated: Boolean) {
            moves += Move.Zoom(levels, focus?.let { it.x to it.y }, animated)
        }

        override fun scrollBy(dx: Float, dy: Float) {
            moves += Move.Scroll(dx, dy)
        }

        override fun padding(left: Int, top: Int, right: Int, bottom: Int) {
            moves += Move.Padding(left, top, right, bottom)
        }
    }

    /** The phone's map, as far as the car's start needs it. */
    private class PhoneMap(override val centerCoordinate: GeoPoint) : MapHost {
        override val isOnScreen = true
        override fun pointsApart(a: GeoPoint, b: GeoPoint) = 0.0
        override fun center(on: GeoPoint, animated: Boolean) {}
        override fun zoom(onto: GeoPoint, metersAcross: Double) {}
        override fun zoom(to: GeoBounds) {}
        override fun locationUpdated(fix: GpsFix) {}
        override fun headingUpdated(heading: Double?) {}
        override fun reloadImagery() {}
    }

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
        AppModel.shared = model
        car = CarMapController(TestCarContext.createCarContext(app), model)
    }

    @After
    fun tearDown() {
        car.release()
        AppLifecycle.resetForTests()
    }

    private fun fix(lat: Double, lon: Double) = GpsFix(lat, lon, 5.0, 300.0, 3.0, 1_800_000_000.0, 100.0)

    /** A fix from the recorder, through the model (`AppModel.locationUpdated` → `carMap.locationUpdated`). */
    private fun gps(f: GpsFix) {
        model.recorder.setForTests(f)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(camera: FakeCamera = FakeCamera(), width: Int = 800, height: Int = 480): FakeCamera {
        car.startForTests(camera, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        return camera
    }

    @Test
    fun withoutYouOrThePhonesMapItStartsOverKrugerAboutOneAndAHalfKilometresAcross() {
        val camera = start()
        val region = MapController.Region.around(GeoPoint(-24.99, 31.59), 1500.0)
        assertEquals(listOf<Move>(Move.Show(region)), camera.moves)
        // iOS: MKCoordinateRegion(center:latitudinalMeters: 1500, longitudinalMeters: 1500), 750 m each way.
        assertEquals(2 * 750 / 111_320.0, region.latDelta, 1e-12)
        assertTrue(car.following)
    }

    @Test
    fun itStartsWhereThePhonesMapIs() {
        val centre = GeoPoint(-33.92, 18.92)
        val phone = PhoneMap(centre)
        model.map = phone
        val camera = start()
        assertEquals(listOf<Move>(Move.Show(MapController.Region.around(centre, 1500.0))), camera.moves)
    }

    @Test
    fun itStartsAroundYouAndCentresOnYouWhenShown() {
        model.recorder.setForTests(fix(-25.5, 31.0))
        model.map = PhoneMap(GeoPoint(-33.92, 18.92)) // you come first
        car.isShown = true
        val camera = start()
        val you = GeoPoint(-25.5, 31.0)
        assertEquals(listOf(Move.Show(MapController.Region.around(you, 1500.0)), Move.Center(you)), camera.moves)
    }

    @Test
    fun itFollowsYouWhileShownUntilMoved() {
        model.carMap = car
        val camera = start()
        camera.moves.clear()

        gps(fix(-25.0, 31.0)) // not shown: the dot moves, the map doesn't
        assertEquals(emptyList<Move>(), camera.moves)

        car.isShown = true
        gps(fix(-25.001, 31.0))
        assertEquals(listOf<Move>(Move.Center(GeoPoint(-25.001, 31.0))), camera.moves)

        // A drag on the car's screen: the map follows the finger (the host gives the distance scrolled, so the map
        // moves the other way) and stops following you.
        camera.moves.clear()
        car.onScroll(30f, -12f)
        assertEquals(listOf<Move>(Move.Scroll(-30f, 12f)), camera.moves)
        assertFalse(car.following)
        gps(fix(-25.002, 31.0))
        assertEquals(listOf<Move>(Move.Scroll(-30f, 12f)), camera.moves)

        // The follow button: back onto you, and following again.
        camera.moves.clear()
        car.recentre()
        assertTrue(car.following)
        assertEquals(listOf<Move>(Move.Center(GeoPoint(-25.002, 31.0))), camera.moves)
        gps(fix(-25.003, 31.0))
        assertEquals(Move.Center(GeoPoint(-25.003, 31.0)), camera.moves.last())

        // The host's pan mode stops following too.
        camera.moves.clear()
        car.dragBegan()
        assertFalse(car.following)
        gps(fix(-25.004, 31.0))
        assertEquals(emptyList<Move>(), camera.moves)
    }

    @Test
    fun theFollowButtonWithoutAFixOnlyFollowsAgain() {
        val camera = start()
        camera.moves.clear()
        car.dragBegan()
        car.recentre()
        assertTrue(car.following)
        assertEquals(emptyList<Move>(), camera.moves)
    }

    @Test
    fun theZoomButtonsZoomOneLevelAroundTheMiddleAnimated() {
        val camera = start()
        camera.moves.clear()
        car.zoom(1.0) // CarPlay zoom(by: 0.5): half the camera distance
        car.zoom(-1.0) // zoom(by: 2)
        assertEquals(listOf<Move>(Move.Zoom(1.0, null, true), Move.Zoom(-1.0, null, true)), camera.moves)
        assertTrue(car.following) // zooming keeps following, as on CarPlay
    }

    @Test
    fun aPinchZoomsAroundItsFocusAndTheHostsOwnZoomAroundTheMiddle() {
        val camera = start()
        camera.moves.clear()
        car.onScale(200f, 120f, 2f)
        car.onScale(-1f, -1f, 0.5f) // no focus: the host's own zoom
        car.onScale(300f, -1f, 1.25f)
        car.onScale(10f, 10f, 0f) // nothing to scale by
        assertEquals(
            listOf<Move>(
                Move.Zoom(1.0, 200f to 120f, false),
                Move.Zoom(-1.0, null, false),
                Move.Zoom(kotlin.math.log2(1.25), null, false),
            ),
            camera.moves,
        )
    }

    @Test
    fun flingsAndTapsDoNothing() {
        val camera = start()
        camera.moves.clear()
        car.onFling(2_000f, -500f)
        car.onClick(100f, 100f)
        assertEquals(emptyList<Move>(), camera.moves)
        assertTrue(car.following)
    }

    @Test
    fun theVisibleAreaBecomesTheMapsPadding() {
        // Told before the map is there: applied as it starts.
        car.onVisibleAreaChanged(Rect(120, 0, 800, 440))
        val camera = start(width = 800, height = 480)
        assertEquals(
            listOf(Move.Show(MapController.Region.around(CarMapController.DEFAULT_CENTRE, 1500.0)), Move.Padding(120, 0, 0, 40)),
            camera.moves,
        )
        camera.moves.clear()
        car.onVisibleAreaChanged(Rect(0, 60, 700, 480))
        assertEquals(listOf<Move>(Move.Padding(0, 60, 100, 0)), camera.moves)
    }

    @Test
    fun aNewSurfaceStartsWhereTheLastOneLeft() {
        val position = CameraPosition.Builder().target(LatLng(-25.1, 31.2)).zoom(13.5).build()
        val first = start(FakeCamera(position))
        car.dragBegan()
        car.surfaceDestroyedForTests()
        first.moves.clear()
        car.zoom(1.0) // no map now
        car.onScroll(5f, 5f)
        assertEquals(emptyList<Move>(), first.moves)

        val second = start(FakeCamera())
        assertEquals(listOf<Move>(Move.Restore(position)), second.moves)
        assertFalse(car.following) // still where it was moved to
    }

    @Test
    fun reloadAndLowMemoryWithoutAMapDoNothing() {
        car.reload()
        car.onLowMemory()
        val camera = start()
        car.reload() // no imagery without MapLibre: nothing to ask again
        assertEquals(1, camera.moves.size)
    }
}
