import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

/// The phone's copy of its groups (docs/groups/SPEC.md §8.3): one SQLite file with the group settings and cursors,
/// the members, trail chunks, pins and live positions received (still sealed: they are opened in memory when shown),
/// the outbox of own trail points (`own_points`) and queued requests (`ops`), the block list and the invites this
/// phone made. Used only by `GroupHub`. Thread-safe: every call is serialized by a lock, as in `MBTiles`.
final class GroupStore {
    enum StoreError: Error, LocalizedError {
        case open(String)
        case sql(String)

        var errorDescription: String? {
            switch self {
            case .open(let m): return "Could not open the groups database: \(m)"
            case .sql(let m): return "Groups database error: \(m)"
            }
        }
    }

    struct StoredGroup: Equatable {
        var groupId: String
        var memberId: String
        var role: String
        var joinedAt: Int64
        var statusJSON: String?
        var cursor: Int64 = 0
        /// The `serverTime` of the page or tick that last moved `cursor` (§8.8).
        var cursorAt: Int64 = 0
        var sharing = false
        var consentAt: Int64?
        var trails = "day"
        var shown = false
        /// The group profile's envelope.
        var profile: Data?
        var profileSeq: Int64 = 0
        /// The Play purchase token last accepted by E1, E5 or E17 for this group (§10.7).
        var proSent: String?
    }

    struct StoredMember: Equatable {
        var memberId: String
        var role: String
        var joinedAt: Int64
        var profile: Data
        var seq: Int64
    }

    struct StoredChunk: Equatable {
        var memberId: String
        var start: Int64
        var rec: Data
        var seq: Int64
    }

    struct StoredPin: Equatable {
        var pinId: String
        var memberId: String
        var createdAt: Int64
        var expiresAt: Int64?
        var rec: Data
        /// 0 while this phone's own new pin waits in `ops`.
        var seq: Int64
    }

    struct StoredLive: Equatable {
        var memberId: String
        var at: Int64
        var rec: Data
    }

    /// A window of own trail points waiting to be acknowledged (§4.3): the chunk plaintext (encoding 0), its version
    /// (one more per point added) and the version last acknowledged.
    struct OwnWindow: Equatable {
        var start: Int64
        var points: Data
        var version: Int64
        var sentVersion: Int64
        var badTimeTries: Int
    }

    struct QueuedOp: Equatable {
        var id: Int64
        var groupId: String
        var kind: String
        var body: String
        var createdAt: Int64
    }

    struct MadeInvite: Equatable {
        var inviteId: String
        var link: String
        var expiresAt: Int64
    }

    /// What applying a page found (§8.8).
    struct Applied: Equatable {
        /// Archive months to download (also queued in `ops` as "archive").
        var archiveFetches: [ArchiveFetch] = []
        /// A `removed member` item for this phone's own member id.
        var removedMe = false
        var changed = false
        var reset = false
    }

    struct ArchiveFetch: Codable, Equatable {
        var memberId: String
        var month: String
        var seq: Int64
    }

