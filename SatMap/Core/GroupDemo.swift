import Foundation

/// Demo mode (docs/groups/SPEC.md §8.15): walkthroughs and screenshots run against `FakeGroupServer` with this seed,
/// relative to the anchor A = (−33.918292, 18.921204), the first point of the walkthrough's walk. Everything is the
/// same on every run except the times, which follow `now`.
///
/// 1. "Kruger trip": yours (display name "Sam", colour 0), not shown, sharing on, consent recorded. Thandi (admin),
///    Pieter, Lerato and Sipho (live 25 min old), each with a 2-hour trail of 60 points ending at their live
///    position; one pin of each type; one pending invite made by you.
/// 2. "Drone team": owned by Pieter, you are a member; paused (`pro_ended`), deleted in 27 days. Ayesha is admin.
/// 3. "Weekend ride": not joined; `inviteLink` (owner Nomsa) is the link the walkthrough pastes.
/// 4. "Braai club": not joined; `inviteLink2` (owner Bongani) is used only by the link checks.
enum GroupDemo {
    static let domain = "example.invalid"
    static let anchor = GeoPoint(lat: -33.918292, lon: 18.921204)
    static let displayName = "Sam"
    /// "Kruger trip" was made 45 days ago (you joined "Drone team" 20 days ago, so the list shows Kruger trip first).
    private static let krugerAge: Int64 = 45 * 86_400

    /// "Weekend ride": group id 0x10…0x1f, invite secret 0x20…0x2f, key 0x30…0x4f.
    static let inviteLink = link(groupId: sequence(0x10, 16), secret: sequence(0x20, 16), key: sequence(0x30, 32))
    /// "Braai club": group id 0x60…0x6f, invite secret 0x70…0x7f, key 0x80…0x9f.
    static let inviteLink2 = link(groupId: sequence(0x60, 16), secret: sequence(0x70, 16), key: sequence(0x80, 32))

    /// The proof the demo's fake Pro store hands out: yearly Pro (any proof verifies on the fake server).
    static func proProof(now: Int64) -> ProProof {
        .apple(signedTransaction: "fake:pro.yearly:\(now + 365 * 86_400)")
    }

    /// What the seed made for you.
    struct Seed {
        let secrets: [GroupSecret]
        let krugerTrip: String
        let droneTeam: String
        let weekendRide: String
        let braaiClub: String
        /// The pending invite you made in "Kruger trip".
        let madeInvite: GroupStore.MadeInvite
    }

