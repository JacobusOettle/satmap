import Foundation

/// What the terrain button shows over the imagery: contour lines, the heights in colour, both, or neither. Each tap
/// moves on to the next.
enum TerrainMode: String, CaseIterable {
    case contours
    case contoursAndColours
    case colours
    case off

    var next: TerrainMode {
        switch self {
        case .contours: return .contoursAndColours
        case .contoursAndColours: return .colours
        case .colours: return .off
        case .off: return .contours
        }
    }

    var showsContours: Bool { self == .contours || self == .contoursAndColours }
    var showsColours: Bool { self == .colours || self == .contoursAndColours }

    var title: String {
        switch self {
        case .contours: return "Contour lines"
        case .contoursAndColours: return "Contour lines and elevation colours"
        case .colours: return "Elevation colours"
        case .off: return "Contours and colours off"
        }
    }

    /// The mode saved under `terrainMode`, or from before there were colours (`showContours`, on unless set to off).
    static func saved(mode: String?, showContours: Bool?) -> TerrainMode {
        if let m = mode.flatMap(TerrainMode.init(rawValue:)) { return m }
        return showContours == false ? .off : .contours
    }
}

/// The heights coloured from blue (`low` and below) to red (`high` and above).
struct ElevationRange: Equatable {
    let low: Double
    let high: Double
}

/// Heights at a tile's grid nodes, `nodes` × `nodes`, row-major from the north edge, the first and last row and column
/// on the tile's edges (so neighbouring tiles share their edge nodes). A class so a drawn image can tell it is still
/// for the same grid.
final class ElevationGrid {
    let nodes: Int
    let values: [Float]

    init(nodes: Int, values: [Float]) {
        self.nodes = nodes
        self.values = values
    }

    /// The lowest and highest heights of the nodes inside a world rectangle (the tile's own `bounds`), leaving out
    /// the sea (below 0 m: the elevation tiles give the sea floor there). Nil if no land node lies inside.
    func extremes(tile bounds: (minX: Double, minY: Double, maxX: Double, maxY: Double),
                  within r: (minX: Double, minY: Double, maxX: Double, maxY: Double)) -> (low: Float, high: Float)? {
        guard nodes >= 2, values.count == nodes * nodes else { return nil }
        let step = (bounds.maxX - bounds.minX) / Double(nodes - 1)
        guard step > 0 else { return nil }
        let i0 = max(0, Int(ceil((r.minX - bounds.minX) / step - 1e-9)))
        let i1 = min(nodes - 1, Int(floor((r.maxX - bounds.minX) / step + 1e-9)))
        let j0 = max(0, Int(ceil((r.minY - bounds.minY) / step - 1e-9)))
        let j1 = min(nodes - 1, Int(floor((r.maxY - bounds.minY) / step + 1e-9)))
        guard i0 <= i1, j0 <= j1 else { return nil }
        var low = Float.infinity, high = -Float.infinity
        for j in j0...j1 {
            let row = j * nodes
            for i in i0...i1 {
                let v = values[row + i]
                guard v >= 0 else { continue }
                if v < low { low = v }
                if v > high { high = v }
            }
        }
        return low <= high ? (low, high) : nil
    }
}

/// The elevation colours over the imagery: each height between the range's low and high gets a colour from blue
/// (lowest) through cyan, green, yellow and orange to red (highest), the range chosen from the heights on screen.
enum ElevationColours {
    /// Tiles of lower zooms get no colours (each needs a whole elevation tile of its own).
    static let minTileZoom = 8
    /// Grid cells across a tile (its colours are drawn smoothly between the nodes).
    static let cells = 64
    /// How much of the imagery shows through.
    static let alpha = 0.5
    /// A range spans at least this much, so nearly flat ground isn't coloured from blue to red by noise in the data.
    static let minimumSpan = 10.0

