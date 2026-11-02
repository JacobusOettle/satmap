import Foundation

// MARK: - Transport, sockets and secrets (docs/groups/SPEC.md §8.2)

/// The app's HTTP transport (URLSession on iPhone; `FakeGroupServer` in demo mode and tests).
protocol GroupTransport: AnyObject {
    /// One request to "/v1/…" under the base URL; `pass` is the Bearer token or nil. Throws
    /// `GroupTransportError.offline` when there is no connection, `.failed` for any other transport error.
    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data)
}

enum GroupTransportError: Error, Equatable {
    case offline
    case failed
}

/// Opens the WebSocket of a group (the URL comes from the base URL). The app reports what happens on it through
/// `GroupHub.socketDidOpen`, `socketDidReceive` and `socketDidClose`, on the main thread.
protocol GroupSocketFactory: AnyObject {
    func open(groupId: String, pass: String) -> GroupSocket
}

protocol GroupSocket: AnyObject {
    func send(_ text: String)
    func close(code: Int)
}

/// Where the memberships live (Keychain on iPhone, Keystore on Android; never backed up).
protocol GroupSecretStore: AnyObject {
    func loadAll() -> [GroupSecret]
    func save(_ secret: GroupSecret) throws
    func delete(groupId: String)
}

/// A membership: the group, your member id and pass, and the group key (base64url texts).
struct GroupSecret: Codable, Equatable {
    let groupId: String
    let memberId: String
    let pass: String
    let groupKey: String
}

/// Secrets in memory only: demo mode (§8.15) and tests.
final class MemoryGroupSecrets: GroupSecretStore {
    private let lock = NSLock()
    private var secrets: [String: GroupSecret] = [:]
    private var order: [String] = []

    init(_ initial: [GroupSecret] = []) {
        for s in initial { try? save(s) }
    }

    func loadAll() -> [GroupSecret] {
        lock.lock()
        defer { lock.unlock() }
        return order.compactMap { secrets[$0] }
    }

    func save(_ secret: GroupSecret) throws {
        lock.lock()
        defer { lock.unlock() }
        if secrets[secret.groupId] == nil { order.append(secret.groupId) }
        secrets[secret.groupId] = secret
    }

    func delete(groupId: String) {
        lock.lock()
        defer { lock.unlock() }
        secrets[groupId] = nil
        order.removeAll { $0 == groupId }
    }
}

// MARK: - Value types (§8.2.1)

enum GroupRole: String, Equatable {
    case owner, admin, member
}

enum TrailsMode: String, Equatable {
    case off, day, all
}

enum PausedReason: String, Equatable {
    case proEnded = "pro_ended", proRefunded = "pro_refunded", proUnverified = "pro_unverified"
}

enum ReportReason: String, CaseIterable, Equatable {
    case offensive, harassment, spam, dangerous, other
}

enum ReportTarget: Equatable {
    case pin(pinId: String)
    case member(memberId: String)
    case group
}

/// The rule in §8.9.
enum MemberState: Equatable {
    case sharing(at: Int64)
    case lastSeen(at: Int64)
    case notSharing
}

struct GroupCoordinate: Equatable {
    let lat: Double
    let lon: Double
}

/// An S2 row.
struct GroupSummary: Equatable {
    let groupId: String
    let name: String
    let memberCount: Int
    let isSharing: Bool
    let isPaused: Bool
    let hasOfferToYou: Bool
    let isShown: Bool
}

/// An S5 member row.
struct MemberRow: Equatable {
    let memberId: String
    let name: String
    let initials: String
    let colourHex: String
    let role: GroupRole
    let isYou: Bool
    let isBlocked: Bool
    let state: MemberState
}

/// S5.
struct GroupDetail: Equatable {
    let groupId: String
    let name: String
    let memberCount: Int
    let yourMemberId: String
    let yourRole: GroupRole
    let joinedAt: Int64
    let isPaused: Bool
    let pausedReason: PausedReason?
    let deleteAt: Int64?
    let isShown: Bool
    let isSharing: Bool
    let hasConsent: Bool
    let trails: TrailsMode
    /// The owner's name when an offer to you is open.
    let offerToYouFrom: String?
    /// The target's name when your offer is open.
    let offerByYouTo: String?
    /// Ordered by joinedAt.
    let members: [MemberRow]
    /// Nil unless owner or admin; empty until E7 answers.
    let invites: [PendingInvite]?
    /// S16; real names.
    let blocked: [MemberRow]
    let canInvite: Bool
    let canRename: Bool
    let canHandOver: Bool
    let canDeleteGroup: Bool
    let canLeave: Bool
    /// Admins and members (§9.3 S5).
    let canReportGroupName: Bool
    /// "{groupId}.{memberId}".
    let supportCode: String
}

/// S12.
struct MemberDetail: Equatable {
    let row: MemberRow
    /// The newest live position, if any and not hidden.
    let live: GroupCoordinate?
    let canMakeAdmin: Bool
    let canRemoveAdmin: Bool
    let canMakeOwner: Bool
    let canRemove: Bool
    /// False for yourself.
    let canReport: Bool
    let canBlock: Bool
}

/// S11.
struct PinDetail: Equatable {
    let pinId: String
    let type: String
    let knownType: PinType?
    let animal: String?
    let note: String?
    let dropperId: String
    let dropperName: String
    let createdAt: Int64
    let expiresAt: Int64?
    let position: GroupCoordinate
    let isYours: Bool
    let canDelete: Bool
    let canReport: Bool
    let canBlock: Bool
}

/// S8.
struct InvitePreview: Equatable {
    let groupId: String
    let name: String
    let inviterName: String?
    let memberCount: Int
    let isPaused: Bool
    let expiresAt: Int64
}

/// S6.
struct MadeInvite: Equatable {
    let inviteId: String
    let link: String
    let expiresAt: Int64
    let groupName: String
}

/// An S5 invite row.
struct PendingInvite: Equatable {
    let inviteId: String
    let createdBy: String
    let createdByName: String
    let createdAt: Int64
    let expiresAt: Int64
    /// Only if this phone made it.
    let link: String?
}

struct GroupAlert: Equatable {
    enum Kind: Equatable {
        case removed, deleted, gone
    }

    let kind: Kind
    let groupId: String
    let groupName: String
}

enum GroupHubEvent: Equatable {
    case groups
    case group(String)
    case map
    case alert(GroupAlert)
}

/// §9.5, for the shown group; blocked members and filters already applied.
struct GroupMapContent: Equatable {
    let groupId: String
    /// Not you; not blocked; live within 24 h.
    let members: [MapMember]
    /// Per the Trails setting; already thinned (§9.5).
    let trails: [MapTrail]
    /// Unexpired; not from blocked members.
    let pins: [MapPin]
}

struct MapMember: Equatable {
    let memberId: String
    let position: GroupCoordinate
    let colourHex: String
    let initials: String
    /// Nil when unknown or stale.
    let headingDeg: Int?
    let isStale: Bool
    /// "{name}, {state text}" (§9.6).
    let a11yLabel: String
}

struct MapTrail: Equatable {
    let memberId: String
    let colourHex: String
    /// 3, or 2 above 10 visible trails.
    let widthPt: Double
    /// Each a polyline of 2+ points, oldest first.
    let segments: [[GroupCoordinate]]
}

struct MapPin: Equatable {
    let pinId: String
    let type: String
    let knownType: PinType?
    let position: GroupCoordinate
    /// §9.4.
    let colourHex: String
    /// Animal pins: "{animal} · {ago}" at `now`; nil otherwise.
    let label: String?
    /// "{title}, dropped by {name} {ago}" (§9.6).
    let a11yLabel: String
}

/// What an action throws; `message` is the text to show (§8.14, or the field texts of §9.3).
struct GroupActionError: Error, Equatable {
    enum Kind: Equatable {
        case offline, rateLimited, server, storeUnavailable, proNotConfirmed, tooManyGroups, forbidden
        case inviteNotFound, alreadyMember(groupId: String), damagedLink, termsOutdated, groupPaused, notFound
        case invalidText(GroupTextField), wordFilter(GroupTextField)
    }

    let kind: Kind
    let message: String

    init(kind: Kind, message: String) {
        self.kind = kind
        self.message = message
    }

    /// The error with its standard text.
    init(_ kind: Kind) {
        self.kind = kind
        switch kind {
        case .offline: message = "No connection. Try again when you're online."
        case .rateLimited: message = "Too many tries. Wait a moment and try again."
        case .server: message = "Something went wrong on our side. Try again in a minute."
        case .storeUnavailable: message = "The store can't be reached right now. Try again later."
        case .proNotConfirmed: message = "SatMap Pro couldn't be confirmed. Tap Restore purchases, then try again."
        case .tooManyGroups: message = "You already own 20 groups. Delete one to make a new group."
        case .forbidden: message = "You can't do that in this group."
        case .inviteNotFound: message = "This invite has already been used, was cancelled or has expired. Ask for a new one."
        case .alreadyMember: message = "You're already in this group."
        case .damagedLink: message = "This invite link is damaged. Ask for a new one."
        case .termsOutdated: message = "Please update SatMap to create or join groups."
        case .groupPaused: message = "This group is paused at the moment."
        case .notFound: message = "This is no longer in the group."
        case .invalidText(let field):
            switch field {
            case .groupName: message = "Group names are 1 to 40 characters, on one line."
            case .displayName: message = "Names are 1 to 30 characters, on one line."
            case .animal: message = "Type the animal, up to 40 characters."
            case .note: message = "Notes are up to 140 characters and 4 lines."
            case .reportComment: message = "Details are up to 500 characters and 4 lines."
            }
        case .wordFilter(let field):
            switch field {
            case .groupName: message = "Please use different words for the group name."
            case .displayName: message = "Please use different words for your name."
            case .animal, .note, .reportComment: message = "Please use different words."
            }
        }
    }

    /// The "Gone" alert's message, for an action on a group that no longer exists for you.
    static let gone = GroupActionError(kind: .notFound, message: "The group no longer exists, or your membership ended.")
}

// MARK: - The hub

/// The groups sync engine (§8): it holds the memberships, keeps the local store in step with the server (status,
/// change feed, live positions, WebSocket ticks), uploads your trail and live position at the §8.6 pace, sends the
/// queued requests, and builds what the screens and the map show.
///
/// All calls happen on the main thread: the synchronous ones by the app's contract, the `async` ones because they are
/// isolated to the main actor (network requests run on the transport). State is in memory, mirrored in `GroupStore`.
final class GroupHub {
    private let store: GroupStore
    private let secrets: GroupSecretStore
    private let api: GroupAPI
    private let sockets: GroupSocketFactory?
    private let words: WordFilter
    /// The invite domain, for links (`GroupConfig.domain`).
    let domain: String
    private let clock: () -> Int64

    var onChange: ((GroupHubEvent) -> Void)?
    /// The terms version the person accepted (`groupsTermsAccepted`), sent with create and redeem (§8.13). On
    /// `terms_outdated` the action throws `.termsOutdated`; the app shows S1 when it has newer terms, sets this and
    /// tries again.
    var acceptedTermsVersion = GroupPolicy.termsVersion

    private var states: [String: GroupState] = [:]
    /// Secrets with no local group yet: a reinstall's restore (§8.3), or a create or join whose answer was lost.
    private var pendingSecrets: [String: GroupSecret] = [:]
    private var foreground = false
    private var locationAuthorised = false
    private var locationPrecise = false
    private var latestFix: Fix?
    private var socket: SocketState?
    private var reconnectAttempt = 0
    private var reconnectAt: Int64?
    private var backgroundWork: [Task<Void, Never>] = []

