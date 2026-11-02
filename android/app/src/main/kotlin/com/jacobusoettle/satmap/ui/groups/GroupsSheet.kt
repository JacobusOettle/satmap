package com.jacobusoettle.satmap.ui.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GroupAlert
import com.jacobusoettle.satmap.groups.GroupsModel
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.RouteStack
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetStack
import kotlinx.coroutines.delay

/** How a groups screen moves about its sheet: push, pop (closing the sheet at its root), replace the whole stack (a
 *  finished create or join flow), and close. */
class GroupsNav internal constructor(private val state: MutableState<RouteStack<GroupsRoute>>, val close: () -> Unit) {
    val stack: RouteStack<GroupsRoute> get() = state.value

    fun push(r: GroupsRoute) = stack.push(r)

    fun pop() = if (stack.size > 1) stack.pop() else close()

    /** The stack becomes `routes` (a new stack: its root can change, as S1 → S2). */
    fun replace(routes: List<GroupsRoute>) {
        val s = RouteStack(routes.first())
        routes.drop(1).forEach(s::push)
        state.value = s
    }

    /** The top screen becomes `r` (the paywall → the screen it was for). */
    fun replaceTop(r: GroupsRoute) {
        if (stack.size > 1) {
            stack.pop()
            stack.push(r)
        } else {
            replace(listOf(r))
        }
    }

    /** Back to the screen `r` (popping what is over it), if it is on the stack. */
    fun popTo(r: GroupsRoute) {
        if (stack.routes.none { it == r }) return
        while (stack.size > 1 && stack.top != r) stack.pop()
    }
}

/** The leading button of a screen: "Back" when it isn't the root (§9.3: "leading 'Back' in the list stack"), else
 *  Done. */
@Composable
fun leading(model: AppModel, nav: GroupsNav, below: GroupsRoute?): ScreenChrome.Leading =
    if (below != null) ScreenChrome.Leading.Back("Back", nav::pop) else ScreenChrome.Leading.Done(nav.close)

/** A screen's title, which also labels the back button of a screen pushed over it. */
fun title(groups: GroupsModel, r: GroupsRoute): String = when (r) {
    GroupsRoute.List, GroupsRoute.Terms -> "Groups"
    is GroupsRoute.Paywall -> "SatMap Pro"
    GroupsRoute.Create -> "New group"
    is GroupsRoute.Detail -> groups.hub.detail(r.groupId)?.name ?: "Groups"
    is GroupsRoute.Consent -> "Share your location?"
    is GroupsRoute.Invite -> "Invite"
    GroupsRoute.JoinLink -> "Join a group"
    GroupsRoute.Scan -> "Scan QR code"
    is GroupsRoute.Member -> groups.hub.memberDetail(r.groupId, r.memberId)?.row?.let(GroupTexts::memberName) ?: "Member"
    is GroupsRoute.Pin -> GroupTexts.pinLabel(groups.hub.pinDetail(r.groupId, r.pinId)?.knownType)
    is GroupsRoute.Report -> "Report"
    is GroupsRoute.EditName -> "Your name and colour"
    is GroupsRoute.Rename -> "Rename group"
    is GroupsRoute.Blocked -> "Blocked"
    is GroupsRoute.Handover -> "Hand over to"
    is GroupsRoute.DropPin -> "Drop pin"
    is GroupsRoute.Join -> "Join group"
}

/** Seconds now, again every 30 s (for the "… ago" texts). */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** `ActiveSheet.groups` (§9.2): S1 first until the terms are accepted, else S2, or the screens a flow asks for. */
@Composable
fun GroupsSheet(model: AppModel, close: () -> Unit) {
    val groups = model.groups
    val state = remember { mutableStateOf(RouteStack<GroupsRoute>(if (groups.needsTerms) GroupsRoute.Terms else GroupsRoute.List)) }
    val nav = remember { GroupsNav(state, close) }
    val navigation by groups.navigation.collectAsStateWithLifecycle()
    LaunchedEffect(navigation) {
        val n = navigation ?: return@LaunchedEffect
        nav.replace(if (groups.needsTerms) listOf(GroupsRoute.Terms) else n.routes)
        groups.navigationTaken(n)
    }
    SheetStack(nav.stack) { route, below -> GroupScreen(model, route, below, nav) }
    // "You're already in {group}." over S5 (an invite to a group you are in, §5.6).
    val notice by groups.notice.collectAsStateWithLifecycle()
    MessageAlert(notice, groups::dismissNotice)
}

