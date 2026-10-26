import Foundation

/// Which contours to draw at each map scale.
///
/// MapKit asks for 256-pixel tiles, so on a 2x/3x screen it requests tiles 1–1.6 zoom levels deeper than a web map
/// would show at the same on-screen scale. Intervals are therefore chosen from the "point zoom" (the web-map zoom
/// with 256-point tiles), so lines are equally dense on every iPhone.
enum ContourPolicy {
    /// Tile zooms below this never get contours (MapKit's overlay minimum).
    static let minTileZoom = 11

    /// Web-equivalent zoom of a tile zoom requested with 256-pixel tiles on a screen of `screenScale`.
    static func pointZoom(tileZoom z: Int, screenScale: Double) -> Double {
        Double(z) - log2(max(1, screenScale))
    }

    /// (interval between lines, interval between labelled index lines) in metres, or nil for no contours.
    static func intervals(forPointZoom zp: Double) -> (step: Double, index: Double)? {
        switch zp {
        case ..<11: return nil
        case ..<12: return (100, 500)
        case ..<13: return (50, 250)
        case ..<14: return (20, 100)
        case ..<16.5: return (10, 50)
        default: return (5, 25)
        }
    }

    /// Elevation tile zoom used to draw a map tile at zoom z.
    static func demZoom(forTileZoom z: Int) -> Int {
        min(z, Terrarium.maxZoom)
    }

    /// Number of grid cells across a map tile. Beyond the DEM's native zoom the data is upsampled,
    /// so fewer cells are enough for smooth lines.
    static func gridCells(forTileZoom z: Int) -> Int {
        z <= Terrarium.maxZoom ? 256 : 128
    }
}

struct ContourLine {
    var level: Double
    /// Points in grid-node units: x ∈ [0, width−1], y ∈ [0, height−1], y grows downwards.
    var points: [(x: Double, y: Double)]
    var isClosed: Bool
}

enum MarchingSquares {
    /// Traces iso-lines at every multiple of `step` through a `width`×`height` grid of node values
    /// (row-major, row 0 at the top) and joins cell segments into polylines.
    static func contours(values: [Float], width: Int, height: Int, step: Double) -> [ContourLine] {
        guard width >= 2, height >= 2, values.count == width * height, step > 0 else { return [] }
        // Terrarium elevations are multiples of 1/256 m and often whole metres, so grid values frequently equal
        // a contour level exactly, which yields degenerate or diagonal segments. Tracing each level a fraction
        // of a millimetre higher (not a multiple of 1/256) avoids that without any visible shift.
        let offset = 0.000_7

        // Segments per level index; each segment joins two grid edges identified by integer keys:
        // horizontal edge from node (i,j) to (i+1,j): (j*width+i)*2; vertical edge from (i,j) to (i,j+1): (j*width+i)*2+1.
        var segments: [Int: [(Int, Int)]] = [:]

        for j in 0..<(height - 1) {
            for i in 0..<(width - 1) {
                let tl = Double(values[j * width + i])
                let tr = Double(values[j * width + i + 1])
                let bl = Double(values[(j + 1) * width + i])
                let br = Double(values[(j + 1) * width + i + 1])
                let lo = min(min(tl, tr), min(bl, br))
                let hi = max(max(tl, tr), max(bl, br))
                guard hi > lo else { continue }
                let kLo = Int(ceil((lo - offset) / step))
                let kHi = Int(floor((hi - offset) / step))
                guard kLo <= kHi else { continue }

                let top = (j * width + i) * 2
                let bottom = ((j + 1) * width + i) * 2
                let left = (j * width + i) * 2 + 1
                let right = (j * width + i + 1) * 2 + 1

                for k in kLo...kHi {
                    let level = Double(k) * step + offset
                    let a = tl >= level, b = tr >= level, c = br >= level, d = bl >= level
                    var crossed: [Int] = []
                    crossed.reserveCapacity(4)
                    if a != b { crossed.append(top) }
                    if b != c { crossed.append(right) }
                    if d != c { crossed.append(bottom) }
                    if a != d { crossed.append(left) }
                    if crossed.count == 2 {
                        segments[k, default: []].append((crossed[0], crossed[1]))
                    } else if crossed.count == 4 {
                        // Saddle: resolve with the cell-centre value.
                        let centreAbove = (tl + tr + bl + br) / 4 >= level
                        // a == c here (diagonal corners agree).
                        if a == centreAbove {
                            // tl/br corners connect through the centre; cut off tr and bl.
                            segments[k, default: []].append((top, right))
                            segments[k, default: []].append((left, bottom))
                        } else {
                            // tr/bl corners connect; cut off tl and br.
                            segments[k, default: []].append((top, left))
                            segments[k, default: []].append((right, bottom))
                        }
                    }
                }
            }
        }

        var lines: [ContourLine] = []
        for k in segments.keys.sorted() {
            let level = Double(k) * step + offset
            let segs = segments[k]!
            func position(_ key: Int) -> (x: Double, y: Double) {
                let node = key >> 1
                let i = node % width, j = node / width
                let v0 = Double(values[node])
                if key & 1 == 0 {
                    let v1 = Double(values[node + 1])
                    let t = v1 == v0 ? 0.5 : (level - v0) / (v1 - v0)
                    return (Double(i) + t, Double(j))
                } else {
                    let v1 = Double(values[node + width])
                    let t = v1 == v0 ? 0.5 : (level - v0) / (v1 - v0)
                    return (Double(i), Double(j) + t)
                }
            }
            for chain in join(segs) {
                let pts = chain.keys.map(position)
                guard ContourGeometry.length(pts) > 0.01 else { continue }
                lines.append(ContourLine(level: Double(k) * step, points: pts, isClosed: chain.closed))
            }
        }
        return lines
    }

