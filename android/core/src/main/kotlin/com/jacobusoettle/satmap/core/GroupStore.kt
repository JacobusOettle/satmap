package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The phone's copy of its groups (docs/groups/SPEC.md §8.3): one SQLite file with the group settings and cursors,
 *  the members, trail chunks, pins and live positions received (still sealed: they are opened in memory when shown),
 *  the outbox of own trail points (`own_points`) and queued requests (`ops`), the block list and the invites this
 *  phone made. Used only by `GroupHub`. Thread-safe: every call is serialized by a lock, as in `MBTiles`.
 *
 *  SQLite comes from `driver`: `MBTiles.driver` unless given (android.database.sqlite in the app, sqlite-jdbc in the
 *  core tests). ":memory:" keeps the database in memory (demo mode, tests). Close the store when done with it. */
class GroupStore(path: String, driver: SqlDriver = MBTiles.driver) : Closeable {
    /** The Swift `StoreError` cases; the message is the Swift `errorDescription`. */
    sealed class StoreError(val errorDescription: String) : Exception(errorDescription) {
        class Open(val m: String) : StoreError("Could not open the groups database: $m")
        class Sql(val m: String) : StoreError("Groups database error: $m")
    }

    data class StoredGroup(
        var groupId: String,
        var memberId: String,
        var role: String,
        var joinedAt: Long,
        var statusJSON: String?,
        var cursor: Long = 0,
        /** The `serverTime` of the page or tick that last moved `cursor` (§8.8). */
        var cursorAt: Long = 0,
        var sharing: Boolean = false,
        var consentAt: Long? = null,
        var trails: String = "day",
        var shown: Boolean = false,
        /** The group profile's envelope. */
        var profile: ByteArray? = null,
        var profileSeq: Long = 0,
        /** The Play purchase token last accepted by E1, E5 or E17 for this group (§10.7). */
        var proSent: String? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StoredGroup && other.groupId == groupId && other.memberId == memberId && other.role == role &&
                other.joinedAt == joinedAt && other.statusJSON == statusJSON && other.cursor == cursor && other.cursorAt == cursorAt &&
                other.sharing == sharing && other.consentAt == consentAt && other.trails == trails && other.shown == shown &&
                contentEquals(other.profile, profile) && other.profileSeq == profileSeq && other.proSent == proSent

        override fun hashCode(): Int = groupId.hashCode() * 31 + memberId.hashCode()
    }

    data class StoredMember(var memberId: String, var role: String, var joinedAt: Long, var profile: ByteArray, var seq: Long) {
        override fun equals(other: Any?): Boolean =
            other is StoredMember && other.memberId == memberId && other.role == role && other.joinedAt == joinedAt &&
                other.profile.contentEquals(profile) && other.seq == seq

        override fun hashCode(): Int = memberId.hashCode() * 31 + seq.hashCode()
    }

    data class StoredChunk(val memberId: String, val start: Long, val rec: ByteArray, val seq: Long) {
        override fun equals(other: Any?): Boolean =
            other is StoredChunk && other.memberId == memberId && other.start == start && other.rec.contentEquals(rec) && other.seq == seq

        override fun hashCode(): Int = (memberId.hashCode() * 31 + start.hashCode()) * 31 + seq.hashCode()
    }

    data class StoredPin(
        val pinId: String,
        val memberId: String,
        val createdAt: Long,
        val expiresAt: Long?,
        val rec: ByteArray,
        /** 0 while this phone's own new pin waits in `ops`. */
        var seq: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StoredPin && other.pinId == pinId && other.memberId == memberId && other.createdAt == createdAt &&
                other.expiresAt == expiresAt && other.rec.contentEquals(rec) && other.seq == seq

        override fun hashCode(): Int = pinId.hashCode() * 31 + seq.hashCode()
    }

    data class StoredLive(val memberId: String, val at: Long, val rec: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is StoredLive && other.memberId == memberId && other.at == at && other.rec.contentEquals(rec)

        override fun hashCode(): Int = memberId.hashCode() * 31 + at.hashCode()
    }

    /** A window of own trail points waiting to be acknowledged (§4.3): the chunk plaintext (encoding 0), its version
     *  (one more per point added) and the version last acknowledged. */
    data class OwnWindow(val start: Long, val points: ByteArray, val version: Long, val sentVersion: Long, val badTimeTries: Int) {
        override fun equals(other: Any?): Boolean =
            other is OwnWindow && other.start == start && other.points.contentEquals(points) && other.version == version &&
                other.sentVersion == sentVersion && other.badTimeTries == badTimeTries

        override fun hashCode(): Int = (start.hashCode() * 31 + version.hashCode()) * 31 + sentVersion.hashCode()
    }

