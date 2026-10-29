package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.map.MovablePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos

/** The measuring tool's state machine (state spec §1.3) with a fake map whose crosshair the test moves and whose
 *  screen distance is one dp per metre: the button and map taps, the 6 dp "no leg of 0 m" and 20 dp "close on A"
 *  thresholds, undo back to aiming, closing, clearing, the elevation resets in `setMeasure` and `movePoint`, the
 *  selected spot and its name, "Go to coordinates", and the derived distance and area. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppModelMeasureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val main = StandardTestDispatcher()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val home = GeoPoint(-25.0, 31.0)

    /** Each spot's height by the metres north of home it is (a height per spot), after 100 ms. */
    private class Heights : ElevationSource {
        val asked = mutableListOf<GeoPoint>()
        var answer: (GeoPoint) -> Double? = { p -> Math.round((p.lat + 25.0) * 111_195.0) + 1000.0 }

        override suspend fun elevation(p: GeoPoint): Double? {
            asked += p
            delay(100)
            return answer(p)
        }
    }

    /** A map whose crosshair the test puts somewhere, and one dp per metre on screen. */
    private class FakeMap : MapHost {
        override var centerCoordinate = GeoPoint(0.0, 0.0)
        override var isOnScreen = true
        val calls = mutableListOf<String>()

        override fun pointsApart(a: GeoPoint, b: GeoPoint) = Geo.distance(a, b)
        override fun center(on: GeoPoint, animated: Boolean) {
            calls += "center $on $animated"
        }

        override fun zoom(onto: GeoPoint, metersAcross: Double) {
            calls += "zoom $onto $metersAcross"
        }

        override fun zoom(to: GeoBounds) {
            calls += "zoom $to"
        }

        override fun locationUpdated(fix: GpsFix) {}
        override fun headingUpdated(heading: Double?) {}
        override fun reloadImagery() {}
    }

    private lateinit var heights: Heights
    private lateinit var map: FakeMap
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        heights = Heights()
        map = FakeMap()
        model = AppModel(app, heights)
        model.map = map
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
        Dispatchers.resetMain()
    }

    /** A spot `north` and `east` metres from home. */
    private fun at(north: Double, east: Double = 0.0) = GeoPoint(
        home.lat + north / 111_195.0,
        home.lon + east / (111_195.0 * cos(home.lat * Math.PI / 180)),
    )

    /** The crosshair on the spot, then the measure button. */
    private fun placeAt(p: GeoPoint) {
        map.centerCoordinate = p
        model.measureButtonTapped()
    }

    private fun TestScope.pass(ms: Long = 100) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun fixAt(p: GeoPoint) =
        GpsFix(p.lat, p.lon, 5.0, null, null, System.currentTimeMillis() / 1000.0, SystemClock.elapsedRealtime() / 1000.0)

    private val measure get() = model.measure.value

    @Test
    fun theButtonAimsThenPlacesPointsAtTheCrosshairAndClearsAClosedShape() {
        val a = at(0.0)
        val b = at(100.0)
        val c = at(100.0, 100.0)
        model.measureButtonTapped()
        assertEquals(MeasureMode.Aiming, measure)
        assertNull(model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
        placeAt(a)
        assertEquals(MeasureMode.Placed(a), measure)
        assertEquals(a, model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
        placeAt(b)
        assertEquals(MeasureMode.Path(listOf(a, b)), measure)
        assertEquals(b, model.selectedPoint.value)
        // From the second point the distance is no longer from you.
        assertFalse(model.recorder.measuring)
        placeAt(c)
        assertEquals(MeasureMode.Path(listOf(a, b, c)), measure)
        // The crosshair back on A: the shape closes (the selected spot stays the last point).
        placeAt(at(10.0))
        assertEquals(MeasureMode.Closed(listOf(a, b, c)), measure)
        assertEquals(c, model.selectedPoint.value)
        assertFalse(model.recorder.measuring)
        model.measureButtonTapped()
        assertEquals(MeasureMode.Off, measure)
        assertNull(model.selectedPoint.value)
        assertFalse(model.recorder.measuring)
    }

    @Test
    fun aSecondPointWithin6dpOfTheLastIsNoLeg() {
        val a = at(0.0)
        model.measureButtonTapped()
        placeAt(a)
        // Another tap without moving the map.
        placeAt(a)
        assertEquals(MeasureMode.Placed(a), measure)
        placeAt(at(5.9))
        assertEquals(MeasureMode.Placed(a), measure)
        val b = at(6.1)
        placeAt(b)
        assertEquals(MeasureMode.Path(listOf(a, b)), measure)
        placeAt(at(6.1 + 5.9))
        assertEquals(MeasureMode.Path(listOf(a, b)), measure)
        val c = at(6.1 + 6.1)
        placeAt(c)
        assertEquals(MeasureMode.Path(listOf(a, b, c)), measure)
    }

    @Test
    fun theShapeClosesWithin20dpOfAOnlyFromThreePoints() {
        val a = at(0.0)
        val b = at(0.0, 100.0)
        model.measureButtonTapped()
        placeAt(a)
        placeAt(b)
        // Two points: back near A is a third point, not a shape.
        val c = at(10.0)
        placeAt(c)
        assertEquals(MeasureMode.Path(listOf(a, b, c)), measure)
        // 20.1 dp from A: a fourth point.
        val d = at(0.0, -20.1)
        placeAt(d)
        assertEquals(MeasureMode.Path(listOf(a, b, c, d)), measure)
        // 19.9 dp from A: closed.
        placeAt(at(-19.9))
        assertEquals(MeasureMode.Closed(listOf(a, b, c, d)), measure)
        assertEquals(d, model.selectedPoint.value)
        // A map tap on a closed shape starts again at the crosshair (the button clears instead).
        val e = at(1000.0)
        map.centerCoordinate = e
        model.mapTapped(a)
        assertEquals(MeasureMode.Placed(e), measure)
        assertEquals(e, model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
    }

    @Test
    fun aMapTapSelectsTheSpotUnlessMeasuringWhenItPlacesAPointAtTheCrosshair() {
        val tapped = at(500.0, 500.0)
        model.mapTapped(tapped)
        assertEquals(tapped, model.selectedPoint.value)
        assertEquals(MeasureMode.Off, measure)
        model.measureButtonTapped()
        val crosshair = at(0.0)
        map.centerCoordinate = crosshair
        // The finger covers the spot it taps: the point goes under the crosshair.
        model.mapTapped(tapped)
        assertEquals(MeasureMode.Placed(crosshair), measure)
        assertEquals(crosshair, model.selectedPoint.value)
        val next = at(50.0)
        map.centerCoordinate = next
        model.mapTapped(tapped)
        assertEquals(MeasureMode.Path(listOf(crosshair, next)), measure)
    }

    @Test
    fun withoutAMapNoPointIsPlaced() {
        model.map = null
        model.measureButtonTapped()
        model.measureButtonTapped()
        model.mapTapped(at(10.0))
        assertEquals(MeasureMode.Aiming, measure)
        assertNull(model.selectedPoint.value)
    }

    @Test
    fun closeShapeNeedsAPathOfThreePoints() {
        val a = at(0.0)
        val b = at(100.0)
        model.closeMeasureShape()
        assertEquals(MeasureMode.Off, measure)
        model.measureButtonTapped()
        placeAt(a)
        model.closeMeasureShape()
        assertEquals(MeasureMode.Placed(a), measure)
        placeAt(b)
        model.closeMeasureShape()
        assertEquals(MeasureMode.Path(listOf(a, b)), measure)
        val c = at(100.0, 100.0)
        placeAt(c)
        model.closeMeasureShape()
        assertEquals(MeasureMode.Closed(listOf(a, b, c)), measure)
    }

    @Test
    fun undoReopensAShapeTakesPointsOffAndEndsAiming() {
        val a = at(0.0)
        val b = at(100.0)
        val c = at(100.0, 100.0)
        // Off and aiming: nothing changes, not even the selected spot.
        model.mapTapped(at(-300.0))
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Off, measure)
        assertEquals(at(-300.0), model.selectedPoint.value)
        model.measureButtonTapped()
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Aiming, measure)
        assertEquals(at(-300.0), model.selectedPoint.value)
        placeAt(a)
        placeAt(b)
        placeAt(c)
        model.closeMeasureShape()
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Path(listOf(a, b, c)), measure)
        assertEquals(c, model.selectedPoint.value)
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Path(listOf(a, b)), measure)
        assertEquals(b, model.selectedPoint.value)
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Placed(a), measure)
        assertEquals(a, model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
        model.undoMeasurePoint()
        assertEquals(MeasureMode.Aiming, measure)
        assertNull(model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
    }

    @Test
    fun clearEndsMeasuringAndTheSelection() {
        model.measureButtonTapped()
        placeAt(at(0.0))
        placeAt(at(100.0))
        model.clearMeasure()
        assertEquals(MeasureMode.Off, measure)
        assertNull(model.selectedPoint.value)
        assertFalse(model.recorder.measuring)
        assertNull(model.markerElevation.value)
        assertNull(model.lastElevation.value)
    }

    @Test
    fun aimingEndsTheCrosshairsSpot() = runTest(main) {
        model.measureButtonTapped()
        // The map moves the crosshair's spot while aiming.
        model.aim.set(at(30.0))
        pass()
        assertEquals(at(30.0), model.aim.point.value)
        assertEquals(1030.0, model.aim.elevation.value)
        placeAt(at(30.0))
        assertNull(model.aim.point.value)
        assertNull(model.aim.elevation.value)
        assertNull(model.aim.elevationPoint.value)
    }

    @Test
    fun theFirstAndLastHeightsGoWhenTheirPointsChange() = runTest(main) {
        val a = at(0.0)
        val b = at(100.0)
        val c = at(200.0)
        model.measureButtonTapped()
        placeAt(a)
        assertNull(model.markerElevation.value)
        pass()
        assertEquals(1000.0, model.markerElevation.value)
        assertNull(model.lastElevation.value)
        val askedForA = heights.asked.count { it == a } // A's own, and the selected spot's
        // A second point: A's height stays (not asked again), B's is looked up.
        placeAt(b)
        assertEquals(1000.0, model.markerElevation.value)
        assertNull(model.lastElevation.value)
        pass()
        assertEquals(1100.0, model.lastElevation.value)
        // A third: the last height goes until C's is known.
        placeAt(c)
        assertNull(model.lastElevation.value)
        pass()
        assertEquals(1200.0, model.lastElevation.value)
        assertEquals(askedForA, heights.asked.count { it == a })
        // Closed: the last point is the same, so its height stays, and nothing is looked up for a shape.
        val asked = heights.asked.size
        model.closeMeasureShape()
        pass()
        assertEquals(1000.0, model.markerElevation.value)
        assertEquals(1200.0, model.lastElevation.value)
        // Reopened: the same points, nothing new.
        model.undoMeasurePoint()
        pass()
        assertEquals(1200.0, model.lastElevation.value)
        assertEquals(asked, heights.asked.size)
        // One point off: B is last again, its height looked up again (and B is the selected spot again).
        model.undoMeasurePoint()
        assertNull(model.lastElevation.value)
        pass()
        assertEquals(1100.0, model.lastElevation.value)
        assertEquals(listOf(b, b), heights.asked.drop(asked))
        // Back to one point: no last height.
        model.undoMeasurePoint()
        assertNull(model.lastElevation.value)
        assertEquals(1000.0, model.markerElevation.value)
        // Back to aiming: no first one either.
        model.undoMeasurePoint()
        assertNull(model.markerElevation.value)
        pass()
        assertNull(model.markerElevation.value)
        assertNull(model.lastElevation.value)
    }

    @Test
    fun aLateHeightForAPointNoLongerThereIsDropped() = runTest(main) {
        val a = at(0.0)
        val b = at(100.0)
        model.measureButtonTapped()
        placeAt(a)
        placeAt(b)
        // B's height is under way when B goes.
        model.undoMeasurePoint()
        pass()
        assertNull(model.lastElevation.value)
        assertEquals(1000.0, model.markerElevation.value)
    }

    @Test
    fun aMovedMeasuringPointHasItsHeightsLookedUpOnceLetGo() = runTest(main) {
        val a = at(0.0)
        val b = at(100.0)
        val c = at(200.0)
        model.measureButtonTapped()
        placeAt(a)
        placeAt(b)
        placeAt(c)
        pass()
        assertEquals(1000.0, model.markerElevation.value)
        assertEquals(1200.0, model.lastElevation.value)
        val asked = heights.asked.size

        // The middle point moves: while it moves nothing is looked up; it is the selected spot.
        val b2 = at(150.0)
        model.movePoint(MovablePoint.Measure(1), at(120.0), done = false)
        model.movePoint(MovablePoint.Measure(1), b2, done = false)
        pass()
        assertEquals(MeasureMode.Path(listOf(a, b2, c)), measure)
        assertEquals(b2, model.selectedPoint.value)
        assertEquals(asked, heights.asked.size)
        // Let go: the selected spot's height; the first and last heights stay.
        model.movePoint(MovablePoint.Measure(1), b2, done = true)
        pass()
        assertEquals(1150.0, model.selectedElevation.value)
        assertEquals(1000.0, model.markerElevation.value)
        assertEquals(1200.0, model.lastElevation.value)
        assertEquals(listOf(b2), heights.asked.drop(asked))

        // A moves: its height goes once let go, and is looked up again.
        val a2 = at(-50.0)
        model.movePoint(MovablePoint.Measure(0), a2, done = false)
        assertEquals(1000.0, model.markerElevation.value)
        model.movePoint(MovablePoint.Measure(0), a2, done = true)
        assertNull(model.markerElevation.value)
        assertEquals(1200.0, model.lastElevation.value)
        pass()
        assertEquals(950.0, model.markerElevation.value)
        assertEquals(950.0, model.selectedElevation.value)

        // The last point the same.
        val c2 = at(250.0)
        model.movePoint(MovablePoint.Measure(2), c2, done = true)
        assertNull(model.lastElevation.value)
        assertEquals(950.0, model.markerElevation.value)
        pass()
        assertEquals(1250.0, model.lastElevation.value)
        assertEquals(MeasureMode.Path(listOf(a2, b2, c2)), measure)

        // A point that isn't there: nothing.
        model.movePoint(MovablePoint.Measure(3), at(999.0), done = true)
        assertEquals(MeasureMode.Path(listOf(a2, b2, c2)), measure)
        assertEquals(c2, model.selectedPoint.value)
    }

    @Test
    fun movingTheOnlyPointResetsOnlyTheFirstHeight() = runTest(main) {
        val a = at(0.0)
        model.measureButtonTapped()
        placeAt(a)
        pass()
        assertEquals(1000.0, model.markerElevation.value)
        val a2 = at(40.0)
        model.movePoint(MovablePoint.Measure(0), a2, done = true)
        assertEquals(MeasureMode.Placed(a2), measure)
        assertNull(model.markerElevation.value)
        pass()
        assertEquals(1040.0, model.markerElevation.value)
        assertNull(model.lastElevation.value)
        // Moving a point while not measuring: nothing.
        model.clearMeasure()
        model.movePoint(MovablePoint.Measure(0), a, done = true)
        assertEquals(MeasureMode.Off, measure)
    }

    @Test
    fun theTappedSpotMovesAndHasItsHeightLookedUpOnceLetGo() = runTest(main) {
        val p = at(10.0)
        model.pointTapped(p, "Gate")
        pass()
        assertEquals(1010.0, model.selectedElevation.value)
        val asked = heights.asked.size
        val q = at(20.0)
        model.movePoint(MovablePoint.Selected, at(15.0), done = false)
        model.movePoint(MovablePoint.Selected, q, done = false)
        pass()
        assertEquals(q, model.selectedPoint.value)
        // A moved point loses the KML point's name; its height is the one from before until it is let go.
        assertNull(model.selectedName.value)
        assertEquals(1010.0, model.selectedElevation.value)
        assertEquals(asked, heights.asked.size)
        model.movePoint(MovablePoint.Selected, q, done = true)
        assertNull(model.selectedElevation.value)
        pass()
        assertEquals(1020.0, model.selectedElevation.value)
        assertEquals(listOf(q), heights.asked.drop(asked))
        // After being let go, a newly selected spot is looked up at once again.
        model.mapTapped(at(30.0))
        pass()
        assertEquals(1030.0, model.selectedElevation.value)
    }

    @Test
    fun aKMLPointsNameShowsUntilTheSelectionChanges() {
        val gate = at(10.0)
        model.pointTapped(gate, "Gate")
        assertEquals(gate, model.selectedPoint.value)
        assertEquals("Gate", model.selectedName.value)
        // The same spot again: still selected, with the new name (an empty one is none).
        model.setSelectedPoint(gate)
        assertEquals("Gate", model.selectedName.value)
        model.pointTapped(gate, "")
        assertNull(model.selectedName.value)
        model.pointTapped(gate, "Main gate")
        assertEquals("Main gate", model.selectedName.value)
        model.pointTapped(at(20.0), null)
        assertNull(model.selectedName.value)
        model.pointTapped(gate, "Gate")
        model.mapTapped(at(30.0))
        assertNull(model.selectedName.value)
        model.pointTapped(gate, "Gate")
        // Measuring from it: a point placed elsewhere ends the name.
        model.measureButtonTapped()
        placeAt(at(500.0))
        assertNull(model.selectedName.value)
        model.pointTapped(gate, "Gate")
        model.clearMeasure()
        assertNull(model.selectedPoint.value)
        assertNull(model.selectedName.value)
    }

    @Test
    fun goToCoordinatesStartsMeasuringFromTheSpotAndShowsYouTooWithin50km() {
        model.setSheet(ActiveSheet.coordinates)
        val far = at(60_000.0)
        // No position: centred on the spot.
        model.measure(from = far)
        assertNull(model.sheet.value)
        assertFalse(model.following.value)
        assertEquals(MeasureMode.Placed(far), measure)
        assertEquals(far, model.selectedPoint.value)
        assertTrue(model.recorder.measuring)
        assertEquals(listOf("center $far true"), map.calls)
        // You 60 km away: centred too.
        model.recorder.setForTests(fixAt(home))
        map.calls.clear()
        model.setFollowing(true)
        model.measure(from = far)
        assertFalse(model.following.value)
        assertEquals(listOf("center $far true"), map.calls)
        // Within 50 km: you and the spot.
        map.calls.clear()
        val near = at(49_000.0)
        model.measure(from = near)
        assertEquals(MeasureMode.Placed(near), measure)
        assertEquals(near, model.selectedPoint.value)
        assertEquals(listOf("zoom ${GeoBounds(points = listOf(home, near))}"), map.calls)
    }

    @Test
    fun goToCoordinatesLooksUpTheSpotsHeightAgain() = runTest(main) {
        val a = at(0.0)
        model.measureButtonTapped()
        placeAt(a)
        pass()
        assertEquals(1000.0, model.markerElevation.value)
        val there = at(300.0)
        model.measure(from = there)
        assertNull(model.markerElevation.value)
        pass()
        assertEquals(1300.0, model.markerElevation.value)
        assertEquals(1300.0, model.selectedElevation.value)
    }

    @Test
    fun theDistanceIsFromYouThenAlongThePointsAndAroundAShape() {
        val a = at(0.0)
        val b = at(300.0)
        val c = at(300.0, 400.0)
        assertNull(model.markerDistance)
        model.measureButtonTapped()
        assertNull(model.markerDistance)
        placeAt(a)
        // One point and no position: nothing to measure from.
        assertNull(model.markerDistance)
        val me = at(-120.0)
        model.recorder.setForTests(fixAt(me))
        assertEquals(Geo.distance(me, a), model.markerDistance!!, 1e-9)
        assertEquals(120.0, model.markerDistance!!, 0.1)
        assertNull(model.measuredArea)
        placeAt(b)
        assertEquals(300.0, model.markerDistance!!, 0.1)
        placeAt(c)
        assertEquals(Geo.pathLength(listOf(a, b, c)), model.markerDistance!!, 1e-9)
        assertNull(model.measuredArea)
        model.closeMeasureShape()
        // Around the shape, back to A: 300 + 400 + 500 m, and its area.
        assertEquals(1200.0, model.markerDistance!!, 0.5)
        assertEquals(Geo.ringArea(listOf(a, b, c)), model.measuredArea!!, 1e-9)
        assertEquals(60_000.0, model.measuredArea!!, 60.0)
        model.clearMeasure()
        assertNull(model.markerDistance)
        assertNull(model.measuredArea)
    }
}
