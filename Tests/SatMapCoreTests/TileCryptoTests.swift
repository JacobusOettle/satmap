import Foundation
import XCTest
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif
@testable import SatMapCore

/// Offline tiles are stored sealed (`TileCrypto`), the same bytes on the iPhone and on Android: the Android tests
/// (android/core/src/test/…/TileCryptoTests.kt) are the same, with the same values.
final class TileCryptoTests: XCTestCase {
    private let tile = TileID(z: 17, x: 72427, y: 79652)

    func testSealAndOpen() throws {
        let data = Data((0..<1000).map { UInt8($0 % 253) })
        let sealed = try TileCrypto.seal(data, tile: tile)
        XCTAssertTrue(TileCrypto.isSealed(sealed))
        XCTAssertEqual(sealed.prefix(4), Data([0x53, 0x4D, 0x54, 0x01]))
        XCTAssertEqual(sealed.count, 4 + 12 + data.count + 16)
        XCTAssertNil(sealed.range(of: data.prefix(16)))
        XCTAssertEqual(try TileCrypto.open(sealed, tile: tile), data)
        // A new nonce each time.
        XCTAssertNotEqual(try TileCrypto.seal(data, tile: tile).subdata(in: 4..<16), sealed.subdata(in: 4..<16))
        XCTAssertEqual(try TileCrypto.open(TileCrypto.seal(Data(), tile: tile), tile: tile), Data())
    }

    func testOpensOnlyInItsOwnPlace() throws {
        let sealed = try TileCrypto.seal(Data("not really a JPEG".utf8), tile: tile)
        for other in [TileID(z: 17, x: 72428, y: 79652), TileID(z: 17, x: 72427, y: 79653), TileID(z: 18, x: 72427, y: 79652)] {
            XCTAssertThrowsError(try TileCrypto.open(sealed, tile: other), "\(other)")
        }
        // A changed byte (nonce, ciphertext, tag) or a cut end is refused, and a plain tile isn't sealed.
        for i in [4, 16, sealed.count - 1] {
            var changed = sealed
            changed[i] ^= 1
            XCTAssertThrowsError(try TileCrypto.open(changed, tile: tile), "byte \(i)")
        }
        XCTAssertThrowsError(try TileCrypto.open(sealed.prefix(sealed.count - 1), tile: tile))
        XCTAssertThrowsError(try TileCrypto.open(sealed.prefix(31), tile: tile))
        XCTAssertThrowsError(try TileCrypto.open(Data("not really a JPEG".utf8), tile: tile))
    }

    func testImagesAreNotSealed() {
        for header: [UInt8] in [[0xFF, 0xD8, 0xFF, 0xE0], [0x89, 0x50, 0x4E, 0x47], Array("RIFF".utf8), Array("GIF8".utf8),
                                [0x53, 0x4D, 0x54, 0x02], [0x53, 0x4D, 0x54]] {
            XCTAssertFalse(TileCrypto.isSealed(Data(header + [UInt8](repeating: 0, count: 40))), "\(header)")
        }
        XCTAssertFalse(TileCrypto.isSealed(BackupFixture.terrainData))
        XCTAssertFalse(TileCrypto.isSealed(Data()))
    }

    func testSameBytesAsAndroid() throws {
        // With a fixed nonce (the app never uses one twice): the Android test expects the same bytes, and so does
        // another AES-GCM implementation (Python's cryptography) given the key.
        let nonce = Data(0..<12)
        XCTAssertEqual(TileCrypto.associatedData(tile), Data("satmap/17/72427/79652".utf8))
        XCTAssertEqual(hex(try TileCrypto.seal(Data("not really a JPEG".utf8), tile: tile, nonce: nonce)),
                       "534d5401000102030405060708090a0be88d56074b197cc14e7c0dc734305e2abf1b79206f97fd09dc2be8420ccc6abd09")
        XCTAssertEqual(hex(try TileCrypto.seal(Data(), tile: tile, nonce: nonce)),
                       "534d5401000102030405060708090a0bc30a8e200b9281f3891810cd15308c84")
        let android = try XCTUnwrap(Data(hex: "534d5401000102030405060708090a0be88d56074b197cc14e7c0dc734305e2abf1b79206f97fd09dc2be8420ccc6abd09"))
        XCTAssertEqual(try TileCrypto.open(android, tile: tile), Data("not really a JPEG".utf8))
    }