    data class QueuedOp(val id: Long, val groupId: String, val kind: String, val body: String, val createdAt: Long)

    data class MadeInvite(val inviteId: String, val link: String, val expiresAt: Long)

    /** What applying a page found (§8.8). */
    data class Applied(
        /** Archive months to download (also queued in `ops` as "archive"). */
        val archiveFetches: MutableList<ArchiveFetch> = mutableListOf(),
        /** A `removed member` item for this phone's own member id. */
        var removedMe: Boolean = false,
        var changed: Boolean = false,
        var reset: Boolean = false,
    )

    data class ArchiveFetch(val memberId: String, val month: String, val seq: Long) {
        /** The JSON kept in the "archive" op. */
        fun json(): String = buildJsonObject {
            put("memberId", memberId)
            put("month", month)
            put("seq", seq)
        }.toString()

        companion object {
            /** `json()`'s text, or null. */
            fun decode(json: String): ArchiveFetch? = try {
                val f = GroupJSON.Fields(GroupJSON.parseObject(json.toByteArray(Charsets.UTF_8)))
                ArchiveFetch(f.string("memberId"), f.string("month"), f.long("seq"))
            } catch (e: SerializationException) {
                null
            }
        }
    }

    private var db: SqlDatabase? = null
    private val lock = ReentrantLock()
    private val statements = HashMap<String, SqlStatement>()
    private var inTransaction = false

    /** Opens (creating if needed) the database at `path`. Throws `StoreError` if it can't. */
    init {
        // On iOS the file is also opened so it can be read and written while the phone is locked (after the first
        // unlock since boot); an Android app's own storage already can be.
        val opened = try {
            driver.open(path, true)
        } catch (e: SqlException) {
            throw StoreError.Open(e.message ?: "code ${e.code}")
        }
        db = opened
        opened.busyTimeout(10_000)
        try {
            // One statement at a time (the Swift runs them as one script).
            for (sql in schema) exec(sql)
        } catch (e: StoreError) {
            close()
            throw e
        }
    }

    override fun close() {
        lock.withLock {
            val db = db ?: return
            for (s in statements.values) s.close()
            statements.clear()
            db.close()
            this.db = null
        }
    }

    /** Runs `body` in one transaction (nested calls join the outer one); rolled back if it throws. The lock is held
     *  throughout, so no other thread's statement lands inside it. */
    fun <T> transaction(body: () -> T): T = lock.withLock {
        val outer = !inTransaction
        if (outer) {
            val db = db ?: throw StoreError.Sql("closed")
            if (db.begin() != SqlResult.OK) throw StoreError.Sql(db.errorMessage)
            inTransaction = true
        }
        try {
            val result = body()
            if (outer) {
                inTransaction = false
                val db = db ?: throw StoreError.Sql("closed")
                val rc = db.commit()
                if (rc != SqlResult.OK) {
                    db.rollback()
                    throw StoreError.Sql("commit failed ($rc)")
                }
            }
            result
        } catch (e: Throwable) {
            if (outer && inTransaction) {
                inTransaction = false
                db?.rollback()
            }
            throw e
        }
    }

    // Groups

    private fun storedGroup(s: SqlStatement) = StoredGroup(
        groupId = text(s, 0), memberId = text(s, 1), role = text(s, 2), joinedAt = s.columnLong(3), statusJSON = optionalText(s, 4),
        cursor = s.columnLong(5), cursorAt = s.columnLong(6), sharing = s.columnLong(7) != 0L, consentAt = optionalLong(s, 8),
        trails = text(s, 9), shown = s.columnLong(10) != 0L, profile = optionalBlob(s, 11), profileSeq = s.columnLong(12),
        proSent = optionalText(s, 13),
    )

    fun groups(): List<StoredGroup> = rows("SELECT $groupColumns FROM groups ORDER BY joined_at, group_id", emptyList(), ::storedGroup)

    fun group(groupId: String): StoredGroup? = rows("SELECT $groupColumns FROM groups WHERE group_id = ?", listOf(groupId), ::storedGroup).firstOrNull()

