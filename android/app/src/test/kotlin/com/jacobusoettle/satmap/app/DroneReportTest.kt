package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.location.GpsFix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drone mode's reporting lines (`updateDroneReport`, as `AppModel.swift`), from the shipped Aviation.tsv: you and the
 *  pin from the nearest airfield with a code (not a helipad) within 150 NM, else the nearest airfield of any kind, as
 *  the terrain mode, your position and the pin change. */
@RunWith(AndroidJUnit4::class)
class DroneReportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel

    /** Skukuza rest camp, 2.1 NM south of Skukuza airport (FASZ). */
    private val camp = GeoPoint(-24.9961, 31.5911)

    /** Londolozi's airstrip, which has no code: the nearest with one is Malamala (FAMD), 2.2 NM away. */
    private val londolozi = GeoPoint(-24.80809, 31.50456)

    companion object {
        /** Read once: some 84 000 places. */
        private var places: AviationIndex? = null
    }

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        val index = places ?: AviationIndex(app.assets.open("Aviation.tsv").use { it.readBytes() }.decodeToString())
            .also { places = it }
        model = AppModel(app, noHeights)
        model.aviationIndex = { index }
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    /** A position used (the recorder passes it on as it does every fix it uses). */
    private fun youAt(p: GeoPoint) {
        model.recorder.setForTests(
            GpsFix(p.lat, p.lon, 5.0, null, null, System.currentTimeMillis() / 1000.0, SystemClock.elapsedRealtime() / 1000.0),
        )
    }

    /** `north` metres north of `p`. */
    private fun north(p: GeoPoint, north: Double) = GeoPoint(p.lat + north / 111_195.0, p.lon)

    @Test
    fun outsideDroneModeThereIsNoReport() {
        youAt(camp)
        model.setSelectedPoint(londolozi)
        assertEquals(DroneReport(), model.droneReport.value)
    }

    @Test
    fun youAndThePinAreReportedFromTheNearestAirfieldWithACode() {
        model.setTerrainMode(TerrainMode.drone)
        // Neither yet.
        assertEquals(DroneReport(), model.droneReport.value)
        youAt(camp)
        assertEquals(DroneReport(you = "2.1 NM S of FASZ Skukuza"), model.droneReport.value)
        // An airstrip without a code is passed over for the nearest with one.
        model.setSelectedPoint(londolozi)
        assertEquals(DroneReport(you = "2.1 NM S of FASZ Skukuza", pin = "2.2 NM WNW of FAMD Malamala"), model.droneReport.value)
        // Over the airfield itself.
        model.setSelectedPoint(GeoPoint(-24.96085, 31.58859))
        assertEquals("Over FASZ Skukuza", model.droneReport.value.pin)
        model.setSelectedPoint(null)
        assertEquals(DroneReport(you = "2.1 NM S of FASZ Skukuza"), model.droneReport.value)
    }

    @Test
    fun leavingDroneModeClearsTheReportAndComingBackMakesItAgain() {
        model.setTerrainMode(TerrainMode.drone)
        youAt(camp)
        model.setSelectedPoint(londolozi)
        model.setTerrainMode(TerrainMode.contours)
        assertEquals(DroneReport(), model.droneReport.value)
        // Your moves aren't reported meanwhile.
        youAt(londolozi)
        assertEquals(DroneReport(), model.droneReport.value)
        model.setTerrainMode(TerrainMode.drone)
        assertEquals(DroneReport(you = "2.2 NM WNW of FAMD Malamala", pin = "2.2 NM WNW of FAMD Malamala"), model.droneReport.value)
    }

    @Test
    fun withoutAnAirfieldWithACodeWithin150NMTheNearestOfAnyKindIsUsed() {
        model.setTerrainMode(TerrainMode.drone)
        // Raoul Island (Kermadecs): an airstrip without a code.
        model.setSelectedPoint(north(GeoPoint(-29.24388, -177.93654), 1000.0))
        assertEquals("0.5 NM N of Raoul Island", model.droneReport.value.pin)
        // Gough Island: a helipad, with a code.
        model.setSelectedPoint(north(GeoPoint(-40.34793, -9.88131), -2000.0))
        assertEquals("1.1 NM S of FAGE Gough Island", model.droneReport.value.pin)
        // The South Atlantic, nothing within 150 NM: no line.
        model.setSelectedPoint(GeoPoint(-50.0, -30.0))
        assertEquals(DroneReport(), model.droneReport.value)
    }
}