    /// (position, red, green, blue)
    static let stops: [(Double, Double, Double, Double)] = [
        (0.0, 0.16, 0.32, 0.95),
        (0.2, 0.10, 0.72, 0.97),
        (0.4, 0.22, 0.80, 0.36),
        (0.6, 0.97, 0.90, 0.20),
        (0.8, 0.99, 0.55, 0.10),
        (1.0, 0.90, 0.12, 0.12),
    ]

    /// The colour at a place along the ramp (0: lowest, blue; 1: highest, red).
    static func colour(at f: Double) -> (red: Double, green: Double, blue: Double) {
        let x = min(1, max(0, f.isFinite ? f : 0))
        for k in 1..<stops.count where x <= stops[k].0 {
            let a = stops[k - 1], b = stops[k]
            let t = (x - a.0) / (b.0 - a.0)
            return (a.1 + (b.1 - a.1) * t, a.2 + (b.2 - a.2) * t, a.3 + (b.3 - a.3) * t)
        }
        let last = stops[stops.count - 1]
        return (last.1, last.2, last.3)
    }

    /// The range to colour with when the screen shows heights from `low` to `high`: the one shown (`current`) while
    /// they still fit in it and fill most of it, so panning around doesn't keep changing the colours; else one just
    /// around them, at round heights. While some tiles on screen have no heights yet (`complete` false), the range
    /// shown only grows.
    static func range(low: Double, high: Double, current: ElevationRange?, complete: Bool = true) -> ElevationRange {
        if let c = current {
            let fits = low >= c.low && high <= c.high
            if fits && (!complete || high - low >= 0.6 * (c.high - c.low)) { return c }
        }
        var lo = low, hi = high
        if !complete, let c = current {
            lo = min(lo, c.low)
            hi = max(hi, c.high)
        }
        if hi - lo < minimumSpan {
            let mid = (lo + hi) / 2
            lo = mid - minimumSpan / 2
            hi = mid + minimumSpan / 2
        }
        let step = niceStep((hi - lo) / 10)
        return ElevationRange(low: (lo / step).rounded(.down) * step, high: (hi / step).rounded(.up) * step)
    }

    /// 1, 2 or 5 times a power of ten, at least `x`.
    static func niceStep(_ x: Double) -> Double {
        guard x > 0, x.isFinite else { return 1 }
        let p = pow(10, floor(log10(x)))
        for m in [1.0, 2, 5, 10] where m * p >= x * (1 - 1e-9) { return m * p }
        return 10 * p
    }

    /// Premultiplied RGBA pixels (4 bytes each), one per grid node, with the sea (below 0 m) left clear.
    static func pixels(_ grid: ElevationGrid, range: ElevationRange, alpha: Double = alpha) -> [UInt8] {
        let table = lookupTable(alpha: alpha)
        let span = max(range.high - range.low, 1e-6)
        let scale = Float(255 / span), low = Float(range.low)
        var out = [UInt8](repeating: 0, count: grid.values.count * 4)
        for (n, v) in grid.values.enumerated() where v >= 0 {
            let k = Int(min(255, max(0, ((v - low) * scale).rounded())))
            out[n * 4] = table[k * 4]
            out[n * 4 + 1] = table[k * 4 + 1]
            out[n * 4 + 2] = table[k * 4 + 2]
            out[n * 4 + 3] = table[k * 4 + 3]
        }
        return out
    }

    /// 256 premultiplied RGBA colours along the ramp.
    private static func lookupTable(alpha: Double) -> [UInt8] {
        let a = min(1, max(0, alpha))
        var table = [UInt8](repeating: 0, count: 256 * 4)
        for k in 0..<256 {
            let c = colour(at: Double(k) / 255)
            table[k * 4] = UInt8((c.red * a * 255).rounded())
            table[k * 4 + 1] = UInt8((c.green * a * 255).rounded())
            table[k * 4 + 2] = UInt8((c.blue * a * 255).rounded())
            table[k * 4 + 3] = UInt8((a * 255).rounded())
        }
        return table
    }
}
