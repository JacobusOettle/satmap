import Foundation
import XCTest
@testable import SatMapCore

private func fixture(_ name: String) throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/" + name, withExtension: nil))
    return try Data(contentsOf: url)
}

private func tempDir() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("satmap-tests-" + UUID().uuidString)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

final class GeoTests: XCTestCase {
    func testMercatorRoundTrip() {
        for p in [GeoPoint(lat: -25.47, lon: 30.97), GeoPoint(lat: 60, lon: -120), GeoPoint(lat: 0, lon: 0)] {
            let w = WebMercator.world(p)
            let q = WebMercator.geo(worldX: w.x, worldY: w.y)
            XCTAssertEqual(p.lat, q.lat, accuracy: 1e-9)
            XCTAssertEqual(p.lon, q.lon, accuracy: 1e-9)
        }
    }

    func testTileContaining() {
        // Same tile as the Esri probe used while designing (z18, Mbombela).
        let t = TileID.containing(GeoPoint(lat: -25.47, lon: 30.97), z: 18)
        XCTAssertEqual(t.x, 153623)
        XCTAssertEqual(t.y, 150261)
        XCTAssertEqual(t.parent, TileID(z: 17, x: 76811, y: 75130))
        XCTAssertEqual(t.ancestor(levels: 2), TileID(z: 16, x: 38405, y: 37565))
    }

    func testDistance() {
        // One degree of latitude ≈ 111.2 km on the mean sphere.
        XCTAssertEqual(Geo.distance(GeoPoint(lat: 0, lon: 0), GeoPoint(lat: 1, lon: 0)), 111_195, accuracy: 5)
    }

    func testDouglasPeucker() {
        let pts: [(x: Double, y: Double)] = (0...100).map { (Double($0), $0 == 50 ? 10.0 : 0.0) }
        let s = Simplify.douglasPeucker(pts, tolerance: 1)
        XCTAssertEqual(s.count, 5)
        XCTAssertEqual(s[2].y, 10)
    }
}

final class TileCoverageTests: XCTestCase {
    /// Brute force: does the polygon intersect the closed tile square?
    private func intersects(_ poly: [(Double, Double)], tileX: Int, tileY: Int) -> Bool {
        let x0 = Double(tileX), y0 = Double(tileY), x1 = x0 + 1, y1 = y0 + 1
        func inside(_ px: Double, _ py: Double) -> Bool {
            var c = false
            var j = poly.count - 1
            for i in 0..<poly.count {
                let (xi, yi) = poly[i], (xj, yj) = poly[j]
                if (yi > py) != (yj > py), px < (xj - xi) * (py - yi) / (yj - yi) + xi { c.toggle() }
                j = i
            }
            return c
        }
        for (px, py) in poly where px >= x0 && px <= x1 && py >= y0 && py <= y1 { return true }
        for (cx, cy) in [(x0, y0), (x1, y0), (x0, y1), (x1, y1)] where inside(cx, cy) { return true }
        func segs(_ a: (Double, Double), _ b: (Double, Double), _ c: (Double, Double), _ d: (Double, Double)) -> Bool {
            func o(_ p: (Double, Double), _ q: (Double, Double), _ r: (Double, Double)) -> Double {
                (q.0 - p.0) * (r.1 - p.1) - (q.1 - p.1) * (r.0 - p.0)
            }
            let d1 = o(c, d, a), d2 = o(c, d, b), d3 = o(a, b, c), d4 = o(a, b, d)
            return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
        }
        let edges = [((x0, y0), (x1, y0)), ((x1, y0), (x1, y1)), ((x1, y1), (x0, y1)), ((x0, y1), (x0, y0))]
        for i in 0..<poly.count {
            let a = poly[i], b = poly[(i + 1) % poly.count]
            for e in edges where segs(a, b, e.0, e.1) { return true }
        }
        return false
    }

