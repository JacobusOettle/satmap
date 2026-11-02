package com.jacobusoettle.satmap.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupRole
import com.jacobusoettle.satmap.core.GroupText
import com.jacobusoettle.satmap.core.GroupTextField
import com.jacobusoettle.satmap.core.MemberRow
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Body
import com.jacobusoettle.satmap.ui.Caption
import com.jacobusoettle.satmap.ui.FormLinkRow
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.FormTextFieldRow
import com.jacobusoettle.satmap.ui.FormValueRow
import com.jacobusoettle.satmap.ui.LabelPrimary
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** S12 Member detail (docs/groups/SPEC.md §9.3): who, their role and state, where they are (Show on map), and what
 *  your role lets you do with them (§1.3): make or remove an admin, make owner (S14), remove; report and block for
 *  anyone else, your name and colour for yourself. */
@Composable
fun MemberDetailScreen(model: AppModel, route: GroupsRoute.Member, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val rev by groups.revision.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    val member = remember(rev, route) { groups.hub.memberDetail(route.groupId, route.memberId) }
    val detail = remember(rev, route) { groups.hub.detail(route.groupId) }
    var seen by remember { mutableStateOf(false) }
    if (member != null) seen = true
    LaunchedEffect(member == null, seen) { if (member == null && seen) nav.pop() }
    var message by remember { mutableStateOf<String?>(null) }
    var alert by remember { mutableStateOf<String?>(null) }

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

    val row = member?.row
    // A blocked member's real name, for Unblock (their row says "Blocked member").
    val realName = row?.let { r -> detail?.blocked?.firstOrNull { it.memberId == r.memberId }?.name ?: r.name } ?: ""
    val liveHeight by androidx.compose.runtime.produceState<Double?>(null, member?.live) {
        val l = member?.live ?: return@produceState
        value = model.height(GeoPoint(l.lat, l.lon))
    }
    SheetScreen(ScreenChrome(row?.name ?: "", leading(model, nav, below))) {
        val m = member ?: return@SheetScreen
        val r = m.row
        item {
            FormSection(first = true) {
                FormRow(verticalPadding = 12.dp) {
                    MemberDot(r.initials, r.colourHex, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(GroupTexts.memberName(r), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LabelPrimary)
                        GroupTexts.role(r.role)?.let { Text(it, style = Body, color = LabelSecondary) }
                    }
                }
                TextRow(GroupTexts.memberDetailState(r.state, now), colour = LabelSecondary)
            }
        }
        m.live?.let { live ->
            item { GroupPositionRow(model, GeoPoint(live.lat, live.lon), "memberCoordinates", liveHeight) }
            item {
                FormSection {
                    ButtonRow("Show on map", icon = Icons.Outlined.Map) {
                        model.setFollowing(false)
                        model.map?.center(on = GeoPoint(live.lat, live.lon), animated = true)
                        nav.close()
                    }
                }
            }
        }
        if (m.canMakeAdmin || m.canRemoveAdmin || m.canMakeOwner || m.canRemove) {
            item {
                FormSection {
                    if (m.canMakeAdmin) ButtonRow("Make admin") { run { groups.hub.setRole(GroupRole.admin, r.memberId, route.groupId) } }
                    if (m.canRemoveAdmin) ButtonRow("Remove admin") { run { groups.hub.setRole(GroupRole.member, r.memberId, route.groupId) } }
                    if (m.canMakeOwner) ButtonRow("Make owner") { alert = "owner" }
                    if (m.canRemove) ButtonRow("Remove from group", destructive = true) { alert = "remove" }
                }
            }
        }
        if (m.canReport || m.canBlock) {
            item {
                FormSection {
                    if (m.canReport) ButtonRow("Report $realName") { nav.push(GroupsRoute.Report(route.groupId, ReportTarget.Member(r.memberId))) }
                    if (m.canBlock) {
                        if (r.isBlocked) {
                            ButtonRow("Unblock $realName") { groups.hub.unblock(r.memberId, route.groupId) }
                        } else {
                            ButtonRow("Block $realName") { alert = "block" }
                        }
                    }
                }
            }
        }
        if (r.isYou) {
            item { FormSection { FormLinkRow("Your name and colour") { nav.push(GroupsRoute.EditName(route.groupId)) } } }
        }
    }
    val r = row ?: return
    when (alert) {
        "owner" -> HandoverAlert(realName, onDismiss = { alert = null }) {
            run {
                groups.hub.offerOwnership(r.memberId, route.groupId)
                nav.pop()
            }
        }
        "remove" -> AlertBox(
            "Remove $realName?",
            "$realName's trail and pins in this group are deleted. They can only come back with a new invite.",
            listOf(AlertButton("Remove", destructive = true) {
                run {
                    groups.hub.removeMember(r.memberId, route.groupId)
                    nav.pop()
                }
            }, AlertButton("Cancel") {}),
        ) { alert = null }
        "block" -> BlockAlert(model, route.groupId, r.memberId, realName, onBlocked = {}) { alert = null }
    }
    MessageAlert(message) { message = null }
}

