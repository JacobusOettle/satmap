import Foundation

/// A WGS84 coordinate. Kept independent of CoreLocation so the core logic is portable and testable.
struct GeoPoint: Codable, Hashable {
    var lat: Double
    var lon: Double

    init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}

/// Spherical Web Mercator in normalized "world" units: x in [0, 1) west→east, y in [0, 1) north→south.
/// Multiply by 2^z to get fractional XYZ tile coordinates at zoom z.
enum WebMercator {
    static let maxLatitude = 85.051_128_779_806_59

    static func world(_ p: GeoPoint) -> (x: Double, y: Double) {
        let lat = min(max(p.lat, -maxLatitude), maxLatitude)
        let x = (p.lon + 180.0) / 360.0
        let s = sin(lat * .pi / 180.0)
        let y = 0.5 - log((1 + s) / (1 - s)) / (4 * .pi)
        return (x, y)
    }

    static func geo(worldX: Double, worldY: Double) -> GeoPoint {
        let lon = worldX * 360.0 - 180.0
        let n = .pi - 2.0 * .pi * worldY
        let lat = 180.0 / .pi * atan(0.5 * (exp(n) - exp(-n)))
        return GeoPoint(lat: lat, lon: lon)
    }
}

/// An XYZ ("slippy map") tile address. y counts from the top (north), as used by Esri, OSM and MapKit.
struct TileID: Hashable, Codable, CustomStringConvertible {
    let z: Int
    let x: Int
    let y: Int

    init(z: Int, x: Int, y: Int) {
        self.z = z
        self.x = x
        self.y = y
    }

    var description: String { "\(z)/\(x)/\(y)" }

    var parent: TileID? {
        z > 0 ? TileID(z: z - 1, x: x >> 1, y: y >> 1) : nil
    }

    /// The ancestor `levels` zoom levels up (levels >= 0).
    func ancestor(levels: Int) -> TileID {
        TileID(z: z - levels, x: x >> levels, y: y >> levels)
    }

    /// World-unit bounds of this tile.
    var worldBounds: (minX: Double, minY: Double, maxX: Double, maxY: Double) {
        let n = Double(1 << z)
        return (Double(x) / n, Double(y) / n, Double(x + 1) / n, Double(y + 1) / n)
    }

    static func containing(_ p: GeoPoint, z: Int) -> TileID {
        let w = WebMercator.world(p)
        let n = 1 << z
        let x = min(max(Int(floor(w.x * Double(n))), 0), n - 1)
        let y = min(max(Int(floor(w.y * Double(n))), 0), n - 1)
        return TileID(z: z, x: x, y: y)
    }
}

enum Geo {
    /// Mean Earth radius (IUGG), metres.
    static let earthRadius = 6_371_008.8
    /// Equatorial circumference used by Web Mercator, metres.
    static let mercatorCircumference = 2 * Double.pi * 6_378_137.0

    /// Great-circle distance in metres (haversine).
    static func distance(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let φ1 = a.lat * .pi / 180, φ2 = b.lat * .pi / 180
        let dφ = φ2 - φ1
        let dλ = (b.lon - a.lon) * .pi / 180
        let h = sin(dφ / 2) * sin(dφ / 2) + cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
        return 2 * earthRadius * asin(min(1, sqrt(h)))
    }

    /// Length of the line through the points, in metres.
    static func pathLength(_ points: [GeoPoint]) -> Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + distance($1.0, $1.1) }
    }

    /// Ground metres represented by one normalized world unit at a given latitude.
    static func metersPerWorldUnit(atLatitude lat: Double) -> Double {
        mercatorCircumference * cos(lat * .pi / 180)
    }

    /// Approximate area of a closed ring in square metres (spherical excess on a sphere).
    static func ringArea(_ ring: [GeoPoint]) -> Double {
        guard ring.count >= 3 else { return 0 }
        var total = 0.0
        for i in 0..<ring.count {
            let p1 = ring[i]
            let p2 = ring[(i + 1) % ring.count]
            total += (p2.lon - p1.lon) * .pi / 180 * (2 + sin(p1.lat * .pi / 180) + sin(p2.lat * .pi / 180))
        }
        return abs(total * earthRadius * earthRadius / 2)
    }
}

/// Axis-aligned bounds in degrees.
struct GeoBounds: Codable, Hashable {
    var minLat: Double
    var minLon: Double
    var maxLat: Double
    var maxLon: Double

    init(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        self.minLat = minLat
        self.minLon = minLon
        self.maxLat = maxLat
        self.maxLon = maxLon
    }

    init?<S: Sequence>(points: S) where S.Element == GeoPoint {
        var it = points.makeIterator()
        guard let first = it.next() else { return nil }
        var b = GeoBounds(minLat: first.lat, minLon: first.lon, maxLat: first.lat, maxLon: first.lon)
        while let p = it.next() { b.include(p) }
        self = b
    }

    mutating func include(_ p: GeoPoint) {
        minLat = min(minLat, p.lat)
        maxLat = max(maxLat, p.lat)
        minLon = min(minLon, p.lon)
        maxLon = max(maxLon, p.lon)
    }

