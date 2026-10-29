package com.jacobusoettle.satmap.app

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.TrackLog
import com.jacobusoettle.satmap.core.TrackPoint
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.map.TileRepository
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** `AppModel`'s M1 state with fakes for the map, the car map and the heights: units and terrain mode under the Swift
 *  keys, the track, following and My location, fixes passed on to the maps, the heights of you and of the selected
 *  spot, and the reload's timing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val main = StandardTestDispatcher()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val lm get() = app.getSystemService(LocationManager::class.java)
    private val home = GeoPoint(-25.0, 31.0)

    private class Heights(var answer: (GeoPoint) -> Double? = { 812.0 }) : ElevationSource {
        val asked = mutableListOf<GeoPoint>()

        override suspend fun elevation(p: GeoPoint): Double? {
            asked += p
            delay(100)
            return answer(p)
        }
    }

    private class FakeMap : MapHost {
        override var centerCoordinate = GeoPoint(0.0, 0.0)
        override var isOnScreen = true
        val calls = mutableListOf<String>()
        val fixes = mutableListOf<GpsFix>()
        val headings = mutableListOf<Double?>()
        var reloads = 0

        override fun pointsApart(a: GeoPoint, b: GeoPoint) = 0.0
        override fun center(on: GeoPoint, animated: Boolean) {
            calls += "center $on $animated"
        }

        override fun zoom(onto: GeoPoint, metersAcross: Double) {
            calls += "zoom $onto $metersAcross"
        }

        override fun zoom(to: GeoBounds) {
            calls += "zoom $to"
        }

        override fun locationUpdated(fix: GpsFix) {
            fixes += fix
        }

        override fun headingUpdated(heading: Double?) {
            headings += heading
        }

        override fun reloadImagery() {
            reloads += 1
        }
    }

    private class FakeCar : CarMapHost {
        val fixes = mutableListOf<GpsFix>()
        var reloads = 0

        override fun locationUpdated(fix: GpsFix) {
            fixes += fix
        }

        override fun reload() {
            reloads += 1
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        TileRepository.init(app)
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        Defaults.setLaunchArguments(null)
        AppLifecycle.resetForTests()
        Dispatchers.resetMain()
    }

    /** Both clocks: the recorder's and the reload's (`elapsedRealtime`) and the coroutines' virtual time. */
    private fun TestScope.pass(seconds: Double) {
        val ms = (seconds * 1000).toLong()
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun recording() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        process.currentState = Lifecycle.State.RESUMED
    }

    /** A GPS fix `north` metres north of home, handed to the recorder's GPS listener (the shadow's `simulateLocation`
     *  would drop fixes under the request's own interval). */
    @Suppress("DEPRECATION")
    private fun gps(north: Double, accuracy: Float = 5f) {
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = home.lat + north / 111_195.0
            longitude = home.lon
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val listeners = shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER)
        assertEquals(1, listeners.size)
        listeners.single().onLocationChanged(loc)
    }

    @Test
    fun theUnitsCycleAndAreSavedUnderTheSwiftKeys() {
        val model = AppModel(app, Heights())
        model.nextDistanceUnit()
        assertEquals(DistanceUnit.imperial, model.distanceUnit.value)
        assertEquals("imperial", Defaults.prefs.getString("distanceUnit", null))
        assertEquals(DistanceUnit.imperial, model.scale.unit)
        model.nextDistanceUnit()
        assertEquals("nautical", Defaults.prefs.getString("distanceUnit", null))
        model.nextDistanceUnit()
        assertEquals(DistanceUnit.metric, model.distanceUnit.value)
        assertEquals("metric", Defaults.prefs.getString("distanceUnit", null))
        model.nextHeightUnit()
        assertEquals(HeightUnit.feet, model.heightUnit.value)
        assertEquals("feet", Defaults.prefs.getString("heightUnit", null))
        model.nextDistanceUnit()
        // A new model (the next launch) reads them back.
        val next = AppModel(app, Heights())
        assertEquals(DistanceUnit.imperial, next.distanceUnit.value)
        assertEquals(DistanceUnit.imperial, next.scale.unit)
        assertEquals(HeightUnit.feet, next.heightUnit.value)
    }

    @Test
    fun theTerrainModeIsSavedOnEverySet() {
        val model = AppModel(app, Heights())
        model.setTerrainMode(TerrainMode.contoursAndColours)
        assertEquals("contoursAndColours", Defaults.prefs.getString("terrainMode", null))
        model.setTerrainMode(model.terrainMode.value.next)
        assertEquals(TerrainMode.colours, model.terrainMode.value)
        assertEquals("colours", Defaults.prefs.getString("terrainMode", null))
        // The same value again is written again (the Swift didSet runs on every set).
        Defaults.prefs.edit().remove("terrainMode").apply()
        model.setTerrainMode(TerrainMode.colours)
        assertEquals("colours", Defaults.prefs.getString("terrainMode", null))
        assertEquals(TerrainMode.colours, AppModel(app, Heights()).terrainMode.value)
    }

    @Test
    fun theTerrainModeFallsBackOnTheLegacyContourSwitch() {
        assertEquals(TerrainMode.contours, AppModel(app, Heights()).terrainMode.value)
        Defaults.putBoolean("showContours", true)
        assertEquals(TerrainMode.contours, AppModel(app, Heights()).terrainMode.value)
        Defaults.putBoolean("showContours", false)
        assertEquals(TerrainMode.off, AppModel(app, Heights()).terrainMode.value)
        // A saved mode wins over the legacy switch.
        Defaults.putString("terrainMode", "drone")
        assertEquals(TerrainMode.drone, AppModel(app, Heights()).terrainMode.value)
        // iOS reads "-showContours NO" given at launch.
        Defaults.prefs.edit().clear().apply()
        Defaults.setLaunchArguments(Bundle().apply { putString("showContours", "NO") })
        assertEquals(TerrainMode.off, AppModel(app, Heights()).terrainMode.value)
    }

    @Test
    fun theScaleBarFollowsTheDistanceUnit() {
        val model = AppModel(app, Heights())
        model.scale.update(metersPerPoint = 10.0)
        assertEquals("1 km", model.scale.label.value)
        assertEquals(100.0, model.scale.width.value, 0.0)
        model.nextDistanceUnit()
        assertEquals(DistanceUnit.imperial.scale(10.0, MapScale.maxWidth)!!.label, model.scale.label.value)
    }

    @Test
    fun theTrackIsLoadedAtStartAndClearedOnRequest() {
        val log = TrackLog(AppPaths.track)
        log.clear()
        val first = TrackPoint(-25.0, 31.0, 1_700_000_000.0, true)
        val last = TrackPoint(-25.001, 31.0, 1_700_000_010.0, false)
        log.append(first)
        log.append(last)
        assertEquals(2, log.loadAll().size) // waits for the appends
        val model = AppModel(app, Heights())
        assertEquals(listOf(first, last), model.track)
        assertEquals(0, model.trackRevision.value)
        model.clearTrack()
        assertTrue(model.track.isEmpty())
        assertEquals(1, model.trackRevision.value)
        assertFalse(AppPaths.track.exists())
    }

    @Test
    fun trackPointsAreAppendedAndSavedAndTheRecorderIsSeeded() = runTest(main) {
        val log = TrackLog(AppPaths.track)
        log.clear()
        log.append(TrackPoint(home.lat, home.lon, 1_700_000_000.0, true))
        log.loadAll()
        val model = AppModel(app, Heights())
        recording()
        runCurrent()
        // Within 3 m of the last point loaded: no new point (the recorder was seeded with it).
        gps(north = 2.0)
        assertEquals(0, model.trackRevision.value)
        pass(2.0)
        gps(north = 30.0)
        assertEquals(1, model.trackRevision.value)
        assertEquals(2, model.track.size)
        // Written in the background, 32 bytes a point.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AppPaths.track.length() < 64 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(64, AppPaths.track.length())
        model.clearTrack()
        assertEquals(2, model.trackRevision.value)
        // Seeded with nothing: the next fix starts the track again, whatever the spacing.
        pass(2.0)
        gps(north = 31.0)
        assertEquals(listOf(true), model.track.map { it.startsSegment })
    }

    @Test
    fun fixesGoToBothMapsAndHeadingsToThePhoneMap() = runTest(main) {
        val model = AppModel(app, Heights())
        val map = FakeMap()
        val car = FakeCar()
        model.map = map
        model.carMap = car
        recording()
        runCurrent()
        gps(north = 0.0)
        assertEquals(1, map.fixes.size)
        assertEquals(1, car.fixes.size)
        assertEquals(model.recorder.location.value, map.fixes.single())
        model.recorder.onHeading?.invoke(42.0)
        assertEquals(listOf<Double?>(42.0), map.headings)
    }

    @Test
    fun myLocationFollowsFirstThenZoomsTo300m() = runTest(main) {
        val model = AppModel(app, Heights())
        val map = FakeMap()
        model.map = map
        // No position yet: it follows, and there is nothing to centre on.
        model.setFollowing(false)
        model.centerOnUser()
        assertTrue(model.following.value)
        assertEquals(emptyList<String>(), map.calls)
        recording()
        runCurrent()
        gps(north = 0.0)
        val me = model.recorder.location.value!!.point
        model.setFollowing(false)
        model.centerOnUser()
        assertTrue(model.following.value)
        assertEquals(listOf("center $me true"), map.calls)
        model.centerOnUser()
        assertEquals(listOf("center $me true", "zoom $me 300.0"), map.calls)
    }

    @Test
    fun yourHeightIsLookedUpWhileTheMapIsOnScreenAndChangesByWholeMetres() = runTest(main) {
        var height = 812.4
        val heights = Heights { height }
        val model = AppModel(app, heights)
        val map = FakeMap()
        model.map = map
        recording()
        runCurrent()
        gps(north = 0.0)
        pass(0.1)
        assertEquals(812.4, model.userElevation.value)
        // Under half a metre different: not published.
        height = 812.3
        pass(2.0)
        gps(north = 10.0)
        pass(0.1)
        assertEquals(2, heights.asked.size)
        assertEquals(812.4, model.userElevation.value)
        height = 813.0
        pass(2.0)
        gps(north = 20.0)
        pass(0.1)
        assertEquals(813.0, model.userElevation.value)
        // The map not on screen: no lookups.
        map.isOnScreen = false
        pass(2.0)
        gps(north = 40.0)
        pass(0.1)
        assertEquals(3, heights.asked.size)
    }

    @Test
    fun theSelectedSpotsHeightIsLookedUpOnce() = runTest(main) {
        val heights = Heights { if (it == home) 640.0 else null }
        val model = AppModel(app, heights)
        model.setSelectedPoint(home)
        assertEquals(home, model.selectedPoint.value)
        assertNull(model.selectedName.value)
        pass(0.1)
        assertEquals(640.0, model.selectedElevation.value)
        // The same spot again: nothing new.
        model.setSelectedPoint(home)
        pass(0.1)
        assertEquals(1, heights.asked.size)
        // Another spot: the height goes until its own is known.
        val there = GeoPoint(-25.1, 31.0)
        model.setSelectedPoint(there)
        assertNull(model.selectedElevation.value)
        pass(0.1)
        assertNull(model.selectedElevation.value)
        assertEquals(listOf(home, there), heights.asked)
        model.setSelectedPoint(null)
        assertNull(model.selectedElevation.value)
    }

    @Test
    fun aReloadWaitsForTheForegroundAndComesAtMostEvery20s() = runTest(main) {
        val model = AppModel(app, Heights())
        val map = FakeMap()
        val car = FakeCar()
        model.map = map
        model.carMap = car
        model.requestReload()
        pass(30.0)
        assertEquals(0, map.reloads)
        // Back in the foreground: the pending reload, at once.
        process.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(1, map.reloads)
        assertEquals(1, car.reloads)
        model.requestReload()
        pass(5.0)
        model.requestReload()
        pass(14.8)
        assertEquals(1, map.reloads)
        pass(0.4)
        assertEquals(2, map.reloads)
        pass(60.0)
        assertEquals(2, map.reloads)
        // 20 s after the last one: at once.
        model.requestReload()
        runCurrent()
        assertEquals(3, map.reloads)
    }

    @Test
    fun aReloadAsksAgainForHeightsThatCouldNotBeFound() = runTest(main) {
        var height: Double? = null
        val heights = Heights { height }
        val model = AppModel(app, heights)
        model.setSelectedPoint(home)
        pass(0.1)
        assertNull(model.selectedElevation.value)
        process.currentState = Lifecycle.State.RESUMED
        runCurrent()
        height = 640.0
        model.requestReload()
        pass(0.1)
        assertEquals(640.0, model.selectedElevation.value)
    }

    @Test
    fun theNetworkComingBackReloadsTheTiles() = runTest(main) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        // Phone without any network at launch: the callback never comes, so the first state is read at once.
        for (c in shadowOf(cm).networkCallbacks.toList()) cm.unregisterNetworkCallback(c)
        shadowOf(cm).setActiveNetworkInfo(null)
        Connectivity.init(app)
        val callback = shadowOf(cm).networkCallbacks.single()
        assertFalse(Connectivity.shared.state.value.isOnline)
        assertFalse(Connectivity.shared.isOnlineNow)
        process.currentState = Lifecycle.State.RESUMED
        val model = AppModel(app, Heights())
        val map = FakeMap()
        model.map = map
        runCurrent()
        val network = ShadowNetwork.newInstance(1)
        val wifi = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        callback.onCapabilitiesChanged(network, wifi)
        assertEquals(Connectivity.State(isOnline = true, isExpensive = false), Connectivity.shared.state.value)
        assertTrue(Connectivity.shared.isOnlineNow)
        runCurrent()
        assertEquals(1, map.reloads)
        // Still online (now metered): not an edge.
        val mobile = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        callback.onCapabilitiesChanged(network, mobile)
        assertTrue(Connectivity.shared.isExpensiveNow)
        pass(30.0)
        assertEquals(1, map.reloads)
        callback.onLost(network)
        assertFalse(Connectivity.shared.state.value.isOnline)
        runCurrent()
        callback.onCapabilitiesChanged(network, wifi)
        runCurrent()
        assertEquals(2, map.reloads)
    }
}