    func testPolygonCoverageMatchesBruteForce() {
        var rng = SystemRandomNumberGenerator()
        for trial in 0..<40 {
            // A random star-shaped polygon around Mbombela, ~2–6 km across.
            let c = GeoPoint(lat: -25.47, lon: 30.97)
            let n = Int.random(in: 3...14, using: &rng)
            var ring: [GeoPoint] = []
            for k in 0..<n {
                let a = Double(k) / Double(n) * 2 * .pi + Double.random(in: -0.2...0.2, using: &rng)
                let r = Double.random(in: 0.005...0.03, using: &rng)
                ring.append(GeoPoint(lat: c.lat + r * sin(a), lon: c.lon + r * cos(a)))
            }
            let shape = CoverageShape(polygons: [ring])
            for z in [10, 13, 15] {
                let cov = TileCoverage(shape: shape, zoom: z)
                let n2 = Double(1 << z)
                let poly = ring.map { p -> (Double, Double) in let w = WebMercator.world(p); return (w.x * n2, w.y * n2) }
                let b = GeoBounds(points: ring)!.tileRange(z: z)
                var expected = Set<TileID>()
                for y in (b.minY - 1)...(b.maxY + 1) {
                    for x in (b.minX - 1)...(b.maxX + 1) where intersects(poly, tileX: x, tileY: y) {
                        expected.insert(TileID(z: z, x: x, y: y))
                    }
                }
                var got = Set<TileID>()
                cov.forEachTile { got.insert($0) }
                // Coverage may include tiles that only touch the polygon at an edge/corner; it must never miss one.
                XCTAssertTrue(expected.isSubset(of: got), "trial \(trial) z\(z) missed \(expected.subtracting(got))")
                let extra = got.subtracting(expected)
                for t in extra {
                    // Any extra tile must at least touch the polygon's bounding box.
                    XCTAssertTrue(t.x >= b.minX - 1 && t.x <= b.maxX + 1 && t.y >= b.minY - 1 && t.y <= b.maxY + 1)
                }
                XCTAssertLessThanOrEqual(extra.count, max(2, expected.count / 10), "too many extra tiles z\(z)")
                XCTAssertEqual(cov.tileCount, got.count)
                for t in got { XCTAssertTrue(cov.contains(t)) }
            }
        }
    }

    func testBufferAndLinesAndPoints() {
        let p = GeoPoint(lat: -25.47, lon: 30.97)
        let pointOnly = TileCoverage(shape: CoverageShape(points: [p]), zoom: 18)
        XCTAssertEqual(pointOnly.tileCount, 1)
        // 200 m buffer at z18 (~137 m tiles at this latitude) → roughly 4×4 tiles.
        let buffered = TileCoverage(shape: CoverageShape(points: [p], bufferMeters: 200), zoom: 18)
        XCTAssertGreaterThanOrEqual(buffered.tileCount, 9)
        XCTAssertLessThanOrEqual(buffered.tileCount, 25)
        let line = TileCoverage(shape: CoverageShape(lines: [[p, GeoPoint(lat: -25.47, lon: 31.0)]]), zoom: 16)
        XCTAssertGreaterThanOrEqual(line.tileCount, 5)
        XCTAssertEqual(line.rows.count, 1)
    }

    func testEmpty() {
        XCTAssertEqual(TileCoverage(shape: CoverageShape(), zoom: 12).tileCount, 0)
    }
}

final class DecoderTests: XCTestCase {
    func testPNGColourTypesAgainstPillow() throws {
        for name in ["rgba", "palette", "gray"] {
            let img = try XCTUnwrap(PNGDecoder.decode(try fixture(name + ".png")), name)
            let ref = [UInt8](try fixture(name + ".rgba"))
            XCTAssertEqual(img.width, 37)
            XCTAssertEqual(img.height, 23)
            XCTAssertEqual(img.pixels, ref, name)
        }
    }

    func testTerrariumAgainstPillow() throws {
        for name in ["terrarium_12_2390_2342", "terrarium_12_2391_2342"] {
            let tile = try XCTUnwrap(Terrarium.decode(try fixture(name + ".png"), id: TileID(z: 12, x: 0, y: 0)))
            XCTAssertEqual(tile.size, 256)
            let ref = try JSONSerialization.jsonObject(with: try fixture(name + ".json")) as! [String: Double]
            for (k, v) in ref {
                let xy = k.split(separator: ",").map { Int($0)! }
                XCTAssertEqual(Double(tile.values[xy[1] * 256 + xy[0]]), v, accuracy: 0.001, "\(name) \(k)")
            }
        }
    }

