package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.TestApplication
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.TerrainMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The process set-up of `SatMapApplication` (without MapLibre) and the model, under the bare `TestApplication`. */
@RunWith(AndroidJUnit4::class)
class AppModelSmokeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
    }

    @After
    fun tearDown() {
        Defaults.setLaunchArguments(null)
        // Robolectric keeps AppLifecycle from one test to the next: forget this test's models.
        AppLifecycle.resetForTests()
    }

    @Test
    fun theModelStartsAsOnIOS() {
        assertTrue(app is TestApplication)
        val model = AppModel(app, noHeights)
        assertEquals(MeasureMode.Off, model.measure.value)
        assertTrue(model.following.value)
        assertTrue(model.lassoActive.value)
        assertFalse(model.isDrawingArea.value)
        assertNull(model.sheet.value)
        assertNull(model.selectedPoint.value)
        assertEquals(DroneReport(), model.droneReport.value)
        assertEquals(TerrainMode.contours, model.terrainMode.value)
        assertEquals(DistanceUnit.metric, model.distanceUnit.value)
        assertEquals(HeightUnit.metres, model.heightUnit.value)
        assertTrue(model.track.isEmpty())
        assertNull(model.map)
        assertEquals(emptyList<Any>(), model.offline.areas.value)
        assertEquals(emptyList<Any>(), model.kml.files.value)
    }

    @Test
    fun aTestCanInstallItsOwnModel() {
        val model = AppModel(app, noHeights)
        AppModel.shared = model
        assertSame(model, AppModel.shared)
        assertEquals(listOf(model.recorder, model.offline, model), AppLifecycle.registered)
    }

    @Test
    fun savedSettingsAreReadWithTheSwiftKeys() {
        Defaults.putString("terrainMode", "colours")
        Defaults.putString("distanceUnit", "nautical")
        Defaults.putString("heightUnit", "feet")
        val model = AppModel(app, noHeights)
        assertEquals(TerrainMode.colours, model.terrainMode.value)
        assertEquals(DistanceUnit.nautical, model.distanceUnit.value)
        assertEquals(HeightUnit.feet, model.heightUnit.value)
        assertEquals(DistanceUnit.nautical, model.scale.unit)
    }

    @Test
    fun theLegacyContourSwitchStillCounts() {
        Defaults.putBoolean("showContours", false)
        assertEquals(TerrainMode.off, AppModel(app, noHeights).terrainMode.value)
    }

    @Test
    fun launchExtrasAreReadFirstAndNeverWritten() {
        Defaults.putString("terrainMode", "contours")
        Defaults.setLaunchArguments(Bundle().apply {
            putString("terrainMode", "drone")
            putBoolean("zoomScript", true)
        })
        val model = AppModel(app, noHeights)
        assertEquals(TerrainMode.drone, model.terrainMode.value)
        assertEquals(true, Defaults.bool("zoomScript"))
        assertEquals("contours", Defaults.prefs.getString("terrainMode", null))
        assertFalse(Defaults.prefs.contains("zoomScript"))
    }

    @Test
    fun theFoldersAreTheIOSOnes() {
        assertEquals("SatMap", AppPaths.root.name)
        assertEquals(app.filesDir, AppPaths.root.parentFile)
        assertTrue(AppPaths.offline.isDirectory)
        assertTrue(AppPaths.kml.isDirectory)
        assertEquals("track.bin", AppPaths.track.name)
        val id = UUID.fromString("6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f")
        assertEquals("6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F", AppPaths.offlineArea(id).name)
        assertTrue(AppPaths.offlineArea(id).isDirectory)
    }

    @Test
    fun theNetworkSnapshotFollowsTheState() {
        val c = Connectivity.shared
        assertEquals(c.state.value.isOnline, c.isOnlineNow)
        assertEquals(c.state.value.isExpensive, c.isExpensiveNow)
    }
}
