import Foundation
import XCTest
@testable import SatMapCore

/// Demo mode (docs/groups/SPEC.md §8.15): the seed the walkthroughs and screenshots show.
final class GroupDemoTests: XCTestCase {
    private let now: Int64 = 1_793_613_600
    private let a = GroupDemo.anchor

    private func demo() async throws -> (hub: GroupHub, server: FakeGroupServer, clock: TestClock, store: GroupStore) {
        let clock = TestClock(now)
        let store = try GroupStore(path: ":memory:")
        let (hub, server) = GroupDemo.makeHub(store: store, words: WordFilter(list: "badword\n"), clock: { clock.now })
        await hub.start()
        await hub.becameActive()
        return (hub, server, clock, store)
    }

    func testTheGroupsList() async throws {
        let (hub, _, _, _) = try await demo()
        let groups = hub.groups
        XCTAssertEqual(groups.map(\.name), ["Kruger trip", "Drone team"])
        XCTAssertEqual(groups.map(\.memberCount), [5, 3])
        XCTAssertEqual(groups.map(\.isSharing), [true, false])
        XCTAssertEqual(groups.map(\.isPaused), [false, true])
        XCTAssertEqual(groups.map(\.isShown), [false, false])
        XCTAssertFalse(groups.contains { $0.hasOfferToYou })
        // Nothing of groups on the map until a group is shown (shots 01–27).
        XCTAssertNil(hub.shownGroupId)
        XCTAssertNil(hub.mapContent(now: now))
    }