    // MARK: - In the tile store

    func testStoresTilesSealedAndReadsThemPlain() throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("t.mbtiles"), create: true)
        defer { store.close() }
        XCTAssertTrue(store.isEncrypted) // a new file
        let image = Data("not really a JPEG".utf8)
        XCTAssertTrue(store.put(tile, data: image))
        let stored = try XCTUnwrap(store.storedTile(tile))
        XCTAssertTrue(TileCrypto.isSealed(stored))
        XCTAssertEqual(try TileCrypto.open(stored, tile: tile), image)
        XCTAssertEqual(store.tile(tile), image)
        // Delivered sealed (as a tile server may): stored as it comes.
        let next = TileID(z: 17, x: 72428, y: 79652)
        let sealed = try TileCrypto.seal(image, tile: next)
        XCTAssertTrue(store.put(next, data: sealed))
        XCTAssertEqual(store.storedTile(next), sealed)
        XCTAssertEqual(store.tile(next), image)
        // A tile sealed for another place doesn't open: it is missing.
        let moved = TileID(z: 17, x: 72429, y: 79652)
        XCTAssertTrue(store.put(moved, data: sealed))
        XCTAssertNil(store.tile(moved))
        XCTAssertNil(store.tile(moved))
        XCTAssertTrue(store.flush())
    }

    func testPlainTilesFromBeforeAreReadAsTheyAre() throws {
        let url = tempDir().appendingPathComponent("old.mbtiles")
        let tiles = plainTiles(3)
        try writePlainStore(url, tiles: tiles)
        // A download opening the file to go on doesn't mark it: it has plain tiles. What it adds is sealed.
        let store = try MBTiles(url: url, create: true)
        defer { store.close() }
        XCTAssertFalse(store.isEncrypted)
        for (t, data) in tiles {
            XCTAssertEqual(store.storedTile(t), data)
            XCTAssertEqual(store.tile(t), data)
        }
        let added = TileID(z: 18, x: 999, y: 2000)
        XCTAssertTrue(store.put(added, data: Data("added".utf8)))
        XCTAssertTrue(TileCrypto.isSealed(try XCTUnwrap(store.storedTile(added))))
        XCTAssertEqual(store.tile(added), Data("added".utf8))
        XCTAssertEqual(store.tile(tiles[1].0), tiles[1].1)
        XCTAssertFalse(store.isEncrypted)
        // An old file without tiles has nothing plain: marked when a download opens it.
        let empty = tempDir().appendingPathComponent("empty.mbtiles")
        try writePlainStore(empty, tiles: [])
        XCTAssertFalse(try MBTiles(url: empty, create: false).isEncrypted)
        XCTAssertTrue(try MBTiles(url: empty, create: true).isEncrypted)
    }

    func testEncryptsPlainTilesInPlaceAndResumes() throws {
        let url = tempDir().appendingPathComponent("old.mbtiles")
        let tiles = plainTiles(300)
        try writePlainStore(url, tiles: tiles)
        let store = try MBTiles(url: url, create: false)
        defer { store.close() }
        // A download writing meanwhile, through a connection of its own.
        let download = try MBTiles(url: url, create: true)
        defer { download.close() }
        XCTAssertFalse(store.isEncrypted)

        // Stopped after two batches: those are sealed, the rest still plain, and every tile reads.
        var reports: [[Int]] = []
        XCTAssertFalse(store.encryptPlainTiles(batchSize: 64) { checked, total in
            reports.append([checked, total])
            return reports.count < 2
        })
        XCTAssertEqual(reports, [[64, 300], [128, 300]])
        XCTAssertEqual(tiles.filter { TileCrypto.isSealed(store.storedTile($0.0) ?? Data()) }.count, 128)
        XCTAssertFalse(store.isEncrypted)
        for (t, data) in tiles { XCTAssertEqual(store.tile(t), data, "\(t)") }

        // The download replaces a plain tile and adds one.
        let added = TileID(z: 18, x: 999, y: 2000)
        XCTAssertTrue(download.put(tiles[200].0, data: Data("new".utf8)))
        XCTAssertTrue(download.put(added, data: Data("added".utf8)))
        XCTAssertTrue(download.flush())

        // Run again, it carries on with the tiles still plain.
        reports = []
        XCTAssertTrue(store.encryptPlainTiles(batchSize: 64) { checked, total in
            reports.append([checked, total])
            return true
        })
        XCTAssertEqual(reports.last, [301, 301])
        XCTAssertTrue(store.isEncrypted)
        XCTAssertEqual(store.tileCount(), 301)
        for (i, (t, data)) in tiles.enumerated() {
            XCTAssertTrue(TileCrypto.isSealed(store.storedTile(t) ?? Data()), "\(t)")
            XCTAssertEqual(store.tile(t), i == 200 ? Data("new".utf8) : data, "\(t)")
        }
        XCTAssertEqual(store.tile(added), Data("added".utf8))
        // Done: at once, without checking the tiles again.
        XCTAssertTrue(store.encryptPlainTiles { _, _ in
            XCTFail("checked again")
            return true
        })
        XCTAssertTrue(try MBTiles(url: url, create: false).isEncrypted)
    }

    func testNoPlainPictureIsLeftInTheFile() throws {
        // Small tiles, and large ones that spill onto overflow pages: the space each took is overwritten, and the
        // sealed pages are folded into the database file, while the map's store stays open.
        let url = tempDir().appendingPathComponent("old.mbtiles")
        let tiles = (0..<40).map { i in
            (TileID(z: 16, x: 500 + i, y: 700), Data(String(repeating: "PLAIN PICTURE \(i) ", count: i % 2 == 0 ? 20 : 600).utf8))
        }
        try writePlainStore(url, tiles: tiles)
        let picture = Data("PLAIN PICTURE".utf8)
        let wal = URL(fileURLWithPath: url.path + "-wal")
        XCTAssertNotNil(try Data(contentsOf: url).range(of: picture))
        let map = try MBTiles(url: url, create: false)
        defer { map.close() }
        XCTAssertEqual(map.tile(tiles[0].0), tiles[0].1)
        let store = try MBTiles(url: url, create: false)
        defer { store.close() }

        // A reader in the middle of a read keeps the checkpoint from finishing: it stops soon, the tiles sealed and the
        // file marked, but the plain pages still in the database file.
        var reader: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &reader), SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(reader, "BEGIN; SELECT COUNT(*) FROM tiles;", nil, nil, nil), SQLITE_OK)
        let start = Date()
        XCTAssertFalse(store.encryptPlainTiles(batchSize: 16))
        XCTAssertLessThan(Date().timeIntervalSince(start), 5)
        XCTAssertTrue(store.isEncrypted)
        XCTAssertNotNil(try Data(contentsOf: url).range(of: picture))
        sqlite3_exec(reader, "COMMIT", nil, nil, nil)
        sqlite3_close(reader)

        // Run again, only the checkpoint is left. Then neither the file nor its log holds a plain picture.
        XCTAssertTrue(store.encryptPlainTiles(batchSize: 16) { _, _ in
            XCTFail("checked again")
            return true
        })
        XCTAssertNil(try Data(contentsOf: url).range(of: picture))
        XCTAssertNil((try? Data(contentsOf: wal))?.range(of: picture))
        for (t, data) in tiles { XCTAssertEqual(map.tile(t), data) }
    }

    func testADownloadKeepingTheFileBusyDoesntHoldUpTheMap() throws {
        let url = tempDir().appendingPathComponent("old.mbtiles")
        let tiles = plainTiles(300)
        try writePlainStore(url, tiles: tiles)
        // Run on the map's store while a download, through a connection of its own, keeps its transaction open (as it
        // does for 256 tiles at a time).
        let store = try MBTiles(url: url, create: false)
        defer { store.close() }
        let download = try MBTiles(url: url, create: true)
        defer { download.close() }
        XCTAssertTrue(download.put(TileID(z: 18, x: 999, y: 2000), data: Data("added".utf8)))

        final class Outcome: @unchecked Sendable { var encrypted: Bool? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let start = Date()
        DispatchQueue.global(qos: .utility).async {
            outcome.encrypted = store.encryptPlainTiles(batchSize: 64)
            done.signal()
        }
        // Meanwhile the map's reads wait for it no longer than about a batch.
        var longestRead: TimeInterval = 0
        var i = 0
        while done.wait(timeout: .now()) == .timedOut {
            let readStart = Date()
            XCTAssertEqual(store.tile(tiles[i % tiles.count].0), tiles[i % tiles.count].1)
            longestRead = max(longestRead, Date().timeIntervalSince(readStart))
            i += 1
        }
        // It stops soon, to run again later.
        XCTAssertEqual(outcome.encrypted, false)
        XCTAssertLessThan(Date().timeIntervalSince(start), 5)
        XCTAssertLessThan(longestRead, 1)
        XCTAssertFalse(store.isEncrypted)

        // Once the download has committed, it runs.
        XCTAssertTrue(download.flush())
        XCTAssertTrue(store.encryptPlainTiles())
        XCTAssertTrue(store.isEncrypted)
        for (t, data) in tiles { XCTAssertEqual(store.tile(t), data, "\(t)") }
    }

    // MARK: - Helpers

    /// Tiles of a few hundred bytes to a few kilobytes, each different.
    private func plainTiles(_ count: Int) -> [(TileID, Data)] {
        (0..<count).map { i in
            (TileID(z: 18, x: 1000 + i, y: 2000), Data(String(repeating: "plain tile \(i) ", count: 20 + i % 7 * 50).utf8))
        }
    }

    /// A tile file as the app wrote it before tiles were encrypted: the tiles stored plain, the file not marked.
    /// Written with `secure_delete`, so it holds no scraps of what SQLite moved while writing it (which
    /// `encryptPlainTiles` doesn't clear).
    private func writePlainStore(_ url: URL, tiles: [(TileID, Data)]) throws {
        try MBTiles(url: url, create: true).close()
        var sql = "PRAGMA secure_delete=ON; BEGIN; DELETE FROM metadata WHERE name = '\(MBTiles.encryptedKey)';"
        for (t, data) in tiles {
            sql += "INSERT INTO tiles VALUES (\(t.z), \(t.x), \((1 << t.z) - 1 - t.y), X'\(hex(data))');"
        }
        sql += "COMMIT;"
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
    }

    private func tempDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("satmap-tests-" + UUID().uuidString)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

func hex(_ data: Data) -> String {
    let digits = Array("0123456789abcdef".utf8)
    return String(decoding: data.flatMap { [digits[Int($0 >> 4)], digits[Int($0 & 15)]] }, as: UTF8.self)
}

extension Data {
    init?(hex: String) {
        let digits = Array(hex.utf8)
        guard digits.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        for i in stride(from: 0, to: digits.count, by: 2) {
            guard let byte = UInt8(String(decoding: digits[i...i + 1], as: UTF8.self), radix: 16) else { return nil }
            bytes.append(byte)
        }
        self.init(bytes)
    }
}
