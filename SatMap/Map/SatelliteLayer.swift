import MapKit
import UIKit

/// The satellite imagery layer. Replaces Apple's map entirely, so MapKit doesn't download its own tiles (or show its
/// logo). A tile overlay, because MapKit only leaves out its own map for those; drawn by `SatelliteRenderer`.
final class SatelliteOverlay: MKTileOverlay {
    init() {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
    }

    // Not used: SatelliteRenderer loads the tiles itself.
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        result(nil, TileUnavailable())
    }
}

/// Draws the satellite imagery itself rather than with MapKit's tile renderer, which shows black wherever a tile of the
/// new zoom level hasn't loaded yet. Here every spot shows the best imagery already in memory (the nearest coarser tile
/// enlarged, or sharper tiles from before zooming out), and its own tile fades in over it when it arrives. In a new
/// place a coarse tile three levels up is asked for first: it covers 64 tiles and arrives quickly.
/// Tiles come from offline areas or Esri through `TileRepository`. MapKit calls `draw` on several threads at once.
final class SatelliteRenderer: MKOverlayRenderer {
    private struct Tile {
        let image: UIImage
        let quality: TileRepository.ImageryQuality
        /// `reload` count when it was loaded: an enlargement made for now is asked for again after a reload.
        let generation: Int
        let arrived: CFTimeInterval
        /// The image it replaced, shown under it while it fades in.
        var previous: UIImage?
    }

    /// Decoded tiles (256 KB each), shared so they outlive a renderer.
    private static let cache = RecentCache<TileID, Tile>(capacity: 240)
    private static let fadeDuration: CFTimeInterval = 0.25
    /// How far up and down to look for a stand-in.
    private static let ancestorLevels = 12
    private static let descendantLevels = 3

    private static let stateLock = NSLock()
    private static var generation = 0
    /// In the background no tiles are loaded (nothing is drawn there) and the cache stays small.
    private static var inBackground = false

