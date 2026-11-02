package com.jacobusoettle.satmap.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.GroupDetail
import com.jacobusoettle.satmap.core.PendingInvite
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.core.TrailsMode
import com.jacobusoettle.satmap.core.MadeInvite
import com.jacobusoettle.satmap.groups.AfterPro
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Caption
import com.jacobusoettle.satmap.ui.CellBackground
import com.jacobusoettle.satmap.ui.FormLinkRow
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormValueRow
import com.jacobusoettle.satmap.ui.IosSwitch
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import com.jacobusoettle.satmap.ui.SatIcons
import com.jacobusoettle.satmap.ui.SystemRed
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.SystemOrange
import com.jacobusoettle.satmap.ui.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** S5 Group detail (docs/groups/SPEC.md §9.3): its sections in the spec's order, each only when it applies. Opening it
 *  refreshes the group (status, members' last live times, invites; `refreshDetail`). */
@Composable
fun GroupDetailScreen(model: AppModel, groupId: String, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val rev by groups.revision.collectAsStateWithLifecycle()
    val isPro by groups.pro.isPro.collectAsStateWithLifecycle()
    val fix by model.recorder.location.collectAsStateWithLifecycle()
    val now = rememberNow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var alert by remember { mutableStateOf<DetailAlert?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val detail = remember(rev, groupId, now) { groups.hub.detail(groupId) }
    var seen by remember { mutableStateOf(false) }
    if (detail != null) seen = true
    LaunchedEffect(groupId) {
        groups.ready()
        try {
            groups.hub.refreshDetail(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Shown again at the next refresh.
        }
    }
    // The group went (removed, deleted, left): back to the list.
    LaunchedEffect(detail == null, seen) { if (detail == null && seen) nav.pop() }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2000)
            toast = null
        }
    }

    fun run(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = errorText(e)
            }
        }
    }

    Box {
        SheetScreen(ScreenChrome(detail?.name ?: "", leading(model, nav, below))) {
            val d = detail ?: return@SheetScreen
            if (d.isPaused) {
                item {
                    // The warning style: the section's rows on an orange tint.
                    FormSection(first = true) {
                        val tint = Modifier.background(SystemOrange.copy(alpha = 0.22f))
                        TextRow(
                            GroupTexts.pausedSection(d.pausedReason, d.canHandOver, d.deleteAt?.let { GroupTexts.date(it, now) }),
                            modifier = tint,
                        )
                        if (d.canHandOver) {
                            ButtonRow("Renew SatMap Pro", modifier = tint) {
                                if (isPro) run { groups.renewPro() } else nav.push(GroupsRoute.Paywall(AfterPro.Renew(groupId)))
                            }
                            ButtonRow("Hand over ownership", modifier = tint) { nav.push(GroupsRoute.Handover(groupId)) }
                        }
                    }
                }
            }
            d.offerToYouFrom?.let { owner ->
                item {
                    FormSection(first = !d.isPaused) {
                        TextRow("$owner wants to make you the owner of this group. You need SatMap Pro.")
                        ButtonRow("Accept", tag = "acceptOwner") {
                            if (isPro) run { groups.acceptOwnership(groupId) } else nav.push(GroupsRoute.Paywall(AfterPro.Accept(groupId)))
                        }
                        ButtonRow("Decline") { run { groups.hub.cancelOwnershipOffer(groupId) } }
                    }
                }
            }
            d.offerByYouTo?.let { target ->
                item {
                    FormSection(first = !d.isPaused && d.offerToYouFrom == null) {
                        TextRow("Waiting for $target to accept ownership.")
                        ButtonRow("Cancel request") { run { groups.hub.cancelOwnershipOffer(groupId) } }
                    }
                }
            }
            item {
                OnTheMap(
                    model,
                    d,
                    first = !d.isPaused && d.offerToYouFrom == null && d.offerByYouTo == null,
                    pushConsent = { nav.push(GroupsRoute.Consent(groupId, afterCreate = false)) },
                ) { alert = it }
            }
            item {
                FormSection(header = "Members (${d.members.size})") {
                    for (m in d.members) key(m.memberId) {
                        FormLinkRow(
                            onClick = { nav.push(GroupsRoute.Member(groupId, m.memberId)) },
                            modifier = Modifier.testTag("memberRow"),
                        ) {
                            MemberDot(m.initials, m.colourHex)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(GroupTexts.memberName(m), style = Body, color = LabelPrimary)
                                Text(GroupTexts.memberSubtitle(m, now), style = Caption, color = LabelSecondary)
                            }
                        }
                    }
                }
            }
            d.invites?.let { invites ->
                item {
                    FormSection(header = "Invites", footer = "Each invite link works once, for one person, and expires after 7 days.") {
                        ButtonRow("Invite someone", icon = GroupSymbols.personBadgePlus, tag = "inviteSomeone") {
                            nav.push(GroupsRoute.Invite(groupId, null))
                        }
                        for (i in invites) key(i.inviteId) {
                            InviteRow(i, now, open = {
                                i.link?.let { link -> nav.push(GroupsRoute.Invite(groupId, MadeInvite(i.inviteId, link, i.expiresAt, d.name))) }
                            }) { alert = DetailAlert.CancelInvite(i.inviteId) }
                        }
                    }
                }
            }
            item {
                FormSection {
                    val canDrop = fix != null && !d.isPaused
                    ButtonRow("Drop a pin where I am", icon = SatIcons.MapPinAndEllipse, enabled = canDrop) {
                        fix?.let { nav.push(GroupsRoute.DropPin(groupId, it.lat, it.lon)) }
                    }
                    FormLinkRow("Your name and colour") { nav.push(GroupsRoute.EditName(groupId)) }
                    if (d.canRename) FormLinkRow("Rename group") { nav.push(GroupsRoute.Rename(groupId)) }
                    if (d.canReportGroupName) {
                        FormLinkRow("Report group name", modifier = Modifier.testTag("reportGroup")) {
                            nav.push(GroupsRoute.Report(groupId, ReportTarget.Group))
                        }
                    }
                }
            }
            item {
                FormSection { ButtonRow("Clear my trail", destructive = true) { alert = DetailAlert.ClearTrail } }
            }
            if (d.blocked.isNotEmpty()) {
                item {
                    FormSection { FormLinkRow("Blocked (${d.blocked.size})") { nav.push(GroupsRoute.Blocked(groupId)) } }
                }
            }
            item {
                FormSection(footer = "Report a problem: ${GroupConfig.contactEmail}. Quote your support code when you write about your data.") {
                    if (d.canHandOver) ButtonRow("Hand over ownership") { nav.push(GroupsRoute.Handover(groupId)) }
                    if (d.canDeleteGroup) ButtonRow("Delete group", destructive = true) { alert = DetailAlert.Delete }
                    if (d.canLeave) ButtonRow("Leave group", destructive = true) { alert = DetailAlert.Leave }
                    FormRow(
                        Modifier.testTag("supportCode").pressable {
                            copyText(context, "Support code", d.supportCode)
                            toast = "Support code copied"
                        },
                        verticalPadding = MultiLineRowPadding,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Support code", style = Body, color = LabelPrimary)
                            Text(
                                d.supportCode,
                                style = Caption.copy(fontFamily = FontFamily.Monospace),
                                color = LabelSecondary,
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                            )
                        }
                    }
                }
            }
        }
        Toast(toast)
    }
    val d = detail
    if (d != null) {
        when (val a = alert) {
            DetailAlert.StopSharing -> AlertBox(
                "Sharing is off",
                "Members can't see where you are now. The trail you already shared in ${d.name} stays visible to them until you clear it.",
                listOf(AlertButton("Clear my trail too", destructive = true) { groups.hub.clearMyTrail(groupId) }, AlertButton("Keep trail") {}),
            ) { alert = null }
            DetailAlert.ClearTrail -> AlertBox(
                "Clear your trail?",
                "Everyone in ${d.name} stops seeing where you've been. Your own track in Where I've been stays. This can't be undone.",
                listOf(AlertButton("Clear my trail", destructive = true) { groups.hub.clearMyTrail(groupId) }, AlertButton("Cancel") {}),
            ) { alert = null }
            DetailAlert.Leave -> AlertBox(
                "Leave ${d.name}?",
                "Your trail and pins in this group are deleted. You can only come back with a new invite.",
                listOf(AlertButton("Leave group", destructive = true) { run { groups.hub.leave(groupId) } }, AlertButton("Cancel") {}),
            ) { alert = null }
            DetailAlert.Delete -> AlertBox(
                "Delete ${d.name}?",
                "The group, its trails and pins are deleted for everyone. This can't be undone.",
                listOf(AlertButton("Delete group", destructive = true) { run { groups.hub.deleteGroup(groupId) } }, AlertButton("Cancel") {}),
            ) { alert = null }
            is DetailAlert.CancelInvite -> AlertBox(
                "Cancel this invite?",
                "The link stops working.",
                listOf(
                    AlertButton("Cancel invite", destructive = true) { run { groups.hub.cancelInvite(a.inviteId, groupId) } },
                    AlertButton("Keep") {},
                ),
            ) { alert = null }
            null -> {}
        }
    }
    MessageAlert(message) { message = null }
}

