import Foundation
import XCTest
@testable import SatMapCore

/// A clock the tests move by hand.
final class TestClock {
    var now: Int64

    init(_ now: Int64) {
        self.now = now
    }

    func advance(_ seconds: Int64) {
        now += seconds
    }
}

/// A WebSocket the tests drive: it records what the hub sends.
final class FakeSocket: GroupSocket {
    let groupId: String
    var sent: [String] = []
    var closedWith: Int?

    init(groupId: String) {
        self.groupId = groupId
    }

    func send(_ text: String) {
        sent.append(text)
    }

    func close(code: Int) {
        closedWith = code
    }

    /// The JSON objects sent (not "ping").
    var messages: [[String: Any]] {
        sent.compactMap { (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [String: Any] }
    }
}

final class FakeSockets: GroupSocketFactory {
    var opened: [FakeSocket] = []

    func open(groupId: String, pass: String) -> GroupSocket {
        let s = FakeSocket(groupId: groupId)
        opened.append(s)
        return s
    }
}

/// The fake server behind a flaky network: a request that matches a rule is delayed on its way (`holdRequest`), or
/// reaches the server and then has its answer held back (`holdAnswer`) or lost (`loseAnswer`, as
/// `GroupTransportError.failed`). Each rule applies once. Held requests wait for `release()`.
final class FlakyTransport: GroupTransport {
    enum Fate {
        case holdRequest, holdAnswer, loseAnswer
    }

    let server: FakeGroupServer
    private let lock = NSLock()
    private var rules: [(method: String, pathContains: String, fate: Fate)] = []
    private var held: [CheckedContinuation<Void, Never>] = []

    init(server: FakeGroupServer) {
        self.server = server
    }

    func next(_ method: String, _ pathContains: String, _ fate: Fate) {
        lock.lock()
        rules.append((method, pathContains, fate))
        lock.unlock()
    }

    var heldCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return held.count
    }

    func release() {
        lock.lock()
        let waiting = held
        held = []
        lock.unlock()
        for c in waiting { c.resume() }
    }

    /// The fate of a request, taking its rule.
    private func fate(_ method: String, _ path: String) -> Fate? {
        lock.lock()
        defer { lock.unlock() }
        guard let index = rules.firstIndex(where: { $0.method == method && path.contains($0.pathContains) }) else { return nil }
        return rules.remove(at: index).fate
    }

    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data) {
        let fate = fate(method, path)
        if fate == .holdRequest { await hold() }
        let answer = try server.handle(method: method, path: path, pass: pass, json: json)
        switch fate {
        case .holdAnswer?: await hold()
        case .loseAnswer?: throw GroupTransportError.failed
        default: break
        }
        return answer
    }

    private func hold() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            lock.lock()
            held.append(c)
            lock.unlock()
        }
    }
}

/// One phone: a hub with its own store and secrets, on the shared fake server.
final class Phone {
    let store: GroupStore
    let secrets: MemoryGroupSecrets
    let hub: GroupHub
    var events: [GroupHubEvent] = []

    init(server: FakeGroupServer, clock: TestClock, sockets: FakeSockets? = nil, words: String = "badword\n",
         transport: GroupTransport? = nil) throws {
        store = try GroupStore(path: ":memory:")
        secrets = MemoryGroupSecrets()
        hub = GroupHub(store: store, secrets: secrets, transport: transport ?? server, sockets: sockets, words: WordFilter(list: words),
                       domain: "example.invalid", clock: { clock.now })
        hub.onChange = { [weak self] in self?.events.append($0) }
        hub.setLocationAccess(authorised: true, precise: true)
    }

    var alerts: [GroupAlert] {
        events.compactMap { if case .alert(let a) = $0 { return a } else { return nil } }
    }
}

/// The groups sync engine against the fake server (docs/groups/SPEC.md §8, §12.2), as the Android core's
/// GroupHubTests.
final class GroupHubTests: XCTestCase {
    let t0: Int64 = 1_793_613_000
    var clock: TestClock!
    var server: FakeGroupServer!
    let pro = ProProof.apple(signedTransaction: "fake:pro.yearly:1825149600")

    override func setUp() {
        clock = TestClock(t0)
        let c = clock!
        server = FakeGroupServer(now: { c.now })
    }

    // MARK: Helpers

    func phone(sockets: FakeSockets? = nil, transport: GroupTransport? = nil) throws -> Phone {
        try Phone(server: server, clock: clock, sockets: sockets, transport: transport)
    }

    /// An owner with a group, and a second phone that joined it through an invite.
    func ownerAndMember(name: String = "Kruger trip", memberTransport: GroupTransport? = nil) async throws
        -> (owner: Phone, member: Phone, groupId: String) {
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: name, displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else {
            XCTFail("not a link: \(made.link)")
            throw XCTSkip("no link")
        }
        let member = try phone(transport: memberTransport)
        clock.advance(10)
        _ = try await member.hub.join(link, displayName: "Thandi")
        await member.hub.waitForBackgroundWork()
        await owner.hub.becameActive()
        return (owner, member, gid)
    }

    func requests(_ method: String, _ pathPart: String) -> [FakeGroupServer.LoggedRequest] {
        server.log.filter { $0.method == method && $0.path.contains(pathPart) }
    }