    init(store: GroupStore, secrets: GroupSecretStore, transport: GroupTransport, sockets: GroupSocketFactory?,
         words: WordFilter, domain: String, clock: @escaping () -> Int64) {
        self.store = store
        self.secrets = secrets
        self.api = GroupAPI(transport: transport)
        self.sockets = sockets
        self.words = words
        self.domain = domain
        self.clock = clock
    }

    // MARK: Reading

    var groups: [GroupSummary] {
        orderedStates.map { st in
            GroupSummary(groupId: st.id, name: groupName(st), memberCount: memberCount(st), isSharing: st.sharingOn,
                         isPaused: st.isPaused, hasOfferToYou: st.status?.transfer?.to == st.secret.memberId, isShown: st.row.shown)
        }
    }

    func detail(_ groupId: String) -> GroupDetail? {
        guard let st = states[groupId] else { return nil }
        let now = clock()
        let role = st.role
        let rows = st.members.map { memberRow(st, $0, now: now, realName: false) }
        let blockedRows = st.members.filter { st.blocked.contains($0.memberId) && $0.memberId != st.secret.memberId }
            .map { memberRow(st, $0, now: now, realName: true) }
        var offerTo: String?
        var offerBy: String?
        if let t = st.status?.transfer, t.expiresAt > now {
            if t.to == st.secret.memberId, let owner = st.status?.ownerId {
                offerTo = name(st, owner, realName: false)
            } else if role == .owner {
                offerBy = name(st, t.to, realName: false)
            }
        }
        var invites: [PendingInvite]?
        if role == .owner || role == .admin {
            let made = Dictionary(st.madeInvites.map { ($0.inviteId, $0.link) }, uniquingKeysWith: { a, _ in a })
            invites = (st.invites ?? []).filter { $0.expiresAt > now }.map { i in
                PendingInvite(inviteId: i.inviteId, createdBy: i.createdBy, createdByName: name(st, i.createdBy, realName: false),
                              createdAt: i.createdAt, expiresAt: i.expiresAt, link: made[i.inviteId])
            }
        }
        return GroupDetail(groupId: st.id, name: groupName(st), memberCount: memberCount(st), yourMemberId: st.secret.memberId,
                           yourRole: role, joinedAt: st.row.joinedAt, isPaused: st.isPaused,
                           pausedReason: st.status?.pausedReason.flatMap(PausedReason.init(rawValue:)),
                           deleteAt: st.isPaused ? st.status?.deleteAt : nil, isShown: st.row.shown, isSharing: st.sharingOn,
                           hasConsent: st.row.consentAt != nil, trails: TrailsMode(rawValue: st.row.trails) ?? .day,
                           offerToYouFrom: offerTo, offerByYouTo: offerBy, members: rows, invites: invites, blocked: blockedRows,
                           canInvite: role != .member, canRename: role != .member, canHandOver: role == .owner,
                           canDeleteGroup: role == .owner, canLeave: role != .owner, canReportGroupName: role != .owner,
                           supportCode: "\(st.id).\(st.secret.memberId)")
    }

    var shownGroupId: String? {
        orderedStates.first { $0.row.shown }?.id
    }

    func mapContent(now: Int64) -> GroupMapContent? {
        guard let gid = shownGroupId, let st = states[gid] else { return nil }
        let me = st.secret.memberId
        var members: [MapMember] = []
        for m in st.members where m.memberId != me && !st.blocked.contains(m.memberId) {
            guard let l = st.live[m.memberId], now - l.at <= GroupPolicy.liveHiddenAfter else { continue }
            let stale = now - l.at > GroupPolicy.staleAfter
            let profile = memberProfile(st, m)
            let shown = profile.map { words.masked($0.name) } ?? Self.unknownMember
            members.append(MapMember(memberId: m.memberId, position: coordinate(l.position.lat, l.position.lon),
                                     colourHex: colourHex(profile), initials: GroupText.initials(shown),
                                     headingDeg: stale ? nil : l.position.heading, isStale: stale,
                                     a11yLabel: "\(shown), \(stateText(memberState(st, m.memberId, now: now), now: now))"))
        }
        var pins: [MapPin] = []
        for p in st.pins where (p.expiresAt.map { $0 > now } ?? true) && !st.blocked.contains(p.memberId) {
            guard let rec = pinRecord(st, p) else { continue }
            let ago = GroupFormat.ago(now - p.createdAt)
            let animal = rec.animal.map { words.masked($0) }
            let title = rec.knownType == .animal ? (animal ?? PinType.animal.label) : (rec.knownType?.label ?? PinType.unknownLabel)
            pins.append(MapPin(pinId: p.pinId, type: rec.type, knownType: rec.knownType, position: coordinate(rec.lat, rec.lon),
                               colourHex: rec.knownType?.colourHex ?? PinType.unknownColourHex,
                               label: rec.knownType == .animal ? "\(animal ?? "") · \(ago)" : nil,
                               a11yLabel: "\(title), dropped by \(name(st, p.memberId, realName: false)) \(ago)"))
        }
        return GroupMapContent(groupId: gid, members: members, trails: trails(st, now: now), pins: pins)
    }

    func pinDetail(groupId: String, pinId: String) -> PinDetail? {
        guard let st = states[groupId], let p = st.pins.first(where: { $0.pinId == pinId }), let rec = pinRecord(st, p) else { return nil }
        let mine = p.memberId == st.secret.memberId
        return PinDetail(pinId: p.pinId, type: rec.type, knownType: rec.knownType, animal: rec.animal.map { words.masked($0) },
                         note: rec.note.map { words.masked($0) }, dropperId: p.memberId, dropperName: name(st, p.memberId, realName: false),
                         createdAt: p.createdAt, expiresAt: p.expiresAt, position: coordinate(rec.lat, rec.lon), isYours: mine,
                         canDelete: mine || st.role != .member, canReport: !mine, canBlock: !mine)
    }

    func memberDetail(groupId: String, memberId: String) -> MemberDetail? {
        guard let st = states[groupId], let m = st.members.first(where: { $0.memberId == memberId }) else { return nil }
        let now = clock()
        let row = memberRow(st, m, now: now, realName: false)
        let you = memberId == st.secret.memberId
        var live: GroupCoordinate?
        if !row.isBlocked, let l = st.live[memberId], now - l.at <= GroupPolicy.liveHiddenAfter {
            live = coordinate(l.position.lat, l.position.lon)
        }
        let myRole = st.role
        return MemberDetail(row: row, live: live,
                            canMakeAdmin: myRole == .owner && !you && row.role == .member,
                            canRemoveAdmin: myRole == .owner && !you && row.role == .admin,
                            canMakeOwner: myRole == .owner && !you,
                            canRemove: !you && row.role != .owner && (myRole == .owner || (myRole == .admin && row.role == .member)),
                            canReport: !you, canBlock: !you)
    }

    func needsConsent(_ groupId: String) -> Bool {
        states[groupId]?.row.consentAt == nil
    }

    /// Seconds until the app should call `tick` again: 5 in the foreground; in the background 15 while someone watches a
    /// group you share with, 60 while you share or something is queued; nil when there is nothing to do.
    func nextTickDelay(foreground: Bool) -> Int? {
        guard !states.isEmpty || !pendingSecrets.isEmpty else { return nil }
        if foreground { return Int(GroupPolicy.liveWatchedForeground) }
        var delay: Int64?
        for st in states.values {
            if isEffectivelySharing(st) {
                delay = min(delay ?? .max, st.watchers > 0 ? GroupPolicy.liveWatchedBackground : GroupPolicy.uploadInterval)
            } else if st.stopLivePending || !store.ops(st.id).isEmpty || store.ownWindows(st.id).contains(where: { $0.version > $0.sentVersion }) {
                delay = min(delay ?? .max, GroupPolicy.uploadInterval)
            }
        }
        return delay.map { Int($0) }
    }

    // MARK: Lifecycle and pipeline

    /// At launch: loads the memberships and the store, then restores any membership with no local copy (§8.3).
    @MainActor
    func start() async {
        let all = secrets.loadAll()
        let ids = Set(all.map(\.groupId))
        for row in store.groups() where !ids.contains(row.groupId) {
            store.deleteGroup(row.groupId)
        }
        store.deleteExpiredMadeInvites(now: clock())
        for s in all {
            guard let key = Base64URL.decode(s.groupKey, count: 32), let gid = Base64URL.decode(s.groupId, count: 16),
                  Base64URL.decode(s.pass, count: 32) != nil, Base64URL.decode(s.memberId, count: 16) != nil else {
                GroupLog.note("a stored membership is damaged; deleted")
                secrets.delete(groupId: s.groupId)
                continue
            }
            if let row = store.group(s.groupId) {
                states[s.groupId] = GroupState(secret: s, keys: GroupKeys(groupKey: key, groupId: gid), row: row)
                reload(s.groupId)
            } else {
                pendingSecrets[s.groupId] = s
            }
        }
        emit(.groups)
        emit(.map)
        await restorePending()
        for st in orderedStates where st.row.cursor == 0 {
            await pull(st.id)
        }
    }

    /// The app came to the front: for every group the status, the changes and the queued uploads; for the shown
    /// group the live positions and the socket (§8.4).
    @MainActor
    func becameActive() async {
        foreground = true
        await restorePending()
        for st in orderedStates {
            await refreshStatus(st.id)
            await pull(st.id)
            await flush(st.id, now: clock(), force: true)
        }
        if let gid = shownGroupId {
            await fetchLive(gid)
        }
        connectSocketIfWanted()
    }

    func enteredBackground() {
        foreground = false
        closeSocket(code: 1000)
    }

    @MainActor
    func connectivityChanged(online: Bool) async {
        guard online else { return }
        for st in states.values {
            st.retryAt = 0
            st.retryStep = 0
        }
        reconnectAt = nil
        await restorePending()
        for st in orderedStates {
            await flush(st.id, now: clock(), force: true)
        }
        connectSocketIfWanted()
    }

    func setLocationAccess(authorised: Bool, precise: Bool) {
        locationAuthorised = authorised
        locationPrecise = precise
    }

    /// S5 opened: the status, the members' last live times (E11) and, for owners and admins, the pending invites.
    /// It also reads the live positions (E21) when the group isn't shown (the shown group's come with the socket or
    /// the foreground ticks), so a member who shares now reads "Sharing", not "Last seen" (§8.9).
    @MainActor
    func refreshDetail(_ groupId: String) async {
        await refreshStatus(groupId)
        guard let st = states[groupId] else { return }
        do {
            let members = try await api.members(groupId, pass: st.secret.pass)
            guard let st = states[groupId] else { return }
            st.lastLiveAt = Dictionary(members.compactMap { m in m.lastLiveAt.map { (m.memberId, $0) } }, uniquingKeysWith: { a, _ in a })
            emit(.group(groupId))
        } catch {
            await handleFailure(error, groupId)
        }
        if let st = states[groupId], !st.row.shown || sockets == nil {
            await fetchLive(groupId)
        }
        if let st = states[groupId], st.role != .member {
            _ = try? await invites(groupId: groupId)
        }
    }