    fun putGroup(g: StoredGroup) {
        run("INSERT OR REPLACE INTO groups (group_id, member_id, role, joined_at, status_json, cursor, cursor_at, sharing, consent_at, trails, shown, profile, profile_seq, pro_sent) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(g.groupId, g.memberId, g.role, g.joinedAt, g.statusJSON, g.cursor, g.cursorAt, if (g.sharing) 1L else 0L, g.consentAt,
                   g.trails, if (g.shown) 1L else 0L, g.profile, g.profileSeq, g.proSent))
    }

    /** Changes a stored group's row; nothing if there is none. */
    fun updateGroup(groupId: String, change: (StoredGroup) -> Unit) = lock.withLock {
        val g = group(groupId) ?: return@withLock
        change(g)
        putGroup(g)
    }

    /** Makes `groupId` the only shown group (null: none). */
    fun setShown(groupId: String?) {
        run("UPDATE groups SET shown = CASE WHEN group_id = ? THEN 1 ELSE 0 END", listOf(groupId))
    }

    /** Deletes everything stored for the group: its row, data, outbox, ops, block list and invites. */
    fun deleteGroup(groupId: String) {
        transactionOrLog {
            for (table in listOf("groups", "members", "chunks", "pins", "live", "own_points", "ops", "blocked", "made_invites")) {
                run("DELETE FROM $table WHERE group_id = ?", listOf(groupId))
            }
        }
    }

    /** For a reset (§8.8): the group's members, chunks, pins and live rows go; the row (settings), block list,
     *  outbox and ops stay; the cursor goes back to 0. */
    fun resetGroupData(groupId: String) {
        transactionOrLog {
            for (table in listOf("members", "chunks", "pins", "live")) {
                run("DELETE FROM $table WHERE group_id = ?", listOf(groupId))
            }
            // The group profile comes again from 0 too, so its change number starts over.
            run("UPDATE groups SET cursor = 0, cursor_at = 0, profile_seq = 0 WHERE group_id = ?", listOf(groupId))
        }
    }

    // Members

    fun members(groupId: String): List<StoredMember> =
        rows("SELECT member_id, role, joined_at, profile, seq FROM members WHERE group_id = ? ORDER BY joined_at, member_id", listOf(groupId)) { s ->
            StoredMember(memberId = text(s, 0), role = text(s, 1), joinedAt = s.columnLong(2), profile = blob(s, 3), seq = s.columnLong(4))
        }

    /** Stores the member unless a copy with a higher `seq` is stored. Returns whether it was stored. */
    fun putMember(groupId: String, m: StoredMember): Boolean = lock.withLock {
        val seq = scalar("SELECT seq FROM members WHERE group_id = ? AND member_id = ?", listOf(groupId, m.memberId))
        if (seq != null && seq > m.seq) return@withLock false
        run("INSERT OR REPLACE INTO members (group_id, member_id, role, joined_at, profile, seq) VALUES (?,?,?,?,?,?)",
            listOf(groupId, m.memberId, m.role, m.joinedAt, m.profile, m.seq))
        true
    }

    /** Deletes the member with their chunks, pins and live position. */
    fun deleteMember(groupId: String, memberId: String) {
        transactionOrLog {
            for (table in listOf("members", "chunks", "pins", "live")) {
                run("DELETE FROM $table WHERE group_id = ? AND member_id = ?", listOf(groupId, memberId))
            }
        }
    }

    // Chunks

    /** The stored chunks, optionally of one member and from a window start on, oldest first. */
    fun chunks(groupId: String, memberId: String? = null, since: Long? = null): List<StoredChunk> {
        var sql = "SELECT member_id, start, rec, seq FROM chunks WHERE group_id = ?"
        val args = mutableListOf<Any?>(groupId)
        if (memberId != null) {
            sql += " AND member_id = ?"
            args.add(memberId)
        }
        if (since != null) {
            sql += " AND start >= ?"
            args.add(since)
        }
        return rows("$sql ORDER BY member_id, start", args) { s ->
            StoredChunk(memberId = text(s, 0), start = s.columnLong(1), rec = blob(s, 2), seq = s.columnLong(3))
        }
    }

    /** One stored chunk's key, without the record. */
    data class ChunkKey(val memberId: String, val start: Long, val seq: Long)

    /** The (member, start, seq) of every stored chunk, without the records. */
    fun chunkKeys(groupId: String): List<ChunkKey> =
        rows("SELECT member_id, start, seq FROM chunks WHERE group_id = ? ORDER BY member_id, start", listOf(groupId)) { s ->
            ChunkKey(text(s, 0), s.columnLong(1), s.columnLong(2))
        }

    /** Keeps the copy with the highest `seq` for each member and window (§8.9); an equal `seq` replaces it. Returns
     *  whether it was stored. */
    fun putChunk(groupId: String, c: StoredChunk): Boolean = lock.withLock {
        val seq = scalar("SELECT seq FROM chunks WHERE group_id = ? AND member_id = ? AND start = ?", listOf(groupId, c.memberId, c.start))
        if (seq != null && seq > c.seq) return@withLock false
        run("INSERT OR REPLACE INTO chunks (group_id, member_id, start, rec, seq) VALUES (?,?,?,?,?)",
            listOf(groupId, c.memberId, c.start, c.rec, c.seq))
        true
    }

    fun deleteChunks(groupId: String, memberId: String) {
        run("DELETE FROM chunks WHERE group_id = ? AND member_id = ?", listOf(groupId, memberId))
    }

    /** Gives the member's stored chunks of a month (UTC) the archive item's `seq` (§6.3). */
    fun relabelChunks(groupId: String, memberId: String, month: String, seq: Long) {
        val range = monthRange(month) ?: return
        run("UPDATE chunks SET seq = ? WHERE group_id = ? AND member_id = ? AND start >= ? AND start < ? AND seq < ?",
            listOf(seq, groupId, memberId, range.first, range.second, seq))
    }

    // Pins

    fun pins(groupId: String): List<StoredPin> =
        rows("SELECT pin_id, member_id, created_at, expires_at, rec, seq FROM pins WHERE group_id = ? ORDER BY created_at, pin_id", listOf(groupId)) { s ->
            StoredPin(pinId = text(s, 0), memberId = text(s, 1), createdAt = s.columnLong(2), expiresAt = optionalLong(s, 3), rec = blob(s, 4),
                      seq = s.columnLong(5))
        }

    /** Stores the pin unless a copy with a higher `seq` is stored. */
    fun putPin(groupId: String, p: StoredPin): Boolean = lock.withLock {
        val seq = scalar("SELECT seq FROM pins WHERE group_id = ? AND pin_id = ?", listOf(groupId, p.pinId))
        if (seq != null && seq > p.seq) return@withLock false
        run("INSERT OR REPLACE INTO pins (group_id, pin_id, member_id, created_at, expires_at, rec, seq) VALUES (?,?,?,?,?,?,?)",
            listOf(groupId, p.pinId, p.memberId, p.createdAt, p.expiresAt, p.rec, p.seq))
        true
    }

    fun deletePin(groupId: String, pinId: String) {
        run("DELETE FROM pins WHERE group_id = ? AND pin_id = ?", listOf(groupId, pinId))
    }

    /** Deletes pins whose expiry has passed; returns how many. */
    fun deleteExpiredPins(groupId: String, now: Long): Int = lock.withLock {
        val n = scalar("SELECT COUNT(*) FROM pins WHERE group_id = ? AND expires_at IS NOT NULL AND expires_at <= ?", listOf(groupId, now)) ?: 0
        if (n > 0) run("DELETE FROM pins WHERE group_id = ? AND expires_at IS NOT NULL AND expires_at <= ?", listOf(groupId, now))
        n.toInt()
    }

    // Live

    fun live(groupId: String): List<StoredLive> =
        rows("SELECT member_id, at, rec FROM live WHERE group_id = ? ORDER BY member_id", listOf(groupId)) { s ->
            StoredLive(memberId = text(s, 0), at = s.columnLong(1), rec = blob(s, 2))
        }

    /** Keeps only the newest live record per member: an older or equal one is ignored. Returns whether it was stored. */
    fun putLive(groupId: String, l: StoredLive): Boolean = lock.withLock {
        val at = scalar("SELECT at FROM live WHERE group_id = ? AND member_id = ?", listOf(groupId, l.memberId))
        if (at != null && at >= l.at) return@withLock false
        run("INSERT OR REPLACE INTO live (group_id, member_id, at, rec) VALUES (?,?,?,?)", listOf(groupId, l.memberId, l.at, l.rec))
        true
    }

    fun deleteLive(groupId: String, memberId: String) {
        run("DELETE FROM live WHERE group_id = ? AND member_id = ?", listOf(groupId, memberId))
    }

    fun deleteAllLive(groupId: String) {
        run("DELETE FROM live WHERE group_id = ?", listOf(groupId))
    }

    // Own points (the outbox)

    fun ownWindows(groupId: String): List<OwnWindow> =
        rows("SELECT start, points, version, sent_version, bad_time_tries FROM own_points WHERE group_id = ? ORDER BY start", listOf(groupId), ::readOwnWindow)

    fun ownWindow(groupId: String, start: Long): OwnWindow? =
        rows("SELECT start, points, version, sent_version, bad_time_tries FROM own_points WHERE group_id = ? AND start = ?", listOf(groupId, start),
             ::readOwnWindow).firstOrNull()

    private fun readOwnWindow(s: SqlStatement): OwnWindow =
        OwnWindow(start = s.columnLong(0), points = blob(s, 1), version = s.columnLong(2), sentVersion = s.columnLong(3),
                  badTimeTries = s.columnLong(4).toInt())

    /** Adds a point to its window (`version + 1`). A point earlier than the window's last one, or past 600 points, is
     *  not added. Returns whether it was added. */
    fun addOwnPoint(groupId: String, point: TrailPoint): Boolean = lock.withLock {
        val start = TrailChunk.windowStart(point.time)
        val existing = ownWindow(groupId, start)
        val points = mutableListOf<TrailPoint>()
        if (existing != null) {
            val decoded = try {
                TrailChunk.decode(existing.points, windowStart = start)
            } catch (e: GroupRecordError) {
                return@withLock false
            }
            points.addAll(decoded)
        }
        val last = points.lastOrNull()
        if (last != null && point.time < last.time) return@withLock false
        if (points.size >= GroupPolicy.maxPointsPerChunk) return@withLock false
        points.add(point)
        val plain = try {
            TrailChunk.encode(points, windowStart = start, compress = false)
        } catch (e: GroupRecordError) {
            return@withLock false
        }
        if (existing != null) {
            run("UPDATE own_points SET points = ?, version = ? WHERE group_id = ? AND start = ?", listOf(plain, existing.version + 1, groupId, start))
        } else {
            run("INSERT INTO own_points (group_id, start, points, version, sent_version, bad_time_tries) VALUES (?,?,?,1,0,0)",
                listOf(groupId, start, plain))
        }
        true
    }

    /** Records that `version` of a window was accepted (§4.3); a closed window whose accepted version is its latest is
     *  deleted. */
    fun markOwnWindowSent(groupId: String, start: Long, version: Long, closed: Boolean) {
        transactionOrLog {
            run("UPDATE own_points SET sent_version = MAX(sent_version, ?), bad_time_tries = 0 WHERE group_id = ? AND start = ?",
                listOf(version, groupId, start))
            if (closed) {
                run("DELETE FROM own_points WHERE group_id = ? AND start = ? AND sent_version >= version", listOf(groupId, start))
            }
        }
    }

    /** Deletes the closed windows whose latest version was accepted (§4.3). A window acknowledged while it was still
     *  open, and given no point after, gets no answer that would delete it. */
    fun deleteAcknowledgedClosedWindows(groupId: String, now: Long) {
        run("DELETE FROM own_points WHERE group_id = ? AND start + ? <= ? AND sent_version >= version",
            listOf(groupId, GroupPolicy.windowSeconds, now))
    }

    fun deleteOwnWindow(groupId: String, start: Long) {
        run("DELETE FROM own_points WHERE group_id = ? AND start = ?", listOf(groupId, start))
    }

    fun setBadTimeTries(groupId: String, start: Long, tries: Int) {
        run("UPDATE own_points SET bad_time_tries = ? WHERE group_id = ? AND start = ?", listOf(tries.toLong(), groupId, start))
    }

    fun deleteOwnPoints(groupId: String) {
        run("DELETE FROM own_points WHERE group_id = ?", listOf(groupId))
    }

    /** Drops queued points that lie inside a pause (§8.5); a window left empty is deleted, a changed one gets a new
     *  version. */
    fun dropOwnPoints(groupId: String, inside: List<GroupStatusDTO.Pause>) {
        if (inside.isEmpty()) return
        transactionOrLog {
            for (w in ownWindows(groupId)) {
                val points = try {
                    TrailChunk.decode(w.points, windowStart = w.start)
                } catch (e: GroupRecordError) {
                    deleteOwnWindow(groupId, w.start)
                    continue
                }
                val kept = points.filter { p -> inside.none { p.time >= it.from && (it.to?.let { to -> p.time < to } ?: true) } }
                if (kept.isEmpty()) {
                    deleteOwnWindow(groupId, w.start)
                } else if (kept.size != points.size) {
                    val plain = try {
                        TrailChunk.encode(kept, windowStart = w.start, compress = false)
                    } catch (e: GroupRecordError) {
                        continue
                    }
                    run("UPDATE own_points SET points = ?, version = ? WHERE group_id = ? AND start = ?", listOf(plain, w.version + 1, groupId, w.start))
                }
            }
        }
    }

    // Ops

    /** Queues an op; returns its id (0 if it couldn't be stored). The insert and `last_insert_rowid()` run in one
     *  transaction: the rowid is per connection, and outside a transaction Android may read on another connection of
     *  its pool than the one that wrote (WAL). */
    fun addOp(groupId: String, kind: String, body: String, createdAt: Long): Long = try {
        transaction {
            run("INSERT INTO ops (group_id, kind, body, created_at) VALUES (?,?,?,?)", listOf(groupId, kind, body, createdAt))
            scalar("SELECT last_insert_rowid()", emptyList()) ?: 0
        }
    } catch (e: StoreError) {
        GroupLog.note("store: an op couldn't be queued (${e.errorDescription})")
        0
    }

    /** The queued ops, in the order they were queued. */
    fun ops(groupId: String? = null): List<QueuedOp> {
        var sql = "SELECT id, group_id, kind, body, created_at FROM ops"
        val args = mutableListOf<Any?>()
        if (groupId != null) {
            sql += " WHERE group_id = ?"
            args.add(groupId)
        }
        return rows("$sql ORDER BY id", args, ::readOp)
    }

    /** The group's first op queued after op `after` (ids only grow) that is not of kind `except`, so a sender walking
     *  the queue also reaches ops queued while it runs. */
    fun nextOp(groupId: String, after: Long, except: String): QueuedOp? =
        rows("SELECT id, group_id, kind, body, created_at FROM ops WHERE group_id = ? AND id > ? AND kind != ? ORDER BY id LIMIT 1",
             listOf(groupId, after, except), ::readOp).firstOrNull()

    private fun readOp(s: SqlStatement): QueuedOp =
        QueuedOp(id = s.columnLong(0), groupId = text(s, 1), kind = text(s, 2), body = text(s, 3), createdAt = s.columnLong(4))

    /** Whether the op is still queued. */
    fun opExists(id: Long): Boolean =
        (scalar("SELECT COUNT(*) FROM ops WHERE id = ?", listOf(id)) ?: 0) > 0

    fun deleteOp(id: Long) {
        run("DELETE FROM ops WHERE id = ?", listOf(id))
    }

    fun updateOp(id: Long, body: String) {
        run("UPDATE ops SET body = ? WHERE id = ?", listOf(body, id))
    }

    fun hasOp(groupId: String, kind: String): Boolean =
        (scalar("SELECT COUNT(*) FROM ops WHERE group_id = ? AND kind = ?", listOf(groupId, kind)) ?: 0) > 0

    // Block list

    fun blocked(groupId: String): Set<String> =
        rows("SELECT member_id FROM blocked WHERE group_id = ?", listOf(groupId)) { text(it, 0) }.toSet()

    fun setBlocked(groupId: String, memberId: String, blocked: Boolean) {
        if (blocked) {
            run("INSERT OR IGNORE INTO blocked (group_id, member_id) VALUES (?,?)", listOf(groupId, memberId))
        } else {
            run("DELETE FROM blocked WHERE group_id = ? AND member_id = ?", listOf(groupId, memberId))
        }
    }

    // Invites made on this phone

    fun madeInvites(groupId: String): List<MadeInvite> =
        rows("SELECT invite_id, link, expires_at FROM made_invites WHERE group_id = ? ORDER BY expires_at", listOf(groupId)) { s ->
            MadeInvite(inviteId = text(s, 0), link = text(s, 1), expiresAt = s.columnLong(2))
        }

    fun putMadeInvite(groupId: String, i: MadeInvite) {
        run("INSERT OR REPLACE INTO made_invites (group_id, invite_id, link, expires_at) VALUES (?,?,?,?)", listOf(groupId, i.inviteId, i.link, i.expiresAt))
    }

    fun deleteMadeInvite(groupId: String, inviteId: String) {
        run("DELETE FROM made_invites WHERE group_id = ? AND invite_id = ?", listOf(groupId, inviteId))
    }

    fun deleteExpiredMadeInvites(now: Long) {
        run("DELETE FROM made_invites WHERE expires_at <= ?", listOf(now))
    }

    // Applying changes (§8.8, §8.9)

    /** Applies a page of changes in one transaction and saves the cursor with `serverTime`. `cursor` and
     *  `cursor_at` from before the page decide each archive item: relabel the stored chunks when
     *  `cursor ≥ maxChunkSeq` and `cursor_at < archivedAt`, otherwise download it (queued as an "archive" op).
     *  Other members' chunks wholly before your `joinedAt` are dropped. A reset deletes the group's data and puts the
     *  cursor back to 0. */
    fun apply(page: ChangesPage, groupId: String): Applied = transaction {
        val g = group(groupId) ?: return@transaction Applied()
        val out = Applied()
        if (page.reset) {
            resetGroupData(groupId)
            out.reset = true
            out.changed = true
            return@transaction out
        }
        val me = g.memberId
        var joinedAt = g.joinedAt
        for (item in page.items) {
            // Items at or below the cursor were applied already (§6.3). A tick computed before a later HTTP page can
            // still carry them; without tombstones, an old pin or member item would bring back what a removal deleted.
            if (item.seq <= g.cursor) continue
            when (item) {
                is ChangeItem.Group -> {
                    val data = Base64URL.decode(item.profile)
                    if (item.seq >= g.profileSeq && data != null) {
                        run("UPDATE groups SET profile = ?, profile_seq = ? WHERE group_id = ? AND profile_seq <= ?", listOf(data, item.seq, groupId, item.seq))
                        out.changed = true
                    }
                }
                is ChangeItem.Member -> {
                    val data = Base64URL.decode(item.profile) ?: continue
                    if (putMember(groupId, StoredMember(memberId = item.memberId, role = item.role, joinedAt = item.joinedAt, profile = data, seq = item.seq))) {
                        out.changed = true
                        if (item.memberId == me) {
                            joinedAt = item.joinedAt
                            run("UPDATE groups SET role = ?, joined_at = ? WHERE group_id = ?", listOf(item.role, item.joinedAt, groupId))
                        }
                    }
                }
                is ChangeItem.Chunk -> {
                    if (item.memberId != me && item.start + GroupPolicy.windowSeconds <= joinedAt) continue
                    val data = Base64URL.decode(item.rec) ?: continue
                    if (putChunk(groupId, StoredChunk(memberId = item.memberId, start = item.start, rec = data, seq = item.seq))) out.changed = true
                }
                is ChangeItem.Archive -> {
                    if (g.cursor >= item.maxChunkSeq && g.cursorAt < item.archivedAt) {
                        relabelChunks(groupId, memberId = item.memberId, month = item.month, seq = item.seq)
                    } else {
                        val fetch = ArchiveFetch(memberId = item.memberId, month = item.month, seq = item.seq)
                        out.archiveFetches.add(fetch)
                        addOp(groupId, kind = "archive", body = fetch.json(), createdAt = page.serverTime ?: 0)
                    }
                }
                is ChangeItem.Pin -> {
                    val data = Base64URL.decode(item.rec) ?: continue
                    if (putPin(groupId, StoredPin(pinId = item.pinId, memberId = item.memberId, createdAt = item.createdAt, expiresAt = item.expiresAt,
                                                  rec = data, seq = item.seq))) {
                        out.changed = true
                    }
                }
                is ChangeItem.Removed -> {
                    when (item.what) {
                        "pin" -> deletePin(groupId, item.id)
                        "trail" -> deleteChunks(groupId, memberId = item.id)
                        "member" -> {
                            deleteMember(groupId, item.id)
                            if (item.id == me) out.removedMe = true
                        }
                        else -> {}
                    }
                    out.changed = true
                }
                is ChangeItem.Unknown -> {}
            }
        }
        // The cursor only moves forward; `cursor_at` is the time of the page that moved it (§8.8).
        if (page.cursor > g.cursor) {
            run("UPDATE groups SET cursor = ?, cursor_at = ? WHERE group_id = ?", listOf(page.cursor, page.serverTime ?: g.cursorAt, groupId))
        }
        out
    }

    /** Stores the windows of a downloaded archive month as chunks with the archive item's `seq` (other members'
     *  windows wholly before your `joinedAt` are left out). */
    fun storeArchive(groupId: String, memberId: String, records: List<GroupArchive.Record>, seq: Long) {
        transaction {
            val g = group(groupId) ?: return@transaction
            for (r in records) {
                if (memberId != g.memberId && r.start + GroupPolicy.windowSeconds <= g.joinedAt) continue
                putChunk(groupId, StoredChunk(memberId = memberId, start = r.start, rec = r.rec, seq = seq))
            }
        }
    }

    // SQLite plumbing

    /** Several writes as one: in a transaction, or one by one (logged) if it can't start. */
    private fun transactionOrLog(body: () -> Unit) {
        try {
            transaction(body)
        } catch (e: StoreError) {
            GroupLog.note("store: transaction failed (${e.errorDescription})")
        }
    }

    private fun exec(sql: String) {
        val db = db ?: throw StoreError.Sql("closed")
        if (db.exec(sql) != SqlResult.OK) throw StoreError.Sql(db.errorMessage)
    }

    private fun statement(sql: String): SqlStatement? {
        statements[sql]?.let { return it }
        val db = db ?: return null
        val s = db.prepare(sql)
        if (s == null) {
            GroupLog.note("store: prepare failed (${db.errorMessage})")
            return null
        }
        statements[sql] = s
        return s
    }

    private fun bind(s: SqlStatement, args: List<Any?>) {
        for ((i, a) in args.withIndex()) {
            val index = i + 1
            when (a) {
                null -> s.bindNull(index)
                is Long -> s.bindLong(index, a)
                is Int -> s.bindLong(index, a.toLong())
                is String -> s.bindText(index, a)
                is ByteArray -> s.bindBlob(index, a)
                else -> throw IllegalArgumentException("Can't bind $a")
            }
        }
    }

    private fun run(sql: String, args: List<Any?> = emptyList()) = lock.withLock {
        val s = statement(sql) ?: return@withLock
        try {
            bind(s, args)
            val rc = s.step()
            if (rc != SqlResult.DONE && rc != SqlResult.ROW) GroupLog.note("store: write failed (${db?.errorMessage})")
        } finally {
            s.reset()
            s.clearBindings()
        }
    }

    private fun <T> rows(sql: String, args: List<Any?>, map: (SqlStatement) -> T): List<T> = lock.withLock {
        val s = statement(sql) ?: return@withLock emptyList()
        try {
            bind(s, args)
            val out = mutableListOf<T>()
            while (s.step() == SqlResult.ROW) out.add(map(s))
            out
        } finally {
            s.reset()
            s.clearBindings()
        }
    }

    private fun scalar(sql: String, args: List<Any?>): Long? = rows(sql, args) { s -> if (s.columnIsNull(0)) null else s.columnLong(0) }.firstOrNull()

    companion object {
        private const val groupColumns = "group_id, member_id, role, joined_at, status_json, cursor, cursor_at, sharing, consent_at, trails, shown, profile, profile_seq, pro_sent"

        private val schema = listOf(
            "PRAGMA journal_mode=WAL",
            "PRAGMA synchronous=NORMAL",
            """CREATE TABLE IF NOT EXISTS groups (group_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, role TEXT NOT NULL,
              joined_at INTEGER NOT NULL, status_json TEXT, cursor INTEGER NOT NULL DEFAULT 0,
              cursor_at INTEGER NOT NULL DEFAULT 0, sharing INTEGER NOT NULL DEFAULT 0, consent_at INTEGER,
              trails TEXT NOT NULL DEFAULT 'day', shown INTEGER NOT NULL DEFAULT 0, profile BLOB,
              profile_seq INTEGER NOT NULL DEFAULT 0, pro_sent TEXT)""",
            """CREATE TABLE IF NOT EXISTS members (group_id TEXT, member_id TEXT, role TEXT, joined_at INTEGER, profile BLOB,
              seq INTEGER, PRIMARY KEY (group_id, member_id))""",
            """CREATE TABLE IF NOT EXISTS chunks (group_id TEXT, member_id TEXT, start INTEGER, rec BLOB, seq INTEGER,
              PRIMARY KEY (group_id, member_id, start))""",
            """CREATE TABLE IF NOT EXISTS pins (group_id TEXT, pin_id TEXT, member_id TEXT, created_at INTEGER,
              expires_at INTEGER, rec BLOB, seq INTEGER, PRIMARY KEY (group_id, pin_id))""",
            """CREATE TABLE IF NOT EXISTS live (group_id TEXT, member_id TEXT, at INTEGER, rec BLOB,
              PRIMARY KEY (group_id, member_id))""",
            """CREATE TABLE IF NOT EXISTS own_points (group_id TEXT, start INTEGER, points BLOB NOT NULL,
              version INTEGER NOT NULL, sent_version INTEGER NOT NULL DEFAULT 0,
              bad_time_tries INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (group_id, start))""",
            """CREATE TABLE IF NOT EXISTS ops (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL,
              kind TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)""",
            "CREATE TABLE IF NOT EXISTS blocked (group_id TEXT, member_id TEXT, PRIMARY KEY (group_id, member_id))",
            """CREATE TABLE IF NOT EXISTS made_invites (group_id TEXT, invite_id TEXT, link TEXT, expires_at INTEGER,
              PRIMARY KEY (group_id, invite_id))""",
        )

        /** Unix seconds of the first second of a "YYYY-MM" month (UTC) and of the next month. */
        fun monthRange(month: String): Pair<Long, Long>? {
            val parts = month.split("-")
            if (month.length != 7 || parts.size != 2) return null
            val y = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            if (m !in 1..12) return null
            fun days(year: Long, month: Long): Long {
                // Days from 1970-01-01 to the first of the month (the civil-from-days inverse, proleptic Gregorian).
                val yy = if (month <= 2) year - 1 else year
                val era = (if (yy >= 0) yy else yy - 399) / 400
                val yoe = yy - era * 400
                val mp = (month + 9) % 12
                val doy = (153 * mp + 2) / 5
                val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
                return era * 146_097 + doe - 719_468
            }
            val next = if (m == 12L) (y + 1) to 1L else y to (m + 1)
            return days(y, m) * 86_400 to days(next.first, next.second) * 86_400
        }

        private fun contentEquals(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == b else a.contentEquals(b)

        private fun text(s: SqlStatement, i: Int): String = s.columnText(i) ?: ""

        private fun optionalText(s: SqlStatement, i: Int): String? = if (s.columnIsNull(i)) null else text(s, i)

        private fun optionalLong(s: SqlStatement, i: Int): Long? = if (s.columnIsNull(i)) null else s.columnLong(i)

        private fun blob(s: SqlStatement, i: Int): ByteArray = s.columnBlob(i) ?: ByteArray(0)

        private fun optionalBlob(s: SqlStatement, i: Int): ByteArray? = if (s.columnIsNull(i)) null else blob(s, i)
    }
}
