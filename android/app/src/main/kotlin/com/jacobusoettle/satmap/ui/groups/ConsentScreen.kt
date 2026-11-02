package com.jacobusoettle.satmap.ui.groups

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.location.LocationRecorder
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen

/** S9 Share your location? (docs/groups/SPEC.md §9.3, §8.13): the consent before sharing starts in a group, with
 *  Google's prominent-disclosure sentence first (§11.5). "Share my location" asks for location first if it was never
 *  asked (as `PermissionFlow` does: fine and coarse together); refused, sharing stays off. Then S5 (after a create
 *  or join) or back to it. */
@Composable
fun ConsentScreen(model: AppModel, route: GroupsRoute.Consent, nav: GroupsNav) {
    val groups = model.groups
    val recorder = model.recorder
    val name = remember(route.groupId) { groups.hub.detail(route.groupId)?.name ?: "" }
    var refused by remember { mutableStateOf(false) }

    fun finish() {
        if (route.afterCreate) nav.replace(listOf(GroupsRoute.List, GroupsRoute.Detail(route.groupId))) else nav.pop()
    }

    fun share() {
        groups.hub.recordConsent(route.groupId)
        groups.hub.setSharing(true, route.groupId)
        finish()
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recorder.authorizationChanged()
        if (recorder.authorization.value == LocationRecorder.Authorization.authorized) share() else refused = true
    }
    SheetScreen(ScreenChrome("Share your location?", leading = null)) {
        item {
            Text(
                "SatMap collects location data to share your position and trail with $name, even when the app is in the background.",
                style = Body,
                color = LabelPrimary,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        item {
            FormSection {
                TextRow("Members see where you are while sharing is on.")
                TextRow("They see the trail you leave from now on, until you clear it.")
                TextRow("Your location is encrypted on your phone. We can't read it.")
                TextRow("Turn sharing off any time in the group.")
            }
        }
        item {
            FormSection(footer = "Without sharing you can still see the group and drop pins.") {
                ButtonRow("Share my location", tag = "consentShare") {
                    when (recorder.authorization.value) {
                        LocationRecorder.Authorization.authorized -> share()
                        LocationRecorder.Authorization.notDetermined -> {
                            Defaults.putBoolean("locationPermissionAsked", true)
                            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                        LocationRecorder.Authorization.denied, LocationRecorder.Authorization.servicesOff -> refused = true
                    }
                }
                ButtonRow("Not now", tag = "consentNotNow") { finish() }
            }
        }
    }
    if (refused) {
        AlertBox(
            "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services to share.",
            null,
            listOf(AlertButton("OK") { finish() }),
        ) { refused = false }
    }
}