    /// Fills the fake server with the four groups and returns your memberships.
    @discardableResult
    static func seed(server: FakeGroupServer, now: Int64) -> Seed {
        let a = anchor
        let day: Int64 = 86_400

        // 1. Kruger trip: the older membership, so it is listed first.
        let kruger = DemoGroup(name: "Kruger trip", label: "kruger")
        let sam = kruger.member("Sam")
        server.seedGroup(groupId: kruger.id, joinProof: kruger.joinProof, profile: kruger.profile, ownerId: sam.id, ownerPass: sam.pass,
                         ownerProfile: kruger.memberProfile(sam, colour: 0), createdAt: now - krugerAge,
                         pro: ProInfoDTO(platform: "apple", productId: "pro.yearly", expiresAt: now + 300 * day))
        struct Walker { let name: String; let role: String; let colour: Int; let offset: (Double, Double); let heading: Int?; let age: Int64; let bearing: Double; let joined: Int64 }
        let walkers = [
            Walker(name: "Thandi", role: "admin", colour: 1, offset: (-0.0012, 0.0010), heading: 120, age: 8, bearing: 200, joined: 44 * day),
            Walker(name: "Pieter", role: "member", colour: 2, offset: (0.0008, 0.0016), heading: 270, age: 15, bearing: 300, joined: 43 * day),
            Walker(name: "Lerato", role: "member", colour: 4, offset: (-0.0004, -0.0015), heading: 30, age: 4, bearing: 70, joined: 42 * day),
            Walker(name: "Sipho", role: "member", colour: 9, offset: (0.0018, -0.0006), heading: nil, age: 25 * 60, bearing: 140, joined: 41 * day),
        ]
        var ids: [String: DemoMember] = ["Sam": sam]
        for w in walkers {
            let m = kruger.member(w.name)
            ids[w.name] = m
            server.seedMember(groupId: kruger.id, memberId: m.id, pass: m.pass, role: w.role, joinedAt: now - w.joined,
                              profile: kruger.memberProfile(m, colour: w.colour))
            let end = GeoPoint(lat: a.lat + w.offset.0, lon: a.lon + w.offset.1)
            let at = now - w.age
            for (start, points) in trail(end: end, endTime: at, bearing: w.bearing) {
                if let plain = try? TrailChunk.encode(points, windowStart: start, compress: true),
                   let rec = try? GroupCrypto.seal(plain, key: kruger.keys.trail(m.id), aad: GroupAAD.trail(kruger.id, m.id, start: start)) {
                    server.seedChunk(groupId: kruger.id, memberId: m.id, start: start, rec: Base64URL.encode(rec))
                }
            }
            let live = LivePosition(time: at, lat: MicroDegrees.from(end.lat), lon: MicroDegrees.from(end.lon), accuracy: 5,
                                    heading: w.heading, speed: w.heading == nil ? nil : 140)
            if let rec = try? GroupCrypto.seal(live.encoded(), key: kruger.keys.live(m.id), aad: GroupAAD.live(kruger.id, m.id, at: at)) {
                server.seedLive(groupId: kruger.id, memberId: m.id, at: at, rec: Base64URL.encode(rec))
            }
        }
        let pins: [(type: PinType, animal: String?, note: String?, offset: (Double, Double), by: String, age: Int64)] = [
            (.animal, "Kudu", "Three bulls", (-0.0006, 0.0004), "Thandi", 2 * 3600),
            (.water, nil, nil, (0.0010, -0.0010), "Pieter", 5 * 3600),
            (.shelter, nil, nil, (0.0014, 0.0008), "Lerato", 26 * 3600),
            (.danger, nil, "Loose gravel on the bend", (-0.0015, 0.0002), "Thandi", 50 * 60),
            (.pothole, nil, nil, (-0.0002, 0.0018), "Sipho", 3 * 3600),
            (.police, nil, nil, (0.0004, -0.0018), "Pieter", 40 * 60),
        ]
        for (i, p) in pins.enumerated() {
            guard let dropper = ids[p.by] else { continue }
            let pinId = Base64URL.encode(demoBytes("kruger|pin|\(i)", 16))
            let createdAt = now - p.age
            let expiresAt = p.type == .animal ? createdAt + GroupPolicy.animalPinLifetime : nil
            let record = PinRecord(type: p.type.rawValue, animal: p.animal, note: p.note, lat: MicroDegrees.from(a.lat + p.offset.0),
                                   lon: MicroDegrees.from(a.lon + p.offset.1))
            if let rec = try? GroupCrypto.seal(record.json(), key: kruger.keys.pin,
                                               aad: GroupAAD.pin(kruger.id, pinId: pinId, memberId: dropper.id, createdAt: createdAt, expiresAt: expiresAt)) {
                server.seedPin(groupId: kruger.id, pinId: pinId, memberId: dropper.id, createdAt: createdAt, expiresAt: expiresAt,
                               rec: Base64URL.encode(rec))
            }
        }
        let inviteId = Base64URL.encode(demoBytes("kruger|invite", 16))
        let inviteCreated = now - 3600
        let token = server.seedInvite(groupId: kruger.id, inviteId: inviteId, secret: demoBytes("kruger|invite|secret", 16), createdBy: sam.id,
                                      createdAt: inviteCreated, expiresAt: inviteCreated + 604_800)
        let madeLink = InviteLink(token: token, groupKey: kruger.groupKey)?.url(domain: domain) ?? ""

        // 2. Drone team: paused 3 days ago, so it is deleted in 27 days.
        let drone = DemoGroup(name: "Drone team", label: "drone")
        let dronePieter = drone.member("Pieter")
        let droneAyesha = drone.member("Ayesha")
        let droneSam = drone.member("Sam")
        let pausedAt = now - 3 * day
        server.seedGroup(groupId: drone.id, joinProof: drone.joinProof, profile: drone.profile, ownerId: dronePieter.id, ownerPass: dronePieter.pass,
                         ownerProfile: drone.memberProfile(dronePieter, colour: 2), createdAt: now - 40 * day,
                         pro: ProInfoDTO(platform: "google", productId: "pro", expiresAt: pausedAt))
        server.seedMember(groupId: drone.id, memberId: droneAyesha.id, pass: droneAyesha.pass, role: "admin", joinedAt: now - 39 * day,
                          profile: drone.memberProfile(droneAyesha, colour: 6))
        server.seedMember(groupId: drone.id, memberId: droneSam.id, pass: droneSam.pass, role: "member", joinedAt: now - 20 * day,
                          profile: drone.memberProfile(droneSam, colour: 0))
        server.pause(groupId: drone.id, reason: "pro_ended", at: pausedAt)

        // 3. Weekend ride and 4. Braai club: not joined; fixed invite links.
        let weekend = DemoGroup(name: "Weekend ride", groupId: sequence(0x10, 16), groupKey: sequence(0x30, 32))
        seedInvited(server, weekend, owner: "Nomsa", others: [("Lindiwe", 5), ("Johan", 7)], secret: sequence(0x20, 16), now: now)
        let braai = DemoGroup(name: "Braai club", groupId: sequence(0x60, 16), groupKey: sequence(0x80, 32))
        seedInvited(server, braai, owner: "Bongani", others: [("Zanele", 8)], secret: sequence(0x70, 16), now: now)

        return Seed(secrets: [kruger.secret(sam), drone.secret(droneSam)], krugerTrip: kruger.id, droneTeam: drone.id, weekendRide: weekend.id,
                    braaiClub: braai.id, madeInvite: GroupStore.MadeInvite(inviteId: inviteId, link: madeLink, expiresAt: inviteCreated + 604_800))
    }

