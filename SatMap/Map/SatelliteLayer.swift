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
/// place a coarse tile three levels up is asked for too: it covers 64 tiles and arrives quickly.
/// Tiles come from offline areas or Esri through `TileRepository`. MapKit calls `draw` on several threads at once.
final class SatelliteRenderer: MKOverlayRenderer {
    private struct Tile {
        let image: UIImage
        /// False for part of an ancestor enlarged in its place (asked for again by `reload`).
        let exact: Bool
        let arrived: CFTimeInterval
    }

    /// Decoded tiles (256 KB each), shared so they outlive a renderer.
    private static let cache = RecentCache<TileID, Tile>(capacity: 240)
    private static let fadeDuration: CFTimeInterval = 0.25
    /// How far up and down to look for a stand-in.
    private static let ancestorLevels = 12
    private static let descendantLevels = 3

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
        let z = TileFallback.zoom(pointsPerWorld: world * Double(zoomScale), maxZoom: TileSources.imageryMaxZoom)
        let tiles = TileFallback.tiles(minX: mapRect.minX / world, minY: mapRect.minY / world,
                                       maxX: mapRect.maxX / world, maxY: mapRect.maxY / world, z: z)
        let now = CACurrentMediaTime()
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        for t in tiles {
            let rect = self.rect(for: Self.mapRect(t))
            let own = Self.cache.value(for: t)
            let alpha = own.map { min(1, (now - $0.arrived) / Self.fadeDuration) } ?? 0
            if alpha < 1 { drawStandIn(for: t, in: rect, context: context) }
            if let own = own {
                own.image.draw(in: rect, blendMode: .normal, alpha: CGFloat(alpha))
                if alpha < 1 { redrawSoon(t) }
            } else {
                load(t)
            }
        }
    }

    /// The nearest coarser tile in memory, enlarged, then sharper ones from deeper levels on top.
    private func drawStandIn(for t: TileID, in rect: CGRect, context: CGContext) {
        let cache = Self.cache
        let ancestor = TileFallback.ancestor(of: t, maxLevels: Self.ancestorLevels) { cache.contains($0) }
        if let a = ancestor, let tile = cache.value(for: a) {
            context.saveGState()
            context.clip(to: rect)
            context.interpolationQuality = .high
            tile.image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
        }
        // A new place: a tile three levels up covers 64 of these and fills the screen quickly.
        if t.z >= 3, (ancestor?.z ?? -1) < t.z - 3 { load(t.ancestor(levels: 3)) }
        for d in TileFallback.descendants(of: t, levels: Self.descendantLevels, has: { cache.contains($0) }) {
            cache.value(for: d)?.image.draw(in: self.rect(for: Self.mapRect(d)))
        }
    }

    private func load(_ t: TileID) {
        let now = CACurrentMediaTime()
        let start: Bool = lock.withLock {
            if let after = retryAfter[t], now < after { return false }
            return loading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            var tile: Tile?
            if let r = await TileRepository.shared.imageryForDisplay(t),
               let image = await Work.run(on: Work.images, { UIImage(data: r.data)?.preparingForDisplay() }) {
                tile = Tile(image: image, exact: r.exact, arrived: CACurrentMediaTime())
                Self.cache.set(tile!, for: t)
            }
            guard let self = self else { return }
            self.lock.withLock {
                self.loading.remove(t)
                if tile == nil { self.retryAfter[t] = CACurrentMediaTime() + 30 }
            }
            if tile != nil {
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

    /// Asks again for tiles that failed and replaces enlarged stand-ins: after the network comes back, or when an
    /// offline area has more.
    func reload() {
        Self.cache.remove { _, tile in !tile.exact }
        lock.withLock { retryAfter.removeAll() }
        setNeedsDisplay()
    }

    /// Frees most of the memory in the background, keeping the tiles last used for a quick return.
    static func trimForBackground() {
        cache.trim(to: 48)
    }

    private static func mapRect(_ t: TileID) -> MKMapRect {
        let size = MKMapSize.world.width / Double(1 << t.z)
        return MKMapRect(x: Double(t.x) * size, y: Double(t.y) * size, width: size, height: size)
    }
}
