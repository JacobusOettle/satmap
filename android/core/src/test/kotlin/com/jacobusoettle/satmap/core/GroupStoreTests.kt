package com.jacobusoettle.satmap.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The local group store (docs/groups/SPEC.md §8.3, §8.8, §8.9) on the core's `SqlDriver` (sqlite-jdbc here), as the
 *  iPhone core's GroupStoreTests. */
class GroupStoreTests {
    private lateinit var dir: File
    private lateinit var store: GroupStore
    private val gid = "oKGio6SlpqeoqaqrrK2urw"
    private val me = "sLGys7S1tre4ubq7vL2-vw"
    private val other = "0NHS09TV1tfY2drb3N3e3w"

    @Before
    fun setUp() {
        dir = tempDir()
        store = GroupStore(File(dir, "groups.sqlite").path, JdbcSqlDriver)
        store.putGroup(GroupStore.StoredGroup(groupId = gid, memberId = me, role = "member", joinedAt = 10_000, statusJSON = null))
    }

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun rec(byte: Int): String = Base64URL.encode(byteArrayOf(0x01) + ByteArray(40) { byte.toByte() })

    private fun page(items: List<ChangeItem>, cursor: Long, serverTime: Long = 50_000) =
        ChangesPage(items = items, cursor = cursor, more = false, serverTime = serverTime)

