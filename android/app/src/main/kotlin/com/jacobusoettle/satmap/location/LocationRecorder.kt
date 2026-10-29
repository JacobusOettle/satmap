package com.jacobusoettle.satmap.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.LocationPolicy
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A position as the Swift uses `CLLocation`, immutable (`android.location.Location` is mutable and never leaves
 *  `LocationRecorder`). */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    /** `Location.accuracy`, metres. */
    val horizontalAccuracy: Double,
    /** Metres above SEA LEVEL (Android's own altitude is above the ellipsoid), null when unknown. */
    val altitude: Double?,
    /** Of that altitude. */
    val verticalAccuracy: Double?,
    /** Seconds since 1970 (`TrackPoint.time`). */
    val timestamp: Double,
    /** Seconds, on the `SystemClock.elapsedRealtime` clock. */
    val elapsed: Double,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** Records the GPS track with a battery-first policy:
 *  - On screen: GPS at best accuracy, one fix used every 2 s.
 *  - In the background: one fix every 10 s; between fixes the GPS request is removed so the GPS can power down (iOS
 *    lowers the accuracy to 3 km), and it is registered again shortly before the next fix is due.
 *  - Not moving for 2 minutes: sleep mode (GPS off, coarse fixes only). It wakes when the motion sensors report
 *    walking/driving, when a periodic short GPS check shows we moved, or on a large coarse position change.
 *
 *  iOS never stops its updates, because under When-In-Use permission stopping them in the background would end
 *  background recording. On Android the foreground service (`LocationService`) keeps the app and its while-in-use
 *  access alive instead, so the GPS request itself can go between fixes; the network and passive requests play the
 *  "3 km" role. Timers are `WakeTimer`s (alarms on the `elapsedRealtime` clock), and all recorder times are
 *  `SystemClock.elapsedRealtime()` in seconds.
 *
 *  Create and use on the main thread only; location updates are delivered on the main executor. */
class LocationRecorder(private val context: Context) : AppLifecycle.Observer {
    enum class Power { active, sleeping }

    /** iOS folds "location services off" into denied; Android tells them apart (its hint opens another page). */
    enum class Authorization { notDetermined, denied, servicesOff, authorized }

    private val _location = MutableStateFlow<GpsFix?>(null)

    /** Last fix used. */
    val location: StateFlow<GpsFix?> = _location.asStateFlow()
    private val _power = MutableStateFlow(Power.active)
    val power: StateFlow<Power> = _power.asStateFlow()
    private val _authorization = MutableStateFlow(Authorization.notDetermined)
    val authorization: StateFlow<Authorization> = _authorization.asStateFlow()

    /** Precise location switched off for the app (approximate only): fixes are kilometres off and no track can be
     *  recorded. */
    private val _reducedAccuracy = MutableStateFlow(false)
    val reducedAccuracy: StateFlow<Boolean> = _reducedAccuracy.asStateFlow()

    /** Called on the main thread for each new track point. */
    var onTrackPoint: ((TrackPoint) -> Unit)? = null

    /** Called on the main thread for each fix that is used (≤ every 2 s on screen, ≤ every 10 s in background). */
    var onFix: ((GpsFix) -> Unit)? = null

    /** Called on the main thread with the compass heading of the top of the screen, in degrees from true north (from
     *  magnetic north until there is a position), or null when the compass can't tell. */
    var onHeading: ((Double?) -> Unit)? = null