    func testKrugerTrip() async throws {
        let (hub, server, clock, _) = try await demo()
        let gid = try XCTUnwrap(hub.groups.first?.groupId)
        // S5 opens (step 32).
        await hub.refreshDetail(gid)
        let detail = try XCTUnwrap(hub.detail(gid))
        XCTAssertEqual(detail.yourRole, .owner)
        XCTAssertTrue(detail.hasConsent && detail.isSharing)
        XCTAssertEqual(detail.trails, .day)
        XCTAssertEqual(detail.members.map(\.name), ["Sam", "Thandi", "Pieter", "Lerato", "Sipho"])
        XCTAssertEqual(detail.members.map(\.role), [.owner, .admin, .member, .member, .member])
        XCTAssertEqual(detail.members.map(\.colourHex), [0, 1, 2, 4, 9].map { GroupPolicy.palette[$0] })
        XCTAssertEqual(detail.members.map(\.initials), ["S", "T", "P", "L", "S"])
        XCTAssertEqual(detail.members[1].state, .sharing(at: now - 8))
        XCTAssertEqual(detail.members[4].state, .lastSeen(at: now - 1500))
        // One pending invite, made by you, with its link on this phone.
        let invites = try await hub.invites(groupId: gid)
        XCTAssertEqual(invites.count, 1)
        XCTAssertEqual(invites.first?.createdByName, "Sam")
        XCTAssertNotNil(invites.first?.link.flatMap { InviteLink.parse($0, domain: GroupDemo.domain) == .notInvite ? nil : $0 })

        // Shown (step 36): dots, trails and six pins.
        hub.setShown(gid)
        await hub.tick(foreground: true)
        await hub.waitForBackgroundWork()
        let map = try XCTUnwrap(hub.mapContent(now: clock.now))
        XCTAssertEqual(map.members.count, 4)
        let thandi = try XCTUnwrap(map.members.first { $0.initials == "T" })
        XCTAssertEqual(thandi.position.lat, a.lat - 0.0012, accuracy: 1e-6)
        XCTAssertEqual(thandi.position.lon, a.lon + 0.0010, accuracy: 1e-6)
        XCTAssertEqual(thandi.headingDeg, 120)
        XCTAssertFalse(thandi.isStale)
        XCTAssertEqual(thandi.colourHex, GroupPolicy.palette[1])
        let sipho = try XCTUnwrap(map.members.first { $0.a11yLabel.hasPrefix("Sipho") })
        XCTAssertTrue(sipho.isStale)
        XCTAssertNil(sipho.headingDeg)
        XCTAssertEqual(sipho.a11yLabel, "Sipho, Last seen 25 min ago")
        XCTAssertEqual(map.members.map(\.headingDeg).compactMap { $0 }.sorted(), [30, 120, 270])
        XCTAssertEqual(map.trails.count, 4)
        for trail in map.trails {
            XCTAssertEqual(trail.segments.count, 1)
            XCTAssertGreaterThan(trail.segments[0].count, 5)
            XCTAssertEqual(trail.widthPt, 3)
            // The trail ends at the member's live position and starts about 0.004° away.
            let member = try XCTUnwrap(map.members.first { $0.memberId == trail.memberId })
            let last = try XCTUnwrap(trail.segments[0].last)
            let first = try XCTUnwrap(trail.segments[0].first)
            XCTAssertEqual(last.lat, member.position.lat, accuracy: 2e-6)
            XCTAssertEqual(last.lon, member.position.lon, accuracy: 2e-6)
            XCTAssertEqual(hypot(first.lat - last.lat, first.lon - last.lon), 0.004, accuracy: 0.0002)
        }
        XCTAssertEqual(map.pins.count, 6)
        XCTAssertEqual(Set(map.pins.compactMap(\.knownType)), Set(PinType.allCases))
        let kudu = try XCTUnwrap(map.pins.first { $0.knownType == .animal })
        XCTAssertEqual(kudu.label, "Kudu · 2 h ago")
        XCTAssertEqual(kudu.a11yLabel, "Kudu, dropped by Thandi 2 h ago")
        XCTAssertEqual(kudu.position.lat, a.lat - 0.0006, accuracy: 1e-6)
        let kuduDetail = try XCTUnwrap(hub.pinDetail(groupId: gid, pinId: kudu.pinId))
        XCTAssertEqual(kuduDetail.note, "Three bulls")
        XCTAssertEqual(kuduDetail.expiresAt, kuduDetail.createdAt + 43_200)
        let danger = try XCTUnwrap(map.pins.first { $0.knownType == .danger })
        XCTAssertEqual(hub.pinDetail(groupId: gid, pinId: danger.pinId)?.note, "Loose gravel on the bend")
        XCTAssertEqual(hub.pinDetail(groupId: gid, pinId: danger.pinId)?.dropperName, "Thandi")
        XCTAssertEqual(server.pinIds(groupId: gid).count, 6)
        // Thandi's member screen (step 39).
        let t = try XCTUnwrap(hub.memberDetail(groupId: gid, memberId: thandi.memberId))
        XCTAssertEqual(t.row.name, "Thandi")
        XCTAssertTrue(t.canRemoveAdmin && t.canMakeOwner && t.canRemove)
        XCTAssertEqual(t.live?.lat ?? 0, a.lat - 0.0012, accuracy: 1e-6)
    }

    func testDroneTeamIsPaused() async throws {
        let (hub, _, _, _) = try await demo()
        let gid = try XCTUnwrap(hub.groups.last?.groupId)
        let detail = try XCTUnwrap(hub.detail(gid))
        XCTAssertTrue(detail.isPaused)
        XCTAssertEqual(detail.pausedReason, .proEnded)
        XCTAssertEqual(detail.deleteAt, now + 27 * 86_400)
        XCTAssertEqual(detail.yourRole, .member)
        XCTAssertEqual(detail.members.map(\.name), ["Pieter", "Ayesha", "Sam"])
        XCTAssertEqual(detail.members.map(\.role), [.owner, .admin, .member])
        XCTAssertFalse(detail.isShown)
        // Step 40: shown, it stays paused on the map.
        hub.setShown(gid)
        XCTAssertEqual(hub.mapContent(now: now)?.groupId, gid)
        XCTAssertThrowsError(try hub.dropPin(groupId: gid, type: .water, animal: nil, note: nil, lat: a.lat, lon: a.lon))
    }