    func testKMLAndKMZ() throws {
        for name in ["blocks.kml", "blocks.kmz"] {
            let doc = try KMLParser.parse(try fixture(name))
            XCTAssertEqual(doc.name, "Farm Blocks")
            XCTAssertEqual(doc.features.count, 4, name)
            XCTAssertEqual(doc.features[0].name, "Block A1")
            guard case .polygon(let outer, let holes) = doc.features[0].geometry else { return XCTFail("polygon") }
            XCTAssertEqual(outer.count, 5)
            XCTAssertEqual(outer[1].lon, 30.975, accuracy: 1e-9)
            XCTAssertEqual(outer[1].lat, -25.47, accuracy: 1e-9)
            XCTAssertEqual(holes.count, 1)
            XCTAssertEqual(doc.features[1].name, "Block A2 & co")
            guard case .polygon = doc.features[1].geometry else { return XCTFail("multigeometry polygon") }
            guard case .line(let l) = doc.features[2].geometry else { return XCTFail("line") }
            XCTAssertEqual(l.count, 2)
            guard case .point(let p) = doc.features[3].geometry else { return XCTFail("point") }
            XCTAssertEqual(p.lat, -25.46, accuracy: 1e-9)
            let shape = doc.coverageShape(bufferMeters: 100)
            XCTAssertEqual(shape.polygons.count, 2)
            XCTAssertEqual(shape.lines.count, 1)
            XCTAssertEqual(shape.points.count, 1)
        }
    }

    func testRejectsGarbage() {
        XCTAssertThrowsError(try KMLParser.parse(Data("hello".utf8)))
        XCTAssertNil(PNGDecoder.decode(Data([1, 2, 3])))
    }
}

final class TerrainTests: XCTestCase {
    private func loadTiles() throws -> [TileID: DEMTile] {
        var tiles: [TileID: DEMTile] = [:]
        for x in [2390, 2391] {
            let id = TileID(z: 12, x: x, y: 2342)
            tiles[id] = try XCTUnwrap(Terrarium.decode(try fixture("terrarium_12_\(x)_2342.png"), id: id))
        }
        return tiles
    }

    func testSamplerPixelCentresAndSeam() throws {
        let tiles = try loadTiles()
        let sampler = DEMSampler(zoom: 12) { tiles[$0] }
        let a = tiles[TileID(z: 12, x: 2390, y: 2342)]!
        let b = tiles[TileID(z: 12, x: 2391, y: 2342)]!
        let scale = Double(256 << 12)
        // Exactly at a pixel centre the bilinear sample equals the pixel value.
        let px = 2390 * 256 + 128, py = 2342 * 256 + 77
        let e = try XCTUnwrap(sampler.elevation(worldX: (Double(px) + 0.5) / scale, worldY: (Double(py) + 0.5) / scale))
        XCTAssertEqual(e, Double(a.values[77 * 256 + 128]), accuracy: 1e-3)
        // Half-way between the last pixel of tile a and the first of tile b: the mean of both.
        let seamX = Double(2391 * 256) / scale
        let s = try XCTUnwrap(sampler.elevation(worldX: seamX, worldY: (Double(py) + 0.5) / scale))
        XCTAssertEqual(s, Double(a.values[77 * 256 + 255] + b.values[77 * 256 + 0]) / 2, accuracy: 1e-3)

        // Grids of neighbouring map tiles agree on their shared edge.
        let ta = TileID(z: 12, x: 2390, y: 2342).worldBounds, tb = TileID(z: 12, x: 2391, y: 2342).worldBounds
        let ga = try XCTUnwrap(sampler.grid(minX: ta.minX, minY: ta.minY, maxX: ta.maxX, maxY: ta.maxY, nodesX: 65, nodesY: 65))
        let gb = try XCTUnwrap(sampler.grid(minX: tb.minX, minY: tb.minY, maxX: tb.maxX, maxY: tb.maxY, nodesX: 65, nodesY: 65))
        for j in 0..<65 { XCTAssertEqual(ga[j * 65 + 64], gb[j * 65], accuracy: 1e-3) }
    }

