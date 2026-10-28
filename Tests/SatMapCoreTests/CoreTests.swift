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

    func testPathLengthAndShapeArea() {
        // A 100 m × 100 m square near Skukuza (1 ha), measured corner to corner.
        let lat = -24.99, lon = 31.59
        let dLat = 100 / 110_574.0, dLon = 100 / (111_320.0 * cos(lat * .pi / 180))
        let square = [GeoPoint(lat: lat, lon: lon), GeoPoint(lat: lat, lon: lon + dLon),
                      GeoPoint(lat: lat + dLat, lon: lon + dLon), GeoPoint(lat: lat + dLat, lon: lon)]
        XCTAssertEqual(Geo.pathLength(square), 300, accuracy: 1)
        XCTAssertEqual(Geo.pathLength(square + [square[0]]), 400, accuracy: 1.5)
        XCTAssertEqual(Geo.ringArea(square), 10_000, accuracy: 100)
        XCTAssertEqual(Geo.ringArea(square.reversed()), 10_000, accuracy: 100) // either direction round
        XCTAssertEqual(Geo.pathLength([square[0]]), 0)
    }

    func testLabelPointInsideConcaveBlock() {
        // An L-shaped block whose centroid lies outside it.
        let l = [(0.0, 0.0), (0.01, 0.0), (0.01, 0.002), (0.002, 0.002), (0.002, 0.01), (0.0, 0.01)]
            .map { GeoPoint(lat: -25.47 + $0.1, lon: 30.97 + $0.0) }
        let p = PolygonLabel.interiorPoint(outer: l)!
        let inX = p.lon - 30.97, inY = p.lat + 25.47
        XCTAssertTrue((inX >= 0 && inX <= 0.01 && inY >= 0 && inY <= 0.002) || (inX >= 0 && inX <= 0.002 && inY >= 0 && inY <= 0.01),
                      "label at \(inX), \(inY) is outside the L")
        // A convex block keeps its centroid.
        let sq = [(0.0, 0.0), (0.01, 0.0), (0.01, 0.01), (0.0, 0.01)].map { GeoPoint(lat: -25.47 + $0.1, lon: 30.97 + $0.0) }
        let c = PolygonLabel.interiorPoint(outer: sq)!
        XCTAssertEqual(c.lon, 30.975, accuracy: 1e-9)
        XCTAssertEqual(c.lat, -25.465, accuracy: 1e-9)
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

    func testLassoThatLoopsPastItsStartCoversEverythingInside() {
        // A finger-drawn circle that overshoots its start by 15% of a turn and is closed with a chord.
        let c = GeoPoint(lat: -25.47, lon: 30.97)
        func ring(turns: Double) -> [GeoPoint] {
            let n = Int(120 * turns)
            return (0...n).map { k in
                let a = Double(k) / 120 * 2 * .pi
                return GeoPoint(lat: c.lat + 0.005 * sin(a), lon: c.lon + 0.005 * cos(a))
            }
        }
        let single = TileCoverage(shape: CoverageShape(polygons: [ring(turns: 1)]), zoom: 18)
        let looped = TileCoverage(shape: CoverageShape(polygons: [ring(turns: 1.15)]), zoom: 18)
        var missing = 0
        single.forEachTile { if !looped.contains($0) { missing += 1 } }
        XCTAssertEqual(missing, 0)
        XCTAssertGreaterThan(single.tileCount, 40)
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
            XCTAssertEqual(doc.features[0].style?.lineColor, KMLColor(kml: "ff00ffff"))
            XCTAssertNil(doc.features[1].style)
            let shape = doc.coverageShape(bufferMeters: 100)
            XCTAssertEqual(shape.polygons.count, 2)
            XCTAssertEqual(shape.lines.count, 1)
            XCTAssertEqual(shape.points.count, 1)
        }
    }

    func testKMLStyles() throws {
        // Shared styles (also after the placemarks using them), a style map's normal style (linked or its own), a
        // placemark's own style over its shared one, and colours written aabbggrr.
        let kml = """
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
          <Placemark><name>A</name><styleUrl>#map</styleUrl>
            <Polygon><outerBoundaryIs><LinearRing><coordinates>31,-25 31.1,-25 31.1,-25.1 31,-25</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
          <Placemark><name>B</name><styleUrl>#green</styleUrl>
            <Style><LineStyle><width>4</width></LineStyle><PolyStyle><fill>0</fill></PolyStyle></Style>
            <LineString><coordinates>31,-25 31.1,-25.1</coordinates></LineString></Placemark>
          <Placemark><name>C</name><styleUrl>#inlineMap</styleUrl><Point><coordinates>31,-25</coordinates></Point></Placemark>
          <Placemark><name>D</name><styleUrl>#missing</styleUrl><Point><coordinates>31.2,-25</coordinates></Point></Placemark>
          <StyleMap id="map"><Pair><key>normal</key><styleUrl>#green</styleUrl></Pair><Pair><key>highlight</key><styleUrl>#red</styleUrl></Pair></StyleMap>
          <StyleMap id="inlineMap"><Pair><key>highlight</key><styleUrl>#red</styleUrl></Pair>
            <Pair><key>normal</key><Style><IconStyle><color>#ff0000ff</color></IconStyle></Style></Pair></StyleMap>
          <Style id="green"><LineStyle><color>ff6ed035</color><width>2</width></LineStyle><PolyStyle><color>596ed035</color></PolyStyle></Style>
          <Style id="red"><LineStyle><color>ff0000ff</color></LineStyle></Style>
        </Document></kml>
        """
        let doc = try KMLParser.parse(Data(kml.utf8))
        XCTAssertEqual(doc.features.count, 4)
        let green = try XCTUnwrap(doc.features[0].style)
        XCTAssertEqual(green.lineWidth, 2)
        let line = try XCTUnwrap(green.lineColor)
        XCTAssertEqual(line.red, 0x35 / 255.0, accuracy: 1e-9)
        XCTAssertEqual(line.green, 0xd0 / 255.0, accuracy: 1e-9)
        XCTAssertEqual(line.blue, 0x6e / 255.0, accuracy: 1e-9)
        XCTAssertEqual(line.alpha, 1, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(green.fillColor).alpha, 0x59 / 255.0, accuracy: 1e-9)
        let own = try XCTUnwrap(doc.features[1].style)
        XCTAssertEqual(own.lineWidth, 4)
        XCTAssertEqual(own.lineColor, green.lineColor)
        XCTAssertEqual(own.fill, false)
        XCTAssertEqual(try XCTUnwrap(doc.features[2].style?.iconColor).red, 1, accuracy: 1e-9)
        XCTAssertNil(doc.features[3].style)
        // Google Earth Web and the Earth app: shared styles in gx:CascadingStyle, which holds the id.
        let earth = """
        <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2"><Document>
          <gx:CascadingStyle kml:id="__managed_style_N"><Style><LineStyle><color>ff2dc0fb</color><width>3.2</width></LineStyle>
            <PolyStyle><color>402dc0fb</color></PolyStyle></Style></gx:CascadingStyle>
          <gx:CascadingStyle kml:id="__managed_style_H"><Style><LineStyle><color>ff2dc0fb</color><width>4.8</width></LineStyle></Style></gx:CascadingStyle>
          <StyleMap id="__managed_style_M"><Pair><key>normal</key><styleUrl>#__managed_style_N</styleUrl></Pair>
            <Pair><key>highlight</key><styleUrl>#__managed_style_H</styleUrl></Pair></StyleMap>
          <Placemark><name>E</name><styleUrl>#__managed_style_M</styleUrl>
            <Polygon><outerBoundaryIs><LinearRing><coordinates>31,-25 31.1,-25 31.1,-25.1 31,-25</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        </Document></kml>
        """
        let web = try XCTUnwrap(KMLParser.parse(Data(earth.utf8)).features.first?.style)
        XCTAssertEqual(web.lineWidth, 3.2)
        XCTAssertEqual(web.lineColor, KMLColor(kml: "ff2dc0fb"))
        XCTAssertEqual(web.fillColor, KMLColor(kml: "402dc0fb"))
        XCTAssertNil(KMLColor(kml: "red"))
        XCTAssertEqual(KMLColor(kml: "0000ff")?.red, 1)
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
        XCTAssertTrue(r.isMissing(TileID(z: 11, x: 4, y: 10)))
        XCTAssertFalse(r.isMissing(TileID(z: 11, x: 5, y: 10)))
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

    /// One progress bar for the whole run (the system's, while SatMap is in the background): it only goes forward and
    /// ends full, and where the imagery stops early it doesn't sit near zero until the end.
    func testOverallProgressOnlyGoesForwardAndEndsFull() async throws {
        let imagery = try MBTiles(url: tempDir().appendingPathComponent("i.mbtiles"), create: true)
        let terrain = try MBTiles(url: tempDir().appendingPathComponent("t.mbtiles"), create: true)
        let patch = TileID.containing(GeoPoint(lat: -25.47, lon: 30.97), z: 16)
        let job = TileDownloadJob(shape: area, layers: [
            DownloadLayer(name: "dem", store: terrain, fetcher: FakeServer(deepest: 12, patch: patch), zooms: 0...14, pruneByParent: false),
            DownloadLayer(name: "sat", store: imagery, fetcher: FakeServer(deepest: 16, patch: patch), zooms: 0...22, pruneByParent: true),
        ], concurrency: 4, retryDelay: { _ in 0 })
        var seen: [DownloadProgress] = []
        let outcome = await job.run { seen.append($0) }
        XCTAssertEqual(outcome, .completed(failed: 0))
        let done = seen.map(\.overallDone)
        XCTAssertEqual(done, done.sorted(), "went backwards")
        XCTAssertGreaterThan(seen.last!.overallTotal, 0)
        XCTAssertEqual(seen.last?.overallDone, seen.last?.overallTotal)
        // At the start everything up to zoom 22 counts; once the imagery runs out the deeper levels stop counting.
        let planned = (0...14).reduce(0) { $0 + TileCoverage(shape: area, zoom: $1).tileCount }
            + (0...22).reduce(0) { $0 + TileCoverage(shape: area, zoom: $1).tileCount }
        XCTAssertEqual(seen.first?.overallTotal, planned)
        XCTAssertLessThan(seen.last!.overallTotal, planned / 100)
        // The level where the imagery stops (z17: all missing but the patch) fills the bar, rather than a jump at the end.
        let lastAt17 = try XCTUnwrap(seen.last { $0.layer == "sat" && $0.zoom == 17 })
        XCTAssertGreaterThan(Double(lastAt17.overallDone) / Double(lastAt17.overallTotal), 0.5)
    }

    /// Choosing a deeper level for a downloaded area runs the job again with a higher maximum: only the new level
    /// is fetched (stored tiles and tiles known to be missing are skipped).
    func testDeeperLevelFetchesOnlyTheNewLevel() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let server = FakeServer(deepest: 19, patch: TileID(z: 0, x: 0, y: 0))
        func job(_ maxZoom: Int) -> TileDownloadJob {
            TileDownloadJob(shape: area, layers: [DownloadLayer(name: "sat", store: store, fetcher: server, zooms: 0...maxZoom, pruneByParent: true)],
                            concurrency: 4, retryDelay: { _ in 0 })
        }
        let firstRun = await job(17).run { _ in }
        XCTAssertEqual(firstRun, .completed(failed: 0))
        let first = server.requests.count
        let secondRun = await job(18).run { _ in }
        XCTAssertEqual(secondRun, .completed(failed: 0))
        let again = server.requests[first...]
        XCTAssertFalse(again.isEmpty)
        XCTAssertTrue(again.allSatisfy { $0.z == 18 }, "only zoom 18 is fetched the second time")
        XCTAssertEqual(again.count, TileCoverage(shape: area, zoom: 18).tileCount)
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

    func testStopsAsOfflineWhenEveryRequestFails() async throws {
        final class DeadInternet: TileFetching {
            var count = 0
            func fetch(_ tile: TileID) async -> TileFetchResult {
                count += 1
                return .failed
            }
        }
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let server = DeadInternet()
        let job = TileDownloadJob(shape: area, layers: [DownloadLayer(name: "dem", store: store, fetcher: server, zooms: 0...16, pruneByParent: false)],
                                  concurrency: 2, maxAttempts: 1, outageThreshold: 5, retryDelay: { _ in 0 })
        let (outcome, _) = await run(job)
        XCTAssertEqual(outcome, .offline)
        XCTAssertLessThan(server.count, 10)
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

final class RoadsTests: XCTestCase {
    /// About 1.1 km × 1.1 km.
    private let square = CoverageShape(polygons: [[
        GeoPoint(lat: -33.910, lon: 18.910), GeoPoint(lat: -33.910, lon: 18.922),
        GeoPoint(lat: -33.920, lon: 18.922), GeoPoint(lat: -33.920, lon: 18.910),
    ]])

    func testQueryHasOneBoxPerPartAndOnlyVehicleRoads() throws {
        let q = try XCTUnwrap(Roads.overpassQuery(for: square))
        XCTAssertTrue(q.hasPrefix("[out:json]"))
        XCTAssertTrue(q.hasSuffix("out geom;"))
        XCTAssertTrue(q.contains("track|"))
        XCTAssertFalse(q.contains("footway"))
        XCTAssertEqual(q.components(separatedBy: "way[").count - 1, 1)
        // The box is the square plus the corridor margin (15 m ≈ 0.000135° of latitude).
        XCTAssertTrue(q.contains("(-33.920135,"))

        let twoBlocks = CoverageShape(polygons: square.polygons + [square.polygons[0].map { GeoPoint(lat: $0.lat - 1, lon: $0.lon) }],
                                      bufferMeters: 150)
        XCTAssertEqual(try XCTUnwrap(Roads.overpassQuery(for: twoBlocks)).components(separatedBy: "way[").count - 1, 2)
        XCTAssertNil(Roads.overpassQuery(for: CoverageShape()))
    }

    func testParsesWaysAndSkipsMissingNodes() throws {
        let json = """
        {"version":0.6,"elements":[
          {"type":"way","id":1,"tags":{"highway":"track","surface":"gravel"},
           "geometry":[{"lat":-33.91,"lon":18.91},{"lat":-33.911,"lon":18.912},null,{"lat":-33.912,"lon":18.913},{"lat":-33.913,"lon":18.914}]},
          {"type":"way","id":2,"geometry":[{"lat":-33.915,"lon":18.915}]},
          {"type":"node","id":3,"lat":-33.9,"lon":18.9}
        ]}
        """
        let lines = try Roads.parse(Data(json.utf8))
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(lines[0], [GeoPoint(lat: -33.91, lon: 18.91), GeoPoint(lat: -33.911, lon: 18.912)])
        XCTAssertEqual(lines[1].count, 2)

        let partial = #"{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 121 seconds."}"#
        XCTAssertThrowsError(try Roads.parse(Data(partial.utf8)))
        XCTAssertThrowsError(try Roads.parse(Data("<html>Too many requests</html>".utf8)))
    }

    func testClipKeepsOnlyThePartsInsideTheArea() {
        // West to east through the middle, starting and ending about 1 km outside, with a bend inside.
        let through = [GeoPoint(lat: -33.915, lon: 18.899), GeoPoint(lat: -33.915, lon: 18.916),
                       GeoPoint(lat: -33.914, lon: 18.933)]
        let outside = [GeoPoint(lat: -33.930, lon: 18.910), GeoPoint(lat: -33.930, lon: 18.930)]
        let clipped = Roads.clip([through, outside], to: square)
        XCTAssertEqual(clipped.count, 1)
        // The square is 0.012° (about 1.11 km) wide; tiles at zoom 21 are about 16 m.
        let width = Geo.distance(GeoPoint(lat: -33.915, lon: 18.910), GeoPoint(lat: -33.915, lon: 18.922))
        XCTAssertEqual(Roads.length(clipped), width, accuracy: 45)
        for p in clipped[0] {
            XCTAssertGreaterThan(p.lon, 18.910 - 0.0004)
            XCTAssertLessThan(p.lon, 18.922 + 0.0004)
        }
        // Collinear points from the 10 m checks are simplified away; the bend stays.
        XCTAssertLessThanOrEqual(clipped[0].count, 4)
        XCTAssertTrue(clipped[0].contains(GeoPoint(lat: -33.915, lon: 18.916)))

        // A road leaving and re-entering a U-shaped area becomes two pieces.
        let u = CoverageShape(polygons: [[
            GeoPoint(lat: -33.910, lon: 18.910), GeoPoint(lat: -33.910, lon: 18.914), GeoPoint(lat: -33.918, lon: 18.914),
            GeoPoint(lat: -33.918, lon: 18.918), GeoPoint(lat: -33.910, lon: 18.918), GeoPoint(lat: -33.910, lon: 18.922),
            GeoPoint(lat: -33.920, lon: 18.922), GeoPoint(lat: -33.920, lon: 18.910),
        ]])
        XCTAssertEqual(Roads.clip([[GeoPoint(lat: -33.912, lon: 18.905), GeoPoint(lat: -33.912, lon: 18.927)]], to: u).count, 2)
    }

    func testTrackLinesSplitAtBreaksAndSimplify() {
        // 40 points walking east every ~10 m in a straight line, a break, then a short second walk and a lone point.
        var track = (0..<40).map { TrackPoint(lat: -24.99, lon: 31.59 + Double($0) * 0.0001, time: Double($0), startsSegment: $0 == 0) }
        track += (0..<3).map { TrackPoint(lat: -25.0 - Double($0) * 0.0001, lon: 31.6, time: 100 + Double($0), startsSegment: $0 == 0) }
        track.append(TrackPoint(lat: -25.01, lon: 31.61, time: 200, startsSegment: true))
        let lines = Roads.trackLines(track)
        XCTAssertEqual(lines.count, 3)
        XCTAssertEqual(lines[0], [track[0].geo, track[39].geo]) // a straight walk keeps only its ends
        XCTAssertEqual(lines[1].count, 2)
        XCTAssertEqual(lines[2], [track[43].geo])
        XCTAssertEqual(Roads.length(lines), 39 * 10.09 + 2 * 11.06, accuracy: 3)
        XCTAssertEqual(Roads.trackLines([]), [])
        // The corridor covers a lone point too.
        XCTAssertGreaterThan(TileCoverage(shape: Roads.corridor([lines[2]]), zoom: 19).tileCount, 0)
    }

    func testTrackAreaKeepsEarlierTripsAfterTheTrackIsCleared() {
        let east = [GeoPoint(lat: -24.99, lon: 31.59), GeoPoint(lat: -24.99, lon: 31.594)]
        let longer = [GeoPoint(lat: -24.99, lon: 31.59), GeoPoint(lat: -24.99, lon: 31.596)]
        let elsewhere = [GeoPoint(lat: -25.05, lon: 31.70), GeoPoint(lat: -25.05, lon: 31.702)]
        // The same track, grown: nothing earlier is kept twice.
        XCTAssertEqual(Roads.mergeTrack(previous: [east], latest: [longer]), [longer])
        // The track was cleared and a new trip recorded: the earlier trip stays in the area.
        let merged = Roads.mergeTrack(previous: [east], latest: [elsewhere])
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(Roads.length(merged), Roads.length([east, elsewhere]), accuracy: 1)
        // A new trip that partly overlaps: only the part not covered again is kept.
        let partly = Roads.mergeTrack(previous: [east], latest: [[GeoPoint(lat: -24.99, lon: 31.592), GeoPoint(lat: -24.99, lon: 31.596)]])
        XCTAssertEqual(Roads.length(partly), Roads.length([longer]), accuracy: 40)
        XCTAssertEqual(Roads.mergeTrack(previous: [], latest: [east]), [east])
        XCTAssertEqual(Roads.mergeTrack(previous: [east], latest: []), [east])

        // A lone point from an earlier trip stays, unless the new track passes it.
        let lone = [GeoPoint(lat: -25.10, lon: 31.80)]
        XCTAssertEqual(Roads.mergeTrack(previous: [east, lone], latest: [elsewhere]).filter { $0.count == 1 }, [lone])
        let through = [GeoPoint(lat: -25.10, lon: 31.799), GeoPoint(lat: -25.10, lon: 31.801)]
        XCTAssertEqual(Roads.mergeTrack(previous: [lone], latest: [through]), [through])
        // An earlier path 20 m beside the new one (another track, the other side of a river) is kept whole;
        // one 3 m off (the same path, recorded again) is not.
        let beside = east.map { GeoPoint(lat: $0.lat + 20 / 110_574, lon: $0.lon) }
        XCTAssertEqual(Roads.length(Roads.mergeTrack(previous: [beside], latest: [east])), 2 * Roads.length([east]), accuracy: 2)
        let same = east.map { GeoPoint(lat: $0.lat + 3 / 110_574, lon: $0.lon) }
        XCTAssertEqual(Roads.mergeTrack(previous: [same], latest: [east]), [east])
        // What a download adds: only the part of the track away from what the area has.
        XCTAssertEqual(Roads.length(Roads.newParts(of: [longer], comparedWith: [east])), Roads.length([longer]) - Roads.length([east]), accuracy: 12)
        XCTAssertEqual(Roads.newParts(of: [east], comparedWith: []), [east])
    }

    func testCorridorWidthDoesNotDependOnOtherPartsOfTheShape() {
        // One track area can hold trips far apart: the corridor near London must not narrow because of a trip in
        // the Kruger (the buffer used to be worked out at the middle latitude of the whole shape).
        let london = [GeoPoint(lat: 51.5, lon: -0.12), GeoPoint(lat: 51.5, lon: -0.11)]
        let kruger = [GeoPoint(lat: -24.99, lon: 31.59), GeoPoint(lat: -24.99, lon: 31.60)]
        for z in [19, 21] {
            let alone = TileCoverage(shape: Roads.corridor([london]), zoom: z).tileCount
            let both = TileCoverage(shape: Roads.corridor([london, kruger]), zoom: z).tileCount
            let krugerAlone = TileCoverage(shape: Roads.corridor([kruger]), zoom: z).tileCount
            XCTAssertEqual(both, alone + krugerAlone, "zoom \(z)")
        }
    }

    func testDownloadsImageryOnlyAlongTheRoads() async throws {
        let road = [GeoPoint(lat: -33.915, lon: 18.910), GeoPoint(lat: -33.915, lon: 18.922)]
        let corridor = Roads.corridor([road])
        let imagery = try MBTiles(url: tempDir().appendingPathComponent("i.mbtiles"), create: true)
        let terrain = try MBTiles(url: tempDir().appendingPathComponent("t.mbtiles"), create: true)
        let sat = FakeServer(deepest: 19, patch: TileID(z: 0, x: 0, y: 0))
        let dem = FakeServer(deepest: 15, patch: TileID(z: 0, x: 0, y: 0))
        let job = TileDownloadJob(shape: square, layers: [
            DownloadLayer(name: "dem", store: terrain, fetcher: dem, zooms: 0...15, pruneByParent: false),
            DownloadLayer(name: "sat", store: imagery, fetcher: sat, zooms: 0...19, pruneByParent: true, shape: corridor),
        ], concurrency: 4, retryDelay: { _ in 0 })
        let outcome = await job.run { _ in }
        XCTAssertEqual(outcome, .completed(failed: 0))

        // Elevation for the whole area, imagery only along the road.
        XCTAssertEqual(dem.requests.filter { $0.z == 15 }.count, TileCoverage(shape: square, zoom: 15).tileCount)
        let along = TileCoverage(shape: corridor, zoom: 19)
        let z19 = sat.requests.filter { $0.z == 19 }
        XCTAssertEqual(z19.count, along.tileCount)
        XCTAssertTrue(z19.allSatisfy { along.contains($0) })
        XCTAssertLessThan(along.tileCount * 5, TileCoverage(shape: square, zoom: 19).tileCount)
        // At least 15 m each side: a z19 tile is about 63 m here, so the corridor is 1 or 2 rows tall.
        XCTAssertLessThanOrEqual(along.rows.count, 2)
    }
}

final class OfflineBackupTests: XCTestCase {
    private let layers = [OfflineBackup.Layer(fileName: "imagery.mbtiles", key: "imagery", label: "satellite"),
                          OfflineBackup.Layer(fileName: "terrain.mbtiles", key: "terrain", label: "elevation")]

    func testExportedFilesAreCompleteOnTheirOwnAndCarryTheArea() throws {
        let area = tempDir()
        let writer = try MBTiles(url: area.appendingPathComponent("imagery.mbtiles"), create: true)
        let tiles = (0..<300).map { TileID(z: 18, x: 1000 + $0, y: 2000) }
        for t in tiles { XCTAssertTrue(writer.put(t, data: Data("\(t)".utf8))) }
        XCTAssertTrue(writer.flush()) // committed to the write-ahead log, not yet to the file itself
        let reader = try MBTiles(url: area.appendingPathComponent("imagery.mbtiles"), create: false)
        XCTAssertNotNil(reader.tile(tiles[0])) // the map has the file open too

        let out = tempDir().appendingPathComponent("Export")
        var used = Set<String>()
        let files = try OfflineBackup.export(areaFolder: area, layers: layers, name: "Farm: north/south",
                                             areaJSON: #"{"name":"Farm"}"#, into: out, usedNames: &used)
        // No elevation downloaded yet: only the satellite file.
        XCTAssertEqual(files.map(\.lastPathComponent), ["Farm- north-south - satellite.mbtiles"])

        // Copy just the file (as Files or AirDrop would) and open it elsewhere: every tile is there.
        let copy = tempDir().appendingPathComponent("copy.mbtiles")
        try FileManager.default.copyItem(at: files[0], to: copy)
        let restored = try OfflineBackup.inspect(copy)
        XCTAssertEqual(restored.layerKey, "imagery")
        XCTAssertEqual(String(decoding: restored.areaJSON, as: UTF8.self), #"{"name":"Farm"}"#)
        let opened = try MBTiles(url: copy, create: false)
        XCTAssertEqual(opened.tileCount(), tiles.count)
        XCTAssertEqual(opened.tile(tiles[299]), Data("\(tiles[299])".utf8))

        // A second area with the same name gets its own file names.
        let files2 = try OfflineBackup.export(areaFolder: area, layers: layers, name: "Farm: north/south",
                                              areaJSON: "{}", into: out, usedNames: &used)
        XCTAssertEqual(files2.map(\.lastPathComponent), ["Farm- north-south (2) - satellite.mbtiles"])
        reader.close()
        writer.close()
    }

    func testOtherMBTilesAreNotBackups() throws {
        let url = tempDir().appendingPathComponent("other.mbtiles")
        let store = try MBTiles(url: url, create: true)
        store.setMetadata(["name": "Somebody's map"])
        store.close()
        XCTAssertThrowsError(try OfflineBackup.inspect(url))
    }

    func testFileSafeNames() {
        XCTAssertEqual(OfflineBackup.fileSafe("  ..Block A1 / B2?  "), "Block A1 - B2-")
        XCTAssertEqual(OfflineBackup.fileSafe("///"), "---")
        XCTAssertEqual(OfflineBackup.fileSafe("  "), "Offline area")
    }
}

final class PolylineTests: XCTestCase {
    func testGoogleExample() {
        // From Google's polyline format documentation.
        let pts = [GeoPoint(lat: 38.5, lon: -120.2), GeoPoint(lat: 40.7, lon: -120.95), GeoPoint(lat: 43.252, lon: -126.453)]
        XCTAssertEqual(Polyline.encode(pts), "_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        XCTAssertEqual(Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"), pts)
    }

    func testRoundTripAndGarbage() {
        let pts = [GeoPoint(lat: -24.99512, lon: 31.59212), GeoPoint(lat: -24.99511, lon: 31.59299), GeoPoint(lat: -25.1, lon: 31.6)]
        XCTAssertEqual(Polyline.decode(Polyline.encode(pts)), pts)
        XCTAssertEqual(Polyline.decode(""), [])
        XCTAssertNil(Polyline.decode("_p~iF~ps|U_"))  // cut off half way through a point
        XCTAssertNil(Polyline.decode("\u{10}abc"))
    }

    func testPreset() throws {
        let json = #"{"name":"Park","note":"OSM","boundary":["\#(Polyline.encode([GeoPoint(lat: -24, lon: 31), GeoPoint(lat: -24, lon: 32), GeoPoint(lat: -25, lon: 32)]))"],"roads":["\#(Polyline.encode([GeoPoint(lat: -24.5, lon: 31.5), GeoPoint(lat: -24.6, lon: 31.6)]))"]}"#
        let p = try RoadPreset(json: Data(json.utf8))
        XCTAssertEqual(p.name, "Park")
        XCTAssertEqual(p.boundary[0].count, 3)
        XCTAssertEqual(p.roads, [[GeoPoint(lat: -24.5, lon: 31.5), GeoPoint(lat: -24.6, lon: 31.6)]])
        XCTAssertThrowsError(try RoadPreset(json: Data(#"{"name":"P","note":"","boundary":[],"roads":[]}"#.utf8)))
    }
}

final class ShippedDataTests: XCTestCase {
    /// SatMap/Resources/KrugerRoads.json (built by tools/build_kruger_roads.py) decodes, and its roads lie inside the park.
    func testKrugerRoadList() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("SatMap/Resources/KrugerRoads.json")
        let preset = try RoadPreset(json: try Data(contentsOf: url))
        XCTAssertEqual(preset.name, "Kruger National Park roads")
        let park = CoverageShape(polygons: preset.boundary)
        let bounds = try XCTUnwrap(park.geometryBounds)
        // Kruger is about 19,500 km² (1.95 million ha), 22.3–25.5° S, 30.9–32.0° E.
        XCTAssertEqual(park.polygonAreaSquareMeters / 1e6, 19_500, accuracy: 1_500)
        XCTAssertTrue(bounds.minLat > -25.6 && bounds.maxLat < -22.2 && bounds.minLon > 30.8 && bounds.maxLon < 32.1)
        let km = Roads.length(preset.roads) / 1000
        XCTAssertGreaterThan(km, 3_000)
        // Roads were cut with the exact boundary; the shipped one is simplified to within 20 m.
        let coverage = TileCoverage(shape: CoverageShape(polygons: preset.boundary, bufferMeters: 100), zoom: 16)
        let points = preset.roads.joined()
        let outside = points.filter { !coverage.contains(TileID.containing($0, z: 16)) }
        XCTAssertEqual(outside.count, 0, "road points outside the park")
        print("Kruger road list: \(preset.roads.count) roads, \(Int(km)) km, \(points.count) points")
    }
}

final class TileFallbackTests: XCTestCase {
    func testFallbackUsesNearestAncestor() {
        let t = TileID(z: 18, x: 140_000, y: 150_000)
        let parent = t.ancestor(levels: 1), far = t.ancestor(levels: 5)
        XCTAssertEqual(TileFallback.ancestor(of: t, maxLevels: 10) { $0 == far || $0 == parent }, parent)
        XCTAssertEqual(TileFallback.ancestor(of: t, maxLevels: 10) { $0 == far }, far)
        XCTAssertNil(TileFallback.ancestor(of: t, maxLevels: 4) { $0 == far })
        XCTAssertNil(TileFallback.ancestor(of: TileID(z: 0, x: 0, y: 0), maxLevels: 4) { _ in true })
    }

    func testRecentCacheKeepsWhatWasUsedLast() {
        let cache = RecentCache<Int, String>(capacity: 4)
        for i in 0..<4 { cache.set("\(i)", for: i) }
        _ = cache.value(for: 0) // used again: kept
        cache.set("4", for: 4) // over capacity: down to 3, the least recently used go
        XCTAssertEqual(cache.count, 3)
        XCTAssertNotNil(cache.value(for: 0))
        XCTAssertNotNil(cache.value(for: 4))
        XCTAssertNil(cache.value(for: 1))
        cache.remove { key, _ in key == 4 }
        XCTAssertFalse(cache.contains(4))
        cache.set("5", for: 5)
        cache.remove(5)
        XCTAssertFalse(cache.contains(5))
        cache.trim(to: 1)
        XCTAssertEqual(cache.count, 1)
    }

    func testRecentCachePeeksAndResizes() {
        // Peeking doesn't count as use; a smaller capacity drops the least recently used at once.
        let cache = RecentCache<Int, String>(capacity: 10)
        for i in 0..<6 { cache.set("\(i)", for: i) }
        XCTAssertEqual(cache.peek(0), "0")
        cache.resize(to: 3)
        XCTAssertEqual(cache.count, 3)
        XCTAssertNil(cache.peek(0))
        XCTAssertEqual(cache.peek(5), "5")
        cache.resize(to: 6)
        for i in 6..<9 { cache.set("\(i)", for: i) }
        XCTAssertEqual(cache.count, 6)
    }

    func testTouchedValuesOutlastNewerOnes() {
        // The coarse tiles around the screen are touched at every stop: many newer tiles don't push them out, and
        // checking for them (contains) doesn't count as use.
        let cache = RecentCache<Int, String>(capacity: 8)
        cache.set("coarse", for: 0)
        cache.set("idle", for: 1)
        for i in 2..<40 {
            cache.set("\(i)", for: i)
            XCTAssertTrue(cache.touch(0))
            _ = cache.contains(1)
        }
        XCTAssertEqual(cache.value(for: 0), "coarse")
        XCTAssertNil(cache.value(for: 1))
        XCTAssertFalse(cache.touch(1))
    }
}

final class ConcurrencyTests: XCTestCase {
    func testMoreAtOnceWhileAnswersComeQuickly() {
        var c = ConcurrencyController(start: 16, min: 4, max: 32)
        XCTAssertEqual(c.limit, 16)
        for _ in 0..<2_000 { c.record(seconds: 0.25, ok: true) }
        XCTAssertEqual(c.limit, 32)
    }

    func testFewerAtOnceWhenRequestsFailOrSlowDown() {
        var c = ConcurrencyController(start: 32, min: 4, max: 32)
        // A burst of failures from one round of requests drops the limit once.
        for _ in 0..<10 { c.record(seconds: 20, ok: false) }
        XCTAssertEqual(c.limit, 24)
        // Still failing, round after round: down to the minimum, not below.
        for _ in 0..<500 { c.record(seconds: 20, ok: false) }
        XCTAssertEqual(c.limit, 4)
        // Slow answers count as trouble too.
        var slow = ConcurrencyController(start: 16, min: 4, max: 32)
        for _ in 0..<12 { slow.record(seconds: 8, ok: true) } // one round at the new limit: one drop
        XCTAssertEqual(slow.limit, 12)
        slow.record(seconds: 8, ok: true) // the next round
        XCTAssertEqual(slow.limit, 9)
        // A fixed number when min = max (as in tests and small jobs).
        var fixed = ConcurrencyController(start: 16, min: 4, max: 4)
        for _ in 0..<100 { fixed.record(seconds: 0.1, ok: true) }
        XCTAssertEqual(fixed.limit, 4)
    }
}

/// Counts how many requests run at the same time.
private final class ParallelProbe: TileFetching {
    private let lock = NSLock()
    private var running = 0
    private(set) var most = 0

    func fetch(_ tile: TileID) async -> TileFetchResult {
        lock.withLock { running += 1; most = max(most, running) }
        try? await Task.sleep(nanoseconds: 2_000_000)
        lock.withLock { running -= 1 }
        return .data(Data([0xFF, 0xD8, 0, 0]))
    }
}

extension ConcurrencyTests {
    func testJobRunsMoreRequestsAtOnceOnAGoodConnection() async throws {
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let probe = ParallelProbe()
        let lat = -33.92, lon = 18.85
        let square = CoverageShape(polygons: [[GeoPoint(lat: lat, lon: lon), GeoPoint(lat: lat, lon: lon + 0.02),
                                               GeoPoint(lat: lat + 0.02, lon: lon + 0.02), GeoPoint(lat: lat + 0.02, lon: lon)]])
        let job = TileDownloadJob(shape: square, layers: [DownloadLayer(name: "sat", store: store, fetcher: probe, zooms: 0...17, pruneByParent: false)],
                                  concurrency: 32, retryDelay: { _ in 0 })
        var last = DownloadProgress()
        let outcome = await job.run { last = $0 }
        XCTAssertEqual(outcome, .completed(failed: 0))
        XCTAssertGreaterThan(probe.most, 16) // grew past the starting 16
        XCTAssertLessThanOrEqual(probe.most, 32)
        XCTAssertGreaterThan(last.parallel, 16)
    }
}

final class CoverageDifferenceTests: XCTestCase {
    func testTilesNotInAnotherCoverage() {
        let road = [GeoPoint(lat: -24.99, lon: 31.59), GeoPoint(lat: -24.99, lon: 31.60)]
        let same = TileCoverage(shape: Roads.corridor([road]), zoom: 22)
        XCTAssertEqual(same.count(notIn: same), 0)
        // The same road recorded 12 m to the side: a strip of new tiles (about 9 m each at zoom 22), not nothing.
        let shifted = road.map { GeoPoint(lat: $0.lat + 12 / 110_574, lon: $0.lon) }
        let moved = TileCoverage(shape: Roads.corridor([shifted]), zoom: 22)
        let extra = moved.count(notIn: same)
        XCTAssertGreaterThan(extra, 0)
        XCTAssertLessThan(extra, moved.tileCount / 3)
        // Somewhere else entirely: all of it is new.
        let elsewhere = TileCoverage(shape: Roads.corridor([road.map { GeoPoint(lat: $0.lat - 0.1, lon: $0.lon) }]), zoom: 22)
        XCTAssertEqual(elsewhere.count(notIn: same), elsewhere.tileCount)
    }

    func testBufferNearThePoleStaysSmall() {
        // A KML line to the South Pole with the usual 150 m margin must not cover the whole world.
        let line = CoverageShape(lines: [[GeoPoint(lat: -89.9, lon: 0), GeoPoint(lat: -90, lon: 0)]], bufferMeters: 150)
        XCTAssertLessThan(TileCoverage(shape: line, zoom: 8).tileCount, 1 << 10)
    }
}

/// Fails every request for the first `count` different tiles it is asked for (one round of requests hit by a gap).
/// None of those answers until all of them have been asked for, as when a connection drops with a round of requests
/// out: so they are the round sent first, in whatever order the tasks happen to run (the job must send at least
/// `count` at once).
private final class FirstRoundFails: TileFetching, @unchecked Sendable {
    private let lock = NSLock()
    private let count: Int
    private var failing = Set<TileID>()
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(count: Int) {
        self.count = count
    }

    func fetch(_ tile: TileID) async -> TileFetchResult {
        enum Step { case answer(fail: Bool), wait, release([CheckedContinuation<Void, Never>]) }
        let step: Step = lock.withLock {
            if failing.contains(tile) { return .answer(fail: true) }
            guard failing.count < count else { return .answer(fail: false) }
            failing.insert(tile)
            if failing.count < count { return .wait }
            defer { waiting = [] }
            return .release(waiting)
        }
        switch step {
        case .answer(let fail):
            return fail ? .failed : .data(Data([0xFF, 0xD8, 0, 0]))
        case .wait:
            await withCheckedContinuation { c in
                let go: Bool = lock.withLock {
                    if failing.count >= count { return true }
                    waiting.append(c)
                    return false
                }
                if go { c.resume() }
            }
        case .release(let others):
            others.forEach { $0.resume() }
        }
        return .failed
    }
}

extension ConcurrencyTests {
    func testFewerAtOnceWhenAnswersQueueUpOnASlowLink() {
        // Answers at the connection's own pace keep it growing; answers well over it (data queueing on a slow link,
        // though under 5 s) bring it down.
        var c = ConcurrencyController(start: 16, min: 4, max: 32)
        for _ in 0..<100 { c.record(seconds: 0.33, ok: true) }
        let grown = c.limit
        XCTAssertGreaterThan(grown, 16)
        for _ in 0..<200 { c.record(seconds: 3.5, ok: true) }
        XCTAssertLessThan(c.limit, grown / 2)
    }

    func testOneRoundOfFailuresIsNotAnOutage() async throws {
        // A short gap fails every request that was under way (16 at the start), and only those: the download goes on
        // (they are retried by the next run), rather than stopping as offline.
        let store = try MBTiles(url: tempDir().appendingPathComponent("s.mbtiles"), create: true)
        let lat = -33.92, lon = 18.85
        let square = CoverageShape(polygons: [[GeoPoint(lat: lat, lon: lon), GeoPoint(lat: lat, lon: lon + 0.01),
                                               GeoPoint(lat: lat + 0.01, lon: lon + 0.01), GeoPoint(lat: lat + 0.01, lon: lon)]])
        let job = TileDownloadJob(shape: square, layers: [DownloadLayer(name: "sat", store: store, fetcher: FirstRoundFails(count: 16),
                                                                        zooms: 17...17, pruneByParent: false)],
                                  concurrency: 32, retryDelay: { _ in 0 })
        let outcome = await job.run { _ in }
        XCTAssertEqual(outcome, .completed(failed: 16))
    }
}

final class CoordinateParserTests: XCTestCase {
    /// A spot in the Kruger: -25.096893, 31.437121 (25° 5' 48.815" S, 31° 26' 13.636" E).
    private let lat = -25.096893, lon = 31.437121
    /// Elsewhere: Big Ben, the Statue of Liberty.
    private let london = GeoPoint(lat: 51.5007, lon: -0.1246), newYork = GeoPoint(lat: 40.6892, lon: -74.0445)
    /// Where you might be: in the Kruger, Cape Town, Madrid, Rio de Janeiro.
    private let kruger = GeoPoint(lat: -25, lon: 31.5), capeTown = GeoPoint(lat: -33.9249, lon: 18.4241),
                madrid = GeoPoint(lat: 40.4168, lon: -3.7038), rio = GeoPoint(lat: -22.9068, lon: -43.1729)

    /// Read as `at` (the Kruger spot if not given), within `metres`, with the format if given.
    private func check(_ text: String, _ format: String? = nil, within metres: Double = 1, near: GeoPoint? = nil,
                       at point: GeoPoint? = nil, file: StaticString = #filePath, line: UInt = #line) {
        guard let r = CoordinateParser.parse(text, near: near) else {
            return XCTFail("Not read: \(text)", file: file, line: line)
        }
        let d = Geo.distance(r.point, point ?? GeoPoint(lat: lat, lon: lon))
        XCTAssertLessThan(d, metres, "\(text) read as \(r.point) (\(r.format)), \(Int(d)) m off", file: file, line: line)
        if let f = format { XCTAssertEqual(r.format, f, text, file: file, line: line) }
    }

    func testDecimalDegrees() {
        for t in ["-25.096893, 31.437121", "-25.096893,31.437121", "-25.096893 31.437121", "-25.096893;31.437121",
                  "25.096893 S, 31.437121 E", "25.096893S 31.437121E", "S25.096893 E31.437121", "S 25.096893° E 31.437121°",
                  "25.096893°S 31.437121°E", "-25.096893° 31.437121°", "(-25.096893, 31.437121)", "−25.096893, +31.437121",
                  "lat -25.096893 lon 31.437121", "Latitude: -25.096893, Longitude: 31.437121",
                  "lon 31.437121 lat -25.096893", "31.437121 E, 25.096893 S", "E 31.437121 S 25.096893",
                  "-25,096893; 31,437121", "-25,096893 31,437121", "25.096893 South 31.437121 East", "25.096893 Suid 31.437121 Oos",
                  "📍 -25.096893, 31.437121", "  -25.096893 ,\n 31.437121  "] {
            check(t, "decimal degrees")
        }
        // Longitude first, where only the second number can be a latitude.
        XCTAssertEqual(CoordinateParser.parse("131.5, -25.1")?.point, GeoPoint(lat: -25.1, lon: 131.5))
    }

    func testDegreesAndMinutes() {
        for t in ["25° 05.81358' S, 31° 26.22726' E", "S 25° 5.81358′ E 31° 26.22726′", "25 05.81358 S 31 26.22726 E",
                  "-25 5.81358, 31 26.22726", "-25° 5.81358' 31° 26.22726'", "S25 05.81358 E031 26.22726",
                  "25°05.81358'S 031°26.22726'E", "25d 5.81358m S 31d 26.22726m E"] {
            check(t, "degrees and decimal minutes")
        }
    }

    func testDegreesMinutesSeconds() {
        for t in ["25°05'48.815\"S 31°26'13.636\"E", "25°05′48.815″S 31°26′13.636″E", "25º 5' 48.815'' S, 31º 26' 13.636'' E",
                  "S 25° 5' 48.815\" E 31° 26' 13.636\"", "25 05 48.815 S 31 26 13.636 E", "-25 05 48.815 31 26 13.636",
                  "-25° 05' 48.815\", 31° 26' 13.636\"", "25°5’48.815”S 31°26’13.636”E", "Lat: 25°05'48.815\"S Long: 31°26'13.636\"E",
                  "25 deg 5 min 48.815 sec S, 31 deg 26 min 13.636 sec E"] {
            check(t, "degrees, minutes and seconds")
        }
    }

    func testGPSAndLinks() {
        check("2505.81358,S,03126.22726,E", "GPS (NMEA)")
        check("$GPGGA,123519,2505.81358,S,03126.22726,E,1,08,0.9,545.4,M,46.9,M,,*47".components(separatedBy: ",")[2...5].joined(separator: ","), "GPS (NMEA)")
        check("https://www.google.com/maps/place/-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/@-25.096893,31.437121,15z", "map link")
        check("https://www.google.com/maps/place/Somewhere/@-25.1,31.4,17z/data=!3m1!4b1!4m6!3m5!3d-25.096893!4d31.437121", "map link")
        check("https://maps.google.com/?q=-25.096893,31.437121", "map link")
        check("https://maps.apple.com/?ll=-25.096893,31.437121&q=Pin", "map link")
        check("https://www.openstreetmap.org/?mlat=-25.096893&mlon=31.437121#map=16/-25.1/31.4", "map link")
        check("https://www.openstreetmap.org/#map=18/-25.096893/31.437121", "map link")
        check("https://www.bing.com/maps?cp=-25.096893~31.437121&lvl=16", "map link")
        check("geo:-25.096893,31.437121?z=17", "geo: link")
    }

    func testUTMAndMGRS() {
        // Reference values from pyproj and the mgrs library.
        check("36J 342401.64 7223411.52", "UTM")
        check("36 J 342401 7223411", "UTM", within: 2)
        check("UTM 36J 342401mE 7223411mN", "UTM", within: 2)
        check("Zone 36J, E 342401, N 7223411", "UTM", within: 2)
        check("36J N 7223411 E 342401", "UTM", within: 2)
        check("36S 342401 7223411", "UTM", within: 2) // S as "south", as some tools write it
        check("36JUT4240123411", "MGRS", within: 2)
        check("36J UT 42401 23411", "MGRS", within: 2)
        check("MGRS 36J UT 4240 2341", "MGRS", within: 15)
        // Elsewhere: Stellenbosch, London, New York, Tromsø, near the equator.
        let others: [(String, Double, Double)] = [
            ("34H 307828.05 6244957.75", -33.918292, 18.921204), ("30U 699567.54 5709427.56", 51.5007, -0.1246),
            ("18T 580735.87 4504695.17", 40.6892, -74.0445), ("34W 420682.24 7728124.9", 69.6496, 18.956),
            ("17N 811683.01 55331.54", 0.5, -78.2), ("34HCH0782844957", -33.918292, 18.921204), ("30UXC9956709427", 51.5007, -0.1246),
            ("18TWL8073504695", 40.6892, -74.0445), ("34WDC2068228124", 69.6496, 18.956), ("17NRA1168355331", 0.5, -78.2),
        ]
        for (text, la, lo) in others {
            guard let r = CoordinateParser.parse(text) else { XCTFail("Not read: \(text)"); continue }
            XCTAssertLessThan(Geo.distance(r.point, GeoPoint(lat: la, lon: lo)), 2, text)
        }
    }

    func testPlusCodes() {
        // Reference values from Google's openlocationcode library (the centre of the code's area, within ~7 m).
        check("5G6HWC3P+6R", "plus code", within: 8)
        check("5G6HWC3P+6RX", "plus code", within: 3)
        check("WC3P+6R", "plus code (near you)", within: 8, near: GeoPoint(lat: -25.0, lon: 31.54))
        XCTAssertNil(CoordinateParser.parse("WC3P+6R")) // a short code needs your position
        guard let s = CoordinateParser.parse("4FRW3WJC+MF") else { return XCTFail("Not read") }
        XCTAssertLessThan(Geo.distance(s.point, GeoPoint(lat: -33.9183125, lon: 18.9211875)), 1)
    }

    func testNotPositions() {
        for t in ["", "hello", "25", "123.4, 456.7", "25° 75' S, 31° 26' E", "91, 181", "25 S 31 S", "1 2 3", "https://example.com/x"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }

    // Inputs found misread or not read by a sweep of real-world sources, by family.

    func testMoreLinks() {
        // Apple Maps (iOS 18 and its documented examples): coordinate=, after an address that has numbers in it.
        check("https://maps.apple.com/place?address=Apple%20Inc.,%201%20Apple%20Park%20Way,%20Cupertino,%20CA%2095014,"
              + "%20United%20States&coordinate=37.334859,-122.009040&name=Apple%20Park&place-id=I7C250D2CDCB364A&map=explore",
              "map link", at: GeoPoint(lat: 37.334859, lon: -122.009040))
        check("https://maps.apple.com/place?coordinate=-25.096893,31.437121&name=Marked%20Location", "map link")
        check("https://maps.apple.com/?coordinate=-25.096893,31.437121", "map link")
        // Android geo: links: the q= pin, not the map centre or the geo:0,0 placeholder.
        check("geo:0,0?q=-25.096893,31.437121(Dropped pin)", "geo: link")
        check("geo:0,0?q=-25.096893%2C31.437121%28Camp%29", "geo: link")
        check("geo:50.123456,-120.123456?q=40.7127400,-74.0059965", "geo: link", at: GeoPoint(lat: 40.71274, lon: -74.0059965))
        XCTAssertNil(CoordinateParser.parse("geo:0,0?q=Skukuza")) // a place by name, not 0,0
        check("google.navigation:q=-25.096893,31.437121&mode=w", "map link")
        // Google Maps: the place or search in the path, not the map centre after /@; a DMS place; where directions go.
        check("https://www.google.com/maps/place/-25.096893,31.437121/@-25.0968930,31.4345295,17z", "map link")
        check("https://www.google.com/maps/search/-25.096893,+31.437121/@-25.0968930,31.4345295,17z?entry=ttu", "map link")
        check("https://www.google.com/maps/place/25%C2%B005'48.8%22S+31%C2%B026'13.6%22E/@-25.0968930,31.4345295,17z/data=!3m1!1e3",
              "map link", within: 2)
        check("https://www.google.com/maps/dir//-25.096893,31.437121/@-25.1105930,31.3998210,13z?entry=ttu", "map link")
        check("https://www.google.com/maps?saddr=-24.976893,31.517121&daddr=-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/dir/Hermannstra%C3%9Fe+1,+12049+Berlin/Reuterstra%C3%9Fe+1,+Berlin/@52.4844406,13.4217121,16z"
              + "/data=!4m14!4m13!1m5!1m1!1s0x0:0x1!2m2!1d13.4236883!2d52.4858222!1m5!1m1!1s0x0:0x2!2m2!1d13.4300356!2d52.4807739!3e2",
              "map link", at: GeoPoint(lat: 52.4807739, lon: 13.4300356)) // the last stop's position in the data
        check("https://www.google.com/maps/dir//The+Station,+1+Mends+St,+South+Perth+WA+6151/@-31.9614112,115.8523381,14z",
              "map link (centre of the route)", at: GeoPoint(lat: -31.9614112, lon: 115.8523381))
        check("https://www.google.com/maps/place/5G6HWC3P%2B6R", "plus code", within: 8)
        check("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3600.5!2d31.437121!3d-25.096893!2m3!1f0!2f0!3f0!3m2!1i1024"
              + "!2i768!4f13.1!3m3!1m2!1s0x0%3A0x0!2z!5e1!3m2!1sen!2sza!4v1730000000000!5m2!1sen!2sza", "map link")
        // OsmAnd.
        check("https://osmand.net/map?pin=-25.09689,31.43712#15/-25.09689/31.43712", "map link")
        check("https://osmand.net/go?lat=-25.096893&lon=31.437121&z=15", "map link")
        // A short link needs the internet to follow.
        XCTAssertNil(CoordinateParser.parse("https://maps.app.goo.gl/q8w1k9mH3qFz7Dxq8"))
    }

    func testMoreFieldNMEAAndISO6709() {
        // Military and aviation degrees, minutes and seconds with dashes (not minus signs).
        check("25-05-48.8S 031-26-13.6E", "degrees, minutes and seconds", within: 2)
        check("S25-05-48.8 E031-26-13.6", "degrees, minutes and seconds", within: 2)
        check("40-41-21.1200N 074-02-40.2000W", "degrees, minutes and seconds", at: newYork)
        // NMEA and aviation within 1° or 2° of the equator or Greenwich (leading zeros).
        check("5130.0420,N,00007.4760,W", "GPS (NMEA)", at: london)
        check("0010.8392,S,07828.0700,W", "GPS (NMEA)", at: GeoPoint(lat: -0.180653, lon: -78.467834))
        check("0536.22N 00011.22W", "GPS (NMEA)", at: GeoPoint(lat: 5.603667, lon: -0.187))
        // Whole NMEA sentences.
        check("$GPGGA,123519,2505.8136,S,03126.2273,E,1,08,0.9,545.4,M,46.9,M,,*47", "GPS (NMEA)")
        check("$GPRMC,123519,A,2505.8136,S,03126.2273,E,022.4,084.4,230394,003.1,W*6A", "GPS (NMEA)")
        // ISO 6709: degrees and minutes, from the time zone database, and with an altitude (iPhone video).
        check("-0117.5240+03649.3168/", "ISO 6709", at: GeoPoint(lat: -1.292066, lon: 36.821946))
        check("-0117+03649", "ISO 6709", at: GeoPoint(lat: -1.283333, lon: 36.816667))
        check("+40.6892-074.0445+545.400/", "ISO 6709", at: newYork)
        // exiftool's video GPS Coordinates, with the altitude after them.
        check("25 deg 5' 48.81\" S, 31 deg 26' 13.64\" E, 545.4 m Above Sea Level", "degrees, minutes and seconds")
    }

    func testMoreGrids() {
        // UTM with a space between the zone and N (band or hemisphere), or the hemisphere in words.
        check("30 N 699568 5709428", "UTM", within: 2, at: london)
        check("48 N 372280 142646", "UTM", within: 2, at: GeoPoint(lat: 1.2903, lon: 103.852001))
        check("UTM 36 South 342402 7223412", "UTM", within: 2)
        // MGRS as one run of digits (10 m), and with a label or a note.
        check("36JUT42402341", "MGRS", within: 15)
        check("MGRS: 36J UT 42401 23411", "MGRS", within: 2)
        check("36J UT 42401 23411 (MGRS)", "MGRS", within: 2)
    }

    func testDataSnippets() {
        // A heading gives the order of the values: "Lat/Long:", "Long/Lat:", a spreadsheet's header row.
        for t in ["Lat/Long: -25.096893, 31.437121", "Lat Long (-25.096893, 31.437121)", "lat/lng: (-25.096893,31.437121)",
                  "Latitude\tLongitude\n-25.096893\t31.437121\n", "Long/Lat: 31.437121, -25.096893",
                  "X: 31.437121, Y: -25.096893"] {
            check(t, "decimal degrees")
        }
        // Quoted values (CSV, JSON, GPX), not seconds; altitude, accuracy and the like left out.
        for t in [#""-25.096893","31.437121""#, #""-25,096893","31,437121""#, #""lat":"-25.0968930","lon":"31.4371210""#,
                  #"{"lat":"-25.0968930","lon":"31.4371210"}"#, #"<wpt lat="-25.096893" lon="31.437121">"#,
                  #"{"location": {"lat": -25.096893, "lng": 31.437121}, "accuracy": 20}"#,
                  "Latitude: -25.096893\nLongitude: 31.437121\nAltitude: 545.4 m",
                  "Latitude: -25.096893\nLongitude: 31.437121\nAltitude: 312 m\nAccuracy: 5 m",
                  "CLLocationCoordinate2D(latitude: -25.096893, longitude: 31.437121)"] {
            check(t, "decimal degrees")
        }
        // Longitude first: GeoJSON, KML, WKT.
        check(#"{"type":"Point","coordinates":[31.437121,-25.096893]}"#, "GeoJSON")
        check(#"{"type": "Point", "coordinates": [31.437121, -25.096893, 312.0]}"#, "GeoJSON")
        check("<coordinates>31.437121,-25.096893,0</coordinates>", "KML")
        check("<Point><coordinates>31.437121,-25.096893,312</coordinates></Point>", "KML")
        check("""
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
            <Placemark>
            \t<name>Untitled Placemark</name>
            \t<LookAt>
            \t\t<longitude>31.43712100</longitude>
            \t\t<latitude>-25.09689300</latitude>
            \t\t<altitude>0</altitude>
            \t\t<range>1000</range>
            \t</LookAt>
            \t<Point>
            \t\t<gx:drawOrder>1</gx:drawOrder>
            \t\t<coordinates>31.437121,-25.096893,0</coordinates>
            \t</Point>
            </Placemark>
            </kml>
            """, "KML")
        check("Point (31.437121 -25.096893)", "WKT")
        check("POINT (31.437121 -25.096893)", "WKT")
        // lon, lat, alt or lat, lon, alt: it can't be told which.
        XCTAssertNil(CoordinateParser.parse("31.437121,-25.096893,0"))
    }

    func testPositionInText() {
        check("25°05'48.8\"S 31°26'13.6\"E\nhttps://maps.app.goo.gl/q8w1k9mH3qFz7Dxq8", "degrees, minutes and seconds", within: 2)
        for t in ["Camp site 14: -25.096893, 31.437121", "Saw leopard at 06:45 -25.096893, 31.437121",
                  "Lions on H4-1 at -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: -25.096893, 31.437121",
                  "27/10/2026, 12:31 - Jaco: -25.096893, 31.437121"] {
            check(t, "decimal degrees")
        }
        check("Spot 1: 51.5007, -0.1246", "decimal degrees", at: london) // not 1° 51.5007'
        // Wikipedia: the same place twice (the more precise of the two is taken).
        check("51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W", "decimal degrees",
              at: GeoPoint(lat: 51.500694, lon: -0.124611))
        // Two different places: it isn't clear which is meant.
        XCTAssertNil(CoordinateParser.parse("Camp at -25.096893, 31.437121; sighting at -25.196893, 31.537121"))
    }

    func testTheEvidenceDecides() {
        // A lone O: east (Afrikaans Oos, German Ost) unless your position says west (French Ouest, Spanish Oeste).
        check("25,096893° S, 31,437121° O", "decimal degrees (O = east)")
        check("40,68920° N, 74,04450° O", "decimal degrees (O = west)", near: GeoPoint(lat: 45.5017, lon: -73.5673), at: newYork)
        check("51,50070° N, 0,12460° O", "decimal degrees (O = west)", near: london, at: london)
        // Without your position or a word that says which language, east, as the format says.
        check("40,68920° N, 74,04450° O", "decimal degrees (O = east)", at: GeoPoint(lat: 40.6892, lon: 74.0445))
        // Another word in the text says which language it is: Dutch Z (zuid) and German Süd with Oost/Ost, Spanish Sur
        // and French sud with Oeste/Ouest.
        check("25,096893 Z 31,437121 O", "decimal degrees (O = east)")
        check("33° 51′ Süd, 151° 12′ O", "degrees and decimal minutes (O = east)", at: GeoPoint(lat: -33.85, lon: 151.2))
        check("34° 36' Sur, 58° 22' O", "degrees and decimal minutes (O = west)", at: GeoPoint(lat: -34.6, lon: -58.366667))
        check("22° 54′ 30″ sud, 43° 11′ 47″ O", "degrees, minutes and seconds (O = west)",
              at: GeoPoint(lat: -22.908333, lon: -43.196389))
        // A minus sign can't be east.
        check("-74,0445 O, 40,6892 N", "decimal degrees (O = west)", at: newYork)
        // A short plus code with a place name was shortened against that place, which can't be looked up offline: near
        // you it could be a square up to 1° from it (110 km north of it at Letaba, 1,600 km away near Cape Town).
        for near in [GeoPoint(lat: -33.9249, lon: 18.4241), GeoPoint(lat: -23.854, lon: 31.575), GeoPoint(lat: -24.99, lon: 31.59)] {
            XCTAssertNil(CoordinateParser.parse("WC3P+6R Skukuza, South Africa", near: near))
        }
    }

    // Inputs checked again after that sweep, by family.

    func testHalvesWithDifferentFields() {
        // Seconds in one half and not the other, marked by a degree sign, a letter in front or a minus sign.
        check("40° 41' 21\" N 74° 2 W", "degrees, minutes and seconds", at: GeoPoint(lat: 40.689167, lon: -74.033333))
        check("51° 30' 26\" N 0° 7 W", "degrees, minutes and seconds", at: GeoPoint(lat: 51.507222, lon: -0.116667))
        check("25.0969° S 31° 26 E", "degrees and decimal minutes", at: GeoPoint(lat: -25.0969, lon: 31.433333))
        check("S 25° E 31° 26", "degrees and decimal minutes", at: GeoPoint(lat: -25, lon: 31.433333))
        check("25°05'48.8\"S 31°26 E", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.433333))
        check("25.0969 S 31 26 E", "degrees and decimal minutes", at: GeoPoint(lat: -25.0969, lon: 31.433333))
        check("S25 05 48.8 E31 26.2273", "degrees, minutes and seconds", within: 2)
        check("-25 05 48.8, 31.437121", "degrees, minutes and seconds", within: 2)
        check("51.5007, -0 07.476", "degrees and decimal minutes", at: london)
        // Without such a mark a camp number before it could be the first field: 1° 25' 05.8" S.
        XCTAssertNil(CoordinateParser.parse("Camp 1 25 05.8 S, 31 26.2 E"))
        // A number right after the last letter could be minutes, but not after a decimal.
        XCTAssertNil(CoordinateParser.parse("25 S 31 E 3"))
        check("25.096893 S 31.437121 E 2026-10-27", "decimal degrees")
    }

    func testListsOfNumbers() {
        // Longitude, latitude and altitude (or latitude, longitude and altitude): it can't be told which.
        for t in ["31.437121 -25.096893 545.4", "31.437121 / -25.096893 / 545", "-74.0445 40.6892 10", "31.437121\t-25.096893\t312",
                  "1730025900 31.437121 -25.096893 545.4", "Lap times: 12.345 13.456 14.567", "Price list: 12.500 31.750 and 45.250"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
        // A number after it that isn't joined the same way, a whole number before it, or a measurement isn't part of a list.
        check("-25.096893, 31.437121 3 cubs", "decimal degrees")
        check("Camp 14 -25.096893 31.437121", "decimal degrees")
        check("Lions at -25.096893, 31.437121 (±5.000 m)", "decimal degrees")
    }

    func testMessages() {
        // Apostrophes: "it's", "Jaco's" and Afrikaans "'n" aren't directions.
        check("40.6892, -74.0445 is where it's at", "decimal degrees", at: newYork)
        check("Jaco's sighting: 51.5007, -0.1246", "decimal degrees", at: london)
        check("It's here: 40.6892, -74.0445", "decimal degrees", at: newYork)
        check("-25.096893, 31.437121 at Jaco's camp", "decimal degrees")
        check("-25.096893, 31.437121 'n groot mannetjie", "decimal degrees")
        // Words in a sentence aren't labels or directions unless a number is next to them.
        check("Long day! Lions at -25.096893, 31.437121", "decimal degrees")
        check("Heading west, lions at -25.096893, 31.437121", "decimal degrees")
        check("Lions near the east gate -25.096893, 31.437121", "decimal degrees")
        check("West side of the river -25.096893 31.437121", "decimal degrees")
        check("Leeus by die oos hek -25.096893, 31.437121", "decimal degrees")
        check("Lions at 25.096893 South 31.437121 East near the camp", "decimal degrees")
        // Times, dates, counts and measurements among words aren't positions; "GPS:" and the like only say one follows.
        for t in ["Gates open 05:30 close 18:00", "Departed 08:15, arrived 09:40", "25 Oct 2026", "12 lions 3 cubs", "Version 2.5.1",
                  "Alt 1450 m, temp 25.5, wind 31.4", "Arrive 7 Dec 25", "4 Mar 26", "25 Dec 31", "abc 25 def 31"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
        check("GPS: -25.1, 31.4", "decimal degrees", within: 20_000)
        // "#27/10/26" in a message isn't a map link.
        check("Ref #27/10/26: lions at -25.096893, 31.437121", "decimal degrees")
    }

    func testOtherLanguages() {
        for t in ["25.096893 Sur 31.437121 Este", "25.096893 Süd 31.437121 Ost", "25.096893 Sud 31.437121 Est", "25.096893 Sth 31.437121 E",
                  "25,096893 Zuid 31,437121 Oost"] {
            check(t, "decimal degrees")
        }
        check("40,6892 N 74,0445 Oeste", "decimal degrees", at: newYork)
        check("33° 51′ 35″ sud, 151° 12′ 40″ est", "degrees, minutes and seconds", at: GeoPoint(lat: -33.859722, lon: 151.211111))
        check("22° 54′ 30″ sud, 43° 11′ 47″ ouest", "degrees, minutes and seconds", at: GeoPoint(lat: -22.908333, lon: -43.196389))
        check("34° 36' Sur, 58° 22' Oeste", "degrees and decimal minutes", at: GeoPoint(lat: -34.6, lon: -58.366667))
        check("33° 51′ ZB, 151° 12′ OL", "degrees and decimal minutes", at: GeoPoint(lat: -33.85, lon: 151.2))
        check("Länge: 13,405 Breite: 52,52", "decimal degrees", at: GeoPoint(lat: 52.52, lon: 13.405))
        check("Longitud: -3,7038 Latitud: 40,4168", "decimal degrees", at: GeoPoint(lat: 40.4168, lon: -3.7038))
    }

    func testDashesAndUnitLetters() {
        // A dash after a half that ends in its letter separates the halves.
        check("25.096893 S - 31.437121 E", "decimal degrees")
        check("25°05'48.8\"S - 31°26'13.6\"E", "degrees, minutes and seconds", within: 2)
        check("25.096893° S \u{2013} 31.437121° E", "decimal degrees")
        // A minus sign with N or E says two things.
        XCTAssertNil(CoordinateParser.parse("-25.096893, -31.437121 E"))
        // d, m and s for degrees, minutes and seconds.
        check("40d41m21sN 74d02m40sW", "degrees, minutes and seconds", at: GeoPoint(lat: 40.689167, lon: -74.044444))
        check("25d05m48.815sS 31d26m13.636sE", "degrees, minutes and seconds")
        check("25°05m48.815sS 31°26m13.636sE", "degrees, minutes and seconds")
    }

    func testLongitudeFirstAndSeveralPlaces() {
        check("{ type: 'Point', coordinates: [-77.032, 38.913] }", "GeoJSON", at: GeoPoint(lat: 38.913, lon: -77.032))
        check(#""coordinates": [31.437121, -25.096893]"#, "GeoJSON")
        check(#"{"type":"MultiPoint","coordinates":[[31.437121,-25.096893]]}"#, "GeoJSON")
        check("MULTIPOINT((31.437121 -25.096893))", "WKT")
        check("point(31.437121 -25.096893)", "WKT")
        check("point z (31.437121 -25.096893 312)", "WKT")
        check("ST_SetSRID(ST_MakePoint(31.437121, -25.096893), 4326)", "decimal degrees")
        check("mapboxgl.LngLat(31.437121, -25.096893)", "decimal degrees")
        check("<gx:coord>31.437121 -25.096893 545.4</gx:coord>", "KML")
        // "point" in a sentence isn't WKT.
        check("Meeting point (-25.096893 31.437121)", "decimal degrees")
        // Two different places, or a line: it isn't clear which is meant.
        for t in ["<Placemark><name>Skukuza</name><Point><coordinates>31.5969,-24.9947,0</coordinates></Point></Placemark>"
                    + "<Placemark><name>Lower Sabie</name><Point><coordinates>31.9150,-25.1197,0</coordinates></Point></Placemark>",
                  #"{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[31.5969,-24.9947]}},"#
                    + #"{"type":"Feature","geometry":{"type":"Point","coordinates":[31.9150,-25.1197]}}]}"#,
                  #"{"type":"LineString","coordinates":[[31.5969,-24.9947],[31.9150,-25.1197]]}"#,
                  "POINT (31.5969 -24.9947), POINT (31.9150 -25.1197)", "2459.682,S,03135.814,E 2507.182,S,03154.900,E"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }

    func testLabelledValuesWithOthers() {
        for t in [#"<wpt lat="-25.096893" lon="31.437121"><ele>545.2</ele><time>2026-10-27T10:45:00Z</time><name>Camp</name></wpt>"#,
                  #"<trkpt lat="-25.096893" lon="31.437121"><ele>312.4</ele><time>2026-10-27T12:31:00Z</time></trkpt>"#,
                  #"<wpt lat="-25.096893" lon="31.437121"><name>Camp 14</name></wpt>"#,
                  #"{"lat": -25.096893, "lng": 31.437121, "zoom": 12}"#, #"{"name":"Camp 14","lat":-25.096893,"lon":31.437121}"#,
                  "<geo:lat>-25.096893</geo:lat> <geo:long>31.437121</geo:long>", "geo:lat=-25.096893 geo:lon=31.437121"] {
            check(t, "decimal degrees")
        }
        // A track: different places.
        XCTAssertNil(CoordinateParser.parse(#"<trkpt lat="-25.096893" lon="31.437121"/><trkpt lat="-25.196893" lon="31.537121"/>"#))
    }

    func testEvenMoreLinks() {
        for t in ["https://www.mapquest.com/latlng/-25.096893,31.437121", "https://share.here.com/l/-25.096893,31.437121,Lions",
                  "https://maps.google.com/?q=Lions@-25.096893,31.437121", "https://example.com/map?lon=31.437121&lat=-25.096893",
                  "https://www.waze.com/live-map/directions?to=ll.-25.096893%2C31.437121",
                  "https://yandex.com/maps/?ll=31.437121%2C-25.096893&z=15",
                  "https://www.arcgis.com/home/webmap/viewer.html?center=31.437121,-25.096893&level=12"] {
            check(t, "map link")
        }
        check("geo:-25.096893 31.437121", "geo: link")
        check("https://geohack.toolforge.org/geohack.php?pagename=Skukuza&params=24_59_46_S_31_35_28_E_region:ZA_type:city",
              "map link", at: GeoPoint(lat: -24.996111, lon: 31.591111))
        XCTAssertNil(CoordinateParser.parse("https://example.com/?bbox=31.4,-25.2,31.5,-25.0"))
    }

    func testGridReferencesInText() {
        // The zone right before the easting and northing, not a number and letter elsewhere.
        check("Waypoint 12 N: 36J 342401 7223411", "UTM", within: 2)
        check("R40 N to 36J 342401 7223411", "UTM", within: 2)
    }

    func testWikipediaAtMinutePrecision() {
        // The decimal form is rounded to 3 places: up to about 55 m from the minutes.
        check("25°03′S 31°08′E / 25.050°S 31.133°E", "decimal degrees", at: GeoPoint(lat: -25.05, lon: 31.133))
        check("Coordinates: 25°03′S 31°08′E\u{FEFF} / \u{FEFF}25.050°S 31.133°E", "decimal degrees", at: GeoPoint(lat: -25.05, lon: 31.133))
        check("24°59′S 31°35′E / 24.983°S 31.583°E / -24.983; 31.583", "decimal degrees", at: GeoPoint(lat: -24.983, lon: 31.583))
        // Different places (more than a minute apart) still aren't read.
        XCTAssertNil(CoordinateParser.parse("25°03′S 31°08′E / 25.150°S 31.133°E"))
    }

    func testDigitsInOtherScriptsAndLongText() {
        check("+٤٠٫٦٨٩٢-٠٧٤٫٠٤٤٥/", "ISO 6709", at: newYork)
        check("+４０.６８９２-０７４.０４４５/", "ISO 6709", at: newYork)
        check("-٢٥٫٠٩٦٨٩٣، ٣١٫٤٣٧١٢١", "decimal degrees")
        check("+٤٠-٠٧٤", "ISO 6709", at: GeoPoint(lat: 40, lon: -74))
        // A long paste is read quickly (it is read as you type).
        let start = Date()
        XCTAssertNil(CoordinateParser.parse(String(repeating: "1", count: 20_000)))
        check("Ref " + String(repeating: "7", count: 5_000) + " at -25.096893, 31.437121", "decimal degrees")
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
    }

    // Inputs checked a third time, by family.

    func testLabelsJoinedByAnd() {
        // Two labels joined by "and" are a heading, as "Lat/Long:" is: they don't label the first value.
        for t in ["Lat & Long: -25.096893, 31.437121", "Lat and Long: -25.096893, 31.437121",
                  "Latitude and longitude: -25.096893, 31.437121", "lat & lng -25.096893, 31.437121",
                  "Breite und Länge: -25,096893; 31,437121", "Latitud y longitud: -25.096893, 31.437121",
                  "Breedte en lengte: -25,096893; 31,437121", "Longitude and latitude: 31.437121, -25.096893"] {
            check(t, "decimal degrees")
        }
        check("Lat & Long: -24.996931, 31.592312", "decimal degrees", near: kruger, at: GeoPoint(lat: -24.996931, lon: 31.592312))
    }

    func testFieldsOfOneHalf() {
        // A full stop at the end of a sentence isn't a decimal point: the commas are.
        check("-24,99 31,59.", "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
        check("Breite 52,52, Länge 13,41.", "decimal degrees", at: GeoPoint(lat: 52.52, lon: 13.41))
        // Numbers with a comma, semicolon or slash between them, or a second point, aren't the fields of one half; a dash
        // or colon keeps them in one (a time, a score).
        for t in ["2026/10/27", "27/10/26", "10/27/26", "2026/1/5", "-24, 31, 45", "27.10.2026", "2026.10.27", "27.10.26", "45-30",
                  "12:30", "23:59", "00:00"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
        // Unless the half's letter comes after them (CSV columns); fields with colons.
        check("25,05,48.815,S,31,26,13.636,E", "degrees, minutes and seconds")
        check("25:05:48.815 S 31:26:13.636 E", "degrees, minutes and seconds")
        // A dash before a number that can't be minutes is a minus sign.
        check("40-74.0445", "decimal degrees", at: GeoPoint(lat: 40, lon: -74.0445))
        check("-33-70", "decimal degrees", at: GeoPoint(lat: -33, lon: -70))
        // A date after the position is other text.
        check("25.096893 S 31.437121 E 27.10.2026", "decimal degrees")
    }

    func testSouthOrSeconds() {
        // An S after minutes is south when the other half starts with its letter; "s" for seconds is written right after
        // the number (see testDashesAndUnitLetters).
        check("25°05'48.8 S E 031°26'13.6", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
        check("34°36'12 S W 58°22'54", "degrees, minutes and seconds", at: GeoPoint(lat: -34.603333, lon: -58.381667))
        check("25 05' 48 S E 31 26' 13", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096667, lon: 31.436944))
        check("40°41'21sN 74°02'40sW", "degrees, minutes and seconds", at: GeoPoint(lat: 40.689167, lon: -74.044444))
        // Degrees, minutes and seconds among words, with a minus sign: not a time or a count.
        check("We're at -25 05 48, 31 26 13", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096667, lon: 31.436944))
    }

    func testLoneOWithMoreEvidence() {
        // A Spanish, Portuguese or French word says west, a German or Afrikaans one east, wherever you are.
        check("Coordenadas: 23° 32′ 51″ S, 46° 38′ 10″ O", "degrees, minutes and seconds (O = west)", near: capeTown,
              at: GeoPoint(lat: -23.5475, lon: -46.636111))
        check("Coordenadas: 22,9068 S 43,1729 O", "decimal degrees (O = west)", at: rio)
        check("Estamos en 40,4168 N 3,7038 O. Ven pronto.", "decimal degrees (O = west)", at: madrid)
        check("Coordonnées : 47,2184 N 1,5536 O", "decimal degrees (O = west)", at: GeoPoint(lat: 47.2184, lon: -1.5536))
        check("Koordinaten: 25,096893 S 31,437121 O", "decimal degrees (O = east)", near: rio)
        // Without one, the reading nearer to you: from Madrid, New York rather than 74° east.
        check("40,68920° N, 74,04450° O", "decimal degrees (O = west)", near: madrid, at: newYork)
        // The place written twice: the reading of the O that agrees with the other.
        check("23°32′51″S 46°38′10″O / -23.5475, -46.6361", "decimal degrees", at: GeoPoint(lat: -23.5475, lon: -46.6361))
        check("40°25′08″N 3°41′31″O / 40.418888888889, -3.6919444444444", "decimal degrees",
              at: GeoPoint(lat: 40.418889, lon: -3.691944))
        // The O of a word ("Posição", "Estação", "año"), or one that reads the same either way, isn't a direction.
        for near in [nil, kruger] {
            check("Posição: -25.096893, 31.437121", "decimal degrees", near: near)
        }
        check("Localização: -22.9068, -43.1729", "decimal degrees", near: rio, at: rio)
        check("Estação -23.5505, -46.6333", "decimal degrees", at: GeoPoint(lat: -23.5505, lon: -46.6333))
        check("Sitio del año -25.096893, 31.437121", "decimal degrees")
        check("O ponto fica em -25.096893, 31.437121", "decimal degrees")
        check("-25.096893, 31.437121 o ponto de encontro", "decimal degrees", near: madrid)
        // The words of the whole text count, also when the position is read from a part of it.
        check("Sichtung um 12:30 bei 25,096893 S 31,437121 O", "decimal degrees (O = east)", near: madrid)
    }

    func testLabelledValuesWithWordsAfterThem() {
        // A direction or unit in words or brackets after a labelled value: read with it, not as a plain value.
        for t in ["Latitude: 25.096893 South, Longitude: 31.437121 East", "Lat 25.096893 Suid, Lon 31.437121 Oos",
                  "lat 25.096893 south lon 31.437121 east", "Latitude: 25.096893 (S)\nLongitude: 31.437121 (E)",
                  "Latitude: 25.096893 deg S, Longitude: 31.437121 deg E"] {
            check(t, "decimal degrees")
        }
        // exiftool's default output, and the like.
        for t in ["GPS Latitude                    : 25 deg 5' 48.81\" S\nGPS Longitude                   : 31 deg 26' 13.64\" E",
                  "Latitude: 25 deg 5 min 48.81 sec S\nLongitude: 31 deg 26 min 13.64 sec E",
                  "Latitude: 25 degrees 5 minutes 48.81 seconds South, Longitude: 31 degrees 26 minutes 13.64 seconds East",
                  "Breite: 25 Grad 5' 48.81\" S, Länge: 31 Grad 26' 13.64\" O"] {
            check(t, within: 2)
        }
        check("Lat: 25 deg 5.8135 min S\nLon: 31 deg 26.2273 min E", "degrees and decimal minutes", within: 2)
        // Other words after them are still read as before.
        for t in ["Lat: -25.096893 Lon: 31.437121 at 12:30", "Camp 14: Lat: -25.096893 Lon: 31.437121 (25+)"] {
            check(t, "decimal degrees")
        }
        // Length and width in German and Afrikaans.
        XCTAssertNil(CoordinateParser.parse("Länge: 120 cm, Breite: 60 cm"))
        XCTAssertNil(CoordinateParser.parse("Lengte 2,10 m breedte 0,90 m"))
    }

    func testNumbersAroundAPosition() {
        // A stop or camp number before letters that go before the numbers.
        check("Stop 3 S25.0969 E31.4371", "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        check("Gate 4 S 25.096893 E 31.437121", "decimal degrees")
        check("Site 12 N 51.5007 W 0.1246", "decimal degrees", at: london)
        // A list of places in degrees and minutes or seconds: none of them is meant.
        let a = "25°05'48.8\"S 31°26'13.6\"E", b = "24°59'46.0\"S 31°35'28.0\"E"
        for t in [a + "\n" + b, a + " " + b, "Waypoints:\n" + a + "\n" + b + "\n25°10'00.0\"S 31°30'00.0\"E", "1. " + a + "\n2. " + b,
                  "25°05'S 31°26'E\n24°59'S 31°35'E", "25°S 31°E\n24°S 32°E"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
        // "25+" or "22+22" is a count or a sum, not a short plus code near you.
        for t in ["Elephants at -25.096893, 31.437121 (25+)", "-25.096893, 31.437121\n\nCount: 25+"] {
            check(t, "decimal degrees", near: kruger)
        }
        for t in ["Temp 35+", "Age 25+", "22+22=44"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testLongitudeFirstOnlyWhereTheFormatSaysSo() {
        // "coordinates: [a, b]" on its own may be latitude first as well: it can't be told which.
        XCTAssertNil(CoordinateParser.parse("coordinates: [-25.096893, 31.437121]"))
        XCTAssertNil(CoordinateParser.parse("coordinates: [31.437121, -25.096893]"))
        // A Google Earth path, or two placemarks: the camera's <LookAt> isn't the place either.
        let lookAt = "<LookAt><longitude>31.50412</longitude><latitude>-25.04501</latitude><range>21345.6</range></LookAt>"
        for t in ["<kml><Document><Placemark><name>Untitled Path</name>" + lookAt
                    + "<LineString><coordinates>31.437121,-25.096893,0 31.591110,-24.996110,0</coordinates></LineString>"
                    + "</Placemark></Document></kml>",
                  "<kml><Document>" + lookAt + "<Placemark><Point><coordinates>31.437121,-25.096893,0</coordinates></Point>"
                    + "</Placemark><Placemark><Point><coordinates>31.591110,-24.996110,0</coordinates></Point></Placemark></Document></kml>"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }

    func testThePinInAMapLink() {
        // Not the map's centre, wherever it is in the link.
        for t in ["https://yandex.ru/maps/?ll=31.500000%2C-25.050000&z=12&pt=31.437121%2C-25.096893",
                  "https://yandex.ru/maps/?ll=31.500000%2C-25.050000&mode=whatshere&whatshere%5Bpoint%5D=31.437121%2C-25.096893",
                  "https://www.arcgis.com/home/webmap/viewer.html?center=31.5,-25.05&level=12&marker=31.437121,-25.096893",
                  "https://www.openstreetmap.org/?lat=-25.05&lon=31.5&zoom=12&layers=M&mlat=-25.096893&mlon=31.437121",
                  "https://www.bing.com/maps?cp=-25.05~31.5&lvl=11&sp=point.-25.096893_31.437121_Camp"] {
            check(t, "map link")
        }
    }

    func testLongRunsOfSpaces() {
        // A spreadsheet's empty cells, a message with many empty lines: read quickly (it is read as you type).
        let start = Date()
        check("Latitude\tLongitude" + String(repeating: "\t", count: 25) + "\n-25.096893\t31.437121" + String(repeating: "\t", count: 25)
              + String(repeating: "\n" + String(repeating: "\t", count: 25), count: 400) + "\nSource: GPS", "decimal degrees")
        check("Lions at -25.096893, 31.437121" + String(repeating: "\n", count: 20_000) + "ok", "decimal degrees")
        XCTAssertNil(CoordinateParser.parse("1" + String(repeating: " ", count: 20_000) + "N"))
        XCTAssertNil(CoordinateParser.parse("36J" + String(repeating: " ", count: 20_000) + "X 342401 7223411"))
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
    }

    // Inputs checked a fourth time, by family. Near Skukuza: S 24° 59' 48.9" E 31° 35' 32.3", or in decimal minutes
    // S 24° 59.815' E 31° 35.539'.
    private let dms = GeoPoint(lat: -24.9969167, lon: 31.5923056), ddm = GeoPoint(lat: -24.9969167, lon: 31.5923167)

    func testACountOrNameBeforeTheLetters() {
        // A sighting's count, a camp or waypoint number, a datum or a year before a position with its letters in front:
        // not its latitude.
        for t in ["3 rhino S 24° 59' 48.9\" E 31° 35' 32.3\"", "Leopard 2 S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "2 lions S24 59 48.9 E31 35 32.3", "Camp 14 S24 59 48.9 E31 35 32.3", "Site 14: S24 59 48.9 E31 35 32.3",
                  "Sighting 2:\nS24°59'48.9\"\nE31°35'32.3\"", "2. S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "WP 02 S 24° 59' 48.9\" E 31° 35' 32.3\"", "Pride of 6 S24°59'48.9\" E31°35'32.3\"",
                  "WGS 84: S 24° 59' 48.9\" E 31° 35' 32.3\"", "GPS (WGS 84): S24 59 48.9 E31 35 32.3", "WGS-84: S24 59 48.9 E31 35 32.3",
                  "Datum: WGS 84\nS24 59 48.9 E31 35 32.3", "Kruger 2026: S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "EPSG:4326: S24 59 48.9 E31 35 32.3", "Lions 2 E 31° 35' 32.3\" S 24° 59' 48.9\""] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        for t in ["Camp 14 S24.99 E31.59", "2 lions S 24.99 E 31.59", "Leopard 2 S24.99, E31.59"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
        }
        check("Site 3 S 25.0 E 31.5", "decimal degrees", at: GeoPoint(lat: -25, lon: 31.5))
        for t in ["3 rhino S 24 59.8 E 31 35.5", "3 rhino S24°59.8' E31°35.5'"] {
            check(t, "degrees and decimal minutes", at: GeoPoint(lat: -24.9966667, lon: 31.5916667))
        }
        // A chat message's time and date before it.
        check("[05:31, 27/10/2026] Jaco: S24 59.815 E31 35.539 2 lions", "degrees and decimal minutes", at: ddm)
        // With the letters after the numbers, what follows the last letter is other text; unless the last half could go on
        // with it ("24 59 E 31 35"), which makes it unclear.
        check("24.99 S 31.59 E 12.5", "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
        check("25.096893 S 31.437121 E 3 cubs", "decimal degrees")
        check("Lions 25.0969 S 31.4371 E, 30° bearing", "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        XCTAssertNil(CoordinateParser.parse("12.5 S 24 59 E 31 35"))
    }

    func testRoadNames() {
        // Kruger's roads (S3, S100, H4-1) next to a position aren't a third letter or a number of it.
        for t in ["S3 S24 59.815 E31 35.539", "S24 59.815 E31 35.539 on S3", "S24 59.815 E31 35.539 (S3)",
                  "Lions on the S3 at S24 59.815 E31 35.539", "Leopard on H4-1 at S24 59.815 E31 35.539"] {
            check(t, "degrees and decimal minutes", at: ddm)
        }
        for t in ["Lions on S3: S24 59 48.9 E31 35 32.3", "S100: S24 59 48.9 E31 35 32.3"] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        for t in ["Cheetah on H4-1, -24.99693, 31.59231", "H4-1, -24.99693, 31.59231",
                  "Cheetah! 2km past Nkuhlu picnic spot on H4-1, -24.99693, 31.59231 at 07:45"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99693, lon: 31.59231))
        }
        // A letter right after a number is its direction.
        check("5130N00007W", "GPS (NMEA)", at: GeoPoint(lat: 51.5, lon: -0.1166667))
    }

    func testNumbersAfterAPosition() {
        // A phone number, a count or a height after a position with its letters in front.
        check("Skukuza Rest Camp GPS: S24 59 48.9 E31 35 32.3 Tel: 013 735 4152", "degrees, minutes and seconds", at: dms)
        check("S 24° 59' 48.9\" E 31° 35' 32.3\" 3 rhino", "degrees, minutes and seconds", at: dms)
        check("S24 59.815 E31 35.539 2 lions", "degrees and decimal minutes", at: ddm)
        check("S 24° 59' 48.9\", E 31° 35' 32.3\", 270.5 m", "degrees, minutes and seconds", at: dms)
        check("S24 59.815 E31 35.539 270m", "degrees and decimal minutes", at: ddm)
        check("N 51° 30' 2.5\" W 0° 7' 28.6\" 12 m", "degrees, minutes and seconds", at: GeoPoint(lat: 51.5006944, lon: -0.1246111))
        // A height with its unit is written for people, who put the latitude first.
        for t in ["-24.996931, 31.592312, 270 m", "Lat -24.996931 Lon 31.592312 270 m"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.996931, lon: 31.592312))
        }
        // Without one, it can't be told from KML's longitude, latitude and altitude.
        XCTAssertNil(CoordinateParser.parse("-24.996931,31.592312,270.5"))
    }

    func testMoreWaysOfWritingIt() {
        // The location iMessage sends (a vCard, with its commas escaped).
        check("item1.URL;type=pref:http://maps.apple.com/?ll=-24.996931\\,31.592312&q=-24.996931\\,31.592312", "map link",
              at: GeoPoint(lat: -24.996931, lon: 31.592312))
        check("BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Current Location;;;\r\nFN:Current Location\r\nitem1.URL;type=pref:http://maps.apple.com/"
              + "?ll=-24.996931\\,31.592312&q=-24.996931\\,31.592312\r\nitem1.X-ABLabel:map url\r\nEND:VCARD\r\n", "map link",
              at: GeoPoint(lat: -24.996931, lon: 31.592312))
        // Degrees, minutes and seconds with points, the letter right next to them.
        for t in ["S24.59.48.9 E31.35.32.3", "24.59.48.9S 31.35.32.3E"] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        // UTM within 10 km north of the equator, where the northing has fewer digits.
        check("31N 166021 0", "UTM", within: 2, at: GeoPoint(lat: 0, lon: 0))
        check("31 N 500000 0", "UTM", within: 2, at: GeoPoint(lat: 0, lon: 3))
        check("37N 285000 1500", "UTM", within: 2, at: GeoPoint(lat: 0.0135632, lon: 37.0682178))
        // GeoJSON copied without its closing bracket is still longitude first.
        check("\"coordinates\": [\n  31.4371,\n  -25.0969", "GeoJSON", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        check("{\"type\": \"Point\", \"coordinates\": [31.4371, -25.0969", "GeoJSON", at: GeoPoint(lat: -25.0969, lon: 31.4371))
    }

    func testTemperaturesAndAngles() {
        // A degree sign, or degrees in words, on whole or one-decimal numbers among words: temperatures and angles.
        for t in ["Dis 25 grade in die oggend en 31 grade in die middag", "Dit was 38 grade by Satara en 41 grade by Letaba",
                  "Min 12 grade, maks 31 grade", "Draai 90 grade links, dan 45 grade regs", "Heute 25 Grad, morgen 31 Grad",
                  "It was 25 degrees this morning and 31 degrees by noon", "Temp 31 deg, humidity 25 deg",
                  "Satara 38°C, Letaba 41°C", "Max 31°C min 18°C", "Skukuza: 34°C / 21°C", "Skukuza 38°/22°", "Min -5° max 31°",
                  "77°F / 88°F", "Water temp 18.5°, air 25.3°", "Pool 26°, sea 19°", "Slope 25°, aspect 31°",
                  "Wind veering from 20° to 45°", "Pitch 12.5°, roll 3.2°", "Field of view 63.4° x 44.1°"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // Lists of degrees, and a degree sign typed for the minute mark: the second one isn't minutes.
        for t in ["Max temps: 31° 32° 33° 34°", "25° 25° 25° 25°", "Day 1: 25°, day 2: 31°", "Bearing 10° 20° 30° 40°",
                  "Skukuza Mon 34° 21°, Tue 33° 20°", "25°05°48.8\"S 31°26°13.6\"E", "25° 05° S, 31° 26° E"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testDistancesTimesAndYears() {
        // A distance after a direction, and a surveyor's bearing.
        for t in ["Drive S 25 km, then E 31 km", "Drive S 25 km then E 31 km to the hide", "Drive N 2 km, E 3 km",
                  "From the gate go N 3 km, then W 2 km", "S 30° W, distance 120 m", "Course S 30° E 12 nm"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // Times, durations and time zones; years, opening hours, postal codes and a screen size.
        for t in ["05:30 - 18:30", "05:30 - 08:45", "05:30, 18:15", "05:31 / 18:12", "12:30 - 14:45", "12:30:45 - 13:15:20",
                  "10:15:30, 11:20:40", "02:15:30, 01:45:20", "+02:00 -05:00", "3:2, 16:9", "2015-2020", "2010-2020", "2000-2024",
                  "2023/24", "2019/20", "2001/2002", "0800-1700", "0800 - 1700", "0530-1830", "0600-1800", "1200 1430",
                  "1350 1242", "1350, 1242", "0800 1234", "1920×1200"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // Fields with colons and a minus sign, with seconds in both halves, can't be times.
        check("-24:59:41, 31:35:32", "degrees, minutes and seconds", at: GeoPoint(lat: -24.9947222, lon: 31.5922222))
    }

    func testTwoDifferentPlaces() {
        // Two places 19 km apart in any two forms: links, a link and a position, plus codes, a grid reference, labelled
        // values, WKT or degrees and a position elsewhere in the text.
        for t in ["https://www.google.com/maps?q=-25.0969,31.4371 https://www.google.com/maps?q=-24.9961,31.5911",
                  "Location: https://www.google.com/maps?q=-25.0969,31.4371\nLocation: https://www.google.com/maps?q=-24.9961,31.5911",
                  "https://maps.apple.com/?ll=-25.0969,31.4371 https://maps.apple.com/?ll=-24.9961,31.5911",
                  "geo:-25.0969,31.4371 geo:-24.9961,31.5911", "https://www.google.com/maps?q=-25.0969,31.4371 or -24.9961, 31.5911",
                  "-25.0969, 31.4371\nhttps://maps.google.com/?q=-24.9961,31.5911",
                  "5G6HWC3P+6R\n5G6HXC5P+2Q", "5G6HWC3P+6R or 5G6HWC4Q+2X", "5G6HWC3P+6R and -24.9961, 31.5911",
                  "Lions WC3P+6R, leopard XC5P+2Q",
                  "36J 342401 7223411\n-24.9961 31.5911", "36J 342401 7223411 and -24.9961, 31.5911",
                  "From -24.9961, 31.5911 to 36J 352401 7233411", "36J UT 42401 23411 and -24.9961 31.5911",
                  "Camp at 36JUT4240123411; sighting at -24.9961 31.5911",
                  "lat: -25.0969, lon: 31.4371 (Skukuza: -24.9961, 31.5911)", "<trkpt lat=\"-25.0969\" lon=\"31.4371\"/> -24.9961, 31.5911",
                  "lat=-25.0969 lon=31.4371 then go to -24.9961, 31.5911", "POINT (31.4371 -25.0969) and -24.9961, 31.5911",
                  "25°05'48.8\"S 31°26'13.6\"E\n-24.9961, 31.5911", "25°05'48.8\"S 31°26'13.6\"E or -24.9961, 31.5911",
                  "Camp: -25.0969, 31.4371\nS24 59 48.9 E31 35 32.3"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // The same place twice is read.
        check("https://www.google.com/maps?q=-25.096893,31.437121 https://www.google.com/maps?q=-25.096893,31.437121", "map link")
        check("-25.096893, 31.437121\nhttps://maps.google.com/?q=-25.096893,31.437121", "map link")
        check("5G6HWC3P+6R, 5G6HWC3P+6R", "plus code", within: 8)
        check("36J UT 42401 23411 / -25.096893, 31.437121", "decimal degrees")
    }

    func testLongPastesAreReadQuickly() {
        // A label with a long run of spaces after it, many zone-like words before a grid reference, a long list of degrees
        // and a 20 KB chat log: each read in a time that grows with the length, not faster (it is read as you type).
        let lines = ["Only 4x4 on the S100 after the rain, 50 m of mud near the bridge", "Leaving at 5 p.m., 2 x 2 litres of water each",
                     "Saw 12 elephants and 3 calves at the dam, about 400 m from the H4-1", "Gate opens 05:30, closes 18:30",
                     "Booked site 14 for 27/10/2026, R 1234.50 paid", "Temp 34°C at noon, 21°C tonight"]
        var log: [String] = []
        while log.joined(separator: "\n").count < 20_000 {
            log.append("[12:\(10 + log.count % 50), 27/10/2026] Jaco: " + lines[log.count % lines.count])
        }
        log.append("[13:01, 27/10/2026] Jaco: Booking ref 1234567, lions at -25.0969, 31.4371")
        let start = Date()
        XCTAssertNil(CoordinateParser.parse("lat" + String(repeating: " ", count: 20_000) + "x 1"))
        XCTAssertNil(CoordinateParser.parse("latitude" + String(repeating: " ", count: 200) + "S 25"))
        XCTAssertNil(CoordinateParser.parse(String(repeating: "1C ", count: 10_000) + "x 342401 7223411"))
        XCTAssertNil(CoordinateParser.parse(String(repeating: "25° ", count: 5_000)))
        check(log.joined(separator: "\n"), "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        XCTAssertLessThan(Date().timeIntervalSince(start), 3)
    }

    // Inputs checked a fifth time, by family.

    func testADirectionAfterADistance() {
        // A distance or a time with its direction after a position ("3 km N of Skukuza") isn't that position's letter.
        let typed = GeoPoint(lat: -24.99692, lon: 31.59231)
        for t in ["-24.99692, 31.59231 (3 km N of Skukuza)", "-24.99692, 31.59231 3 km N of Skukuza", "-24.99692, 31.59231\n3 km N of Skukuza",
                  "Lions -24.99692, 31.59231 3km N of Skukuza", "Leopard: -24.99692, 31.59231 (3 km N of Skukuza on H1-2)",
                  "-24.99692, 31.59231 (2 km S of Satara)", "-24.99692, 31.59231 (1 km W of Skukuza)",
                  "-24.99692, 31.59231 (500 m W of the bridge)", "-24.99692, 31.59231, 3 km N of Skukuza", "-24.99692 31.59231 3 km N",
                  "Leeus by -24.99692, 31.59231, 2 km suid van Skukuza", "-24.99692, 31.59231 (5 min N of Skukuza)"] {
            for near in [nil, kruger] {
                check(t, "decimal degrees", near: near, at: typed)
            }
        }
        check("51.5007, -0.1246 (3 km S of Camden)", "decimal degrees", at: london)
    }

    func testDrivingDirectionsAndTimes() {
        // Distances in words or "kms", driving times, and a distance with its direction but no unit ("5 N of Satara") aren't
        // positions; nor are times and distances in a sports result.
        for t in ["Drive south 25 kilometres then east 31 kilometres", "Drive S 25 kilometre, then E 31 kilometre",
                  "Drive north 2 kilometers, then east 3 kilometers", "Drive S 25 kms, then E 31 kms", "S 25 kms E 31 kms",
                  "Lions S 2 kms, W 1 kms of Satara", "From Satara drive 10 min S on the H1-3, then 5 min E on the S100",
                  "Head 10 min north then 2 min west on the S100", "Ry 10 min suid van Satara en dan 2 min oos op die S100",
                  "Leeus 10 min S van Skukuza, 5 min O van die brug", "Lions 10 minutes S of Satara and 5 minutes E of the H1-3",
                  "Leopard in tree, 5 min N of Skukuza on H1-2, 1 min W of the Sabie bridge",
                  "Lions 5 N of Satara, leopard 3 E of Satara", "Wild dogs 3 S of Lower Sabie, cheetah 2 W of Crocodile Bridge",
                  "Lions 25 S of Satara on the H1-3, 31 E of the river", "Pride of 15 S of Tshokwane, 3 E of the H1-2",
                  "Lions S 2, W 1 of Satara", "100m 10.123 s, long jump 8.952 m", "Ran 100 m in 12.345 s, then 8.952 m"] {
            for near in [nil, kruger] {
                XCTAssertNil(CoordinateParser.parse(t, near: near), t)
            }
        }
        // Whole degrees with the letter in front are still read.
        check("Elephants S 25 E 31", "decimal degrees", at: GeoPoint(lat: -25, lon: 31))
    }

    func testMinutesWrittenWithAnM() {
        // "m" for minutes after whole degrees without a degree sign too, and "s" for the seconds after it.
        for t in ["25 05 m S, 31 26 m E", "25 5m S, 31 26m E", "S 25 05m E 31 26m", "25 05m S 31 26m E", "-25 5m, 31 26m"] {
            check(t, "degrees and decimal minutes", at: GeoPoint(lat: -25.083333, lon: 31.433333))
        }
        check("25 05.8m S, 31 26.2m E", "degrees and decimal minutes", at: GeoPoint(lat: -25.096667, lon: 31.436667))
        check("25 05.8136m S 31 26.2273m E", "degrees and decimal minutes", within: 2)
        for t in ["25 5m 48.8s S, 31 26m 13.6s E", "25 05m 48.8s S 31 26m 13.6s E"] {
            check(t, "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
        }
        check("25 5m 48s S 31 26m 13s E", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096667, lon: 31.436944))
        // Minutes and seconds in words in the other languages, or after numbers alone.
        for t in ["25 grados 5 minutos 48 segundos S, 31 grados 26 minutos 13 segundos E", "25 05 48 sec S 31 26 13 sec E",
                  "25 5 min 48 sec S, 31 26 min 13 sec E"] {
            check(t, "degrees, minutes and seconds", at: GeoPoint(lat: -25.096667, lon: 31.436944))
        }
    }

    func testAPositionFollowedByItsOwnDecimals() {
        // A position and the same place in signed decimals after it (a map's title, a GPS app showing both).
        let p = GeoPoint(lat: -25.096889, lon: 31.437111)
        for t in ["25°05'48.8\"S 31°26'13.6\"E\n-25.096889, 31.437111", "25°05'48.8\"S 31°26'13.6\"E, -25.096889, 31.437111",
                  "25°05'48.8\"S 31°26'13.6\"E -25.096889, 31.437111", "25°05'48.8\"S 31°26'13.6\"E\n\n-25.096889, 31.437111",
                  "25°05'48.8\"S 31°26'13.6\"E\n-25.096889 31.437111", "25 05 48.8 S 31 26 13.6 E\n-25.096889, 31.437111",
                  "25°05.8133'S 31°26.2267'E\n-25.096889, 31.437111", "25.096889 S 31.437111 E\n-25.096889, 31.437111"] {
            check(t, at: p)
        }
        check("51°30′02.5″N 0°07′28.6″W\n51.500694, -0.124611", at: GeoPoint(lat: 51.500694, lon: -0.124611))
        check("Leopard 24°59'48.9\"S 31°35'32.3\"E\n-24.996917, 31.592306", at: dms)
        // Another place after it still isn't read.
        XCTAssertNil(CoordinateParser.parse("25°05'48.8\"S 31°26'13.6\"E -24.9961, 31.5911"))
    }

    func testMoreWaysWithPointsAndColons() {
        // Degrees, minutes and seconds with points after a degree sign, or with a space next to the letter.
        for t in ["S24°59.48.9 E31°35.32.3", "S 24°59.48.9 E 31°35.32.3", "24°59.48.9S 31°35.32.3E", "24°59.48.9 S, 31°35.32.3 E",
                  "S 24.59.48.9 E 31.35.32.3", "24.59.48.9 S 31.35.32.3 E", "S 24.59.48.9, E 31.35.32.3"] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        check("S25°05.48.8 E31°26.13.6", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
        // Decimal minutes after a degree sign stay decimal minutes.
        check("S24°59.815 E31°35.539", "degrees and decimal minutes", at: ddm)
        // Degrees and minutes with colons and a minus sign that no hour or time zone has.
        check("-25:05, 31:26", "degrees and decimal minutes", at: GeoPoint(lat: -25.083333, lon: 31.433333))
        check("-24:59, 31:35", "degrees and decimal minutes", at: GeoPoint(lat: -24.983333, lon: 31.583333))
        for t in ["-05:00, 02:00", "-25:05 - 31:26"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
        // UTM near the equator with the northing labelled first, or "E" and "N" after the numbers.
        for t in ["37N N 1500 E 285000", "37N 285000 E 1500 N"] {
            check(t, "UTM", within: 2, at: GeoPoint(lat: 0.0135632, lon: 37.0682178))
        }
    }

    func testListsOfBearings() {
        for t in ["Bearing 10° 20° 30°", "Heading 10° 20° 30°", "Bearing: 10° 20° 30°", "Bearing 10°, 20°, 30°", "Bearing 10 20 30"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // A bearing or an altitude next to a position is still left out.
        check("Bearing 270°, -25.096893, 31.437121", "decimal degrees")
        check("-24.99, 31.59, alt 312", "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
    }

    func testRoadsAndMarkersNextToAPosition() {
        // A kilometre marker on a road ("H1-3 km 14"), or a road right before the position or a count, isn't part of it.
        let h13 = GeoPoint(lat: -24.3812, lon: 31.8456)
        for t in ["Lions on H1-3 km 14: 24.3812 S 31.8456 E", "Lions on H1-3 km 14: 24 22.872 S 31 50.736 E",
                  "H1-3 km 14 - lions 24 22.872 S 31 50.736 E", "Lions H1-3 km 14, 24.3812 S, 31.8456 E",
                  "Lions on S100 km 7: 24 22.872 S 31 50.736 E", "Lions on S100 24 22.872 S 31 50.736 E",
                  "Leopard on S100 km 7, -24.3812, 31.8456"] {
            check(t, within: 2, at: h13)
        }
        check("S100 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds", at: GeoPoint(lat: -24.381194, lon: 31.845611))
        check("S3 24 59.816 S 31 35.539 E", "degrees and decimal minutes", at: GeoPoint(lat: -24.996933, lon: 31.592317))
        check("S100 2 lions S24 23.580 E31 46.680", "degrees and decimal minutes", at: GeoPoint(lat: -24.393, lon: 31.778))
        check("Lions S25 07.178 E31 55.057 S28 3 males", "degrees and decimal minutes", at: GeoPoint(lat: -25.119633, lon: 31.917617))
        check("Lions on S 3 at S24 59.815 E31 35.539", "degrees and decimal minutes", at: ddm)
        // A road's marker after its name and "km" isn't the first field of the degrees after it; a number after a word could
        // be ("Camp 1 25 05.8 S, …"), unless both halves are degrees and minutes to 3 decimals, as a GPS writes them (see
        // testCountsAndTemperaturesBeforeAGarminPosition), and one after a distance is a distance ("5 km 24 59.816 S …").
        for t in ["Leopard H4-1 km 3 24 59.816 S 31 35.539 E", "Leopard H4-1 km 14 24 59.816 S 31 35.539 E",
                  "Lions 5 km 24 59.816 S 31 35.539 E", "Leopard km 3 24 59.816 S 31 35.539 E"] {
            check(t, "degrees and decimal minutes", at: GeoPoint(lat: -24.996933, lon: 31.592317))
        }
        XCTAssertNil(CoordinateParser.parse("Leopard km 3 24 59.8 S 31 35.5 E"))
        // A road written next to degrees and minutes, with no letter on the other half, could as well be a direction.
        for t in ["Lions on S28 25.0969, 31.4371", "Accident on N4 25.4567, 30.9876"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testTimesAndCountsWrittenWithLetters() {
        // "06h45", "4x4", "10am", "3rd", "5L", "12x" or "2x" next to a position: other text, not a grid zone.
        for t in ["Leeus by S24 59.816 O31 35.539 om 06h45", "4x4 only S24 59.816 E31 35.539", "Lions S24 59.816 E31 35.539 at 10am",
                  "Bring 5L water. S24 59.816 E31 35.539", "Wild dogs 12x S24 59.816 E31 35.539"] {
            check(t, at: GeoPoint(lat: -24.996933, lon: 31.592317))
        }
        for t in ["Skukuza Rest Camp\nGPS: S24 59 48.9 E31 35 32.3\nGate hours: 05h30 - 18h30\nTel: 013 735 4152",
                  "Lions S24 59 48.9 E31 35 32.3 3rd time", "Lions 24°59'48.9\"S 31°35'32.3\"E 4x4 only"] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        for t in ["Lions S24.99693 E31.59231 12h00", "Lions 24.99693 S 31.59231 E 2x leopard"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99693, lon: 31.59231))
        }
    }

    func testTimesDatesAndCountsAroundAPosition() {
        // A time, a date, a phone number or a count with a word next to a position isn't part of a list of numbers.
        let p = GeoPoint(lat: -24.3812, lon: 31.8456)
        for t in ["Lions at -24.3812, 31.8456, 06:45 this morning", "Lions -24.3812, 31.8456, 3 males and 2 cubs",
                  "Lions at -24.3812, 31.8456, 27/10/2026", "Lions at -24.3812, 31.8456, 082 555 1234", "Lions -24.3812, 31.8456; 06:45",
                  "Lions\n-24.3812, 31.8456,\n3 males", "Lions, 06:45, -24.3812, 31.8456", "27/10/2026, -24.3812, 31.8456",
                  "Wild dogs 12, -24.3812, 31.8456"] {
            check(t, "decimal degrees", at: p)
        }
        for t in ["-24.4567 31.6789 06:45", "Lions!! 4 females + 2 cubs, S36 about 5km from H1-3 junction -24.4567 31.6789 9:15am"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.4567, lon: 31.6789))
        }
        for t in ["Leopard 07:45, -24.99693, 31.59231", "Leopard -24.99693, 31.59231, 3 cubs", "Leopard -24.99693, 31.59231, 3 cubs, 05:45"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99693, lon: 31.59231))
        }
        // Before letters after the numbers, the numbers right before them, as many as the other half has.
        for t in ["[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "07:15 Lions 24 22.872 S 31 50.736 E",
                  "Lions at 07:15, 24 22.872 S 31 50.736 E", "27/10 Lions 24 22.872 S 31 50.736 E",
                  "12 buffalo at Mazithi dam\nLions 24 22.872 S 31 50.736 E"] {
            check(t, "degrees and decimal minutes", at: p)
        }
        check("27/10/2026, 07:15 - Jaco: 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds",
              at: GeoPoint(lat: -24.381194, lon: 31.845611))
        // Before letters in front, a time, a date or two counts rather than a whole half after the second letter.
        for t in ["12:30 Lions S 24 59 48.9 E 31 35 32.3", "[07:45] Jaco: S24 59 48.9 E31 35 32.3", "27/10: S24 59 48.9 E31 35 32.3",
                  "27/10/26 S24 59 48.9 E31 35 32.3", "2 lions 3 cubs S 24 59 48.9 E 31 35 32.3", "Camp 14 site 7 S24 59 48.9 E31 35 32.3",
                  "12:30 E 31 35 32.3 S 24 59 48.9", "Camp 14 24 59 48.9 S 31 35 32.3 E"] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
        check("Gate 05:30 S25 07 09.6 E31 54 56.4", "degrees, minutes and seconds", at: GeoPoint(lat: -25.119333, lon: 31.915667))
        check("Lions 07:45 S 24 59.81 E 31 35.53", "degrees and decimal minutes", at: GeoPoint(lat: -24.996833, lon: 31.592167))
        // Not when what is before them could be a half as well: then as before.
        check("24.99 S 31.59 E 12 30.5", "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
    }

    func testTheLastOfManyChatMessages() {
        // More messages than the whole text is read for: a GPS's degrees and minutes with letters can only be a position.
        let messages = ["[06:12, 27/10/2026] Pieter: Morning all, gates opened 05:30",
                        "[06:20, 27/10/2026] Marié: Ellies on the H1-3 near Tshokwane",
                        "[06:31, 27/10/2026] Jaco: Leopard at Sweni hide, 2 cubs", "[06:40, 27/10/2026] Pieter: Thanks!",
                        "[06:52, 27/10/2026] Anel: Road closed S41 after the rain", "[07:03, 27/10/2026] Marié: 12 buffalo at Mazithi dam"]
        check((messages + ["[07:15, 27/10/2026] Jaco: Lions on S100: S24 22.872 E31 50.736"]).joined(separator: "\n"),
              "degrees and decimal minutes", within: 2, at: GeoPoint(lat: -24.3812, lon: 31.8456))
        check((messages + ["[07:15, 27/10/2026] Jaco: Lions on S100: S24 22 52.3 E31 50 44.2"]).joined(separator: "\n"),
              "degrees, minutes and seconds", at: GeoPoint(lat: -24.381194, lon: 31.845611))
    }

    func testTwoPlacesAroundLinksAndOtherLetters() {
        // "ß" or a ligature in the text, a link with a space in it or a part of a link: the other place is still looked for.
        for t in ["Löwen bei lat: -25.0969, lon: 31.4371 (Skukuza: -24.9961, 31.5911). Grüße",
                  "Löwen an der Straße H4-1: 36J 342401 7223411, Skukuza -24.9961 31.5911", "5G6HWC3P+6R and -24.9961, 31.5911 Grüße",
                  "Camp: lat -25.0969 lon 31.4371; gate at S 24° 59' 48.9\" E 31° 35' 32.3\" (\u{FB01}rst left)",
                  "https://maps.google.com/?q=-25.0969, 31.4371 and -24.9961, 31.5911",
                  "https://maps.google.com/?q=-25.0969, 31.4371\n-24.9961, 31.5911", "geo:-25.0969 31.4371 and -24.9961, 31.5911",
                  "maps.google.com/?q=-25.0969, 31.4371 and S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "maps?q=-25.0969,31.4371 and -24.9961, 31.5911", "&ll=-25.0969,31.4371 and -24.9961, 31.5911",
                  "/@-25.0969,31.4371,15z and -24.9961, 31.5911"] {
            for near in [nil, kruger] {
                XCTAssertNil(CoordinateParser.parse(t, near: near), t)
            }
        }
        check("https://maps.google.com/?q=-25.096893, 31.437121", "map link")
        check("Löwen bei lat: -25.096893, lon: 31.437121. Grüße", "decimal degrees")
        // The same place written less precisely: the same as closely as that is written.
        for t in ["lat: -24.99, lon: 31.59 (S 24° 59' 48.9\" E 31° 35' 32.3\")",
                  "https://maps.google.com/?q=-24.99,31.59 S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "S 24° 59' 48.9\" E 31° 35' 32.3\" https://maps.google.com/?q=-24.99,31.59"] {
            check(t, at: GeoPoint(lat: -24.99, lon: 31.59))
        }
        check("Lions at -24.996931, 31.592312 (-24.997, 31.592)", "decimal degrees", at: GeoPoint(lat: -24.996931, lon: 31.592312))
    }

    // Inputs checked a sixth time, by family. Near Skukuza with five decimals, or in decimal minutes as "S24 59.816".
    private let typed = GeoPoint(lat: -24.99693, lon: 31.59231), gps = GeoPoint(lat: -24.9969333, lon: 31.5923167)

    func testWhatIsBeforeLettersInFront() {
        // A date, a time, a temperature or a number with decimals before letters written right before their numbers:
        // the letters are in front, whatever is before them.
        let p = GeoPoint(lat: -24.99, lon: 31.59)
        for t in ["27/10/2026 S24.99 E31.59", "2026/10/27 S24.99 E31.59", "27/10/26 S24.99 E31.59", "27/10/2026 06:45 S24.99 E31.59",
                  "3/4 S24.99 E31.59", "27/10/2026 E31.59 S24.99", "27/10 S24.99 E31.59", "25° S24.99 E31.59", "12.5 S24.99 E31.59",
                  "Lions at 06.45, S24.99 E31.59", "Km 14.5, then S24.99 E31.59", "Temp 31.5, lions at S24.99 E31.59",
                  "Lions 3.5 E31.59 S24.99", "Lions, 31°C, S 24.99 E 31.59"] {
            check(t, "decimal degrees", at: p)
        }
        // With a space after the letters too: a date's last number or a time isn't a half.
        check("27/10/2026 S 25.09 E 31.43", "decimal degrees", at: GeoPoint(lat: -25.09, lon: 31.43))
        check("27/10/2026 N 51.50 W 0.12", "decimal degrees", at: GeoPoint(lat: 51.5, lon: -0.12))
        check("27/10/2026 06:45 S 25 E 31", "decimal degrees", at: GeoPoint(lat: -25, lon: 31))
        // Fields joined by dashes or colons, and the SANParks form after a temperature or a degree sign.
        for t in ["06:45 S24:59:48.9 E31:35:32.3", "[06:45] S24-59-48.9 E031-35-32.3", "Gate 2/3 S24-59-48.9 E031-35-32.3",
                  "27/10/26 S24:59:48.9 E31:35:32.3", "2026-10-27 06:45 S24:59:48.9 E31:35:32.3", "06:45 E31:35:32.3 S24:59:48.9",
                  "38°C S24 59 48.9 E31 35 32.3", "Hot! 38°C. Lions S24 59 48.9 E31 35 32.3", "Temp 32°C, S 24 59 48.9 E 31 35 32.3",
                  "38° in the shade, lions at S24 59 48.9 E31 35 32.3", "Dis 34 grade! Leeus by S24 59 48.9 O31 35 32.3",
                  "Warm 34°. Leeus by S24 59 48.9 O31 35 32.3", "Lions 06.45 S24 59 48.9 E31 35 32.3"] {
            check(t, at: dms)
        }
        check("06:45 S25-05-48.8 E031-26-13.6", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
        // Both halves written alike: 24° 59.8' S, 31° 35.5' E, not 6.45° S, 24° 59.8' E.
        check("Leopard 06.45 S 24 59.8 E 31 35.5", "degrees and decimal minutes", at: GeoPoint(lat: -24.996667, lon: 31.591667))
    }

    func testWhatIsBeforeLettersAfter() {
        // A count or a name's number before a position in decimals with its letters after them, or a time or a short date.
        for t in ["Leopard 2 24.99692S, 31.59231E", "Camp 14 24.99692S, 31.59231E", "Stop 3 24.99692S, 31.59231E",
                  "#3 24.99692S, 31.59231E", "06:45 24.99692S, 31.59231E", "Leopard 2 24.99692 S, 31.59231 E"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99692, lon: 31.59231))
        }
        for t in ["06:45 24.99693 S 31.59231 E", "Lions at 06:45 24.99693 S 31.59231 E", "06:45 24.99693S 31.59231E",
                  "06:45 24.99693 S, 31.59231 E", "06:45 24.99693° S, 31.59231° E 3 lions", "27/10 24.99693 S 31.59231 E (3 lions)",
                  "27/10 24.99693 S 31.59231 E x2"] {
            check(t, "decimal degrees", at: typed)
        }
        // Not a number that could be the degrees of decimal minutes.
        XCTAssertNil(CoordinateParser.parse("25 5.8136 S, 31.4371 E"))
    }

    func testTemperaturesAndBearingsNextToAPosition() {
        // A temperature or a bearing after a position isn't its longitude, nor the next number of a list.
        for t in ["24 59 48.9 S 31 35 32.3 E 38°C", "Lions 24 59 48.9 S 31 35 32.3 E (32°C)", "Lions 24 59 48.9 S 31 35 32.3 E 45° bearing",
                  "Leeus 24 59 48.9 S 31 35 32.3 O 34°", "24 59 48.9 S 31 35 32.3 E 32 grade", "24 59 48.9 S 31 35 32.3 E 1° C cooler",
                  "24°59'48.9\"S 31°35'32.3\"E 32°C"] {
            check(t, at: dms)
        }
        for t in ["-24.996931, 31.592312, 32°C", "Lions at -24.996931, 31.592312, 32°C", "Sun 27 Oct, 5 lions, -24.996931 31.592312, 32°C",
                  "Lions -24.996931, 31.592312, 32℃", "-24.996931, 31.592312; 32°C"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.996931, lon: 31.592312))
        }
        for t in ["24.99693° S, 31.59231° E 32°C", "Lions 24.99693° S, 31.59231° E 32°C", "-24.99693, 31.59231, 32°C, 06:45"] {
            check(t, "decimal degrees", at: typed)
        }
        check("Lions 24 59.816 S 31 35.539 E 32°C", "degrees and decimal minutes", at: gps)
        // Ranges of temperatures.
        for t in ["18°-31°", "18° - 31°", "25° and 31°"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testOneCoordinateIsNotAPosition() {
        // A latitude or a longitude on its own, with its minutes.
        for t in ["S 24° 59.815'", "25° 05.8136' S", "E 31° 35.539'", "24° 59'", "Lat: S 24° 59.815'", "24° 59.816 S", "S 24° 59.816"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // A degree sign read in the wrong encoding ("Â°"), or written with a small o.
        check("25Â° 05.8136' S, 31Â° 26.2273' E", "degrees and decimal minutes", within: 2)
        check("25ᵒ05'48.8\"S 31ᵒ26'13.6\"E", "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
    }

    func testOtherDashesAndNumbers() {
        // A dash of any kind before a number is a minus sign; one with spaces round it, after a half written with a minus
        // sign, separates the halves.
        for t in ["\u{2010}25.096893, 31.437121", "\u{2011}25.096893 31.437121", "\u{2012}25.096893, 31.437121",
                  "\u{FE63}25.096893, 31.437121", "Lat: \u{2010}25.096893 Lon: 31.437121", "-25.096893 - 31.437121",
                  "-25.096893 – 31.437121"] {
            check(t, "decimal degrees")
        }
        check("-24°59.815' - 31°35.539'", "degrees and decimal minutes", at: ddm)
        // A fraction, a superscript or a circled number next to a position isn't one of its digits.
        for t in ["Lions S 24.99 E 31.59, ½ km from gate", "Burn area S 24.99 E 31.59, 2 km²", "Lions 24.99 S 31.59 E (½ km N of camp)",
                  "Lions S24.99 E31.59 ①"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.99, lon: 31.59))
        }
    }

    func testListsOfPlacesInMixedForms() {
        // Two or three places in grid references, links, degrees and decimals, also after the same place written four or
        // five ways: none of them is meant.
        for t in ["Lions 36J 342401 7223411\nLeopard 36J 352401 7233411\nCheetah -24.8961, 31.6911",
                  "Lions 36J UT 42401 23411\nLeopard 36J UT 52401 33411\nCheetah -24.8961, 31.6911",
                  "Lions 36J 342401 7223411\nLeopard 36J 352401 7233411\nCheetah https://maps.google.com/?q=-24.8961,31.6911",
                  "25°05'48.8\"S 31°26'13.6\"E\n24°59'46\"S 31°35'28\"E\nhttps://maps.google.com/?q=-24.8961,31.6911",
                  "S 25.0969 E 31.4371\nS 24.9961 E 31.5911\n36J 362401 7243411",
                  "36J 342401 7223411\n36J 342401 7223411\n-24.9961, 31.5911",
                  "36J 342401 7223411\n36J UT 62401 43411\n36J 352401 7233411",
                  "https://maps.google.com/?q=-25.096893,31.437121\n5G6HWC3P+6R\n2505.8136,S,03126.2273,E\n-2505.8136+03126.2273/\n"
                    + "36J 342401 7223411\n-24.9961, 31.5911",
                  "https://maps.google.com/?q=-25.0969,31.4371 \n5G6HWC3P+6R \n2505.8136,S,03126.2273,E \n36J 342401 7223411 \n"
                    + "lat=-25.0969 lon=31.4371 \nLeopard: -24.9961, 31.5911"] {
            for near in [nil, kruger] {
                XCTAssertNil(CoordinateParser.parse(t, near: near), t)
            }
        }
    }

    func testTheSamePlaceInMoreForms() {
        for t in ["S 25.096893 E 31.437121\n25°05'48.8\"S 31°26'13.6\"E", "S 25.096893 E 31.437121\n25.096893 S 31.437121 E",
                  "36J 342401 7223411\n36J 342401 7223411", "36J 342401 7223411\n36J UT 42401 23411",
                  "25°05'48.8\"S 31°26'13.6\"E\n25°05'48.8\"S 31°26'13.6\"E\n25°05'48.8\"S 31°26'13.6\"E",
                  "https://maps.google.com/?q=-25.096893,31.437121\n5G6HWC3P+6R\n2505.8136,S,03126.2273,E\n36J 342401 7223411\n"
                    + "lat=-25.096893 lon=31.437121\n-25.096893, 31.437121"] {
            check(t, within: 10)
        }
        check("N 51.500694 W 0.124611\n51°30'02.5\"N 0°07'28.6\"W", within: 10, at: GeoPoint(lat: 51.500694, lon: -0.124611))
    }

    func testRoadMarkersAndNumbersNextToAPair() {
        // A road's marker after the position ("km 12"), a number after labelled values, and a distance or an odometer with
        // decimals before a pair.
        for t in ["-24.99693, 31.59231 km 12", "S 24.99693 E 31.59231 km 12 H4-1", "Lions S 24.99693 E 31.59231 km 12",
                  "Leeus S 24,99693 O 31,59231 km 12", "-24.99693; 31.59231 km 12", "-24.99693, 31.59231 km 120",
                  "-24.99693 31.59231 Km-12", "27/10 S 24.99693 E 31.59231 km 12", "Lat -24.99693 Lon 31.59231 06:45",
                  "Lat -24.99693 Lon 31.59231 3 lions", "Lat -24.99693 Lon 31.59231 27/10/2026", "Lat -24.99693 Lon 31.59231 km 12",
                  "Breedte -24,99693 Lengte 31,59231 3 leeus"] {
            check(t, at: typed)
        }
        check("06:45 S 24 59.816 E 31 35.539 km 12", "degrees and decimal minutes", at: gps)
        for t in ["H1-2 km 23.4, -24.9969, 31.5923", "Lions on H4-1 km 12.5 -24.9969 31.5923", "Odometer 12345.6, -24.9969, 31.5923"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -24.9969, lon: 31.5923))
        }
        check("Lions on H4-1 at km 12.5, -24.996931, 31.592312", "decimal degrees", at: GeoPoint(lat: -24.996931, lon: 31.592312))
        // A list is still a list.
        XCTAssertNil(CoordinateParser.parse("12.345, -24.9969, 31.5923"))
    }

    func testDegreesInWordsAndMinutesWithAnMNextToTheLetter() {
        for t in ["S24 deg 59.816 E31 deg 35.539", "Lions at S24 deg 59.816 E31 deg 35.539",
                  "S24 degrees 59.816 minutes E31 degrees 35.539 minutes", "S24 deg 59.816 min, E31 deg 35.539 min",
                  "S24 grad 59.816 E31 grad 35.539", "S24 graad 59.816 E31 graad 35.539", "S24 ° 59.816' E31 ° 35.539'",
                  "S24 °59.816' E31 °35.539'", "S24 59.816m E31 35.539m", "S24 59.816 m E31 35.539 m", "S24d 59.816m E31d 35.539m",
                  "S24d59.816m E31d35.539m"] {
            check(t, "degrees and decimal minutes", at: gps)
        }
        check("S25d 05.8136m E31d 26.2273m", "degrees and decimal minutes", within: 2)
        check("S25 deg 07.182 E31 deg 54.942", "degrees and decimal minutes", at: GeoPoint(lat: -25.1197, lon: 31.9157))
        check("N40 deg 41.352 W74 deg 2.670", "degrees and decimal minutes", at: newYork)
        for t in ["S24 deg 59' 48.9\" E31 deg 35' 32.3\"", "S24deg 59' 48.9\" E31deg 35' 32.3\"",
                  "S24 graad 59' 48.9\" E31 graad 35' 32.3\""] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
    }

    func testSearchLinksTypedInOtherForms() {
        // A search, a pin or a destination in degrees and minutes, with letters or in DMS: the whole value is read, not its
        // first two numbers.
        for t in ["https://maps.google.com/?q=24+59.816+S+31+35.539+E", "https://maps.apple.com/?q=24+59.816+S+31+35.539+E",
                  "https://www.google.com/maps/search/?api=1&query=24%2059.816%20S%2031%2035.539%20E",
                  "https://www.google.com/maps/dir/?api=1&destination=24+59.816+S+31+35.539+E",
                  "https://maps.apple.com/?daddr=24+59.816+S+31+35.539+E", "https://maps.google.com/?q=-24+59.816,+31+35.539",
                  "https://www.google.com/maps/search/?api=1&query=S24%2059.816%20E31%2035.539",
                  "https://maps.apple.com/?q=S24%2059.816%20E31%2035.539", "https://maps.google.com/?q=S24+59.816+E31+35.539"] {
            check(t, "map link", at: gps)
        }
        check("geo:0,0?q=24 59.816 S 31 35.539 E", "geo: link", at: gps)
        check("https://maps.google.com/?q=24+59+48.9+S+31+35+32.3+E", "map link", at: dms)
        check("https://www.google.com/maps?q=24%C2%B059'48.9%22S+31%C2%B035'32.3%22E", "map link", at: dms)
        for t in ["https://maps.google.com/?q=24.996931+S+31.592312+E", "https://maps.google.com/?q=S24.996931,E31.592312"] {
            check(t, "map link", at: GeoPoint(lat: -24.996931, lon: 31.592312))
        }
        // A "%" elsewhere in the message doesn't keep the link encoded.
        check("100% sure https://www.google.com/maps/place/24%C2%B059'48.9%22S+31%C2%B035'32.3%22E/@-24.85,31.2,9z", "map link", at: dms)
        check("Battery 15% https://www.google.com/maps/place/5G6HWC3P%2B6R", "plus code", within: 8)
    }

    func testDottedSecondsBeforeAFullStop() {
        for t in ["Leopard S24.59.48.9 E31.35.32.3. 2 cubs", "Leopard S24.59.48.9, E31.35.32.3. 2 cubs",
                  "06:45 Lions at S24.59.48.9 E31.35.32.3.", "27/10 S24.59.48.9 E31.35.32.3.", "S24.59.48.9 E31.35.32.3.",
                  "Lions at S24.59.48.9 E31.35.32.3."] {
            check(t, "degrees, minutes and seconds", at: dms)
        }
    }

    func testShortDatesAndNamedNumbersNextToAPosition() {
        // A day and a month next to a position aren't another place.
        let p = GeoPoint(lat: -24.996931, lon: 31.592312)
        for t in ["27/10 -24.996931, 31.592312", "-24.996931 31.592312\n27/10", "-24.996931, 31.592312 (27/10)",
                  "-24.996931, 31.592312 12/3"] {
            check(t, "decimal degrees", at: p)
        }
        check("https://maps.google.com/?q=-24.996931,31.592312 27/10", "map link", at: p)
        check("-24.9969, 31.5923, 27/10", "decimal degrees", at: GeoPoint(lat: -24.9969, lon: 31.5923))
        // Beside a position, numbers each named by words before them are what the message says, not another place; on their
        // own the words may be labels in another language.
        for t in ["USD/ZAR 18.2345, EUR/ZAR 19.8765. Lions at -25.0969, 31.4371", "Temp 25.123, wind 31.456. Lions at -25.0969, 31.4371"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        }
        check("Широта: 55.7558 Долгота: 37.6173", "decimal degrees", at: GeoPoint(lat: 55.7558, lon: 37.6173))
        check("Lions at -25.096893 and 31.437121", "decimal degrees")
        XCTAssertNil(CoordinateParser.parse("25° and 31°"))
    }

    func testTablesWithAHeaderRow() {
        // Rows under a header that names the latitude and longitude columns, whatever the other columns hold.
        let p = GeoPoint(lat: -24.996931, lon: 31.592312)
        for t in ["id,lat,lon\n1,-24.996931,31.592312", "Name,Count,Lat,Lon\nLeopard,2,-24.996931,31.592312",
                  "id;name;lat;lon\n1;Camp;-24,996931;31,592312", "| lat | lon |\n|---|---|\n| -24.996931 | 31.592312 |",
                  "id,lat,lon\n1,-24.996931,31.592312\n2,-24.996932,31.592313"] {
            check(t, "decimal degrees", at: p)
        }
        XCTAssertNil(CoordinateParser.parse("id,lat,lon\n1,-24.9969,31.5923\n2,-25.0969,31.4371"))
    }

    func testCountsAfterLettersInAChatLog() {
        // More chat messages than the whole text is read for, the last a position with its letters after the numbers and
        // a count after it.
        let lines = ["[06:00, 27/10/2026] Jaco: Only 4x4 on the S100 after the rain, 50 m of mud near the bridge",
                     "[07:01, 27/10/2026] Jaco: Leaving at 5 tomorrow, gates open 05:30",
                     "[08:02, 27/10/2026] Jaco: Saw 12 elephants and 3 calves at Sunset Dam"]
        let log = (lines + lines + lines).joined(separator: "\n") + "\n[18:01, 27/10/2026] Jaco: "
        for t in ["Lions 24.9969 S 31.5923 E 2 males", "24.9969S 31.5923E 2 males"] {
            check(log + t, "decimal degrees", at: GeoPoint(lat: -24.9969, lon: 31.5923))
        }
        check(log + "Lions 24 59 48.9 S 31 35 32.3 E 3 rhino", "degrees, minutes and seconds", at: dms)
        check(log + "24°59.815' S 31°35.539' E 2 males", "degrees and decimal minutes", at: ddm)
    }

    func testNumbersWithAPlusAndLongRuns() {
        // A number with a plus isn't a plus code, nor is a padded code (an area 5 km across or more).
        for t in ["25000000+ visitors", "22000000+", "Over 25000000+ views", "5G6HWC00+"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        // Long runs after a grid reference's labels, or of digits in KML: read in a time that grows with the length.
        let start = Date()
        XCTAssertNil(CoordinateParser.parse("N" + String(repeating: " ", count: 20_000) + "x E 342401 7223411"))
        check("36J 342401 7223411 E" + String(repeating: " ", count: 20_000) + "x", "UTM", within: 2)
        XCTAssertNil(CoordinateParser.parse("<coordinates>" + String(repeating: "1", count: 20_000)))
        XCTAssertLessThan(Date().timeIntervalSince(start), 3)
    }

    // Inputs checked a seventh time, by family.

    func testMinutesAndSecondsInWordsAfterLetterMarks() {
        // "min" and "sec" after degrees written with a "d", or after minutes written with an "m": the position's minutes
        // and seconds, not a time left out.
        for t in ["S25d 05.8136 min E31d 26.2273 min", "-25d 05.8136 min, 31d 26.2273 min", "S 25 d 05.8136 min E 31 d 26.2273 min",
                  "Lat: -25d 05.8136 min Lon: 31d 26.2273 min", "25d 05.8136 min S, 31d 26.2273 min E"] {
            check(t, "degrees and decimal minutes", within: 2)
        }
        for t in ["S 25d 05 min 48.8 sec E 31d 26 min 13.6 sec", "-25d 05 min 48.8 sec, 31d 26 min 13.6 sec",
                  "25d 05 min 48.8\" S, 31d 26 min 13.6\" E", "Lat: 25d 05 min 48.8 sec S Long: 31d 26 min 13.6 sec E",
                  "25d 05 min 48.8 sec S, 31d 26 min 13.6 sec E", "25°05m 48.8 sec S, 31°26m 13.6 sec E",
                  "25d 05m 48.8 sec S, 31d 26m 13.6 sec E", "25 05 m 48.8 sec S, 31 26 m 13.6 sec E",
                  "25°05m48.8 sec S 31°26m13.6 sec E", "25° 05m 48.8sec S 31° 26m 13.6sec E", "25 05m 48.8 sec S 31 26m 13.6 sec E",
                  "25°05m 48.8 secs S, 31°26m 13.6 secs E"] {
            for near in [nil, kruger] {
                check(t, "degrees, minutes and seconds", near: near, at: GeoPoint(lat: -25.096889, lon: 31.437111))
            }
        }
        check("25d 5 min S, 31d 26 min E", "degrees and decimal minutes", at: GeoPoint(lat: -25.083333, lon: 31.433333))
        check("N 40d 41 min W 74d 2 min", "degrees and decimal minutes", at: GeoPoint(lat: 40.683333, lon: -74.033333))
        for t in ["N40d 41.352min W74d 2.670min", "40d 41.352 min N, 74d 2.670 min W"] {
            check(t, "degrees and decimal minutes", at: newYork)
        }
        for t in ["40d 41 min 21.1 sec N, 74d 2 min 40.2 sec W", "40d 41m 21.1 sec N 74d 2m 40.2 sec W"] {
            check(t, "degrees, minutes and seconds", at: GeoPoint(lat: 40.689194, lon: -74.0445))
        }
        // Elsewhere still a time.
        XCTAssertNil(CoordinateParser.parse("Lions 10 min S of Satara"))
        check("-25.0969, 31.4371 seen 5 min ago", "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
    }

    func testSecondsMarkedWithAnS() {
        // An "s" after the seconds, with the letters in front, a minus sign, or a space before the letter after it.
        for t in ["S 25°05'48.815s E 31°26'13.636s", "-25°05'48.815s, 31°26'13.636s", "25°05'48.815s S, 31°26'13.636s E",
                  "25°05'48.815s S 31°26'13.636s E", "S 25°05′48.815s E 31°26′13.636s", "S 25º05'48.815s E 31º26'13.636s",
                  "S 25d05m48.815s E 31d26m13.636s", "S 25 deg 05 min 48.815s E 31 deg 26 min 13.636s",
                  "S 25°05min48.815s E 31°26min13.636s", "-25d05m48.815s, 31d26m13.636s", "25°05'48.815sS 31°26'13.636sE"] {
            check(t, "degrees, minutes and seconds")
        }
        for t in ["S25°05'48.8s, E31°26'13.6s", "S25d05m48.8s E31d26m13.6s", "25 05 48.8s S 31 26 13.6s E",
                  "25°05'48.8S E 031°26'13.6"] {
            check(t, "degrees, minutes and seconds", at: GeoPoint(lat: -25.096889, lon: 31.437111))
        }
        // Where nothing else says the hemispheres, an "s" may as well be south.
        for t in ["25 05 48S 31 26 13S", "25°05'48.815s, 31°26'13.636s"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }

    func testDegreesWithTheirLetterRightAfterThem() {
        for t in ["25.096893 degS 31.437121 degE", "25.096893 DegS, 31.437121 DegE", "25.096893 degreesS 31.437121 degreesE",
                  "Lat 25.096893 degS Lon 31.437121 degE", "25.096893degS 31.437121degE"] {
            check(t, "decimal degrees")
        }
        check("33.9249 degS 18.4241 degW", "decimal degrees", at: GeoPoint(lat: -33.9249, lon: -18.4241))
    }

    func testDegreesAndMinutesOnEachSideOfASlash() {
        // A letter on each side: the two halves of one position, not two read on their own.
        for t in ["S25 05.8136 / E31 26.2273", "S 25 05.8136 / E 31 26.2273", "25 05.8136 S / 31 26.2273 E",
                  "25 05.8136S / 31 26.2273E", "S25 05.8136 / E31 26.2273 (Satara)"] {
            check(t, "degrees and decimal minutes", within: 2)
        }
        check("S24 59.400 / E31 35.400", "degrees and decimal minutes", at: GeoPoint(lat: -24.99, lon: 31.59))
    }

    func testADirectionAfterAPairWithSigns() {
        // Where an animal is heading, or a direction from a place, after a pair written with a minus sign or after a heading:
        // not the position's letter.
        let p = GeoPoint(lat: -24.996931, lon: 31.592312)
        for t in ["Wild dogs at -24.996931, 31.592312 heading W towards Skukuza", "-24.996931, 31.592312 heading W",
                  "-24.996931, 31.592312, Heading 270° W", "-24.996931 31.592312 W 270°", "-24.996931, 31.592312 W of Skukuza",
                  "-24.996931, 31.592312 (W of the bridge)", "-24.996931, 31.592312 heading west", "-24.996931, 31.592312 Bearing W",
                  "-24.996931, 31.592312 heading N", "Leopard at -24.996931, 31.592312, heading S on H1-2",
                  "-24.996931, 31.592312, Heading 180° S", "-24.996931, 31.592312 heading south", "-24.996931, 31.592312 moving W"] {
            for near in [nil, kruger] {
                check(t, "decimal degrees", near: near, at: p)
            }
        }
        check("24.996931, 31.592312, Heading 180° S", "decimal degrees", at: GeoPoint(lat: 24.996931, lon: 31.592312))
        check("S24 59.816 E31 35.539 heading S", "degrees and decimal minutes", at: gps)
        check("40.6892, -74.0445 heading S", "decimal degrees", at: newYork)
    }

    func testPositionsBeforeOfOrFrom() {
        // A position with its letters, then "from Pieter" or "van Jaco": not a distance with its direction.
        for t in ["Lions 24.99 S 31.59 E from Pieter", "Leeus 24.99 S 31.59 O van Skukuza", "S 24.99 E 31.59 of Satara",
                  "Lions at S24.99 E31.59 of the road", "Lions 24.99 S 31.59 E from camp 3 km", "Lions 24.99S 31.59E from Pieter"] {
            check(t, at: GeoPoint(lat: -24.99, lon: 31.59))
        }
        check("Wild dogs 25.10 S 31.44 E van Jaco", "decimal degrees", at: GeoPoint(lat: -25.1, lon: 31.44))
        for t in ["Leeus 24 59.82 S 31 35.54 O van Jaco", "Lions 24 59.82 S 31 35.54 E from Skukuza"] {
            check(t, at: GeoPoint(lat: -24.997, lon: 31.592333))
        }
        check("Lions 24 59.8 S 31 35.5 E from Pieter", "degrees and decimal minutes", at: GeoPoint(lat: -24.996667, lon: 31.591667))
        // Distances with their direction still aren't positions.
        for t in ["Lions 2.5 S of Satara, leopard 1.5 E of Satara", "Lions 2.25 N of Satara, leopard 3.75 E of Satara",
                  "Lions 2.5 N 3.5 E of Satara"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testAPhotosDetails() {
        // exiftool's lines, with the hemisphere on a line of its own before or after the value.
        let p = GeoPoint(lat: -24.996931, lon: 31.592311)
        for t in ["GPS Version ID                  : 2.2.0.0\nGPS Latitude Ref                : South\n"
                    + "GPS Latitude                    : 24 deg 59' 48.95\"\nGPS Longitude Ref               : East\n"
                    + "GPS Longitude                   : 31 deg 35' 32.32\"",
                  "Latitude Ref: South\nLatitude: 24° 59' 48.95\"\nLongitude Ref: East\nLongitude: 31° 35' 32.32\"",
                  "[GPS] GPS Latitude Ref : South\n[GPS] GPS Latitude : 24 deg 59' 48.95\"\n[GPS] GPS Longitude Ref : East\n"
                    + "[GPS] GPS Longitude : 31 deg 35' 32.32\"",
                  "GPSLatitudeRef: S\nGPSLatitude: 24 deg 59' 48.95\"\nGPSLongitudeRef: E\nGPSLongitude: 31 deg 35' 32.32\"",
                  "GPS Latitude : 24 deg 59' 48.95\"\nGPS Latitude Ref : South\nGPS Longitude : 31 deg 35' 32.32\"\nGPS Longitude Ref : East",
                  "GPS Latitude Ref: S\nGPS Latitude: 24; 59; 48.95\nGPS Longitude Ref: E\nGPS Longitude: 31; 35; 32.32"] {
            check(t, "degrees, minutes and seconds", at: p)
        }
        check("GPS Latitude Ref : South\nGPS Latitude : 24.9969305555556\nGPS Longitude Ref : East\nGPS Longitude : 31.5923111111111",
              "decimal degrees", at: p)
        check("GPS Latitude Ref : North\nGPS Latitude : 40 deg 41' 21.12\"\nGPS Longitude Ref : West\nGPS Longitude : 74 deg 2' 40.20\"",
              "degrees, minutes and seconds", at: newYork)
        // Windows: degrees, minutes and seconds with semicolons, and no hemisphere.
        for t in ["Latitude\t24; 59; 48.95\nLongitude\t31; 35; 32.32",
                  "Latitude 24; 59; 48.9500000000006\nLongitude 31; 35; 32.3200000000006\nAltitude 285.4"] {
            check(t, "degrees, minutes and seconds", at: GeoPoint(lat: 24.996931, lon: 31.592311))
        }
        XCTAssertNil(CoordinateParser.parse("Latitude\t24; 59\nLongitude\t31; 35"))
    }

    func testBothLettersTogether() {
        // The halves' letters written together before or after the numbers, in the halves' order.
        let p = GeoPoint(lat: -24.99693, lon: 31.59232)
        for t in ["GPS (S/E): 24.99693, 31.59232", "Coords (S, E): 24.99693 / 31.59232", "GPS: S/E 24.99693 31.59232",
                  "24.99693 / 31.59232 (S/E)", "24.99693, 31.59232 (S, E)", "Sighting at 24.99693, 31.59232 S/E",
                  "Lat/Lon (S/E): 24.99693, 31.59232", "24.99693 S/E 31.59232", "S/E: -24.99693, 31.59232"] {
            check(t, "decimal degrees", at: p)
        }
        check("N/W: 40.6892, 74.0445", "decimal degrees", at: newYork)
        check("S/E 24 59.816 31 35.539", "degrees and decimal minutes", at: gps)
        // Not with a minus sign that says otherwise, nor a pair read on its own from a text that only as a whole says them.
        for t in ["S/E: 24.99693, -31.59232", "Lions (S/E): 24.99693, 31.59232, 3 males"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }

    func testWindsAndSpeeds() {
        // A wind's direction and speed (in knots, Beaufort, mph or none) isn't a position, nor is it beside one.
        for t in ["Wind S 10 kt, later E 15 kt", "Wind S 15-20 kts, becoming E 25 kts", "Wind: S 15 kt, E 10 kt later",
                  "Breeze from S 12 knots then E 18 knots", "Wind S 4 Bft, E 5 Bft later", "Today: Wind S 24, tomorrow E 31",
                  "Windrigting S 24 knope, later O 31 knope", "Durban: wind S 25 kt; Richards Bay: wind E 30 kt",
                  "Wind S 10 mph, later E 15 mph"] {
            for near in [nil, kruger] {
                XCTAssertNil(CoordinateParser.parse(t, near: near), t)
            }
        }
        check("Lions at S24 59.815 E31 35.539. Wind S 10 kt, later E 15 kt", "degrees and decimal minutes", at: ddm)
    }

    // Inputs checked an eighth time, by family. Near Satara: 24° 22.872' S, 31° 50.736' E.
    private let satara = GeoPoint(lat: -24.3812, lon: 31.8456), signed = GeoPoint(lat: -24.996931, lon: 31.592312)

    func testADirectionAsAThirdLetter() {
        // A direction from a place after a position with its letters in front, or of travel before one with its letters
        // after its numbers: not one of its letters, whatever else is around it.
        for t in ["Lions S24 59 48.9 E31 35 32.3 N of camp", "Lions S24 59 48.9 E31 35 32.3 N of camp, 3 males",
                  "S24 59 48.9 E31 35 32.3 N of Skukuza 06:45", "Lions S24 59 48.9 E31 35 32.3 S of Satara",
                  "Leeus S24 59 48.9 E31 35 32.3 noord van Skukuza", "Lions S24 59 48.9 E31 35 32.3 north of camp",
                  "S24 59 48.9 E31 35 32.3\nN of camp\nwind E 15 kts", "Lions heading W 24 59 48.9 S 31 35 32.3 E",
                  "Lions walking W 24°59'48.9\"S 31°35'32.3\"E", "Lions walking N 24°59'48.9\"S 31°35'32.3\"E"] {
            for near in [nil, kruger] {
                check(t, "degrees, minutes and seconds", near: near, at: dms)
            }
        }
        check("S25 07 10.7 E31 55 03.4 S of the dam", "degrees, minutes and seconds", at: GeoPoint(lat: -25.1196389, lon: 31.9176111))
        check("Lions walking W 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds", at: GeoPoint(lat: -24.3811944, lon: 31.8456111))
        for t in ["Leopard on H4-1 km 3: S24 59.816 E31 35.539 N side", "Wind S 10 kt. Lions S24 59.816 E31 35.539 N of the dam",
                  "Lions S24 59.816 E31 35.539 N of the dam", "Lions walking W 24 59.816 S 31 35.539 E",
                  "Wild dogs moving E 24 59.816 S 31 35.539 E", "Leeus stap W 24 59.816 S 31 35.539 O",
                  "Leeus loop O 24 59.816 S 31 35.539 O", "Lions walking W 24°59.816'S 31°35.539'E",
                  "Wild dogs moving N 24 59.816 S 31 35.539 E", "Leopard going S 24 59.816 S 31 35.539 E"] {
            for near in [nil, kruger] {
                check(t, near: near, at: gps)
            }
        }
        check("Lions on H1-3 km 14 S25 07.178 E31 55.057 N of camp", "degrees and decimal minutes",
              at: GeoPoint(lat: -25.1196333, lon: 31.9176167))
        check("Lions S24 59.8 E31 35.5 N of camp", "degrees and decimal minutes", at: GeoPoint(lat: -24.9966667, lon: 31.5916667))
        for t in ["Ellies crossing, moving E 24 22.872 S 31 50.736 E", "Lions 2 km W 24 22.872 S 31 50.736 E",
                  "Lions W 24 22.872 S 31 50.736 E", "Lions walking W\n24 22.872 S 31 50.736 E", "Leeus beweeg W 24 22.872 S 31 50.736 O"] {
            check(t, at: satara)
        }
        for t in ["Lions walking W 24.99693 S 31.59231 E", "Wild dogs moving E 24.99693 S, 31.59231 E", "Heading E 24.99693 S, 31.59231 E"] {
            check(t, "decimal degrees", at: typed)
        }
        // So too in a long chat log, where only the pairs in it are read.
        let log = String(repeating: "[12:31, 27/10/2026] Jaco: Leaving at 5 p.m., 2 x 2 litres of water, 3 x 1 kg of ice\n", count: 20)
        check(log + "Lions walking W 24 59.816 S 31 35.539 E", "degrees and decimal minutes", at: gps)
        check(log + "Lions S24 59.816 E31 35.539 N of the dam", "degrees and decimal minutes", at: gps)
        // A letter glued to the seconds is their mark, not a direction after the position.
        check("S 25°05'48.815\" E 31°26'13.636s", "degrees, minutes and seconds")
    }

    func testAHeadingOrAWindBeforeAPosition() {
        // A heading that is only a direction, with a stop or a colon after it, doesn't label the position's first number;
        // with its angle after it, that is its value.
        for t in ["Lions heading W: 24 59.816 S 31 35.539 E", "Heading W. 24 59.816 S 31 35.539 E", "Lions heading W. -24 59.816, 31 35.539",
                  "Bearing W: 24 59.816 S 31 35.539 E", "Course W. 24 59.816 S 31 35.539 E"] {
            check(t, "degrees and decimal minutes", at: gps)
        }
        check("Wild dogs heading W. 24 59 48.9 S 31 35 32.3 E", "degrees, minutes and seconds", at: dms)
        check("Heading W. 24 22.872 S 31 50.736 E", "degrees and decimal minutes", at: satara)
        check("Heading W. -25.096893, 31.437121. 45° bearing", "decimal degrees")
        check("Lions -24 22.872, 31 50.736 heading W: 45° bearing", "degrees and decimal minutes", at: satara)
        check("Lions -24.3812, 31.8456, course S: 3 males", "decimal degrees", at: satara)
        // A minus sign right after a bearing's degrees is the position's.
        for t in ["Bearing 270° -25.096893, 31.437121", "Bearing 270° -25.096893, 31.437121, wind E 15 kts"] {
            check(t, "decimal degrees")
        }
        // A letter glued to a number with minutes, or followed by a number only a coordinate has, is a position's, not
        // the wind's.
        for t in ["Lions out of the wind S24 59.816 E31 35.539", "Strong wind S24 59.816 E31 35.539", "Lots of wind S24 59.816 E31 35.539",
                  "Lions out of the wind S24°59.816' E31°35.539'", "Leeus uit die wind S24 59.816 O31 35.539",
                  "Breeze S24 59.816 E31 35.539"] {
            for near in [nil, kruger] {
                check(t, near: near, at: gps)
            }
        }
        for t in ["Lions out of the wind S 24.99693 E 31.59231", "Wind: S 24.99693 E 31.59231"] {
            check(t, "decimal degrees", at: typed)
        }
    }

    func testDirectionsNextToAPairWithSigns() {
        // A direction from a place or of travel, in one letter or two, before or after a pair written with a minus sign,
        // doesn't change a sign; a distance with two letters and "of" isn't their letters either.
        for t in ["Lions 3 S/W of Satara -24.99693, 31.59231", "Lions 3 N/E of Satara -24.99693, 31.59231",
                  "Lions 3 S/E of Satara -24.99693, 31.59231", "Lions 2 S/W of the bridge at -24.99693 31.59231",
                  "Leopard 5 S, W of camp -24.99693, 31.59231", "Lions -24.99693, 31.59231, 3 S/W of Satara",
                  "Lions 1 S/W of Skukuza: -24.99693, 31.59231, 06:45", "Leeus 3 S/W van Satara -24.99693, 31.59231",
                  "-24.99693, 31.59231 S/W", "-24.99693, 31.59231 (S/W of camp)", "Lions -24.99693, 31.59231 S/W of Satara"] {
            for near in [nil, kruger] {
                check(t, "decimal degrees", near: near, at: typed)
            }
        }
        for t in ["2 lions -24.996931, 31.592312 (S/E)", "06:45 -24.996931, 31.592312 (S/E)",
                  "[07:15, 27/10/2026] Jaco: -24.996931, 31.592312 (S/E)", "Wild dogs moving W -24.996931, 31.592312",
                  "Lions heading W -24.996931, 31.592312", "Heading W -24.996931, 31.592312", "Leeus stap W -24.996931, 31.592312",
                  "Lions walking W\n-24.996931, 31.592312", "Lions (moving W) -24.996931, 31.592312", "Rhino 2 km W -24.996931, 31.592312",
                  "Lions 500 m W -24.996931, 31.592312", "Lions moving W -24,996931; 31,592312", "Leeus beweeg O -24.996931, 31.592312",
                  "Leeus loop O -24.996931 31.592312"] {
            for near in [nil, kruger] {
                check(t, near: near, at: signed)
            }
        }
        check("Lions moving W -24°59.816', 31°35.539'", "degrees and decimal minutes", at: gps)
        check("N4 W to Nelspruit, -25.4753, 30.9694", "decimal degrees", at: GeoPoint(lat: -25.4753, lon: 30.9694))
        check("R536 W to Hazyview -25.0476 31.1295", "decimal degrees", at: GeoPoint(lat: -25.0476, lon: 31.1295))
        for t in ["S1 W -25.0012, 31.5409", "Gate 3 W -25.0012, 31.5409"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -25.0012, lon: 31.5409))
        }
        // So too in a long chat log, where only the pairs in it are read.
        let log = String(repeating: "[12:31, 27/10/2026] Jaco: Leaving at 5 p.m., 2 x 2 litres of water, 3 x 1 kg of ice\n", count: 20)
        for t in ["Wild dogs moving W -24.996931, 31.592312", "-24.996931, 31.592312 W of the dam", "-24.996931, 31.592312 (S/W)"] {
            check(log + t, "decimal degrees", at: signed)
        }
        // A W right after the last number of a half without a minus sign may as well be its hemisphere; with a minus sign
        // it says the same.
        for t in ["-33.4489 70.6693W", "-33.4489, 70.6693 W", "-34.6037, 58.3816 W", "-0.180653, 78.467834 W", "Lat -33.4489 Lon 70.6693 W",
                  "-25°05'48.8\", 31°26'13.6\"W", "-24 59.816, 31 35.539 W"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
        check("40 41.352, -74 2.670 W", "degrees and decimal minutes", at: newYork)
        // Both letters after a pair without a minus sign, read only with the whole text: not when other numbers are before it.
        for t in ["3 lions at 24.99693, 31.59232 S/E", "06:45 sighting at 24.99693, 31.59232 S/E", "06:45 GPS (S/E): 24.99693, 31.59232"] {
            XCTAssertNil(CoordinateParser.parse(t, near: kruger), t)
        }
    }

    func testAPhotosDetailsWithMoreLines() {
        // exiftool's default output: the same place as latitude and longitude lines and as a position line, with its
        // altitude and time before them.
        let pad = { (label: String) in label + String(repeating: " ", count: 32 - label.count) + ": " }
        let lines = pad("GPS Latitude") + "24 deg 59' 48.95\" S\n" + pad("GPS Longitude") + "31 deg 35' 32.32\" E\n" + pad("GPS Position")
            + "24 deg 59' 48.95\" S, 31 deg 35' 32.32\" E"
        let photo = GeoPoint(lat: -24.996931, lon: 31.592311)
        for t in [lines, pad("GPS Altitude") + "285.4 m Above Sea Level\n" + pad("GPS Date/Time") + "2026:10:28 04:45:12Z\n" + lines,
                  "Latitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E\nPosition: 24°59'48.95\" S, 31°35'32.32\" E",
                  "GPS Altitude : 285.4 m Above Sea Level\nGPS Latitude : 24 deg 59' 48.95\" S\nGPS Longitude : 31 deg 35' 32.32\" E",
                  "Altitude: 285 m\nLatitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E",
                  "Elevation 285 m\nLat 24°59'48.95\"S\nLon 31°35'32.32\"E", "Accuracy: 5 m\nLat: 24°59'48.95\" S\nLong: 31°35'32.32\" E",
                  "Altitude 285 m, Latitude 24°59'48.95\" S, Longitude 31°35'32.32\" E"] {
            check(t, "degrees, minutes and seconds", at: photo)
        }
        check("GPS Latitude : 24.996931 S\nGPS Longitude : 31.592311 E\nGPS Position : 24.996931 S, 31.592311 E", "decimal degrees",
              at: photo)
        check("Altitude: 285.4 m\nLatitude: 24° 59.816' S\nLongitude: 31° 35.539' E", "degrees and decimal minutes", at: gps)
        // Two different places so written aren't read.
        XCTAssertNil(CoordinateParser.parse("Latitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E\n"
                                            + "Position: 25°05'48.8\" S, 31°26'13.6\" E"))
        // A waypoint's or camp's number before labels with the letters after the values.
        check("Camp 14 lat 24.99693 S lon 31.59231 E", "decimal degrees", at: typed)
        for t in ["WP 12 Lat 24°59.816'S Lon 31°35.539'E", "Waypoint 12 Lat 24 59.816 S Lon 31 35.539 E"] {
            check(t, "degrees and decimal minutes", at: gps)
        }
        // A long run of spaces or tabs after a "Ref" label, and a chat log with two letters together in it, are read
        // quickly.
        let start = Date()
        for t in ["Latitude Ref" + String(repeating: " ", count: 20_000) + "x 1", "Lat ref" + String(repeating: "\t", count: 2_000) + "x 1",
                  "GPS Latitude Ref: S\nGPS Latitude" + String(repeating: " ", count: 2_000) + "x"] {
            XCTAssertNil(CoordinateParser.parse(t))
        }
        let log = String(repeating: "[12:31, 27/10/2026] Jaco: Only 4x4 on the S100 after the rain\n[12:33, 27/10/2026] Jaco: "
                         + "Leopard S/E of Satara\n", count: 150)
        check(log + "[13:01, 27/10/2026] Jaco: lions at -25.0969, 31.4371", "decimal degrees", at: GeoPoint(lat: -25.0969, lon: 31.4371))
        XCTAssertLessThan(Date().timeIntervalSince(start), 3)
    }

    func testCountsAndTemperaturesBeforeAGarminPosition() {
        // A temperature, or a count or waypoint's number, before degrees and minutes written as the other half is: not
        // their first field.
        for t in ["Hot today 32° 24 22.872 S 31 50.736 E", "Lions 32° 24 22.872 S 31 50.736 E", "Lions (32°) 24 22.872 S 31 50.736 E",
                  "Lions 18° 24 22.872 S 31 50.736 E", "Lions 32 deg 24 22.872 S 31 50.736 E", "Leeus 32 grade 24 22.872 S 31 50.736 O",
                  "Lions 3 24 22.872 S 31 50.736 E", "Pride of 6 24 22.872 S 31 50.736 E", "017 24 22.872 S 31 50.736 E",
                  "WP 017 24 22.872 S 31 50.736 E 285 m", "#3 24 22.872 S 31 50.736 E", "No. 3 24 22.872 S 31 50.736 E",
                  "Temp 32 24 22.872 S 31 50.736 E"] {
            for near in [nil, kruger] {
                check(t, near: near, at: satara)
            }
        }
        check("Lions 32° 24.3812 S 31.8456 E", "decimal degrees", at: satara)
        // Written otherwise, they are.
        check("24° 22 52.3 S 31 50.736 E", "degrees, minutes and seconds", at: GeoPoint(lat: -24.3811944, lon: 31.8456))
        XCTAssertNil(CoordinateParser.parse("Lions 3 24 22.8 S 31 50.7 E"))
    }

    func testUnitsWithALetterRightAfterThem() {
        // Seconds or minutes in words with the direction right after them; "secS" is south when another unit has its own.
        let photo = GeoPoint(lat: -24.9969306, lon: 31.5923111)
        for t in ["24°59min48.95secS, 31°35min32.32secE", "24d59min48.95 secS, 31d35min32.32 secE",
                  "24°59min48.95secondsS 31°35min32.32secondsE", "24°59min48.95 sS 31°35min32.32 sE", "24°59'48.95secS, 31°35'32.32secE"] {
            check(t, "degrees, minutes and seconds", at: photo)
        }
        check("24d59.816 minS, 31d35.539 minE", "degrees and decimal minutes", at: gps)
        check("40°41min21.1secN 74°02min40.2secW", "degrees, minutes and seconds", at: GeoPoint(lat: 40.6891944, lon: -74.0445))
        check("25°05min48.8secS 31°26min13.6secE", "degrees, minutes and seconds", at: GeoPoint(lat: -25.0968889, lon: 31.4371111))
        // "de" isn't a unit with its letter.
        for t in ["Lat: -33.4489 Lon: -70.6693 de Santiago", "Latitud: -33.4489 Longitud: -70.6693 de Santiago 06:45"] {
            check(t, "decimal degrees", at: GeoPoint(lat: -33.4489, lon: -70.6693))
        }
        // The degree sign written after the minutes: one coordinate, not a position.
        for t in ["25 05.8136degS", "25° 05.8136 degS", "25 05.8136 deg S"] {
            XCTAssertNil(CoordinateParser.parse(t), t)
        }
    }
}

final class ScaleBarTests: XCTestCase {
    func testLongestRoundDistanceThatFits() {
        func check(_ metersPerPoint: Double, _ meters: Double, _ label: String, line: UInt = #line) {
            guard let s = ScaleBar.choose(metersPerPoint: metersPerPoint, maxWidth: 110) else {
                return XCTFail("No scale", line: line)
            }
            XCTAssertEqual(s.meters, meters, accuracy: 1e-9, line: line)
            XCTAssertEqual(s.width, meters / metersPerPoint, accuracy: 1e-9, line: line)
            XCTAssertLessThanOrEqual(s.width, 110 + 1e-9, line: line)
            XCTAssertGreaterThanOrEqual(s.width, 110 * 0.4 - 1e-9, line: line) // 2 of 5, the biggest step down
            XCTAssertEqual(ScaleBar.label(s.meters), label, line: line)
        }
        check(300.0 / 393, 50, "50 m")      // the screen 300 m across
        check(1, 100, "100 m")              // exactly 100 m in 100 points; 110 m doesn't round
        check(1000.0 / 110, 1000, "1 km")   // exactly the bar's length
        check(45, 2000, "2 km")             // 4950 m fit
        check(0.004, 0.2, "20 cm")          // closest zoom
        check(90_000, 5_000_000, "5000 km") // the world
    }

    func testNoScaleWithoutAZoom() {
        XCTAssertNil(ScaleBar.choose(metersPerPoint: 0, maxWidth: 110))
        XCTAssertNil(ScaleBar.choose(metersPerPoint: .nan, maxWidth: 110))
        XCTAssertNil(ScaleBar.choose(metersPerPoint: .infinity, maxWidth: 110))
    }
}