    func union(_ o: GeoBounds) -> GeoBounds {
        GeoBounds(minLat: min(minLat, o.minLat), minLon: min(minLon, o.minLon),
                  maxLat: max(maxLat, o.maxLat), maxLon: max(maxLon, o.maxLon))
    }

    var center: GeoPoint { GeoPoint(lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2) }

    /// Expands the bounds by a distance in metres on every side.
    func expanded(byMeters m: Double) -> GeoBounds {
        let dLat = m / 111_320.0
        let dLon = m / max(1, 111_320.0 * cos(center.lat * .pi / 180))
        return GeoBounds(minLat: max(-90, minLat - dLat), minLon: max(-180, minLon - dLon),
                         maxLat: min(90, maxLat + dLat), maxLon: min(180, maxLon + dLon))
    }

    /// Inclusive tile index range covered by these bounds at zoom z.
    func tileRange(z: Int) -> (minX: Int, minY: Int, maxX: Int, maxY: Int) {
        let a = TileID.containing(GeoPoint(lat: maxLat, lon: minLon), z: z)
        let b = TileID.containing(GeoPoint(lat: minLat, lon: maxLon), z: z)
        return (a.x, a.y, b.x, b.y)
    }
}

enum PolygonLabel {
    /// A point inside the polygon (outer ring minus holes) for a name label: the area centroid if it is inside,
    /// otherwise the middle of the widest horizontal span across the polygon (L- and U-shaped blocks).
    static func interiorPoint(outer: [GeoPoint], holes: [[GeoPoint]] = []) -> GeoPoint? {
        guard outer.count >= 3, let b = GeoBounds(points: outer) else { return nil }
        // Work relative to the first vertex for precision.
        let o = outer[0]
        func local(_ p: GeoPoint) -> (x: Double, y: Double) { (p.lon - o.lon, p.lat - o.lat) }
        let ring = outer.map(local)
        let holeRings = holes.map { $0.map(local) }

        var a = 0.0, cx = 0.0, cy = 0.0
        for i in 0..<ring.count {
            let p = ring[i], q = ring[(i + 1) % ring.count]
            let cross = p.x * q.y - q.x * p.y
            a += cross
            cx += (p.x + q.x) * cross
            cy += (p.y + q.y) * cross
        }
        if abs(a) > 1e-18 {
            let c = (x: cx / (3 * a), y: cy / (3 * a))
            if inside(c, ring) && !holeRings.contains(where: { inside(c, $0) }) {
                return GeoPoint(lat: o.lat + c.y, lon: o.lon + c.x)
            }
        }

        // Widest interior span on a few horizontal lines.
        var best: (width: Double, x: Double, y: Double)?
        for k in 1...15 {
            let y = (b.minLat - o.lat) + (b.maxLat - b.minLat) * Double(k) / 16
            var xs: [Double] = []
            for r in [ring] + holeRings {
                for i in 0..<r.count {
                    let p = r[i], q = r[(i + 1) % r.count]
                    if (p.y > y) != (q.y > y) { xs.append(p.x + (y - p.y) / (q.y - p.y) * (q.x - p.x)) }
                }
            }
            xs.sort()
            var i = 0
            while i + 1 < xs.count {
                let w = xs[i + 1] - xs[i]
                if w > (best?.width ?? 0) { best = (w, (xs[i] + xs[i + 1]) / 2, y) }
                i += 2
            }
        }
        if let best = best { return GeoPoint(lat: o.lat + best.y, lon: o.lon + best.x) }
        return b.center
    }

    private static func inside(_ p: (x: Double, y: Double), _ ring: [(x: Double, y: Double)]) -> Bool {
        var c = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let a = ring[i], b = ring[j]
            if (a.y > p.y) != (b.y > p.y), p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x { c.toggle() }
            j = i
        }
        return c
    }
}

enum Simplify {
    /// Douglas–Peucker simplification of a polyline in planar coordinates.
    static func douglasPeucker(_ pts: [(x: Double, y: Double)], tolerance: Double) -> [(x: Double, y: Double)] {
        guard pts.count > 2 else { return pts }
        var keep = [Bool](repeating: false, count: pts.count)
        keep[0] = true
        keep[pts.count - 1] = true
        var stack: [(Int, Int)] = [(0, pts.count - 1)]
        let tol2 = tolerance * tolerance
        while let (s, e) = stack.popLast() {
            guard e > s + 1 else { continue }
            let a = pts[s], b = pts[e]
            var maxD = -1.0
            var idx = s
            for i in (s + 1)..<e {
                let d = segmentDistanceSquared(pts[i], a, b)
                if d > maxD { maxD = d; idx = i }
            }
            if maxD > tol2 {
                keep[idx] = true
                stack.append((s, idx))
                stack.append((idx, e))
            }
        }
        return pts.enumerated().compactMap { keep[$0.offset] ? $0.element : nil }
    }

    static func segmentDistanceSquared(_ p: (x: Double, y: Double), _ a: (x: Double, y: Double), _ b: (x: Double, y: Double)) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let len2 = dx * dx + dy * dy
        var t = 0.0
        if len2 > 0 { t = max(0, min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2)) }
        let qx = a.x + t * dx - p.x, qy = a.y + t * dy - p.y
        return qx * qx + qy * qy
    }
}
