import Foundation

/// What the map shows for a spot while its own tile loads, so it never goes black: the nearest coarser tile already in
/// memory (enlarged), then sharper tiles from deeper levels that are still in memory (after zooming out). The real
/// tile is drawn over them, fading in, when it arrives.
enum TileFallback {
    /// The tile zoom for a map shown `pixelsPerWorld` screen pixels across the whole world: one image pixel per
    /// screen pixel, rounded to the nearest level (as MapKit's own tile overlays).
    static func zoom(pixelsPerWorld: Double, maxZoom: Int) -> Int {
        guard pixelsPerWorld > 0 else { return 0 }
        let z = log2(pixelsPerWorld / 256).rounded()
        return Int(min(Double(maxZoom), max(0, z)))
    }

    /// The tiles at zoom `z` covering part of the world given as fractions (0...1) of its width and height.
    static func tiles(minX: Double, minY: Double, maxX: Double, maxY: Double, z: Int) -> [TileID] {
        let n = 1 << z
        let scale = Double(n)
        let x0 = max(0, Int(floor(minX * scale))), x1 = min(n - 1, Int(ceil(maxX * scale)) - 1)
        let y0 = max(0, Int(floor(minY * scale))), y1 = min(n - 1, Int(ceil(maxY * scale)) - 1)
        guard x0 <= x1, y0 <= y1 else { return [] }
        var out: [TileID] = []
        out.reserveCapacity((x1 - x0 + 1) * (y1 - y0 + 1))
        for y in y0...y1 {
            for x in x0...x1 { out.append(TileID(z: z, x: x, y: y)) }
        }
        return out
    }

    /// The nearest ancestor, at most `maxLevels` up, that `has` says is available.
    static func ancestor(of t: TileID, maxLevels: Int, has: (TileID) -> Bool) -> TileID? {
        guard t.z > 0, maxLevels > 0 else { return nil }
        for k in 1...min(maxLevels, t.z) {
            let a = t.ancestor(levels: k)
            if has(a) { return a }
        }
        return nil
    }

    /// Available tiles inside `t` from up to `levels` zooms deeper, coarsest first: drawn in this order, the sharpest
    /// end on top.
    static func descendants(of t: TileID, levels: Int, has: (TileID) -> Bool) -> [TileID] {
        var out: [TileID] = []
        guard levels > 0 else { return out }
        for d in 1...levels {
            let side = 1 << d
            for dy in 0..<side {
                for dx in 0..<side {
                    let c = TileID(z: t.z + d, x: (t.x << d) + dx, y: (t.y << d) + dy)
                    if has(c) { out.append(c) }
                }
            }
        }
        return out
    }
}

/// A thread-safe cache that keeps the most recently used values (lookups are O(1); when full, the least recently used
/// quarter is dropped at once).
final class RecentCache<Key: Hashable, Value> {
    private let capacity: Int
    private var values: [Key: (value: Value, used: UInt64)] = [:]
    private var clock: UInt64 = 0
    private let lock = NSLock()

    init(capacity: Int) {
        self.capacity = max(1, capacity)
    }

    var count: Int { lock.withLock { values.count } }

    func value(for key: Key) -> Value? {
        lock.withLock {
            guard let entry = values[key] else { return nil }
            clock += 1
            values[key] = (entry.value, clock)
            return entry.value
        }
    }

    func contains(_ key: Key) -> Bool {
        lock.withLock { values[key] != nil }
    }

    func set(_ value: Value, for key: Key) {
        lock.withLock {
            clock += 1
            values[key] = (value, clock)
            if values.count > capacity { trimLocked(to: capacity * 3 / 4) }
        }
    }

    func remove(where shouldRemove: (Key, Value) -> Bool) {
        lock.withLock {
            values = values.filter { !shouldRemove($0.key, $0.value.value) }
        }
    }

    /// Keeps only the `count` most recently used values.
    func trim(to count: Int) {
        lock.withLock { trimLocked(to: count) }
    }

    private func trimLocked(to count: Int) {
        guard values.count > count else { return }
        let keep = values.sorted { $0.value.used > $1.value.used }.prefix(max(0, count))
        values = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
    }
}