    func testInviteLinks() async throws {
        let (hub, _, _, _) = try await demo()
        let weekend = try XCTUnwrap(InviteLink(token: Base64URL.encode(GroupDemo.sequence(0x10, 16) + GroupDemo.sequence(0x20, 16)),
                                               groupKey: GroupDemo.sequence(0x30, 32)))
        XCTAssertEqual(GroupDemo.inviteLink, weekend.url(domain: "example.invalid"))
        XCTAssertEqual(GroupDemo.inviteLink,
                       "https://example.invalid/j/EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8#MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8")
        guard case .invite(let link) = InviteLink.parse(GroupDemo.inviteLink, domain: GroupDemo.domain),
              case .invite(let link2) = InviteLink.parse(GroupDemo.inviteLink2, domain: GroupDemo.domain) else {
            return XCTFail("demo links don't parse")
        }
        XCTAssertEqual(link2.groupId, GroupDemo.sequence(0x60, 16))
        XCTAssertEqual(link2.groupKey, GroupDemo.sequence(0x80, 32))
        XCTAssertEqual(InviteLink.parse(link2.schemeURL, domain: GroupDemo.domain), .invite(link2))
        let preview2 = try await hub.preview(link2)
        XCTAssertEqual(preview2.name, "Braai club")
        XCTAssertEqual(preview2.inviterName, "Bongani")
        // Steps 34–35: paste the link, preview, join; the joined group becomes the shown group.
        let preview = try await hub.preview(link)
        XCTAssertEqual(preview.name, "Weekend ride")
        XCTAssertEqual(preview.inviterName, "Nomsa")
        XCTAssertEqual(preview.memberCount, 3)
        XCTAssertFalse(preview.isPaused)
        XCTAssertEqual(preview.expiresAt, now - 7200 + 604_800)
        let gid = try await hub.join(link, displayName: GroupDemo.displayName)
        XCTAssertEqual(hub.shownGroupId, gid)
        XCTAssertTrue(hub.needsConsent(gid))
        XCTAssertEqual(hub.groups.map(\.name), ["Kruger trip", "Drone team", "Weekend ride"])
        XCTAssertEqual(hub.detail(gid)?.members.map(\.name), ["Nomsa", "Lindiwe", "Johan", "Sam"])
        hub.recordConsent(gid)
        hub.setSharing(true, groupId: gid)
        XCTAssertTrue(hub.detail(gid)?.isSharing ?? false)
    }

    func testCreatingAGroupWithTheFakePro() async throws {
        let (hub, _, _, _) = try await demo()
        let gid = try await hub.createGroup(name: "Weekend braai", displayName: "Sam", pro: GroupDemo.proProof(now: now))
        XCTAssertEqual(hub.detail(gid)?.name, "Weekend braai")
        let made = try await hub.createInvite(groupId: gid)
        XCTAssertTrue(made.link.hasPrefix("https://example.invalid/j/"))
    }

    func testTheSeedIsTheSameEveryRun() {
        let s1 = GroupDemo.seed(server: FakeGroupServer(now: { 1 }), now: now)
        let s2 = GroupDemo.seed(server: FakeGroupServer(now: { 2 }), now: now + 999)
        XCTAssertEqual(s1.secrets, s2.secrets)
        XCTAssertEqual(s1.krugerTrip, s2.krugerTrip)
        XCTAssertEqual(s1.weekendRide, Base64URL.encode(GroupDemo.sequence(0x10, 16)))
        XCTAssertEqual(s1.braaiClub, Base64URL.encode(GroupDemo.sequence(0x60, 16)))
        XCTAssertEqual(s1.madeInvite.link, s2.madeInvite.link)
        XCTAssertEqual(Set(s1.secrets.map(\.groupId)), [s1.krugerTrip, s1.droneTeam])
    }
}
