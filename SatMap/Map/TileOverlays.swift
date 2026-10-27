import MapKit
import os
import UIKit

struct TileUnavailable: Error {}

/// MapKit stops requesting tiles past `maximumZ` (and doesn't reliably enlarge the last level),
/// so the contour overlay accepts very deep zooms and works from ancestor data itself.
private let deepestTileZoom = 25

/// Contour lines drawn on the device from elevation tiles (offline areas or online).
final class ContourTileOverlay: MKTileOverlay {
    /// Create with `ContourTileOverlay(urlTemplate: nil)`.
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = false
        minimumZ = ContourPolicy.minTileZoom
        maximumZ = deepestTileZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let n = 1 << path.z
        guard path.y >= 0, path.y < n else { return result(nil, TileUnavailable()) }
        let t = TileID(z: path.z, x: ((path.x % n) + n) % n, y: path.y)
        let scale = Double(path.contentScaleFactor)
        tileLog.debug("contour tile requested \(t.description, privacy: .public) contentScale \(scale)")
        Task.detached(priority: .userInitiated) {
            if let data = await ContourRenderer.shared.render(t, contentScale: scale) {
                result(data, nil)
            } else {
                result(nil, TileUnavailable())
            }
        }
    }
}

/// Draws 256×256-pixel transparent contour tiles. MapKit shows a tile pixel at 1/scale points (scale = the
/// tile's `contentScaleFactor`, falling back to the screen scale), so stroke widths and fonts are multiplied by it
/// and the contour interval is chosen from the equivalent web-map zoom.
final class ContourRenderer {
    static let shared = ContourRenderer()

    private let cache = NSCache<NSString, NSData>()
    /// Tiles with nothing to draw (flat ground), so they aren't recomputed.
    private let emptyCache = NSCache<NSString, NSNumber>()
    private let lock = NSLock()
    private var scale: Double = 3
    /// MapKit sometimes asks for the same tile again before the first answer: share the work.
    private var inFlight: [String: Task<Data?, Never>] = [:]

    private let minorColor = UIColor(red: 1.0, green: 0.84, blue: 0.32, alpha: 0.85)
    private let indexColor = UIColor(red: 1.0, green: 0.74, blue: 0.16, alpha: 1.0)
    private let haloColor = UIColor(white: 0, alpha: 0.38)

    /// Screen scale (2 or 3); set once from the UI, used when MapKit doesn't report a tile scale.
    var screenScale: Double {
        get { lock.withLock { scale } }
        set { lock.withLock { scale = max(1, newValue) } }
    }

