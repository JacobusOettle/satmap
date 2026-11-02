// The groups screens' logic that needs no UIKit: the navigation routes and how the flows change them, the texts that
// depend on state (docs/groups/SPEC.md §9.3), dates and times, and the Pro prices. It is plain Foundation over the
// core's value types, so the SwiftPM tests compile it too (Tests/SatMapCoreTests/AppGroups links to this file); in
// the app it is part of the app module, where `SatMapCore` doesn't exist.
#if canImport(SatMapCore)
@testable import SatMapCore
#endif
import Foundation

// MARK: - Routes (§9.2)

/// What a report is about (S13).
enum ReportSubject: Hashable {
    case pin(String)
    case member(String)
    case group

    var target: ReportTarget {
        switch self {
        case .pin(let id): return .pin(pinId: id)
        case .member(let id): return .member(memberId: id)
        case .group: return .group
        }
    }
}

/// Where S9 (consent) goes when it finishes: the group's screen replaces it (after creating or joining), or back to
/// S5 (the switch was turned on there).
enum ConsentNext: Hashable {
    case showDetail
    case back
}

/// What a purchase on S3 continues with.
enum PaywallNext: Hashable {
    case create
    case renew(String)
    case accept(String)
}

/// The screens pushed in the Groups sheet (the root is S2, or S1 until the terms are accepted, or S5 opened alone).
/// S10 (drop pin) has no route in §9.2; `dropPin` is added for "Drop a pin where I am" in S5.
enum GroupsRoute: Hashable {
    case paywall(PaywallNext)
    case create
    case detail(String)
    case consent(String, ConsentNext)
    case invite(groupId: String, inviteId: String)
    case joinLink
    case scan
    case member(groupId: String, memberId: String)
    case pin(groupId: String, pinId: String)
    case report(groupId: String, subject: ReportSubject)
    case editName(String)
    case rename(String)
    case blocked(String)
    case handover(String)
    case dropPin(groupId: String, lat: Double, lon: Double)

    /// The group the screen belongs to, if any.
    var groupId: String? {
        switch self {
        case .paywall(.renew(let g)), .paywall(.accept(let g)): return g
        case .paywall(.create), .create, .joinLink, .scan: return nil
        case .detail(let g), .consent(let g, _), .editName(let g), .rename(let g), .blocked(let g), .handover(let g): return g
        case .invite(let g, _), .member(let g, _), .pin(let g, _), .report(let g, _), .dropPin(let g, _, _): return g
        }
    }
}

/// The root of the Groups sheet.
enum GroupsRoot: Equatable {
    /// S2 (S1 first while the terms aren't accepted).
    case list
    /// S5 opened alone (the paused banner, "Open group"): its leading button is "Done".
    case detail(String)
}

/// A screen opened from the map in its own sheet (S11, S12, and S10 at a tapped spot), with S13 pushed on it.
enum GroupItem: Identifiable, Hashable {
    case pin(groupId: String, pinId: String)
    case member(groupId: String, memberId: String)
    case dropPin(groupId: String, lat: Double, lon: Double)

    var id: String {
        switch self {
        case .pin(let g, let p): return "pin|\(g)|\(p)"
        case .member(let g, let m): return "member|\(g)|\(m)"
        case .dropPin(let g, let lat, let lon): return "drop|\(g)|\(lat)|\(lon)"
        }
    }
}

/// How the flows between the screens change the stack (§9.2).
enum GroupsFlow {
    /// S4 succeeded: the stack becomes [List, Consent(gid)].
    static func afterCreate(_ groupId: String) -> [GroupsRoute] {
        [.consent(groupId, .showDetail)]
    }

    /// S8 succeeded: the Groups sheet opens with [List, Consent(gid)].
    static func afterJoin(_ groupId: String) -> [GroupsRoute] {
        [.consent(groupId, .showDetail)]
    }

    /// S9 finished: replaced by [List, Detail(gid)] after creating or joining; popped back to S5 otherwise.
    static func afterConsent(_ path: [GroupsRoute], groupId: String, next: ConsentNext) -> [GroupsRoute] {
        var p = path
        guard let i = p.lastIndex(of: .consent(groupId, next)) else { return p }
        p.removeSubrange(i...)
        if next == .showDetail { p.append(.detail(groupId)) }
        return p
    }

