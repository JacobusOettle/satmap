package com.jacobusoettle.satmap.location

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.cos
import kotlin.math.roundToLong

/** The recorder's power policy on Robolectric's location manager, alarms and clock: the requests registered in each
 *  state (DESIGN.md §5.1), the 2 s foreground gate, the 10 s background duty cycle (re-registered at +7 s, a cached
 *  fix from before rejected, used from the 10 s mark), the track's spacing and segment breaks, sleep, peeks and their
 *  wake thresholds, significant motion and steps, Android Auto's screen counting as the foreground, and Stop. Fixes
 *  are handed to the registered listeners directly (the shadow would drop those under its own interval). */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowTriggerSensors::class])
class LocationRecorderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm get() = app.getSystemService(LocationManager::class.java)
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val home = GeoPoint(-25.0, 31.0)
    private val fixes = mutableListOf<GpsFix>()
    private val points = mutableListOf<TrackPoint>()

    @Before
    fun setUp() {
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        Defaults.init(app)
        AppLifecycle.init()
        ShadowTriggerSensors.reset()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    private fun recorder(): LocationRecorder = LocationRecorder(app).also { r ->
        r.onFix = { fixes += it }
        r.onTrackPoint = { points += it }
    }

    /** A recorder started on screen. */
    private fun onScreen(): LocationRecorder {
        val r = recorder()
        process.currentState = Lifecycle.State.RESUMED
        return r
    }

    private fun background() {
        process.currentState = Lifecycle.State.CREATED
    }

    private fun foreground() {
        process.currentState = Lifecycle.State.RESUMED
    }

    /** Time passes: the clock moves on and the recorder's alarms fire when due, in order. */
    private fun pass(seconds: Double) {
        val end = SystemClock.elapsedRealtime() + (seconds * 1000).roundToLong()
        while (true) {
            val next = alarms.scheduledAlarms.minByOrNull { it.triggerAtMs } ?: break
            if (next.triggerAtMs > end) break
            fire(next)
        }
        val rest = end - SystemClock.elapsedRealtime()
        if (rest > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(rest))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The clock moves on to the next alarm, which fires. */
    private fun fireNext() {
        fire(alarms.scheduledAlarms.minByOrNull { it.triggerAtMs }!!)
    }

    private fun fire(alarm: org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm) {
        val step = alarm.triggerAtMs - SystemClock.elapsedRealtime()
        if (step > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(step))
        alarms.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Seconds until the alarm fires. */
    private fun dueIn(tag: String): Double = (alarm(tag)!!.triggerAtMs - SystemClock.elapsedRealtime()) / 1000.0

    /** A position `north` and `east` metres from home. */
    private fun at(north: Double, east: Double = 0.0) = GeoPoint(
        home.lat + north / 111_195.0,
        home.lon + east / (111_195.0 * cos(home.lat * Math.PI / 180)),
    )

    private fun location(
        provider: String,
        p: GeoPoint,
        accuracy: Float = 5f,
        age: Double = 0.0,
        wall: Long = System.currentTimeMillis(),
    ) = Location(provider).apply {
        latitude = p.lat
        longitude = p.lon
        this.accuracy = accuracy
        time = wall
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - (age * 1e9).toLong()
    }

    /** The recorder's listener for `provider`, if one is registered. (The shadow deprecates reading them in favour of
     *  `simulateLocation`, which drops fixes under the request's own interval: the 2 s gate couldn't be tested.) */
    @Suppress("DEPRECATION")
    private fun listener(provider: String) = shadowOf(lm).getLocationUpdateListeners(provider).singleOrNull()

    /** Hands a fix to the listener registered for `provider`; false if there is none. */
    private fun deliver(provider: String, p: GeoPoint, accuracy: Float = 5f, age: Double = 0.0, wall: Long = System.currentTimeMillis()): Boolean {
        val l = listener(provider) ?: return false
        l.onLocationChanged(location(provider, p, accuracy, age, wall))
        return true
    }

    private fun gps(p: GeoPoint, accuracy: Float = 5f, age: Double = 0.0, wall: Long = System.currentTimeMillis()) =
        assertTrue("no GPS request", deliver(LocationManager.GPS_PROVIDER, p, accuracy, age, wall))

    private fun request(provider: String): LocationRequest? = shadowOf(lm).getLocationRequests(provider).singleOrNull()

    private fun assertRequest(provider: String, interval: Long, quality: Int, minInterval: Long = interval) {
        val r = request(provider)
        assertNotNull("no $provider request", r)
        assertEquals("$provider interval", interval, r!!.intervalMillis)
        assertEquals("$provider minimum interval", minInterval, r.minUpdateIntervalMillis)
        assertEquals("$provider quality", quality, r.quality)
    }

    private fun assertNone(vararg providers: String) {
        for (p in providers) assertNull("a $p request", request(p))
    }

    private fun alarm(tag: String) = alarms.scheduledAlarms.singleOrNull { it.tag == "satmap:$tag" }

    /** Phones have a significant-motion sensor (a one-shot wake-up trigger); Robolectric has none until one is added,
     *  which must be before the recorder is made. */
    private fun addSignificantMotion() {
        val sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_SIGNIFICANT_MOTION).setWakeUpFlag(true).build()
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(sensor)
    }

    /** A step detector (the wake-up kind), with the activity-recognition permission unless `granted` is false; before
     *  the recorder is made. */
    private fun addStepDetector(granted: Boolean = true): Sensor {
        val sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_DETECTOR).setWakeUpFlag(true).build()
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(sensor)
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        return sensor
    }

    /** One step, taken `ago` seconds before it is handed over (steps kept in the sensor's FIFO while the CPU slept). */
    private fun step(sensor: Sensor, ago: Double = 0.0) {
        val event = SensorEventBuilder.newBuilder(sensor, floatArrayOf(1f))
            .setTimestamp(SystemClock.elapsedRealtimeNanos() - (ago * 1e9).roundToLong())
            .build()
        shadowOf(app.getSystemService(SensorManager::class.java)).sendSensorEventToListeners(event, sensor)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** An Android Auto session showing SatMap (its screen doesn't matter here). */
    private class CarSession : Session() {
        override fun onCreateScreen(intent: Intent): Screen = object : Screen(carContext) {
            override fun onGetTemplate(): Template = MessageTemplate.Builder("SatMap").setTitle("SatMap").build()
        }
    }

    /** Fallen asleep (just now) after 120 s still on screen, with a good fix at home as the anchor. */
    private fun asleep(): LocationRecorder {
        val r = onScreen()
        gps(home)
        repeat(40) { if (r.power.value != LocationRecorder.Power.sleeping) fireNext() }
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        return r
    }

    // Authorization

    @Test
    fun theAuthorizationIsReadAsAndroidHasIt() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val r = recorder()
        assertEquals(LocationRecorder.Authorization.notDetermined, r.authorization.value)
        Defaults.putBoolean("locationPermissionAsked", true)
        r.authorizationChanged()
        assertEquals(LocationRecorder.Authorization.denied, r.authorization.value)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        r.authorizationChanged()
        assertEquals(LocationRecorder.Authorization.authorized, r.authorization.value)
        assertTrue(r.reducedAccuracy.value)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        r.authorizationChanged()
        assertFalse(r.reducedAccuracy.value)
        shadowOf(lm).setLocationEnabled(false)
        r.authorizationChanged()
        assertEquals(LocationRecorder.Authorization.servicesOff, r.authorization.value)
        assertFalse(r.reducedAccuracy.value)
    }

    @Test
    fun nothingStartsWithoutPermissionOrInTheBackground() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val r = recorder()
        r.start()
        foreground()
        assertNone(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        assertNull(shadowOf(app).nextStartedService)
        // Allowed while in the background: still nothing until the app is in the foreground.
        background()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        r.authorizationChanged()
        assertNone(LocationManager.GPS_PROVIDER)
        // The permission answer comes while on screen: recording starts, with the foreground service.
        foreground()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    // The request table

    @Test
    fun onScreenTheGpsAsksEvery2sAndTheNetworkOnlyWhileTheGpsSearches() {
        onScreen()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertRequest(LocationManager.NETWORK_PROVIDER, 5000, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
        assertNone(LocationManager.PASSIVE_PROVIDER)
        gps(home)
        assertNone(LocationManager.NETWORK_PROVIDER)
        pass(29.0)
        assertNone(LocationManager.NETWORK_PROVIDER)
        // 30 s without a GPS fix: searching again.
        pass(1.5)
        assertRequest(LocationManager.NETWORK_PROVIDER, 5000, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
        gps(home)
        assertNone(LocationManager.NETWORK_PROVIDER)
    }

    @Test
    fun theRequestsInEachState() {
        val r = onScreen()
        gps(home)
        // Background, acquiring: GPS every second until a fix is used.
        pass(10.0)
        background()
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNone(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        // Resting between fixes: nothing at all (the foreground service keeps the app alive).
        gps(home)
        assertNone(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        // Back on screen: the GPS every 2 s.
        foreground()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        // Asleep: coarse fixes only, ours every minute (at most every 30 s) and other apps' at most every 10 s.
        repeat(40) { if (r.power.value != LocationRecorder.Power.sleeping) fireNext() }
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertNone(LocationManager.GPS_PROVIDER)
        assertRequest(LocationManager.NETWORK_PROVIDER, 60_000, LocationRequestCompat.QUALITY_LOW_POWER, minInterval = 30_000)
        assertRequest(LocationManager.PASSIVE_PROVIDER, 10_000, LocationRequestCompat.QUALITY_LOW_POWER)
        // Peeking: the GPS alone, every second, on screen and off.
        pass(dueIn("peek"))
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNone(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        background()
        pass(dueIn("peek"))
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNone(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }

    @Test
    fun approximateOnlyUsesTheFusedProviderAtLowPower() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val r = onScreen()
        assertTrue(r.reducedAccuracy.value)
        assertNone(LocationManager.GPS_PROVIDER)
        val coarse = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        assertRequest(coarse, 2000, LocationRequestCompat.QUALITY_LOW_POWER)
        // Precise location switched on in Settings: the GPS.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        r.authorizationChanged()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        if (coarse == LocationManager.FUSED_PROVIDER) assertNone(LocationManager.FUSED_PROVIDER)
    }

    // On screen

    @Test
    fun onScreenOneFixIsUsedEvery2s() {
        onScreen()
        gps(at(0.0))
        assertEquals(1, fixes.size)
        pass(1.0)
        gps(at(10.0))
        pass(0.8)
        gps(at(20.0))
        assertEquals(1, fixes.size)
        pass(0.06) // 1.86 s after the last used: within the 0.15 s slack
        gps(at(30.0))
        assertEquals(2, fixes.size)
        assertEquals(at(30.0).lat, fixes.last().lat, 1e-12)
    }

    @Test
    fun staleFixesAndFixesWithoutAccuracyAreDropped() {
        val r = onScreen()
        gps(home, age = 20.0)
        listener(LocationManager.GPS_PROVIDER)!!
            .onLocationChanged(location(LocationManager.GPS_PROVIDER, home).apply { removeAccuracy() })
        assertNull(r.location.value)
        gps(home, age = 19.0)
        assertNotNull(r.location.value)
    }

    @Test
    fun aCoarseFixDoesNotReplaceARecentGoodOne() {
        val r = onScreen()
        gps(home)
        pass(2.0)
        gps(at(500.0), accuracy = 150f)
        assertEquals(5.0, r.location.value!!.horizontalAccuracy, 0.0)
        assertEquals(1, fixes.size)
        // A minute later it does.
        pass(60.0)
        gps(at(500.0), accuracy = 150f)
        assertEquals(150.0, r.location.value!!.horizontalAccuracy, 0.0)
    }

    // Background duty cycle

    @Test
    fun inTheBackgroundTheGpsIsWokenAt7sAndAFixIsUsedFromThe10sMark() {
        onScreen()
        gps(home)
        pass(10.0)
        background()
        // Acquiring: a fix cached from before the GPS was woken is refused.
        gps(home, age = 2.0)
        assertEquals(1, fixes.size)
        gps(at(5.0))
        assertEquals(2, fixes.size)
        // Resting until 7 s after the fix used (3 s before the next is due).
        assertNone(LocationManager.GPS_PROVIDER)
        pass(6.9)
        assertNone(LocationManager.GPS_PROVIDER)
        pass(0.2)
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        // Android hands back its cached fix on registering: taken before the GPS was woken, refused.
        gps(at(5.0), age = 1.1)
        // A new fix before the 10 s mark: refused.
        pass(1.0)
        gps(at(10.0))
        assertEquals(2, fixes.size)
        // At 9.5 s and after: used, and the GPS rests again.
        pass(1.5)
        gps(at(12.0))
        assertEquals(3, fixes.size)
        assertNone(LocationManager.GPS_PROVIDER)
        assertNotNull(alarm("duty"))
    }

    @Test
    fun inTheBackgroundAWorseFixIsTakenAfterWaiting() {
        onScreen()
        gps(home)
        pass(10.0)
        background()
        gps(at(5.0), accuracy = 30f)
        assertEquals(1, fixes.size)
        // Waited 20 s: any fix.
        pass(20.0)
        gps(at(5.0), accuracy = 30f)
        assertEquals(2, fixes.size)
    }

    // Track

    @Test
    fun trackPointsNeedTheirSpacingAndBreakAfter300sAnd200m() {
        val r = onScreen()
        val wall0 = 1_700_000_000_000L
        r.seed(TrackPoint(home.lat, home.lon, wall0 / 1000.0, true))
        // Closer than the fix's accuracy (5 m): GPS wander, not walking.
        gps(at(4.0), wall = wall0 + 2_000)
        assertEquals(0, points.size)
        pass(2.0)
        gps(at(6.0), wall = wall0 + 4_000)
        assertEquals(1, points.size)
        assertFalse(points.last().startsSegment)
        // Worse than 20 m: not recorded.
        pass(2.0)
        gps(at(100.0), accuracy = 25f, wall = wall0 + 6_000)
        assertEquals(1, points.size)
        // A gap of over 300 s and over 200 m: a new segment.
        pass(2.0)
        gps(at(260.0), wall = wall0 + 4_000 + 301_000)
        assertTrue(points.last().startsSegment)
        // Over 300 s but under 200 m: joined.
        pass(2.0)
        gps(at(400.0), wall = wall0 + 4_000 + 700_000)
        assertFalse(points.last().startsSegment)
        // Over 200 m but under 300 s: joined.
        pass(2.0)
        gps(at(700.0), wall = wall0 + 4_000 + 900_000)
        assertFalse(points.last().startsSegment)
        assertEquals(4, points.size)
        assertEquals(wall0 / 1000.0 + 904.0, points.last().time, 1e-9)
    }

    @Test
    fun theFirstPointEverStartsASegment() {
        onScreen()
        gps(home)
        assertEquals(listOf(true), points.map { it.startsSegment })
    }

    // Sleep and peeks

    @Test
    fun itSleepsAfter120sStill() {
        val r = onScreen()
        gps(home)
        pass(105.0)
        // GPS wander within 15 m isn't movement.
        gps(at(10.0))
        pass(10.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        pass(20.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertNull(alarm("stationary"))
        assertNotNull(alarm("peek"))
    }

    @Test
    fun movementPutsSleepOff() {
        val r = onScreen()
        gps(home)
        pass(100.0)
        gps(at(20.0))
        pass(100.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        pass(30.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun withoutAGoodFixItKeepsSearchingFor600s() {
        val r = onScreen()
        assertTrue(deliver(LocationManager.NETWORK_PROVIDER, home, accuracy = 80f))
        pass(590.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        pass(30.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun peeksComeEveryMinuteOnScreenEvery2MinutesInTheBackgroundAndLast15sAfterAGoodFix() {
        val r = asleep()
        assertEquals(60.0, dueIn("peek"), 0.0)
        pass(60.0)
        assertNotNull(request(LocationManager.GPS_PROVIDER))
        assertEquals(15.0, dueIn("peekEnd"), 0.0)
        pass(14.9)
        assertNotNull(request(LocationManager.GPS_PROVIDER))
        pass(0.1)
        assertNone(LocationManager.GPS_PROVIDER)
        assertEquals(60.0, dueIn("peek"), 0.0)
        background()
        assertEquals(120.0, dueIn("peek"), 0.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun withoutAGoodFixAPeekLasts45sAndItsFirstGoodFixWakes() {
        val r = onScreen()
        pass(600.0)
        repeat(5) { if (r.power.value != LocationRecorder.Power.sleeping) fireNext() }
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        pass(dueIn("peek"))
        // The GPS may need a cold start.
        assertEquals(45.0, dueIn("peekEnd"), 0.0)
        pass(44.9)
        assertNotNull(request(LocationManager.GPS_PROVIDER))
        // Fallen asleep without a good position: the first one is used.
        gps(at(3.0), accuracy = 20f)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        assertEquals(at(3.0).lat, r.location.value!!.lat, 1e-12)
    }

    @Test
    fun aPeekFixWakesOnlyWhenWeMovedBeyondItsError() {
        val r = asleep()
        pass(60.0)
        // 15 m: within max(20 m, 2 × accuracy). The dot is refreshed, the peek ends, nothing is recorded.
        val before = points.size
        gps(at(15.0))
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(at(15.0).lat, r.location.value!!.lat, 1e-12)
        assertNone(LocationManager.GPS_PROVIDER)
        assertEquals(before, points.size)
        // 25 m: moved. Awake, the fix used and (10 m or better) recorded.
        pass(60.0)
        gps(at(25.0))
        assertEquals(LocationRecorder.Power.active, r.power.value)
        assertEquals(before + 1, points.size)
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNotNull(alarm("stationary"))
    }

    @Test
    fun aLessPrecisePeekFixMustBeTwiceItsErrorAway() {
        val r = asleep()
        pass(60.0)
        // 15 m accuracy: the threshold is 2 × 15 = 30 m, above the 20 m floor. 28 m: still here.
        gps(at(28.0), accuracy = 15f)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertNone(LocationManager.GPS_PROVIDER)
        // 32 m: moved.
        pass(60.0)
        gps(at(32.0), accuracy = 15f)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun whileMeasuringALessPrecisePeekFixMustBeOneAndAHalfTimesItsErrorAway() {
        val r = asleep()
        r.measuring = true
        // 10 m accuracy: the threshold is 1.5 × 10 = 15 m, above the 8 m floor. 14 m: still here.
        gps(at(14.0), accuracy = 10f)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertNone(LocationManager.GPS_PROVIDER)
        // 16 m: moved.
        pass(15.0)
        gps(at(16.0), accuracy = 10f)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun whileMeasuringAPeekComesAtOnceEvery15sAndReactsToSmallerMoves() {
        val r = asleep()
        r.measuring = true
        // At once.
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        gps(at(7.0))
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(15_000L, alarm("peek")!!.triggerAtMs - SystemClock.elapsedRealtime())
        pass(15.0)
        // Over max(8 m, 1.5 × accuracy): awake.
        gps(at(14.0))
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun aCoarseJumpWakesAndTheFirstTrackPointWaitsForAPreciseFix() {
        val r = asleep()
        val before = points.size
        // Within what the coarse fix's error explains: still asleep.
        assertTrue(deliver(LocationManager.NETWORK_PROVIDER, at(250.0), accuracy = 100f))
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        // Another app's GPS fix (passive) far off: awake.
        assertTrue(deliver(LocationManager.PASSIVE_PROVIDER, at(350.0), accuracy = 100f))
        assertEquals(LocationRecorder.Power.active, r.power.value)
        // For 30 s only a fix of 10 m or better is recorded.
        gps(at(360.0), accuracy = 15f)
        assertEquals(at(360.0).lat, r.location.value!!.lat, 1e-12)
        assertEquals(before, points.size)
        pass(2.0)
        gps(at(370.0), accuracy = 8f)
        assertEquals(before + 1, points.size)
        pass(2.0)
        gps(at(390.0), accuracy = 15f)
        assertEquals(before + 2, points.size)
    }

    @Test
    fun aSleepingRecorderPeeksAtOnceOnReturningToTheForeground() {
        val r = asleep()
        background()
        assertNone(LocationManager.GPS_PROVIDER)
        foreground()
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    // Motion

    @Test
    fun significantMotionWakesAtOnceAndIsArmedAgain() {
        addSignificantMotion()
        val r = asleep()
        // Armed since recording began.
        assertEquals(1, ShadowTriggerSensors.armed.size)
        ShadowTriggerSensors.fire()
        assertEquals(LocationRecorder.Power.active, r.power.value)
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNull(alarm("peek"))
        assertNotNull(alarm("stationary"))
        // It disarms itself when it fires, and is armed again.
        assertEquals(1, ShadowTriggerSensors.armed.size)
        // Asked again on becoming active, it isn't requested twice (Android would refuse that); Stop disarms it.
        background()
        foreground()
        background()
        r.stop()
        assertEquals(0, ShadowTriggerSensors.armed.size)
    }

    @Test
    fun significantMotionKeepsSleepOffForAMinuteAfterIt() {
        addSignificantMotion()
        val r = onScreen()
        gps(home)
        pass(110.0)
        ShadowTriggerSensors.fire()
        // From 120 s still it would sleep; motion said moving under a minute ago.
        pass(55.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        // A minute without motion: asleep.
        pass(15.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun motionKeepsSleepOffFor600sAtMost() {
        addSignificantMotion()
        val r = onScreen()
        gps(home)
        pass(110.0)
        // Moving by the sensor (not by the GPS) from 110 s on: a trigger every 30 s until 680 s; now it is 710 s.
        repeat(20) {
            ShadowTriggerSensors.fire()
            pass(30.0)
        }
        // The check at 705 s: 595 s since motion began.
        assertEquals(LocationRecorder.Power.active, r.power.value)
        // At 720 s: 610 s.
        pass(10.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun significantMotionInTheMovingItFellAsleepInDoesntWake() {
        addSignificantMotion()
        val r = onScreen()
        gps(home)
        pass(110.0)
        // As above: moving by the sensor from 110 s, asleep at 720 s.
        repeat(20) {
            ShadowTriggerSensors.fire()
            pass(30.0)
        }
        pass(10.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        // At 720 s, 40 s after the last trigger: the same drive, which iOS isn't told about again. Asleep, armed again.
        ShadowTriggerSensors.fire()
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(1, ShadowTriggerSensors.armed.size)
        // A minute without motion ends it; the next trigger is new motion: awake.
        pass(70.0)
        ShadowTriggerSensors.fire()
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun withoutTheSensorNothingIsArmed() {
        onScreen()
        assertEquals(0, ShadowTriggerSensors.armed.size)
    }

    @Test
    fun sixStepsIn6sWakeAtOnce() {
        val steps = addStepDetector()
        val r = asleep()
        repeat(5) {
            step(steps)
            pass(1.0)
        }
        // Five steps: a wake is pending, 6 s after the first.
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(1.0, dueIn("motion"), 0.0)
        step(steps)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        assertNull(alarm("motion"))
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertNotNull(alarm("stationary"))
    }

    @Test
    fun aFewStepsWakeAfter6sIfTheWalkingGoesOn() {
        val steps = addStepDetector()
        val r = asleep()
        // Steps at 0, 1, 2 and 3 s.
        step(steps)
        repeat(3) {
            pass(1.0)
            step(steps)
        }
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(3.0, dueIn("motion"), 0.0)
        // At 6 s: three steps in the last 6 s.
        pass(3.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun stepsThatStopDontWake() {
        val steps = addStepDetector()
        val r = asleep()
        step(steps)
        pass(1.0)
        step(steps)
        // At 6 s only the second step is that recent: they stopped.
        pass(6.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertNull(alarm("motion"))
        assertNotNull(alarm("peek"))
        // A step later waits again.
        pass(10.0)
        step(steps)
        assertEquals(6.0, dueIn("motion"), 0.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun aStepKeepsSleepOffFor15sAfterIt() {
        val steps = addStepDetector()
        val r = onScreen()
        gps(home)
        pass(110.0)
        step(steps)
        // From 120 s still it would sleep; a step came 10 s ago.
        pass(10.0)
        assertEquals(LocationRecorder.Power.active, r.power.value)
        // 25 s without a step: asleep.
        pass(15.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun stepsKeepSleepOffFor600sAtMost() {
        val steps = addStepDetector()
        val r = onScreen()
        gps(home)
        pass(110.0)
        // Pacing on the spot from 110 s, a step every 10 s until 700 s; now it is 710 s.
        repeat(60) {
            step(steps)
            pass(10.0)
        }
        // The check at 705 s: 595 s since the steps began.
        assertEquals(LocationRecorder.Power.active, r.power.value)
        // At 720 s: 610 s.
        step(steps)
        pass(10.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
    }

    @Test
    fun walkingOnTheSpotDoesntWakeOnceItHasFallenAsleep() {
        val steps = addStepDetector()
        val r = onScreen()
        gps(home)
        // Walking on the spot, two steps a second, for 900 s: sleep is put off for 600 s, then the same walk doesn't
        // wake it again (iOS is told no new activity while it goes on); only the peeks run.
        var sleptAt: Double? = null
        repeat(1800) { i ->
            step(steps)
            pass(0.5)
            val t = (i + 1) * 0.5
            if (r.power.value == LocationRecorder.Power.sleeping) {
                if (sleptAt == null) sleptAt = t
            } else {
                assertNull("awake again at $t s", sleptAt)
            }
        }
        assertEquals(600.0, sleptAt!!, 15.0)
        // A 20 s pause ends that walk. Six steps are then a new one: awake.
        pass(20.0)
        repeat(5) {
            step(steps)
            pass(0.5)
        }
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        step(steps)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun stepsHandedOverTogetherCountWhenTheyWereTaken() {
        val steps = addStepDetector()
        val r = asleep()
        background()
        pass(300.0)
        // The CPU wakes and the sensor's FIFO is emptied: six steps taken 30 s apart, handed over at once. That isn't
        // steady walking: no wake now, and the pending one finds that the steps stopped.
        for (ago in listOf(150.0, 120.0, 90.0, 60.0, 30.0, 0.0)) step(steps, ago)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        assertEquals(6.0, dueIn("motion"), 0.0)
        pass(6.0)
        assertEquals(LocationRecorder.Power.sleeping, r.power.value)
        // Six steps taken within 6 s are, even when handed over together: awake.
        for (ago in listOf(2.5, 2.0, 1.5, 1.0, 0.5, 0.0)) step(steps, ago)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    @Test
    fun theStepsAreCountedOnlyWithThePermissionAndOnce() {
        val sm = app.getSystemService(SensorManager::class.java)
        addStepDetector(granted = false)
        val r = onScreen()
        assertEquals(0, shadowOf(sm).listeners.size)
        // Allowed in the dialog (PermissionFlow then calls startMotion), and again on becoming active.
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        r.startMotion()
        r.startMotion()
        assertEquals(1, shadowOf(sm).listeners.size)
        background()
        foreground()
        assertEquals(1, shadowOf(sm).listeners.size)
        // Stop ends them.
        background()
        r.stop()
        assertEquals(0, shadowOf(sm).listeners.size)
    }

    @Test
    fun theWakeUpStepDetectorIsPreferred() {
        val sm = app.getSystemService(SensorManager::class.java)
        val plain = SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_DETECTOR).build()
        shadowOf(sm).addSensor(plain)
        val wakeUp = addStepDetector()
        onScreen()
        assertTrue(shadowOf(sm).hasListener(shadowOf(sm).listeners.single(), wakeUp))
        assertFalse(shadowOf(sm).hasListener(shadowOf(sm).listeners.single(), plain))
    }

    // Android Auto

    @Test
    fun theCarScreenCountsAsForegroundWithThePhoneLocked() {
        val r = recorder()
        // The phone locked, SatMap not on it.
        assertNone(LocationManager.GPS_PROVIDER)
        val session = CarSession()
        val car = SessionController(session, TestCarContext.createCarContext(app), Intent())
        AppLifecycle.observeCar(session)
        car.moveToState(Lifecycle.State.RESUMED)
        // Recording as on screen, with its service.
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        gps(home)
        assertEquals(1, fixes.size)
        // The car screen gone too: background recording, the next fix taken now.
        car.moveToState(Lifecycle.State.CREATED)
        assertRequest(LocationManager.GPS_PROVIDER, 1000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationRecorder.Power.active, r.power.value)
    }

    // Stop

    @Test
    fun stopEndsEverythingUntilTheAppIsNextOpened() {
        val r = asleep()
        background()
        r.stop()
        assertEquals(LocationRecorder.Power.active, r.power.value)
        assertNone(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        assertEquals(emptyList<Any>(), alarms.scheduledAlarms)
        assertNotNull(r.location.value)
        // Opened again: recording, with its service.
        shadowOf(app).clearStartedServices()
        foreground()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test
    fun stopOnScreenEndsRecordingWhenSatMapLeavesTheScreen() {
        val r = onScreen()
        gps(home)
        // The notification shade or split screen over the map: SatMap stays on screen, and the map keeps its location.
        r.stop()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        pass(2.0)
        gps(at(10.0))
        assertEquals(2, fixes.size)
        // Gone to the background: everything off.
        background()
        assertNone(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        assertEquals(emptyList<Any>(), alarms.scheduledAlarms)
        assertEquals(at(10.0).lat, r.location.value!!.lat, 1e-12)
        // Opened again: recording, with its service.
        shadowOf(app).clearStartedServices()
        foreground()
        assertRequest(LocationManager.GPS_PROVIDER, 2000, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    // Compass

    @Test
    fun theHeadingIsTheTopOfTheScreens() {
        // The phone flat, its top to the north (the rotation vector's identity).
        val flat = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(0.0, LocationRecorder.heading(flat, Surface.ROTATION_0) % 360, 1e-3)
        assertEquals(90.0, LocationRecorder.heading(flat, Surface.ROTATION_90), 1e-3)
        assertEquals(180.0, LocationRecorder.heading(flat, Surface.ROTATION_180), 1e-3)
        assertEquals(270.0, LocationRecorder.heading(flat, Surface.ROTATION_270), 1e-3)
    }

    @Test
    fun theCompassSendsEvery2DegreesNothingWhenItCantTellAndStopsWhenNotWanted() {
        val sm = app.getSystemService(SensorManager::class.java)
        val sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_ROTATION_VECTOR).build()
        shadowOf(sm).addSensor(sensor)
        val r = recorder()
        val headings = mutableListOf<Double?>()
        r.onHeading = { headings += it }
        r.headingWanted = true
        assertEquals(1, shadowOf(sm).listeners.size)
        // Flat, turned so that its top points `h` degrees east of north.
        fun turn(h: Double, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
            val half = Math.toRadians(-h) / 2
            val values = floatArrayOf(0f, 0f, kotlin.math.sin(half).toFloat(), kotlin.math.cos(half).toFloat())
            shadowOf(sm).sendSensorEventToListeners(
                SensorEventBuilder.newBuilder(sensor, values).setAccuracy(accuracy).build(),
            )
        }
        turn(10.0)
        turn(11.0) // under 2°: not sent
        turn(12.5)
        turn(12.5, SensorManager.SENSOR_STATUS_UNRELIABLE)
        turn(30.0, SensorManager.SENSOR_STATUS_UNRELIABLE) // "can't tell" once
        turn(12.5)
        // Landscape, turned to the left: the top of the screen is the phone's right side.
        r.screenRotation = Surface.ROTATION_90
        turn(10.0)
        r.headingWanted = false
        assertEquals(0, shadowOf(sm).listeners.size)
        val expected = listOf(10.0, 12.5, null, 12.5, 100.0, null)
        assertEquals(expected.size, headings.size)
        for ((e, h) in expected.zip(headings)) {
            if (e == null) assertNull(h) else assertEquals(e, h!!, 0.01)
        }
    }

    @Test
    fun noHeadingIsSentWhenNotWanted() {
        val r = recorder()
        val headings = mutableListOf<Double?>()
        r.onHeading = { headings += it }
        // No compass on this device (Robolectric has no sensors): nothing, as iOS without headingAvailable.
        r.headingWanted = true
        r.headingWanted = false
        assertEquals(emptyList<Double?>(), headings)
    }

    @Test
    fun screenTestsCanSetThePositionAndThePermissionState() {
        val r = recorder()
        val fix = GpsFix(home.lat, home.lon, 5.0, 612.0, 3.0, 1_700_000_000.0, SystemClock.elapsedRealtime() / 1000.0)
        r.setForTests(fix, LocationRecorder.Authorization.authorized, reducedAccuracy = true)
        assertEquals(fix, r.location.value)
        assertEquals(LocationRecorder.Authorization.authorized, r.authorization.value)
        assertTrue(r.reducedAccuracy.value)
        // Passed on as a fix used, but not recorded, and nothing is asked of the location manager.
        assertEquals(listOf(fix), fixes)
        assertEquals(emptyList<TrackPoint>(), points)
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        r.setForTests(null, LocationRecorder.Authorization.servicesOff)
        assertNull(r.location.value)
        assertEquals(LocationRecorder.Authorization.servicesOff, r.authorization.value)
        assertFalse(r.reducedAccuracy.value)
        assertEquals(1, fixes.size)
    }

    @Test
    fun theTestPositionsAreWhereTheTestsSayTheyAre() {
        assertEquals(25.0, Geo.distance(home, at(25.0)), 0.2)
        assertEquals(350.0, Geo.distance(home, at(350.0)), 1.5)
    }
}