    func testMissingTileReturnsNil() throws {
        let sampler = DEMSampler(zoom: 12) { _ in nil }
        XCTAssertNil(sampler.elevation(at: GeoPoint(lat: -25.4, lon: 30.1)))
    }
}

final class ContourTests: XCTestCase {
    func testConeGivesConcentricClosedRings() {
        let n = 101
        var v = [Float](repeating: 0, count: n * n)
        for j in 0..<n { for i in 0..<n { v[j * n + i] = Float(100 - hypot(Double(i - 50), Double(j - 50))) } }
        let lines = MarchingSquares.contours(values: v, width: n, height: n, step: 10)
        let closed = lines.filter { $0.isClosed }
        // Levels 50...90 fit inside the grid (radius ≤ 50, just inside the border); 100 is the single peak node so no ring.
        XCTAssertEqual(Set(closed.map(\.level)), [50, 60, 70, 80, 90])
        for l in closed {
            let r = 100 - l.level
            for p in l.points {
                XCTAssertEqual(hypot(p.x - 50, p.y - 50), r, accuracy: 0.6)
            }
        }
        // Levels 10...40 hit the grid border and must be open lines with ends on the border.
        for l in lines where !l.isClosed {
            for e in [l.points.first!, l.points.last!] {
                XCTAssertTrue(e.x == 0 || e.y == 0 || e.x == Double(n - 1) || e.y == Double(n - 1))
            }
        }
    }

    func testSaddleProducesTwoSegments() {
        // tl=1, tr=0, bl=0, br=1 at level 0.5 → two separate segments, each cutting off one low corner
        let lines = MarchingSquares.contours(values: [1, 0, 0, 1], width: 2, height: 2, step: 0.5).filter { $0.level == 0.5 }
        XCTAssertEqual(lines.count, 2)
        XCTAssertTrue(lines.allSatisfy { $0.points.count == 2 && !$0.isClosed })
        // A node exactly on a level never yields a corner-to-corner diagonal.
        let exact = MarchingSquares.contours(values: [1, 0, 0, 1], width: 2, height: 2, step: 1).filter { $0.level == 1 }
        XCTAssertTrue(exact.isEmpty)
    }

    func testPolicy() {
        // A 3x iPhone asks for z17 tiles when showing roughly 1 km across: web zoom ≈ 15.4 → 10 m lines.
        let zp = ContourPolicy.pointZoom(tileZoom: 17, screenScale: 3)
        XCTAssertEqual(zp, 15.415, accuracy: 0.01)
        XCTAssertEqual(ContourPolicy.intervals(forPointZoom: zp)?.step, 10)
        XCTAssertNil(ContourPolicy.intervals(forPointZoom: 10.9))
        XCTAssertEqual(ContourPolicy.intervals(forPointZoom: 18)?.step, 5)
        XCTAssertEqual(ContourPolicy.intervals(forPointZoom: 18)?.index, 25)
        XCTAssertEqual(ContourPolicy.demZoom(forTileZoom: 19), 15)
        XCTAssertEqual(ContourPolicy.gridCells(forTileZoom: 19), 128)
    }

    func testLabelAnchor() {
        let pts: [(x: Double, y: Double)] = [(0, 100), (200, 100)]
        let a = ContourGeometry.labelAnchor(pts, minLength: 100, bounds: (256, 256), margin: 10)
        XCTAssertNotNil(a)
        XCTAssertEqual(a!.x, 100, accuracy: 1e-9)
        XCTAssertEqual(a!.angle, 0, accuracy: 1e-9)
        let reversed = ContourGeometry.labelAnchor(Array(pts.reversed()), minLength: 100, bounds: (256, 256), margin: 10)
        XCTAssertEqual(reversed!.angle, 0, accuracy: 1e-9) // kept upright
        XCTAssertNil(ContourGeometry.labelAnchor(pts, minLength: 300, bounds: (256, 256), margin: 10))
    }

