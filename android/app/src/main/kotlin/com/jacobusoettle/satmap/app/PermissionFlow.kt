package com.jacobusoettle.satmap.app

import androidx.activity.ComponentActivity
import com.jacobusoettle.satmap.location.LocationRecorder

/** The runtime permissions, as iOS asks for them at launch: location (fine and coarse together, so Android 12+ offers
 *  precise or approximate), then motion (`ACTIVITY_RECOGNITION`) once location is authorised. Each is asked once per
 *  install (`*PermissionAsked` flags); there are no rationale screens, as iOS has none. Notifications are asked on the
 *  first Download tap, not here (DESIGN.md D10). One per `MainActivity`. */
class PermissionFlow(private val activity: ComponentActivity, private val recorder: LocationRecorder) {
    /** Called from `MainActivity.onResume` (iOS didBecomeActive) and after each answer. */
    fun next() {
        // M1: registers the two launchers in the constructor and asks location, then motion (DESIGN.md §5.3).
    }
}
