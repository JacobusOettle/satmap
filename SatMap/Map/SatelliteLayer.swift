import MapKit
import UIKit

/// Satellite imagery in two of MapKit's own tile layers, so the map neither goes black while tiles load nor flickers:
/// - `SatelliteBackdropOverlay` underneath answers at once with the best imagery already in memory: the nearest
///   coarser tile enlarged (after zooming in), or the four sharper tiles under it (after zooming out). In a new place
///   it asks for one tile three levels up first, which covers 64 tiles and arrives quickly.
/// - `SatelliteTileOverlay` on top loads each tile itself (offline areas, then Esri), and MapKit shows it over the
///   backdrop as it arrives.
/// Both replace Apple's map entirely, so MapKit doesn't download its own tiles.

/// Imagery tiles loaded for the screen (JPEG data, 15–60 KB each), for the backdrop to build on.
enum ImageryMemory {
    static let tiles = RecentCache<TileID, Data>(capacity: 900)

    static func enteredBackground() {
        tiles.trim(to: 150)
    }

    static func trimMemory() {
        tiles.trim(to: 100)
    }
}

/// The tiles of each level: offline areas first, then Esri online, then an enlarged ancestor tile.
final class SatelliteTileOverlay: MKTileOverlay {
    /// Create with `SatelliteTileOverlay(urlTemplate: nil)`; tiles come from `loadTile`, not a URL template.
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        minimumZ = 0
        maximumZ = deepestTileZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    // MapKit calls this concurrently on background queues; `result` may be called on any queue.
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        guard let t = TileID(path) else { return result(nil, TileUnavailable()) }
        Task.detached(priority: .userInitiated) {
            if let r = await TileRepository.shared.imageryForDisplay(t) {
                if r.quality == .exact { ImageryMemory.tiles.set(r.data, for: t) }
                result(r.data, nil)
            } else {
                result(nil, TileUnavailable())
            }
        }
    }
}

/// Underneath: whatever imagery is at hand for each tile, straight away.
final class SatelliteBackdropOverlay: MKTileOverlay {
    /// Create with `SatelliteBackdropOverlay(urlTemplate: nil)`.
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        minimumZ = 0
        maximumZ = deepestTileZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        guard let t = TileID(path) else { return result(nil, TileUnavailable()) }
        Task.detached(priority: .userInitiated) {
            if let data = await Self.backdrop(for: t) {
                result(data, nil)
            } else {
                result(nil, TileUnavailable())
            }
        }
    }

    /// How far up to look for a coarser tile in memory, and which one to ask for in a new place.
    private static let ancestorLevels = 8
    private static let seedLevels = 3

    private static func backdrop(for t: TileID) async -> Data? {
        let memory = ImageryMemory.tiles
        if let own = memory.value(for: t) { return own }
        // After zooming out: the four sharper tiles under this one.
        if t.z < TileSources.imageryMaxZoom {
            let children = (0..<4).map { TileID(z: t.z + 1, x: t.x * 2 + $0 % 2, y: t.y * 2 + $0 / 2) }
            let found = children.compactMap { memory.value(for: $0) }
            if found.count == 4 {
                return await Work.run(on: Work.images) { ImageTools.combine(found) }
            }
        }
        // After zooming in: the nearest coarser tile, enlarged.
        if let a = TileFallback.ancestor(of: t, maxLevels: ancestorLevels, has: { memory.contains($0) }),
           let d = memory.value(for: a) {
            return await enlarge(d, from: a, to: t)
        }
        // A new place: one tile a few levels up, asked for before the others.
        let seed = t.ancestor(levels: min(seedLevels, t.z))
        guard let r = await TileRepository.shared.imageryForDisplay(seed, urgent: true) else { return nil }
        if r.quality == .exact { memory.set(r.data, for: seed) }
        return seed == t ? r.data : await enlarge(r.data, from: seed, to: t)
    }

    private static func enlarge(_ data: Data, from a: TileID, to t: TileID) async -> Data? {
        let k = t.z - a.z
        return await Work.run(on: Work.images) {
            ImageTools.enlarge(data, levels: k, x: t.x - (a.x << k), y: t.y - (a.y << k))
        }
    }
}

extension TileID {
    /// The tile MapKit asks for, with x wrapped around the world; nil above or below it.
    init?(_ path: MKTileOverlayPath) {
        let n = 1 << path.z
        guard path.y >= 0, path.y < n else { return nil }
        self.init(z: path.z, x: ((path.x % n) + n) % n, y: path.y)
    }
}