    /// A point of "Where I've been" (`AppModel.appendTrack`): queued for every group that shares (§8.5).
    func trackPoint(_ point: TrackPoint) {
        guard point.time.isFinite else { return }
        for st in orderedStates where isEffectivelySharing(st) {
            let p = TrailPoint(time: Int64(point.time.rounded(.down)), lat: MicroDegrees.from(point.lat), lon: MicroDegrees.from(point.lon),
                               startsSegment: point.startsSegment || st.startSegmentNext)
            if store.addOwnPoint(st.id, p) {
                st.startSegmentNext = false
            }
        }
    }

    /// The newest location fix; kept in memory only, never queued (§8.5).
    func fix(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?, heading: Double?, time: Double) {
        latestFix = Fix(lat: lat, lon: lon, accuracy: accuracy, course: course, speed: speed, heading: heading, time: time)
    }

    /// Sends what is due (§8.6): queued requests, trail chunks and live positions. In the foreground it also keeps
    /// the socket going (reconnects, pings) or, without sockets (demo mode), pulls the shown group's changes and live
    /// positions.
    @MainActor
    func tick(foreground: Bool) async {
        self.foreground = foreground
        let now = clock()
        for st in orderedStates where store.deleteExpiredPins(st.id, now: now) > 0 {
            reload(st.id)
            notify(st.id)
        }
        if foreground {
            if sockets == nil, let gid = shownGroupId {
                await pull(gid)
                await fetchLive(gid)
            } else {
                connectSocketIfWanted()
                if var s = socket, s.isOpen, now - s.lastPing >= GroupPolicy.socketPing {
                    s.socket.send(SocketMessage.ping)
                    s.lastPing = now
                    socket = s
                }
            }
        } else {
            closeSocket(code: 1000)
        }
        for st in orderedStates {
            await flush(st.id, now: clock(), force: false)
        }
    }

    func socketDidOpen(groupId: String) {
        guard var s = socket, s.groupId == groupId, let st = states[groupId] else { return }
        s.isOpen = true
        s.lastPing = clock()
        socket = s
        s.socket.send(SocketMessage.hello(watching: isWatching(st), cursor: st.row.cursor))
    }

    func socketDidReceive(groupId: String, text: String) {
        guard let s = socket, s.groupId == groupId, let st = states[groupId], let message = SocketMessage.parse(text) else { return }
        switch message {
        case .welcome(let status, let live):
            socket?.welcomed = true
            reconnectAttempt = 0
            applyStatus(groupId, status)
            applyLive(st, live)
            if status.seq > st.row.cursor { background { await $0.pull(groupId) } }
        case .tick(let tick):
            if let w = tick.watchers { st.watchers = w }
            if let live = tick.live { applyLive(st, live) }
            if let status = tick.status { applyStatus(groupId, status) }
            if let changes = tick.changes {
                if changes.from > st.row.cursor {
                    background { await $0.pull(groupId) }
                } else {
                    applyPage(groupId, ChangesPage(items: changes.items, cursor: changes.cursor, more: changes.more,
                                                   serverTime: tick.serverTime ?? st.row.cursorAt))
                    if changes.more { background { await $0.pull(groupId) } }
                }
            }
        case .uploaded(let id, let result):
            guard let sent = socket?.pending.removeValue(forKey: id) else { return }
            applyUploadResult(st, result, sent: sent, now: clock())
        case .error(let id, let code):
            if let id = id { socket?.pending[id] = nil }
            if code == "group_paused" { background { await $0.refreshStatus(groupId) } }
        case .unknown:
            break
        }
    }

    func socketDidClose(groupId: String, code: Int) {
        guard let s = socket, s.groupId == groupId else { return }
        socket = nil
        switch code {
        case 4401:
            background { await $0.checkMembership(groupId) }
        case 4403:
            wipe(groupId, alert: .removed)
        case 4404:
            wipe(groupId, alert: .deleted)
        default:
            let step = GroupPolicy.socketBackoff[min(reconnectAttempt, GroupPolicy.socketBackoff.count - 1)]
            let jitter = Double.random(in: 0.8...1.2)
            reconnectAt = clock() + max(1, Int64((Double(step) * jitter).rounded()))
            reconnectAttempt += 1
        }
    }

    // MARK: Actions

    @MainActor
    func createGroup(name: String, displayName: String, pro: ProProof) async throws -> String {
        let groupName = try checked(name, .groupName)
        let myName = try checked(displayName, .displayName)
        var clashes = 0
        while true {
            let groupIdBytes = GroupRandom.bytes(16)
            let groupKey = GroupRandom.bytes(32)
            let keys = GroupKeys(groupKey: groupKey, groupId: groupIdBytes)
            let gid = keys.groupId
            let secret = GroupSecret(groupId: gid, memberId: GroupRandom.id(), pass: Base64URL.encode(GroupRandom.bytes(32)),
                                     groupKey: Base64URL.encode(groupKey))
            let body: CreateGroupRequest
            do {
                body = CreateGroupRequest(groupId: gid, memberId: secret.memberId, pass: secret.pass, joinProof: Base64URL.encode(keys.joinProof),
                                          groupProfile: Base64URL.encode(try GroupCrypto.seal(GroupProfile(name: groupName).json(), key: keys.group, aad: GroupAAD.group(gid))),
                                          memberProfile: Base64URL.encode(try GroupCrypto.seal(MemberProfile(name: myName, colour: 0).json(), key: keys.member,
                                                                                               aad: GroupAAD.member(gid, secret.memberId))),
                                          termsVersion: acceptedTermsVersion, pro: pro)
                // Saved before the request, so a crash after the server said yes can't lose the membership (§8.3).
                try secrets.save(secret)
            } catch {
                throw GroupActionError(.server)
            }
            do {
                let status = try await api.create(body)
                addGroup(secret, keys: keys, status: status)
                if case .google(_, let token) = pro { store.updateGroup(gid) { $0.proSent = token } }
                reload(gid)
                notify(gid)
                await pull(gid)
                return gid
            } catch let e as GroupAPIError where e.status == 409 && e.code == "group_exists" && clashes == 0 {
                secrets.delete(groupId: gid)
                clashes += 1
            } catch {
                forgetSecretIfDefinite(secret, error)
                throw actionError(error)
            }
        }
    }

    @MainActor
    func preview(_ invite: InviteLink) async throws -> InvitePreview {
        let gid = invite.groupIdText
        if pendingSecrets[gid] != nil { await restore(gid) }
        if let st = states[gid] {
            throw GroupActionError(kind: .alreadyMember(groupId: gid), message: "You're already in \(groupName(st)).")
        }
        let keys = GroupKeys(groupKey: invite.groupKey, groupId: invite.groupId)
        let p: PreviewDTO
        do {
            p = try await api.preview(token: invite.token, joinProof: Base64URL.encode(keys.joinProof))
        } catch {
            throw actionError(error)
        }
        guard let env = p.profile.flatMap(Base64URL.decode), let plain = try? GroupCrypto.open(env, key: keys.group, aad: GroupAAD.group(gid)),
              let profile = try? GroupProfile.decode(plain) else {
            throw GroupActionError(.damagedLink)
        }
        var inviter: String?
        if let i = p.inviter, let env = Base64URL.decode(i.profile),
           let plain = try? GroupCrypto.open(env, key: keys.member, aad: GroupAAD.member(gid, i.memberId)),
           let m = try? MemberProfile.decode(plain) {
            inviter = words.masked(m.name)
        } else if p.inviter != nil {
            inviter = Self.unknownMember
        }
        return InvitePreview(groupId: gid, name: words.masked(profile.name), inviterName: inviter, memberCount: p.memberCount,
                             isPaused: p.status == "paused", expiresAt: p.expiresAt)
    }

    @MainActor
    func join(_ invite: InviteLink, displayName: String) async throws -> String {
        let gid = invite.groupIdText
        if let st = states[gid] {
            throw GroupActionError(kind: .alreadyMember(groupId: gid), message: "You're already in \(groupName(st)).")
        }
        let myName = try checked(displayName, .displayName)
        let keys = GroupKeys(groupKey: invite.groupKey, groupId: invite.groupId)
        let colour = Int.random(in: 0..<GroupPolicy.palette.count)
        // A join whose answer was lost is retried with the same member id and pass: the server answers it again (§6.7).
        var secret = pendingSecrets[gid].flatMap { $0.groupKey == Base64URL.encode(invite.groupKey) ? $0 : nil }
            ?? GroupSecret(groupId: gid, memberId: GroupRandom.id(), pass: Base64URL.encode(GroupRandom.bytes(32)),
                           groupKey: Base64URL.encode(invite.groupKey))
        pendingSecrets[gid] = nil
        var clashes = 0
        while true {
            let body: RedeemRequest
            do {
                let profile = try GroupCrypto.seal(MemberProfile(name: myName, colour: colour).json(), key: keys.member,
                                                   aad: GroupAAD.member(gid, secret.memberId))
                body = RedeemRequest(token: invite.token, joinProof: Base64URL.encode(keys.joinProof), memberId: secret.memberId,
                                     pass: secret.pass, profile: Base64URL.encode(profile), termsVersion: acceptedTermsVersion)
                try secrets.save(secret)
            } catch {
                throw GroupActionError(.server)
            }
            do {
                let status = try await api.redeem(body)
                addGroup(secret, keys: keys, status: status)
                states[gid]?.needsColourCheck = true
                setShown(gid)
                notify(gid)
                await pull(gid)
                return gid
            } catch let e as GroupAPIError where e.status == 409 && e.code == "member_exists" && clashes == 0 {
                secrets.delete(groupId: gid)
                secret = GroupSecret(groupId: gid, memberId: GroupRandom.id(), pass: Base64URL.encode(GroupRandom.bytes(32)),
                                     groupKey: secret.groupKey)
                clashes += 1
            } catch {
                forgetSecretIfDefinite(secret, error)
                throw actionError(error)
            }
        }
    }

    /// Chooses the group the map shows (SD1); nil shows none.
    func setShown(_ groupId: String?) {
        let target = groupId.flatMap { states[$0] != nil ? $0 : nil }
        store.setShown(target)
        for st in states.values { st.row.shown = st.id == target }
        emit(.groups)
        if let gid = target { emit(.group(gid)) }
        emit(.map)
        connectSocketIfWanted()
        if let gid = target, foreground || sockets == nil {
            background { await $0.fetchLive(gid) }
        }
    }

    func recordConsent(_ groupId: String) {
        guard states[groupId] != nil else { return }
        let now = clock()
        store.updateGroup(groupId) { $0.consentAt = now }
        states[groupId]?.row.consentAt = now
        notify(groupId)
    }

    /// Sharing on or off for a group. On: the next point starts a segment. Off: an immediate upload with `"live": null`;
    /// points already queued still go (§8.5).
    func setSharing(_ on: Bool, groupId: String) {
        guard let st = states[groupId], st.row.sharing != on else { return }
        store.updateGroup(groupId) { $0.sharing = on }
        st.row.sharing = on
        if on {
            st.startSegmentNext = true
            st.stopLivePending = false
        } else {
            st.stopLivePending = true
            st.lastLiveSentAt = nil
            st.lastLivePosition = nil
            background { hub in await hub.flush(groupId, now: hub.clock(), force: true) }
        }
        notify(groupId)
    }

    func setTrails(_ mode: TrailsMode, groupId: String) {
        guard let st = states[groupId] else { return }
        store.updateGroup(groupId) { $0.trails = mode.rawValue }
        st.row.trails = mode.rawValue
        st.trailCache = nil
        notify(groupId)
    }