    /// Where "Create group" leads: S3 first without Pro.
    static func createEntry(isPro: Bool) -> GroupsRoute {
        isPro ? .create : .paywall(.create)
    }

    /// A purchase on S3 succeeded: it pops, and the action continues (S4 for a create; E5 or E17 for the others).
    static func afterPurchase(_ path: [GroupsRoute], next: PaywallNext) -> [GroupsRoute] {
        var p = path
        if let i = p.lastIndex(of: .paywall(next)) { p.removeSubrange(i...) }
        if next == .create { p.append(.create) }
        return p
    }

    /// The scanner found an invite: it closes (S8 opens in its own sheet).
    static func afterScan(_ path: [GroupsRoute]) -> [GroupsRoute] {
        var p = path
        if let i = p.lastIndex(of: .scan) { p.removeSubrange(i...) }
        return p
    }

    /// A group is gone from the phone (left, deleted, removed): every screen of it closes.
    static func removingGroup(_ path: [GroupsRoute], _ groupId: String) -> [GroupsRoute] {
        guard let i = path.firstIndex(where: { $0.groupId == groupId }) else { return path }
        return Array(path[..<i])
    }

    /// The last screen closes.
    static func pop(_ path: [GroupsRoute]) -> [GroupsRoute] {
        Array(path.dropLast())
    }

    /// How long SwiftUI takes to close a sheet (and anything over it) once told to.
    static let sheetCloseTime: TimeInterval = 0.65

    /// How long an alert waits so that a screen told to close at `closedAt` is gone first: an alert presented over a
    /// screen on its way out would go with it.
    static func alertWait(screensClosedAt closedAt: Date?, now: Date) -> TimeInterval {
        guard let closedAt = closedAt else { return 0 }
        return min(sheetCloseTime, max(0, sheetCloseTime - now.timeIntervalSince(closedAt)))
    }
}

// MARK: - Texts (§9.3)

enum GroupTexts {
    /// "{n} members" ("1 member" for one).
    static func members(_ n: Int) -> String {
        n == 1 ? "1 member" : "\(n) members"
    }

    /// The S2 row's subtitle: an offer to you first, then a pause, else the members and whether you share.
    static func listSubtitle(_ s: GroupSummary) -> String {
        if s.hasOfferToYou { return "Wants you as owner" }
        if s.isPaused { return "Paused" }
        return "\(members(s.memberCount)) · \(s.isSharing ? "Sharing" : "Not sharing")"
    }

    static func role(_ r: GroupRole) -> String? {
        switch r {
        case .owner: return "Owner"
        case .admin: return "Admin"
        case .member: return nil
        }
    }

    /// The state in S5 rows and on the map (§8.9).
    static func state(_ s: MemberState, now: Int64) -> String {
        switch s {
        case .sharing(let at): return "Sharing · \(GroupFormat.ago(now - at))"
        case .lastSeen(let at): return "Last seen \(GroupFormat.ago(now - at))"
        case .notSharing: return "Not sharing"
        }
    }

    /// The state in S12.
    static func detailState(_ s: MemberState, now: Int64) -> String {
        switch s {
        case .sharing(let at): return "Sharing · last position \(GroupFormat.ago(now - at))"
        case .lastSeen(let at): return "Last seen \(GroupFormat.ago(now - at))"
        case .notSharing: return "Not sharing"
        }
    }

    /// An S5 member row's subtitle: the role ("Owner", "Admin" or nothing) joined with " · " to the state.
    static func memberSubtitle(role r: GroupRole, state s: MemberState, now: Int64) -> String {
        [role(r), state(s, now: now)].compactMap { $0 }.joined(separator: " · ")
    }

    /// A member row's name: " (you)" for you; a blocked member's row already reads "Blocked member" (core).
    static func memberName(_ row: MemberRow) -> String {
        row.isYou ? "\(row.name) (you)" : row.name
    }