    private let lock = NSLock()
    private var loading = Set<TileID>()
    /// Tiles that couldn't be loaded aren't asked for again before this time (or until `reload`).
    private var retryAfter: [TileID: CFTimeInterval] = [:]
    private var fading = Set<TileID>()
    private var redrawScheduled = false

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true // there is always something to draw, if only an enlarged coarser tile
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        let world = MKMapSize.world.width
        // One image pixel per screen pixel, as MapKit's own tile overlays.
        let z = TileFallback.zoom(pixelsPerWorld: world * Double(zoomScale) * Double(contentScaleFactor),
                                  maxZoom: TileSources.imageryMaxZoom)
        let tiles = TileFallback.tiles(minX: mapRect.minX / world, minY: mapRect.minY / world,
                                       maxX: mapRect.maxX / world, maxY: mapRect.maxY / world, z: z)
        let now = CACurrentMediaTime()
        let generation = Self.stateLock.withLock { Self.generation }
        var seeds = Set<TileID>()
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        for t in tiles {
            let rect = self.rect(for: Self.mapRect(t))
            guard let own = Self.cache.value(for: t) else {
                if let seed = drawStandIn(for: t, in: rect, context: context) { seeds.insert(seed) }
                load(t)
                continue
            }
            let alpha = min(1, (now - own.arrived) / Self.fadeDuration)
            if alpha < 1 {
                if let previous = own.previous {
                    previous.draw(in: rect)
                } else {
                    _ = drawStandIn(for: t, in: rect, context: context)
                }
                own.image.draw(in: rect, blendMode: .normal, alpha: CGFloat(alpha))
                redrawSoon(t)
            } else {
                own.image.draw(in: rect)
                if own.previous != nil {
                    var done = own
                    done.previous = nil
                    Self.cache.set(done, for: t)
                }
            }
            // Shown for now: replaced once the network is back (it stays until the new one is here).
            if own.quality == .enlargedForNow && own.generation < generation { load(t) }
        }
        // Asked for last, so they are served first, and before the other requests.
        for seed in seeds { load(seed, urgent: true) }
    }

    /// The nearest coarser tile in memory, enlarged, then sharper ones from deeper levels on top. Returns a coarse
    /// tile to ask for when there's no close one in memory (a new place).
    private func drawStandIn(for t: TileID, in rect: CGRect, context: CGContext) -> TileID? {
        let cache = Self.cache
        let ancestor = TileFallback.ancestor(of: t, maxLevels: Self.ancestorLevels) { cache.contains($0) }
        if let a = ancestor, let tile = cache.value(for: a) {
            context.saveGState()
            context.clip(to: rect)
            context.interpolationQuality = .high
            tile.image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
        }
        for d in TileFallback.descendants(of: t, levels: Self.descendantLevels, has: { cache.contains($0) }) {
            cache.value(for: d)?.image.draw(in: self.rect(for: Self.mapRect(d)))
        }
        // A tile three levels up covers 64 of these and fills the screen quickly.
        if t.z >= 3, (ancestor?.z ?? -1) < t.z - 3 { return t.ancestor(levels: 3) }
        return nil
    }

    private func load(_ t: TileID, urgent: Bool = false) {
        let now = CACurrentMediaTime()
        let (background, generation) = Self.stateLock.withLock { (Self.inBackground, Self.generation) }
        guard !background else { return }
        let start: Bool = lock.withLock {
            if let after = retryAfter[t], now < after { return false }
            return loading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            var loaded = false
            if let r = await TileRepository.shared.imageryForDisplay(t, urgent: urgent),
               let image = await Work.run(on: Work.images, { UIImage(data: r.data)?.preparingForDisplay() }),
               !Self.stateLock.withLock({ Self.inBackground }) {
                let previous = Self.cache.value(for: t)?.image
                Self.cache.set(Tile(image: image, quality: r.quality, generation: generation, arrived: CACurrentMediaTime(),
                                    previous: previous), for: t)
                loaded = true
            }
            guard let self = self else { return }
            self.lock.withLock {
                self.loading.remove(t)
                if !loaded { self.retryAfter[t] = CACurrentMediaTime() + 30 }
            }
            if loaded {
                DispatchQueue.main.async { self.setNeedsDisplay(Self.mapRect(t)) }
            }
        }
    }

    /// Fading in: draw this tile again in a moment.
    private func redrawSoon(_ t: TileID) {
        let schedule: Bool = lock.withLock {
            fading.insert(t)
            if redrawScheduled { return false }
            redrawScheduled = true
            return true
        }
        guard schedule else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0 / 30) { [weak self] in
            guard let self = self else { return }
            let tiles: Set<TileID> = self.lock.withLock {
                let tiles = self.fading
                self.fading = []
                self.redrawScheduled = false
                return tiles
            }
            for t in tiles { self.setNeedsDisplay(Self.mapRect(t)) }
        }
    }

    /// Asks again for tiles that failed and for enlargements shown in their place (after the network comes back, or
    /// when an offline area has more). What is on screen stays until its replacement has loaded.
    func reload() {
        Self.stateLock.withLock { Self.generation += 1 }
        lock.withLock { retryAfter.removeAll() }
        setNeedsDisplay()
    }

    /// In the background: no loading, and most of the memory freed (two screens' worth kept for a quick return).
    static func enteredBackground() {
        stateLock.withLock { inBackground = true }
        cache.trim(to: 96)
    }

    static func enteredForeground() {
        stateLock.withLock { inBackground = false }
    }

    /// iOS is short of memory.
    static func trimMemory() {
        cache.trim(to: stateLock.withLock { inBackground } ? 0 : 48)
    }

    private static func mapRect(_ t: TileID) -> MKMapRect {
        let size = MKMapSize.world.width / Double(1 << t.z)
        return MKMapRect(x: Double(t.x) * size, y: Double(t.y) * size, width: size, height: size)
    }
}
