package com.jacobusoettle.satmap.ui.groups

import com.jacobusoettle.satmap.core.GroupFormat
import com.jacobusoettle.satmap.core.GroupRole
import com.jacobusoettle.satmap.core.GroupSummary
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.MemberRow
import com.jacobusoettle.satmap.core.MemberState
import com.jacobusoettle.satmap.core.PausedReason
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.core.ReportReason
import com.jacobusoettle.satmap.core.ReportTarget
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The groups screens' texts that are built from data (docs/groups/SPEC.md §9.3, §9.4), as plain functions so the
 *  tests check them. The fixed texts are written where they show. Both apps use the same words. */
object GroupTexts {
    /** "{n} members" (one member: "1 member"). */
    fun members(n: Int): String = if (n == 1) "1 member" else "$n members"

    /** What is wrong with a pasted (S7) or scanned (S17) `text`: an invite link without its key, or no invite link at
     *  all (nothing for an empty field); null for a whole invite link. */
    fun linkProblem(parsed: InviteLink.Parsed, text: String): String? = when (parsed) {
        InviteLink.Parsed.MissingKey -> "This invite link is incomplete. Copy the whole link from the message, or scan the QR code."
        InviteLink.Parsed.NotInvite -> if (text.isBlank()) null else "That isn't a SatMap invite link."
        is InviteLink.Parsed.Invite -> null
    }

    /** S2's row subtitle: an offer to you first, then paused, else the members and whether you share. */
    fun listSubtitle(g: GroupSummary): String = when {
        g.hasOfferToYou -> "Wants you as owner"
        g.isPaused -> "Paused"
        else -> members(g.memberCount) + if (g.isSharing) " · Sharing" else " · Not sharing"
    }

    /** "Sharing · {ago}", "Last seen {ago}" or "Not sharing" (§8.9). */
    fun state(state: MemberState, now: Long): String = when (state) {
        is MemberState.Sharing -> "Sharing · ${GroupFormat.ago(now - state.at)}"
        is MemberState.LastSeen -> "Last seen ${GroupFormat.ago(now - state.at)}"
        MemberState.NotSharing -> "Not sharing"
    }

    /** S12's state: "Sharing · last position {ago}", "Not sharing" or "Last seen {ago}". */
    fun memberDetailState(state: MemberState, now: Long): String = when (state) {
        is MemberState.Sharing -> "Sharing · last position ${GroupFormat.ago(now - state.at)}"
        is MemberState.LastSeen -> "Last seen ${GroupFormat.ago(now - state.at)}"
        MemberState.NotSharing -> "Not sharing"
    }

    fun role(role: GroupRole): String? = when (role) {
        GroupRole.owner -> "Owner"
        GroupRole.admin -> "Admin"
        GroupRole.member -> null
    }

    /** S5's member row subtitle: the role ("Owner", "Admin" or nothing) joined with " · " to the state. */
    fun memberSubtitle(row: MemberRow, now: Long): String = listOfNotNull(role(row.role), state(row.state, now)).joinToString(" · ")

    /** The row's name: " (you)" added for you; a blocked member's is "Blocked member" already (core). */
    fun memberName(row: MemberRow): String = if (row.isYou) "${row.name} (you)" else row.name

    /** The map's paused banner (§9.1). */
    fun pausedBanner(group: String, reason: PausedReason?, owner: Boolean): String = when (reason ?: PausedReason.proEnded) {
        PausedReason.proEnded ->
            if (owner) "$group is paused: your SatMap Pro has ended. Tap to renew." else "$group is paused: the owner's SatMap Pro has ended."
        PausedReason.proRefunded ->
            if (owner) "$group is paused: your SatMap Pro was refunded or cancelled. Tap to renew."
            else "$group is paused: the owner's SatMap Pro was refunded or cancelled."
        PausedReason.proUnverified ->
            if (owner) "$group is paused: your SatMap Pro couldn't be confirmed. Tap to fix."
            else "$group is paused: the owner's SatMap Pro couldn't be confirmed."
    }