    private static func pauseCause(_ reason: PausedReason?, isOwner: Bool) -> String {
        let whose = isOwner ? "your SatMap Pro" : "the owner's SatMap Pro"
        switch reason ?? .proEnded {
        case .proEnded: return "\(whose) has ended"
        case .proRefunded: return "\(whose) was refunded or cancelled"
        case .proUnverified: return "\(whose) couldn't be confirmed"
        }
    }

    /// The map's paused banner (§9.1).
    static func pausedBanner(group: String, reason: PausedReason?, isOwner: Bool) -> String {
        let text = "\(group) is paused: \(pauseCause(reason, isOwner: isOwner))."
        guard isOwner else { return text }
        return text + ((reason ?? .proEnded) == .proUnverified ? " Tap to fix." : " Tap to renew.")
    }

    /// S5's paused section. `deleteDate` is `deleteAt` as a date ("9 Nov"); without one the last sentence is left out.
    static func pausedSection(reason: PausedReason?, isOwner: Bool, deleteDate: String?) -> String {
        let until = isOwner ? "until you renew Pro or hand the group to a member who has Pro"
                            : "until the owner renews Pro or hands the group to a member who has Pro"
        var text = "This group is paused because \(pauseCause(reason, isOwner: isOwner)). Nobody can share their location or drop pins \(until)."
        if let d = deleteDate {
            text += " If it stays paused, everything shared in the group is deleted on \(d)."
        }
        return text
    }

    /// S5 "On the map" footer.
    static func sharingFooter(isSharing: Bool, isPaused: Bool) -> String {
        if isPaused { return "Sharing is off while the group is paused." }
        if isSharing { return "Members see where you are and the trail you leave, also while SatMap is in the background." }
        return "Members can't see where you are now. The trail you already shared stays visible until you clear it. You can still see the group and drop pins."
    }

    /// S10's footer: animal sightings expire, other pins stay.
    static func dropPinFooter(type: PinType?, group: String) -> String {
        type == .animal ? "Animal sightings disappear after 12 hours. Everyone in \(group) sees this pin."
                        : "Everyone in \(group) sees this pin until it's deleted."
    }

    /// S13: "Why are you reporting this {pin / name / group name}?"
    static func reportQuestion(_ s: ReportSubject) -> String {
        switch s {
        case .pin: return "Why are you reporting this pin?"
        case .member: return "Why are you reporting this name?"
        case .group: return "Why are you reporting this group name?"
        }
    }

    /// S13's notice: "We'll get a copy of this {pin's text / name / group name} …"
    static func reportNotice(_ s: ReportSubject) -> String {
        let what: String
        switch s {
        case .pin: what = "pin's text"
        case .member: what = "name"
        case .group: what = "group name"
        }
        return "We'll get a copy of this \(what) to review, without any position. We review reports within 24 hours."
    }

    static func reportReason(_ r: ReportReason) -> String {
        switch r {
        case .offensive: return "Offensive or hateful"
        case .harassment: return "Harassment or bullying"
        case .spam: return "Spam"
        case .dangerous: return "Dangerous or illegal"
        case .other: return "Something else"
        }
    }

    /// The text shared with "Share link" (S6).
    static func inviteShareText(group: String, link: String) -> String {
        "Join \"\(group)\" on SatMap: \(link)"
    }

    /// S6's footer.
    static func inviteFooter(date: String, time: String) -> String {
        "This invite works once, for one person, and expires on \(date) at \(time). Anyone who gets the link can use it, so send it only to the person you're inviting."
    }

    /// The pin's title (§9.4): the type label, "Pin" for a type this version doesn't know.
    static func pinTitle(_ type: PinType?) -> String {
        type?.label ?? PinType.unknownLabel
    }

    /// The grid's short label (§9.4).
    static func pinShortLabel(_ type: PinType) -> String {
        type == .animal ? "Animal" : type.label
    }

    /// The SF Symbol of a pin type (§9.4).
    static func pinSymbol(_ type: PinType?) -> String {
        switch type {
        case .animal: return "pawprint.fill"
        case .shelter: return "house.fill"
        case .water: return "drop.fill"
        case .danger: return "exclamationmark.triangle.fill"
        case .pothole: return "smallcircle.filled.circle"
        case .police: return "shield.lefthalf.filled"
        case nil: return "mappin"
        }
    }

