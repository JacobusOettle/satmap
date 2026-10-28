package com.jacobusoettle.satmap.location

import android.content.Context
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Records the dotted track with the Swift power policy, ported line for line on `LocationManager` (GPS provider; no
 *  Google Play services): one fix per 2 s on screen, one per 10 s in the background with the GPS off in between, and
 *  sleep (GPS off, coarse fixes and GPS peeks) after 120 s without movement. Main thread only. */
class LocationRecorder(private val context: Context) : AppLifecycle.Observer {
    enum class Power { active, sleeping }

    /** iOS folds "location services off" into denied; Android tells them apart (its hint opens another page). */
    enum class Authorization { notDetermined, denied, servicesOff, authorized }

    private val _location = MutableStateFlow<GpsFix?>(null)

    /** The last fix used. */
    val location: StateFlow<GpsFix?> = _location.asStateFlow()
    private val _power = MutableStateFlow(Power.active)
    val power: StateFlow<Power> = _power.asStateFlow()
    private val _authorization = MutableStateFlow(Authorization.notDetermined)
    val authorization: StateFlow<Authorization> = _authorization.asStateFlow()

    /** Approximate location only (coarse): nothing reaches the track. */
    private val _reducedAccuracy = MutableStateFlow(false)
    val reducedAccuracy: StateFlow<Boolean> = _reducedAccuracy.asStateFlow()

    var onTrackPoint: ((TrackPoint) -> Unit)? = null
    var onFix: ((GpsFix) -> Unit)? = null
    var onHeading: ((Double?) -> Unit)? = null

    /** The compass runs only while wanted (the map is shown). */
    var headingWanted: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            // M5: updateHeading().
        }

    /** `Surface.ROTATION_*` of the phone's display, for the compass. */
    var screenRotation: Int = 0
        set(value) {
            if (value == field) return
            field = value
            // M5: re-apply the remap.
        }

    /** The setter runs on every assignment, as the Swift didSet. */
    var measuring: Boolean = false
        set(value) {
            field = value
            // M5: starts a peek at once when measuring while asleep in the foreground.
        }

    init {
        // Registered here, as the Swift does in its init: AppModel creates this first, so the recorder is told before
        // the offline manager and the model (DESIGN.md §2.3).
        AppLifecycle.addObserver(this)
    }

    fun seed(lastRecorded: TrackPoint?) {
        // M1: the last track point, for spacing and segment breaks.
    }

    /** Safe to call repeatedly. */
    fun start() {
        // M1: reads the authorization; beginUpdates() when authorised and in the foreground (DESIGN.md §5.1).
    }

    /** After a permission result, ON_START or MODE_CHANGED_ACTION. */
    fun authorizationChanged() {
        // M1: re-reads authorization and reducedAccuracy; beginUpdates() once authorised.
    }

    /** Idempotent; called again after the activity-recognition result. */
    fun startMotion() {
        // M5: significant motion and the step detector.
    }

    /** The notification's Stop (iOS force-quit): everything off until the app is next opened. */
    fun stop() {
        // M1: removes the listeners, sensors and timers; started = false; power = active.
    }

    override fun willEnterForeground() {
        // M1: the Swift willEnterForeground.
    }

    override fun didBecomeActive() {
        // M1: start(), or startMotion() once started.
    }

    override fun didEnterBackground() {
        // M1: the Swift didEnterBackground.
    }
}
