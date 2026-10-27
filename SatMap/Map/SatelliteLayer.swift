import ImageIO
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
        SatelliteRenderer.trim(to: 0)
        SatelliteBackdropOverlay.trim(to: 0)
    }

    static func trimMemory() {
        tiles.trim(to: 100)
        SatelliteRenderer.trim(to: 32)
        SatelliteBackdropOverlay.trim(to: 0)
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

    private let lock = NSLock()
    private var needsReload = false

    /// Whether a tile was answered with nothing or a stand-in since the last call (then a reload can improve it).
    func takeNeedsReload() -> Bool {
        lock.withLock {
            defer { needsReload = false }
            return needsReload
        }
    }

    // MapKit calls this concurrently on background queues; `result` may be called on any queue.
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        guard let t = TileID(path) else { return result(nil, TileUnavailable()) }
        // Tiles shown before come straight from memory (also when MapKit asks again after a reload).
        if let d = ImageryMemory.tiles.value(for: t) { return result(d, nil) }
        Task.detached(priority: .userInitiated) {
            let r = await TileRepository.shared.imageryForDisplay(t)
            if let r = r, r.quality == .exact {
                ImageryMemory.tiles.set(r.data, for: t)
            } else {
                self.lock.withLock { self.needsReload = true }
            }
            if let r = r { result(r.data, nil) } else { result(nil, TileUnavailable()) }
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
    /// Decoded tiles the backdrop cuts from (many tiles share one), so each is decoded once.
    private static let decoded = RecentCache<TileID, CGImage>(capacity: 64)

    static func trim(to count: Int) {
        decoded.trim(to: count)
    }

    private static func backdrop(for t: TileID) async -> Data? {
        let memory = ImageryMemory.tiles
        if let own = memory.value(for: t) { return own }
        // After zooming out: the four sharper tiles under this one.
        if t.z < TileSources.imageryMaxZoom {
            let children = (0..<4).map { TileID(z: t.z + 1, x: t.x * 2 + $0 % 2, y: t.y * 2 + $0 / 2) }
            let found = children.compactMap { memory.value(for: $0) }
            if found.count == 4 {
                return await Work.run(on: Work.backdrop) { ImageTools.combine(found) }
            }
        }
        // After zooming in: the nearest coarser tile, the part of it under this one.
        if let a = TileFallback.ancestor(of: t, maxLevels: ancestorLevels, has: { memory.contains($0) }),
           let d = memory.value(for: a) {
            return await cut(d, from: a, to: t)
        }
        // A new place: one tile a few levels up, asked for before the others.
        let seed = t.ancestor(levels: min(seedLevels, t.z))
        guard let r = await TileRepository.shared.imageryForDisplay(seed, urgent: true) else { return nil }
        if r.quality == .exact { memory.set(r.data, for: seed) }
        return seed == t ? r.data : await cut(r.data, from: seed, to: t)
    }

    /// The part of ancestor `a`'s image under tile `t`, at its own size: MapKit enlarges it to the tile, which is much
    /// quicker than enlarging it here (the backdrop has to answer a whole screen of tiles at once).
    private static func cut(_ data: Data, from a: TileID, to t: TileID) async -> Data? {
        await Work.run(on: Work.backdrop) {
            let image: CGImage
            if let i = decoded.value(for: a) {
                image = i
            } else {
                guard let src = CGImageSourceCreateWithData(data as CFData, nil),
                      let i = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
                else { return nil }
                decoded.set(i, for: a)
                image = i
            }
            let k = t.z - a.z
            let side = max(1, image.width >> k)
            let rect = CGRect(x: (t.x - (a.x << k)) * side, y: (t.y - (a.y << k)) * side, width: side, height: side)
            guard let part = image.cropping(to: rect) else { return nil }
            return ImageTools.jpeg(part)
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

/// Which way the imagery is drawn: a setting while the ways are tried out against each other (Offline maps, and the
/// zoom test, see ZoomTests).
enum SatelliteMode: String {
    /// One tile layer, as before the backdrop.
    case single
    /// A backdrop tile layer under the tile layer (`SatelliteBackdropOverlay`).
    case backdrop
    /// SatMap draws what it has under the tile layer (`SatelliteRenderer`).
    case renderer

    static let changed = Notification.Name("SatelliteModeChanged")

    static var current: SatelliteMode {
        UserDefaults.standard.string(forKey: "satelliteMode").flatMap(SatelliteMode.init) ?? .renderer
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

/// The layer under the tile layer in Drawn mode: each spot is painted at once, as MapKit asks for it at a new zoom or
/// place, with the best imagery in memory: its own tile, else the nearest coarser one enlarged and the sharper ones
/// under it. MapKit shows this while the tile layer above loads, instead of grey. It loads nothing itself and asks
/// MapKit to redraw only spots it had nothing for, once a coarse tile for that place has arrived (they were empty, so
/// nothing visible blinks). MapKit calls `draw` on several threads at once.
final class SatelliteRenderer: MKOverlayRenderer {
    /// Decoded tiles (256 KB each) drawn from, so each is decoded once.
    private static let decoded = RecentCache<TileID, UIImage>(capacity: 300)

    private let lock = NSLock()
    /// Coarse tiles asked for, with the spots (and the zoom scales they were drawn at) waiting for them.
    private var waiting: [TileID: [(TileID, MKZoomScale)]] = [:]
    private var retryAfter: [TileID: CFTimeInterval] = [:]

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
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
                } else if !drawStandIn(for: t, in: rect, context: context), t.z >= 1 {
                    waitFor(t.ancestor(levels: min(3, t.z)), spot: t, scale: zoomScale)
                }
            }
        }
    }

    private static func image(_ t: TileID) -> UIImage? {
        if let i = decoded.value(for: t) { return i }
        guard let d = ImageryMemory.tiles.value(for: t), let i = UIImage(data: d)?.preparingForDisplay() else { return nil }
        decoded.set(i, for: t)
        return i
    }

    /// The nearest coarser tile in memory, enlarged, then the sharper ones under it. False if there was none.
    private func drawStandIn(for t: TileID, in rect: CGRect, context: CGContext) -> Bool {
        let memory = ImageryMemory.tiles
        var drew = false
        if let a = TileFallback.ancestor(of: t, maxLevels: 10, has: { memory.contains($0) }), let image = Self.image(a) {
            context.saveGState()
            context.clip(to: rect)
            image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
            drew = true
        }
        if t.z < TileSources.imageryMaxZoom {
            for i in 0..<4 {
                let c = TileID(z: t.z + 1, x: t.x * 2 + i % 2, y: t.y * 2 + i / 2)
                if memory.contains(c), let image = Self.image(c) {
                    image.draw(in: self.rect(for: Self.mapRect(c)))
                    drew = true
                }
            }
        }
        return drew
    }

    /// Asks for a coarse tile covering an empty spot (one request for 64 spots), first in line.
    private func waitFor(_ seed: TileID, spot: TileID, scale: MKZoomScale) {
        let now = CACurrentMediaTime()
        let start: Bool = lock.withLock {
            if let after = retryAfter[seed], now < after { return false }
            let first = waiting[seed] == nil
            waiting[seed, default: []].append((spot, scale))
            return first
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let r = await TileRepository.shared.imageryForDisplay(seed, urgent: true)
            if let r = r, r.quality == .exact { ImageryMemory.tiles.set(r.data, for: seed) }
            guard let self = self else { return }
            let spots: [(TileID, MKZoomScale)] = self.lock.withLock {
                if r == nil { self.retryAfter[seed] = CACurrentMediaTime() + 30 }
                return self.waiting.removeValue(forKey: seed) ?? []
            }
            guard r?.quality == .exact, !spots.isEmpty else { return }
            DispatchQueue.main.async {
                // Redraw the spots that were empty, at the zoom they were drawn at.
                for (t, scale) in spots { self.setNeedsDisplay(Self.mapRect(t), zoomScale: scale) }
            }
        }
    }

    func reload() {
        lock.withLock { retryAfter.removeAll() }
    }

    static func trim(to count: Int) {
        decoded.trim(to: count)
    }

    private static func mapRect(_ t: TileID) -> MKMapRect {
        let size = MKMapSize.world.width / Double(1 << t.z)
        return MKMapRect(x: Double(t.x) * size, y: Double(t.y) * size, width: size, height: size)
    }
}