/** S14's alert: "Make {name} the owner?" (§9.3). */
@Composable
fun HandoverAlert(name: String, onDismiss: () -> Unit, send: () -> Unit) {
    AlertBox(
        "Make $name the owner?",
        "$name needs SatMap Pro to accept. You'll become an admin. If they don't accept within 7 days, the request ends.",
        listOf(AlertButton("Send request", onClick = send), AlertButton("Cancel") {}),
        onDismiss,
    )
}

/** S14 Hand over ownership, from S5 (§9.3): the members to choose from, then the alert. */
@Composable
fun HandoverScreen(model: AppModel, groupId: String, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val rev by groups.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val detail = remember(rev, groupId) { groups.hub.detail(groupId) }
    var chosen by remember { mutableStateOf<MemberRow?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val now = rememberNow()
    SheetScreen(ScreenChrome("Hand over to", leading(model, nav, below))) {
        val d = detail ?: return@SheetScreen
        item {
            FormSection(first = true) {
                for (m in d.members.filter { !it.isYou }) key(m.memberId) {
                    FormLinkRow(onClick = { chosen = m }) {
                        MemberDot(m.initials, m.colourHex)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.name, style = Body, color = LabelPrimary)
                            Text(GroupTexts.memberSubtitle(m, now), style = Caption, color = LabelSecondary)
                        }
                    }
                }
            }
        }
    }
    chosen?.let { m ->
        HandoverAlert(m.name, onDismiss = { chosen = null }) {
            scope.launch {
                try {
                    groups.hub.offerOwnership(m.memberId, groupId)
                    nav.pop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = errorText(e)
                }
            }
        }
    }
    MessageAlert(message) { message = null }
}

/** S15 Your name and colour / Rename group (§9.3): one text field (and the 12 colours for your name); Save. */
@Composable
fun EditNameScreen(model: AppModel, groupId: String, rename: Boolean, nav: GroupsNav) {
    val groups = model.groups
    val scope = rememberCoroutineScope()
    val detail = remember(groupId) { groups.hub.detail(groupId) }
    val me = detail?.members?.firstOrNull { it.isYou }
    var text by rememberSaveable { mutableStateOf(if (rename) detail?.name ?: "" else me?.name ?: "") }
    var colour by rememberSaveable { mutableIntStateOf(me?.let { palette.indexOf(it.colourHex.uppercase()) }?.takeIf { it >= 0 } ?: 0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val field = if (rename) GroupTextField.groupName else GroupTextField.displayName
    val valid = GroupText.clean(text, field) != null
    val title = if (rename) "Rename group" else "Your name and colour"
    SheetScreen(
        ScreenChrome(
            title,
            ScreenChrome.Leading.Cancel(nav::pop),
            trailing = {
                TextButton(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                message = null
                                if (rename) groups.hub.renameGroup(text, groupId) else groups.hub.setDisplayName(text, colour, groupId)
                                nav.pop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = errorText(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Save", style = Body.copy(fontWeight = FontWeight.SemiBold), color = if (valid && !busy) Accent else LabelSecondary) }
            },
        ),
    ) {
        item {
            FormSection(first = true) {
                FormTextFieldRow(
                    text,
                    { text = it },
                    placeholder = if (rename) "e.g. Kruger trip" else "e.g. Sam",
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (rename) KeyboardCapitalization.Sentences else KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }
        if (!rename) {
            item {
                FormSection {
                    ColourChoices(colour, GroupText.initials(text.ifBlank { me?.name ?: "" })) { colour = it }
                }
            }
        }
        message?.let { item { FormSection { NoticeRow(it) } } }
    }
}

/** S16 Blocked (§9.3): the members blocked on this phone, by their real names, each with Unblock. */
@Composable
fun BlockedScreen(model: AppModel, groupId: String, nav: GroupsNav, below: GroupsRoute?) {
    val groups = model.groups
    val rev by groups.revision.collectAsStateWithLifecycle()
    val detail = remember(rev, groupId) { groups.hub.detail(groupId) }
    SheetScreen(ScreenChrome("Blocked", leading(model, nav, below))) {
        val d = detail ?: return@SheetScreen
        item {
            FormSection(
                first = true,
                footer = "Blocked members are hidden on this phone only. They aren't told, and they can still see you while you share in this group.",
            ) {
                for (m in d.blocked) key(m.memberId) {
                    FormRow {
                        MemberDot(m.initials, m.colourHex)
                        Spacer(Modifier.width(12.dp))
                        Text(m.name, style = Body, color = LabelPrimary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { groups.hub.unblock(m.memberId, groupId) }) { Text("Unblock", style = Body, color = Accent) }
                    }
                }
            }
        }
    }
}
