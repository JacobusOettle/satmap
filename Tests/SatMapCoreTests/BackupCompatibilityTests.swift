import Foundation
import XCTest
@testable import SatMapCore

/// Offline map backups move between the iPhone and Android apps: both write and read the same files. These tests
/// pin the format on the iPhone side and read a backup made by the iPhone app (Fixtures/backup/iphone; the Android
/// tests read it too). The expected area is `BackupFixture.area`, in
/// the Android tests too.
final class BackupCompatibilityTests: XCTestCase {
    func testAreaJSONIsTheSharedFormat() throws {
        let json = try JSONEncoder().encode(BackupFixture.area)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["id", "name", "created", "shape", "roads", "maxZoom", "allowCellular", "status",
                                          "failedTiles", "bytesOnDisk"]) // preset, isTrack and problem not set: left out
        XCTAssertEqual(object["id"] as? String, "6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9") // in capitals
        // Seconds since 1 January 2001 (Swift's own date encoding), not since 1970.
        XCTAssertEqual(try XCTUnwrap(object["created"] as? Double), 1_761_696_000 - 978_307_200, accuracy: 1e-6)
        XCTAssertEqual(object["status"] as? String, "complete")
        XCTAssertEqual(object["bytesOnDisk"] as? Int, 2_345_678_901)
        let shape = try XCTUnwrap(object["shape"] as? [String: Any])
        XCTAssertEqual(Set(shape.keys), ["polygons", "lines", "points", "bufferMeters"])
        let ring = try XCTUnwrap((shape["polygons"] as? [[[String: Double]]])?.first)
        XCTAssertEqual(ring.first, ["lat": -33.9245, "lon": 18.9232])
    }

    func testReadsBackupsMadeOnTheIPhone() throws {
        try checkBackup(in: "iphone")
    }

    /// Writes the iPhone fixture with the app's own export (run with SATMAP_WRITE_BACKUP_FIXTURES=<folder>).
    func testWriteIPhoneBackupFixture() throws {
        guard let out = ProcessInfo.processInfo.environment["SATMAP_WRITE_BACKUP_FIXTURES"] else {
            throw XCTSkip("Writes the fixture only when asked")
        }
        let area = FileManager.default.temporaryDirectory.appendingPathComponent("backup-fixture-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: area, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: area) }
        let imagery = try MBTiles(url: area.appendingPathComponent("imagery.mbtiles"), create: true)
        for (t, data) in BackupFixture.imageryTiles { XCTAssertTrue(imagery.put(t, data: data)) }
        XCTAssertTrue(imagery.putMissing(BackupFixture.missingImagery))
        XCTAssertTrue(imagery.flush())
        imagery.close()
        let terrain = try MBTiles(url: area.appendingPathComponent("terrain.mbtiles"), create: true)
        XCTAssertTrue(terrain.put(BackupFixture.terrainTile, data: BackupFixture.terrainData))
        XCTAssertTrue(terrain.flush())
        terrain.close()
        var used = Set<String>()
        let json = String(decoding: try JSONEncoder().encode(BackupFixture.area), as: UTF8.self)
        let files = try OfflineBackup.export(areaFolder: area, layers: BackupFixture.layers, name: BackupFixture.area.name,
                                             areaJSON: json, into: URL(fileURLWithPath: out), usedNames: &used)
        XCTAssertEqual(files.map(\.lastPathComponent), ["Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles"])
    }

    /// Restores a backup as the app does (`OfflineBackup.inspect`, then the area's JSON) and checks every part.
    private func checkBackup(in folder: String) throws {
        let dir = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/backup/" + folder, withExtension: nil))
        var layers: [String: URL] = [:]
        for name in ["Fixture farm - satellite.mbtiles", "Fixture farm - elevation.mbtiles"] {
            // A copy, as the app restores (a store may write to its file when opened).
            let source = dir.appendingPathComponent(name)
            XCTAssertFalse(FileManager.default.fileExists(atPath: source.path + "-wal"), "\(folder): \(name) left a write-ahead log")
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mbtiles")
            try FileManager.default.copyItem(at: source, to: copy)
            defer { try? FileManager.default.removeItem(at: copy) }
            let info = try OfflineBackup.inspect(copy)
            let area = try JSONDecoder().decode(OfflineArea.self, from: info.areaJSON)
            XCTAssertEqual(area, BackupFixture.area, "\(folder): \(name)")
            layers[info.layerKey] = source
            let store = try MBTiles(url: copy, create: false)
            defer { store.close() }
            switch info.layerKey {
            case "imagery":
                for (t, data) in BackupFixture.imageryTiles { XCTAssertEqual(store.tile(t), data, "\(folder): \(t)") }
                XCTAssertTrue(store.isMissing(BackupFixture.missingImagery), folder)
                XCTAssertEqual(store.tileCount(), BackupFixture.imageryTiles.count, folder)
            case "terrain":
                XCTAssertEqual(store.tile(BackupFixture.terrainTile), BackupFixture.terrainData, folder)
            default:
                XCTFail("\(folder): unknown layer \(info.layerKey)")
            }
        }
        XCTAssertEqual(Set(layers.keys), ["imagery", "terrain"], folder)
    }
}

/// The backup both apps' tests expect (android/core/src/test/…/BackupCompatibilityTests.kt has the same values).
enum BackupFixture {
    static let layers = [
        OfflineBackup.Layer(fileName: "imagery.mbtiles", key: "imagery", label: "satellite"),
        OfflineBackup.Layer(fileName: "terrain.mbtiles", key: "terrain", label: "elevation"),
    ]

    static let area = OfflineArea(
        id: UUID(uuidString: "6F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9")!,
        name: "Fixture farm",
        created: Date(timeIntervalSince1970: 1_761_696_000),
        shape: CoverageShape(polygons: [[GeoPoint(lat: -33.9245, lon: 18.9232), GeoPoint(lat: -33.9201, lon: 18.9302),
                                         GeoPoint(lat: -33.9263, lon: 18.9355), GeoPoint(lat: -33.9301, lon: 18.9268)]],
                             lines: [], points: [GeoPoint(lat: -33.9175, lon: 18.924)], bufferMeters: 150),
        roads: [[GeoPoint(lat: -33.9238, lon: 18.9241), GeoPoint(lat: -33.9259, lon: 18.9312)]],
        preset: nil, isTrack: nil, maxZoom: 18, allowCellular: false, status: .complete, failedTiles: 0,
        bytesOnDisk: 2_345_678_901, problem: nil)

    /// Two imagery tiles (any bytes do; real ones are JPEG) and one known to be missing.
    static let imageryTiles: [(TileID, Data)] = [
        (TileID(z: 17, x: 72427, y: 79652), Data((0..<300).map { UInt8($0 % 251) })),
        (TileID(z: 18, x: 144855, y: 159305), Data("not really a JPEG".utf8)),
    ]
    static let missingImagery = TileID(z: 19, x: 289710, y: 318610)

    static let terrainTile = TileID(z: 12, x: 2390, y: 2342)
    static var terrainData: Data {
        let url = Bundle.module.url(forResource: "Fixtures/terrarium_12_2390_2342", withExtension: "png")!
        return try! Data(contentsOf: url)
    }
}