    /// Joins segments (pairs of edge keys) into chains of edge keys.
    static func join(_ segs: [(Int, Int)]) -> [(keys: [Int], closed: Bool)] {
        var adjacency: [Int: (Int, Int)] = [:] // edge key -> up to two segment indices (-1 = none)
        adjacency.reserveCapacity(segs.count * 2)
        for (idx, s) in segs.enumerated() {
            for key in [s.0, s.1] {
                if let e = adjacency[key] {
                    if e.1 == -1 { adjacency[key] = (e.0, idx) }
                } else {
                    adjacency[key] = (idx, -1)
                }
            }
        }
        var used = [Bool](repeating: false, count: segs.count)
        var chains: [(keys: [Int], closed: Bool)] = []

        func walk(from startKey: Int, segment startSeg: Int) -> (keys: [Int], closed: Bool) {
            var keys = [startKey]
            var key = startKey
            var seg = startSeg
            while true {
                used[seg] = true
                let s = segs[seg]
                let next = s.0 == key ? s.1 : s.0
                keys.append(next)
                if next == startKey { return (keys, true) }
                guard let e = adjacency[next] else { break }
                let candidate = e.0 == seg ? e.1 : e.0
                if candidate < 0 || used[candidate] { break }
                key = next
                seg = candidate
            }
            return (keys, false)
        }

        // Open chains first, starting from edge keys used by exactly one segment.
        for (key, e) in adjacency where e.1 == -1 && !used[e.0] {
            chains.append(walk(from: key, segment: e.0))
        }
        // Remaining segments form closed loops.
        for idx in 0..<segs.count where !used[idx] {
            chains.append(walk(from: segs[idx].0, segment: idx))
        }
        return chains
    }
}

enum ContourGeometry {
    /// One pass of Chaikin corner cutting; keeps the end points of open lines.
    static func chaikin(_ pts: [(x: Double, y: Double)], closed: Bool) -> [(x: Double, y: Double)] {
        guard pts.count >= 3 else { return pts }
        var out: [(x: Double, y: Double)] = []
        out.reserveCapacity(pts.count * 2)
        let n = pts.count
        if !closed { out.append(pts[0]) }
        let last = closed ? n - 1 : n - 2
        for i in 0..<(last + 1) {
            let p = pts[i], q = pts[(i + 1) % n]
            if closed && i == n - 1 && p.x == pts[0].x && p.y == pts[0].y { continue }
            out.append((0.75 * p.x + 0.25 * q.x, 0.75 * p.y + 0.25 * q.y))
            out.append((0.25 * p.x + 0.75 * q.x, 0.25 * p.y + 0.75 * q.y))
        }
        if !closed { out.append(pts[n - 1]) } else if let f = out.first { out.append(f) }
        return out
    }

    static func length(_ pts: [(x: Double, y: Double)]) -> Double {
        guard pts.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<pts.count { total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y) }
        return total
    }

    /// A label position at the middle of a line, with an upright text angle (radians) following the line.
    /// Returns nil if the line is shorter than `minLength` or the label would fall within `margin` of the bounds.
    static func labelAnchor(_ pts: [(x: Double, y: Double)], minLength: Double, bounds: (w: Double, h: Double), margin: Double)
        -> (x: Double, y: Double, angle: Double)? {
        let total = length(pts)
        guard total >= minLength else { return nil }
        let target = total / 2
        var run = 0.0
        for i in 1..<pts.count {
            let a = pts[i - 1], b = pts[i]
            let seg = hypot(b.x - a.x, b.y - a.y)
            if run + seg >= target, seg > 0 {
                let t = (target - run) / seg
                let x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t
                guard x >= margin, y >= margin, x <= bounds.w - margin, y <= bounds.h - margin else { return nil }
                // Direction from points roughly ±minLength/6 along the line, for a stable angle.
                let window = minLength / 6
                let p0 = point(pts, at: max(0, target - window))
                let p1 = point(pts, at: min(total, target + window))
                var angle = atan2(p1.y - p0.y, p1.x - p0.x)
                if angle > .pi / 2 { angle -= .pi }
                if angle < -.pi / 2 { angle += .pi }
                return (x, y, angle)
            }
            run += seg
        }
        return nil
    }

    static func point(_ pts: [(x: Double, y: Double)], at distance: Double) -> (x: Double, y: Double) {
        var run = 0.0
        for i in 1..<pts.count {
            let a = pts[i - 1], b = pts[i]
            let seg = hypot(b.x - a.x, b.y - a.y)
            if run + seg >= distance, seg > 0 {
                let t = (distance - run) / seg
                return (a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            run += seg
        }
        return pts[pts.count - 1]
    }
}