/** The sheet opened from the map (`model.groupItem`): a pin (S11), a member (S12) or dropping a pin (S10), with
 *  Report (and your name) pushed on it. */
@Composable
fun GroupItemSheet(model: AppModel, root: GroupsRoute, close: () -> Unit) {
    val state = remember(root) { mutableStateOf(RouteStack(root)) }
    val nav = remember(root) { GroupsNav(state, close) }
    SheetStack(nav.stack) { route, below -> GroupScreen(model, route, below, nav) }
}

/** S8's own sheet (`model.pendingInvite`), over anything: S1 first when the terms aren't accepted. */
@Composable
fun JoinSheet(model: AppModel, root: GroupsRoute.Join, close: () -> Unit) {
    val groups = model.groups
    val state = remember(root) { mutableStateOf(RouteStack<GroupsRoute>(if (groups.needsTerms) GroupsRoute.Terms else root)) }
    val nav = remember(root) { GroupsNav(state, close) }
    SheetStack(nav.stack) { route, below ->
        if (route == GroupsRoute.Terms) {
            TermsScreen(model, below = null, leading = ScreenChrome.Leading.Cancel(close)) { nav.replace(listOf(root)) }
        } else {
            GroupScreen(model, route, below, nav)
        }
    }
}

@Composable
private fun GroupScreen(model: AppModel, route: GroupsRoute, below: GroupsRoute?, nav: GroupsNav) {
    when (route) {
        GroupsRoute.Terms -> TermsScreen(model, below, leading(model, nav, below)) { nav.replace(listOf(GroupsRoute.List)) }
        GroupsRoute.List -> GroupListScreen(model, nav, below)
        is GroupsRoute.Paywall -> PaywallScreen(model, route, nav)
        GroupsRoute.Create -> CreateGroupScreen(model, nav)
        is GroupsRoute.Detail -> GroupDetailScreen(model, route.groupId, nav, below)
        is GroupsRoute.Consent -> ConsentScreen(model, route, nav)
        is GroupsRoute.Invite -> InviteScreen(route, nav)
        GroupsRoute.JoinLink -> JoinLinkScreen(model, nav, below)
        GroupsRoute.Scan -> ScanScreen(model, nav)
        is GroupsRoute.Member -> MemberDetailScreen(model, route, nav, below)
        is GroupsRoute.Pin -> PinDetailScreen(model, route, nav, below)
        is GroupsRoute.Report -> ReportScreen(model, route, nav)
        is GroupsRoute.EditName -> EditNameScreen(model, route.groupId, rename = false, nav)
        is GroupsRoute.Rename -> EditNameScreen(model, route.groupId, rename = true, nav)
        is GroupsRoute.Blocked -> BlockedScreen(model, route.groupId, nav, below)
        is GroupsRoute.Handover -> HandoverScreen(model, route.groupId, nav, below)
        is GroupsRoute.DropPin -> DropPinScreen(model, route, nav)
        is GroupsRoute.Join -> JoinScreen(model, route, nav)
    }
}

/** The removed, deleted and gone alerts (§9.3), over the map. */
@Composable
fun GroupAlerts(model: AppModel) {
    val alerts by model.groups.alerts.collectAsStateWithLifecycle()
    val a = alerts.firstOrNull() ?: return
    val (title, message) = when (a.kind) {
        GroupAlert.Kind.removed ->
            "Removed from ${a.groupName}" to "An admin removed you from this group. Its positions, trails and pins are gone from this phone."
        GroupAlert.Kind.deleted -> "${a.groupName} was deleted" to "The owner deleted this group."
        GroupAlert.Kind.gone -> "${a.groupName} is no longer available" to "The group no longer exists, or your membership ended."
    }
    AlertBox(title, message, listOf(AlertButton("OK") {}), onDismiss = model.groups::dismissAlert)
}
