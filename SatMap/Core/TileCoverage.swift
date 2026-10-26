import Foundation

/// The area to make available offline: filled polygons, lines and points, each grown by a buffer.
/// A lasso-drawn area is a single polygon; a KML file contributes all of its geometry.
struct CoverageShape: Codable, Hashable {
    var polygons: [[GeoPoint]]
    var lines: [[GeoPoint]]
    var points: [GeoPoint]
    var bufferMeters: Double

    init(polygons: [[GeoPoint]] = [], lines: [[GeoPoint]] = [], points: [GeoPoint] = [], bufferMeters: Double = 0) {
        self.polygons = polygons
        self.lines = lines
        self.points = points
        self.bufferMeters = bufferMeters
    }

    var isEmpty: Bool {
        polygons.allSatisfy { $0.count < 3 } && lines.allSatisfy { $0.isEmpty } && points.isEmpty
    }

    /// Bounds of the geometry, without the buffer.
    var geometryBounds: GeoBounds? {
        GeoBounds(points: Array(polygons.joined()) + Array(lines.joined()) + points)
    }

    /// Bounds including the buffer.
    var bounds: GeoBounds? {
        geometryBounds?.expanded(byMeters: bufferMeters)
    }

    /// Area of the filled polygons in square metres (lines/points are not counted).
    var polygonAreaSquareMeters: Double {
        polygons.reduce(0) { $0 + Geo.ringArea($1) }
    }
}

/// The exact set of tiles at one zoom level that intersect a `CoverageShape`,
/// stored as merged inclusive x-ranges per tile row so that huge areas stay cheap.
struct TileCoverage {
    struct Row {
        let y: Int
        let ranges: [ClosedRange<Int>]
    }

    let zoom: Int
    /// Rows sorted by y; each row's ranges are sorted and non-overlapping.
    let rows: [Row]
    let tileCount: Int