    /** Whether the compass is wanted (the phone's screen shows the map): it runs only then. */
    var headingWanted: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updateHeading()
        }

    /** How the screen is turned (`Surface.ROTATION_*`, set by the phone's map): the compass gives the heading of its
     *  top. Each reading is turned with it, so nothing more is done here. */
    var screenRotation: Int = Surface.ROTATION_0

    /** Set while the measuring marker is placed: on screen, sleep-mode GPS checks run every 15 s instead of every
     *  minute and react to smaller moves, so the live distance follows even steps motion detection misses. The setter
     *  runs on every assignment, as the Swift didSet. */
    var measuring: Boolean = false
        set(value) {
            field = value
            if (value && isForeground && _power.value == Power.sleeping) startPeek()
        }

    private val lm: LocationManager = context.getSystemService(LocationManager::class.java)
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false
    private var isForeground = AppLifecycle.isForeground

    /** Stop was pressed while SatMap was on screen: everything stops when it goes to the background. */
    private var stopInBackground = false

    private var lastUsedAt = Double.NEGATIVE_INFINITY
    private var lastRecorded: TrackPoint? = null

    /** After waking, the first track point waits for a fix of 10 m or better (or 30 s), to avoid spikes. */
    private var preciseFixNeededUntil = Double.NEGATIVE_INFINITY

    // Stationary detection.
    private var movementAnchor: GpsFix? = null
    private var lastMovementAt = now()

    /** Last fix of 50 m or better, and when the GPS started searching without one. */
    private var lastGoodFixAt: Double? = null
    private var searchingSince = now()
    private val stationaryTimer = WakeTimer(context, "stationary") { stationaryTick() }

    // Motion: significant motion (no permission needed); the step detector comes in M5 (DESIGN.md §5.1). Without the
    // sensor the GPS peeks and coarse jumps alone notice movement, as on an iPhone without motion permission.
    private val significantMotion: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var significantMotionArmed = false
    private var lastSignificantMotionAt: Double? = null
    private var motionMovingSince: Double? = null
    private val pendingMotionWake = WakeTimer(context, "motion") {}

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            // A one-shot sensor: it is disarmed now.
            significantMotionArmed = false
            significantMotionDetected()
        }
    }

    // Background duty cycle.
    private var gpsResting = false
    private var acquiringSince = now()
    private val dutyTimer = WakeTimer(context, "duty") { startAcquiring() }

    // Sleep mode.
    private var sleepAnchor: GpsFix? = null
    private var peeking = false
    private val peekTimer = WakeTimer(context, "peek") { startPeek() }
    private val peekEndTimer = WakeTimer(context, "peekEnd") { endPeek() }

    // The location requests (DESIGN.md §5.1): one listener per slot, so each can be removed alone.
    private enum class Slot { gps, network, passive }

    /** A request as registered; equal requests aren't registered again (the Swift `if desiredAccuracy != desired`). */
    private data class Request(val provider: String, val intervalMs: Long, val minIntervalMs: Long?, val quality: Int) {
        fun compat(): LocationRequestCompat = LocationRequestCompat.Builder(intervalMs)
            .setQuality(quality)
            .setMinUpdateDistanceMeters(0f)
            .apply { minIntervalMs?.let { setMinUpdateIntervalMillis(it) } }
            .build()
    }

    private val listeners = Slot.entries.associateWith { Listener(it) }
    private val registered = HashMap<Slot, Request>()

    /** The last fix from the GPS slot. The network request runs only while the GPS has had none for 30 s (it is
     *  searching): iOS also shows a Wi-Fi position quickly at a cold start. */
    private var lastGpsFixAt: Double? = null

    /** Brings the network request back 30 s after the GPS's last fix. */
    private val searchTimer = WakeTimer(context, "searching") { applyRequests() }

    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            authorizationChanged()
        }
    }

    init {
        readAuthorization()
        // Location switched on or off in the quick settings.
        ContextCompat.registerReceiver(
            context, modeReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Registered here, as the Swift does in its init: AppModel creates this first, so the recorder is told before
        // the offline manager and the model (DESIGN.md §2.3).
        AppLifecycle.addObserver(this)
    }

    /** The last recorded point, so a restart continues the existing track correctly. */
    fun seed(lastRecorded: TrackPoint?) {
        this.lastRecorded = lastRecorded
    }

    /** Starts recording once location is allowed. Safe to call repeatedly. Android can't ask for the permission from
     *  here (iOS's `requestWhenInUseAuthorization`): `PermissionFlow` asks at launch. */
    fun start() {
        readAuthorization()
        if (_authorization.value == Authorization.authorized) beginUpdates()
    }

    /** After a permission result, a provider switched on or off, or `MODE_CHANGED_ACTION` (iOS's authorization
     *  callback): re-reads the authorization and precise location, and starts once allowed. */
    fun authorizationChanged() {
        readAuthorization()
        if (_authorization.value == Authorization.authorized) beginUpdates()
        // Precise location switched on or off in Settings: GPS ↔ approximate.
        applyRequests()
    }

    /** No permission: not determined until asked (Android can't tell "never asked" from "denied"), then denied.
     *  Location services off is its own state. Approximate only (coarse) is `reducedAccuracy`. */
    private fun readAuthorization() {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val a = when {
            !fine && !coarse ->
                if (Defaults.bool("locationPermissionAsked") == true) Authorization.denied else Authorization.notDetermined
            !LocationManagerCompat.isLocationEnabled(lm) -> Authorization.servicesOff
            else -> Authorization.authorized
        }
        _authorization.value = a
        _reducedAccuracy.value = a == Authorization.authorized && !fine
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun beginUpdates() {
        // Background updates can only be started while the app is in the foreground.
        if (started || !AppLifecycle.isForeground || _authorization.value != Authorization.authorized) return
        started = true
        // iOS: allowsBackgroundLocationUpdates and the blue background indicator.
        LocationService.start(context)
        lastMovementAt = now()
        searchingSince = now()
        applyRequests()
        startMotion()
        startStationaryTimer()
    }

    /** The notification's Stop (iOS's force-quit): everything off until the app is next opened. The last position
     *  stays. Pressed while SatMap is on screen (the notification shade or split screen over the map), updates go on
     *  while it stays visible, as an iPhone never shows a visible map without them, and stop when it goes to the
     *  background: Stop ends background recording only. */
    fun stop() {
        if (isForeground) {
            stopInBackground = true
            return
        }
        stopInBackground = false
        for ((slot, _) in registered) LocationManagerCompat.removeUpdates(lm, listeners.getValue(slot))
        registered.clear()
        stopMotion()
        for (t in listOf(stationaryTimer, dutyTimer, peekTimer, peekEndTimer, pendingMotionWake, searchTimer)) t.cancel()
        started = false
        peeking = false
        gpsResting = false
        _power.value = Power.active
    }

    /** Checks for "not moving" while active; stopped during sleep so the CPU isn't woken needlessly. */
    private fun startStationaryTimer() {
        stationaryTimer.schedule(15.0)
    }

    private fun stationaryTick() {
        stationaryTimer.schedule(15.0)
        evaluateStationary()
    }

    // Requests (iOS: accuracy)

    /** iOS's `applyAccuracy`. Android has no accuracy dial on the GPS: its request is there (GPS on) or not (GPS off),
     *  so the wanted requests are worked out per state and only what changed is registered again:
     *
     *  | power | peeking | foreground | GPS | network | passive |
     *  | active | – | yes | 2 s, high | 5 s, balanced, while searching | – |
     *  | active | – | no, acquiring | 1 s, high | the same | – |
     *  | active | – | no, resting | – | – | – |
     *  | sleeping | no | any | – | 60 s (at most every 30 s), low power | 10 s |
     *  | sleeping | yes | any | 1 s, high | – | – |
     *
     *  Approximate only: the GPS slot uses the fused (else the network) provider at low power. */
    private fun applyRequests() {
        val now = now()
        val want = wanted(now)
        for (slot in Slot.entries) {
            val w = want[slot]
            if (w == registered[slot]) continue
            val listener = listeners.getValue(slot)
            if (registered.remove(slot) != null) LocationManagerCompat.removeUpdates(lm, listener)
            if (w == null) continue
            try {
                LocationManagerCompat.requestLocationUpdates(lm, w.provider, w.compat(), ContextCompat.getMainExecutor(context), listener)
                registered[slot] = w
            } catch (e: SecurityException) {
                // The permission went (the process is about to end).
            } catch (e: IllegalArgumentException) {
                // No such provider on this phone.
            }
        }
        val last = lastGpsFixAt
        if (want.containsKey(Slot.gps) && _power.value == Power.active && last != null && now - last < 30) {
            if (!searchTimer.scheduled) searchTimer.schedule(last + 30 - now)
        } else {
            searchTimer.cancel()
        }
    }

    private fun wanted(now: Double): Map<Slot, Request> {
        if (!started) return emptyMap()
        val out = HashMap<Slot, Request>()
        val precise = !_reducedAccuracy.value
        val gpsProvider = when {
            precise -> LocationManager.GPS_PROVIDER
            Build.VERSION.SDK_INT >= 31 && LocationManagerCompat.hasProvider(lm, LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val hasGps = LocationManagerCompat.hasProvider(lm, gpsProvider)
        val hasNetwork = LocationManagerCompat.hasProvider(lm, LocationManager.NETWORK_PROVIDER)
        fun gps(intervalMs: Long) = Request(
            gpsProvider, intervalMs, null,
            if (precise) LocationRequestCompat.QUALITY_HIGH_ACCURACY else LocationRequestCompat.QUALITY_LOW_POWER,
        )
        when (_power.value) {
            Power.sleeping -> if (peeking) {
                if (hasGps) out[Slot.gps] = gps(1000)
            } else {
                if (hasNetwork) {
                    out[Slot.network] = Request(LocationManager.NETWORK_PROVIDER, 60_000, 30_000, LocationRequestCompat.QUALITY_LOW_POWER)
                }
                // Free fixes made for other apps. A passive request's interval asks for nothing; LocationRequest folds
                // a smaller minimum into the interval, so the interval is the 10 s minimum.
                out[Slot.passive] = Request(LocationManager.PASSIVE_PROVIDER, 10_000, null, LocationRequestCompat.QUALITY_LOW_POWER)
            }
            Power.active -> {
                val interval: Long? = when {
                    isForeground -> (LocationPolicy.foregroundInterval * 1000).toLong()
                    gpsResting -> null
                    else -> 1000
                }
                if (interval != null) {
                    if (hasGps) out[Slot.gps] = gps(interval)
                    val last = lastGpsFixAt
                    val searching = last == null || now - last >= 30
                    if (searching && hasNetwork && gpsProvider != LocationManager.NETWORK_PROVIDER) {
                        out[Slot.network] = Request(
                            LocationManager.NETWORK_PROVIDER, 5_000, null, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY,
                        )
                    }
                }
            }
        }
        return out
    }

    private inner class Listener(private val slot: Slot) : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            received(slot, location)
        }

        /** No batching is asked for; if a batch comes anyway, the last fix is the one used (as `locations.last`). */
        override fun onLocationChanged(locations: MutableList<Location>) {
            locations.lastOrNull()?.let { received(slot, it) }
        }

        override fun onProviderEnabled(provider: String) {
            authorizationChanged()
        }

        override fun onProviderDisabled(provider: String) {
            authorizationChanged()
        }
    }

    /** GPS fixes go to the Swift pipeline (and end the network's searching request); network fixes too (which goes
     *  to `handleWhileSleeping` when asleep); passive fixes only while asleep and not peeking. */
    private fun received(slot: Slot, loc: Location) {
        val fix = fix(loc) ?: return
        when (slot) {
            Slot.gps -> {
                // When the fix was taken (a cached one handed back on registering counts from then).
                val at = min(fix.elapsed, now())
                if (at > (lastGpsFixAt ?: Double.NEGATIVE_INFINITY)) lastGpsFixAt = at
                didUpdateLocations(fix)
                applyRequests()
            }
            Slot.network -> didUpdateLocations(fix)
            Slot.passive -> if (_power.value == Power.sleeping && !peeking) didUpdateLocations(fix)
        }
    }

    private fun fix(loc: Location): GpsFix? {
        if (!loc.hasAccuracy()) return null
        val (altitude, verticalAccuracy) = seaLevel(loc)
        return GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            horizontalAccuracy = loc.accuracy.toDouble(),
            altitude = altitude,
            verticalAccuracy = verticalAccuracy,
            timestamp = loc.time / 1000.0,
            elapsed = LocationCompat.getElapsedRealtimeMillis(loc) / 1000.0,
        )
    }

    // Altitude above sea level (iOS's `altitude`; Android's own is above the WGS84 ellipsoid, about 30 m apart in
    // South Africa): the provider's when it gives one, else on API 35+ Android's geoid model, else none (no GPS height
    // is shown).

    private val geoid: Any? = if (Build.VERSION.SDK_INT >= 35) Geoid() else null

    private fun seaLevel(loc: Location): Pair<Double?, Double?> {
        if (LocationCompat.hasMslAltitude(loc)) return mslOf(loc)
        if (Build.VERSION.SDK_INT >= 35 && loc.hasAltitude()) {
            val g = geoid as Geoid
            if (g.add(loc) && LocationCompat.hasMslAltitude(loc)) return mslOf(loc)
        }
        return null to null
    }

    private fun mslOf(loc: Location): Pair<Double?, Double?> {
        val accuracy = when {
            LocationCompat.hasMslAltitudeAccuracy(loc) -> LocationCompat.getMslAltitudeAccuracyMeters(loc).toDouble()
            LocationCompat.hasVerticalAccuracy(loc) -> LocationCompat.getVerticalAccuracyMeters(loc).toDouble()
            else -> null
        }
        return LocationCompat.getMslAltitudeMeters(loc) to accuracy
    }

    /** `AltitudeConverter`: its data is loaded once off the main thread (with a nearby position; again when we have
     *  moved beyond what it loaded), after which each fix is converted on the main thread without blocking. */
    @RequiresApi(35)
    private inner class Geoid {
        private val converter = AltitudeConverter()
        private var loading = false
        private var unavailable = false

        fun add(loc: Location): Boolean {
            if (loading) return false
            if (converter.tryAddMslAltitudeToLocation(loc)) return true
            if (unavailable) return false
            loading = true
            val nearby = Location(loc)
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        converter.addMslAltitudeToLocation(context, nearby)
                        true
                    } catch (e: IOException) {
                        false
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                }
                loading = false
                if (!ok) unavailable = true
            }
            return false
        }
    }

    // Fixes

    private fun didUpdateLocations(loc: GpsFix) {
        val now = now()
        if (!(loc.horizontalAccuracy >= 0) || abs(now - loc.elapsed) >= 20) return

        if (_power.value == Power.sleeping) {
            handleWhileSleeping(loc, now)
            return
        }

        if (isForeground) {
            if (now - lastUsedAt < LocationPolicy.foregroundInterval - 0.15) return
            use(loc, now)
        } else {
            // Coarse fixes while the GPS rests only keep the app alive.
            if (gpsResting) return
            val waited = now - acquiringSince
            // Only a fix taken after the GPS was woken (Android S+ may at once hand back a cached one when the request
            // is registered again), and no earlier than the 10 s mark.
            if (!(loc.elapsed >= acquiringSince - 0.5 &&
                    (now - lastUsedAt >= LocationPolicy.backgroundInterval - 0.5 || waited >= 20))
            ) {
                return
            }
            if (!(loc.horizontalAccuracy <= 20 ||
                    (waited >= 6 && loc.horizontalAccuracy <= LocationPolicy.maxTrackAccuracy) || waited >= 20)
            ) {
                return
            }
            use(loc, now)
            restUntilNextBackgroundFix()
        }
    }

    private fun use(loc: GpsFix, now: Double) {
        lastUsedAt = now
        noteMovement(loc, now)
        // A coarse (cell / Wi-Fi) fix, e.g. right after waking, mustn't replace a recent GPS position.
        val current = _location.value
        if (current != null && loc.horizontalAccuracy > 100 && current.horizontalAccuracy <= 100 &&
            now - current.elapsed < 60
        ) {
            return
        }
        _location.value = loc
        record(loc)
        onFix?.invoke(loc)
    }

    // Track

    private fun record(loc: GpsFix) {
        if (loc.horizontalAccuracy > LocationPolicy.maxTrackAccuracy) return
        if (now() < preciseFixNeededUntil) {
            if (loc.horizontalAccuracy > 10) return
            preciseFixNeededUntil = Double.NEGATIVE_INFINITY
        }
        var p = TrackPoint(lat = loc.lat, lon = loc.lon, time = loc.timestamp, startsSegment = true)
        lastRecorded?.let { last ->
            // Geo.distance where the Swift uses CLLocation's distance (Apple's algorithm is unspecified; they differ
            // by under 0.5 %).
            val d = Geo.distance(last.geo, loc.point)
            // Moves smaller than the fix's own error are GPS wander, not walking.
            if (d < max(LocationPolicy.minTrackSpacing, loc.horizontalAccuracy)) return
            p = p.copy(
                startsSegment = p.time - last.time > LocationPolicy.segmentBreakSeconds && d > LocationPolicy.segmentBreakMeters,
            )
        }
        lastRecorded = p
        onTrackPoint?.invoke(p)
    }

    // Background duty cycle

    private fun restUntilNextBackgroundFix() {
        if (isForeground || _power.value != Power.active) return
        gpsResting = true
        applyRequests()
        val fireAt = lastUsedAt + LocationPolicy.backgroundInterval - LocationPolicy.backgroundWakeLead
        dutyTimer.schedule(max(0.0, fireAt - now()))
    }

    private fun startAcquiring() {
        dutyTimer.cancel()
        gpsResting = false
        acquiringSince = now()
        applyRequests()
    }

    // Stationary detection & sleep

    private fun noteMovement(loc: GpsFix, now: Double) {
        if (loc.horizontalAccuracy > 50) return
        lastGoodFixAt = now
        val a = movementAnchor
        if (a != null) {
            if (Geo.distance(loc.point, a.point) > max(LocationPolicy.movementThreshold, loc.horizontalAccuracy)) {
                movementAnchor = loc
                lastMovementAt = now
            }
        } else {
            movementAnchor = loc
            lastMovementAt = now
        }
    }

    private fun evaluateStationary() {
        if (!started || _power.value != Power.active) return
        val now = now()
        // Motion says we're walking/driving: don't sleep (covers pacing within a few metres).
        val moving = motionSaysMoving(now)
        val since = motionMovingSince
        if (moving && since != null && now - since < 600) return
        // No usable fix yet (cold start, poor sky view): keep searching for a while, since we can't tell
        // whether we're moving.
        if (lastGoodFixAt == null && now - searchingSince < 600) return
        if (now - lastMovementAt >= LocationPolicy.stationaryTimeout) {
            enterSleep()
        }
    }

    private fun enterSleep() {
        _power.value = Power.sleeping
        sleepAnchor = _location.value
        stationaryTimer.cancel()
        dutyTimer.cancel()
        gpsResting = false
        peeking = false
        applyRequests()
        schedulePeek()
    }

    private fun wake() {
        if (_power.value != Power.sleeping) return
        _power.value = Power.active
        peeking = false
        peekTimer.cancel()
        peekEndTimer.cancel()
        pendingMotionWake.cancel()
        val now = now()
        lastMovementAt = now
        searchingSince = now
        movementAnchor = null
        lastUsedAt = Double.NEGATIVE_INFINITY
        preciseFixNeededUntil = now + 30
        gpsResting = false
        acquiringSince = now
        applyRequests()
        startStationaryTimer()
    }

    private fun schedulePeek() {
        peekTimer.cancel()
        if (_power.value != Power.sleeping) return
        val interval = when {
            !isForeground -> LocationPolicy.sleepPeekIntervalBackground
            measuring -> LocationPolicy.sleepPeekIntervalMeasuring
            else -> LocationPolicy.sleepPeekIntervalForeground
        }
        peekTimer.schedule(interval)
    }

    private fun startPeek() {
        peekTimer.cancel()
        if (_power.value != Power.sleeping) return
        peeking = true
        applyRequests()
        // Without a recent good fix the GPS may need a cold start, which takes longer.
        val recentFix = lastGoodFixAt?.let { now() - it < 600 } ?: false
        peekEndTimer.schedule(if (recentFix) LocationPolicy.peekTimeout else 45.0)
    }

    private fun endPeek() {
        peekEndTimer.cancel()
        if (_power.value != Power.sleeping) return
        peeking = false
        applyRequests()
        schedulePeek()
    }

    private fun handleWhileSleeping(loc: GpsFix, now: Double) {
        if (peeking) {
            if (loc.horizontalAccuracy > 25) return
            lastGoodFixAt = now
            val anchor = sleepAnchor
            if (anchor == null || anchor.horizontalAccuracy > 25) {
                // We fell asleep without a good position: this is the first one, so use it.
                wake()
                use(loc, now)
                return
            }
            val threshold = if (measuring && isForeground) max(8.0, 1.5 * loc.horizontalAccuracy)
            else max(20.0, 2 * loc.horizontalAccuracy)
            if (Geo.distance(loc.point, anchor.point) > threshold) {
                wake()
                use(loc, now)
            } else {
                // Still here: refresh the dot and measuring line without adding to the track.
                _location.value = loc
                onFix?.invoke(loc)
                endPeek()
            }
            return
        }
        // Coarse (cell / Wi-Fi) fixes: wake on a jump that can't be explained by their error.
        val a = sleepAnchor
        if (a != null && loc.horizontalAccuracy < 1500 &&
            Geo.distance(loc.point, a.point) > max(300.0, 2 * loc.horizontalAccuracy + a.horizontalAccuracy)
        ) {
            wake()
        }
    }

    // Motion

    /** iOS's motion activity: significant motion (wake now) and the step detector (≥ 6 steps in 6 s: wake now; else
     *  a wake after 6 s that needs 3 steps). Idempotent; called again after the activity-recognition result.
     *  Significant motion needs no permission and is armed here; the step detector is M5 (DESIGN.md §5.1). */
    fun startMotion() {
        if (!started) return
        armSignificantMotion()
        // M5: register TYPE_STEP_DETECTOR once ACTIVITY_RECOGNITION is granted.
    }

    /** Once: Android refuses a second request for an armed trigger. */
    private fun armSignificantMotion() {
        val s = sensors ?: return
        val sensor = significantMotion ?: return
        if (significantMotionArmed) return
        significantMotionArmed = s.requestTriggerSensor(significantMotionListener, sensor)
    }

    private fun stopMotion() {
        val s = sensors
        val sensor = significantMotion
        if (significantMotionArmed && s != null && sensor != null) {
            s.cancelTriggerSensor(significantMotionListener, sensor)
        }
        significantMotionArmed = false
        // M5: unregister the step listener.
        lastSignificantMotionAt = null
        motionMovingSince = null
    }

    /** iOS: walking, running, cycling or driving at high confidence. Fires on the main thread. */
    private fun significantMotionDetected() {
        if (!started) return
        val now = now()
        noteMoving(now)
        lastSignificantMotionAt = now
        if (_power.value == Power.sleeping) wake()
        // It disarms itself after firing.
        armSignificantMotion()
    }

    /** iOS: a translating activity of medium or high confidence. */
    private fun noteMoving(now: Double) {
        if (!motionSaysMoving(now)) motionMovingSince = now
    }

    /** Motion says we're walking/driving: significant motion in the last 60 s (M5: or a step in the last 15 s). Once
     *  it doesn't, `motionMovingSince` is cleared (iOS: a stationary activity). */
    private fun motionSaysMoving(now: Double): Boolean {
        val moving = lastSignificantMotionAt?.let { now - it < 60 } ?: false
        if (!moving) motionMovingSince = null
        return moving
    }

    // Compass

    private var lastHeading: Double? = null
    private var declinationFix: GpsFix? = null
    private var declination = 0.0

    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            headingChanged(event)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (headingWanted && unreliable(accuracy)) sendHeading(null)
        }
    }

    private fun updateHeading() {
        // iOS: CLLocationManager.headingAvailable().
        val s = sensors ?: return
        val sensor = s.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: s.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) ?: return
        if (headingWanted) {
            s.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_UI, mainHandler)
        } else {
            s.unregisterListener(headingListener)
            lastHeading = null
            onHeading?.invoke(null)
        }
    }

    private fun headingChanged(event: SensorEvent) {
        if (!headingWanted) return
        // iOS: headingAccuracy < 0 (the compass can't tell): no arrow. Android has no calibration screen to show.
        if (unreliable(event.accuracy)) {
            sendHeading(null)
            return
        }
        var h = heading(event.values, screenRotation)
        // True north once there is a position (iOS: trueHeading, else magneticHeading).
        _location.value?.let { h = (h + declinationAt(it) + 360) % 360 }
        sendHeading(h)
    }

    private fun declinationAt(fix: GpsFix): Double {
        if (fix !== declinationFix) {
            declination = GeomagneticField(
                fix.lat.toFloat(), fix.lon.toFloat(), (fix.altitude ?: 0.0).toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
            declinationFix = fix
        }
        return declination
    }

    /** iOS's `headingFilter` of 2°: a heading is sent when it moved 2° or more from the last one sent, or when there
     *  was none; "can't tell" (null) once. */
    private fun sendHeading(h: Double?) {
        val last = lastHeading
        if (h == null) {
            if (last == null) return
        } else if (last != null && abs((h - last + 540) % 360 - 180) < 2) {
            return
        }
        lastHeading = h
        onHeading?.invoke(h)
    }

    private fun unreliable(accuracy: Int) =
        accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT

    // Tests

    /** For screen tests, where no location arrives: the position, the permission state and precise location as the
     *  screens show them. A fix is passed on as one used (`onFix`: the maps, the drone report), but not recorded in
     *  the track. A later re-read of the permissions (`start`, coming to the foreground) replaces the authorization. */
    @VisibleForTesting
    fun setForTests(fix: GpsFix?, authorization: Authorization = Authorization.authorized, reducedAccuracy: Boolean = false) {
        _authorization.value = authorization
        _reducedAccuracy.value = reducedAccuracy
        _location.value = fix
        if (fix != null) onFix?.invoke(fix)
    }

    // App state

    override fun didEnterBackground() {
        isForeground = false
        // Stop was pressed while SatMap was on screen.
        if (stopInBackground) {
            stop()
            return
        }
        if (_power.value == Power.active) {
            // Take the next fix now, then rest the GPS between 10 s fixes.
            startAcquiring()
        } else {
            peeking = false
            peekEndTimer.cancel()
            applyRequests()
            schedulePeek()
        }
    }

    override fun willEnterForeground() {
        isForeground = true
        dutyTimer.cancel()
        gpsResting = false
        readAuthorization()
        applyRequests()
        if (_power.value == Power.sleeping) {
            // Check right away whether we moved while the app was in the background.
            startPeek()
        }
        // Android Auto makes the app foreground without making it active (CarPlay makes it active).
        if (_authorization.value == Authorization.authorized) beginUpdates()
    }

    override fun didBecomeActive() {
        isForeground = true
        if (!started) start()
        // Motion permission may have been answered while the app was inactive behind the prompt.
        if (started) startMotion()
    }

    companion object {
        /** The recorder's clock: seconds on `elapsedRealtime`, which counts through deep sleep. */
        fun now(): Double = SystemClock.elapsedRealtime() / 1000.0

        /** The magnetic heading of the top of the screen, in degrees (0…360), from a rotation vector: the device's
         *  axes are turned to the screen's for its rotation, then the azimuth of the screen's up axis is read. */
        fun heading(rotationVector: FloatArray, screenRotation: Int): Double {
            val r = FloatArray(9)
            // Some phones give five values; the first four are the quaternion.
            SensorManager.getRotationMatrixFromVector(r, rotationVector.copyOf(min(4, rotationVector.size)))
            val (ax, ay) = when (screenRotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            val out = FloatArray(9)
            SensorManager.remapCoordinateSystem(r, ax, ay, out)
            val o = FloatArray(3)
            SensorManager.getOrientation(out, o)
            return (Math.toDegrees(o[0].toDouble()) + 360) % 360
        }
    }
}