    func testChaikinKeepsEnds() {
        let s = ContourGeometry.chaikin([(0, 0), (10, 0), (10, 10)], closed: false)
        XCTAssertEqual(s.first!.x, 0)
        XCTAssertEqual(s.last!.y, 10)
        XCTAssertEqual(s.count, 6)
    }
}

final class StorageTests: XCTestCase {
    func testMBTilesRoundTrip() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("t.mbtiles")
        let w = try MBTiles(url: url, create: true)
        for x in 0..<300 { w.put(TileID(z: 10, x: x, y: 5), data: Data([UInt8(x & 0xFF), 1, 2])) }
        w.putMissing(TileID(z: 11, x: 4, y: 10))
        w.setMetadata(["name": "test", "format": "jpg"])
        w.flush()
        let r = try MBTiles(url: url, create: false)
        XCTAssertEqual(r.tile(TileID(z: 10, x: 7, y: 5)), Data([7, 1, 2]))
        XCTAssertNil(r.tile(TileID(z: 10, x: 7, y: 6)))
        XCTAssertEqual(r.existingColumns(z: 10, y: 5, xs: 5...9), [5, 6, 7, 8, 9])
        XCTAssertEqual(r.missingColumns(z: 11, y: 10, xs: 0...10), [4])
        XCTAssertEqual(r.tileCount(), 300)
        XCTAssertEqual(r.maxStoredZoom(), 10)
        XCTAssertTrue(r.contains(TileID(z: 10, x: 299, y: 5)))
        w.close()
        r.close()
        XCTAssertThrowsError(try MBTiles(url: dir.appendingPathComponent("nope.mbtiles"), create: false))
    }

    func testTrackLog() throws {
        let url = tempDir().appendingPathComponent("track.bin")
        let log = TrackLog(url: url)
        let pts = (0..<5).map { TrackPoint(lat: -25 + Double($0) * 0.001, lon: 30, time: 1000 + Double($0), startsSegment: $0 == 3) }
        pts.forEach(log.append)
        XCTAssertEqual(log.loadAll(), pts)
        // A torn write leaves a partial record: it is ignored on load and trimmed on the next append.
        let h = try FileHandle(forWritingTo: url)
        try h.seekToEnd()
        try h.write(contentsOf: Data([1, 2, 3]))
        try h.close()
        let log2 = TrackLog(url: url)
        XCTAssertEqual(log2.loadAll(), pts)
        let extra = TrackPoint(lat: 1, lon: 2, time: 3, startsSegment: false)
        log2.append(extra)
        XCTAssertEqual(log2.loadAll(), pts + [extra])
        log2.clear()
        XCTAssertEqual(log2.loadAll(), [])
    }
}

/// A fake imagery server: tiles exist everywhere up to `deepest` (and z+1 only inside a small patch).
private final class FakeServer: TileFetching {
    let deepest: Int
    let patch: TileID // tiles under this one exist one level deeper
    var failFirstAttemptFor: Set<TileID> = []
    var offlineAfter: Int?
    private let lock = NSLock()
    private(set) var requests: [TileID] = []

    init(deepest: Int, patch: TileID) {
        self.deepest = deepest
        self.patch = patch
    }

    func fetch(_ tile: TileID) async -> TileFetchResult {
        let (count, failNow) = lock.withLock { () -> (Int, Bool) in
            requests.append(tile)
            return (requests.count, failFirstAttemptFor.remove(tile) != nil)
        }
        if let o = offlineAfter, count > o { return .offline }
        if failNow { return .failed }
        let exists = tile.z <= deepest || (tile.z == deepest + 1 && tile.z > patch.z && tile.ancestor(levels: tile.z - patch.z) == patch)
        return exists ? .data(Data("\(tile)".utf8)) : .missing
    }
}

final class DownloadEngineTests: XCTestCase {
    private let area = CoverageShape(polygons: [[
        GeoPoint(lat: -25.465, lon: 30.965), GeoPoint(lat: -25.465, lon: 30.975),
        GeoPoint(lat: -25.475, lon: 30.975), GeoPoint(lat: -25.475, lon: 30.965),
    ]])

