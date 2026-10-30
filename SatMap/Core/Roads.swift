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
    /// doesn't fetch every road between them). Each box is the part grown by its buffer and the corridor.
    static func overpassQuery(for area: CoverageShape) -> String? {
        let margin = area.bufferMeters + corridorMeters
        let polygonMargin = area.polygonBuffer + corridorMeters
        var boxes: [GeoBounds] = []
        for ring in area.polygons where ring.count >= 3 {
            if let b = GeoBounds(points: ring) { boxes.append(b.expanded(byMeters: polygonMargin)) }
        }
        for line in area.lines where !line.isEmpty {
            if let b = GeoBounds(points: line) { boxes.append(b.expanded(byMeters: margin)) }
        }
        for p in area.points {
            boxes.append(GeoBounds(minLat: p.lat, minLon: p.lon, maxLat: p.lat, maxLon: p.lon).expanded(byMeters: margin))
        }
        if boxes.count > maxQueryBoxes, let all = area.geometryBounds {
            boxes = [all.expanded(byMeters: max(margin, polygonMargin))]
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
        return parts(of: roads.filter { $0.count >= 2 }, step: 10) { coverage.contains(TileID.containing($0, z: z)) }
    }

    /// The parts of the lines where `keep` holds, checked every `step` metres or less and simplified to within 1 m.
    /// A one-point line is kept whole or not at all.
    private static func parts(of lines: [[GeoPoint]], step: Double, keep: (GeoPoint) -> Bool) -> [[GeoPoint]] {
        func lerp(_ a: GeoPoint, _ b: GeoPoint, _ t: Double) -> GeoPoint {
            GeoPoint(lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t)
        }

        var out: [[GeoPoint]] = []
        for line in lines where !line.isEmpty {
            if line.count == 1 {
                if keep(line[0]) { out.append(line) }
                continue
            }
            var run: [GeoPoint] = []
            func flush() {
                if run.count >= 2 { out.append(simplify(run, toleranceMeters: 1)) }
                run = []
            }
            for i in 0..<(line.count - 1) {
                let a = line[i], b = line[i + 1]
                let n = max(1, Int((Geo.distance(a, b) / step).rounded(.up)))
                for k in 0..<n {
                    if keep(lerp(a, b, (Double(k) + 0.5) / Double(n))) {
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

    /// The area to download imagery for: every road with `corridorMeters` on each side (a one-point line: that far
    /// around its point, as a file's dots are saved along roads).
    static func corridor(_ roads: [[GeoPoint]]) -> CoverageShape {
        CoverageShape(lines: roads, bufferMeters: corridorMeters)
    }

    /// Total length in metres.
    static func length(_ roads: [[GeoPoint]]) -> Double {
        roads.reduce(0) { total, line in
            zip(line, line.dropFirst()).reduce(total) { $0 + Geo.distance($1.0, $1.1) }
        }
    }

    /// The recorded track as lines (a new line wherever the dotted line breaks), simplified to within 2 m, for
    /// downloading imagery along where you have been. A lone point stays a one-point line (its corridor is a square).
    static func trackLines(_ track: [TrackPoint]) -> [[GeoPoint]] {
        var lines: [[GeoPoint]] = []
        var current: [GeoPoint] = []
        for p in track {
            if p.startsSegment, !current.isEmpty {
                lines.append(current)
                current = []
            }
            current.append(p.geo)
        }
        if !current.isEmpty { lines.append(current) }
        return lines.map { simplify($0, toleranceMeters: 2) }
    }

    /// How close a part of an earlier track must be to the latest one to count as the same path (both lie within 2 m
    /// of where you were, after simplifying).
    static let sameTrackMeters = 5.0

    /// The lines of the area along your track after an update: the latest track plus the parts of the earlier lines
    /// away from it (the track may have been cleared since), so imagery saved before stays part of the area.
    static func mergeTrack(previous: [[GeoPoint]], latest: [[GeoPoint]]) -> [[GeoPoint]] {
        guard !previous.isEmpty else { return latest }
        guard !latest.isEmpty else { return previous }
        return latest + newParts(of: previous, comparedWith: latest)
    }

    /// The parts of `lines` farther than `sameTrackMeters` from every one of `others` (checked every 5 m or less).
    static func newParts(of lines: [[GeoPoint]], comparedWith others: [[GeoPoint]]) -> [[GeoPoint]] {
        guard !others.isEmpty else { return lines }
        let near = NearLines(others, within: sameTrackMeters)
        return parts(of: lines, step: 5) { !near.contains($0) }
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

/// Whether a point is within some metres of a set of lines, quick for long tracks: the segments are filed under the
/// zoom-16 tiles (about 600 m) they come near.
private struct NearLines {
    private let meters: Double
    private var cells: [Int64: [(GeoPoint, GeoPoint)]] = [:]
    private static let z = 16

    init(_ lines: [[GeoPoint]], within meters: Double) {
        self.meters = meters
        for line in lines where !line.isEmpty {
            let segments = line.count == 1 ? [(line[0], line[0])] : Array(zip(line, line.dropFirst()))
            for (a, b) in segments {
                let dLat = meters / 110_574
                let dLon = meters / (111_320 * max(0.01, cos(max(abs(a.lat), abs(b.lat)) * .pi / 180)))
                let nw = TileID.containing(GeoPoint(lat: max(a.lat, b.lat) + dLat, lon: min(a.lon, b.lon) - dLon), z: Self.z)
                let se = TileID.containing(GeoPoint(lat: min(a.lat, b.lat) - dLat, lon: max(a.lon, b.lon) + dLon), z: Self.z)
                for y in nw.y...se.y {
                    for x in nw.x...se.x { cells[Self.key(x, y), default: []].append((a, b)) }
                }
            }
        }
    }

    private static func key(_ x: Int, _ y: Int) -> Int64 { Int64(x) << 32 | Int64(y) }

    func contains(_ p: GeoPoint) -> Bool {
        let t = TileID.containing(p, z: Self.z)
        guard let segments = cells[Self.key(t.x, t.y)] else { return false }
        // Local metres around p.
        let mx = 111_320 * cos(p.lat * .pi / 180), my = 110_574.0
        for (a, b) in segments {
            let ax = (a.lon - p.lon) * mx, ay = (a.lat - p.lat) * my
            let bx = (b.lon - p.lon) * mx, by = (b.lat - p.lat) * my
            let dx = bx - ax, dy = by - ay
            let len2 = dx * dx + dy * dy
            let t = len2 > 0 ? min(1, max(0, -(ax * dx + ay * dy) / len2)) : 0
            let cx = ax + t * dx, cy = ay + t * dy
            if cx * cx + cy * cy <= meters * meters { return true }
        }
        return false
    }
}

/// Google's encoded polyline format (5 decimals, about 1 m): a compact text form for road lines.
enum Polyline {
    static func encode(_ points: [GeoPoint]) -> String {
        var out = ""
        var lastLat = 0, lastLon = 0
        for p in points {
            let lat = Int((p.lat * 1e5).rounded()), lon = Int((p.lon * 1e5).rounded())
            for delta in [lat - lastLat, lon - lastLon] {
                var v = delta < 0 ? ~(delta << 1) : delta << 1
                while v >= 0x20 {
                    out.unicodeScalars.append(Unicode.Scalar(UInt8((0x20 | (v & 0x1f)) + 63)))
                    v >>= 5
                }
                out.unicodeScalars.append(Unicode.Scalar(UInt8(v + 63)))
            }
            lastLat = lat
            lastLon = lon
        }
        return out
    }

    /// Nil if the text isn't a valid polyline.
    static func decode(_ text: String) -> [GeoPoint]? {
        let bytes = Array(text.utf8)
        var i = 0
        func next() -> Int? {
            var result = 0, shift = 0
            while i < bytes.count {
                let b = Int(bytes[i]) - 63
                i += 1
                guard b >= 0, b < 64, shift < 60 else { return nil }
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 { return (result & 1) != 0 ? ~(result >> 1) : result >> 1 }
            }
            return nil
        }
        var points: [GeoPoint] = []
        var lat = 0, lon = 0
        while i < bytes.count {
            guard let dLat = next(), let dLon = next() else { return nil }
            lat += dLat
            lon += dLon
            points.append(GeoPoint(lat: Double(lat) / 1e5, lon: Double(lon) / 1e5))
        }
        return points
    }
}

/// Roads shipped with the app for a place (e.g. every road in Kruger National Park), with the place's boundary.
/// Stored as JSON: {"name", "note", "boundary": [polyline], "roads": [polyline]}.
struct RoadPreset {
    let name: String
    /// Where the roads come from and when, shown to the user.
    let note: String
    /// Outer rings of the place.
    let boundary: [[GeoPoint]]
    let roads: [[GeoPoint]]

    struct Invalid: Error {}

    private struct File: Decodable {
        let name: String
        let note: String
        let boundary: [String]
        let roads: [String]
    }

    init(json: Data) throws {
        let f = try JSONDecoder().decode(File.self, from: json)
        let boundary = f.boundary.compactMap(Polyline.decode)
        let roads = f.roads.compactMap(Polyline.decode)
        guard boundary.count == f.boundary.count, roads.count == f.roads.count, !boundary.isEmpty else { throw Invalid() }
        name = f.name
        note = f.note
        self.boundary = boundary
        self.roads = roads
    }
}