    @Test
    fun testApplyKeepsTheHighestSeq() {
        store.apply(page(listOf(ChangeItem.Member(seq = 2, memberId = other, role = "member", joinedAt = 9000, profile = rec(1)),
                                ChangeItem.Chunk(seq = 5, memberId = other, start = 10_200, rec = rec(2)),
                                ChangeItem.Pin(seq = 6, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3)),
                                ChangeItem.Group(seq = 1, profile = rec(9))), cursor = 6), gid)
        // An older copy doesn't replace a newer one; the same item twice does no harm.
        store.apply(page(listOf(ChangeItem.Member(seq = 1, memberId = other, role = "admin", joinedAt = 9000, profile = rec(4)),
                                ChangeItem.Chunk(seq = 4, memberId = other, start = 10_200, rec = rec(5)),
                                ChangeItem.Pin(seq = 3, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(6)),
                                ChangeItem.Chunk(seq = 5, memberId = other, start = 10_200, rec = rec(2))), cursor = 6), gid)
        assertEquals("member", store.members(gid).first().role)
        assertArrayEquals(Base64URL.decode(rec(1)), store.members(gid).first().profile)
        assertEquals(1, store.chunks(gid).size)
        assertArrayEquals(Base64URL.decode(rec(2)), store.chunks(gid).first().rec)
        assertArrayEquals(Base64URL.decode(rec(3)), store.pins(gid).first().rec)
        assertArrayEquals(Base64URL.decode(rec(9)), store.group(gid)?.profile)
        assertEquals(1L, store.group(gid)?.profileSeq)
        // A newer copy does.
        store.apply(page(listOf(ChangeItem.Member(seq = 8, memberId = other, role = "admin", joinedAt = 9000, profile = rec(7)),
                                ChangeItem.Chunk(seq = 9, memberId = other, start = 10_200, rec = rec(8)),
                                ChangeItem.Group(seq = 10, profile = rec(10))), cursor = 10, serverTime = 60_000), gid)
        assertEquals("admin", store.members(gid).first().role)
        assertEquals(9L, store.chunks(gid).first().seq)
        assertEquals(10L, store.group(gid)?.profileSeq)
        assertEquals(10L, store.group(gid)?.cursor)
        assertEquals(60_000L, store.group(gid)?.cursorAt)
        // The cursor never moves back.
        store.apply(page(emptyList(), cursor = 7, serverTime = 70_000), gid)
        assertEquals(10L, store.group(gid)?.cursor)
        assertEquals(60_000L, store.group(gid)?.cursorAt)
        // A page for a group that isn't stored changes nothing.
        assertFalse(store.apply(page(listOf(ChangeItem.Group(seq = 1, profile = rec(1))), cursor = 1), "nope").changed)
    }

    @Test
    fun testOwnMemberItemUpdatesRoleAndJoinedAt() {
        store.apply(page(listOf(ChangeItem.Member(seq = 3, memberId = me, role = "admin", joinedAt = 10_005, profile = rec(1))), cursor = 3), gid)
        assertEquals("admin", store.group(gid)?.role)
        assertEquals(10_005L, store.group(gid)?.joinedAt)
    }

    @Test
    fun testJoinedAtDropAndTrim() {
        // joinedAt 10 000: another member's window 9 000–9 600 is wholly before it; 9 600–10 200 straddles it.
        store.apply(page(listOf(ChangeItem.Chunk(seq = 1, memberId = other, start = 9000, rec = rec(1)),
                                ChangeItem.Chunk(seq = 2, memberId = other, start = 9600, rec = rec(2)),
                                ChangeItem.Chunk(seq = 3, memberId = me, start = 9000, rec = rec(3))), cursor = 3), gid)
        assertEquals(listOf("me@9000", "other@9600"), store.chunks(gid).map { "${if (it.memberId == me) "me" else "other"}@${it.start}" }.sorted())
        // Points before joinedAt are cut from the straddling chunk, for other members only.
        val points = listOf(9601L, 9999, 10_000, 10_100).map { TrailPoint(time = it, lat = 0, lon = 0, startsSegment = false) }
        assertEquals(listOf(10_000L, 10_100L), GroupHub.visible(points, memberId = other, me = me, joinedAt = 10_000).map { it.time })
        assertEquals(4, GroupHub.visible(points, memberId = me, me = me, joinedAt = 10_000).size)
        // An archive download keeps the same rule.
        store.storeArchive(gid, memberId = other, records = listOf(GroupArchive.Record(8400, byteArrayOf(1)), GroupArchive.Record(9600, byteArrayOf(2)),
                                                                   GroupArchive.Record(10_200, byteArrayOf(3))), seq = 20)
        assertEquals(listOf(9600L, 10_200L), store.chunks(gid, memberId = other).map { it.start })
        assertEquals(listOf(20L, 20L), store.chunks(gid, memberId = other).map { it.seq })
        assertEquals(listOf(GroupStore.ChunkKey(me, 9000, 3), GroupStore.ChunkKey(other, 9600, 20), GroupStore.ChunkKey(other, 10_200, 20)),
                     store.chunkKeys(gid).sortedBy { it.memberId != me })
    }

    @Test
    fun testRemovals() {
        store.apply(page(listOf(ChangeItem.Member(seq = 1, memberId = other, role = "member", joinedAt = 9000, profile = rec(1)),
                                ChangeItem.Chunk(seq = 2, memberId = other, start = 10_200, rec = rec(2)),
                                ChangeItem.Chunk(seq = 3, memberId = other, start = 10_800, rec = rec(2)),
                                ChangeItem.Pin(seq = 4, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3)),
                                ChangeItem.Pin(seq = 5, pinId = "p2", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3))), cursor = 5), gid)
        store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 10_400, rec = byteArrayOf(1)))
        var applied = store.apply(page(listOf(ChangeItem.Removed(seq = 6, what = "pin", id = "p1")), cursor = 6), gid)
        assertEquals(listOf("p2"), store.pins(gid).map { it.pinId })
        assertTrue(applied.changed)
        store.apply(page(listOf(ChangeItem.Removed(seq = 7, what = "trail", id = other)), cursor = 7), gid)
        assertTrue(store.chunks(gid).isEmpty())
        assertEquals(1, store.members(gid).size)
        applied = store.apply(page(listOf(ChangeItem.Removed(seq = 8, what = "member", id = other), ChangeItem.Removed(seq = 8, what = "member", id = other)), cursor = 8), gid)
        assertTrue(store.members(gid).isEmpty())
        assertTrue(store.pins(gid).isEmpty())
        assertTrue(store.live(gid).isEmpty())
        assertFalse(applied.removedMe)
        applied = store.apply(page(listOf(ChangeItem.Removed(seq = 9, what = "member", id = me), ChangeItem.Unknown(seq = 10)), cursor = 10), gid)
        assertTrue(applied.removedMe)
        assertEquals(10L, store.group(gid)?.cursor)
    }

    @Test
    fun testItemsAtOrBelowTheCursorAreSkipped() {
        store.apply(page(listOf(ChangeItem.Member(seq = 2, memberId = other, role = "member", joinedAt = 9000, profile = rec(1)),
                                ChangeItem.Pin(seq = 4, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3))), cursor = 4), gid)
        store.apply(page(listOf(ChangeItem.Removed(seq = 6, what = "pin", id = "p1"), ChangeItem.Removed(seq = 7, what = "member", id = other)), cursor = 7), gid)
        // A tick computed before that page (from 3) arrives after it: its old items bring nothing back (no tombstones),
        // its newer ones apply, and the cursor stays.
        val applied = store.apply(page(listOf(ChangeItem.Member(seq = 2, memberId = other, role = "member", joinedAt = 9000, profile = rec(1)),
                                              ChangeItem.Pin(seq = 4, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3)),
                                              ChangeItem.Chunk(seq = 5, memberId = other, start = 10_200, rec = rec(2))), cursor = 5), gid)
        assertFalse(applied.changed)
        assertTrue(store.members(gid).isEmpty())
        assertTrue(store.pins(gid).isEmpty())
        assertTrue(store.chunks(gid).isEmpty())
        assertEquals(7L, store.group(gid)?.cursor)
        store.apply(page(listOf(ChangeItem.Pin(seq = 4, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3)),
                                ChangeItem.Pin(seq = 8, pinId = "p2", memberId = other, createdAt = 10_400, expiresAt = null, rec = rec(4))), cursor = 8), gid)
        assertEquals(listOf("p2"), store.pins(gid).map { it.pinId })
        assertEquals(8L, store.group(gid)?.cursor)
    }

    @Test
    fun testReset() {
        store.apply(page(listOf(ChangeItem.Member(seq = 1, memberId = other, role = "member", joinedAt = 9000, profile = rec(1)),
                                ChangeItem.Chunk(seq = 2, memberId = other, start = 10_200, rec = rec(2)),
                                ChangeItem.Pin(seq = 3, pinId = "p1", memberId = other, createdAt = 10_300, expiresAt = null, rec = rec(3))), cursor = 3), gid)
        store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 10_400, rec = byteArrayOf(1)))
        store.setBlocked(gid, other, true)
        store.addOwnPoint(gid, TrailPoint(time = 10_500, lat = 1, lon = 2, startsSegment = true))
        store.addOp(gid, kind = "cleartrail", body = "", createdAt = 1)
        store.updateGroup(gid) { it.sharing = true }
        val applied = store.apply(ChangesPage(items = emptyList(), cursor = 0, more = false, serverTime = null, reset = true), gid)
        assertTrue(applied.reset)
        assertTrue(store.members(gid).isEmpty())
        assertTrue(store.chunks(gid).isEmpty())
        assertTrue(store.pins(gid).isEmpty())
        assertTrue(store.live(gid).isEmpty())
        assertEquals(0L, store.group(gid)?.cursor)
        assertEquals(0L, store.group(gid)?.cursorAt)
        // Kept: the settings, block list, outbox and ops.
        assertEquals(true, store.group(gid)?.sharing)
        assertEquals(setOf(other), store.blocked(gid))
        assertEquals(1, store.ownWindows(gid).size)
        assertEquals(listOf("cleartrail"), store.ops(gid).map { it.kind })
    }

    @Test
    fun testArchiveRelabelOnlyWhenTheCursorProvesIt() {
        val month = "2026-11"
        val start = 1_793_613_600L
        store.updateGroup(gid) { it.joinedAt = start - 86_400 }
        store.apply(page(listOf(ChangeItem.Chunk(seq = 30, memberId = other, start = start, rec = rec(1)),
                                ChangeItem.Chunk(seq = 39, memberId = other, start = start + 600, rec = rec(2))), cursor = 40, serverTime = 1000), gid)
        // cursor 40 ≥ maxChunkSeq 39 and cursor_at 1000 < archivedAt 2000: relabel, no download.
        var applied = store.apply(page(listOf(ChangeItem.Archive(seq = 41, memberId = other, month = month, maxChunkSeq = 39, archivedAt = 2000)), cursor = 41, serverTime = 3000), gid)
        assertTrue(applied.archiveFetches.isEmpty())
        assertEquals(listOf(41L, 41L), store.chunks(gid).map { it.seq })
        assertTrue(store.ops(gid).isEmpty())
        // cursor 41 < maxChunkSeq 45: download.
        applied = store.apply(page(listOf(ChangeItem.Archive(seq = 46, memberId = other, month = month, maxChunkSeq = 45, archivedAt = 4000)), cursor = 46, serverTime = 5000), gid)
        assertEquals(listOf(GroupStore.ArchiveFetch(memberId = other, month = month, seq = 46)), applied.archiveFetches)
        assertEquals(listOf("archive"), store.ops(gid).map { it.kind })
        assertEquals(GroupStore.ArchiveFetch(memberId = other, month = month, seq = 46), GroupStore.ArchiveFetch.decode(store.ops(gid).first().body))
        // cursor 46 ≥ maxChunkSeq 46, but cursor_at 5000 is not before archivedAt 4500: the cursor was reached after the
        // pass, so it may have skipped rows the pass deleted: download.
        applied = store.apply(page(listOf(ChangeItem.Archive(seq = 47, memberId = other, month = month, maxChunkSeq = 46, archivedAt = 4500)), cursor = 47, serverTime = 6000), gid)
        assertEquals(1, applied.archiveFetches.size)
        assertEquals(2, store.ops(gid).size)
        // Chunks of another month are never relabelled.
        store.apply(page(listOf(ChangeItem.Chunk(seq = 48, memberId = other, start = 1_796_083_200, rec = rec(3))), cursor = 48, serverTime = 7000), gid)
        store.apply(page(listOf(ChangeItem.Archive(seq = 49, memberId = other, month = month, maxChunkSeq = 48, archivedAt = 8000)), cursor = 49, serverTime = 9000), gid)
        assertEquals(listOf(49L, 49L, 48L), store.chunks(gid).map { it.seq })
    }

    @Test
    fun testMonthRange() {
        assertEquals(1_793_491_200L, GroupStore.monthRange("2026-11")?.first)
        assertEquals(1_796_083_200L, GroupStore.monthRange("2026-11")?.second)
        assertEquals(1_706_745_600L, GroupStore.monthRange("2024-02")?.first)
        assertEquals(1_709_251_200L, GroupStore.monthRange("2024-02")?.second)
        assertEquals(0L, GroupStore.monthRange("1970-01")?.first)
        assertNull(GroupStore.monthRange("2026-13"))
        assertNull(GroupStore.monthRange("2026-1"))
        assertEquals("2026-11", FakeGroupServer.month(1_793_613_600))
        assertEquals("2026-11", FakeGroupServer.month(1_796_083_199))
        assertEquals("2026-12", FakeGroupServer.month(1_796_083_200))
        assertEquals("2024-02", FakeGroupServer.month(1_709_251_199))
        assertEquals("1969-12", FakeGroupServer.month(-1))
    }

    @Test
    fun testOutboxVersions() {
        val w = 1_793_613_600L
        assertTrue(store.addOwnPoint(gid, TrailPoint(time = w + 5, lat = 1, lon = 2, startsSegment = true)))
        assertTrue(store.addOwnPoint(gid, TrailPoint(time = w + 9, lat = 3, lon = 4, startsSegment = false)))
        // Earlier than the last point: refused (it would break the chunk).
        assertFalse(store.addOwnPoint(gid, TrailPoint(time = w + 7, lat = 3, lon = 4, startsSegment = false)))
        assertTrue(store.addOwnPoint(gid, TrailPoint(time = w + 600, lat = 5, lon = 6, startsSegment = false)))
        var windows = store.ownWindows(gid)
        assertEquals(listOf(w, w + 600), windows.map { it.start })
        assertEquals(listOf(2L, 1L), windows.map { it.version })
        assertEquals(listOf(0L, 0L), windows.map { it.sentVersion })
        assertEquals(listOf(w + 5, w + 9), TrailChunk.decode(windows[0].points, windowStart = w).map { it.time })
        // The open window stays after it is acknowledged; it is re-sent only when it changes.
        store.markOwnWindowSent(gid, start = w + 600, version = 1, closed = false)
        assertEquals(1L, store.ownWindows(gid).last().sentVersion)
        // A closed window acknowledged with an older version stays; with its latest version it goes.
        store.markOwnWindowSent(gid, start = w, version = 1, closed = true)
        assertEquals(2, store.ownWindows(gid).size)
        store.markOwnWindowSent(gid, start = w, version = 2, closed = true)
        windows = store.ownWindows(gid)
        assertEquals(listOf(w + 600), windows.map { it.start })
        // bad_time tries.
        store.setBadTimeTries(gid, start = w + 600, tries = 1)
        assertEquals(1, store.ownWindows(gid).first().badTimeTries)
        // Points inside a pause are dropped; a window left empty goes, a changed one gets a new version.
        store.addOwnPoint(gid, TrailPoint(time = w + 610, lat = 7, lon = 8, startsSegment = false))
        store.addOwnPoint(gid, TrailPoint(time = w + 1300, lat = 7, lon = 8, startsSegment = false))
        store.dropOwnPoints(gid, inside = listOf(GroupStatusDTO.Pause(from = w + 605, to = w + 700), GroupStatusDTO.Pause(from = w + 1200, to = null)))
        windows = store.ownWindows(gid)
        assertEquals(listOf(w + 600), windows.map { it.start })
        assertEquals(listOf(w + 600), TrailChunk.decode(windows[0].points, windowStart = w + 600).map { it.time })
        assertEquals(3L, windows[0].version)
        // At most 600 points in a window.
        val v = w + 6000
        for (i in 0 until 600) assertTrue(store.addOwnPoint(gid, TrailPoint(time = v + i / 2, lat = i, lon = 0, startsSegment = false)))
        assertFalse(store.addOwnPoint(gid, TrailPoint(time = v + 599, lat = 0, lon = 0, startsSegment = false)))
        store.deleteOwnPoints(gid)
        assertTrue(store.ownWindows(gid).isEmpty())
    }

    @Test
    fun testAcknowledgedClosedWindowsAreDeleted() {
        val w = 1_793_613_600L
        for (k in 0 until 3) assertTrue(store.addOwnPoint(gid, TrailPoint(time = w + k * 600 + 5, lat = 1, lon = 2, startsSegment = false)))
        store.addOwnPoint("other", TrailPoint(time = w + 5, lat = 1, lon = 2, startsSegment = false))
        // w: acknowledged while open, no point after. w + 600: acknowledged while open, then one more point.
        // w + 1200: acknowledged, still open. "other": another group's.
        store.markOwnWindowSent(gid, start = w, version = 1, closed = false)
        store.markOwnWindowSent(gid, start = w + 600, version = 1, closed = false)
        assertTrue(store.addOwnPoint(gid, TrailPoint(time = w + 609, lat = 3, lon = 4, startsSegment = false)))
        store.markOwnWindowSent(gid, start = w + 1200, version = 1, closed = false)
        store.markOwnWindowSent("other", start = w, version = 1, closed = false)
        assertEquals(3, store.ownWindows(gid).size)
        store.deleteAcknowledgedClosedWindows(gid, now = w + 1205)
        assertEquals(listOf(w + 600, w + 1200), store.ownWindows(gid).map { it.start })
        assertEquals(1, store.ownWindows("other").size)
        store.deleteAcknowledgedClosedWindows(gid, now = w + 1800)
        assertEquals(listOf(w + 600), store.ownWindows(gid).map { it.start })
        // One window by its start.
        assertEquals(2L, store.ownWindow(gid, start = w + 600)?.version)
        assertEquals(listOf(w + 605, w + 609), TrailChunk.decode(store.ownWindow(gid, start = w + 600)!!.points, windowStart = w + 600).map { it.time })
        assertNull(store.ownWindow(gid, start = w))
    }

    @Test
    fun testOpsKeepTheirOrder() {
        val a = store.addOp(gid, kind = "pin", body = "a", createdAt = 3)
        val b = store.addOp(gid, kind = "unpin", body = "b", createdAt = 1)
        val c = store.addOp(gid, kind = "cleartrail", body = "", createdAt = 2)
        store.addOp("other", kind = "pin", body = "x", createdAt = 0)
        assertEquals(listOf(a, b, c), store.ops(gid).map { it.id })
        assertEquals(listOf("pin", "unpin", "cleartrail"), store.ops(gid).map { it.kind })
        assertEquals(4, store.ops().size)
        assertTrue(store.hasOp(gid, kind = "cleartrail"))
        store.updateOp(a, body = "a2")
        store.deleteOp(b)
        assertEquals(listOf("a2", ""), store.ops(gid).map { it.body })
        assertFalse(store.hasOp(gid, kind = "unpin"))
        assertTrue(store.opExists(a))
        assertFalse(store.opExists(b))
        // Walking the queue reaches an op queued meanwhile, skipping a kind; ids are never reused.
        val archive = store.addOp(gid, kind = "archive", body = "{}", createdAt = 4)
        assertEquals(a, store.nextOp(gid, after = 0, except = "archive")?.id)
        assertEquals(c, store.nextOp(gid, after = a, except = "archive")?.id)
        assertNull(store.nextOp(gid, after = c, except = "archive"))
        store.deleteOp(archive)
        val d = store.addOp(gid, kind = "unpin", body = "d", createdAt = 5)
        assertTrue(d > archive)
        assertEquals(d, store.nextOp(gid, after = c, except = "archive")?.id)
    }

    @Test
    fun testBlockListAndInvites() {
        store.setBlocked(gid, other, true)
        store.setBlocked(gid, other, true)
        assertEquals(setOf(other), store.blocked(gid))
        assertEquals(emptySet<String>(), store.blocked("x"))
        store.setBlocked(gid, other, false)
        assertEquals(emptySet<String>(), store.blocked(gid))
        store.putMadeInvite(gid, GroupStore.MadeInvite(inviteId = "i1", link = "https://example.invalid/j/x#y", expiresAt = 100))
        store.putMadeInvite(gid, GroupStore.MadeInvite(inviteId = "i2", link = "l2", expiresAt = 300))
        store.deleteExpiredMadeInvites(now = 200)
        assertEquals(listOf("i2"), store.madeInvites(gid).map { it.inviteId })
        store.deleteMadeInvite(gid, "i2")
        assertTrue(store.madeInvites(gid).isEmpty())
    }

    @Test
    fun testLiveKeepsTheNewest() {
        assertTrue(store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 100, rec = byteArrayOf(1))))
        assertFalse(store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 90, rec = byteArrayOf(2))))
        assertFalse(store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 100, rec = byteArrayOf(3))))
        assertTrue(store.putLive(gid, GroupStore.StoredLive(memberId = other, at = 110, rec = byteArrayOf(4))))
        assertEquals(listOf(GroupStore.StoredLive(memberId = other, at = 110, rec = byteArrayOf(4))), store.live(gid))
        store.deleteLive(gid, other)
        assertTrue(store.live(gid).isEmpty())
    }

    @Test
    fun testPinsAndExpiry() {
        assertTrue(store.putPin(gid, GroupStore.StoredPin(pinId = "a", memberId = other, createdAt = 100, expiresAt = 200, rec = byteArrayOf(1), seq = 5)))
        assertFalse(store.putPin(gid, GroupStore.StoredPin(pinId = "a", memberId = other, createdAt = 100, expiresAt = 200, rec = byteArrayOf(2), seq = 4)))
        store.putPin(gid, GroupStore.StoredPin(pinId = "b", memberId = me, createdAt = 150, expiresAt = null, rec = ByteArray(0), seq = 0))
        assertEquals(0, store.deleteExpiredPins(gid, now = 199))
        assertEquals(1, store.deleteExpiredPins(gid, now = 200))
        // A pin without expiry stays; an empty record reads back empty.
        assertEquals(listOf(GroupStore.StoredPin(pinId = "b", memberId = me, createdAt = 150, expiresAt = null, rec = ByteArray(0), seq = 0)), store.pins(gid))
    }

    @Test
    fun testShownDeleteAndReopen() {
        store.putGroup(GroupStore.StoredGroup(groupId = "g2", memberId = me, role = "owner", joinedAt = 20_000, statusJSON = "{}"))
        store.setShown("g2")
        assertEquals(listOf("g2"), store.groups().filter { it.shown }.map { it.groupId })
        store.setShown(gid)
        assertEquals(listOf(gid), store.groups().filter { it.shown }.map { it.groupId })
        store.setShown(null)
        assertTrue(store.groups().none { it.shown })
        store.addOp("g2", kind = "pin", body = "", createdAt = 0)
        store.setBlocked("g2", other, true)
        store.deleteGroup("g2")
        assertNull(store.group("g2"))
        assertTrue(store.ops("g2").isEmpty())
        assertTrue(store.blocked("g2").isEmpty())
        // Everything is on disk.
        store.updateGroup(gid) {
            it.consentAt = 123
            it.profile = byteArrayOf(1, 2)
            it.proSent = "token"
        }
        store.close()
        store = GroupStore(File(dir, "groups.sqlite").path, JdbcSqlDriver)
        assertEquals(123L, store.group(gid)?.consentAt)
        assertArrayEquals(byteArrayOf(1, 2), store.group(gid)?.profile)
        assertEquals("token", store.group(gid)?.proSent)
        assertEquals(1, store.groups().size)
        // In memory, for demo mode.
        val memory = GroupStore(":memory:", JdbcSqlDriver)
        memory.putGroup(GroupStore.StoredGroup(groupId = "m", memberId = me, role = "member", joinedAt = 1, statusJSON = null))
        assertEquals(1, memory.groups().size)
        memory.close()
        // A file that can't be opened.
        assertThrows(GroupStore.StoreError.Open::class.java) { GroupStore(File(dir, "missing/dir/groups.sqlite").path, JdbcSqlDriver) }
    }

    @Test
    fun testTransactionsRollBack() {
        class Boom : Exception()
        assertThrows(Boom::class.java) {
            store.transaction {
                store.addOp(gid, kind = "pin", body = "", createdAt = 0)
                throw Boom()
            }
        }
        assertTrue(store.ops(gid).isEmpty())
        store.transaction {
            store.transaction { store.addOp(gid, kind = "pin", body = "", createdAt = 0) }
        }
        assertEquals(1, store.ops(gid).size)
    }
}