    private func run(_ job: TileDownloadJob) async -> (DownloadOutcome, DownloadProgress) {
        var last = DownloadProgress()
        let outcome = await job.run { last = $0 }
        return (outcome, last)
    }

    func testPrunesBelowDeepestAvailableLevel() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let patch = TileID.containing(GeoPoint(lat: -25.47, lon: 30.97), z: 16)
        let server = FakeServer(deepest: 16, patch: patch)
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 0...22, pruneByParent: true)],
                                  concurrency: 4, retryDelay: { _ in 0 })
        let (outcome, progress) = await run(job)
        XCTAssertEqual(outcome, .completed(failed: 0))
        // Every requested tile is inside the coverage, and nothing deeper than z18 was ever requested:
        // z17 is requested for children of stored z16 tiles; z18 only under the patch's z17 tiles (which 404).
        XCTAssertLessThanOrEqual(server.requests.map(\.z).max()!, 18)
        let z16 = TileCoverage(shape: area, zoom: 16).tileCount
        XCTAssertEqual(server.requests.filter { $0.z == 16 }.count, z16)
        XCTAssertGreaterThan(progress.fetched, 0)
        // Patch children at z17 exist; z18 under them are missing; so z18 was requested only for those children.
        let z17Stored = server.requests.filter { $0.z == 17 && $0.parent == patch }.count
        XCTAssertGreaterThan(z17Stored, 0)
        XCTAssertTrue(server.requests.filter { $0.z == 18 }.allSatisfy { $0.ancestor(levels: 2) == patch })

        // Running again resumes: nothing left to fetch.
        let before = server.requests.count
        let (again, _) = await run(job)
        XCTAssertEqual(again, .completed(failed: 0))
        XCTAssertEqual(server.requests.count, before)
    }

    func testRetriesTransientFailures() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let server = FakeServer(deepest: 14, patch: TileID(z: 0, x: 0, y: 0))
        let target = TileID.containing(GeoPoint(lat: -25.47, lon: 30.97), z: 14)
        server.failFirstAttemptFor = [target]
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 10...14, pruneByParent: true)],
                                  retryDelay: { _ in 0 })
        let (outcome, _) = await run(job)
        XCTAssertEqual(outcome, .completed(failed: 0))
        XCTAssertNotNil(store.tile(target))
        XCTAssertEqual(server.requests.filter { $0 == target }.count, 2)
    }

    func testStopsWhenOfflineAndResumes() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let server = FakeServer(deepest: 17, patch: TileID(z: 0, x: 0, y: 0))
        server.offlineAfter = 20
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 0...17, pruneByParent: true)],
                                  concurrency: 2, retryDelay: { _ in 0 })
        let (outcome, _) = await run(job)
        XCTAssertEqual(outcome, .offline)
        server.offlineAfter = nil
        let (outcome2, _) = await run(job)
        XCTAssertEqual(outcome2, .completed(failed: 0))
        var expected = 0
        for z in 0...17 { expected += TileCoverage(shape: area, zoom: z).tileCount }
        XCTAssertEqual(store.tileCount(), expected)
    }

    func testStopsOnStorageError() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        store.close() // every write now fails, as on a full disk
        let server = FakeServer(deepest: 14, patch: TileID(z: 0, x: 0, y: 0))
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 0...14, pruneByParent: true)],
                                  retryDelay: { _ in 0 })
        let (outcome, progress) = await run(job)
        guard case .storageError = outcome else { return XCTFail("expected storage error, got \(outcome)") }
        XCTAssertEqual(progress.fetched, 0)
        XCTAssertLessThan(server.requests.count, 20) // stopped at the first level
    }

    func testCancellation() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let server = FakeServer(deepest: 20, patch: TileID(z: 0, x: 0, y: 0))
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 0...20, pruneByParent: true)],
                                  concurrency: 2, retryDelay: { _ in 0 })
        let task = Task { await job.run { _ in } }
        task.cancel()
        let outcome = await task.value
        XCTAssertEqual(outcome, .cancelled)
    }
}