    /// A hub in demo mode: the fake server with the seed, memberships in memory, no sockets, and your local settings
    /// for "Kruger trip" (sharing on, consent recorded, not shown) and its invite link. Call `start()` on it as usual.
    static func makeHub(store: GroupStore, words: WordFilter, clock: @escaping () -> Int64) -> (hub: GroupHub, server: FakeGroupServer) {
        let server = FakeGroupServer(now: clock)
        let now = clock()
        let seed = seed(server: server, now: now)
        if let you = seed.secrets.first(where: { $0.groupId == seed.krugerTrip }) {
            store.putGroup(GroupStore.StoredGroup(groupId: seed.krugerTrip, memberId: you.memberId, role: "owner", joinedAt: now - krugerAge,
                                                  statusJSON: nil, sharing: true, consentAt: now - krugerAge, trails: TrailsMode.day.rawValue,
                                                  shown: false))
            store.putMadeInvite(seed.krugerTrip, seed.madeInvite)
        }
        let hub = GroupHub(store: store, secrets: MemoryGroupSecrets(seed.secrets), transport: server, sockets: nil, words: words,
                           domain: domain, clock: clock)
        return (hub, server)
    }

    // MARK: - Building the seed

    /// Bytes `start`, `start + 1`, … (`count` of them).
    static func sequence(_ start: UInt8, _ count: Int) -> Data {
        Data((0..<count).map { start &+ UInt8($0) })
    }

    private static func link(groupId: Data, secret: Data, key: Data) -> String {
        InviteLink(token: Base64URL.encode(groupId + secret), groupKey: key)?.url(domain: domain) ?? ""
    }

    /// Fixed bytes for a label, so the demo's ids, keys and passes are the same on every run.
    private static func demoBytes(_ label: String, _ count: Int) -> Data {
        GroupCrypto.subkey(groupKey: Data(repeating: 0x5A, count: 32), groupId: Data("satmap-demo".utf8), purpose: label).prefix(count)
    }

    private struct DemoMember {
        let id: String
        let pass: String
        let name: String
    }

    private struct DemoGroup {
        let name: String
        let label: String
        let groupKey: Data
        let keys: GroupKeys
        var id: String { keys.groupId }
        var joinProof: String { Base64URL.encode(keys.joinProof) }

