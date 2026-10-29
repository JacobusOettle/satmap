package com.jacobusoettle.satmap.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jacobusoettle.satmap.location.LocationRecorder

/** The runtime permissions, as iOS asks for them at launch: location (fine and coarse together, so Android 12+ offers
 *  precise or approximate), then motion (`ACTIVITY_RECOGNITION`) once location is authorised. Each is asked once per
 *  install (`*PermissionAsked` flags: Android can't tell "never asked" from "denied", and stops showing the dialog
 *  after two denials anyway); there are no rationale screens, as iOS has none. Notifications are asked on the first
 *  Download tap, not here (DESIGN.md D10). One per `MainActivity`, made in its `onCreate`. */
class PermissionFlow(private val activity: ComponentActivity, private val recorder: LocationRecorder) {
    /** A dialog is up: the activity pauses and resumes under it, which calls `next()` again. */
    private var asking = false

    private val location = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asking = false
        recorder.authorizationChanged()
        next()
    }

    private val motion = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        asking = false
        recorder.startMotion()
        next()
    }

    /** Called from `MainActivity.onResume` (iOS didBecomeActive) and after each answer. */
    fun next() {
        if (asking) return
        when {
            recorder.authorization.value == LocationRecorder.Authorization.notDetermined -> {
                Defaults.putBoolean("locationPermissionAsked", true)
                asking = true
                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            // Motion only after location, as iOS asks for it once updates begin.
            recorder.authorization.value != LocationRecorder.Authorization.authorized -> return
            Build.VERSION.SDK_INT >= 29 && stepDetectorPresent() &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED &&
                Defaults.bool("motionPermissionAsked") != true -> {
                Defaults.putBoolean("motionPermissionAsked", true)
                asking = true
                motion.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
    }

    /** Without a step detector (emulators, some phones) the permission would serve nothing: the GPS checks remain. */
    private fun stepDetectorPresent(): Boolean =
        activity.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
}
