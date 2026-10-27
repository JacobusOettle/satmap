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

/// Which way the imagery is drawn (a setting for measuring them against each other; see ZoomTests).
enum SatelliteMode: String {
    /// One tile layer, as before the backdrop.
    case single
    /// A backdrop tile layer under the tile layer.
    case backdrop
    /// SatMap draws the imagery itself (`SatelliteRenderer`).
    case renderer

    static var current: SatelliteMode {
        UserDefaults.standard.string(forKey: "satelliteMode").flatMap(SatelliteMode.init) ?? .backdrop
    }
}

/// For `SatelliteRenderer`: a tile overlay only so that MapKit leaves out its own map; its tiles aren't used.
final class SatelliteDrawnOverlay: MKTileOverlay {
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        result(nil, TileUnavailable())
    }
}

/// Draws the imagery itself: each tile is painted at once with the best imagery in memory (its own tile, else the
/// nearest coarser one enlarged and the sharper ones under it), so a zoom never shows an empty block. Tiles that arrive
/// are drawn by asking MapKit to redraw their places, gathered and at most about three times a second.
final class SatelliteRenderer: MKOverlayRenderer {
    private static let decoded = RecentCache<TileID, UIImage>(capacity: 300)

    private let lock = NSLock()
    private var loading = Set<TileID>()
    private var retryAfter: [TileID: CFTimeInterval] = [:]
    private var arrived = Set<TileID>()
    private var flushScheduled = false
    private var lastZoomScale: MKZoomScale = 0

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        lock.withLock { lastZoomScale = zoomScale }
        let world = MKMapSize.world.width
        let pixels = world * Double(zoomScale) * Double(contentScaleFactor)
        let z = Int(min(Double(TileSources.imageryMaxZoom), max(0, log2(pixels / 256).rounded())))
        let n = 1 << z
        let size = world / Double(n)
        let x0 = max(0, Int(floor(mapRect.minX / size))), x1 = min(n - 1, Int(ceil(mapRect.maxX / size)) - 1)
        let y0 = max(0, Int(floor(mapRect.minY / size))), y1 = min(n - 1, Int(ceil(mapRect.maxY / size)) - 1)
        guard x0 <= x1, y0 <= y1 else { return }
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        context.interpolationQuality = .medium
        for y in y0...y1 {
            for x in x0...x1 {
                let t = TileID(z: z, x: x, y: y)
                let rect = self.rect(for: Self.mapRect(t))
                if let image = Self.image(t) {
                    image.draw(in: rect)
                    continue
                }
                let ancestor = drawStandIn(for: t, in: rect, context: context)
                load(t)
                // A new place: a tile three levels up covers 64 of these.
                if t.z >= 3, (ancestor?.z ?? -1) < t.z - 3 { load(t.ancestor(levels: 3), urgent: true) }
            }
        }
    }

    private static func image(_ t: TileID) -> UIImage? {
        if let i = decoded.value(for: t) { return i }
        guard let d = ImageryMemory.tiles.value(for: t), let i = UIImage(data: d)?.preparingForDisplay() else { return nil }
        decoded.set(i, for: t)
        return i
    }

    private func drawStandIn(for t: TileID, in rect: CGRect, context: CGContext) -> TileID? {
        let memory = ImageryMemory.tiles
        let ancestor = TileFallback.ancestor(of: t, maxLevels: 10) { memory.contains($0) || Self.decoded.contains($0) }
        if let a = ancestor, let image = Self.image(a) {
            context.saveGState()
            context.clip(to: rect)
            image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
        }
        if t.z < TileSources.imageryMaxZoom {
            for i in 0..<4 {
                let c = TileID(z: t.z + 1, x: t.x * 2 + i % 2, y: t.y * 2 + i / 2)
                if memory.contains(c), let image = Self.image(c) { image.draw(in: self.rect(for: Self.mapRect(c))) }
            }
        }
        return ancestor
    }

    private func load(_ t: TileID, urgent: Bool = false) {
        let now = CACurrentMediaTime()
        let start: Bool = lock.withLock {
            if let after = retryAfter[t], now < after { return false }
            return loading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            var ok = false
            if let r = await TileRepository.shared.imageryForDisplay(t, urgent: urgent),
               let image = await Work.run(on: Work.images, { UIImage(data: r.data)?.preparingForDisplay() }) {
                if r.quality == .exact { ImageryMemory.tiles.set(r.data, for: t) }
                Self.decoded.set(image, for: t)
                ok = true
            }
            guard let self = self else { return }
            let schedule: Bool = self.lock.withLock {
                self.loading.remove(t)
                if !ok {
                    self.retryAfter[t] = CACurrentMediaTime() + 30
                    return false
                }
                self.arrived.insert(t)
                if self.flushScheduled { return false }
                self.flushScheduled = true
                return true
            }
            guard schedule else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.flush() }
        }
    }

    /// Redraws the places of the tiles that arrived, at the zoom on screen.
    private func flush() {
        let (tiles, scale): (Set<TileID>, MKZoomScale) = lock.withLock {
            let t = arrived
            arrived = []
            flushScheduled = false
            return (t, lastZoomScale)
        }
        for t in tiles {
            if scale > 0 { setNeedsDisplay(Self.mapRect(t), zoomScale: scale) } else { setNeedsDisplay(Self.mapRect(t)) }
        }
    }

    func reload() {
        lock.withLock { retryAfter.removeAll() }
    }

    private static func mapRect(_ t: TileID) -> MKMapRect {
        let size = MKMapSize.world.width / Double(1 << t.z)
        return MKMapRect(x: Double(t.x) * size, y: Double(t.y) * size, width: size, height: size)
    }
}