    /** S5's paused section (§9.3 "Paused texts"); `date` is the formatted `deleteAt`. */
    fun pausedSection(reason: PausedReason?, owner: Boolean, date: String?): String {
        val whose = if (owner) "your SatMap Pro" else "the owner's SatMap Pro"
        val because = when (reason ?: PausedReason.proEnded) {
            PausedReason.proEnded -> "because $whose has ended"
            PausedReason.proRefunded -> "because $whose was refunded or cancelled"
            PausedReason.proUnverified -> "because $whose couldn't be confirmed"
        }
        val until = if (owner) "until you renew Pro or hand the group to a member who has Pro" else "until the owner renews Pro or hands the group to a member who has Pro"
        val deleted = date?.let { " If it stays paused, everything shared in the group is deleted on $it." } ?: ""
        return "This group is paused $because. Nobody can share their location or drop pins $until.$deleted"
    }

    /** §9.4 labels for titles ("Animal sighting"), and the grid's short ones ("Animal"). */
    fun pinLabel(type: PinType?): String = when (type) {
        PinType.animal -> "Animal sighting"
        PinType.shelter -> "Shelter"
        PinType.water -> "Water"
        PinType.danger -> "Danger"
        PinType.pothole -> "Pothole"
        PinType.police -> "Police"
        null -> "Pin"
    }

    fun pinShortLabel(type: PinType): String = when (type) {
        PinType.animal -> "Animal"
        else -> pinLabel(type)
    }

    /** §9.4 colours. */
    fun pinColour(type: PinType?): Long = when (type) {
        PinType.animal -> 0xFF2E7D32
        PinType.shelter -> 0xFF8D6E63
        PinType.water -> 0xFF1E88E5
        PinType.danger -> 0xFFE53935
        PinType.pothole -> 0xFFF57C00
        PinType.police -> 0xFF3949AB
        null -> 0xFF757575
    }

    fun reportReason(r: ReportReason): String = when (r) {
        ReportReason.offensive -> "Offensive or hateful"
        ReportReason.harassment -> "Harassment or bullying"
        ReportReason.spam -> "Spam"
        ReportReason.dangerous -> "Dangerous or illegal"
        ReportReason.other -> "Something else"
    }

    /** S13: "Why are you reporting this {pin / name / group name}?" */
    fun reportQuestion(t: ReportTarget): String = "Why are you reporting this ${reportWhat(t)}?"

    private fun reportWhat(t: ReportTarget) = when (t) {
        is ReportTarget.Pin -> "pin"
        is ReportTarget.Member -> "name"
        ReportTarget.Group -> "group name"
    }

    /** S13: "We'll get a copy of this {pin's text / name / group name} to review, …" */
    fun reportNote(t: ReportTarget): String {
        val what = when (t) {
            is ReportTarget.Pin -> "pin's text"
            is ReportTarget.Member -> "name"
            ReportTarget.Group -> "group name"
        }
        return "We'll get a copy of this $what to review, without any position. We review reports within 24 hours."
    }

    /** S6's share text. */
    fun shareText(group: String, link: String) = "Join \"$group\" on SatMap: $link"

    /** "9 Nov", with the year when it isn't this year (§9 intro), in the phone's time zone and in its locale's order
     *  ("Nov 9" in the US), as the iPhone app's day-and-month template. */
    fun date(
        seconds: Long,
        now: Long = System.currentTimeMillis() / 1000,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = seconds * 1000 }
        val thisYear = Calendar.getInstance(zone).apply { timeInMillis = now * 1000 }.get(Calendar.YEAR)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (c.get(Calendar.YEAR) == thisYear) "dMMM" else "dMMMy")
        return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(seconds * 1000))
    }

    /** The time as the phone shows it: "14:05" with a 24-hour clock, else "2:05 PM". */
    fun time(seconds: Long, use24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.ENGLISH).apply { timeZone = zone }.format(Date(seconds * 1000))

    /** A moment: its time when it is today, otherwise "9 Nov, 14:05" (S11's dropped and disappearing times). */
    fun moment(
        seconds: Long,
        now: Long,
        use24Hour: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val a = Calendar.getInstance(zone).apply { timeInMillis = seconds * 1000 }
        val b = Calendar.getInstance(zone).apply { timeInMillis = now * 1000 }
        val today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val t = time(seconds, use24Hour, zone)
        return if (today) t else "${date(seconds, now, zone, locale)}, $t"
    }

    /** S6's footer. */
    fun inviteFooter(date: String, time: String) =
        "This invite works once, for one person, and expires on $date at $time. Anyone who gets the link can use it, so send it only to the person you're inviting."

    /** S11's line. */
    fun droppedBy(name: String, time: String, ago: String) = "Dropped by $name · $time ($ago)"
}