    /// The alerts for a group that left the phone on its own (§9.3 "Alerts").
    static func alert(_ a: GroupAlert) -> (title: String, message: String) {
        switch a.kind {
        case .removed:
            return ("Removed from \(a.groupName)",
                    "An admin removed you from this group. Its positions, trails and pins are gone from this phone.")
        case .deleted:
            return ("\(a.groupName) was deleted", "The owner deleted this group.")
        case .gone:
            return ("\(a.groupName) is no longer available", "The group no longer exists, or your membership ended.")
        }
    }
}

// MARK: - Dates and times

/// Times in the phone's locale ("14:05" in South Africa); dates "9 Nov", with the year when it isn't this year (§9).
struct GroupDates {
    var locale: Locale = .current
    var timeZone: TimeZone = .current

    private func formatter(template: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = timeZone
        f.setLocalizedDateFormatFromTemplate(template)
        return f
    }

    private func year(_ t: Int64) -> Int {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = timeZone
        return c.component(.year, from: Date(timeIntervalSince1970: TimeInterval(t)))
    }

    /// "9 Nov", or "9 Nov 2027" when not this year.
    func day(_ t: Int64, now: Int64) -> String {
        formatter(template: year(t) == year(now) ? "dMMM" : "dMMMy").string(from: Date(timeIntervalSince1970: TimeInterval(t)))
    }

    /// "14:05" (the locale's short time).
    func time(_ t: Int64) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = timeZone
        f.dateStyle = .none
        f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
    }

    /// A moment: its time when it is today, otherwise "9 Nov, 14:05".
    func moment(_ t: Int64, now: Int64) -> String {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = timeZone
        let a = Date(timeIntervalSince1970: TimeInterval(t)), b = Date(timeIntervalSince1970: TimeInterval(now))
        return c.isDate(a, inSameDayAs: b) ? time(t) : "\(day(t, now: now)), \(time(t))"
    }
}

// MARK: - Pro

/// The three plans on the paywall (§10.1), in the order shown.
enum ProPlan: String, CaseIterable, Hashable {
    case yearly, monthly, lifetime

    var productId: String {
        switch self {
        case .yearly: return "pro.yearly"
        case .monthly: return "pro.monthly"
        case .lifetime: return "pro.lifetime"
        }
    }

    var title: String {
        switch self {
        case .yearly: return "Yearly"
        case .monthly: return "Monthly"
        case .lifetime: return "Lifetime"
        }
    }

    /// "{price} a year", "{price} a month", "{price} once".
    func detail(price: String) -> String {
        switch self {
        case .yearly: return "\(price) a year"
        case .monthly: return "\(price) a month"
        case .lifetime: return "\(price) once"
        }
    }

    /// "Subscribe" for the subscriptions, "Buy" for Lifetime.
    var buyTitle: String { self == .lifetime ? "Buy" : "Subscribe" }

    var accessibilityId: String {
        switch self {
        case .yearly: return "planYearly"
        case .monthly: return "planMonthly"
        case .lifetime: return "planLifetime"
        }
    }

    init?(productId: String) {
        guard let p = ProPlan.allCases.first(where: { $0.productId == productId }) else { return nil }
        self = p
    }
}

enum ProPricing {
    /// n = floor((1 − yearly ÷ (12 × monthly)) × 100), nil when it isn't above 0 (§9.3 S3).
    static func savePercent(yearly: Decimal, monthly: Decimal) -> Int? {
        guard monthly > 0, yearly > 0 else { return nil }
        var fraction = (1 - yearly / (12 * monthly)) * 100
        var floored = Decimal()
        NSDecimalRound(&floored, &fraction, 0, .down)
        let n = NSDecimalNumber(decimal: floored).intValue
        return n > 0 ? n : nil
    }
}

