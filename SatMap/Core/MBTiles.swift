import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif
#if canImport(os)
import os

private let storeLog = Logger(subsystem: "com.jacobusoettle.satmap", category: "tiles")
#endif

/// A tile store in the MBTiles layout (SQLite; tile_row uses the TMS convention, flipped from XYZ).
/// One file per layer per offline area. Thread-safe: every operation is serialized by a lock.
/// Also keeps a `missing_tiles` table of tiles the server reported as non-existent, so resumed
/// downloads don't ask again.
///
/// Tiles are stored sealed (`TileCrypto`), so other apps can't show them. Files from before tiles were encrypted
/// still hold plain ones, which are read as they are until `encryptPlainTiles` seals them.
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
    /// The last write error (e.g. disk full). Writes after an error are not committed.
    private(set) var writeError: String?
    private var db: OpaquePointer?
    private let lock = NSLock()
    private var statements: [String: OpaquePointer] = [:]
    private var uncommittedWrites = 0
    private let writesPerTransaction = 256
    private var loggedUnreadable = false
    /// How long a statement waits for another connection's lock before it gives up.
    private static let busyTimeout: Int32 = 10_000
    /// The same for `encryptPlainTiles`, which waits holding the lock: briefly, so the store's other calls (the map's
    /// reads, if it runs on the map's store) aren't held up while a download writes through another connection.
    private static let briefBusyTimeout: Int32 = 200

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Opens the database. The downloader opens it with `create: true` (creating file and schema);
    /// map readers open their own instance with `create: false`. WAL journaling lets the map read
    /// while a download writes.
    init(url: URL, create: Bool) throws {
        self.url = url
        var flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX | (create ? SQLITE_OPEN_CREATE : 0)
        #if os(iOS)
        // Readable and writable while the phone is locked (after the first unlock since boot), like the rest of the app's data.
        flags |= SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION
        #endif
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(url.path, &handle, flags, nil)
        guard rc == SQLITE_OK, let opened = handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "code \(rc)"
            if let h = handle { sqlite3_close(h) }
            throw StoreError.open(msg)
        }
        db = opened
        sqlite3_busy_timeout(opened, Self.busyTimeout)
        guard create else { return }
        try exec("""
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=NORMAL;
            PRAGMA journal_size_limit=67108864;
            CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT);
            CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL);
            CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_row, tile_column);
            CREATE TABLE IF NOT EXISTS missing_tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL, PRIMARY KEY (zoom_level, tile_row, tile_column)) WITHOUT ROWID;
            INSERT OR IGNORE INTO metadata (name, value) SELECT '\(Self.encryptedKey)', '1' WHERE NOT EXISTS (SELECT 1 FROM tiles);
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
            if sqlite3_exec(db, "COMMIT", nil, nil, nil) != SQLITE_OK { sqlite3_exec(db, "ROLLBACK", nil, nil, nil) }
            uncommittedWrites = 0
        }
        for (_, s) in statements { sqlite3_finalize(s) }
        statements = [:]
        sqlite3_close(db)
        self.db = nil
    }

    // MARK: - Reading

    /// The tile's image: opened if it is sealed, as it is if it was stored before tiles were encrypted. A tile that
    /// doesn't open (damaged, or moved from another place) is treated as missing, and logged once.
    func tile(_ t: TileID) -> Data? {
        guard let blob = storedTile(t) else { return nil }
        guard TileCrypto.isSealed(blob) else { return blob }
        do {
            return try TileCrypto.open(blob, tile: t)
        } catch {
            lock.lock()
            let first = !loggedUnreadable
            loggedUnreadable = true
            lock.unlock()
            if first { logUnreadable("\(url.lastPathComponent): tile \(t) doesn't open (\(error)), treated as missing; others aren't logged") }
            return nil
        }
    }

    /// The tile's bytes as stored: sealed, or plain from before tiles were encrypted.
    func storedTile(_ t: TileID) -> Data? {
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

    /// Whether the downloader recorded that the server has no tile here.
    func isMissing(_ t: TileID) -> Bool {
        !missingColumns(z: t.z, y: t.y, xs: t.x...t.x).isEmpty
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

    /// Stores a tile, sealed (`TileCrypto`) unless it already is: a tile server may deliver tiles sealed in the same
    /// format, and they are stored as they come. Returns false if it could not be written (see `writeError`).
    @discardableResult
    func put(_ t: TileID, data: Data) -> Bool {
        guard let blob = try? (TileCrypto.isSealed(data) ? data : TileCrypto.seal(data, tile: t)) else { return false }
        lock.lock()
        defer { lock.unlock() }
        guard writeError == nil, beginIfNeeded(),
              let s = statement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)") else { return false }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(t.z))
        sqlite3_bind_int(s, 2, Int32(t.x))
        sqlite3_bind_int(s, 3, Int32(Self.tmsRow(t)))
        _ = blob.withUnsafeBytes { buf in
            sqlite3_bind_blob(s, 4, buf.baseAddress, Int32(buf.count), Self.transient)
        }
        let rc = sqlite3_step(s)
        sqlite3_clear_bindings(s)
        guard rc == SQLITE_DONE else { return failWrite(rc) }
        return noteWrite()
    }

    /// Records that the server has no tile here. Returns false on a write error.
    @discardableResult
    func putMissing(_ t: TileID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard writeError == nil, beginIfNeeded(),
              let s = statement("INSERT OR IGNORE INTO missing_tiles (zoom_level, tile_column, tile_row) VALUES (?,?,?)") else { return false }
        defer { sqlite3_reset(s) }
        sqlite3_bind_int(s, 1, Int32(t.z))
        sqlite3_bind_int(s, 2, Int32(t.x))
        sqlite3_bind_int(s, 3, Int32(Self.tmsRow(t)))
        let rc = sqlite3_step(s)
        guard rc == SQLITE_DONE else { return failWrite(rc) }
        return noteWrite()
    }

    func metadata(_ name: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let s = statement("SELECT value FROM metadata WHERE name = ?") else { return nil }
        defer { sqlite3_reset(s) }
        sqlite3_bind_text(s, 1, name, -1, Self.transient)
        guard sqlite3_step(s) == SQLITE_ROW, let text = sqlite3_column_text(s, 0) else { return nil }
        return String(cString: text)
    }

    func setMetadata(_ values: [String: String]) {
        lock.lock()
        defer { lock.unlock() }
        guard writeError == nil, beginIfNeeded(), let s = statement("INSERT OR REPLACE INTO metadata (name, value) VALUES (?,?)") else { return }
        for (k, v) in values {
            sqlite3_bind_text(s, 1, k, -1, Self.transient)
            sqlite3_bind_text(s, 2, v, -1, Self.transient)
            sqlite3_step(s)
            sqlite3_reset(s)
        }
        _ = noteWrite()
    }

    /// Commits any pending writes. Returns false on a write error.
    @discardableResult
    func flush() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return commitLocked()
    }

    /// Folds the write-ahead log back into the database file and truncates it (after each downloaded layer, and
    /// before the file is exported). Returns false if it couldn't finish, e.g. because a reader was busy.
    @discardableResult
    func checkpoint() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return checkpointLocked()
    }

    // MARK: - Encrypting files from before tiles were encrypted

    /// Metadata key set to "1" on a file whose tiles are all sealed: on new files as they are created, and by
    /// `encryptPlainTiles` when it has sealed them all.
    static let encryptedKey = "satmap_encrypted"

    /// Whether every tile is sealed (the file is marked with `encryptedKey`). Files from before tiles were encrypted,
    /// and backups made then, aren't until `encryptPlainTiles` has run on them.
    var isEncrypted: Bool {
        metadata(Self.encryptedKey) == "1"
    }

    /// Seals the tiles stored plain in place, `batchSize` rows at a time, each batch in a transaction of its own; the
    /// batch that finds no rows left marks the file (`encryptedKey`). Then a checkpoint (`checkpoint`) folds the sealed
    /// tiles into the database file and empties the write-ahead log: until then the file itself still holds the plain
    /// tiles, and the log earlier copies of their pages. The space a plain tile took is overwritten with zeros
    /// (`secure_delete`), so after the checkpoint its picture isn't left in the file's free space; scraps that SQLite
    /// left in unused parts of pages when the file was written may remain.
    ///
    /// Meant to run in the background at low priority, on the area's download store while one is open (its pending
    /// writes are committed before each batch), else on the map's store or one of its own: between batches the map
    /// reads and a download writes as usual. A batch, and the checkpoint, hold the store's lock while they wait for
    /// another connection (a download's transaction, a reader in the middle of a read), so they wait only 0.2 s, not
    /// the usual 10 s: the store's other calls are held up by little more than a batch, and if the other connection is
    /// still busy it stops.
    ///
    /// `progress` is told the rows checked so far and the tiles in the file after each batch, and stops the work by
    /// returning false. Returns true when every tile is sealed, the file is marked and the checkpoint has finished (if
    /// the file was marked already, only the checkpoint runs); false if it stopped early: asked to, a write failed
    /// (e.g. storage full), or another connection kept the file busy. It can run again at any time and carries on with
    /// what is left.
    @discardableResult
    func encryptPlainTiles(batchSize: Int = 64, progress: (_ checked: Int, _ total: Int) -> Bool = { _, _ in true }) -> Bool {
        if !isEncrypted {
            let total = tileCount()
            var after: Int64 = 0
            var checked = 0
            while true {
                guard let batch = encryptBatch(after: after, limit: max(1, batchSize)) else { return false }
                guard batch.rows > 0 else { break }
                checked += batch.rows
                after = batch.lastRowID
                // Tiles a download adds meanwhile come after the others (and are sealed already).
                guard progress(checked, max(total, checked)) else { return false }
            }
        }
        // Also when the file was marked already: an earlier run may have stopped at the checkpoint.
        lock.lock()
        defer { lock.unlock() }
        return waitingBriefly { checkpointLocked() }
    }

    /// Seals the plain tiles among the next `limit` rows after `after` (a rowid) in one transaction, and marks the file
    /// if there are none. The number of rows checked and the last one's rowid, or nil if it failed (and nothing was
    /// changed).
    private func encryptBatch(after: Int64, limit: Int) -> (rows: Int, lastRowID: Int64)? {
        lock.lock()
        defer { lock.unlock() }
        // A download's writes still open on this connection are committed first: the batch is a transaction of its own.
        guard let db = db, commitLocked(),
              waitingBriefly({ sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil) }) == SQLITE_OK else { return nil }
        // Freed space is overwritten with zeros. Set inside the transaction: on Android that runs it on the writing
        // connection.
        sqlite3_exec(db, "PRAGMA secure_delete=ON", nil, nil, nil)
        guard let select = statement("SELECT rowid, zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE rowid > ? ORDER BY rowid LIMIT ?"),
              let update = statement("UPDATE tiles SET tile_data=? WHERE rowid=?") else {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            return nil
        }
        var rows = 0
        var lastRowID = after
        var plain: [(rowID: Int64, tile: TileID, data: Data)] = []
        sqlite3_bind_int64(select, 1, after)
        sqlite3_bind_int(select, 2, Int32(clamping: limit))
        var rc = sqlite3_step(select)
        while rc == SQLITE_ROW {
            rows += 1
            lastRowID = sqlite3_column_int64(select, 0)
            let count = Int(sqlite3_column_bytes(select, 4))
            let data = sqlite3_column_blob(select, 4).map { Data(bytes: $0, count: count) } ?? Data()
            if !TileCrypto.isSealed(data) {
                let z = Int(sqlite3_column_int(select, 1))
                let t = TileID(z: z, x: Int(sqlite3_column_int(select, 2)), y: (1 << z) - 1 - Int(sqlite3_column_int(select, 3)))
                plain.append((lastRowID, t, data))
            }
            rc = sqlite3_step(select)
        }
        sqlite3_reset(select)
        var ok = rc == SQLITE_DONE
        for p in plain where ok {
            guard let sealed = try? TileCrypto.seal(p.data, tile: p.tile) else {
                ok = false
                break
            }
            _ = sealed.withUnsafeBytes { buf in
                sqlite3_bind_blob(update, 1, buf.baseAddress, Int32(buf.count), Self.transient)
            }
            sqlite3_bind_int64(update, 2, p.rowID)
            ok = sqlite3_step(update) == SQLITE_DONE
            sqlite3_clear_bindings(update)
            sqlite3_reset(update)
        }
        // None left: every tile is sealed (a download adds them sealed), so the file is marked in the same transaction.
        if ok && rows == 0 {
            ok = sqlite3_exec(db, "INSERT OR REPLACE INTO metadata (name, value) VALUES ('\(Self.encryptedKey)', '1')", nil, nil, nil) == SQLITE_OK
        }
        guard ok, sqlite3_exec(db, "COMMIT", nil, nil, nil) == SQLITE_OK else {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            return nil
        }
        return (rows, lastRowID)
    }

    // MARK: - Internals (call with lock held)

    private func beginIfNeeded() -> Bool {
        guard let db = db else { return false }
        if uncommittedWrites == 0 {
            let rc = sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil)
            guard rc == SQLITE_OK else { return failWrite(rc) }
        }
        return true
    }

    private func noteWrite() -> Bool {
        uncommittedWrites += 1
        if uncommittedWrites >= writesPerTransaction { return commitLocked() }
        return true
    }

    private func checkpointLocked() -> Bool {
        guard let db = db else { return false }
        let committed = commitLocked()
        return sqlite3_wal_checkpoint_v2(db, nil, SQLITE_CHECKPOINT_TRUNCATE, nil, nil) == SQLITE_OK && committed
    }

    /// Runs `body` with the short busy timeout of `encryptPlainTiles` (`briefBusyTimeout`), then sets the usual one back.
    private func waitingBriefly<T>(_ body: () -> T) -> T {
        guard let db = db else { return body() }
        sqlite3_busy_timeout(db, Self.briefBusyTimeout)
        defer { sqlite3_busy_timeout(db, Self.busyTimeout) }
        return body()
    }

    private func commitLocked() -> Bool {
        guard let db = db else { return false }
        guard uncommittedWrites > 0 else { return writeError == nil }
        let rc = sqlite3_exec(db, "COMMIT", nil, nil, nil)
        uncommittedWrites = 0
        guard rc == SQLITE_OK else {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            return failWrite(rc)
        }
        return true
    }

    /// Records a write failure (disk full, I/O error) and abandons the open transaction.
    private func failWrite(_ rc: Int32) -> Bool {
        if writeError == nil {
            writeError = rc == SQLITE_FULL ? "The iPhone is out of storage space." : "Couldn't save map tiles (SQLite error \(rc))."
        }
        if let db = db, uncommittedWrites > 0 {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            uncommittedWrites = 0
        }
        return false
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

    private func logUnreadable(_ message: String) {
        #if canImport(os)
        storeLog.error("\(message, privacy: .public)")
        #else
        FileHandle.standardError.write(Data((message + "\n").utf8))
        #endif
    }
}
