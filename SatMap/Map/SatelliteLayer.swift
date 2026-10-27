import ImageIO
import MapKit
import UIKit

/// Satellite imagery in two layers, so the map neither goes grey while tiles load nor flickers:
/// - `SatelliteTileOverlay` on top, one of MapKit's tile layers: loads each tile itself (offline areas, then Esri),
///   and MapKit fades it in as it arrives. Where it has no tile yet it is see-through.
/// - Underneath (`SatelliteMode`), what is shown meanwhile: by default `SatelliteRenderer`, which paints every spot at
///   once with the best imagery in memory (the nearest coarser tile enlarged after zooming in, the sharper ones under
///   it after zooming out). `ImageryPrefetch` keeps coarse tiles around the screen in memory for it.
/// The bottom layer replaces Apple's map entirely, so MapKit doesn't download its own tiles; only that one, as MapKit
/// paints a grey loading grid where a replacing layer has no tile yet (`MapController.satelliteModeChanged`).

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
    /// Coarse tiles asked for, with the spots (and the zoom scale each was drawn at) waiting for them.
    private var waiting: [TileID: [TileID: MKZoomScale]] = [:]
    private var retryAfter: [TileID: CFTimeInterval] = [:]

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        let world = MKMapSize.world.width
        let z = Self.level(pixelsPerMapPoint: Double(zoomScale) * Double(contentScaleFactor))
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

    /// The imagery level drawn at a zoom: tiles about 256 pixels across on screen.
    static func level(pixelsPerMapPoint: Double) -> Int {
        let pixels = MKMapSize.world.width * pixelsPerMapPoint
        guard pixels > 0, pixels.isFinite else { return 0 }
        return Int(min(Double(TileSources.imageryMaxZoom), max(0, log2(pixels / 256).rounded())))
    }

    /// Decodes tiles in memory ahead of drawing (off the drawing threads), so the next zoom or pan paints at once.
    static func prepare(_ tiles: [TileID]) {
        let wanted = tiles.filter { !decoded.contains($0) && ImageryMemory.tiles.contains($0) }
        guard !wanted.isEmpty else { return }
        Work.prepare.addOperation {
            for t in wanted where !decoded.contains(t) { _ = image(t) }
        }
    }

    private static func image(_ t: TileID) -> UIImage? {
        if let i = decoded.value(for: t) { return i }
        guard let d = ImageryMemory.tiles.value(for: t), let i = UIImage(data: d)?.preparingForDisplay() else { return nil }
        decoded.set(i, for: t)
        return i
    }

    /// The sharper tiles in memory (after zooming out): each quarter's tile one level down, else what there is of its
    /// four two levels down; under them, where they don't cover it all, the nearest coarser tile enlarged. False if
    /// there was nothing.
    private func drawStandIn(for t: TileID, in rect: CGRect, context: CGContext) -> Bool {
        let memory = ImageryMemory.tiles
        let deepest = TileSources.imageryMaxZoom
        func quarters(_ p: TileID) -> [TileID] {
            (0..<4).map { TileID(z: p.z + 1, x: p.x * 2 + $0 % 2, y: p.y * 2 + $0 / 2) }
        }
        var sharper: [TileID] = []
        var covered = t.z < deepest
        if t.z < deepest {
            for c in quarters(t) {
                if memory.contains(c) {
                    sharper.append(c)
                } else {
                    covered = false
                    if c.z < deepest { sharper += quarters(c).filter { memory.contains($0) } }
                }
            }
        }
        var drew = false
        if !covered, let a = TileFallback.ancestor(of: t, maxLevels: 10, has: { memory.contains($0) }),
           let image = Self.image(a) {
            context.saveGState()
            context.clip(to: rect)
            image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
            drew = true
        }
        for c in sharper {
            if let image = Self.image(c) {
                image.draw(in: self.rect(for: Self.mapRect(c)))
                drew = true
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
            waiting[seed, default: [:]][spot] = scale
            return first
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let r = await TileRepository.shared.imageryForDisplay(seed, urgent: true)
            if let r = r, r.quality == .exact { ImageryMemory.tiles.set(r.data, for: seed) }
            guard let self = self else { return }
            let spots: [TileID: MKZoomScale] = self.lock.withLock {
                if r == nil { self.retryAfter[seed] = CACurrentMediaTime() + 30 }
                return self.waiting.removeValue(forKey: seed) ?? [:]
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

/// When the map comes to rest: gets the imagery ready for the next zoom or pan, so its new spots have something to
/// show at once rather than grey:
/// - coarse tiles around the screen, 3 levels up over twice the screen and 6 levels up over eight times it, in the
///   background (offline areas first, then online after everything on screen);
/// - for the Drawn layer, those and the tiles on screen decoded ahead (they are what a zoom in enlarges).
enum ImageryPrefetch {
    private static let lock = NSLock()
    private static var loading = Set<TileID>()

    static func mapStopped(visible: MKMapRect, pixelsPerMapPoint: Double, decode: Bool) {
        let z = SatelliteRenderer.level(pixelsPerMapPoint: pixelsPerMapPoint)
        let onScreen = tiles(z, in: visible)
        var surround: [TileID] = []
        for (up, grow) in [(3, 0.5), (6, 3.5)] where z - up >= 0 {
            let r = visible.insetBy(dx: -visible.width * grow, dy: -visible.height * grow)
            surround += tiles(z - up, in: r)
        }
        if decode { SatelliteRenderer.prepare(onScreen + surround) }
        let memory = ImageryMemory.tiles
        let wanted: [TileID] = lock.withLock {
            let w = surround.filter { !memory.contains($0) && !loading.contains($0) }
            loading.formUnion(w)
            return w
        }
        for t in wanted {
            Task.detached(priority: .utility) {
                if let d = await TileRepository.shared.imageryInBackground(t) {
                    memory.set(d, for: t)
                    if decode { SatelliteRenderer.prepare([t]) }
                }
                _ = lock.withLock { loading.remove(t) }
            }
        }
    }

    /// The tiles of a level covering a map rect (wrapping round the world sideways).
    private static func tiles(_ z: Int, in r: MKMapRect) -> [TileID] {
        let n = 1 << z
        let size = MKMapSize.world.width / Double(n)
        let x0 = Int(floor(r.minX / size)), x1 = Int(ceil(r.maxX / size)) - 1
        let y0 = max(0, Int(floor(r.minY / size))), y1 = min(n - 1, Int(ceil(r.maxY / size)) - 1)
        guard x0 <= x1, y0 <= y1, x1 - x0 < 64, y1 - y0 < 64 else { return [] }
        var out = Set<TileID>()
        for y in y0...y1 {
            for x in x0...x1 { out.insert(TileID(z: z, x: ((x % n) + n) % n, y: y)) }
        }
        return Array(out)
    }
}