        init(name: String, label: String) {
            self.init(name: name, label: label, groupId: GroupDemo.demoBytes("\(label)|group", 16), groupKey: GroupDemo.demoBytes("\(label)|key", 32))
        }

        init(name: String, groupId: Data, groupKey: Data) {
            self.init(name: name, label: Base64URL.encode(groupId), groupId: groupId, groupKey: groupKey)
        }

        private init(name: String, label: String, groupId: Data, groupKey: Data) {
            self.name = name
            self.label = label
            self.groupKey = groupKey
            keys = GroupKeys(groupKey: groupKey, groupId: groupId)
        }

        var profile: String {
            Base64URL.encode((try? GroupCrypto.seal(GroupProfile(name: name).json(), key: keys.group, aad: GroupAAD.group(id))) ?? Data())
        }

        func member(_ name: String) -> DemoMember {
            DemoMember(id: Base64URL.encode(GroupDemo.demoBytes("\(label)|member|\(name)", 16)),
                       pass: Base64URL.encode(GroupDemo.demoBytes("\(label)|pass|\(name)", 32)), name: name)
        }

        func memberProfile(_ m: DemoMember, colour: Int) -> String {
            Base64URL.encode((try? GroupCrypto.seal(MemberProfile(name: m.name, colour: colour).json(), key: keys.member,
                                                    aad: GroupAAD.member(id, m.id))) ?? Data())
        }

        func secret(_ m: DemoMember) -> GroupSecret {
            GroupSecret(groupId: id, memberId: m.id, pass: m.pass, groupKey: Base64URL.encode(groupKey))
        }
    }

    private static func seedInvited(_ server: FakeGroupServer, _ g: DemoGroup, owner: String, others: [(String, Int)], secret: Data, now: Int64) {
        let o = g.member(owner)
        server.seedGroup(groupId: g.id, joinProof: g.joinProof, profile: g.profile, ownerId: o.id, ownerPass: o.pass,
                         ownerProfile: g.memberProfile(o, colour: 0), createdAt: now - 10 * 86_400,
                         pro: ProInfoDTO(platform: "apple", productId: "pro.yearly", expiresAt: now + 200 * 86_400))
        for (i, other) in others.enumerated() {
            let m = g.member(other.0)
            server.seedMember(groupId: g.id, memberId: m.id, pass: m.pass, role: "member", joinedAt: now - Int64(9 - i) * 86_400,
                              profile: g.memberProfile(m, colour: other.1))
        }
        let created = now - 2 * 3600
        server.seedInvite(groupId: g.id, inviteId: Base64URL.encode(demoBytes("\(g.label)|invite", 16)), secret: secret, createdBy: o.id,
                          createdAt: created, expiresAt: created + 604_800)
    }

    /// A 2-hour trail of 60 points (one every 2 minutes) ending at `end` at `endTime`: a straight line from 0.004°
    /// away (towards `bearing` degrees from the end), plus a sine wiggle of 0.0003° across it. Grouped by window.
    private static func trail(end: GeoPoint, endTime: Int64, bearing: Double) -> [(start: Int64, points: [TrailPoint])] {
        let rad = bearing * .pi / 180
        let along = (lat: cos(rad), lon: sin(rad))
        let across = (lat: -sin(rad), lon: cos(rad))
        var windows: [(start: Int64, points: [TrailPoint])] = []
        for i in 0..<60 {
            let t = Double(i) / 59
            let wiggle = 0.0003 * sin(3 * .pi * t)
            let lat = end.lat + (1 - t) * 0.004 * along.lat + wiggle * across.lat
            let lon = end.lon + (1 - t) * 0.004 * along.lon + wiggle * across.lon
            let time = endTime - Int64(59 - i) * 120
            let p = TrailPoint(time: time, lat: MicroDegrees.from(lat), lon: MicroDegrees.from(lon), startsSegment: i == 0)
            let start = TrailChunk.windowStart(time)
            if let last = windows.indices.last, windows[last].start == start {
                windows[last].points.append(p)
            } else {
                windows.append((start, [p]))
            }
        }
        return windows
    }
}