    @MainActor
    func setDisplayName(_ name: String, colour: Int, groupId: String) async throws {
        let cleaned = try checked(name, .displayName)
        guard let st = states[groupId] else { throw GroupActionError.gone }
        let me = st.secret.memberId
        let env: Data
        do {
            env = try GroupCrypto.seal(MemberProfile(name: cleaned, colour: max(0, min(GroupPolicy.palette.count - 1, colour))).json(),
                                       key: st.keys.member, aad: GroupAAD.member(groupId, me))
        } catch {
            throw GroupActionError(.server)
        }
        do {
            let seq = try await api.updateMyProfile(groupId, pass: st.secret.pass, profile: Base64URL.encode(env)).seq
            for op in store.ops(groupId) where op.kind == "profile" { store.deleteOp(op.id) }
            if var m = states[groupId]?.members.first(where: { $0.memberId == me }) {
                m.profile = env
                m.seq = seq
                store.putMember(groupId, m)
            }
            reload(groupId)
            notify(groupId)
        } catch {
            throw await failure(error, groupId)
        }
    }

    @MainActor
    func renameGroup(_ name: String, groupId: String) async throws {
        let cleaned = try checked(name, .groupName)
        guard let st = states[groupId] else { throw GroupActionError.gone }
        let env: Data
        do {
            env = try GroupCrypto.seal(GroupProfile(name: cleaned).json(), key: st.keys.group, aad: GroupAAD.group(groupId))
        } catch {
            throw GroupActionError(.server)
        }
        do {
            let seq = try await api.renameGroup(groupId, pass: st.secret.pass, profile: Base64URL.encode(env)).seq
            store.updateGroup(groupId) { g in
                if seq >= g.profileSeq {
                    g.profile = env
                    g.profileSeq = seq
                }
            }
            reload(groupId)
            notify(groupId)
        } catch {
            throw await failure(error, groupId)
        }
    }

