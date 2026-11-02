import Foundation
import XCTest
@testable import SatMapCore

/// The local group store (docs/groups/SPEC.md §8.3, §8.8, §8.9), as the Android core's GroupStoreTests.
final class GroupStoreTests: XCTestCase {
    private var dir: URL!
    private var store: GroupStore!
    private let gid = "oKGio6SlpqeoqaqrrK2urw"
    private let me = "sLGys7S1tre4ubq7vL2-vw"
    private let other = "0NHS09TV1tfY2drb3N3e3w"

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("satmap-groups-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = try GroupStore(path: dir.appendingPathComponent("groups.sqlite").path)
        store.putGroup(GroupStore.StoredGroup(groupId: gid, memberId: me, role: "member", joinedAt: 10_000, statusJSON: nil))
    }

    override func tearDownWithError() throws {
        store.close()
        try? FileManager.default.removeItem(at: dir)
    }

    private func rec(_ byte: UInt8) -> String {
        Base64URL.encode(Data([0x01] + [UInt8](repeating: byte, count: 40)))
    }

    private func page(_ items: [ChangeItem], cursor: Int64, serverTime: Int64 = 50_000) -> ChangesPage {
        ChangesPage(items: items, cursor: cursor, more: false, serverTime: serverTime)
    }

    func testApplyKeepsTheHighestSeq() throws {
        _ = try store.apply(page([.member(seq: 2, memberId: other, role: "member", joinedAt: 9000, profile: rec(1)),
                                  .chunk(seq: 5, memberId: other, start: 10_200, rec: rec(2)),
                                  .pin(seq: 6, pinId: "p1", memberId: other, createdAt: 10_300, expiresAt: nil, rec: rec(3)),
                                  .group(seq: 1, profile: rec(9))], cursor: 6), groupId: gid)
        // An older copy doesn't replace a newer one; the same item twice does no harm.
        _ = try store.apply(page([.member(seq: 1, memberId: other, role: "admin", joinedAt: 9000, profile: rec(4)),
                                  .chunk(seq: 4, memberId: other, start: 10_200, rec: rec(5)),
                                  .pin(seq: 3, pinId: "p1", memberId: other, createdAt: 10_300, expiresAt: nil, rec: rec(6)),
                                  .chunk(seq: 5, memberId: other, start: 10_200, rec: rec(2))], cursor: 6), groupId: gid)
        XCTAssertEqual(store.members(gid).first?.role, "member")
        XCTAssertEqual(store.members(gid).first?.profile, Base64URL.decode(rec(1)))
        XCTAssertEqual(store.chunks(gid).map(\.rec), [Base64URL.decode(rec(2))!])
        XCTAssertEqual(store.pins(gid).first?.rec, Base64URL.decode(rec(3)))
        XCTAssertEqual(store.group(gid)?.profile, Base64URL.decode(rec(9)))
        XCTAssertEqual(store.group(gid)?.profileSeq, 1)
        // A newer copy does.
        _ = try store.apply(page([.member(seq: 8, memberId: other, role: "admin", joinedAt: 9000, profile: rec(7)),
                                  .chunk(seq: 9, memberId: other, start: 10_200, rec: rec(8)),
                                  .group(seq: 10, profile: rec(10))], cursor: 10, serverTime: 60_000), groupId: gid)
        XCTAssertEqual(store.members(gid).first?.role, "admin")
        XCTAssertEqual(store.chunks(gid).first?.seq, 9)
        XCTAssertEqual(store.group(gid)?.profileSeq, 10)
        XCTAssertEqual(store.group(gid)?.cursor, 10)
        XCTAssertEqual(store.group(gid)?.cursorAt, 60_000)
        // The cursor never moves back.
        _ = try store.apply(page([], cursor: 7, serverTime: 70_000), groupId: gid)
        XCTAssertEqual(store.group(gid)?.cursor, 10)
        XCTAssertEqual(store.group(gid)?.cursorAt, 60_000)
    }

    func testOwnMemberItemUpdatesRoleAndJoinedAt() throws {
        _ = try store.apply(page([.member(seq: 3, memberId: me, role: "admin", joinedAt: 10_005, profile: rec(1))], cursor: 3), groupId: gid)
        XCTAssertEqual(store.group(gid)?.role, "admin")
        XCTAssertEqual(store.group(gid)?.joinedAt, 10_005)
    }

