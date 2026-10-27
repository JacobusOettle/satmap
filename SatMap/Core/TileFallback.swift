import Foundation

/// Stand-ins for a tile that hasn't loaded yet, so the map never goes black.
enum TileFallback {
    /// The nearest ancestor, at most `maxLevels` up, that `has` says is available.
    static func ancestor(of t: TileID, maxLevels: Int, has: (TileID) -> Bool) -> TileID? {
        guard t.z > 0, maxLevels > 0 else { return nil }
        for k in 1...min(maxLevels, t.z) {
            let a = t.ancestor(levels: k)
            if has(a) { return a }
        }
        return nil
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

    /// Marks a value as just used (so it is kept longer) and says whether there is one.
    @discardableResult
    func touch(_ key: Key) -> Bool {
        lock.withLock {
            guard let entry = values[key] else { return false }
            clock += 1
            values[key] = (entry.value, clock)
            return true
        }
    }

    func set(_ value: Value, for key: Key) {
        lock.withLock {
            clock += 1
            values[key] = (value, clock)
            if values.count > capacity { trimLocked(to: capacity * 3 / 4) }
        }
    }

    func remove(_ key: Key) {
        lock.withLock { values[key] = nil }
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