    private init() {
        cache.totalCostLimit = 32 << 20
        let nc = NotificationCenter.default
        for name in [UIApplication.didReceiveMemoryWarningNotification, UIApplication.didEnterBackgroundNotification] {
            nc.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in self?.clearCaches() }
        }
    }

    func clearCaches() {
        cache.removeAllObjects()
        emptyCache.removeAllObjects()
    }

    func render(_ t: TileID, contentScale: Double) async -> Data? {
        let key = "\(t.z)/\(t.x)/\(t.y)"
        let task: Task<Data?, Never> = lock.withLock {
            if let existing = inFlight[key] { return existing }
            let new = Task.detached(priority: .userInitiated) { [self] in await self.renderNow(t, contentScale: contentScale) }
            inFlight[key] = new
            return new
        }
        let data = await task.value
        lock.withLock { inFlight[key] = nil }
        return data
    }

    private func renderNow(_ t: TileID, contentScale: Double) async -> Data? {
        let s = contentScale >= 1 ? min(contentScale, 3) : screenScale
        guard let interval = ContourPolicy.intervals(forPointZoom: ContourPolicy.pointZoom(tileZoom: t.z, screenScale: s)) else { return nil }
        let key = "\(t.z)/\(t.x)/\(t.y)" as NSString
        if let d = cache.object(forKey: key) { return d as Data }
        if emptyCache.object(forKey: key) != nil { return nil }

        let started = Date()
        let dz = ContourPolicy.demZoom(forTileZoom: t.z)
        let b = t.worldBounds
        let (tiles, complete) = await TileRepository.shared.demTiles(zoom: dz, minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY)
        let fetchedMs = Int(Date().timeIntervalSince(started) * 1000)
        guard !tiles.isEmpty else {
            // No elevation data (offline and not downloaded, or the fetch failed).
            tileLog.error("contours \(t.description, privacy: .public): no elevation tiles after \(fetchedMs) ms")
            return nil
        }

        let cells = ContourPolicy.gridCells(forTileZoom: t.z)
        // Gridding, tracing and drawing are CPU-heavy: keep them on the contour queue.
        var computeMs = 0
        let result: (png: Data?, empty: Bool)? = await Work.run(on: Work.contours) { [self] in
            let computeStart = Date()
            defer { computeMs = Int(Date().timeIntervalSince(computeStart) * 1000) }
            let sampler = DEMSampler(zoom: dz, tile: { tiles[$0] })
            guard let grid = sampler.grid(minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY,
                                          nodesX: cells + 1, nodesY: cells + 1) else { return nil }
            let lines = MarchingSquares.contours(values: grid, width: cells + 1, height: cells + 1, step: interval.step)
            guard !lines.isEmpty else { return (nil, true) }
            // Label every other tile (checkerboard) so index lines aren't over-labelled.
            return (self.draw(lines, cells: cells, interval: interval, scale: s, smooth: t.z > Terrarium.maxZoom,
                              labels: (t.x + t.y) % 2 == 0), false)
        }
        let totalMs = Int(Date().timeIntervalSince(started) * 1000)
        guard let result = result else {
            tileLog.error("contours \(t.description, privacy: .public): no grid (\(tiles.count) elevation tiles)")
            return nil
        }
        tileLog.debug("contours \(t.description, privacy: .public) scale \(s) \(result.empty ? "empty" : "drawn", privacy: .public) complete=\(complete) elevation \(fetchedMs) ms compute \(computeMs) ms total \(totalMs) ms")
        // With a neighbour missing, edges are approximated: show it, but don't keep it.
        if complete {
            if result.empty { emptyCache.setObject(1, forKey: key) }
            if let png = result.png { cache.setObject(png as NSData, forKey: key, cost: png.count) }
        }
        return result.png
    }

    private func draw(_ lines: [ContourLine], cells: Int, interval: (step: Double, index: Double),
                      scale s: Double, smooth: Bool, labels: Bool) -> Data? {
        let size = 256.0
        let k = size / Double(cells)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)

        struct Prepared {
            let points: [(x: Double, y: Double)]
            let isIndex: Bool
            let level: Double
            let path: CGPath
        }
        let prepared: [Prepared] = lines.map { line in
            var pts = line.points.map { (x: $0.x * k, y: $0.y * k) }
            if smooth { pts = ContourGeometry.chaikin(pts, closed: line.isClosed) }
            let path = CGMutablePath()
            if let first = pts.first {
                path.move(to: CGPoint(x: first.x, y: first.y))
                for p in pts.dropFirst() { path.addLine(to: CGPoint(x: p.x, y: p.y)) }
            }
            let isIndex = abs(line.level.remainder(dividingBy: interval.index)) < 0.01
            return Prepared(points: pts, isIndex: isIndex, level: line.level, path: path)
        }

        // MapKit shows a tile enlarged up to 2x until the camera reaches the next zoom level, so the base sizes
        // are on the thin side.
        let minorWidth = CGFloat(0.7 * s)
        let indexWidth = CGFloat(1.3 * s)
        let haloExtra = CGFloat(0.9 * s)

        return renderer.pngData { ctx in
            let cg = ctx.cgContext
            cg.setLineJoin(.round)
            cg.setLineCap(.round)
            // Dark halo first so lines stay visible on bright ground, then the lines.
            cg.setStrokeColor(haloColor.cgColor)
            for p in prepared {
                cg.setLineWidth((p.isIndex ? indexWidth : minorWidth) + haloExtra)
                cg.addPath(p.path)
                cg.strokePath()
            }
            for p in prepared {
                cg.setStrokeColor((p.isIndex ? indexColor : minorColor).cgColor)
                cg.setLineWidth(p.isIndex ? indexWidth : minorWidth)
                cg.addPath(p.path)
                cg.strokePath()
            }
            guard labels else { return }

            // Elevation labels along index lines.
            let font = UIFont.systemFont(ofSize: CGFloat(8.5 * s), weight: .semibold)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor(red: 1.0, green: 0.93, blue: 0.7, alpha: 1),
                .strokeColor: UIColor(white: 0, alpha: 0.75),
                .strokeWidth: -3.0,
            ]
            for p in prepared where p.isIndex {
                let text = NSAttributedString(string: "\(Int(p.level.rounded()))", attributes: attrs)
                let textSize = text.size()
                let margin = Double(textSize.width) / 2 + 4 * s
                guard margin * 2 < size,
                      let anchor = ContourGeometry.labelAnchor(p.points, minLength: Double(textSize.width) + 16 * s,
                                                               bounds: (size, size), margin: margin) else { continue }
                cg.saveGState()
                cg.translateBy(x: CGFloat(anchor.x), y: CGFloat(anchor.y))
                cg.rotate(by: CGFloat(anchor.angle))
                let box = CGRect(x: -textSize.width / 2 - CGFloat(2 * s), y: -textSize.height / 2,
                                 width: textSize.width + CGFloat(4 * s), height: textSize.height)
                // Clear the line under the label.
                cg.setBlendMode(.clear)
                cg.fill(box)
                cg.setBlendMode(.normal)
                text.draw(at: CGPoint(x: -textSize.width / 2, y: -textSize.height / 2))
                cg.restoreGState()
            }
        }
    }
}