/// The Pro state kept on the phone for offline use (§10.6): `{"productId", "expiresAt" (null for Lifetime),
/// "checkedAt"}`. Never sent to the server.
struct ProCache: Codable, Equatable {
    var productId: String
    var expiresAt: Int64?
    var checkedAt: Int64

    /// Whether it still says Pro at `now`: Lifetime, or a subscription not yet expired.
    func isPro(now: Int64) -> Bool {
        expiresAt.map { $0 > now } ?? true
    }

    func encoded() -> Data? {
        try? JSONEncoder().encode(self)
    }

    static func decode(_ data: Data?) -> ProCache? {
        data.flatMap { try? JSONDecoder().decode(ProCache.self, from: $0) }
    }
}

// MARK: - Endpoints (§5.1, §6.1, §6.6, §8.7)

enum GroupEndpoints {
    /// The invite domain from the Info.plist value (the `GROUPS_DOMAIN` build setting); the placeholder
    /// `example.invalid` when it is missing or wasn't filled in.
    static func domain(infoPlistValue: String?) -> String {
        let value = infoPlistValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-")
        guard !value.isEmpty, value.unicodeScalars.allSatisfy(allowed.contains) else { return "example.invalid" }
        return value
    }

    /// The domain invite links are made and read with: the configured one, or in demo mode the demo's
    /// (`GroupDemo.domain`), which the fake server's links (S6, the walkthrough's pasted link) use whatever
    /// `GROUPS_DOMAIN` is.
    static func inviteDomain(configured: String, demo: Bool) -> String {
        demo ? GroupDemo.domain : configured
    }

    /// The WebSocket request's timeout, in seconds. URLSession may use it as an idle timer on the open socket, so it is
    /// well above the time between two frames of a quiet socket: the "pong" to a "ping" sent every 30 s on the 5 s
    /// tick grid (§8.7), with one of them allowed to go missing.
    static let socketTimeout: TimeInterval = 75

    /// The API base: `https://{domain}`, or the `-groupsBaseURL` launch argument when it is an http(s) URL with a host.
    static func apiBase(override: String?, domain: String) -> URL {
        if let text = override, let url = URL(string: text), url.scheme == "http" || url.scheme == "https", url.host != nil {
            return url
        }
        return URL(string: "https://" + domain)!
    }

    /// A request's URL: the base and a path such as "/v1/groups/abc/changes?since=4".
    static func apiURL(base: URL, path: String) -> URL? {
        var text = base.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        return URL(string: text + path)
    }

    /// The group's WebSocket: https → wss, http → ws (a local test server).
    static func socketURL(base: URL, groupId: String) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        c.scheme = c.scheme == "http" ? "ws" : "wss"
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        c.path = path + "/v1/groups/\(groupId)/ws"
        c.query = nil
        c.fragment = nil
        return c.url
    }

    /// The close code the app reports when a socket's task ended (§8.7): the server's close code if it sent one (0 means
    /// none), else the refused upgrade's status as a close code. `401` and `404` become 4401 (the hub checks with E2).
    /// A `410` is 4401 too: URLSession doesn't hand over the body that says "removed" or "group_deleted", and E2 then
    /// answers with the code, so the right alert shows. Anything else (or no answer) is 1006, which reconnects.
    static func closeCode(httpStatus: Int?, socketCloseCode: Int?) -> Int {
        if let c = socketCloseCode, c != 0 { return c }
        switch httpStatus {
        case 401, 404, 410: return 4401
        default: return 1006
        }
    }

    /// The URLSession errors that mean there is no connection (everything else is "something went wrong", §8.14).
    static func isOffline(_ code: URLError.Code) -> Bool {
        switch code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff, .callIsActive:
            return true
        default:
            return false
        }
    }
}

// MARK: - Colours

enum GroupColour {
    /// "#RRGGBB" as red, green and blue from 0 to 1; nil if it isn't that form.
    static func rgb(_ hex: String) -> (red: Double, green: Double, blue: Double)? {
        let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard digits.count == 6, let v = UInt32(digits, radix: 16) else { return nil }
        return (Double((v >> 16) & 0xFF) / 255, Double((v >> 8) & 0xFF) / 255, Double(v & 0xFF) / 255)
    }
}