    func testJoinedAtDropAndTrim() throws {
        // joinedAt 10 000: another member's window 9 000–9 600 is wholly before it; 9 600–10 200 straddles it.
        _ = try store.apply(page([.chunk(seq: 1, memberId: other, start: 9000, rec: rec(1)),
                                  .chunk(seq: 2, memberId: other, start: 9600, rec: rec(2)),
                                  .chunk(seq: 3, memberId: me, start: 9000, rec: rec(3))], cursor: 3), groupId: gid)
        XCTAssertEqual(store.chunks(gid).map { "\($0.memberId == me ? "me" : "other")@\($0.start)" }.sorted(), ["me@9000", "other@9600"])
        // Points before joinedAt are cut from the straddling chunk, for other members only.
        let points = [9601, 9999, 10_000, 10_100].map { TrailPoint(time: Int64($0), lat: 0, lon: 0, startsSegment: false) }
        XCTAssertEqual(GroupHub.visible(points, memberId: other, me: me, joinedAt: 10_000).map(\.time), [10_000, 10_100])
        XCTAssertEqual(GroupHub.visible(points, memberId: me, me: me, joinedAt: 10_000).count, 4)
        // An archive download keeps the same rule.
        try store.storeArchive(gid, memberId: other, records: [(8400, Data([1])), (9600, Data([2])), (10_200, Data([3]))], seq: 20)
        XCTAssertEqual(store.chunks(gid, memberId: other).map(\.start), [9600, 10_200])
        XCTAssertEqual(store.chunks(gid, memberId: other).map(\.seq), [20, 20])
    }