/** The alerts S5 shows. */
sealed interface DetailAlert {
    data object StopSharing : DetailAlert
    data object ClearTrail : DetailAlert
    data object Leave : DetailAlert
    data object Delete : DetailAlert
    data class CancelInvite(val inviteId: String) : DetailAlert
}

/** "On the map": show it, share your location (S9 first without consent; off asks whether to clear the trail too),
 *  and which trails show. */
@Composable
private fun OnTheMap(model: AppModel, d: GroupDetail, first: Boolean, pushConsent: () -> Unit, showAlert: (DetailAlert) -> Unit) {
    val groups = model.groups
    val footer = when {
        d.isPaused -> "Sharing is off while the group is paused."
        d.isSharing -> "Members see where you are and the trail you leave, also while SatMap is in the background."
        else -> "Members can't see where you are now. The trail you already shared stays visible until you clear it. You can still see the group and drop pins."
    }
    FormSection(header = "On the map", footer = footer, first = first) {
        SwitchRow("Show on the map", d.isShown, tag = "showOnMap") { groups.setShown(d.groupId, it) }
        SwitchRow("Share my location", d.isSharing, enabled = !d.isPaused, tag = "shareLocation") { on ->
            if (on) {
                if (!d.hasConsent) {
                    pushConsent()
                } else {
                    groups.hub.setSharing(true, d.groupId)
                }
            } else {
                groups.hub.setSharing(false, d.groupId)
                showAlert(DetailAlert.StopSharing)
            }
        }
        TrailsRow(d.trails) { groups.hub.setTrails(it, d.groupId) }
    }
}

