import Foundation

/// Roads and tracks from OpenStreetMap (Overpass API), for offline areas that keep satellite imagery only
/// along the roads.
enum Roads {
    /// Imagery kept on each side of a road's centre line, in metres.
    static let corridorMeters = 15.0

    /// OSM `highway` types a vehicle drives on: public roads of every class, service roads (yards, driveways)
    /// and tracks (farm and forest roads, mostly gravel or dirt). Footpaths and cycle paths are left out.
    static let highwayTypes = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
                               "living_street", "service", "track", "road",
                               "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"]

    /// More boxes than this are replaced by one box around the whole area.
    private static let maxQueryBoxes = 40

    /// Overpass QL for the roads in boxes around each part of the area (so a KML file with blocks far apart
    /// doesn't fetch every road between them).
    static func overpassQuery(for area: CoverageShape) -> String? {
        let margin = area.bufferMeters + corridorMeters
        var boxes: [GeoBounds] = []
        for ring in area.polygons where ring.count >= 3 {
            if let b = GeoBounds(points: ring) { boxes.append(b.expanded(byMeters: margin)) }
        }
        for line in area.lines where !line.isEmpty {
            if let b = GeoBounds(points: line) { boxes.append(b.expanded(byMeters: margin)) }
        }
        for p in area.points {
            boxes.append(GeoBounds(minLat: p.lat, minLon: p.lon, maxLat: p.lat, maxLon: p.lon).expanded(byMeters: margin))
        }
        if boxes.count > maxQueryBoxes, let all = area.geometryBounds {
            boxes = [all.expanded(byMeters: margin)]
        }
        guard !boxes.isEmpty else { return nil }
        let filter = "way[\"highway\"~\"^(\(highwayTypes.joined(separator: "|")))$\"]"
        let parts = boxes.map { b in
            filter + String(format: "(%.6f,%.6f,%.6f,%.6f);", b.minLat, b.minLon, b.maxLat, b.maxLon)
        }
        return "[out:json][timeout:60];(\(parts.joined()));out geom;"
    }

    struct ServerError: Error {
        let message: String
    }

    private struct Response: Decodable {
        struct Element: Decodable {
            let type: String
            let geometry: [Node?]?
        }

        struct Node: Decodable {
            let lat: Double
            let lon: Double
        }

        let elements: [Element]
        /// Set when the server stopped early (timeout, out of memory); the elements are then incomplete.
        let remark: String?
    }

    /// Road centre lines from an Overpass `out geom` JSON response.
    static func parse(_ data: Data) throws -> [[GeoPoint]] {
        let r = try JSONDecoder().decode(Response.self, from: data)
        if let remark = r.remark, remark.lowercased().contains("error") {
            throw ServerError(message: remark)
        }
        var lines: [[GeoPoint]] = []
        for e in r.elements where e.type == "way" {
            var run: [GeoPoint] = []
            for node in e.geometry ?? [] {
                if let n = node {
                    run.append(GeoPoint(lat: n.lat, lon: n.lon))
                } else {
                    if run.count >= 2 { lines.append(run) }
                    run = []
                }
            }
            if run.count >= 2 { lines.append(run) }
        }
        return lines
    }

    /// The parts of the roads inside the area: each road is checked every 10 m or less against the area's
    /// tiles at zoom 21 (about 15 m), and the result is simplified to within 1 m.
    static func clip(_ roads: [[GeoPoint]], to area: CoverageShape) -> [[GeoPoint]] {
        let z = 21
        let coverage = TileCoverage(shape: area, zoom: z)
        func inside(_ p: GeoPoint) -> Bool { coverage.contains(TileID.containing(p, z: z)) }
        func lerp(_ a: GeoPoint, _ b: GeoPoint, _ t: Double) -> GeoPoint {
            GeoPoint(lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t)
        }

        var out: [[GeoPoint]] = []
        for road in roads where road.count >= 2 {
            var run: [GeoPoint] = []
            func flush() {
                if run.count >= 2 { out.append(simplify(run, toleranceMeters: 1)) }
                run = []
            }
            for i in 0..<(road.count - 1) {
                let a = road[i], b = road[i + 1]
                let n = max(1, Int((Geo.distance(a, b) / 10).rounded(.up)))
                for k in 0..<n {
                    if inside(lerp(a, b, (Double(k) + 0.5) / Double(n))) {
                        if run.isEmpty { run.append(lerp(a, b, Double(k) / Double(n))) }
                        run.append(lerp(a, b, Double(k + 1) / Double(n)))
                    } else {
                        flush()
                    }
                }
            }
            flush()
        }
        return out
    }

    /// The area to download imagery for: every road with `corridorMeters` on each side.
    static func corridor(_ roads: [[GeoPoint]]) -> CoverageShape {
        CoverageShape(lines: roads, bufferMeters: corridorMeters)
    }

    /// Total length in metres.
    static func length(_ roads: [[GeoPoint]]) -> Double {
        roads.reduce(0) { total, line in
            zip(line, line.dropFirst()).reduce(total) { $0 + Geo.distance($1.0, $1.1) }
        }
    }

    /// Douglas–Peucker in local metres around the line's first point.
    private static func simplify(_ line: [GeoPoint], toleranceMeters: Double) -> [GeoPoint] {
        guard line.count > 2 else { return line }
        let o = line[0]
        let mx = 111_320.0 * cos(o.lat * .pi / 180), my = 110_574.0
        let planar = line.map { (x: ($0.lon - o.lon) * mx, y: ($0.lat - o.lat) * my) }
        return Simplify.douglasPeucker(planar, tolerance: toleranceMeters).map {
            GeoPoint(lat: o.lat + $0.y / my, lon: o.lon + $0.x / mx)
        }
    }
}