    func testRemovals() throws {
        _ = try store.apply(page([.member(seq: 1, memberId: other, role: "member", joinedAt: 9000, profile: rec(1)),
                                  .chunk(seq: 2, memberId: other, start: 10_200, rec: rec(2)),
                                  .chunk(seq: 3, memberId: other, start: 10_800, rec: rec(2)),
                                  .pin(seq: 4, pinId: "p1", memberId: other, createdAt: 10_300, expiresAt: nil, rec: rec(3)),
                                  .pin(seq: 5, pinId: "p2", memberId: other, createdAt: 10_300, expiresAt: nil, rec: rec(3))], cursor: 5), groupId: gid)
        store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 10_400, rec: Data([1])))
        var applied = try store.apply(page([.removed(seq: 6, what: "pin", id: "p1")], cursor: 6), groupId: gid)
        XCTAssertEqual(store.pins(gid).map(\.pinId), ["p2"])
        XCTAssertTrue(applied.changed)
        _ = try store.apply(page([.removed(seq: 7, what: "trail", id: other)], cursor: 7), groupId: gid)
        XCTAssertTrue(store.chunks(gid).isEmpty)
        XCTAssertEqual(store.members(gid).count, 1)
        applied = try store.apply(page([.removed(seq: 8, what: "member", id: other), .removed(seq: 8, what: "member", id: other)], cursor: 8), groupId: gid)
        XCTAssertTrue(store.members(gid).isEmpty)
        XCTAssertTrue(store.pins(gid).isEmpty)
        XCTAssertTrue(store.live(gid).isEmpty)
        XCTAssertFalse(applied.removedMe)
        applied = try store.apply(page([.removed(seq: 9, what: "member", id: me), .unknown(seq: 10)], cursor: 10), groupId: gid)
        XCTAssertTrue(applied.removedMe)
        XCTAssertEqual(store.group(gid)?.cursor, 10)
    }

    func testReset() throws {
        _ = try store.apply(page([.member(seq: 1, memberId: other, role: "member", joinedAt: 9000, profile: rec(1)),
                                  .chunk(seq: 2, memberId: other, start: 10_200, rec: rec(2)),
                                  .pin(seq: 3, pinId: "p1", memberId: other, createdAt: 10_300, expiresAt: nil, rec: rec(3))], cursor: 3), groupId: gid)
        store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 10_400, rec: Data([1])))
        store.setBlocked(gid, other, true)
        store.addOwnPoint(gid, TrailPoint(time: 10_500, lat: 1, lon: 2, startsSegment: true))
        store.addOp(gid, kind: "cleartrail", body: "", createdAt: 1)
        store.updateGroup(gid) { $0.sharing = true }
        let applied = try store.apply(ChangesPage(items: [], cursor: 0, more: false, serverTime: nil, reset: true), groupId: gid)
        XCTAssertTrue(applied.reset)
        XCTAssertTrue(store.members(gid).isEmpty)
        XCTAssertTrue(store.chunks(gid).isEmpty)
        XCTAssertTrue(store.pins(gid).isEmpty)
        XCTAssertTrue(store.live(gid).isEmpty)
        XCTAssertEqual(store.group(gid)?.cursor, 0)
        XCTAssertEqual(store.group(gid)?.cursorAt, 0)
        // Kept: the settings, block list, outbox and ops.
        XCTAssertEqual(store.group(gid)?.sharing, true)
        XCTAssertEqual(store.blocked(gid), [other])
        XCTAssertEqual(store.ownWindows(gid).count, 1)
        XCTAssertEqual(store.ops(gid).map(\.kind), ["cleartrail"])
    }

    func testArchiveRelabelOnlyWhenTheCursorProvesIt() throws {
        let month = "2026-11"
        let start: Int64 = 1_793_613_600
        store.updateGroup(gid) { $0.joinedAt = start - 86_400 }
        _ = try store.apply(page([.chunk(seq: 30, memberId: other, start: start, rec: rec(1)),
                                  .chunk(seq: 39, memberId: other, start: start + 600, rec: rec(2))], cursor: 40, serverTime: 1000), groupId: gid)
        // cursor 40 ≥ maxChunkSeq 39 and cursor_at 1000 < archivedAt 2000: relabel, no download.
        var applied = try store.apply(page([.archive(seq: 41, memberId: other, month: month, maxChunkSeq: 39, archivedAt: 2000)], cursor: 41, serverTime: 3000), groupId: gid)
        XCTAssertTrue(applied.archiveFetches.isEmpty)
        XCTAssertEqual(store.chunks(gid).map(\.seq), [41, 41])
        XCTAssertTrue(store.ops(gid).isEmpty)
        // cursor 41 < maxChunkSeq 45: download.
        applied = try store.apply(page([.archive(seq: 46, memberId: other, month: month, maxChunkSeq: 45, archivedAt: 4000)], cursor: 46, serverTime: 5000), groupId: gid)
        XCTAssertEqual(applied.archiveFetches, [GroupStore.ArchiveFetch(memberId: other, month: month, seq: 46)])
        XCTAssertEqual(store.ops(gid).map(\.kind), ["archive"])
        // cursor 46 ≥ maxChunkSeq 46, but cursor_at 5000 is not before archivedAt 4500: the cursor was reached after the
        // pass, so it may have skipped rows the pass deleted: download.
        applied = try store.apply(page([.archive(seq: 47, memberId: other, month: month, maxChunkSeq: 46, archivedAt: 4500)], cursor: 47, serverTime: 6000), groupId: gid)
        XCTAssertEqual(applied.archiveFetches.count, 1)
        XCTAssertEqual(store.ops(gid).count, 2)
        // Chunks of another month are never relabelled.
        _ = try store.apply(page([.chunk(seq: 48, memberId: other, start: 1_796_083_200, rec: rec(3))], cursor: 48, serverTime: 7000), groupId: gid)
        _ = try store.apply(page([.archive(seq: 49, memberId: other, month: month, maxChunkSeq: 48, archivedAt: 8000)], cursor: 49, serverTime: 9000), groupId: gid)
        XCTAssertEqual(store.chunks(gid).map(\.seq), [49, 49, 48])
    }

    func testMonthRange() {
        XCTAssertEqual(GroupStore.monthRange("2026-11")?.start, 1_793_491_200)
        XCTAssertEqual(GroupStore.monthRange("2026-11")?.end, 1_796_083_200)
        XCTAssertEqual(GroupStore.monthRange("2024-02")?.start, 1_706_745_600)
        XCTAssertEqual(GroupStore.monthRange("2024-02")?.end, 1_709_251_200)
        XCTAssertEqual(GroupStore.monthRange("1970-01")?.start, 0)
        XCTAssertNil(GroupStore.monthRange("2026-13"))
        XCTAssertNil(GroupStore.monthRange("2026-1"))
        XCTAssertEqual(FakeGroupServer.month(1_793_613_600), "2026-11")
        XCTAssertEqual(FakeGroupServer.month(1_796_083_199), "2026-11")
        XCTAssertEqual(FakeGroupServer.month(1_796_083_200), "2026-12")
        XCTAssertEqual(FakeGroupServer.month(1_709_251_199), "2024-02")
    }

    func testOutboxVersions() throws {
        let w: Int64 = 1_793_613_600
        XCTAssertTrue(store.addOwnPoint(gid, TrailPoint(time: w + 5, lat: 1, lon: 2, startsSegment: true)))
        XCTAssertTrue(store.addOwnPoint(gid, TrailPoint(time: w + 9, lat: 3, lon: 4, startsSegment: false)))
        // Earlier than the last point: refused (it would break the chunk).
        XCTAssertFalse(store.addOwnPoint(gid, TrailPoint(time: w + 7, lat: 3, lon: 4, startsSegment: false)))
        XCTAssertTrue(store.addOwnPoint(gid, TrailPoint(time: w + 600, lat: 5, lon: 6, startsSegment: false)))
        var windows = store.ownWindows(gid)
        XCTAssertEqual(windows.map(\.start), [w, w + 600])
        XCTAssertEqual(windows.map(\.version), [2, 1])
        XCTAssertEqual(windows.map(\.sentVersion), [0, 0])
        XCTAssertEqual(try TrailChunk.decode(windows[0].points, windowStart: w).map(\.time), [w + 5, w + 9])
        // The open window stays after it is acknowledged; it is re-sent only when it changes.
        store.markOwnWindowSent(gid, start: w + 600, version: 1, closed: false)
        XCTAssertEqual(store.ownWindows(gid).last?.sentVersion, 1)
        // A closed window acknowledged with an older version stays; with its latest version it goes.
        store.markOwnWindowSent(gid, start: w, version: 1, closed: true)
        XCTAssertEqual(store.ownWindows(gid).count, 2)
        store.markOwnWindowSent(gid, start: w, version: 2, closed: true)
        windows = store.ownWindows(gid)
        XCTAssertEqual(windows.map(\.start), [w + 600])
        // bad_time tries.
        store.setBadTimeTries(gid, start: w + 600, tries: 1)
        XCTAssertEqual(store.ownWindows(gid).first?.badTimeTries, 1)
        // Points inside a pause are dropped; a window left empty goes, a changed one gets a new version.
        store.addOwnPoint(gid, TrailPoint(time: w + 610, lat: 7, lon: 8, startsSegment: false))
        store.addOwnPoint(gid, TrailPoint(time: w + 1300, lat: 7, lon: 8, startsSegment: false))
        store.dropOwnPoints(gid, inside: [(from: w + 605, to: w + 700), (from: w + 1200, to: nil)])
        windows = store.ownWindows(gid)
        XCTAssertEqual(windows.map(\.start), [w + 600])
        XCTAssertEqual(try TrailChunk.decode(windows[0].points, windowStart: w + 600).map(\.time), [w + 600])
        XCTAssertEqual(windows[0].version, 3)
        store.deleteOwnPoints(gid)
        XCTAssertTrue(store.ownWindows(gid).isEmpty)
    }

    func testAcknowledgedWindowsAreSweptOnceClosed() throws {
        let w: Int64 = 1_793_613_600
        for k in 0..<3 { store.addOwnPoint(gid, TrailPoint(time: w + Int64(k) * 600 + 5, lat: 1, lon: 2, startsSegment: false)) }
        store.addOwnPoint("other", TrailPoint(time: w + 5, lat: 1, lon: 2, startsSegment: false))
        XCTAssertTrue(store.hasUnsentOwnWindows(gid))
        XCTAssertEqual(store.ownWindow(gid, start: w + 600)?.version, 1)
        XCTAssertNil(store.ownWindow(gid, start: w + 1800))
        // All three acknowledged while open.
        for k in 0..<3 { store.markOwnWindowSent(gid, start: w + Int64(k) * 600, version: 1, closed: false) }
        XCTAssertFalse(store.hasUnsentOwnWindows(gid))
        XCTAssertEqual(store.ownWindows(gid).count, 3)
        // A window changed since its acknowledgement stays until it is acknowledged again.
        store.addOwnPoint(gid, TrailPoint(time: w + 605, lat: 1, lon: 2, startsSegment: false))
        XCTAssertTrue(store.hasUnsentOwnWindows(gid))
        store.deleteSentOwnWindows(gid, endedBy: w + 1199)
        XCTAssertEqual(store.ownWindows(gid).map(\.start), [w + 600, w + 1200])
        store.deleteSentOwnWindows(gid, endedBy: w + 1800)
        XCTAssertEqual(store.ownWindows(gid).map(\.start), [w + 600])
        XCTAssertEqual(store.ownWindows("other").count, 1, "other groups are untouched")
    }

    func testOpsKeepTheirOrder() {
        let a = store.addOp(gid, kind: "pin", body: "a", createdAt: 3)
        let b = store.addOp(gid, kind: "unpin", body: "b", createdAt: 1)
        let c = store.addOp(gid, kind: "cleartrail", body: "", createdAt: 2)
        store.addOp("other", kind: "pin", body: "x", createdAt: 0)
        XCTAssertEqual(store.ops(gid).map(\.id), [a, b, c])
        XCTAssertEqual(store.ops(gid).map(\.kind), ["pin", "unpin", "cleartrail"])
        XCTAssertEqual(store.ops().count, 4)
        XCTAssertTrue(store.hasOp(gid, kind: "cleartrail"))
        store.updateOp(a, body: "a2")
        store.deleteOp(b)
        XCTAssertEqual(store.ops(gid).map(\.body), ["a2", ""])
        XCTAssertFalse(store.hasOp(gid, kind: "unpin"))
        // One at a time, as stored now: archive downloads are skipped, a deleted op is gone.
        XCTAssertEqual(store.op(a)?.body, "a2")
        XCTAssertNil(store.op(b))
        let archive = store.addOp(gid, kind: "archive", body: "{}", createdAt: 4)
        let d = store.addOp(gid, kind: "profile", body: "p", createdAt: 5)
        XCTAssertEqual(store.nextOp(gid, after: 0)?.id, a)
        XCTAssertEqual(store.nextOp(gid, after: a)?.id, c)
        XCTAssertEqual(store.nextOp(gid, after: c)?.id, d)
        XCTAssertNil(store.nextOp(gid, after: d))
        XCTAssertNotNil(store.op(archive))
    }

    func testBlockListAndInvites() {
        store.setBlocked(gid, other, true)
        store.setBlocked(gid, other, true)
        XCTAssertEqual(store.blocked(gid), [other])
        XCTAssertEqual(store.blocked("x"), [])
        store.setBlocked(gid, other, false)
        XCTAssertEqual(store.blocked(gid), [])
        store.putMadeInvite(gid, GroupStore.MadeInvite(inviteId: "i1", link: "https://example.invalid/j/x#y", expiresAt: 100))
        store.putMadeInvite(gid, GroupStore.MadeInvite(inviteId: "i2", link: "l2", expiresAt: 300))
        store.deleteExpiredMadeInvites(now: 200)
        XCTAssertEqual(store.madeInvites(gid).map(\.inviteId), ["i2"])
        store.deleteMadeInvite(gid, "i2")
        XCTAssertTrue(store.madeInvites(gid).isEmpty)
    }

    func testLiveKeepsTheNewest() {
        XCTAssertTrue(store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 100, rec: Data([1]))))
        XCTAssertFalse(store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 90, rec: Data([2]))))
        XCTAssertFalse(store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 100, rec: Data([3]))))
        XCTAssertTrue(store.putLive(gid, GroupStore.StoredLive(memberId: other, at: 110, rec: Data([4]))))
        XCTAssertEqual(store.live(gid), [GroupStore.StoredLive(memberId: other, at: 110, rec: Data([4]))])
        store.deleteLive(gid, other)
        XCTAssertTrue(store.live(gid).isEmpty)
    }

    func testShownDeleteAndReopen() throws {
        store.putGroup(GroupStore.StoredGroup(groupId: "g2", memberId: me, role: "owner", joinedAt: 20_000, statusJSON: "{}"))
        store.setShown("g2")
        XCTAssertEqual(store.groups().filter(\.shown).map(\.groupId), ["g2"])
        store.setShown(gid)
        XCTAssertEqual(store.groups().filter(\.shown).map(\.groupId), [gid])
        store.setShown(nil)
        XCTAssertTrue(store.groups().allSatisfy { !$0.shown })
        store.addOp("g2", kind: "pin", body: "", createdAt: 0)
        store.setBlocked("g2", other, true)
        store.deleteGroup("g2")
        XCTAssertNil(store.group("g2"))
        XCTAssertTrue(store.ops("g2").isEmpty)
        XCTAssertTrue(store.blocked("g2").isEmpty)
        // Everything is on disk.
        store.updateGroup(gid) { $0.consentAt = 123 }
        store.close()
        store = try GroupStore(path: dir.appendingPathComponent("groups.sqlite").path)
        XCTAssertEqual(store.group(gid)?.consentAt, 123)
        XCTAssertEqual(store.groups().count, 1)
        // In memory, for demo mode.
        let memory = try GroupStore(path: ":memory:")
        memory.putGroup(GroupStore.StoredGroup(groupId: "m", memberId: me, role: "member", joinedAt: 1, statusJSON: nil))
        XCTAssertEqual(memory.groups().count, 1)
    }

    func testTransactionsRollBack() {
        struct Boom: Error {}
        XCTAssertThrowsError(try store.transaction {
            store.addOp(gid, kind: "pin", body: "", createdAt: 0)
            throw Boom()
        })
        XCTAssertTrue(store.ops(gid).isEmpty)
        XCTAssertNoThrow(try store.transaction {
            try store.transaction { store.addOp(gid, kind: "pin", body: "", createdAt: 0) }
        })
        XCTAssertEqual(store.ops(gid).count, 1)
    }
}