    init(shape: CoverageShape, zoom: Int) {
        self.zoom = zoom
        guard let gb = shape.geometryBounds, !shape.isEmpty else {
            rows = []
            tileCount = 0
            return
        }
        let n = Double(1 << zoom)
        let maxIndex = (1 << zoom) - 1
        let metersPerWorld = Geo.metersPerWorldUnit(atLatitude: gb.center.lat)
        let buf = max(0, shape.bufferMeters) / metersPerWorld * n

        var extents: [Int: [(Double, Double)]] = [:]

        func add(row: Int, _ x0: Double, _ x1: Double) {
            guard row >= 0, row <= maxIndex else { return }
            extents[row, default: []].append((min(x0, x1), max(x0, x1)))
        }

        func toTile(_ p: GeoPoint) -> (x: Double, y: Double) {
            let w = WebMercator.world(p)
            return (w.x * n, w.y * n)
        }

        func rowSpan(_ minY: Double, _ maxY: Double) -> ClosedRange<Int>? {
            let r0 = max(0, Int(floor(minY - buf)))
            let r1 = min(maxIndex, Int(floor(maxY + buf)))
            return r0 <= r1 ? r0...r1 : nil
        }

        /// x-extent of segment p→q clipped to the horizontal band [top, bottom].
        func clip(_ p: (x: Double, y: Double), _ q: (x: Double, y: Double), _ top: Double, _ bottom: Double) -> (Double, Double)? {
            if max(p.y, q.y) < top || min(p.y, q.y) > bottom { return nil }
            if p.y == q.y { return (min(p.x, q.x), max(p.x, q.x)) }
            let tA = (top - p.y) / (q.y - p.y)
            let tB = (bottom - p.y) / (q.y - p.y)
            let lo = max(0, min(tA, tB))
            let hi = min(1, max(tA, tB))
            guard lo <= hi else { return nil }
            let xa = p.x + lo * (q.x - p.x)
            let xb = p.x + hi * (q.x - p.x)
            return (min(xa, xb), max(xa, xb))
        }

        // Filled polygons: the x-projection of (polygon ∩ band) is the union of the clipped edges'
        // extents and the polygon's interior spans along the band's top and bottom lines.
        for ring in shape.polygons where ring.count >= 3 {
            var pts = ring.map(toTile)
            if pts.first! != pts.last! { pts.append(pts.first!) }
            let ys = pts.map(\.y)
            guard let span = rowSpan(ys.min()!, ys.max()!) else { continue }
            for row in span {
                let top = Double(row) - buf
                let bottom = Double(row + 1) + buf
                for i in 0..<(pts.count - 1) {
                    if let e = clip(pts[i], pts[i + 1], top, bottom) { add(row: row, e.0, e.1) }
                }
                for lineY in [top, bottom] {
                    var xs: [Double] = []
                    for i in 0..<(pts.count - 1) {
                        let p = pts[i], q = pts[i + 1]
                        if (p.y > lineY) != (q.y > lineY) {
                            xs.append(p.x + (lineY - p.y) / (q.y - p.y) * (q.x - p.x))
                        }
                    }
                    xs.sort()
                    var k = 0
                    while k + 1 < xs.count {
                        add(row: row, xs[k], xs[k + 1])
                        k += 2
                    }
                }
            }
        }

        for line in shape.lines where !line.isEmpty {
            let pts = line.map(toTile)
            if pts.count == 1 {
                if let span = rowSpan(pts[0].y, pts[0].y) {
                    for row in span { add(row: row, pts[0].x, pts[0].x) }
                }
                continue
            }
            for i in 0..<(pts.count - 1) {
                let p = pts[i], q = pts[i + 1]
                guard let span = rowSpan(min(p.y, q.y), max(p.y, q.y)) else { continue }
                for row in span {
                    if let e = clip(p, q, Double(row) - buf, Double(row + 1) + buf) { add(row: row, e.0, e.1) }
                }
            }
        }

        for point in shape.points {
            let p = toTile(point)
            if let span = rowSpan(p.y, p.y) {
                for row in span { add(row: row, p.x, p.x) }
            }
        }

        var builtRows: [Row] = []
        var count = 0
        for y in extents.keys.sorted() {
            var ranges: [ClosedRange<Int>] = extents[y]!.map { e in
                let start = max(0, Int(floor(e.0 - buf)))
                let end = min(maxIndex, max(start, Int(ceil(e.1 + buf)) - 1))
                return start...max(start, end)
            }
            ranges.sort { $0.lowerBound < $1.lowerBound }
            var merged: [ClosedRange<Int>] = []
            for r in ranges {
                if let last = merged.last, r.lowerBound <= last.upperBound + 1 {
                    merged[merged.count - 1] = last.lowerBound...max(last.upperBound, r.upperBound)
                } else {
                    merged.append(r)
                }
            }
            count += merged.reduce(0) { $0 + $1.count }
            builtRows.append(Row(y: y, ranges: merged))
        }
        rows = builtRows
        tileCount = count
    }

    func contains(x: Int, y: Int) -> Bool {
        var lo = 0, hi = rows.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let r = rows[mid]
            if r.y == y {
                return Self.rangesContain(r.ranges, x)
            } else if r.y < y {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return false
    }

    func contains(_ tile: TileID) -> Bool {
        tile.z == zoom && contains(x: tile.x, y: tile.y)
    }

    func forEachTile(_ body: (TileID) throws -> Void) rethrows {
        for row in rows {
            for r in row.ranges {
                for x in r { try body(TileID(z: zoom, x: x, y: row.y)) }
            }
        }
    }

    private static func rangesContain(_ ranges: [ClosedRange<Int>], _ x: Int) -> Bool {
        var lo = 0, hi = ranges.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let r = ranges[mid]
            if r.contains(x) { return true }
            if x < r.lowerBound { hi = mid - 1 } else { lo = mid + 1 }
        }
        return false
    }

    /// Upper-bound tile counts per zoom for a shape (before any "imagery doesn't exist here" pruning).
    static func tileCounts(shape: CoverageShape, zooms: ClosedRange<Int>) -> [Int: Int] {
        var out: [Int: Int] = [:]
        for z in zooms { out[z] = TileCoverage(shape: shape, zoom: z).tileCount }
        return out
    }
}