    private var db: OpaquePointer?
    private let lock = NSLock()
    private var statements: [String: OpaquePointer] = [:]
    private var inTransaction = false
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Opens (creating if needed) the database at `path`; ":memory:" keeps it in memory (demo mode, tests).
    init(path: String) throws {
        var flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
        #if os(iOS)
        // Readable while the phone is locked after the first unlock: recording and uploads run in the background.
        flags |= SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION
        #endif
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(path, &handle, flags, nil)
        guard rc == SQLITE_OK, let opened = handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "code \(rc)"
            if let h = handle { sqlite3_close(h) }
            throw StoreError.open(msg)
        }
        db = opened
        sqlite3_busy_timeout(opened, 10_000)
        try exec("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            CREATE TABLE IF NOT EXISTS groups (group_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, role TEXT NOT NULL,
              joined_at INTEGER NOT NULL, status_json TEXT, cursor INTEGER NOT NULL DEFAULT 0,
              cursor_at INTEGER NOT NULL DEFAULT 0, sharing INTEGER NOT NULL DEFAULT 0, consent_at INTEGER,
              trails TEXT NOT NULL DEFAULT 'day', shown INTEGER NOT NULL DEFAULT 0, profile BLOB,
              profile_seq INTEGER NOT NULL DEFAULT 0, pro_sent TEXT);
            CREATE TABLE IF NOT EXISTS members (group_id TEXT, member_id TEXT, role TEXT, joined_at INTEGER, profile BLOB,
              seq INTEGER, PRIMARY KEY (group_id, member_id));
            CREATE TABLE IF NOT EXISTS chunks (group_id TEXT, member_id TEXT, start INTEGER, rec BLOB, seq INTEGER,
              PRIMARY KEY (group_id, member_id, start));
            CREATE TABLE IF NOT EXISTS pins (group_id TEXT, pin_id TEXT, member_id TEXT, created_at INTEGER,
              expires_at INTEGER, rec BLOB, seq INTEGER, PRIMARY KEY (group_id, pin_id));
            CREATE TABLE IF NOT EXISTS live (group_id TEXT, member_id TEXT, at INTEGER, rec BLOB,
              PRIMARY KEY (group_id, member_id));
            CREATE TABLE IF NOT EXISTS own_points (group_id TEXT, start INTEGER, points BLOB NOT NULL,
              version INTEGER NOT NULL, sent_version INTEGER NOT NULL DEFAULT 0,
              bad_time_tries INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (group_id, start));
            CREATE TABLE IF NOT EXISTS ops (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL,
              kind TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS blocked (group_id TEXT, member_id TEXT, PRIMARY KEY (group_id, member_id));
            CREATE TABLE IF NOT EXISTS made_invites (group_id TEXT, invite_id TEXT, link TEXT, expires_at INTEGER,
              PRIMARY KEY (group_id, invite_id));
            """)
    }

    deinit {
        close()
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        guard let db = db else { return }
        for (_, s) in statements { sqlite3_finalize(s) }
        statements = [:]
        sqlite3_close(db)
        self.db = nil
    }

    /// Runs `body` in one transaction (nested calls join the outer one); rolled back if it throws.
    func transaction<T>(_ body: () throws -> T) throws -> T {
        lock.lock()
        let outer = !inTransaction
        if outer {
            guard sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else {
                let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "closed"
                lock.unlock()
                throw StoreError.sql(msg)
            }
            inTransaction = true
        }
        lock.unlock()
        do {
            let result = try body()
            if outer {
                lock.lock()
                inTransaction = false
                let rc = sqlite3_exec(db, "COMMIT", nil, nil, nil)
                if rc != SQLITE_OK { sqlite3_exec(db, "ROLLBACK", nil, nil, nil) }
                lock.unlock()
                guard rc == SQLITE_OK else { throw StoreError.sql("commit failed (\(rc))") }
            }
            return result
        } catch {
            if outer {
                lock.lock()
                inTransaction = false
                sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
                lock.unlock()
            }
            throw error
        }
    }

    // MARK: - Groups

    private static let groupColumns = "group_id, member_id, role, joined_at, status_json, cursor, cursor_at, sharing, consent_at, trails, shown, profile, profile_seq, pro_sent"

    private static func storedGroup(_ s: OpaquePointer) -> StoredGroup {
        StoredGroup(groupId: text(s, 0), memberId: text(s, 1), role: text(s, 2), joinedAt: int(s, 3), statusJSON: optionalText(s, 4),
                    cursor: int(s, 5), cursorAt: int(s, 6), sharing: int(s, 7) != 0, consentAt: optionalInt(s, 8),
                    trails: text(s, 9), shown: int(s, 10) != 0, profile: optionalBlob(s, 11), profileSeq: int(s, 12),
                    proSent: optionalText(s, 13))
    }

    func groups() -> [StoredGroup] {
        rows("SELECT \(Self.groupColumns) FROM groups ORDER BY joined_at, group_id", [], Self.storedGroup)
    }

    func group(_ groupId: String) -> StoredGroup? {
        rows("SELECT \(Self.groupColumns) FROM groups WHERE group_id = ?", [.text(groupId)], Self.storedGroup).first
    }

    func putGroup(_ g: StoredGroup) {
        run("INSERT OR REPLACE INTO groups (group_id, member_id, role, joined_at, status_json, cursor, cursor_at, sharing, consent_at, trails, shown, profile, profile_seq, pro_sent) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            [.text(g.groupId), .text(g.memberId), .text(g.role), .int(g.joinedAt), .optionalText(g.statusJSON), .int(g.cursor),
             .int(g.cursorAt), .int(g.sharing ? 1 : 0), .optionalInt(g.consentAt), .text(g.trails), .int(g.shown ? 1 : 0),
             .optionalBlob(g.profile), .int(g.profileSeq), .optionalText(g.proSent)])
    }

    /// Changes a stored group's row; nothing if there is none.
    func updateGroup(_ groupId: String, _ change: (inout StoredGroup) -> Void) {
        guard var g = group(groupId) else { return }
        change(&g)
        putGroup(g)
    }

    /// Makes `groupId` the only shown group (nil: none).
    func setShown(_ groupId: String?) {
        run("UPDATE groups SET shown = CASE WHEN group_id = ? THEN 1 ELSE 0 END", [.optionalText(groupId)])
    }

    /// Deletes everything stored for the group: its row, data, outbox, ops, block list and invites.
    func deleteGroup(_ groupId: String) {
        for table in ["groups", "members", "chunks", "pins", "live", "own_points", "ops", "blocked", "made_invites"] {
            run("DELETE FROM \(table) WHERE group_id = ?", [.text(groupId)])
        }
    }

    /// For a reset (§8.8): the group's members, chunks, pins and live rows go; the row (settings), block list,
    /// outbox and ops stay; the cursor goes back to 0.
    func resetGroupData(_ groupId: String) {
        for table in ["members", "chunks", "pins", "live"] {
            run("DELETE FROM \(table) WHERE group_id = ?", [.text(groupId)])
        }
        // The group profile comes again from 0 too, so its change number starts over.
        run("UPDATE groups SET cursor = 0, cursor_at = 0, profile_seq = 0 WHERE group_id = ?", [.text(groupId)])
    }

    // MARK: - Members

    func members(_ groupId: String) -> [StoredMember] {
        rows("SELECT member_id, role, joined_at, profile, seq FROM members WHERE group_id = ? ORDER BY joined_at, member_id", [.text(groupId)]) { s in
            StoredMember(memberId: text(s, 0), role: text(s, 1), joinedAt: int(s, 2), profile: blob(s, 3), seq: int(s, 4))
        }
    }

    /// Stores the member unless a copy with a higher `seq` is stored. Returns whether it was stored.
    @discardableResult
    func putMember(_ groupId: String, _ m: StoredMember) -> Bool {
        if let seq = scalar("SELECT seq FROM members WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(m.memberId)]), seq > m.seq {
            return false
        }
        run("INSERT OR REPLACE INTO members (group_id, member_id, role, joined_at, profile, seq) VALUES (?,?,?,?,?,?)",
            [.text(groupId), .text(m.memberId), .text(m.role), .int(m.joinedAt), .blob(m.profile), .int(m.seq)])
        return true
    }

    /// Deletes the member with their chunks, pins and live position.
    func deleteMember(_ groupId: String, _ memberId: String) {
        for table in ["members", "chunks", "pins", "live"] {
            run("DELETE FROM \(table) WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(memberId)])
        }
    }

    // MARK: - Chunks

    /// The stored chunks, optionally of one member and from a window start on, oldest first.
    func chunks(_ groupId: String, memberId: String? = nil, since: Int64? = nil) -> [StoredChunk] {
        var sql = "SELECT member_id, start, rec, seq FROM chunks WHERE group_id = ?"
        var args: [Value] = [.text(groupId)]
        if let m = memberId {
            sql += " AND member_id = ?"
            args.append(.text(m))
        }
        if let s = since {
            sql += " AND start >= ?"
            args.append(.int(s))
        }
        return rows(sql + " ORDER BY member_id, start", args) { s in
            StoredChunk(memberId: text(s, 0), start: int(s, 1), rec: blob(s, 2), seq: int(s, 3))
        }
    }

    /// The (member, start, seq) of every stored chunk, without the records.
    func chunkKeys(_ groupId: String) -> [(memberId: String, start: Int64, seq: Int64)] {
        rows("SELECT member_id, start, seq FROM chunks WHERE group_id = ? ORDER BY member_id, start", [.text(groupId)]) { s in
            (text(s, 0), int(s, 1), int(s, 2))
        }
    }

    /// Keeps the copy with the highest `seq` for each member and window (§8.9); an equal `seq` replaces it. Returns
    /// whether it was stored.
    @discardableResult
    func putChunk(_ groupId: String, _ c: StoredChunk) -> Bool {
        if let seq = scalar("SELECT seq FROM chunks WHERE group_id = ? AND member_id = ? AND start = ?",
                            [.text(groupId), .text(c.memberId), .int(c.start)]), seq > c.seq {
            return false
        }
        run("INSERT OR REPLACE INTO chunks (group_id, member_id, start, rec, seq) VALUES (?,?,?,?,?)",
            [.text(groupId), .text(c.memberId), .int(c.start), .blob(c.rec), .int(c.seq)])
        return true
    }

    func deleteChunks(_ groupId: String, memberId: String) {
        run("DELETE FROM chunks WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(memberId)])
    }

    /// Gives the member's stored chunks of a month (UTC) the archive item's `seq` (§6.3).
    func relabelChunks(_ groupId: String, memberId: String, month: String, seq: Int64) {
        guard let range = Self.monthRange(month) else { return }
        run("UPDATE chunks SET seq = ? WHERE group_id = ? AND member_id = ? AND start >= ? AND start < ? AND seq < ?",
            [.int(seq), .text(groupId), .text(memberId), .int(range.start), .int(range.end), .int(seq)])
    }

    /// Unix seconds of the first second of a "YYYY-MM" month (UTC) and of the next month.
    static func monthRange(_ month: String) -> (start: Int64, end: Int64)? {
        let parts = month.split(separator: "-")
        guard month.count == 7, parts.count == 2, let y = Int64(parts[0]), let m = Int64(parts[1]), (1...12).contains(m) else { return nil }
        func days(_ year: Int64, _ month: Int64) -> Int64 {
            // Days from 1970-01-01 to the first of the month (the civil-from-days inverse, proleptic Gregorian).
            let yy = month <= 2 ? year - 1 : year
            let era = (yy >= 0 ? yy : yy - 399) / 400
            let yoe = yy - era * 400
            let mp = (month + 9) % 12
            let doy = (153 * mp + 2) / 5
            let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }
        let next = m == 12 ? (y + 1, Int64(1)) : (y, m + 1)
        return (days(y, m) * 86_400, days(next.0, next.1) * 86_400)
    }

    // MARK: - Pins

    func pins(_ groupId: String) -> [StoredPin] {
        rows("SELECT pin_id, member_id, created_at, expires_at, rec, seq FROM pins WHERE group_id = ? ORDER BY created_at, pin_id", [.text(groupId)]) { s in
            StoredPin(pinId: text(s, 0), memberId: text(s, 1), createdAt: int(s, 2), expiresAt: optionalInt(s, 3), rec: blob(s, 4), seq: int(s, 5))
        }
    }

    /// Stores the pin unless a copy with a higher `seq` is stored.
    @discardableResult
    func putPin(_ groupId: String, _ p: StoredPin) -> Bool {
        if let seq = scalar("SELECT seq FROM pins WHERE group_id = ? AND pin_id = ?", [.text(groupId), .text(p.pinId)]), seq > p.seq {
            return false
        }
        run("INSERT OR REPLACE INTO pins (group_id, pin_id, member_id, created_at, expires_at, rec, seq) VALUES (?,?,?,?,?,?,?)",
            [.text(groupId), .text(p.pinId), .text(p.memberId), .int(p.createdAt), .optionalInt(p.expiresAt), .blob(p.rec), .int(p.seq)])
        return true
    }

    func deletePin(_ groupId: String, _ pinId: String) {
        run("DELETE FROM pins WHERE group_id = ? AND pin_id = ?", [.text(groupId), .text(pinId)])
    }

    /// Deletes pins whose expiry has passed; returns how many.
    @discardableResult
    func deleteExpiredPins(_ groupId: String, now: Int64) -> Int {
        let n = scalar("SELECT COUNT(*) FROM pins WHERE group_id = ? AND expires_at IS NOT NULL AND expires_at <= ?", [.text(groupId), .int(now)]) ?? 0
        if n > 0 { run("DELETE FROM pins WHERE group_id = ? AND expires_at IS NOT NULL AND expires_at <= ?", [.text(groupId), .int(now)]) }
        return Int(n)
    }

    // MARK: - Live

    func live(_ groupId: String) -> [StoredLive] {
        rows("SELECT member_id, at, rec FROM live WHERE group_id = ? ORDER BY member_id", [.text(groupId)]) { s in
            StoredLive(memberId: text(s, 0), at: int(s, 1), rec: blob(s, 2))
        }
    }

    /// Keeps only the newest live record per member: an older or equal one is ignored. Returns whether it was stored.
    @discardableResult
    func putLive(_ groupId: String, _ l: StoredLive) -> Bool {
        if let at = scalar("SELECT at FROM live WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(l.memberId)]), at >= l.at {
            return false
        }
        run("INSERT OR REPLACE INTO live (group_id, member_id, at, rec) VALUES (?,?,?,?)",
            [.text(groupId), .text(l.memberId), .int(l.at), .blob(l.rec)])
        return true
    }

    func deleteLive(_ groupId: String, _ memberId: String) {
        run("DELETE FROM live WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(memberId)])
    }

    func deleteAllLive(_ groupId: String) {
        run("DELETE FROM live WHERE group_id = ?", [.text(groupId)])
    }

    // MARK: - Own points (the outbox)

    private static func ownWindow(_ s: OpaquePointer) -> OwnWindow {
        OwnWindow(start: int(s, 0), points: blob(s, 1), version: int(s, 2), sentVersion: int(s, 3), badTimeTries: Int(int(s, 4)))
    }

    func ownWindows(_ groupId: String) -> [OwnWindow] {
        rows("SELECT start, points, version, sent_version, bad_time_tries FROM own_points WHERE group_id = ? ORDER BY start", [.text(groupId)],
             Self.ownWindow)
    }

    func ownWindow(_ groupId: String, start: Int64) -> OwnWindow? {
        rows("SELECT start, points, version, sent_version, bad_time_tries FROM own_points WHERE group_id = ? AND start = ?",
             [.text(groupId), .int(start)], Self.ownWindow).first
    }

    /// Whether a window holds points that were never acknowledged.
    func hasUnsentOwnWindows(_ groupId: String) -> Bool {
        (scalar("SELECT COUNT(*) FROM own_points WHERE group_id = ? AND version > sent_version", [.text(groupId)]) ?? 0) > 0
    }

    /// Adds a point to its window (`version + 1`). A point earlier than the window's last one, or past 600 points, is
    /// not added. Returns whether it was added.
    @discardableResult
    func addOwnPoint(_ groupId: String, _ point: TrailPoint) -> Bool {
        let start = TrailChunk.windowStart(point.time)
        let existing = ownWindow(groupId, start: start)
        var points: [TrailPoint] = []
        if let e = existing {
            guard let decoded = try? TrailChunk.decode(e.points, windowStart: start) else { return false }
            points = decoded
        }
        if let last = points.last, point.time < last.time { return false }
        guard points.count < GroupPolicy.maxPointsPerChunk else { return false }
        points.append(point)
        guard let plain = try? TrailChunk.encode(points, windowStart: start, compress: false) else { return false }
        if let e = existing {
            run("UPDATE own_points SET points = ?, version = ? WHERE group_id = ? AND start = ?",
                [.blob(plain), .int(e.version + 1), .text(groupId), .int(start)])
        } else {
            run("INSERT INTO own_points (group_id, start, points, version, sent_version, bad_time_tries) VALUES (?,?,?,1,0,0)",
                [.text(groupId), .int(start), .blob(plain)])
        }
        return true
    }

    /// Records that `version` of a window was accepted (§4.3); a closed window whose accepted version is its latest is
    /// deleted.
    func markOwnWindowSent(_ groupId: String, start: Int64, version: Int64, closed: Bool) {
        run("UPDATE own_points SET sent_version = MAX(sent_version, ?), bad_time_tries = 0 WHERE group_id = ? AND start = ?",
            [.int(version), .text(groupId), .int(start)])
        if closed {
            run("DELETE FROM own_points WHERE group_id = ? AND start = ? AND sent_version >= version", [.text(groupId), .int(start)])
        }
    }

    /// Deletes the windows that ended at or before `time` and whose latest version was acknowledged (§4.3), such as
    /// one whose last upload went while it was still open.
    func deleteSentOwnWindows(_ groupId: String, endedBy time: Int64) {
        run("DELETE FROM own_points WHERE group_id = ? AND sent_version >= version AND start + ? <= ?",
            [.text(groupId), .int(GroupPolicy.windowSeconds), .int(time)])
    }

    func deleteOwnWindow(_ groupId: String, start: Int64) {
        run("DELETE FROM own_points WHERE group_id = ? AND start = ?", [.text(groupId), .int(start)])
    }

    func setBadTimeTries(_ groupId: String, start: Int64, tries: Int) {
        run("UPDATE own_points SET bad_time_tries = ? WHERE group_id = ? AND start = ?", [.int(Int64(tries)), .text(groupId), .int(start)])
    }

    func deleteOwnPoints(_ groupId: String) {
        run("DELETE FROM own_points WHERE group_id = ?", [.text(groupId)])
    }

    /// Drops queued points that lie inside a pause (§8.5); a window left empty is deleted, a changed one gets a new
    /// version.
    func dropOwnPoints(_ groupId: String, inside pauses: [(from: Int64, to: Int64?)]) {
        guard !pauses.isEmpty else { return }
        for w in ownWindows(groupId) {
            guard let points = try? TrailChunk.decode(w.points, windowStart: w.start) else {
                deleteOwnWindow(groupId, start: w.start)
                continue
            }
            let kept = points.filter { p in !pauses.contains { p.time >= $0.from && ($0.to.map { p.time < $0 } ?? true) } }
            if kept.isEmpty {
                deleteOwnWindow(groupId, start: w.start)
            } else if kept.count != points.count, let plain = try? TrailChunk.encode(kept, windowStart: w.start, compress: false) {
                run("UPDATE own_points SET points = ?, version = ? WHERE group_id = ? AND start = ?",
                    [.blob(plain), .int(w.version + 1), .text(groupId), .int(w.start)])
            }
        }
    }

    // MARK: - Ops

    @discardableResult
    func addOp(_ groupId: String, kind: String, body: String, createdAt: Int64) -> Int64 {
        run("INSERT INTO ops (group_id, kind, body, created_at) VALUES (?,?,?,?)", [.text(groupId), .text(kind), .text(body), .int(createdAt)])
        lock.lock()
        defer { lock.unlock() }
        return db.map { sqlite3_last_insert_rowid($0) } ?? 0
    }

    private static func queuedOp(_ s: OpaquePointer) -> QueuedOp {
        QueuedOp(id: int(s, 0), groupId: text(s, 1), kind: text(s, 2), body: text(s, 3), createdAt: int(s, 4))
    }

    /// The queued ops, in the order they were queued.
    func ops(_ groupId: String? = nil) -> [QueuedOp] {
        var sql = "SELECT id, group_id, kind, body, created_at FROM ops"
        var args: [Value] = []
        if let g = groupId {
            sql += " WHERE group_id = ?"
            args.append(.text(g))
        }
        return rows(sql + " ORDER BY id", args, Self.queuedOp)
    }

    /// The op as it is stored now; nil once it was deleted.
    func op(_ id: Int64) -> QueuedOp? {
        rows("SELECT id, group_id, kind, body, created_at FROM ops WHERE id = ?", [.int(id)], Self.queuedOp).first
    }

    /// The group's first queued op after `id` that is not an archive download, as it is stored now.
    func nextOp(_ groupId: String, after id: Int64) -> QueuedOp? {
        rows("SELECT id, group_id, kind, body, created_at FROM ops WHERE group_id = ? AND id > ? AND kind != 'archive' ORDER BY id LIMIT 1",
             [.text(groupId), .int(id)], Self.queuedOp).first
    }

    func deleteOp(_ id: Int64) {
        run("DELETE FROM ops WHERE id = ?", [.int(id)])
    }

    func updateOp(_ id: Int64, body: String) {
        run("UPDATE ops SET body = ? WHERE id = ?", [.text(body), .int(id)])
    }

    func hasOp(_ groupId: String, kind: String) -> Bool {
        (scalar("SELECT COUNT(*) FROM ops WHERE group_id = ? AND kind = ?", [.text(groupId), .text(kind)]) ?? 0) > 0
    }

    // MARK: - Block list

    func blocked(_ groupId: String) -> Set<String> {
        Set(rows("SELECT member_id FROM blocked WHERE group_id = ?", [.text(groupId)]) { text($0, 0) })
    }

    func setBlocked(_ groupId: String, _ memberId: String, _ blocked: Bool) {
        if blocked {
            run("INSERT OR IGNORE INTO blocked (group_id, member_id) VALUES (?,?)", [.text(groupId), .text(memberId)])
        } else {
            run("DELETE FROM blocked WHERE group_id = ? AND member_id = ?", [.text(groupId), .text(memberId)])
        }
    }

    // MARK: - Invites made on this phone

    func madeInvites(_ groupId: String) -> [MadeInvite] {
        rows("SELECT invite_id, link, expires_at FROM made_invites WHERE group_id = ? ORDER BY expires_at", [.text(groupId)]) { s in
            MadeInvite(inviteId: text(s, 0), link: text(s, 1), expiresAt: int(s, 2))
        }
    }

    func putMadeInvite(_ groupId: String, _ i: MadeInvite) {
        run("INSERT OR REPLACE INTO made_invites (group_id, invite_id, link, expires_at) VALUES (?,?,?,?)",
            [.text(groupId), .text(i.inviteId), .text(i.link), .int(i.expiresAt)])
    }

    func deleteMadeInvite(_ groupId: String, _ inviteId: String) {
        run("DELETE FROM made_invites WHERE group_id = ? AND invite_id = ?", [.text(groupId), .text(inviteId)])
    }

    func deleteExpiredMadeInvites(now: Int64) {
        run("DELETE FROM made_invites WHERE expires_at <= ?", [.int(now)])
    }

    // MARK: - Applying changes (§8.8, §8.9)

    /// Applies a page of changes in one transaction and saves the cursor with `serverTime`. `cursor` and
    /// `cursor_at` from before the page decide each archive item: relabel the stored chunks when
    /// `cursor ≥ maxChunkSeq` and `cursor_at < archivedAt`, otherwise download it (queued as an "archive" op).
    /// Other members' chunks wholly before your `joinedAt` are dropped. A reset deletes the group's data and puts the
    /// cursor back to 0.
    func apply(_ page: ChangesPage, groupId: String) throws -> Applied {
        try transaction {
            guard let g = group(groupId) else { return Applied() }
            var out = Applied()
            if page.reset {
                resetGroupData(groupId)
                out.reset = true
                out.changed = true
                return out
            }
            let me = g.memberId
            var joinedAt = g.joinedAt
            for item in page.items {
                switch item {
                case .group(let seq, let profile):
                    if seq >= g.profileSeq, let data = Base64URL.decode(profile) {
                        run("UPDATE groups SET profile = ?, profile_seq = ? WHERE group_id = ? AND profile_seq <= ?",
                            [.blob(data), .int(seq), .text(groupId), .int(seq)])
                        out.changed = true
                    }
                case .member(let seq, let memberId, let role, let memberJoinedAt, let profile):
                    guard let data = Base64URL.decode(profile) else { continue }
                    if putMember(groupId, StoredMember(memberId: memberId, role: role, joinedAt: memberJoinedAt, profile: data, seq: seq)) {
                        out.changed = true
                        if memberId == me {
                            joinedAt = memberJoinedAt
                            run("UPDATE groups SET role = ?, joined_at = ? WHERE group_id = ?", [.text(role), .int(memberJoinedAt), .text(groupId)])
                        }
                    }
                case .chunk(let seq, let memberId, let start, let rec):
                    if memberId != me, start + GroupPolicy.windowSeconds <= joinedAt { continue }
                    guard let data = Base64URL.decode(rec) else { continue }
                    if putChunk(groupId, StoredChunk(memberId: memberId, start: start, rec: data, seq: seq)) { out.changed = true }
                case .archive(let seq, let memberId, let month, let maxChunkSeq, let archivedAt):
                    if g.cursor >= maxChunkSeq && g.cursorAt < archivedAt {
                        relabelChunks(groupId, memberId: memberId, month: month, seq: seq)
                    } else {
                        let fetch = ArchiveFetch(memberId: memberId, month: month, seq: seq)
                        out.archiveFetches.append(fetch)
                        if let body = try? String(decoding: JSONEncoder().encode(fetch), as: UTF8.self) {
                            addOp(groupId, kind: "archive", body: body, createdAt: page.serverTime ?? 0)
                        }
                    }
                case .pin(let seq, let pinId, let memberId, let createdAt, let expiresAt, let rec):
                    guard let data = Base64URL.decode(rec) else { continue }
                    if putPin(groupId, StoredPin(pinId: pinId, memberId: memberId, createdAt: createdAt, expiresAt: expiresAt, rec: data, seq: seq)) {
                        out.changed = true
                    }
                case .removed(_, let what, let id):
                    switch what {
                    case "pin": deletePin(groupId, id)
                    case "trail": deleteChunks(groupId, memberId: id)
                    case "member":
                        deleteMember(groupId, id)
                        if id == me { out.removedMe = true }
                    default: break
                    }
                    out.changed = true
                case .unknown:
                    break
                }
            }
            // The cursor only moves forward; `cursor_at` is the time of the page that moved it (§8.8).
            if page.cursor > g.cursor {
                run("UPDATE groups SET cursor = ?, cursor_at = ? WHERE group_id = ?",
                    [.int(page.cursor), .int(page.serverTime ?? g.cursorAt), .text(groupId)])
            }
            return out
        }
    }

    /// Stores the windows of a downloaded archive month as chunks with the archive item's `seq` (other members'
    /// windows wholly before your `joinedAt` are left out).
    func storeArchive(_ groupId: String, memberId: String, records: [(start: Int64, rec: Data)], seq: Int64) throws {
        try transaction {
            guard let g = group(groupId) else { return }
            for r in records {
                if memberId != g.memberId, r.start + GroupPolicy.windowSeconds <= g.joinedAt { continue }
                putChunk(groupId, StoredChunk(memberId: memberId, start: r.start, rec: r.rec, seq: seq))
            }
        }
    }

    // MARK: - SQLite plumbing

    enum Value {
        case int(Int64)
        case text(String)
        case blob(Data)
        case null

        static func optionalInt(_ v: Int64?) -> Value { v.map(Value.int) ?? .null }
        static func optionalText(_ v: String?) -> Value { v.map(Value.text) ?? .null }
        static func optionalBlob(_ v: Data?) -> Value { v.map(Value.blob) ?? .null }
    }

    private func exec(_ sql: String) throws {
        guard let db = db else { throw StoreError.sql("closed") }
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? "unknown"
            sqlite3_free(err)
            throw StoreError.sql(msg)
        }
    }

    private func statement(_ sql: String) -> OpaquePointer? {
        if let s = statements[sql] { return s }
        guard let db = db else { return nil }
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK, let prepared = s else {
            GroupLog.note("store: prepare failed (\(String(cString: sqlite3_errmsg(db))))")
            return nil
        }
        statements[sql] = prepared
        return prepared
    }

    private func bind(_ s: OpaquePointer, _ args: [Value]) {
        for (i, a) in args.enumerated() {
            let index = Int32(i + 1)
            switch a {
            case .int(let v): sqlite3_bind_int64(s, index, v)
            case .text(let v): sqlite3_bind_text(s, index, v, -1, Self.transient)
            case .blob(let v):
                _ = v.withUnsafeBytes { buf in
                    // A zero-length blob still binds as a blob, not NULL.
                    sqlite3_bind_blob(s, index, buf.baseAddress ?? UnsafeRawPointer(bitPattern: 1), Int32(buf.count), Self.transient)
                }
            case .null: sqlite3_bind_null(s, index)
            }
        }
    }

    private func run(_ sql: String, _ args: [Value] = []) {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement(sql) else { return }
        defer {
            sqlite3_reset(s)
            sqlite3_clear_bindings(s)
        }
        bind(s, args)
        let rc = sqlite3_step(s)
        if rc != SQLITE_DONE && rc != SQLITE_ROW, let db = db {
            GroupLog.note("store: write failed (\(String(cString: sqlite3_errmsg(db))))")
        }
    }

    private func rows<T>(_ sql: String, _ args: [Value] = [], _ map: (OpaquePointer) -> T) -> [T] {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement(sql) else { return [] }
        defer {
            sqlite3_reset(s)
            sqlite3_clear_bindings(s)
        }
        bind(s, args)
        var out: [T] = []
        while sqlite3_step(s) == SQLITE_ROW { out.append(map(s)) }
        return out
    }

    private func scalar(_ sql: String, _ args: [Value]) -> Int64? {
        rows(sql, args) { s in sqlite3_column_type(s, 0) == SQLITE_NULL ? nil : sqlite3_column_int64(s, 0) }.first ?? nil
    }
}

private func text(_ s: OpaquePointer, _ i: Int32) -> String {
    sqlite3_column_text(s, i).map { String(cString: $0) } ?? ""
}

private func optionalText(_ s: OpaquePointer, _ i: Int32) -> String? {
    sqlite3_column_type(s, i) == SQLITE_NULL ? nil : text(s, i)
}

private func int(_ s: OpaquePointer, _ i: Int32) -> Int64 {
    sqlite3_column_int64(s, i)
}

private func optionalInt(_ s: OpaquePointer, _ i: Int32) -> Int64? {
    sqlite3_column_type(s, i) == SQLITE_NULL ? nil : sqlite3_column_int64(s, i)
}

private func blob(_ s: OpaquePointer, _ i: Int32) -> Data {
    let count = Int(sqlite3_column_bytes(s, i))
    guard count > 0, let p = sqlite3_column_blob(s, i) else { return Data() }
    return Data(bytes: p, count: count)
}

private func optionalBlob(_ s: OpaquePointer, _ i: Int32) -> Data? {
    sqlite3_column_type(s, i) == SQLITE_NULL ? nil : blob(s, i)
}