    @MainActor
    func createInvite(groupId: String) async throws -> MadeInvite {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            let made = try await api.createInvite(groupId, pass: st.secret.pass)
            guard let groupKey = Base64URL.decode(st.secret.groupKey, count: 32), let link = InviteLink(token: made.token, groupKey: groupKey),
                  link.groupIdText == groupId else { throw GroupActionError(.server) }
            let url = link.url(domain: domain)
            store.putMadeInvite(groupId, GroupStore.MadeInvite(inviteId: made.inviteId, link: url, expiresAt: made.expiresAt))
            if let st = states[groupId] {
                st.madeInvites = store.madeInvites(groupId)
                st.invites = [InviteDTO(inviteId: made.inviteId, createdBy: st.secret.memberId, createdAt: made.createdAt,
                                        expiresAt: made.expiresAt)] + (st.invites ?? [])
                notify(groupId)
            }
            return MadeInvite(inviteId: made.inviteId, link: url, expiresAt: made.expiresAt, groupName: groupName(st))
        } catch {
            throw await failure(error, groupId)
        }
    }

    @MainActor
    func invites(groupId: String) async throws -> [PendingInvite] {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            let list = try await api.invites(groupId, pass: st.secret.pass)
            guard let st = states[groupId] else { throw GroupActionError.gone }
            st.invites = list
            let ids = Set(list.map(\.inviteId))
            for made in st.madeInvites where !ids.contains(made.inviteId) {
                store.deleteMadeInvite(groupId, made.inviteId)
            }
            st.madeInvites = store.madeInvites(groupId)
            notify(groupId)
            return detail(groupId)?.invites ?? []
        } catch {
            throw await failure(error, groupId)
        }
    }

    @MainActor
    func cancelInvite(_ inviteId: String, groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            try await api.cancelInvite(groupId, pass: st.secret.pass, inviteId: inviteId)
        } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
            // Already gone: done.
        } catch {
            throw await failure(error, groupId)
        }
        store.deleteMadeInvite(groupId, inviteId)
        if let st = states[groupId] {
            st.invites?.removeAll { $0.inviteId == inviteId }
            st.madeInvites = store.madeInvites(groupId)
            notify(groupId)
        }
    }

    @MainActor
    func setRole(_ role: GroupRole, memberId: String, groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            let seq = try await api.setRole(groupId, pass: st.secret.pass, memberId: memberId, role: role.rawValue).seq
            if var m = states[groupId]?.members.first(where: { $0.memberId == memberId }) {
                m.role = role.rawValue
                m.seq = seq
                store.putMember(groupId, m)
            }
            reload(groupId)
            notify(groupId)
        } catch {
            throw await failure(error, groupId)
        }
    }

    @MainActor
    func removeMember(_ memberId: String, groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            try await api.removeMember(groupId, pass: st.secret.pass, memberId: memberId)
        } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
            // Already gone: done.
        } catch {
            throw await failure(error, groupId)
        }
        store.deleteMember(groupId, memberId)
        reload(groupId)
        notify(groupId)
    }

    @MainActor
    func offerOwnership(to memberId: String, groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            let status = try await api.offerOwnership(groupId, pass: st.secret.pass, to: memberId)
            applyStatus(groupId, status)
        } catch {
            throw await failure(error, groupId)
        }
    }

    /// The owner cancels the offer, or its target declines it.
    @MainActor
    func cancelOwnershipOffer(groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            try await api.cancelOwnershipOffer(groupId, pass: st.secret.pass)
        } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
            // No offer any more: done.
        } catch {
            throw await failure(error, groupId)
        }
        await refreshStatus(groupId)
    }

    @MainActor
    func acceptOwnership(groupId: String, pro: ProProof) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        do {
            let status = try await api.acceptOwnership(groupId, pass: st.secret.pass, pro: pro)
            if case .google(_, let token) = pro { store.updateGroup(groupId) { $0.proSent = token } }
            applyStatus(groupId, status)
            await pull(groupId)
        } catch {
            throw await failure(error, groupId)
        }
    }

    /// Sends the Pro proof (E5) for each group you own where §10.7 says so: no Pro on record, a Pro pause, another
    /// product, a later known expiry, or (Google) a new purchase token. A `402` changes nothing on the phone.
    @MainActor
    func updatePro(_ pro: ProProof, productId: String, expiresAt: Int64?) async {
        for st in orderedStates where st.role == .owner {
            guard let status = st.status, shouldSendPro(status: status, row: st.row, pro: pro, productId: productId, expiresAt: expiresAt) else { continue }
            do {
                let answer = try await api.updatePro(st.id, pass: st.secret.pass, pro: pro)
                if case .google(_, let token) = pro { store.updateGroup(st.id) { $0.proSent = token } }
                applyStatus(st.id, answer)
            } catch let e as GroupAPIError where e.status == 402 {
                GroupLog.note("pro proof refused (\(e.code))")
            } catch {
                await handleFailure(error, st.id)
            }
        }
    }

    /// Drops a pin: shown at once, sent when online (queued in `ops`).
    func dropPin(groupId: String, type: PinType, animal: String?, note: String?, lat: Double, lon: Double) throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        guard !st.isPaused else { throw GroupActionError(.groupPaused) }
        var animalName: String?
        if type == .animal {
            animalName = try checked(animal ?? "", .animal)
        }
        let cleanedNote = try checked(note ?? "", .note)
        let record = PinRecord(type: type.rawValue, animal: animalName, note: cleanedNote.isEmpty ? nil : cleanedNote,
                               lat: MicroDegrees.from(lat), lon: MicroDegrees.from(lon))
        let pinId = GroupRandom.id()
        let createdAt = clock()
        let expiresAt = type == .animal ? createdAt + GroupPolicy.animalPinLifetime : nil
        let me = st.secret.memberId
        let env: Data
        do {
            env = try GroupCrypto.seal(record.json(), key: st.keys.pin,
                                       aad: GroupAAD.pin(groupId, pinId: pinId, memberId: me, createdAt: createdAt, expiresAt: expiresAt))
        } catch {
            throw GroupActionError(.server)
        }
        let op = PinOp(pinId: pinId, createdAt: createdAt, expiresAt: expiresAt, plain: Base64URL.encode(record.json()), retried: false)
        try? store.transaction {
            store.putPin(groupId, GroupStore.StoredPin(pinId: pinId, memberId: me, createdAt: createdAt, expiresAt: expiresAt, rec: env, seq: 0))
            store.addOp(groupId, kind: "pin", body: op.json, createdAt: createdAt)
        }
        reload(groupId)
        notify(groupId)
        background { hub in await hub.flush(groupId, now: hub.clock(), force: true) }
    }

    /// Deletes a pin for everyone: gone from the map at once; the request is queued.
    func deletePin(_ pinId: String, groupId: String) {
        guard states[groupId] != nil else { return }
        let unsent = store.ops(groupId).first { $0.kind == "pin" && PinOp(json: $0.body)?.pinId == pinId }
        try? store.transaction {
            store.deletePin(groupId, pinId)
            if let op = unsent {
                store.deleteOp(op.id)
            } else {
                store.addOp(groupId, kind: "unpin", body: pinId, createdAt: clock())
            }
        }
        reload(groupId)
        notify(groupId)
        background { hub in await hub.flush(groupId, now: hub.clock(), force: true) }
    }

    /// Reports a pin, a member or the group name with the reporter's decrypted copy, never a position (SD10, SD28).
    @MainActor
    func report(groupId: String, target: ReportTarget, reason: ReportReason, comment: String) async throws {
        let cleaned = try checked(comment, .reportComment)
        guard let st = states[groupId] else { throw GroupActionError.gone }
        var content: [String: String] = [:]
        let kind: String
        let targetId: String
        switch target {
        case .pin(let pinId):
            kind = "pin"
            targetId = pinId
            if let p = st.pins.first(where: { $0.pinId == pinId }) {
                if let rec = pinRecord(st, p) {
                    content["type"] = String(String.UnicodeScalarView(rec.type.unicodeScalars.prefix(20)))
                    content["animal"] = rec.animal
                    content["note"] = rec.note
                }
                content["name"] = realName(st, p.memberId)
            }
        case .member(let memberId):
            kind = "member"
            targetId = memberId
            content["name"] = realName(st, memberId)
        case .group:
            kind = "group"
            targetId = groupId
            content["name"] = groupProfile(st)?.name
        }
        do {
            _ = try await api.report(groupId, pass: st.secret.pass,
                                     ReportRequest(targetKind: kind, targetId: targetId, reason: reason.rawValue, comment: cleaned, content: content))
        } catch {
            throw await failure(error, groupId)
        }
    }

    /// Hides a member on this phone only (§8.11). You can't block yourself.
    func block(_ memberId: String, groupId: String) {
        guard let st = states[groupId], memberId != st.secret.memberId else { return }
        store.setBlocked(groupId, memberId, true)
        st.blocked.insert(memberId)
        st.trailCache = nil
        notify(groupId)
    }

    func unblock(_ memberId: String, groupId: String) {
        guard let st = states[groupId] else { return }
        store.setBlocked(groupId, memberId, false)
        st.blocked.remove(memberId)
        st.trailCache = nil
        notify(groupId)
    }

    /// Deletes your queued points and stored chunks at once and queues E22; no chunks go up until it has gone (§8.10).
    func clearMyTrail(groupId: String) {
        guard let st = states[groupId] else { return }
        try? store.transaction {
            store.deleteOwnPoints(groupId)
            store.deleteChunks(groupId, memberId: st.secret.memberId)
            if !store.hasOp(groupId, kind: "cleartrail") {
                store.addOp(groupId, kind: "cleartrail", body: "", createdAt: clock())
            }
        }
        st.badTimeHold.removeAll()
        st.chunkRevision += 1
        notify(groupId)
        background { hub in await hub.flush(groupId, now: hub.clock(), force: true) }
    }

    /// Leaves the group (needs a connection); then everything of it goes from the phone. The owner can't leave (SD12).
    @MainActor
    func leave(groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        guard st.role != .owner else { throw GroupActionError(.forbidden) }
        do {
            try await api.removeMember(groupId, pass: st.secret.pass, memberId: "me")
        } catch let e as GroupAPIError where [401, 404, 410].contains(e.status) {
            // No longer a member anyway.
        } catch {
            throw actionError(error)
        }
        wipe(groupId, alert: nil)
    }

    @MainActor
    func deleteGroup(groupId: String) async throws {
        guard let st = states[groupId] else { throw GroupActionError.gone }
        guard st.role == .owner else { throw GroupActionError(.forbidden) }
        do {
            try await api.deleteGroup(groupId, pass: st.secret.pass)
        } catch let e as GroupAPIError where (e.status == 404 && e.code == "group_not_found") || (e.status == 410 && e.code == "group_deleted") {
            // Already deleted.
        } catch {
            throw await failure(error, groupId)
        }
        wipe(groupId, alert: nil)
    }

    // MARK: - Internals: state

    static let unknownMember = "Unknown member"
    static let blockedMember = "Blocked member"
    static let unnamedGroup = "Unnamed group"
    private static let proReasons: Set<String> = ["pro_ended", "pro_refunded", "pro_unverified"]

    private struct Fix {
        var lat: Double
        var lon: Double
        var accuracy: Double?
        var course: Double?
        var speed: Double?
        var heading: Double?
        var time: Double
    }

    /// What a send carried: the windows and versions, and whether it carried a live record.
    struct SentUpload {
        var windows: [(start: Int64, version: Int64)]
    }

    private struct SocketState {
        let groupId: String
        let socket: GroupSocket
        var isOpen = false
        var welcomed = false
        var lastPing: Int64 = 0
        var nextId = 1
        var pending: [Int: SentUpload] = [:]
    }

    /// A queued pin (`ops` kind "pin"): its plain record is kept so it can be sealed again with a new id after a
    /// `pin_exists` clash.
    private struct PinOp: Codable {
        var pinId: String
        var createdAt: Int64
        var expiresAt: Int64?
        var plain: String
        var retried: Bool

        init(pinId: String, createdAt: Int64, expiresAt: Int64?, plain: String, retried: Bool) {
            self.pinId = pinId
            self.createdAt = createdAt
            self.expiresAt = expiresAt
            self.plain = plain
            self.retried = retried
        }

        init?(json: String) {
            guard let op = try? JSONDecoder().decode(PinOp.self, from: Data(json.utf8)) else { return nil }
            self = op
        }

        var json: String {
            (try? String(decoding: JSONEncoder().encode(self), as: UTF8.self)) ?? "{}"
        }
    }

    private final class GroupState {
        let secret: GroupSecret
        let keys: GroupKeys
        var row: GroupStore.StoredGroup
        var status: GroupStatusDTO?
        var members: [GroupStore.StoredMember] = []
        var pins: [GroupStore.StoredPin] = []
        var live: [String: (at: Int64, position: LivePosition)] = [:]
        var blocked: Set<String> = []
        var madeInvites: [GroupStore.MadeInvite] = []
        var invites: [InviteDTO]?
        var lastLiveAt: [String: Int64] = [:]

        var profileCache: [String: (data: Data, profile: MemberProfile?)] = [:]
        var pinCache: [String: (data: Data, record: PinRecord?)] = [:]
        var chunkCache: [String: (seq: Int64, data: Data, points: [TrailPoint]?)] = [:]
        var chunkRevision = 0
        var trailCache: (key: String, trails: [MapTrail])?

        /// When the last chunk upload and the last live slot were (nil: never), for the pace (§8.6).
        var lastChunkUpload: Int64?
        var lastLiveSlot: Int64?
        var lastLiveSentAt: Int64?
        var lastLivePosition: GeoPoint?
        var watchers = 0
        var startSegmentNext = true
        var badTimeHold: Set<Int64> = []
        var retryAt: Int64 = 0
        var retryStep = 0
        var stopLivePending = false
        var needsColourCheck = false
        var pulling = false
        var pullAgain = false
        var flushing = false

        init(secret: GroupSecret, keys: GroupKeys, row: GroupStore.StoredGroup) {
            self.secret = secret
            self.keys = keys
            self.row = row
            status = row.statusJSON.flatMap { try? JSONDecoder().decode(GroupStatusDTO.self, from: Data($0.utf8)) }
        }

        var id: String { secret.groupId }
        var role: GroupRole { GroupRole(rawValue: status?.you.role ?? row.role) ?? .member }
        var isPaused: Bool { status?.isPaused ?? false }
        /// The sharing switch as shown: on, with consent.
        var sharingOn: Bool { row.sharing && row.consentAt != nil }
    }

    private var orderedStates: [GroupState] {
        states.values.sorted { ($0.row.joinedAt, $0.id) < ($1.row.joinedAt, $1.id) }
    }

    /// Whether the group shares now (§8.5): switched on, consent recorded, active, location authorised and precise.
    private func isEffectivelySharing(_ st: GroupState) -> Bool {
        st.sharingOn && !st.isPaused && locationAuthorised && locationPrecise
    }

    private func isWatching(_ st: GroupState) -> Bool {
        foreground && st.row.shown && !st.isPaused
    }

    /// Reloads a group's rows from the store into memory.
    private func reload(_ groupId: String) {
        guard let st = states[groupId], let row = store.group(groupId) else { return }
        st.row = row
        if let json = row.statusJSON, let status = try? JSONDecoder().decode(GroupStatusDTO.self, from: Data(json.utf8)) {
            st.status = status
        }
        st.members = store.members(groupId)
        st.pins = store.pins(groupId)
        st.blocked = store.blocked(groupId)
        st.madeInvites = store.madeInvites(groupId)
        var live: [String: (at: Int64, position: LivePosition)] = [:]
        for l in store.live(groupId) {
            if let p = openLive(st, memberId: l.memberId, at: l.at, rec: l.rec) { live[l.memberId] = (l.at, p) }
        }
        st.live = live
    }

    private func addGroup(_ secret: GroupSecret, keys: GroupKeys, status: GroupStatusDTO) {
        let row = GroupStore.StoredGroup(groupId: secret.groupId, memberId: secret.memberId, role: status.you.role, joinedAt: status.you.joinedAt,
                                         statusJSON: Self.json(status), profile: status.profile.flatMap(Base64URL.decode),
                                         profileSeq: status.profileSeq ?? 0)
        if let existing = store.group(secret.groupId) {
            var merged = existing
            merged.role = row.role
            merged.joinedAt = row.joinedAt
            merged.statusJSON = row.statusJSON
            store.putGroup(merged)
        } else {
            store.putGroup(row)
        }
        pendingSecrets[secret.groupId] = nil
        let st = GroupState(secret: secret, keys: keys, row: store.group(secret.groupId) ?? row)
        st.status = status
        st.watchers = status.watchers ?? 0
        states[secret.groupId] = st
        reload(secret.groupId)
    }

    private static func json(_ status: GroupStatusDTO) -> String? {
        (try? JSONEncoder().encode(status)).map { String(decoding: $0, as: UTF8.self) }
    }

    private func emit(_ event: GroupHubEvent) {
        onChange?(event)
    }

    /// Tells the app a group changed (and the map, if it is the shown group).
    private func notify(_ groupId: String) {
        emit(.groups)
        emit(.group(groupId))
        if states[groupId]?.row.shown == true { emit(.map) }
    }

    private func background(_ work: @escaping (GroupHub) async -> Void) {
        let task = Task { @MainActor [weak self] in
            guard let self = self else { return }
            await work(self)
        }
        backgroundWork.append(task)
        if backgroundWork.count > 32 { backgroundWork.removeFirst(backgroundWork.count - 32) }
    }

    /// Waits for the work the synchronous calls started (tests).
    @MainActor
    func waitForBackgroundWork() async {
        while !backgroundWork.isEmpty {
            let work = backgroundWork
            backgroundWork = []
            for t in work { await t.value }
        }
    }

    // MARK: - Internals: names and decoding

    private func memberProfile(_ st: GroupState, _ m: GroupStore.StoredMember) -> MemberProfile? {
        if let c = st.profileCache[m.memberId], c.data == m.profile { return c.profile }
        var profile: MemberProfile?
        do {
            profile = try MemberProfile.decode(GroupCrypto.open(m.profile, key: st.keys.member, aad: GroupAAD.member(st.id, m.memberId)))
        } catch {
            GroupLog.note("a member profile doesn't open (\(error)); shown as unknown")
        }
        st.profileCache[m.memberId] = (m.profile, profile)
        return profile
    }

    private func groupProfile(_ st: GroupState) -> GroupProfile? {
        guard let env = st.row.profile else { return nil }
        return (try? GroupCrypto.open(env, key: st.keys.group, aad: GroupAAD.group(st.id))).flatMap { try? GroupProfile.decode($0) }
    }

    private func groupName(_ st: GroupState) -> String {
        groupProfile(st).map { words.masked($0.name) } ?? Self.unnamedGroup
    }

    private func memberCount(_ st: GroupState) -> Int {
        st.status?.memberCount ?? st.members.count
    }

    /// The name shown for a member: masked, "Unknown member" if the profile doesn't open, "Blocked member" if blocked.
    private func name(_ st: GroupState, _ memberId: String, realName: Bool) -> String {
        if !realName, st.blocked.contains(memberId), memberId != st.secret.memberId { return Self.blockedMember }
        guard let m = st.members.first(where: { $0.memberId == memberId }), let p = memberProfile(st, m) else { return Self.unknownMember }
        return words.masked(p.name)
    }

    /// The decrypted name as typed (reports carry the reporter's copy unmasked).
    private func realName(_ st: GroupState, _ memberId: String) -> String? {
        guard let m = st.members.first(where: { $0.memberId == memberId }) else { return nil }
        return memberProfile(st, m)?.name
    }

    private func colourHex(_ profile: MemberProfile?) -> String {
        guard let c = profile?.colour, GroupPolicy.palette.indices.contains(c) else { return GroupPolicy.grey }
        return GroupPolicy.palette[c]
    }

    private func memberRow(_ st: GroupState, _ m: GroupStore.StoredMember, now: Int64, realName: Bool) -> MemberRow {
        let blocked = st.blocked.contains(m.memberId) && m.memberId != st.secret.memberId
        let profile = memberProfile(st, m)
        let shown = name(st, m.memberId, realName: realName)
        return MemberRow(memberId: m.memberId, name: shown, initials: GroupText.initials(shown),
                         colourHex: blocked && !realName ? GroupPolicy.grey : colourHex(profile),
                         role: GroupRole(rawValue: m.role) ?? .member, isYou: m.memberId == st.secret.memberId, isBlocked: blocked,
                         state: memberState(st, m.memberId, now: now))
    }

    /// §8.9: the newest live record if it is at most 10 minutes old is sharing, older is last seen; without one, the
    /// last E11 `lastLiveAt`; otherwise not sharing.
    private func memberState(_ st: GroupState, _ memberId: String, now: Int64) -> MemberState {
        if let l = st.live[memberId], now - l.at <= GroupPolicy.liveHiddenAfter {
            return now - l.at <= GroupPolicy.staleAfter ? .sharing(at: l.at) : .lastSeen(at: l.at)
        }
        if let last = st.lastLiveAt[memberId] { return .lastSeen(at: last) }
        return .notSharing
    }

    private func stateText(_ state: MemberState, now: Int64) -> String {
        switch state {
        case .sharing(let at): return "Sharing · \(GroupFormat.ago(now - at))"
        case .lastSeen(let at): return "Last seen \(GroupFormat.ago(now - at))"
        case .notSharing: return "Not sharing"
        }
    }

    private func coordinate(_ lat: Int32, _ lon: Int32) -> GroupCoordinate {
        GroupCoordinate(lat: MicroDegrees.degrees(lat), lon: MicroDegrees.degrees(lon))
    }

    /// A pin's record, if it opens and its expiry follows the rule (§4.5).
    private func pinRecord(_ st: GroupState, _ p: GroupStore.StoredPin) -> PinRecord? {
        if let c = st.pinCache[p.pinId], c.data == p.rec { return c.record }
        var record: PinRecord?
        do {
            let plain = try GroupCrypto.open(p.rec, key: st.keys.pin,
                                             aad: GroupAAD.pin(st.id, pinId: p.pinId, memberId: p.memberId, createdAt: p.createdAt, expiresAt: p.expiresAt))
            let r = try PinRecord.decode(plain)
            let expected = r.type == PinType.animal.rawValue ? p.createdAt + GroupPolicy.animalPinLifetime : nil
            if p.expiresAt == expected {
                record = r
            } else {
                GroupLog.note("a pin's expiry breaks the rule; dropped")
            }
        } catch {
            GroupLog.note("a pin doesn't open (\(error)); dropped")
        }
        st.pinCache[p.pinId] = (p.rec, record)
        return record
    }

    private func openLive(_ st: GroupState, memberId: String, at: Int64, rec: Data) -> LivePosition? {
        do {
            let p = try LivePosition.decode(GroupCrypto.open(rec, key: st.keys.live(memberId), aad: GroupAAD.live(st.id, memberId, at: at)))
            guard p.time == at else {
                GroupLog.note("a live record's time differs from its at; dropped")
                return nil
            }
            return p
        } catch {
            GroupLog.note("a live record doesn't open (\(error)); dropped")
            return nil
        }
    }

    /// A chunk's points (§8.9): other members' points before your `joinedAt` are cut.
    private func chunkPoints(_ st: GroupState, _ c: GroupStore.StoredChunk) -> [TrailPoint]? {
        let key = "\(c.memberId)|\(c.start)"
        if let cached = st.chunkCache[key], cached.seq == c.seq, cached.data == c.rec { return cached.points }
        var points: [TrailPoint]?
        do {
            let plain = try GroupCrypto.open(c.rec, key: st.keys.trail(c.memberId), aad: GroupAAD.trail(st.id, c.memberId, start: c.start))
            points = Self.visible(try TrailChunk.decode(plain, windowStart: c.start), memberId: c.memberId, me: st.secret.memberId,
                                  joinedAt: st.row.joinedAt)
        } catch {
            GroupLog.note("a trail chunk doesn't open (\(error)); dropped")
        }
        st.chunkCache[key] = (c.seq, c.rec, points)
        return points
    }

    /// Other members' points earlier than your `joinedAt` are cut (R9); your own stay.
    static func visible(_ points: [TrailPoint], memberId: String, me: String, joinedAt: Int64) -> [TrailPoint] {
        memberId == me ? points : points.filter { $0.time >= joinedAt }
    }

    /// The other members' trails for the map (§9.5): per the Trails setting, split into segments, simplified by the
    /// number of visible trails, at most the newest 4000 points per member.
    private func trails(_ st: GroupState, now: Int64) -> [MapTrail] {
        let mode = TrailsMode(rawValue: st.row.trails) ?? .day
        guard mode != .off else { return [] }
        let since: Int64? = mode == .day ? now - GroupPolicy.dayTrail : nil
        let cacheKey = "\(st.chunkRevision)|\(mode.rawValue)|\(st.blocked.sorted().joined(separator: ","))|\(st.members.map { "\($0.memberId):\($0.seq)" }.joined(separator: ","))|\(since.map { $0 / 60 } ?? 0)"
        if let c = st.trailCache, c.key == cacheKey { return c.trails }
        let me = st.secret.memberId
        let current = Set(st.members.map(\.memberId))
        var byMember: [String: [TrailPoint]] = [:]
        let windowFrom = since.map { TrailChunk.windowStart($0) }
        for c in store.chunks(st.id, since: windowFrom) where c.memberId != me && current.contains(c.memberId) && !st.blocked.contains(c.memberId) {
            guard let points = chunkPoints(st, c) else { continue }
            byMember[c.memberId, default: []].append(contentsOf: since.map { s in points.filter { $0.time >= s } } ?? points)
        }
        var segmentsByMember: [(String, [[TrailPoint]])] = []
        for m in st.members where byMember[m.memberId] != nil {
            let points = byMember[m.memberId]!.sorted { $0.time < $1.time }
            var segments: [[TrailPoint]] = []
            var current: [TrailPoint] = []
            for p in points {
                if let last = current.last, p.startsSegment || p.time - last.time > GroupPolicy.windowSeconds {
                    segments.append(current)
                    current = []
                }
                current.append(p)
            }
            segments.append(current)
            segments = segments.filter { $0.count >= 2 }
            if !segments.isEmpty { segmentsByMember.append((m.memberId, segments)) }
        }
        let visibleCount = segmentsByMember.count
        let tolerance: Double = visibleCount <= 10 ? 3 : (visibleCount <= 30 ? 10 : 25)
        let width: Double = visibleCount > 10 ? 2 : 3
        var out: [MapTrail] = []
        for (memberId, segments) in segmentsByMember {
            var simplified = segments.map { Self.simplify($0, tolerance: tolerance) }
            // At most the newest 4000 points per member.
            var total = simplified.reduce(0) { $0 + $1.count }
            while total > 4000, !simplified.isEmpty {
                let excess = total - 4000
                if simplified[0].count - excess >= 2 {
                    simplified[0].removeFirst(excess)
                    total -= excess
                } else {
                    total -= simplified[0].count
                    simplified.removeFirst()
                }
            }
            let profile = st.members.first { $0.memberId == memberId }.flatMap { memberProfile(st, $0) }
            out.append(MapTrail(memberId: memberId, colourHex: colourHex(profile), widthPt: width,
                                segments: simplified.map { $0.map { GroupCoordinate(lat: $0.lat, lon: $0.lon) } }))
        }
        st.trailCache = (cacheKey, out)
        return out
    }

    /// Douglas–Peucker in Web Mercator units, with the tolerance in metres at the segment's latitude.
    private static func simplify(_ points: [TrailPoint], tolerance: Double) -> [GeoPoint] {
        let geo = points.map { GeoPoint(lat: MicroDegrees.degrees($0.lat), lon: MicroDegrees.degrees($0.lon)) }
        guard geo.count > 2, let first = geo.first else { return geo }
        let unit = tolerance / Geo.metersPerWorldUnit(atLatitude: first.lat)
        let world = geo.map { WebMercator.world($0) }
        let kept = Simplify.douglasPeucker(world, tolerance: unit)
        return kept.map { WebMercator.geo(worldX: $0.x, worldY: $0.y) }
    }

    // MARK: - Internals: text

    private func checked(_ text: String, _ field: GroupTextField) throws -> String {
        guard let cleaned = GroupText.clean(text, field) else { throw GroupActionError(.invalidText(field)) }
        guard words.allows(cleaned) else { throw GroupActionError(.wordFilter(field)) }
        return cleaned
    }

    // MARK: - Internals: failures

    /// The error to throw for a failed request, after running §8.10 when it says the membership ended.
    private func failure(_ error: Error, _ groupId: String) async -> GroupActionError {
        let mapped = actionError(error)
        let gone = await handleFailure(error, groupId)
        return gone ? GroupActionError.gone : mapped
    }

    private func actionError(_ error: Error) -> GroupActionError {
        if let e = error as? GroupActionError { return e }
        if let t = error as? GroupTransportError { return GroupActionError(t == .offline ? .offline : .server) }
        guard let a = error as? GroupAPIError else { return GroupActionError(.server) }
        switch (a.status, a.code) {
        case (429, _): return GroupActionError(.rateLimited)
        case (503, "store_unavailable"): return GroupActionError(.storeUnavailable)
        case (402, _): return GroupActionError(.proNotConfirmed)
        case (409, "too_many_groups"): return GroupActionError(.tooManyGroups)
        case (403, _): return GroupActionError(.forbidden)
        case (404, "invite_not_found"): return GroupActionError(.inviteNotFound)
        case (409, "terms_outdated"): return GroupActionError(.termsOutdated)
        case (409, "group_paused"): return GroupActionError(.groupPaused)
        case (401, _), (404, "group_not_found"), (410, "removed"), (410, "group_deleted"): return GroupActionError.gone
        case (404, _): return GroupActionError(.notFound)
        default: return GroupActionError(.server)
        }
    }

    /// Runs §8.10 for an answer that says the membership may have ended: `410 removed` or `group_deleted` at once; a
    /// `401` or `404 group_not_found` only when E2 says the same (a check is made unless this was E2). Returns whether
    /// the group is gone from the phone.
    @discardableResult
    private func handleFailure(_ error: Error, _ groupId: String, fromStatus: Bool = false) async -> Bool {
        guard let a = error as? GroupAPIError else { return false }
        switch (a.status, a.code) {
        case (410, "removed"):
            wipe(groupId, alert: .removed)
            return true
        case (410, "group_deleted"):
            wipe(groupId, alert: .deleted)
            return true
        case (401, _), (404, "group_not_found"):
            if fromStatus {
                wipe(groupId, alert: .gone)
                return true
            }
            await checkMembership(groupId)
            return states[groupId] == nil
        case (409, "group_paused"):
            if !fromStatus { await refreshStatus(groupId) }
            return false
        default:
            return false
        }
    }

    /// E2 for a group whose membership another endpoint put in doubt.
    @MainActor
    private func checkMembership(_ groupId: String) async {
        await refreshStatus(groupId)
    }

    private func forgetSecretIfDefinite(_ secret: GroupSecret, _ error: Error) {
        if let a = error as? GroupAPIError, (400..<500).contains(a.status), a.status != 429 {
            secrets.delete(groupId: secret.groupId)
            pendingSecrets[secret.groupId] = nil
        } else if states[secret.groupId] == nil {
            // The answer may have been lost: E2 decides at the next start or activation (§8.3).
            pendingSecrets[secret.groupId] = secret
        }
    }

    /// Deletes a group from the phone: rows, secret, socket; with an alert when the server ended the membership.
    private func wipe(_ groupId: String, alert: GroupAlert.Kind?) {
        let name = states[groupId].map { groupName($0) } ?? Self.unnamedGroup
        let wasShown = states[groupId]?.row.shown ?? false
        if socket?.groupId == groupId { closeSocket(code: 1000) }
        store.deleteGroup(groupId)
        secrets.delete(groupId: groupId)
        states[groupId] = nil
        pendingSecrets[groupId] = nil
        emit(.groups)
        if wasShown { emit(.map) }
        if let kind = alert { emit(.alert(GroupAlert(kind: kind, groupId: groupId, groupName: name))) }
    }

    // MARK: - Internals: status, changes, live

    @MainActor
    private func restorePending() async {
        for gid in pendingSecrets.keys.sorted() {
            await restore(gid)
        }
    }

    /// A membership with no local copy: E2, then the changes from 0 (§8.3). Only E2's 401, 404 or 410 deletes it.
    @MainActor
    private func restore(_ groupId: String) async {
        guard let s = pendingSecrets[groupId], let key = Base64URL.decode(s.groupKey, count: 32),
              let gidBytes = Base64URL.decode(s.groupId, count: 16) else { return }
        do {
            let status = try await api.status(groupId, pass: s.pass)
            guard pendingSecrets[groupId] != nil, states[groupId] == nil else { return }
            addGroup(s, keys: GroupKeys(groupKey: key, groupId: gidBytes), status: status)
            notify(groupId)
            await pull(groupId)
        } catch let e as GroupAPIError where e.status == 401 || e.status == 410 || (e.status == 404 && e.code == "group_not_found") {
            secrets.delete(groupId: groupId)
            pendingSecrets[groupId] = nil
        } catch {
            // Offline or a server error: try again later.
        }
    }

    @MainActor
    private func refreshStatus(_ groupId: String) async {
        guard let st = states[groupId] else { return }
        do {
            let status = try await api.status(groupId, pass: st.secret.pass)
            applyStatus(groupId, status)
        } catch {
            await handleFailure(error, groupId, fromStatus: true)
        }
    }

    private func applyStatus(_ groupId: String, _ status: GroupStatusDTO) {
        guard let st = states[groupId] else { return }
        let wasPaused = st.isPaused
        st.status = status
        if let w = status.watchers { st.watchers = w }
        store.updateGroup(groupId) { g in
            g.statusJSON = Self.json(status)
            g.role = status.you.role
            g.joinedAt = status.you.joinedAt
            if let p = status.profile, let seq = status.profileSeq, seq >= g.profileSeq, let data = Base64URL.decode(p) {
                g.profile = data
                g.profileSeq = seq
            }
        }
        st.badTimeHold.removeAll()
        if status.isPaused && !wasPaused {
            // The server deleted every live position; nobody shares while paused.
            store.deleteAllLive(groupId)
            st.lastLiveSentAt = nil
        } else if !status.isPaused && wasPaused {
            st.startSegmentNext = true
        }
        if status.isPaused != wasPaused, let s = socket, s.groupId == groupId, s.isOpen {
            s.socket.send(SocketMessage.watching(isWatching(st)))
        }
        reload(groupId)
        notify(groupId)
    }

    private func applyLive(_ st: GroupState, _ entries: [LiveEntry]) {
        let now = clock()
        var changed = false
        for e in entries {
            guard let rec = e.rec else {
                if st.live[e.memberId] != nil {
                    store.deleteLive(st.id, e.memberId)
                    st.live[e.memberId] = nil
                    changed = true
                }
                continue
            }
            guard now - e.at <= GroupPolicy.liveHiddenAfter, (st.live[e.memberId]?.at ?? .min) < e.at,
                  let env = Base64URL.decode(rec), let p = openLive(st, memberId: e.memberId, at: e.at, rec: env) else { continue }
            if store.putLive(st.id, GroupStore.StoredLive(memberId: e.memberId, at: e.at, rec: env)) {
                st.live[e.memberId] = (e.at, p)
                changed = true
            }
        }
        if changed { notify(st.id) }
    }

    @MainActor
    private func fetchLive(_ groupId: String) async {
        guard let st = states[groupId] else { return }
        do {
            let page = try await api.live(groupId, pass: st.secret.pass)
            guard let st = states[groupId] else { return }
            if let w = page.watchers { st.watchers = w }
            applyLive(st, page.live)
        } catch {
            await handleFailure(error, groupId)
        }
    }

    /// Applies one page (or tick) and reloads what it changed.
    @discardableResult
    private func applyPage(_ groupId: String, _ page: ChangesPage) -> GroupStore.Applied? {
        guard let st = states[groupId] else { return nil }
        let applied: GroupStore.Applied
        do {
            applied = try store.apply(page, groupId: groupId)
        } catch {
            GroupLog.note("couldn't store a page of changes (\(error))")
            return nil
        }
        if applied.changed {
            st.chunkRevision += 1
            reload(groupId)
            notify(groupId)
        } else {
            st.row = store.group(groupId) ?? st.row
        }
        if applied.removedMe {
            background { await $0.checkMembership(groupId) }
        }
        return applied
    }

    /// Pulls E19 from the cursor until `more` is false (§8.8), then fetches queued archive months.
    @MainActor
    private func pull(_ groupId: String) async {
        guard let st = states[groupId] else { return }
        if st.pulling {
            st.pullAgain = true
            return
        }
        st.pulling = true
        defer { states[groupId]?.pulling = false }
        var pages = 0
        repeat {
            st.pullAgain = false
            var more = true
            while more, pages < 1000 {
                guard let st = states[groupId] else { return }
                do {
                    let page = try await api.changes(groupId, pass: st.secret.pass, since: st.row.cursor)
                    guard states[groupId] === st else { return }
                    pages += 1
                    guard let applied = applyPage(groupId, page) else { return }
                    more = applied.reset || page.more
                } catch {
                    await handleFailure(error, groupId)
                    return
                }
            }
        } while states[groupId]?.pullAgain == true
        await fetchArchives(groupId)
        guard let st = states[groupId] else { return }
        if let s = socket, s.groupId == groupId, s.welcomed {
            s.socket.send(SocketMessage.ack(cursor: st.row.cursor))
        }
        if st.needsColourCheck {
            st.needsColourCheck = false
            fixColourClash(st)
        }
    }

    @MainActor
    private func fetchArchives(_ groupId: String) async {
        for op in store.ops(groupId) where op.kind == "archive" {
            guard let st = states[groupId], let fetch = try? JSONDecoder().decode(GroupStore.ArchiveFetch.self, from: Data(op.body.utf8)) else {
                store.deleteOp(op.id)
                continue
            }
            do {
                let data = try await api.archive(groupId, pass: st.secret.pass, memberId: fetch.memberId, month: fetch.month)
                guard let st = states[groupId] else { return }
                if let records = GroupArchive.parse(data),
                   fetch.memberId == st.secret.memberId || st.members.contains(where: { $0.memberId == fetch.memberId }) {
                    try? store.storeArchive(groupId, memberId: fetch.memberId, records: records, seq: fetch.seq)
                    st.chunkRevision += 1
                    notify(groupId)
                } else {
                    GroupLog.note("an archive month didn't parse or its member is gone; skipped")
                }
                store.deleteOp(op.id)
            } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
                store.deleteOp(op.id)
            } catch let e as GroupAPIError where (400..<500).contains(e.status) && e.status != 429 && e.status != 401 && e.status != 410 && e.code != "group_not_found" {
                store.deleteOp(op.id)
            } catch {
                // Retried at the next pull; the cursor has moved on.
                await handleFailure(error, groupId)
                return
            }
        }
    }

    /// A joiner whose random colour another member already has takes the lowest free one, if any (§4.6).
    private func fixColourClash(_ st: GroupState) {
        let me = st.secret.memberId
        guard let mine = st.members.first(where: { $0.memberId == me }), let profile = memberProfile(st, mine) else { return }
        let others = st.members.filter { $0.memberId != me }.compactMap { memberProfile(st, $0)?.colour }
        guard others.contains(profile.colour), let free = GroupPolicy.palette.indices.first(where: { !others.contains($0) }) else { return }
        guard let env = try? GroupCrypto.seal(MemberProfile(name: profile.name, colour: free).json(), key: st.keys.member,
                                              aad: GroupAAD.member(st.id, me)) else { return }
        store.addOp(st.id, kind: "profile", body: Base64URL.encode(env), createdAt: clock())
        background { hub in await hub.flush(st.id, now: hub.clock(), force: true) }
    }

    // MARK: - Internals: the outbox

    /// Sends the queued requests, then the trail chunks and live position that are due.
    @MainActor
    private func flush(_ groupId: String, now: Int64, force: Bool) async {
        guard let st = states[groupId], !st.flushing else { return }
        guard force || now >= st.retryAt else { return }
        st.flushing = true
        defer { states[groupId]?.flushing = false }
        guard await sendOps(groupId, now: now) else { return }
        await upload(groupId, now: now)
    }

    private func noteRetry(_ st: GroupState, now: Int64, error: Error) {
        if let a = error as? GroupAPIError, a.status == 429 {
            st.retryAt = now + 60
            return
        }
        st.retryAt = now + GroupPolicy.retryBackoff[min(st.retryStep, GroupPolicy.retryBackoff.count - 1)]
        st.retryStep += 1
    }

    private func isRetryable(_ error: Error) -> Bool {
        if error is GroupTransportError { return true }
        guard let a = error as? GroupAPIError else { return true }
        return a.status == 429 || a.status >= 500
    }

    /// Sends the queued ops in order. Returns false when it had to stop (offline, a server error, or the group gone).
    @MainActor
    private func sendOps(_ groupId: String, now: Int64) async -> Bool {
        for op in store.ops(groupId) where op.kind != "archive" {
            guard let st = states[groupId] else { return false }
            if op.kind == "pin" && st.isPaused { continue } // waits for the resume; deletes and the rest still go
            do {
                switch op.kind {
                case "pin":
                    try await sendPin(st, op)
                case "unpin":
                    do {
                        try await api.deletePin(groupId, pass: st.secret.pass, pinId: op.body)
                    } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
                        // Already deleted.
                    }
                    store.deleteOp(op.id)
                case "profile":
                    let seq = try await api.updateMyProfile(groupId, pass: st.secret.pass, profile: op.body).seq
                    store.deleteOp(op.id)
                    if var m = states[groupId]?.members.first(where: { $0.memberId == st.secret.memberId }), let env = Base64URL.decode(op.body) {
                        m.profile = env
                        m.seq = seq
                        store.putMember(groupId, m)
                        reload(groupId)
                        notify(groupId)
                    }
                case "cleartrail":
                    do {
                        try await api.clearTrail(groupId, pass: st.secret.pass)
                    } catch let e as GroupAPIError where e.status == 404 && e.code == "not_found" {
                        // Nothing to clear.
                    }
                    store.deleteOp(op.id)
                default:
                    store.deleteOp(op.id)
                }
                st.retryStep = 0
            } catch {
                if isRetryable(error) {
                    noteRetry(st, now: now, error: error)
                    return false
                }
                if await handleFailure(error, groupId) { return false }
                if let a = error as? GroupAPIError, a.status == 409, a.code == "group_paused" {
                    continue
                }
                // A definite refusal: the op can never succeed.
                GroupLog.note("a queued \(op.kind) was refused (\((error as? GroupAPIError)?.code ?? "?")); dropped")
                store.deleteOp(op.id)
                if op.kind == "pin", let pin = PinOp(json: op.body) {
                    store.deletePin(groupId, pin.pinId)
                    reload(groupId)
                    notify(groupId)
                }
            }
        }
        return true
    }

    /// E23 for a queued pin. `pin_exists` gives it a new id once; `pin_deleted`, `pin_expired` and other refusals drop it.
    @MainActor
    private func sendPin(_ st: GroupState, _ op: GroupStore.QueuedOp) async throws {
        guard var pin = PinOp(json: op.body), let plain = Base64URL.decode(pin.plain) else {
            store.deleteOp(op.id)
            return
        }
        let gid = st.id
        let me = st.secret.memberId
        while true {
            let env = try GroupCrypto.seal(plain, key: st.keys.pin,
                                           aad: GroupAAD.pin(gid, pinId: pin.pinId, memberId: me, createdAt: pin.createdAt, expiresAt: pin.expiresAt))
            do {
                let seq = try await api.dropPin(gid, pass: st.secret.pass,
                                                PinRequest(pinId: pin.pinId, createdAt: pin.createdAt, expiresAt: pin.expiresAt, rec: Base64URL.encode(env))).seq
                store.deleteOp(op.id)
                if let stored = store.pins(gid).first(where: { $0.pinId == pin.pinId }), stored.seq < seq {
                    var updated = stored
                    updated.seq = seq
                    store.putPin(gid, updated)
                }
                reload(gid)
                notify(gid)
                return
            } catch let e as GroupAPIError where e.status == 409 && e.code == "pin_exists" && !pin.retried {
                let oldId = pin.pinId
                pin.pinId = GroupRandom.id()
                pin.retried = true
                let newEnv = try GroupCrypto.seal(plain, key: st.keys.pin,
                                                  aad: GroupAAD.pin(gid, pinId: pin.pinId, memberId: me, createdAt: pin.createdAt, expiresAt: pin.expiresAt))
                try? store.transaction {
                    store.deletePin(gid, oldId)
                    store.putPin(gid, GroupStore.StoredPin(pinId: pin.pinId, memberId: me, createdAt: pin.createdAt, expiresAt: pin.expiresAt, rec: newEnv, seq: 0))
                    store.updateOp(op.id, body: pin.json)
                }
                reload(gid)
            }
        }
    }

    /// Uploads the due trail chunks and live position (§4.3, §8.6), over the socket when it is open for the shown
    /// group in the foreground, otherwise over HTTP; packed by 50 chunks or 192 KiB.
    @MainActor
    private func upload(_ groupId: String, now: Int64) async {
        guard let st = states[groupId] else { return }
        let paused = st.isPaused
        guard !paused || st.stopLivePending else { return }
        let sharing = isEffectivelySharing(st)
        let clearing = store.hasOp(groupId, kind: "cleartrail")
        if !paused, let pauses = st.status?.pauses, !pauses.isEmpty {
            store.dropOwnPoints(groupId, inside: pauses)
        }
        let windows = paused || clearing ? [] : store.ownWindows(groupId).filter { $0.version > $0.sentVersion && !st.badTimeHold.contains($0.start) }
        let viaSocket = isSocketReady(groupId)
        let chunkDue = !windows.isEmpty && (st.lastChunkUpload.map { now - $0 >= GroupPolicy.uploadInterval } ?? true)
        var liveDue = false
        if sharing {
            let since = st.lastLiveSlot.map { now - $0 } ?? .max
            if viaSocket && st.watchers > 0 {
                liveDue = (since >= GroupPolicy.liveWatchedForeground && movedEnough(st)) || since >= GroupPolicy.liveHeartbeat
            } else if viaSocket {
                liveDue = since >= GroupPolicy.uploadInterval
            } else if st.watchers > 0 {
                liveDue = since >= GroupPolicy.liveWatchedBackground
            } else {
                liveDue = since >= GroupPolicy.uploadInterval
            }
        }
        let stopDue = st.stopLivePending && !sharing
        guard chunkDue || liveDue || stopDue else { return }

        var liveField = UploadRequest.LiveField.unchanged
        var liveSent: (at: Int64, position: GeoPoint)?
        if stopDue {
            liveField = .stopped
        } else if liveDue, let made = liveRecord(st) {
            liveField = .position(made.live)
            liveSent = (made.live.at, made.position)
        }
        if liveDue { st.lastLiveSlot = now }
        if chunkDue { st.lastChunkUpload = now }

        var queue = chunkDue ? windows : []
        var first = true
        while first || !queue.isEmpty {
            first = false
            let (chunks, sent) = pack(st, &queue, live: liveField, socket: viaSocket)
            let body = UploadRequest(chunks: chunks, live: liveField)
            if viaSocket, var s = socket, s.groupId == groupId {
                let id = s.nextId
                s.nextId += 1
                s.pending[id] = sent
                s.socket.send(String(decoding: body.json(socketId: id), as: UTF8.self))
                socket = s
                if stopDue { st.stopLivePending = false }
            } else {
                do {
                    let result = try await api.upload(groupId, pass: st.secret.pass, body)
                    guard states[groupId] === st else { return }
                    st.retryStep = 0
                    if stopDue { st.stopLivePending = false }
                    applyUploadResult(st, result, sent: sent, now: now)
                } catch {
                    if let a = error as? GroupAPIError, a.status == 413, let firstWindow = sent.windows.first {
                        GroupLog.note("an upload was too large; its oldest chunk is dropped")
                        store.deleteOwnWindow(groupId, start: firstWindow.start)
                    } else if isRetryable(error) {
                        noteRetry(st, now: now, error: error)
                    } else {
                        await handleFailure(error, groupId)
                    }
                    // Not sent: the live slot and chunk time go back so it is tried again.
                    if liveDue { st.lastLiveSlot = nil }
                    if chunkDue { st.lastChunkUpload = nil }
                    return
                }
            }
            if let l = liveSent {
                st.lastLiveSentAt = l.at
                st.lastLivePosition = l.position
                liveSent = nil
            }
            liveField = .unchanged
        }
    }

    /// Takes chunks oldest first until 50 or the JSON body would pass 192 KiB (§4.3); each a fresh encryption.
    private func pack(_ st: GroupState, _ queue: inout [GroupStore.OwnWindow], live: UploadRequest.LiveField, socket: Bool)
        -> ([UploadRequest.Chunk], SentUpload) {
        var chunks: [UploadRequest.Chunk] = []
        var sent = SentUpload(windows: [])
        var size = UploadRequest(chunks: [], live: live).json(socketId: socket ? 1_000_000 : nil).count
        let me = st.secret.memberId
        while let w = queue.first, chunks.count < GroupPolicy.maxChunksPerUpload {
            guard let points = try? TrailChunk.decode(w.points, windowStart: w.start),
                  let plain = try? TrailChunk.encode(points, windowStart: w.start, compress: true),
                  let env = try? GroupCrypto.seal(plain, key: st.keys.trail(me), aad: GroupAAD.trail(st.id, me, start: w.start)) else {
                queue.removeFirst()
                store.deleteOwnWindow(st.id, start: w.start)
                continue
            }
            let chunk = UploadRequest.Chunk(start: w.start, rec: Base64URL.encode(env))
            let added = "{\"start\":\(chunk.start),\"rec\":\"\(chunk.rec)\"}".utf8.count + (chunks.isEmpty ? 0 : 1)
            if !chunks.isEmpty && size + added > GroupPolicy.maxUploadBytes { break }
            size += added
            chunks.append(chunk)
            sent.windows.append((w.start, w.version))
            queue.removeFirst()
        }
        return (chunks, sent)
    }

    /// The live record to send: the newest fix, if location is authorised and precise, its accuracy known and at most
    /// 100 m, and it is newer than the last one sent (§8.5, §8.6).
    private func liveRecord(_ st: GroupState) -> (live: UploadRequest.Live, position: GeoPoint)? {
        guard locationAuthorised, locationPrecise, let f = latestFix, let acc = f.accuracy, acc >= 0, acc <= GroupPolicy.liveMaxAccuracy else { return nil }
        let p = LiveFix.position(lat: f.lat, lon: f.lon, accuracy: f.accuracy, course: f.course, speed: f.speed, heading: f.heading, time: f.time)
        if let last = st.lastLiveSentAt, p.time <= last { return nil }
        let me = st.secret.memberId
        guard let env = try? GroupCrypto.seal(p.encoded(), key: st.keys.live(me), aad: GroupAAD.live(st.id, me, at: p.time)) else { return nil }
        return (UploadRequest.Live(at: p.time, rec: Base64URL.encode(env)), GeoPoint(lat: f.lat, lon: f.lon))
    }

    private func movedEnough(_ st: GroupState) -> Bool {
        guard let f = latestFix else { return false }
        guard let last = st.lastLivePosition else { return true }
        return Geo.distance(last, GeoPoint(lat: f.lat, lon: f.lon)) >= GroupPolicy.liveMinMove
    }

    /// Handles an upload's answer per window (§4.3).
    private func applyUploadResult(_ st: GroupState, _ result: UploadResult, sent: SentUpload, now: Int64) {
        let gid = st.id
        let versions = Dictionary(sent.windows.map { ($0.start, $0.version) }, uniquingKeysWith: { a, _ in a })
        for start in result.accepted {
            guard let version = versions[start] else { continue }
            store.markOwnWindowSent(gid, start: start, version: version, closed: start + GroupPolicy.windowSeconds <= now)
        }
        for r in result.rejected {
            switch r.error {
            case "bad_time":
                let tries = store.ownWindows(gid).first { $0.start == r.start }?.badTimeTries ?? 0
                if tries >= 1 {
                    store.deleteOwnWindow(gid, start: r.start)
                } else {
                    store.setBadTimeTries(gid, start: r.start, tries: tries + 1)
                    st.badTimeHold.insert(r.start)
                }
            default:
                // before_join, window_paused, bad_record: it can never be accepted.
                store.deleteOwnWindow(gid, start: r.start)
            }
        }
        if let w = result.watchers { st.watchers = w }
        if let status = result.status, (status == "paused") != st.isPaused {
            background { await $0.refreshStatus(gid) }
        }
    }

    // MARK: - Internals: the socket

    private func isSocketReady(_ groupId: String) -> Bool {
        guard let s = socket else { return false }
        return s.groupId == groupId && s.isOpen && s.welcomed && foreground
    }

    /// Opens the socket for the shown group while in the foreground (§8.7), closing one for another group.
    private func connectSocketIfWanted() {
        guard let factory = sockets else { return }
        let wanted = foreground ? shownGroupId : nil
        if let s = socket, s.groupId != wanted {
            closeSocket(code: 1000)
        }
        guard let gid = wanted, socket == nil, let st = states[gid] else { return }
        if let at = reconnectAt, clock() < at { return }
        reconnectAt = nil
        socket = SocketState(groupId: gid, socket: factory.open(groupId: gid, pass: st.secret.pass))
    }

    private func closeSocket(code: Int) {
        guard let s = socket else { return }
        socket = nil
        reconnectAttempt = 0
        reconnectAt = nil
        s.socket.close(code: code)
    }

    private func shouldSendPro(status: GroupStatusDTO, row: GroupStore.StoredGroup, pro: ProProof, productId: String, expiresAt: Int64?) -> Bool {
        guard let current = status.pro else { return true }
        if let reason = status.pausedReason, Self.proReasons.contains(reason) { return true }
        if current.productId != productId { return true }
        if let e = expiresAt, let known = current.expiresAt, e > known + 60 { return true }
        if case .google(_, let token) = pro, token != row.proSent { return true }
        return false
    }
}
