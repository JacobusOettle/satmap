import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

/// A tile store in the MBTiles layout (SQLite; tile_row uses the TMS convention, flipped from XYZ).
/// One file per layer per offline area. Thread-safe: every operation is serialized by a lock.
/// Also keeps a `missing_tiles` table of tiles the server reported as non-existent, so resumed
/// downloads don't ask again.
final class MBTiles {
    enum StoreError: Error, LocalizedError {
        case open(String)
        case sql(String)

        var errorDescription: String? {
            switch self {
            case .open(let m): return "Could not open tile database: \(m)"
            case .sql(let m): return "Tile database error: \(m)"
            }
        }
    }

    let url: URL
    private var db: OpaquePointer?
    private let lock = NSLock()
    private var statements: [String: OpaquePointer] = [:]
    private var uncommittedWrites = 0
    private let writesPerTransaction = 256

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Opens the database. The downloader opens it with `create: true` (creating file and schema);
    /// map readers open their own instance with `create: false`. WAL journaling lets the map read
    /// while a download writes.
    init(url: URL, create: Bool) throws {
        self.url = url
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX | (create ? SQLITE_OPEN_CREATE : 0)
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(url.path, &handle, flags, nil)
        guard rc == SQLITE_OK, let opened = handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "code \(rc)"
            if let h = handle { sqlite3_close(h) }
            throw StoreError.open(msg)
        }
        db = opened
        sqlite3_busy_timeout(opened, 10_000)
        guard create else { return }
        try exec("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT);
            CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL);
            CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_row, tile_column);
            CREATE TABLE IF NOT EXISTS missing_tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, PRIMARY KEY (zoom_level, tile_row, tile_column)) WITHOUT ROWID;
            """)
    }

    deinit {
        close()
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        guard let db = db else { return }
        if uncommittedWrites > 0 {
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
            uncommittedWrites = 0
        }
        for (_, s) in statements { sqlite3_finalize(s) }
        statements = [:]
        sqlite3_close(db)
        self.db = nil
    }

    // MARK: - Reading

    func tile(_ t: TileID) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?") else { return nil }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(t.z))
        sqlite3_bind_int(s, 2, Int32(t.x))
        sqlite3_bind_int(s, 3, Int32(Self.tmsRow(t)))
        guard sqlite3_step(s) == SQLITE_ROW else { return nil }
        let count = Int(sqlite3_column_bytes(s, 0))
        guard count > 0, let p = sqlite3_column_blob(s, 0) else { return nil }
        return Data(bytes: p, count: count)
    }

    func contains(_ t: TileID) -> Bool {
        !existingColumns(z: t.z, y: t.y, xs: t.x...t.x).isEmpty
    }

    /// XYZ columns in `xs` that have a stored tile in XYZ row `y`.
    func existingColumns(z: Int, y: Int, xs: ClosedRange<Int>) -> Set<Int> {
        columns(sql: "SELECT tile_column FROM tiles WHERE zoom_level=? AND tile_row=? AND tile_column BETWEEN ? AND ?", z: z, y: y, xs: xs)
    }

    /// XYZ columns in `xs` that the server reported as non-existent in XYZ row `y`.
    func missingColumns(z: Int, y: Int, xs: ClosedRange<Int>) -> Set<Int> {
        columns(sql: "SELECT tile_column FROM missing_tiles WHERE zoom_level=? AND tile_row=? AND tile_column BETWEEN ? AND ?", z: z, y: y, xs: xs)
    }

    private func columns(sql: String, z: Int, y: Int, xs: ClosedRange<Int>) -> Set<Int> {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement(sql) else { return [] }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(z))
        sqlite3_bind_int(s, 2, Int32((1 << z) - 1 - y))
        sqlite3_bind_int(s, 3, Int32(xs.lowerBound))
        sqlite3_bind_int(s, 4, Int32(xs.upperBound))
        var out = Set<Int>()
        while sqlite3_step(s) == SQLITE_ROW { out.insert(Int(sqlite3_column_int(s, 0))) }
        return out
    }

    func tileCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement("SELECT COUNT(*) FROM tiles") else { return 0 }
        defer { sqlite3_reset(s) }
        return sqlite3_step(s) == SQLITE_ROW ? Int(sqlite3_column_int64(s, 0)) : 0
    }

    /// Highest zoom level that has at least one stored tile.
    func maxStoredZoom() -> Int? {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement("SELECT MAX(zoom_level) FROM tiles") else { return nil }
        defer { sqlite3_reset(s) }
        guard sqlite3_step(s) == SQLITE_ROW, sqlite3_column_type(s, 0) != SQLITE_NULL else { return nil }
        return Int(sqlite3_column_int(s, 0))
    }

    // MARK: - Writing (batched into transactions)

    func put(_ t: TileID, data: Data) {
        lock.lock()
        defer { lock.unlock() }
        guard beginIfNeeded(),
              let s = statement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)") else { return }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(t.z))
        sqlite3_bind_int(s, 2, Int32(t.x))
        sqlite3_bind_int(s, 3, Int32(Self.tmsRow(t)))
        _ = data.withUnsafeBytes { buf in
            sqlite3_bind_blob(s, 4, buf.baseAddress, Int32(buf.count), Self.transient)
        }
        sqlite3_step(s)
        sqlite3_clear_bindings(s)
        noteWrite()
    }

    func putMissing(_ t: TileID) {
        lock.lock()
        defer { lock.unlock() }
        guard beginIfNeeded(),
              let s = statement("INSERT OR IGNORE INTO missing_tiles (zoom_level, tile_column, tile_row) VALUES (?,?,?)") else { return }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(t.z))
        sqlite3_bind_int(s, 2, Int32(t.x))
        sqlite3_bind_int(s, 3, Int32(Self.tmsRow(t)))
        sqlite3_step(s)
        noteWrite()
    }

    func setMetadata(_ values: [String: String]) {
        lock.lock()
        defer { lock.unlock() }
        guard beginIfNeeded(), let s = statement("INSERT OR REPLACE INTO metadata (name, value) VALUES (?,?)") else { return }
        for (k, v) in values {
            sqlite3_bind_text(s, 1, k, -1, Self.transient)
            sqlite3_bind_text(s, 2, v, -1, Self.transient)
            sqlite3_step(s)
            sqlite3_reset(s)
        }
        noteWrite()
    }

    /// Commits any pending writes.
    func flush() {
        lock.lock()
        defer { lock.unlock() }
        commitLocked()
    }

    // MARK: - Internals (call with lock held)

    private func beginIfNeeded() -> Bool {
        guard let db = db else { return false }
        if uncommittedWrites == 0 {
            return sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK
        }
        return true
    }

    private func noteWrite() {
        uncommittedWrites += 1
        if uncommittedWrites >= writesPerTransaction { commitLocked() }
    }

    private func commitLocked() {
        guard let db = db, uncommittedWrites > 0 else { return }
        sqlite3_exec(db, "COMMIT", nil, nil, nil)
        uncommittedWrites = 0
    }

    private func statement(_ sql: String) -> OpaquePointer? {
        if let s = statements[sql] { return s }
        guard let db = db else { return nil }
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK, let prepared = s else { return nil }
        statements[sql] = prepared
        return prepared
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

    private static func tmsRow(_ t: TileID) -> Int {
        (1 << t.z) - 1 - t.y
    }
}