/** A switch row that can be disabled (dimmed, no taps). */
@Composable
fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, tag: String? = null, onChange: (Boolean) -> Unit) {
    FormRow(
        Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.4f),
        verticalPadding = 6.dp,
        endPadding = 14.dp,
    ) {
        Text(title, style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
        IosSwitch(checked)
    }
}

private fun trailsTitle(m: TrailsMode) = when (m) {
    TrailsMode.off -> "Off"
    TrailsMode.day -> "Last 24 hours"
    TrailsMode.all -> "Everything"
}

/** "Trails": the current choice; a tap opens the three (iOS's menu picker). */
@Composable
private fun TrailsRow(mode: TrailsMode, onChoose: (TrailsMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FormRow(
            Modifier
                .testTag("trailsMode")
                .pressable(onClickLabel = "Choose trails") { open = true }
                .semantics { stateDescription = trailsTitle(mode) },
        ) {
            Text("Trails", style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
            Text(trailsTitle(mode), style = Body, color = LabelSecondary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = LabelSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = CellBackground) {
            for (m in listOf(TrailsMode.off, TrailsMode.day, TrailsMode.all)) {
                DropdownMenuItem(
                    text = { Text(trailsTitle(m), style = Body) },
                    onClick = {
                        open = false
                        onChoose(m)
                    },
                    colors = MenuDefaults.itemColors(textColor = if (m == mode) Accent else LabelPrimary),
                )
            }
        }
    }
}

/** A pending invite: who made it, when it expires, a tap to show it again (if this phone made it), and Cancel. */
@Composable
private fun InviteRow(i: PendingInvite, now: Long, open: () -> Unit, cancel: () -> Unit) {
    FormRow(verticalPadding = 0.dp) {
        Column(
            Modifier.weight(1f).pressable(enabled = i.link != null, onClick = open).padding(vertical = MultiLineRowPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Invite from ${i.createdByName}", style = Body, color = LabelPrimary)
            Text("Expires ${GroupTexts.date(i.expiresAt, now)}", style = Caption, color = LabelSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Text("Cancel", style = Body, color = SystemRed, modifier = Modifier.pressable(onClick = cancel))
    }
}
