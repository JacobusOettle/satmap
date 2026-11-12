package com.jacobusoettle.satmap.ui.groups

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.ui.text.font.FontWeight
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var refused by remember { mutableStateOf<String?>(null) }

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
        when (recorder.authorization.value) {
            LocationRecorder.Authorization.authorized -> share()
            LocationRecorder.Authorization.servicesOff -> refused = LOCATION_SERVICES_OFF
            else -> refused = LOCATION_OFF
        }
    }
    SheetScreen(ScreenChrome("Share your location?", leading = null)) {
        item {
            FormSection(first = true) {
                FormRow(verticalPadding = MultiLineRowPadding) {
                    Text(
                        "Spoor collects location data to share your position and trail with $name, even when the app is in the background.",
                        style = Body.copy(fontWeight = FontWeight.SemiBold),
                        color = LabelPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                IconTextRow("Members see where you are while sharing is on.", Icons.Filled.NearMe)
                IconTextRow("They see the trail you leave from now on, until you clear it.", Icons.Filled.Route)
                IconTextRow("Your location is encrypted on your phone. We can't read it.", GroupSymbols.lock)
                IconTextRow("Turn sharing off any time in the group.", Icons.Filled.ToggleOn)
            }
        }
        item {
            FormSection(footer = "Without sharing you can still see the group and drop pins.") {
                ButtonRow("Share my location", tag = "consentShare", style = Headline) {
                    when (recorder.authorization.value) {
                        LocationRecorder.Authorization.authorized -> share()
                        LocationRecorder.Authorization.notDetermined -> {
                            Defaults.putBoolean("locationPermissionAsked", true)
                            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                        LocationRecorder.Authorization.denied -> refused = LOCATION_OFF
                        LocationRecorder.Authorization.servicesOff -> refused = LOCATION_SERVICES_OFF
                    }
                }
                ButtonRow("Not now", tag = "consentNotNow") { finish() }
            }
        }
    }
    refused?.let { text ->
        AlertBox(
            text,
            null,
            listOf(AlertButton("OK") { finish() }),
        ) { refused = null }
    }
}