    func uploads() -> [[String: Any]] {
        requests("POST", "/upload").compactMap { r in r.body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } }
    }

    /// Starts sharing on a phone: consent, sharing on, location granted.
    func share(_ p: Phone, _ gid: String) {
        p.hub.recordConsent(gid)
        p.hub.setSharing(true, groupId: gid)
        p.hub.setLocationAccess(authorised: true, precise: true)
    }

    func fix(_ p: Phone, lat: Double = -24.99619, lon: Double = 31.592512, accuracy: Double? = 5, at time: Int64? = nil) {
        p.hub.fix(lat: lat, lon: lon, accuracy: accuracy, course: 87, speed: 3, heading: nil, time: Double(time ?? clock.now) + 0.4)
    }

    func point(_ p: Phone, lat: Double = -24.99619, lon: Double = 31.592512, at time: Int64? = nil, segment: Bool = false) {
        p.hub.trackPoint(TrackPoint(lat: lat, lon: lon, time: Double(time ?? clock.now) + 0.7, startsSegment: segment))
    }

    /// Waits (up to 5 s) until the flaky network holds `count` requests.
    func waitUntilHeld(_ net: FlakyTransport, _ count: Int = 1, file: StaticString = #filePath, line: UInt = #line) async throws {
        var waited = 0
        while net.heldCount < count && waited < 500 {
            try await Task.sleep(nanoseconds: 10_000_000)
            waited += 1
        }
        XCTAssertEqual(net.heldCount, count, "requests held", file: file, line: line)
    }

    func bodies<T: Decodable>(_ method: String, _ pathPart: String, as type: T.Type) -> [T] {
        requests(method, pathPart).compactMap { $0.body.flatMap { try? JSONDecoder().decode(T.self, from: $0) } }
    }

    /// A member profile envelope, sealed as the member's own phone seals it.
    func sealedProfile(_ secret: GroupSecret, name: String, colour: Int) throws -> String {
        let keys = GroupKeys(groupKey: try XCTUnwrap(Base64URL.decode(secret.groupKey, count: 32)),
                             groupId: try XCTUnwrap(Base64URL.decode(secret.groupId, count: 16)))
        return Base64URL.encode(try GroupCrypto.seal(MemberProfile(name: name, colour: colour).json(), key: keys.member,
                                                     aad: GroupAAD.member(secret.groupId, secret.memberId)))
    }

    /// The profile the server holds for the member, opened.
    func serverProfile(_ secret: GroupSecret) throws -> MemberProfile {
        let keys = GroupKeys(groupKey: try XCTUnwrap(Base64URL.decode(secret.groupKey, count: 32)),
                             groupId: try XCTUnwrap(Base64URL.decode(secret.groupId, count: 16)))
        let env = try XCTUnwrap(server.memberProfile(groupId: secret.groupId, memberId: secret.memberId).flatMap { Base64URL.decode($0) })
        return try MemberProfile.decode(GroupCrypto.open(env, key: keys.member, aad: GroupAAD.member(secret.groupId, secret.memberId)))
    }

    // MARK: - Create, invite, join, see each other

    func testCreateInviteJoinAndBothSeeEachOther() async throws {
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: " Kruger trip ", displayName: "Sam", pro: pro)
        XCTAssertEqual(owner.hub.groups.map(\.name), ["Kruger trip"])
        XCTAssertEqual(owner.secrets.loadAll().map(\.groupId), [gid])
        var detail = try XCTUnwrap(owner.hub.detail(gid))
        XCTAssertEqual(detail.yourRole, .owner)
        XCTAssertEqual(detail.members.map(\.name), ["Sam"])
        XCTAssertEqual(detail.members.first?.colourHex, GroupPolicy.palette[0])
        XCTAssertTrue(detail.members.first?.isYou ?? false)
        XCTAssertTrue(detail.canInvite && detail.canRename && detail.canHandOver && detail.canDeleteGroup)
        XCTAssertFalse(detail.canLeave)
        XCTAssertFalse(detail.canReportGroupName)
        XCTAssertEqual(detail.supportCode, "\(gid).\(detail.yourMemberId)")
        XCTAssertTrue(owner.hub.needsConsent(gid))
        // The server got only ciphertext: the name appears in no request.
        for r in server.log {
            XCTAssertFalse(String(decoding: r.body ?? Data(), as: UTF8.self).contains("Kruger trip"), r.path)
        }

        let made = try await owner.hub.createInvite(groupId: gid)
        XCTAssertEqual(made.groupName, "Kruger trip")
        XCTAssertEqual(made.expiresAt, clock.now + 604_800)
        XCTAssertTrue(made.link.hasPrefix("https://example.invalid/j/"))
        detail = try XCTUnwrap(owner.hub.detail(gid))
        XCTAssertEqual(detail.invites?.map(\.link), [made.link])
        XCTAssertEqual(detail.invites?.first?.createdByName, "Sam")
        guard case .invite(let link) = InviteLink.parse("Join \"Kruger trip\" on SatMap: \(made.link)", domain: "example.invalid") else {
            return XCTFail("link doesn't parse")
        }
        XCTAssertEqual(link.groupIdText, gid)

        let member = try phone()
        clock.advance(30)
        let preview = try await member.hub.preview(link)
        XCTAssertEqual(preview, InvitePreview(groupId: gid, name: "Kruger trip", inviterName: "Sam", memberCount: 1, isPaused: false,
                                              expiresAt: made.expiresAt))
        let joined = try await member.hub.join(link, displayName: "Thandi")
        XCTAssertEqual(joined, gid)
        XCTAssertEqual(member.hub.shownGroupId, gid)
        let mine = try XCTUnwrap(member.hub.detail(gid))
        XCTAssertEqual(mine.yourRole, .member)
        XCTAssertEqual(mine.members.map(\.name), ["Sam", "Thandi"])
        XCTAssertFalse(mine.canInvite)
        XCTAssertNil(mine.invites)
        XCTAssertTrue(mine.canLeave && mine.canReportGroupName)

        await owner.hub.becameActive()
        XCTAssertEqual(owner.hub.detail(gid)?.members.map(\.name), ["Sam", "Thandi"])
        XCTAssertEqual(owner.hub.groups.first?.memberCount, 2)
        // The invite was used: E7 no longer lists it, and the phone forgets its link.
        let pending = try await owner.hub.invites(groupId: gid)
        XCTAssertTrue(pending.isEmpty)
        XCTAssertTrue(owner.store.madeInvites(gid).isEmpty)
        // Opening the link again on the joined phone says so.
        do {
            _ = try await member.hub.preview(link)
            XCTFail("preview of an own group")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .alreadyMember(groupId: gid))
            XCTAssertEqual(e.message, "You're already in Kruger trip.")
        }
        // A used invite no longer works for anyone else.
        let third = try phone()
        do {
            _ = try await third.hub.join(link, displayName: "Pieter")
            XCTFail("joined with a used invite")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .inviteNotFound)
            XCTAssertEqual(e.message, "This invite has already been used, was cancelled or has expired. Ask for a new one.")
        }
        XCTAssertTrue(third.secrets.loadAll().isEmpty)
    }

    func testTextIsCheckedBeforeAnythingIsSent() async throws {
        let p = try phone()
        do {
            _ = try await p.hub.createGroup(name: "badword trip", displayName: "Sam", pro: pro)
            XCTFail("filtered word accepted")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .wordFilter(.groupName))
            XCTAssertEqual(e.message, "Please use different words for the group name.")
        }
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: String(repeating: "x", count: 31), pro: pro)
            XCTFail("long name accepted")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .invalidText(.displayName))
        }
        XCTAssertTrue(server.log.isEmpty)
        XCTAssertTrue(p.secrets.loadAll().isEmpty)
    }

    func testCreateErrors() async throws {
        let p = try phone()
        server.proAccepted = false
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created without Pro")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .proNotConfirmed)
            XCTAssertEqual(e.message, "SatMap Pro couldn't be confirmed. Tap Restore purchases, then try again.")
        }
        // A definite refusal forgets the membership saved before the request.
        XCTAssertTrue(p.secrets.loadAll().isEmpty)
        server.proAccepted = true
        server.fail("POST", "/v1/groups", status: 503, code: "store_unavailable")
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .storeUnavailable)
        }
        server.fail("POST", "/v1/groups", status: 429, code: "rate_limited")
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .rateLimited)
        }
        // A group id clash makes a new id and tries once more.
        server.fail("POST", "/v1/groups", status: 409, code: "group_exists")
        let gid = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        XCTAssertEqual(requests("POST", "/v1/groups").count, 5)
        XCTAssertEqual(p.secrets.loadAll().map(\.groupId).filter { $0 == gid }.count, 1)
        server.maxGroupsPerPurchase = 1
        do {
            _ = try await p.hub.createGroup(name: "Trip 2", displayName: "Sam", pro: pro)
            XCTFail("created")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .tooManyGroups)
            XCTAssertEqual(e.message, "You already own 20 groups. Delete one to make a new group.")
        }
    }

    func testLostCreateAnswerIsRestoredByStatus() async throws {
        let p = try phone()
        server.offline = true
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created offline")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .offline)
            XCTAssertEqual(e.message, "No connection. Try again when you're online.")
        }
        // Kept: the server may have said yes. It didn't, so E2 answers 404 and the secret goes.
        XCTAssertEqual(p.secrets.loadAll().count, 1)
        server.offline = false
        await p.hub.becameActive()
        XCTAssertTrue(p.secrets.loadAll().isEmpty)
        XCTAssertTrue(p.hub.groups.isEmpty)
    }

    func testRetriedCreateAfterALostAnswerMakesOneGroup() async throws {
        let net = FlakyTransport(server: server)
        let p = try phone(transport: net)
        func creates() -> [CreateGroupRequest] {
            server.log.filter { $0.method == "POST" && $0.path == "/v1/groups" }
                .compactMap { $0.body.flatMap { try? JSONDecoder().decode(CreateGroupRequest.self, from: $0) } }
        }
        // E1 went through, but its answer was lost: the person taps Create again.
        net.next("POST", "/v1/groups", .loseAnswer)
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created without an answer")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .server)
        }
        let first = try XCTUnwrap(creates().first)
        XCTAssertEqual(server.status(groupId: first.groupId), "active")
        let gid = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        // The same group id and pass again: the server answers with the group it made (§6.7).
        XCTAssertEqual(gid, first.groupId)
        XCTAssertEqual(creates().map(\.groupId), [gid, gid])
        XCTAssertEqual(Set(creates().map(\.pass)).count, 1)
        XCTAssertEqual(creates().map(\.memberId), [first.memberId, first.memberId])
        XCTAssertEqual(p.hub.groups.map(\.groupId), [gid])
        XCTAssertEqual(p.hub.detail(gid)?.yourRole, .owner)
        await p.hub.becameActive()
        XCTAssertEqual(p.hub.groups.map(\.groupId), [gid])
        XCTAssertEqual(p.secrets.loadAll().map(\.groupId), [gid])

        // An E1 that never reached the server: the retry makes the group with the names typed now.
        clock.advance(10)
        server.offline = true
        do {
            _ = try await p.hub.createGroup(name: "Trip 2", displayName: "Sam", pro: pro)
            XCTFail("created offline")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .offline)
        }
        let offlineTry = try XCTUnwrap(creates().last)
        XCTAssertNil(server.status(groupId: offlineTry.groupId))
        server.offline = false
        let second = try await p.hub.createGroup(name: "Second trip", displayName: "Sammy", pro: pro)
        XCTAssertEqual(second, offlineTry.groupId)
        XCTAssertEqual(p.hub.groups.map(\.name), ["Trip", "Second trip"])
        XCTAssertEqual(p.hub.detail(second)?.members.map(\.name), ["Sammy"])

        // Once E2 found the group, a new create is a new group.
        clock.advance(10)
        net.next("POST", "/v1/groups", .loseAnswer)
        _ = try? await p.hub.createGroup(name: "Third", displayName: "Sam", pro: pro)
        let lost = try XCTUnwrap(creates().last)
        await p.hub.becameActive()
        XCTAssertEqual(p.hub.groups.map(\.groupId), [gid, second, lost.groupId])
        clock.advance(10)
        let fourth = try await p.hub.createGroup(name: "Fourth", displayName: "Sam", pro: pro)
        XCTAssertNotEqual(fourth, lost.groupId)
        XCTAssertEqual(p.hub.groups.map(\.name), ["Trip", "Second trip", "Third", "Fourth"])
    }

    func testReinstallRestoresMembershipsFromSecrets() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        _ = owner
        // A new install: same secrets, empty store.
        let fresh = try Phone(server: server, clock: clock)
        for s in member.secrets.loadAll() { try fresh.secrets.save(s) }
        await fresh.hub.start()
        XCTAssertEqual(fresh.hub.groups.map(\.name), ["Kruger trip"])
        XCTAssertEqual(fresh.hub.detail(gid)?.members.map(\.name), ["Sam", "Thandi"])
        XCTAssertEqual(fresh.hub.detail(gid)?.yourRole, .member)
        // A restarted phone keeps its store.
        let again = GroupHub(store: member.store, secrets: member.secrets, transport: server, sockets: nil, words: WordFilter(list: ""),
                             domain: "example.invalid", clock: { [clock] in clock!.now })
        await again.start()
        XCTAssertEqual(again.groups.map(\.groupId), [gid])
        // A store row without a secret is useless and goes.
        let orphan = try Phone(server: server, clock: clock)
        orphan.store.putGroup(GroupStore.StoredGroup(groupId: gid, memberId: "x", role: "member", joinedAt: 1, statusJSON: nil))
        await orphan.hub.start()
        XCTAssertTrue(orphan.hub.groups.isEmpty)
        XCTAssertNil(orphan.store.group(gid))
    }

    func testJoinErrorsAndRetries() async throws {
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        let net = FlakyTransport(server: server)
        let member = try phone(transport: net)
        // A damaged key: the server refuses the proof.
        var bad = Data(link.groupKey)
        bad[0] ^= 1
        let damaged = try XCTUnwrap(InviteLink(token: link.token, groupKey: bad))
        do {
            _ = try await member.hub.preview(damaged)
            XCTFail("previewed with a wrong key")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .inviteNotFound)
        }
        // Offline: the membership saved before the request stays.
        server.offline = true
        do {
            _ = try await member.hub.join(link, displayName: "Thandi")
            XCTFail("joined offline")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .offline)
        }
        XCTAssertEqual(member.secrets.loadAll().map(\.groupId), [gid])
        server.offline = false
        // A lost answer: the redeem went through but the phone never heard. The retry sends the same member id and
        // pass, which the server answers again (§6.7); a new pass would find the invite used.
        net.next("POST", "/invites/redeem", .loseAnswer)
        do {
            _ = try await member.hub.join(link, displayName: "Thandi")
            XCTFail("joined without an answer")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .server)
        }
        XCTAssertEqual(server.memberIds(groupId: gid).count, 2)
        XCTAssertTrue(member.hub.groups.isEmpty)
        _ = try await member.hub.join(link, displayName: "Thandi")
        XCTAssertEqual(member.hub.groups.map(\.groupId), [gid])
        XCTAssertEqual(server.memberIds(groupId: gid).count, 2)
        let redeems = bodies("POST", "/invites/redeem", as: RedeemRequest.self)
        XCTAssertEqual(redeems.count, 3)
        XCTAssertEqual(Set(redeems.map(\.pass)).count, 1)
        XCTAssertEqual(Set(redeems.map(\.memberId)).count, 1)
        XCTAssertEqual(member.secrets.loadAll().map(\.memberId), [redeems[0].memberId])
        XCTAssertEqual(member.hub.detail(gid)?.yourMemberId, redeems[0].memberId)

        // A member id clash makes a new id once.
        let next = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link2) = InviteLink.parse(next.link, domain: "example.invalid") else { return XCTFail("no link") }
        let third = try phone()
        server.clearLog()
        server.fail("POST", "/invites/redeem", status: 409, code: "member_exists")
        _ = try await third.hub.join(link2, displayName: "Pieter")
        let tries = bodies("POST", "/invites/redeem", as: RedeemRequest.self)
        XCTAssertEqual(tries.count, 2)
        XCTAssertNotEqual(tries[0].memberId, tries[1].memberId)
        XCTAssertNotEqual(tries[0].pass, tries[1].pass)
        XCTAssertEqual(third.secrets.loadAll().map(\.memberId), [tries[1].memberId])
        XCTAssertEqual(server.memberIds(groupId: gid).count, 3)
    }

    func testRestoreLeavesASecretARetriedJoinTookOver() async throws {
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        let net = FlakyTransport(server: server)
        let member = try phone(transport: net)
        clock.advance(10)
        server.offline = true
        _ = try? await member.hub.join(link, displayName: "Thandi")
        server.offline = false
        // Back online, E2 for the pending membership is answered 401 (it isn't a member yet), but the answer is
        // slow; meanwhile the person taps Join again, which takes the secret over and succeeds.
        net.next("GET", "/v1/groups/\(gid)", .holdAnswer)
        let online = Task { await member.hub.connectivityChanged(online: true) }
        try await waitUntilHeld(net)
        _ = try await member.hub.join(link, displayName: "Thandi")
        net.release()
        await online.value
        await member.hub.waitForBackgroundWork()
        XCTAssertEqual(member.hub.groups.map(\.groupId), [gid])
        XCTAssertEqual(member.secrets.loadAll().map(\.groupId), [gid], "the late 401 belongs to the old try")
        // The membership survives a restart.
        let again = GroupHub(store: member.store, secrets: member.secrets, transport: server, sockets: nil, words: WordFilter(list: ""),
                             domain: "example.invalid", clock: { [clock] in clock!.now })
        await again.start()
        XCTAssertEqual(again.groups.map(\.groupId), [gid])
        XCTAssertEqual(again.detail(gid)?.members.map(\.name), ["Sam", "Thandi"])
    }

    // MARK: - Sharing and the map

    func testSharingReachesTheOtherPhonesMap() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        point(member, segment: true)
        clock.advance(4)
        point(member, lat: -24.99615, lon: 31.59256)
        fix(member, lat: -24.99615, lon: 31.59256)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 1)
        let body = try XCTUnwrap(uploads().first)
        XCTAssertEqual((body["chunks"] as? [Any])?.count, 1)
        XCTAssertNotNil(body["live"] as? [String: Any])
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        XCTAssertEqual(server.chunkStarts(groupId: gid, memberId: memberId), [TrailChunk.windowStart(clock.now)])

        owner.hub.setShown(gid)
        clock.advance(2)
        await owner.hub.tick(foreground: true)
        await owner.hub.waitForBackgroundWork()
        let map = try XCTUnwrap(owner.hub.mapContent(now: clock.now))
        XCTAssertEqual(map.groupId, gid)
        XCTAssertEqual(map.members.count, 1)
        let dot = try XCTUnwrap(map.members.first)
        XCTAssertEqual(dot.memberId, memberId)
        XCTAssertEqual(dot.initials, "T")
        XCTAssertEqual(dot.colourHex.count, 7)
        XCTAssertEqual(dot.position.lat, -24.99615, accuracy: 1e-6)
        XCTAssertEqual(dot.headingDeg, 87)
        XCTAssertFalse(dot.isStale)
        XCTAssertEqual(dot.a11yLabel, "Thandi, Sharing · just now")
        XCTAssertEqual(map.trails.count, 1)
        XCTAssertEqual(map.trails.first?.segments.first?.count, 2)
        XCTAssertEqual(map.trails.first?.widthPt, 3)
        XCTAssertEqual(owner.hub.detail(gid)?.members.last?.state, .sharing(at: clock.now - 2))
        // Your own trail is never drawn as a group trail, and you get no dot.
        XCTAssertNil(member.hub.mapContent(now: clock.now)?.members.first)
        XCTAssertTrue(member.hub.mapContent(now: clock.now)?.trails.isEmpty ?? false)
        // The dot turns stale after 10 minutes and hides after 24 hours.
        clock.advance(601)
        let stale = try XCTUnwrap(owner.hub.mapContent(now: clock.now)?.members.first)
        XCTAssertTrue(stale.isStale)
        XCTAssertNil(stale.headingDeg)
        XCTAssertTrue(stale.a11yLabel.hasPrefix("Thandi, Last seen 10 min ago"))
        XCTAssertTrue(owner.hub.mapContent(now: clock.now + 86_400)?.members.isEmpty ?? false)
    }

    func testConsentGating() async throws {
        let (_, member, gid) = try await ownerAndMember()
        XCTAssertTrue(member.hub.needsConsent(gid))
        member.hub.setSharing(true, groupId: gid)
        XCTAssertEqual(member.hub.detail(gid)?.isSharing, false)
        point(member)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty)
        XCTAssertTrue(uploads().isEmpty)
        member.hub.recordConsent(gid)
        XCTAssertFalse(member.hub.needsConsent(gid))
        XCTAssertEqual(member.hub.detail(gid)?.isSharing, true)
        XCTAssertEqual(member.hub.groups.first?.isSharing, true)
        point(member)
        XCTAssertEqual(member.store.ownWindows(gid).count, 1)
        // Location not precise (reduced accuracy, coarse only): nothing is recorded or sent.
        member.hub.setLocationAccess(authorised: true, precise: false)
        clock.advance(5)
        point(member)
        XCTAssertEqual(member.store.ownWindows(gid).first?.version, 1)
    }

    func testFirstPointAfterSharingOnStartsASegment() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        point(member)
        clock.advance(3)
        point(member)
        member.hub.setSharing(false, groupId: gid)
        member.hub.setSharing(true, groupId: gid)
        clock.advance(3)
        point(member)
        let w = try XCTUnwrap(member.store.ownWindows(gid).first)
        XCTAssertEqual(try TrailChunk.decode(w.points, windowStart: w.start).map(\.startsSegment), [true, false, true])
    }

    // MARK: - Upload pace (§8.6)

    func testHTTPPaceWithAndWithoutWatchers() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        server.clearLog()
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 1, "the first live send is due at once")
        XCTAssertEqual(member.hub.nextTickDelay(foreground: false), 60)
        XCTAssertEqual(member.hub.nextTickDelay(foreground: true), 5)
        for _ in 0..<5 {
            clock.advance(10)
            fix(member)
            await member.hub.tick(foreground: false)
        }
        XCTAssertEqual(uploads().count, 1, "nobody watches: every 60 s")
        clock.advance(10)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 2)
        // Someone starts watching: the answer says so, and the pace becomes 15 s.
        server.setWatchers(1, groupId: gid)
        clock.advance(60)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 3)
        XCTAssertEqual(member.hub.nextTickDelay(foreground: false), 15)
        clock.advance(10)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 3)
        clock.advance(5)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 4)
        XCTAssertNotNil(uploads().last?["live"] as? [String: Any])
    }

    func testHeartbeatWithoutANewFixHasNoLiveKey() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        server.clearLog()
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertNotNil(uploads().last?["live"] as? [String: Any])
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 2)
        XCTAssertNil(uploads().last?["live"], "no new fix: the heartbeat has no live key")
        XCTAssertEqual(uploads().last?.keys.sorted(), ["chunks"])
    }

    func testCoarseFixesAreNeverSentAsLive() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        server.clearLog()
        fix(member, accuracy: 150)
        await member.hub.tick(foreground: false)
        XCTAssertNil(uploads().last?["live"])
        clock.advance(60)
        fix(member, accuracy: nil)
        await member.hub.tick(foreground: false)
        XCTAssertNil(uploads().last?["live"])
        clock.advance(60)
        member.hub.setLocationAccess(authorised: true, precise: false)
        fix(member, accuracy: 5)
        await member.hub.tick(foreground: false)
        XCTAssertNil(uploads().last?["live"])
        XCTAssertNil(server.liveAt(groupId: gid, memberId: memberId))
        member.hub.setLocationAccess(authorised: true, precise: true)
        clock.advance(60)
        fix(member, accuracy: 100)
        await member.hub.tick(foreground: false)
        XCTAssertNotNil(uploads().last?["live"] as? [String: Any])
        XCTAssertEqual(server.liveAt(groupId: gid, memberId: memberId), clock.now)
    }

    func testSocketPaceWhenWatchedAndNot() async throws {
        let sockets = FakeSockets()
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        let member = try Phone(server: server, clock: clock, sockets: sockets)
        _ = try await member.hub.join(link, displayName: "Thandi")
        share(member, gid)
        await member.hub.becameActive()
        let socket = try XCTUnwrap(sockets.opened.last)
        XCTAssertEqual(socket.groupId, gid)
        member.hub.socketDidOpen(groupId: gid)
        let hello = try XCTUnwrap(socket.messages.first)
        XCTAssertEqual(hello["type"] as? String, "hello")
        XCTAssertEqual(hello["watching"] as? Bool, true)
        XCTAssertEqual((hello["cursor"] as? NSNumber)?.int64Value, member.store.group(gid)?.cursor)
        // Welcome with one watcher.
        let status = try await GroupAPI(transport: server).status(gid, pass: try XCTUnwrap(member.secrets.loadAll().first).pass)
        let statusJSON = String(decoding: try JSONEncoder().encode(status), as: UTF8.self)
        member.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"welcome\",\"v\":1,\"status\":\(statusJSON),\"live\":[]}")
        member.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"tick\",\"serverTime\":\(clock.now),\"watchers\":1}")
        server.clearLog()
        func socketUploads() -> [[String: Any]] { socket.messages.filter { $0["type"] as? String == "upload" } }
        // becameActive's flush (before the socket opened) used the live slot over HTTP; 30 s later one is due.
        clock.advance(30)
        fix(member)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 1)
        XCTAssertTrue(uploads().isEmpty, "over the socket, not HTTP")
        // 5 s later, moved 10 m: due. Moved less than 5 m: not due until 30 s.
        clock.advance(5)
        fix(member, lat: -24.99610)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 2)
        clock.advance(5)
        fix(member, lat: -24.99611)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 2)
        clock.advance(25)
        fix(member, lat: -24.99611)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 3)
        // The answer acknowledges the chunk.
        let last = try XCTUnwrap(socketUploads().last)
        let id = try XCTUnwrap((last["id"] as? NSNumber)?.intValue)
        // Nobody watching: every 60 s, with the chunk upload.
        member.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"tick\",\"serverTime\":\(clock.now),\"watchers\":0}")
        member.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"uploaded\",\"id\":\(id),\"accepted\":[],\"rejected\":[],\"liveAccepted\":true}")
        clock.advance(30)
        fix(member, lat: -24.99500)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 3)
        clock.advance(30)
        await member.hub.tick(foreground: true)
        XCTAssertEqual(socketUploads().count, 4)
        // Pings every 30 s.
        XCTAssertTrue(socket.sent.contains("ping"))
        // The background closes the socket; uploads go over HTTP.
        member.hub.enteredBackground()
        XCTAssertEqual(socket.closedWith, 1000)
        clock.advance(60)
        fix(member, lat: -24.99400)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(uploads().count, 1)
    }

    func testSocketClosesAndReconnects() async throws {
        let sockets = FakeSockets()
        let owner = try Phone(server: server, clock: clock, sockets: sockets)
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        owner.hub.setShown(gid)
        await owner.hub.becameActive()
        XCTAssertEqual(sockets.opened.count, 1)
        owner.hub.socketDidOpen(groupId: gid)
        owner.hub.socketDidClose(groupId: gid, code: 1006)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(sockets.opened.count, 1, "waits for the backoff")
        clock.advance(2)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(sockets.opened.count, 2)
        // Showing another group (or none) closes it.
        owner.hub.setShown(nil)
        XCTAssertEqual(sockets.opened.last?.closedWith, 1000)
        owner.hub.setShown(gid)
        XCTAssertEqual(sockets.opened.count, 3)
        // 4404: deleted; no reconnect.
        owner.hub.socketDidClose(groupId: gid, code: 4404)
        XCTAssertEqual(owner.alerts.map(\.kind), [.deleted])
        XCTAssertTrue(owner.hub.groups.isEmpty)
        clock.advance(100)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(sockets.opened.count, 3)
    }

    func testSocketTicksApplyChanges() async throws {
        let sockets = FakeSockets()
        let owner = try Phone(server: server, clock: clock, sockets: sockets)
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        owner.hub.setShown(gid)
        await owner.hub.becameActive()
        owner.hub.socketDidOpen(groupId: gid)
        let cursor = try XCTUnwrap(owner.store.group(gid)?.cursor)
        // Another admin renames the group (same key); the change arrives in a tick.
        let secret = try XCTUnwrap(owner.secrets.loadAll().first)
        let keys = GroupKeys(groupKey: Base64URL.decode(secret.groupKey)!, groupId: Base64URL.decode(gid)!)
        func profile(_ name: String) throws -> String {
            Base64URL.encode(try GroupCrypto.seal(GroupProfile(name: name).json(), key: keys.group, aad: GroupAAD.group(gid)))
        }
        let api = GroupAPI(transport: server)
        let status = String(decoding: try JSONEncoder().encode(try await api.status(gid, pass: secret.pass)), as: UTF8.self)
        owner.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"welcome\",\"v\":1,\"status\":\(status),\"live\":[]}")
        let env = try profile("Kruger 2027")
        let seq = try await api.renameGroup(gid, pass: secret.pass, profile: env).seq
        XCTAssertEqual(seq, cursor + 1)
        owner.hub.socketDidReceive(groupId: gid, text: """
            {"type":"tick","serverTime":\(clock.now),"changes":{"from":\(cursor),"items":[{"kind":"group","seq":\(seq),"profile":"\(env)"}],"cursor":\(seq),"more":false}}
            """)
        XCTAssertEqual(owner.hub.groups.first?.name, "Kruger 2027")
        XCTAssertEqual(owner.store.group(gid)?.cursor, seq)
        XCTAssertEqual(owner.store.group(gid)?.cursorAt, clock.now)
        // A tick whose `from` is ahead of the cursor (a frame was missed) is ignored and pulled over HTTP instead.
        _ = try await api.renameGroup(gid, pass: secret.pass, profile: try profile("Missed"))
        let last = try await api.renameGroup(gid, pass: secret.pass, profile: try profile("Kruger 2028")).seq
        server.clearLog()
        owner.hub.socketDidReceive(groupId: gid, text: """
            {"type":"tick","serverTime":\(clock.now),"changes":{"from":\(last - 1),"items":[{"kind":"group","seq":\(last),"profile":"\(try profile("Wrong"))"}],"cursor":\(last),"more":false}}
            """)
        await owner.hub.waitForBackgroundWork()
        XCTAssertEqual(requests("GET", "/changes").count, 1)
        XCTAssertEqual(owner.store.group(gid)?.cursor, last)
        XCTAssertEqual(owner.hub.groups.first?.name, "Kruger 2028")
        // After catching up over HTTP the socket is told where the client is.
        XCTAssertEqual(sockets.opened.last?.messages.last?["type"] as? String, "ack")
        XCTAssertEqual((sockets.opened.last?.messages.last?["cursor"] as? NSNumber)?.int64Value, last)
        // A tick that says `more` makes the client pull too.
        server.clearLog()
        owner.hub.socketDidReceive(groupId: gid, text: """
            {"type":"tick","serverTime":\(clock.now),"changes":{"from":\(last),"items":[],"cursor":\(last),"more":true}}
            """)
        await owner.hub.waitForBackgroundWork()
        XCTAssertEqual(requests("GET", "/changes").count, 1)
    }

    // MARK: - Packing and answers (§4.3)

    func testPackingBy50ChunksAnd192KiB() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        server.offline = true
        let first = TrailChunk.windowStart(clock.now) + 600
        // 60 windows of 300 incompressible points.
        var seed: UInt64 = 12345
        func next() -> Double {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(seed >> 11) / Double(1 << 53)
        }
        for w in 0..<60 {
            for i in 0..<300 {
                point(member, lat: -80 + 160 * next(), lon: -170 + 340 * next(), at: first + Int64(w) * 600 + Int64(i))
            }
        }
        XCTAssertEqual(member.store.ownWindows(gid).count, 60)
        clock.now = first + 60 * 600 + 10
        server.offline = false
        server.clearLog()
        await member.hub.connectivityChanged(online: true)
        let bodies = requests("POST", "/upload").compactMap(\.body)
        XCTAssertGreaterThan(bodies.count, 1)
        var total = 0
        for b in bodies {
            XCTAssertLessThanOrEqual(b.count, GroupPolicy.maxUploadBytes)
            let chunks = ((try? JSONSerialization.jsonObject(with: b)) as? [String: Any])?["chunks"] as? [Any] ?? []
            XCTAssertLessThanOrEqual(chunks.count, 50)
            total += chunks.count
        }
        XCTAssertEqual(total, 60)
        // Acknowledged windows go once they have been closed for a minute; the newest waits for late points.
        XCTAssertEqual(member.store.ownWindows(gid).map(\.start), [first + 59 * 600])
        clock.advance(GroupPolicy.lateWindowGrace)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty, "closed and acknowledged windows are deleted")
        // Oldest first.
        let firstBody = (try? JSONSerialization.jsonObject(with: bodies[0])) as? [String: Any]
        let starts = (firstBody?["chunks"] as? [[String: Any]])?.compactMap { ($0["start"] as? NSNumber)?.int64Value } ?? []
        XCTAssertEqual(starts.first, first)
        XCTAssertEqual(starts, starts.sorted())
    }

    func testFiftyChunkLimit() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let first = TrailChunk.windowStart(clock.now) + 600
        for w in 0..<120 {
            point(member, at: first + Int64(w) * 600)
        }
        clock.now = first + 120 * 600 + 10
        server.clearLog()
        await member.hub.tick(foreground: false)
        let counts = requests("POST", "/upload").compactMap(\.body).map { b in
            (((try? JSONSerialization.jsonObject(with: b)) as? [String: Any])?["chunks"] as? [Any])?.count ?? -1
        }
        XCTAssertEqual(counts, [50, 50, 20])
    }

    func testRejectedChunks() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let w = TrailChunk.windowStart(clock.now) + 600
        for k in 0..<4 { point(member, at: w + Int64(k) * 600) }
        server.chunkRejections = [w: "before_join", w + 600: "window_paused", w + 1200: "bad_record", w + 1800: "bad_time"]
        clock.now = w + 2400 + 5
        await member.hub.tick(foreground: false)
        // Three can never be accepted and go; bad_time stays for one retry after the next E2.
        XCTAssertEqual(member.store.ownWindows(gid).map(\.start), [w + 1800])
        XCTAssertEqual(member.store.ownWindows(gid).first?.badTimeTries, 1)
        server.clearLog()
        clock.advance(120)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(uploads().allSatisfy { ($0["chunks"] as? [Any])?.isEmpty ?? true }, "held until E2")
        await member.hub.refreshDetail(gid)
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertEqual((uploads().last?["chunks"] as? [Any])?.count, 1)
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty, "rejected again: deleted")
        // A bad_time that is accepted on its retry is kept as usual.
        server.chunkRejections = [w + 3000: "bad_time"]
        point(member, at: w + 3000)
        clock.now = w + 3600 + 5
        await member.hub.tick(foreground: false)
        server.chunkRejections = [:]
        await member.hub.refreshDetail(gid)
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        XCTAssertEqual(server.chunkStarts(groupId: gid, memberId: memberId), [w + 3000])
    }

    func testOpenChunkIsResentOnlyWhenChanged() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let w = TrailChunk.windowStart(clock.now) + 600
        clock.now = w + 10
        point(member)
        server.clearLog()
        await member.hub.tick(foreground: false)
        XCTAssertEqual((uploads().last?["chunks"] as? [Any])?.count, 1)
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertEqual((uploads().last?["chunks"] as? [Any])?.count, 0, "unchanged: not re-sent")
        point(member)
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertEqual((uploads().last?["chunks"] as? [Any])?.count, 1)
        // Each send is a new encryption.
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        let recs = uploads().compactMap { ($0["chunks"] as? [[String: Any]])?.first?["rec"] as? String }
        XCTAssertEqual(recs.count, 2)
        XCTAssertNotEqual(recs[0], recs[1])
        XCTAssertEqual(server.chunkRecord(groupId: gid, memberId: memberId, start: w), recs[1])
        XCTAssertEqual(member.store.ownWindows(gid).count, 1, "the open window stays")
    }

    func testWindowsAcknowledgedWhileOpenAreDeletedOnceClosed() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let secret = try XCTUnwrap(member.secrets.loadAll().first)
        let w = TrailChunk.windowStart(clock.now) + 600
        // One point per window, each window sent while it is still open and never changed after.
        for k in 0..<6 {
            clock.now = w + Int64(k) * 600 + 10
            point(member)
            await member.hub.tick(foreground: false)
        }
        XCTAssertEqual(server.chunkStarts(groupId: gid, memberId: secret.memberId).count, 6)
        // Each goes once it has been closed for a minute; the last closed one and the open one are left.
        let left = member.store.ownWindows(gid)
        XCTAssertEqual(left.map(\.start), [w + 2400, w + 3000])
        XCTAssertTrue(left.allSatisfy { $0.sentVersion == $0.version })

        // A point the recorder delivers a few seconds late joins its whole window, which goes again with all its
        // points instead of replacing the server's copy with that point alone: the window stays for a minute after
        // it closed, even through an upload pass.
        clock.now = w + 3600 + 2
        await member.hub.tick(foreground: false)
        XCTAssertEqual(member.store.ownWindows(gid).map(\.start), [w + 3000])
        clock.now = w + 3600 + 5
        point(member, at: w + 3599)
        XCTAssertEqual(member.store.ownWindow(gid, start: w + 3000)?.version, 2)
        clock.advance(60)
        await member.hub.tick(foreground: false)
        let keys = GroupKeys(groupKey: try XCTUnwrap(Base64URL.decode(secret.groupKey, count: 32)),
                             groupId: try XCTUnwrap(Base64URL.decode(gid, count: 16)))
        let rec = try XCTUnwrap(server.chunkRecord(groupId: gid, memberId: secret.memberId, start: w + 3000).flatMap { Base64URL.decode($0) })
        let plain = try GroupCrypto.open(rec, key: keys.trail(secret.memberId), aad: GroupAAD.trail(gid, secret.memberId, start: w + 3000))
        XCTAssertEqual(try TrailChunk.decode(plain, windowStart: w + 3000).map(\.time), [w + 3010, w + 3599])
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty)
    }

    // MARK: - Clearing the trail (§8.10)

    func testNoChunkUploadWhileClearTrailIsQueued() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        point(member)
        await member.hub.tick(foreground: false)
        XCTAssertFalse(server.chunkStarts(groupId: gid, memberId: memberId).isEmpty)
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(owner.store.chunks(gid, memberId: memberId).count, 1)

        server.clearLog()
        server.fail("DELETE", "/trail/me", status: 500, code: "internal", times: 2)
        member.hub.clearMyTrail(groupId: gid)
        XCTAssertTrue(member.store.ownWindows(gid).isEmpty)
        clock.advance(10)
        point(member)
        await member.hub.waitForBackgroundWork()
        clock.advance(300)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(member.store.ops(gid).map(\.kind), ["cleartrail"])
        XCTAssertTrue(uploads().isEmpty, "nothing goes up while E22 waits")
        XCTAssertEqual(member.store.ownWindows(gid).count, 1, "points keep collecting")
        // E22 goes through; then the new chunk.
        server.clearLog()
        clock.advance(400)
        await member.hub.tick(foreground: false)
        let order = server.log.filter { $0.path.contains("/trail/me") || $0.path.contains("/upload") }.map(\.method)
        XCTAssertEqual(order.first, "DELETE")
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        XCTAssertEqual(server.chunkStarts(groupId: gid, memberId: memberId).count, 1)
        // The other phone hears `removed trail` and drops the old chunks.
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(owner.store.chunks(gid, memberId: memberId).count, 1)
        XCTAssertEqual(owner.store.chunks(gid, memberId: memberId).first?.start, TrailChunk.windowStart(clock.now - 700))
    }

    // MARK: - Offline

    func testOfflineQueueThenFlush() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        server.offline = true
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: "Tap at the gate", lat: -24.99, lon: 31.6)
        clock.advance(1)
        try member.hub.dropPin(groupId: gid, type: .animal, animal: "Rhino", note: nil, lat: -24.98, lon: 31.6)
        point(member)
        fix(member)
        await member.hub.tick(foreground: false)
        await member.hub.waitForBackgroundWork()
        // Shown at once; queued.
        XCTAssertEqual(member.hub.mapContent(now: clock.now)?.pins.count, 2)
        XCTAssertEqual(member.store.ops(gid).map(\.kind), ["pin", "pin"])
        XCTAssertTrue(server.pinIds(groupId: gid).isEmpty)
        server.offline = false
        clock.advance(30)
        await member.hub.connectivityChanged(online: true)
        XCTAssertEqual(server.pinIds(groupId: gid).count, 2)
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        XCTAssertTrue(member.store.pins(gid).allSatisfy { $0.seq > 0 })
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        XCTAssertEqual(server.chunkStarts(groupId: gid, memberId: memberId).count, 1)
        // Live is never queued: the fix taken offline went with the first upload after it only because it is the newest.
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        let pins = try XCTUnwrap(owner.hub.mapContent(now: clock.now)?.pins)
        XCTAssertEqual(pins.map(\.knownType), [.water, .animal])
        XCTAssertEqual(pins[1].label, "Rhino · just now")
        XCTAssertEqual(pins[0].a11yLabel, "Water, dropped by Thandi just now")
        XCTAssertEqual(pins[1].a11yLabel, "Rhino, dropped by Thandi just now")
        XCTAssertEqual(pins[0].colourHex, "#1E88E5")
        let detail = try XCTUnwrap(owner.hub.pinDetail(groupId: gid, pinId: pins[0].pinId))
        XCTAssertEqual(detail.note, "Tap at the gate")
        XCTAssertEqual(detail.dropperName, "Thandi")
        XCTAssertNil(detail.expiresAt)
        XCTAssertTrue(detail.canDelete, "the owner may delete any pin")
        XCTAssertTrue(detail.canReport && detail.canBlock)
        XCTAssertFalse(detail.isYours)
        let animal = try XCTUnwrap(owner.hub.pinDetail(groupId: gid, pinId: pins[1].pinId))
        XCTAssertEqual(animal.expiresAt, animal.createdAt + 43_200)
    }

    // MARK: - Pins

    func testExpiredAnimalPinHidesOffline() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        try member.hub.dropPin(groupId: gid, type: .animal, animal: "Kudu", note: nil, lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(owner.hub.mapContent(now: clock.now)?.pins.count, 1)
        server.offline = true
        clock.advance(43_200 - 1)
        XCTAssertEqual(owner.hub.mapContent(now: clock.now)?.pins.first?.label, "Kudu · 11 h ago")
        clock.advance(1)
        XCTAssertTrue(owner.hub.mapContent(now: clock.now)?.pins.isEmpty ?? false)
        await owner.hub.tick(foreground: true)
        XCTAssertTrue(owner.store.pins(gid).isEmpty)
    }

    func testPinDeletedOnTheServerDropsTheQueuedPin() async throws {
        let (_, member, gid) = try await ownerAndMember()
        server.fail("POST", "/pins", status: 410, code: "pin_deleted")
        try member.hub.dropPin(groupId: gid, type: .danger, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        XCTAssertTrue(member.hub.mapContent(now: clock.now)?.pins.isEmpty ?? false)
    }

    func testPinIdClashGetsANewIdOnce() async throws {
        let (_, member, gid) = try await ownerAndMember()
        server.fail("POST", "/pins", status: 409, code: "pin_exists")
        try member.hub.dropPin(groupId: gid, type: .shelter, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        let posted = requests("POST", "/pins").compactMap { $0.body.flatMap { try? JSONDecoder().decode(PinRequest.self, from: $0) } }
        XCTAssertEqual(posted.count, 2)
        XCTAssertNotEqual(posted[0].pinId, posted[1].pinId)
        XCTAssertEqual(server.pinIds(groupId: gid), [posted[1].pinId])
        XCTAssertEqual(member.store.pins(gid).map(\.pinId), [posted[1].pinId])
    }

    func testDeletingPins() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        try member.hub.dropPin(groupId: gid, type: .pothole, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        let pinId = try XCTUnwrap(server.pinIds(groupId: gid).first)
        // A pin deleted before it was sent never reaches the server.
        server.offline = true
        try member.hub.dropPin(groupId: gid, type: .police, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        let unsent = try XCTUnwrap(member.store.pins(gid).first { $0.seq == 0 }?.pinId)
        await member.hub.waitForBackgroundWork()
        member.hub.deletePin(unsent, groupId: gid)
        await member.hub.waitForBackgroundWork()
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        server.offline = false
        // The owner deletes the member's pin; the member hears it.
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        owner.hub.deletePin(pinId, groupId: gid)
        await owner.hub.waitForBackgroundWork()
        XCTAssertTrue(server.pinIds(groupId: gid).isEmpty)
        await member.hub.becameActive()
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        // Dropping needs a valid animal; the word filter applies.
        XCTAssertThrowsError(try member.hub.dropPin(groupId: gid, type: .animal, animal: " ", note: nil, lat: 0, lon: 0)) { e in
            XCTAssertEqual((e as? GroupActionError)?.kind, .invalidText(.animal))
        }
        XCTAssertThrowsError(try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: "badword", lat: 0, lon: 0)) { e in
            XCTAssertEqual((e as? GroupActionError)?.message, "Please use different words.")
        }
    }

    @MainActor
    func testPinDeletedWhileItsDropIsOnItsWay() async throws {
        let net = FlakyTransport(server: server)
        let (owner, member, gid) = try await ownerAndMember(memberTransport: net)
        // The E23 reaches the server, but its answer is slow; the rhino pin is deleted meanwhile.
        net.next("POST", "/pins", .holdAnswer)
        try member.hub.dropPin(groupId: gid, type: .animal, animal: "Rhino", note: nil, lat: -24.98, lon: 31.6)
        try await waitUntilHeld(net)
        let pinId = try XCTUnwrap(server.pinIds(groupId: gid).first)
        member.hub.deletePin(pinId, groupId: gid)
        XCTAssertTrue(member.hub.mapContent(now: clock.now)?.pins.isEmpty ?? false)
        net.release()
        await member.hub.waitForBackgroundWork()
        // E24 followed the E23: the pin is gone for everyone, and stays gone on the dropper's map.
        XCTAssertTrue(server.pinIds(groupId: gid).isEmpty)
        XCTAssertEqual(requests("DELETE", "/pins/\(pinId)").count, 1)
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        await member.hub.becameActive()
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        XCTAssertTrue(owner.store.pins(gid).isEmpty)

        // An id clash answer for a pin deleted on its way: it isn't sent again under a new id, and no E24 goes for
        // the clashing id (another member's pin).
        server.fail("POST", "/pins", status: 409, code: "pin_exists")
        net.next("POST", "/pins", .holdAnswer)
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        try await waitUntilHeld(net)
        let clashed = try XCTUnwrap(member.store.pins(gid).first?.pinId)
        member.hub.deletePin(clashed, groupId: gid)
        server.clearLog()
        net.release()
        await member.hub.waitForBackgroundWork()
        XCTAssertTrue(requests("POST", "/pins").isEmpty)
        XCTAssertTrue(requests("DELETE", "/pins").isEmpty)
        XCTAssertTrue(server.pinIds(groupId: gid).isEmpty)
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        XCTAssertTrue(member.store.ops(gid).isEmpty)
    }

    @MainActor
    func testPinDeletedLaterInTheSamePassNeverGoes() async throws {
        let net = FlakyTransport(server: server)
        let (_, member, gid) = try await ownerAndMember(memberTransport: net)
        server.offline = true
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: -24.99, lon: 31.6)
        clock.advance(1)
        try member.hub.dropPin(groupId: gid, type: .police, animal: nil, note: nil, lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        let queued = member.store.pins(gid).map(\.pinId)
        XCTAssertEqual(queued.count, 2)
        // Back online: the first E23 is on its way when the second pin is deleted.
        server.offline = false
        server.clearLog()
        net.next("POST", "/pins", .holdAnswer)
        let online = Task { await member.hub.connectivityChanged(online: true) }
        try await waitUntilHeld(net)
        member.hub.deletePin(queued[1], groupId: gid)
        net.release()
        await online.value
        await member.hub.waitForBackgroundWork()
        XCTAssertEqual(server.pinIds(groupId: gid), [queued[0]])
        XCTAssertEqual(bodies("POST", "/pins", as: PinRequest.self).map(\.pinId), [queued[0]])
        XCTAssertTrue(requests("DELETE", "/pins").isEmpty)
        XCTAssertEqual(member.store.pins(gid).map(\.pinId), [queued[0]])
        XCTAssertTrue(member.store.ops(gid).isEmpty)
    }

    @MainActor
    func testRenameIsNotOverwrittenByAQueuedColourFix() async throws {
        let net = FlakyTransport(server: server)
        let (_, member, gid) = try await ownerAndMember(memberTransport: net)
        let secret = try XCTUnwrap(member.secrets.loadAll().first)
        let colourFix = try sealedProfile(secret, name: "Thandi", colour: 3)
        func profilePuts() -> Int { requests("PUT", "/members/me/profile").count }

        // A colour fix on its way when the person renames: the rename goes after its answer, so it lands last.
        member.store.addOp(gid, kind: "profile", body: colourFix, createdAt: clock.now)
        server.clearLog()
        net.next("PUT", "/members/me/profile", .holdRequest)
        let flush = Task { await member.hub.tick(foreground: false) }
        try await waitUntilHeld(net)
        let rename = Task { try await member.hub.setDisplayName("Thandi M", colour: 5, groupId: gid) }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(profilePuts(), 0, "the rename waits for the colour fix's answer")
        net.release()
        await flush.value
        try await rename.value
        XCTAssertEqual(profilePuts(), 2)
        XCTAssertEqual(try serverProfile(secret), MemberProfile(name: "Thandi M", colour: 5))
        XCTAssertEqual(member.hub.detail(gid)?.members.last?.name, "Thandi M")
        XCTAssertTrue(member.store.ops(gid).isEmpty)

        // A colour fix queued behind a pin on its way, replaced by a rename meanwhile: it never goes.
        server.offline = true
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: -24.99, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        member.store.addOp(gid, kind: "profile", body: colourFix, createdAt: clock.now)
        server.offline = false
        server.clearLog()
        net.next("POST", "/pins", .holdAnswer)
        let online = Task { await member.hub.connectivityChanged(online: true) }
        try await waitUntilHeld(net)
        try await member.hub.setDisplayName("Thandi K", colour: 7, groupId: gid)
        net.release()
        await online.value
        await member.hub.waitForBackgroundWork()
        XCTAssertEqual(profilePuts(), 1)
        XCTAssertEqual(try serverProfile(secret), MemberProfile(name: "Thandi K", colour: 7))
        XCTAssertTrue(member.store.ops(gid).isEmpty)
        XCTAssertEqual(server.pinIds(groupId: gid).count, 1)
    }

    // MARK: - Pause (§8.10)

    func testPausedDropsPointsAndStopsLive() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        fix(member)
        point(member)
        await member.hub.tick(foreground: false)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        XCTAssertNotNil(server.liveAt(groupId: gid, memberId: memberId))
        server.pause(groupId: gid, reason: "pro_ended")
        await member.hub.becameActive()
        let detail = try XCTUnwrap(member.hub.detail(gid))
        XCTAssertTrue(detail.isPaused)
        XCTAssertEqual(detail.pausedReason, .proEnded)
        XCTAssertEqual(detail.deleteAt, clock.now + 2_592_000)
        XCTAssertTrue(member.hub.groups.first?.isPaused ?? false)
        server.clearLog()
        clock.advance(100)
        point(member)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(member.store.ownWindows(gid).map(\.version), [1], "no points recorded while paused")
        XCTAssertTrue(uploads().isEmpty, "no live while paused")
        XCTAssertThrowsError(try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: 0, lon: 0)) { e in
            XCTAssertEqual((e as? GroupActionError)?.kind, .groupPaused)
        }
        // Reporting, renaming and invites still work.
        try await owner.hub.renameGroup("Kruger 2027", groupId: gid)
        _ = try await owner.hub.createInvite(groupId: gid)
        try await member.hub.report(groupId: gid, target: .group, reason: .spam, comment: "")
        // Resumed: sharing goes on, the first point starts a new segment.
        server.resume(groupId: gid)
        await member.hub.becameActive()
        XCTAssertFalse(member.hub.detail(gid)?.isPaused ?? true)
        XCTAssertEqual(member.hub.groups.first?.name, "Kruger 2027")
        clock.advance(10)
        point(member)
        let w = try XCTUnwrap(member.store.ownWindows(gid).last)
        XCTAssertEqual(try TrailChunk.decode(w.points, windowStart: w.start).last?.startsSegment, true)
    }

    func testQueuedPointsInsideAPauseAreDropped() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        let secret = try XCTUnwrap(member.secrets.loadAll().first)
        let keys = GroupKeys(groupKey: try XCTUnwrap(Base64URL.decode(secret.groupKey)), groupId: try XCTUnwrap(Base64URL.decode(gid)))
        let w = TrailChunk.windowStart(clock.now) + 600
        // The phone didn't hear of the pause from w + 100 to w + 200 and kept recording.
        point(member, at: w + 10)
        point(member, at: w + 120)
        point(member, at: w + 300)
        server.pause(groupId: gid, at: w + 100)
        clock.now = w + 200
        server.resume(groupId: gid)
        clock.now = w + 700
        // E2 brings `pausedIntervals`; before the upload the point inside it is dropped.
        await member.hub.refreshDetail(gid)
        await member.hub.tick(foreground: false)
        let rec = try XCTUnwrap(server.chunkRecord(groupId: gid, memberId: memberId, start: w))
        let plain = try GroupCrypto.open(try XCTUnwrap(Base64URL.decode(rec)), key: keys.trail(memberId), aad: GroupAAD.trail(gid, memberId, start: w))
        XCTAssertEqual(try TrailChunk.decode(plain, windowStart: w).map(\.time), [w + 10, w + 300])
    }

    // MARK: - Roles, removal, leaving, deletion

    func testRolesAndPermissions() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        let ownerId = try XCTUnwrap(owner.hub.detail(gid)?.yourMemberId)
        var m = try XCTUnwrap(owner.hub.memberDetail(groupId: gid, memberId: memberId))
        XCTAssertTrue(m.canMakeAdmin && m.canMakeOwner && m.canRemove && m.canReport && m.canBlock)
        XCTAssertFalse(m.canRemoveAdmin)
        let me = try XCTUnwrap(owner.hub.memberDetail(groupId: gid, memberId: ownerId))
        XCTAssertFalse(me.canReport || me.canBlock || me.canRemove || me.canMakeOwner)
        // A member can't do owner things: the server refuses with 403.
        do {
            try await member.hub.setRole(.admin, memberId: memberId, groupId: gid)
            XCTFail("member made an admin")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .forbidden)
            XCTAssertEqual(e.message, "You can't do that in this group.")
        }
        try await owner.hub.setRole(.admin, memberId: memberId, groupId: gid)
        m = try XCTUnwrap(owner.hub.memberDetail(groupId: gid, memberId: memberId))
        XCTAssertEqual(m.row.role, .admin)
        XCTAssertTrue(m.canRemoveAdmin)
        await member.hub.becameActive()
        let asAdmin = try XCTUnwrap(member.hub.detail(gid))
        XCTAssertEqual(asAdmin.yourRole, .admin)
        XCTAssertTrue(asAdmin.canInvite && asAdmin.canRename && asAdmin.canLeave && asAdmin.canReportGroupName)
        XCTAssertFalse(asAdmin.canHandOver || asAdmin.canDeleteGroup)
        XCTAssertEqual(asAdmin.invites, [])
        // An admin can't remove the owner.
        let ownerRow = try XCTUnwrap(member.hub.memberDetail(groupId: gid, memberId: ownerId))
        XCTAssertFalse(ownerRow.canRemove || ownerRow.canMakeAdmin)
        // Ownership offer, decline, offer, accept.
        try await owner.hub.offerOwnership(to: memberId, groupId: gid)
        XCTAssertEqual(owner.hub.detail(gid)?.offerByYouTo, "Thandi")
        await member.hub.becameActive()
        XCTAssertEqual(member.hub.detail(gid)?.offerToYouFrom, "Sam")
        XCTAssertTrue(member.hub.groups.first?.hasOfferToYou ?? false)
        try await member.hub.cancelOwnershipOffer(groupId: gid)
        XCTAssertNil(member.hub.detail(gid)?.offerToYouFrom)
        try await owner.hub.offerOwnership(to: memberId, groupId: gid)
        await member.hub.becameActive()
        try await member.hub.acceptOwnership(groupId: gid, pro: .google(productId: "pro", purchaseToken: "token-1"))
        XCTAssertEqual(member.hub.detail(gid)?.yourRole, .owner)
        XCTAssertEqual(member.store.group(gid)?.proSent, "token-1")
        await owner.hub.becameActive()
        XCTAssertEqual(owner.hub.detail(gid)?.yourRole, .admin)
        XCTAssertEqual(owner.hub.detail(gid)?.members.first { $0.isYou }?.role, .admin)
    }

    func testRemovalWipesTheRemovedPhone() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: 0, lon: 0)
        await member.hub.waitForBackgroundWork()
        try await owner.hub.removeMember(memberId, groupId: gid)
        XCTAssertEqual(owner.hub.detail(gid)?.members.map(\.name), ["Sam"])
        XCTAssertTrue(server.pinIds(groupId: gid).isEmpty, "their pins go with them")
        await member.hub.becameActive()
        XCTAssertEqual(member.alerts, [GroupAlert(kind: .removed, groupId: gid, groupName: "Kruger trip")])
        XCTAssertTrue(member.hub.groups.isEmpty)
        XCTAssertNil(member.store.group(gid))
        XCTAssertTrue(member.store.pins(gid).isEmpty)
        XCTAssertTrue(member.secrets.loadAll().isEmpty)
    }

    func testDeletedAndGoneAlerts() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        try await owner.hub.deleteGroup(groupId: gid)
        XCTAssertTrue(owner.hub.groups.isEmpty)
        XCTAssertTrue(owner.alerts.isEmpty, "your own deletion has no alert")
        await member.hub.becameActive()
        XCTAssertEqual(member.alerts.map(\.kind), [.deleted])
        XCTAssertEqual(member.alerts.first?.groupName, "Kruger trip")
        XCTAssertTrue(member.secrets.loadAll().isEmpty)

        let (_, other, gid2) = try await ownerAndMember(name: "Drone team")
        server.purgeGroup(groupId: gid2)
        await other.hub.becameActive()
        XCTAssertEqual(other.alerts.map(\.kind), [.gone])
        XCTAssertTrue(other.hub.groups.isEmpty)
    }

    func test401FromAnotherEndpointWipesNothingUnlessStatusAgrees() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        server.fail("POST", "/upload", status: 401, code: "unauthorized")
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertEqual(member.hub.groups.map(\.groupId), [gid], "E2 still answers: nothing deleted")
        XCTAssertEqual(member.secrets.loadAll().count, 1)
        XCTAssertGreaterThanOrEqual(requests("GET", "/v1/groups/\(gid)").filter { !$0.path.contains("/changes") && !$0.path.contains("/live") }.count, 1)
        server.fail("POST", "/upload", status: 401, code: "unauthorized")
        server.fail("GET", "/v1/groups/\(gid)", status: 401, code: "unauthorized")
        clock.advance(60)
        fix(member)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(member.hub.groups.isEmpty)
        XCTAssertEqual(member.alerts.map(\.kind), [.gone])
        XCTAssertTrue(member.secrets.loadAll().isEmpty)
    }

    func testLeaving() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        do {
            try await owner.hub.leave(groupId: gid)
            XCTFail("the owner left")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .forbidden)
        }
        server.offline = true
        do {
            try await member.hub.leave(groupId: gid)
            XCTFail("left offline")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .offline)
        }
        XCTAssertEqual(member.hub.groups.count, 1)
        server.offline = false
        try await member.hub.leave(groupId: gid)
        XCTAssertTrue(member.hub.groups.isEmpty)
        XCTAssertTrue(member.alerts.isEmpty)
        XCTAssertTrue(member.secrets.loadAll().isEmpty)
        await owner.hub.becameActive()
        XCTAssertEqual(owner.hub.detail(gid)?.members.count, 1)
    }

    // MARK: - Block list (§8.11)

    func testBlockedMembersAreHidden() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        share(member, gid)
        point(member, segment: true)
        clock.advance(5)
        point(member, lat: -24.9961)
        fix(member)
        try member.hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: 0, lon: 0)
        await member.hub.waitForBackgroundWork()
        await member.hub.tick(foreground: false)
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        var map = try XCTUnwrap(owner.hub.mapContent(now: clock.now))
        XCTAssertEqual(map.members.count, 1)
        XCTAssertEqual(map.trails.count, 1)
        XCTAssertEqual(map.pins.count, 1)
        owner.hub.block(memberId, groupId: gid)
        map = try XCTUnwrap(owner.hub.mapContent(now: clock.now))
        XCTAssertTrue(map.members.isEmpty && map.trails.isEmpty && map.pins.isEmpty)
        let detail = try XCTUnwrap(owner.hub.detail(gid))
        XCTAssertEqual(detail.members.map(\.name), ["Sam", "Blocked member"])
        XCTAssertTrue(detail.members[1].isBlocked)
        XCTAssertEqual(detail.blocked.map(\.name), ["Thandi"])
        XCTAssertNil(owner.hub.memberDetail(groupId: gid, memberId: memberId)?.live)
        // Not told to anyone: no request.
        XCTAssertFalse(server.log.contains { $0.path.contains("block") })
        // You can't block yourself.
        owner.hub.block(detail.yourMemberId, groupId: gid)
        XCTAssertEqual(owner.store.blocked(gid), [memberId])
        owner.hub.unblock(memberId, groupId: gid)
        XCTAssertEqual(owner.hub.mapContent(now: clock.now)?.members.count, 1)
        XCTAssertTrue(owner.hub.detail(gid)?.blocked.isEmpty ?? false)
    }

    // MARK: - Member state (§8.9)

    func testMemberState() async throws {
        let sockets = FakeSockets()
        let owner = try Phone(server: server, clock: clock, sockets: sockets)
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        let member = try phone()
        _ = try await member.hub.join(link, displayName: "Thandi")
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        owner.hub.setShown(gid)
        await owner.hub.becameActive()
        owner.hub.socketDidOpen(groupId: gid)
        func state() -> MemberState? { owner.hub.detail(gid)?.members.first { $0.memberId == memberId }?.state }
        XCTAssertEqual(state(), .notSharing)
        share(member, gid)
        fix(member)
        let sharedAt = clock.now
        await member.hub.tick(foreground: false)
        // The live entry arrives in a tick.
        let ownerPass = try XCTUnwrap(owner.secrets.loadAll().first?.pass)
        let live = try await GroupAPI(transport: server).live(gid, pass: ownerPass).live
        let liveJSON = String(decoding: try JSONEncoder().encode(live), as: UTF8.self)
        owner.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"tick\",\"serverTime\":\(clock.now),\"live\":\(liveJSON)}")
        XCTAssertEqual(state(), .sharing(at: sharedAt))
        XCTAssertEqual(owner.hub.memberDetail(groupId: gid, memberId: memberId)?.live?.lat ?? 0, -24.99619, accuracy: 1e-6)
        clock.advance(601)
        XCTAssertEqual(state(), .lastSeen(at: sharedAt))
        // Stopped sharing: a tick with `rec: null` removes it; then the last E11 `lastLiveAt` decides.
        member.hub.setSharing(false, groupId: gid)
        await member.hub.waitForBackgroundWork()
        XCTAssertNil(server.liveAt(groupId: gid, memberId: memberId))
        owner.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"tick\",\"serverTime\":\(clock.now),\"live\":[{\"memberId\":\"\(memberId)\",\"at\":\(clock.now),\"rec\":null}]}")
        XCTAssertEqual(state(), .notSharing)
        XCTAssertNil(owner.hub.memberDetail(groupId: gid, memberId: memberId)?.live)
        await owner.hub.refreshDetail(gid)
        XCTAssertEqual(state(), .lastSeen(at: sharedAt))
        // One older than 24 h is never used.
        clock.advance(86_400)
        owner.hub.socketDidReceive(groupId: gid, text: "{\"type\":\"tick\",\"serverTime\":\(clock.now),\"live\":\(liveJSON)}")
        XCTAssertNil(owner.hub.memberDetail(groupId: gid, memberId: memberId)?.live)
    }

    func testStopSharingSendsLiveNull() async throws {
        let (_, member, gid) = try await ownerAndMember()
        share(member, gid)
        fix(member)
        point(member)
        await member.hub.tick(foreground: false)
        server.clearLog()
        clock.advance(5)
        point(member)
        member.hub.setSharing(false, groupId: gid)
        await member.hub.waitForBackgroundWork()
        let body = try XCTUnwrap(uploads().first)
        XCTAssertTrue(body["live"] is NSNull, "\"live\": null")
        // Points recorded while sharing still go.
        clock.advance(60)
        await member.hub.tick(foreground: false)
        XCTAssertTrue(member.store.ownWindows(gid).allSatisfy { $0.sentVersion == $0.version })
        XCTAssertEqual(member.hub.detail(gid)?.isSharing, false)
    }

    // MARK: - Terms (§8.13)

    func testTermsOutdatedBothWays() async throws {
        server.termsVersion = 2
        let p = try phone()
        do {
            _ = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
            XCTFail("created with old terms")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .termsOutdated)
            XCTAssertEqual(e.message, "Please update SatMap to create or join groups.")
        }
        XCTAssertTrue(p.secrets.loadAll().isEmpty)
        // The app had newer terms: S1 again, then the retry with the new version works.
        p.hub.acceptedTermsVersion = 2
        let gid = try await p.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await p.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        let joiner = try phone()
        do {
            _ = try await joiner.hub.join(link, displayName: "Thandi")
            XCTFail("joined with old terms")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .termsOutdated)
        }
        XCTAssertTrue(joiner.secrets.loadAll().isEmpty)
        joiner.hub.acceptedTermsVersion = 2
        _ = try await joiner.hub.join(link, displayName: "Thandi")
        XCTAssertEqual(joiner.hub.groups.count, 1)
        let redeem = try XCTUnwrap(requests("POST", "/invites/redeem").last?.body)
        XCTAssertEqual(try JSONDecoder().decode(RedeemRequest.self, from: redeem).termsVersion, 2)
    }

    // MARK: - Pro proofs (§10.7)

    func testUpdateProDecisions() async throws {
        let owner = try phone()
        let expires: Int64 = 1_825_149_600
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: .apple(signedTransaction: "fake:pro.yearly:\(expires)"))
        func e5Count() -> Int { requests("PUT", "/pro").count }
        // None holds: nothing sent.
        await owner.hub.updatePro(.apple(signedTransaction: "jws"), productId: "pro.yearly", expiresAt: expires)
        await owner.hub.updatePro(.apple(signedTransaction: "jws"), productId: "pro.yearly", expiresAt: expires + 60)
        await owner.hub.updatePro(.apple(signedTransaction: "jws"), productId: "pro.yearly", expiresAt: nil)
        XCTAssertEqual(e5Count(), 0)
        // 4: a later known expiry.
        await owner.hub.updatePro(.apple(signedTransaction: "fake:pro.yearly:\(expires + 61)"), productId: "pro.yearly", expiresAt: expires + 61)
        XCTAssertEqual(e5Count(), 1)
        // 3: another product.
        await owner.hub.updatePro(.apple(signedTransaction: "fake:pro.lifetime:-"), productId: "pro.lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 2)
        let stored = try JSONDecoder().decode(GroupStatusDTO.self, from: Data(try XCTUnwrap(owner.store.group(gid)?.statusJSON).utf8))
        XCTAssertEqual(stored.pro?.productId, "pro.lifetime")
        XCTAssertNil(stored.pro?.expiresAt)
        await owner.hub.updatePro(.apple(signedTransaction: "x"), productId: "pro.lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 2)
        // 2: a Pro pause.
        server.pause(groupId: gid, reason: "pro_unverified")
        await owner.hub.becameActive()
        XCTAssertTrue(owner.hub.detail(gid)?.isPaused ?? false)
        await owner.hub.updatePro(.apple(signedTransaction: "fake:pro.lifetime:-"), productId: "pro.lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 3)
        XCTAssertFalse(owner.hub.detail(gid)?.isPaused ?? true, "E5 resumed it")
        // 5: a new Google purchase token (Android); the same token again sends nothing.
        await owner.hub.updatePro(.google(productId: "pro_lifetime", purchaseToken: "t1"), productId: "pro_lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 4)
        XCTAssertEqual(owner.store.group(gid)?.proSent, "t1")
        await owner.hub.updatePro(.google(productId: "pro_lifetime", purchaseToken: "t1"), productId: "pro_lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 4)
        await owner.hub.updatePro(.google(productId: "pro_lifetime", purchaseToken: "t2"), productId: "pro_lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 5)
        // A 402 changes nothing on the phone.
        server.proAccepted = false
        await owner.hub.updatePro(.google(productId: "pro_lifetime", purchaseToken: "t3"), productId: "pro_lifetime", expiresAt: nil)
        XCTAssertEqual(e5Count(), 6)
        XCTAssertEqual(owner.store.group(gid)?.proSent, "t2")
        server.proAccepted = true
        // 1: no Pro on record (a seeded group without it); and members never send.
        let seeded = try phone()
        let sid = GroupRandom.bytes(16)
        let key = GroupRandom.bytes(32)
        let keys = GroupKeys(groupKey: key, groupId: sid)
        let pass = Base64URL.encode(GroupRandom.bytes(32))
        let ownerId = GroupRandom.id()
        server.seedGroup(groupId: keys.groupId, joinProof: Base64URL.encode(keys.joinProof),
                         profile: Base64URL.encode(try GroupCrypto.seal(GroupProfile(name: "Old").json(), key: keys.group, aad: GroupAAD.group(keys.groupId))),
                         ownerId: ownerId, ownerPass: pass,
                         ownerProfile: Base64URL.encode(try GroupCrypto.seal(MemberProfile(name: "Sam", colour: 0).json(), key: keys.member, aad: GroupAAD.member(keys.groupId, ownerId))),
                         createdAt: clock.now, pro: nil)
        try seeded.secrets.save(GroupSecret(groupId: keys.groupId, memberId: ownerId, pass: pass, groupKey: Base64URL.encode(key)))
        await seeded.hub.start()
        server.clearLog()
        await seeded.hub.updatePro(.apple(signedTransaction: "jws"), productId: "pro.yearly", expiresAt: nil)
        XCTAssertEqual(e5Count(), 1)
        let (_, member, _) = try await ownerAndMember(name: "Other")
        server.clearLog()
        await member.hub.updatePro(.apple(signedTransaction: "jws"), productId: "pro.monthly", expiresAt: nil)
        XCTAssertEqual(e5Count(), 0)
    }

    // MARK: - Names, colours, reports

    func testNamesColoursAndRename() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        try await member.hub.setDisplayName("Thandi M", colour: 5, groupId: gid)
        try await owner.hub.renameGroup("Kruger 2027", groupId: gid)
        await owner.hub.becameActive()
        let row = try XCTUnwrap(owner.hub.detail(gid)?.members.last)
        XCTAssertEqual(row.name, "Thandi M")
        XCTAssertEqual(row.initials, "TM")
        XCTAssertEqual(row.colourHex, GroupPolicy.palette[5])
        await member.hub.becameActive()
        XCTAssertEqual(member.hub.groups.first?.name, "Kruger 2027")
        // A member can't rename.
        do {
            try await member.hub.renameGroup("Mine", groupId: gid)
            XCTFail("renamed")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .forbidden)
        }
        do {
            try await member.hub.setDisplayName("badword", colour: 1, groupId: gid)
            XCTFail("filtered")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.message, "Please use different words for your name.")
        }
    }

    func testJoinerWithAClashingColourTakesTheLowestFree() async throws {
        // The owner has colour 0; joiners pick at random. Force a clash by retrying until one happens.
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        for attempt in 0..<40 {
            let made = try await owner.hub.createInvite(groupId: gid)
            guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
            let p = try phone()
            _ = try await p.hub.join(link, displayName: "M\(attempt)")
            await p.hub.waitForBackgroundWork()
            await p.hub.becameActive()
            let mine = try XCTUnwrap(p.hub.detail(gid)?.members.first { $0.isYou })
            let others = Set(p.hub.detail(gid)?.members.filter { !$0.isYou }.map(\.colourHex) ?? [])
            if others.count < GroupPolicy.palette.count {
                XCTAssertFalse(others.contains(mine.colourHex), "attempt \(attempt): \(mine.colourHex) clashes")
            }
        }
    }

    func testReportsCarryTheDecryptedCopyWithoutPositions() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        try member.hub.dropPin(groupId: gid, type: .animal, animal: "Rhino", note: "Two adults", lat: -24.98, lon: 31.6)
        await member.hub.waitForBackgroundWork()
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        let pinId = try XCTUnwrap(server.pinIds(groupId: gid).first)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        try await owner.hub.report(groupId: gid, target: .pin(pinId: pinId), reason: .offensive, comment: " Not nice ")
        try await owner.hub.report(groupId: gid, target: .member(memberId: memberId), reason: .harassment, comment: "")
        try await member.hub.report(groupId: gid, target: .group, reason: .other, comment: "line one\nline two")
        let reports = server.reports
        XCTAssertEqual(reports.count, 3)
        XCTAssertEqual(reports[0].targetKind, "pin")
        XCTAssertEqual(reports[0].content, ["type": "animal", "animal": "Rhino", "note": "Two adults", "name": "Thandi"])
        XCTAssertEqual(reports[0].comment, "Not nice")
        XCTAssertEqual(reports[1].content, ["name": "Thandi"])
        XCTAssertEqual(reports[2].targetId, gid)
        XCTAssertEqual(reports[2].content, ["name": "Kruger trip"])
        // Never a position (SD10): only these keys travel.
        for r in requests("POST", "/reports") {
            let body = try XCTUnwrap((try? JSONSerialization.jsonObject(with: r.body ?? Data())) as? [String: Any])
            XCTAssertEqual(Set(body.keys), ["targetKind", "targetId", "reason", "comment", "content"])
            XCTAssertTrue(Set((body["content"] as? [String: Any] ?? [:]).keys).isSubset(of: ["type", "animal", "note", "name"]))
        }
        do {
            try await owner.hub.report(groupId: gid, target: .member(memberId: memberId), reason: .spam, comment: "badword")
            XCTFail("filtered comment sent")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .wordFilter(.reportComment))
        }
    }

    func testReceivedNamesAreMasked() async throws {
        let owner = try Phone(server: server, clock: clock, words: "")
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let made = try await owner.hub.createInvite(groupId: gid)
        guard case .invite(let link) = InviteLink.parse(made.link, domain: "example.invalid") else { return XCTFail("no link") }
        // This phone's list is empty, so it can type the word; the owner's phone masks it.
        let rude = try Phone(server: server, clock: clock, words: "")
        clock.advance(1)
        _ = try await rude.hub.join(link, displayName: "Sipho badword")
        let strict = GroupHub(store: owner.store, secrets: owner.secrets, transport: server, sockets: nil, words: WordFilter(list: "badword\n"),
                              domain: "example.invalid", clock: { [clock] in clock!.now })
        await strict.start()
        await strict.becameActive()
        XCTAssertEqual(strict.detail(gid)?.members.map(\.name), ["Sam", "Sipho •••"])
    }

    // MARK: - Invites

    func testInvitesListAndCancel() async throws {
        let owner = try phone()
        let gid = try await owner.hub.createGroup(name: "Trip", displayName: "Sam", pro: pro)
        let a = try await owner.hub.createInvite(groupId: gid)
        clock.advance(5)
        let b = try await owner.hub.createInvite(groupId: gid)
        let list = try await owner.hub.invites(groupId: gid)
        XCTAssertEqual(list.map(\.inviteId), [b.inviteId, a.inviteId])
        XCTAssertEqual(list.map(\.link), [b.link, a.link])
        XCTAssertEqual(server.pendingInviteCount(groupId: gid), 2)
        try await owner.hub.cancelInvite(a.inviteId, groupId: gid)
        try await owner.hub.cancelInvite(a.inviteId, groupId: gid) // 404: done
        XCTAssertEqual(server.pendingInviteCount(groupId: gid), 1)
        XCTAssertEqual(owner.hub.detail(gid)?.invites?.map(\.inviteId), [b.inviteId])
        guard case .invite(let link) = InviteLink.parse(a.link, domain: "example.invalid") else { return XCTFail("no link") }
        let p = try phone()
        do {
            _ = try await p.hub.preview(link)
            XCTFail("cancelled invite works")
        } catch let e as GroupActionError {
            XCTAssertEqual(e.kind, .inviteNotFound)
        }
        // Expired invites leave the list.
        clock.advance(604_800)
        XCTAssertEqual(owner.hub.detail(gid)?.invites, [])
    }

    // MARK: - Archives (§6.3, §8.8)

    func testArchivedMonthsAreDownloadedOrRelabelled() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        let memberId = try XCTUnwrap(member.hub.detail(gid)?.yourMemberId)
        let w = TrailChunk.windowStart(clock.now) + 600
        point(member, at: w + 1)
        point(member, at: w + 601)
        clock.now = w + 1300
        await member.hub.tick(foreground: false)
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(owner.store.chunks(gid, memberId: memberId).count, 2)
        // The archive pass moves them; the owner had seen them before the pass: relabel, no download.
        clock.advance(10)
        server.archive(groupId: gid, before: w + 1200)
        server.clearLog()
        clock.advance(10)
        await owner.hub.tick(foreground: true)
        XCTAssertTrue(requests("GET", "/archive/").isEmpty)
        XCTAssertEqual(owner.store.chunks(gid, memberId: memberId).count, 2)
        // A phone that never saw them downloads the month.
        let fresh = try Phone(server: server, clock: clock)
        for s in owner.secrets.loadAll() { try fresh.secrets.save(s) }
        await fresh.hub.start()
        XCTAssertEqual(requests("GET", "/archive/").count, 1)
        XCTAssertEqual(fresh.store.chunks(gid, memberId: memberId).map(\.start), [w, w + 600])
        fresh.hub.setShown(gid)
        XCTAssertEqual(fresh.hub.mapContent(now: clock.now)?.trails.first?.segments.first?.count, 2)
        XCTAssertTrue(fresh.store.ops(gid).isEmpty)
    }

    // MARK: - Trails setting and thinning (§9.5)

    func testTrailsSettingAndSegments() async throws {
        let (owner, member, gid) = try await ownerAndMember()
        share(member, gid)
        let w = TrailChunk.windowStart(clock.now) + 600
        point(member, at: w + 1, segment: true)
        point(member, lat: -24.9960, at: w + 61)
        // A gap of more than 600 s starts a new segment.
        point(member, lat: -24.9950, at: w + 1300)
        point(member, lat: -24.9940, at: w + 1360)
        clock.now = w + 1900
        await member.hub.tick(foreground: false)
        owner.hub.setShown(gid)
        await owner.hub.tick(foreground: true)
        XCTAssertEqual(owner.hub.mapContent(now: clock.now)?.trails.first?.segments.map(\.count), [2, 2])
        owner.hub.setTrails(.off, groupId: gid)
        XCTAssertEqual(owner.hub.detail(gid)?.trails, .off)
        XCTAssertTrue(owner.hub.mapContent(now: clock.now)?.trails.isEmpty ?? false)
        owner.hub.setTrails(.day, groupId: gid)
        XCTAssertTrue(owner.hub.mapContent(now: clock.now + 86_400 + 2000)?.trails.isEmpty ?? false, "older than 24 h")
        owner.hub.setTrails(.all, groupId: gid)
        XCTAssertEqual(owner.hub.mapContent(now: clock.now + 86_400 + 2000)?.trails.first?.segments.count, 2)
    }
}
