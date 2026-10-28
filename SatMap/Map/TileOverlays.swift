import MapKit
import os
import UIKit

struct TileUnavailable: Error {}

/// Draws 256×256-pixel transparent contour tiles, which the imagery layer draws over the imagery
/// (`SatelliteRenderer`). Stroke widths and fonts are multiplied by the scale it gives (the screen's), and the
/// contour interval is chosen from the equivalent web-map zoom.
final class ContourRenderer {
    static let shared = ContourRenderer()

    private let cache = NSCache<NSString, NSData>()
    /// Tiles with nothing to draw (flat ground), so they aren't recomputed.
    private let emptyCache = NSCache<NSString, NSNumber>()
    private let lock = NSLock()
    private var scale: Double = 3
    /// A tile asked for again before the first answer shares its work.
    private var inFlight: [String: Task<Tile, Never>] = [:]

    private let minorColor = UIColor(red: 1.0, green: 0.84, blue: 0.32, alpha: 0.85)
    private let indexColor = UIColor(red: 1.0, green: 0.74, blue: 0.16, alpha: 1.0)
    private let haloColor = UIColor(white: 0, alpha: 0.38)

    /// Screen scale (2 or 3); set once from the UI, used when no scale is given.
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

    /// What there is to draw on a tile.
    enum Tile {
        /// The lines (PNG); not `complete` when an elevation tile around it was missing, so its edges are approximated.
        case lines(Data, complete: Bool)
        /// Nothing: flat ground, or a zoom without contours.
        case blank
        /// No elevation for it (offline and not downloaded, or the fetch failed).
        case unavailable
    }

    func tile(_ t: TileID, contentScale: Double) async -> Tile {
        let key = "\(t.z)/\(t.x)/\(t.y)"
        let task: Task<Tile, Never> = lock.withLock {
            if let existing = inFlight[key] { return existing }
            let new = Task.detached(priority: .userInitiated) { [self] in await self.renderNow(t, contentScale: contentScale) }
            inFlight[key] = new
            return new
        }
        let tile = await task.value
        lock.withLock { inFlight[key] = nil }
        return tile
    }

    private func renderNow(_ t: TileID, contentScale: Double) async -> Tile {
        let s = contentScale >= 1 ? min(contentScale, 3) : screenScale
        guard let interval = ContourPolicy.intervals(forPointZoom: ContourPolicy.pointZoom(tileZoom: t.z, screenScale: s)) else { return .blank }
        let key = "\(t.z)/\(t.x)/\(t.y)" as NSString
        if let d = cache.object(forKey: key) { return .lines(d as Data, complete: true) }
        if emptyCache.object(forKey: key) != nil { return .blank }

        let started = Date()
        let dz = ContourPolicy.demZoom(forTileZoom: t.z)
        let b = t.worldBounds
        let (tiles, complete) = await TileRepository.shared.demTiles(zoom: dz, minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY)
        let fetchedMs = Int(Date().timeIntervalSince(started) * 1000)
        guard !tiles.isEmpty else {
            // No elevation data (offline and not downloaded, or the fetch failed).
            tileLog.error("contours \(t.description, privacy: .public): no elevation tiles after \(fetchedMs) ms")
            return .unavailable
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
            return .unavailable
        }
        tileLog.debug("contours \(t.description, privacy: .public) scale \(s) \(result.empty ? "empty" : "drawn", privacy: .public) complete=\(complete) elevation \(fetchedMs) ms compute \(computeMs) ms total \(totalMs) ms")
        // With a neighbour missing, edges are approximated: show it, but don't keep it.
        if complete {
            if result.empty { emptyCache.setObject(1, forKey: key) }
            if let png = result.png { cache.setObject(png as NSData, forKey: key, cost: png.count) }
        }
        if result.empty { return .blank }
        guard let png = result.png else { return .unavailable }
        return .lines(png, complete: complete)
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

/// The heights of a tile for its elevation colours (`ElevationColours`): a grid of nodes from edge to edge, sampled
/// from the elevation tiles of its zoom (as its contour lines are), which the imagery layer colours and draws.
final class HeightGrids {
    static let shared = HeightGrids()

    private let lock = NSLock()
    /// A tile asked for again before the first answer shares its work.
    private var inFlight: [TileID: Task<Tile, Never>] = [:]

    enum Tile {
        /// The heights; not `complete` when an elevation tile around it was missing, so its edges are approximated.
        case grid(ElevationGrid, complete: Bool)
        /// No elevation for it (offline and not downloaded, or the fetch failed).
        case unavailable
    }

    func tile(_ t: TileID) async -> Tile {
        let task: Task<Tile, Never> = lock.withLock {
            if let existing = inFlight[t] { return existing }
            let new = Task.detached(priority: .userInitiated) { await Self.load(t) }
            inFlight[t] = new
            return new
        }
        let tile = await task.value
        lock.withLock { inFlight[t] = nil }
        return tile
    }

    private static func load(_ t: TileID) async -> Tile {
        let dz = ContourPolicy.demZoom(forTileZoom: t.z)
        let b = t.worldBounds
        let (tiles, complete) = await TileRepository.shared.demTiles(zoom: dz, minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY)
        guard !tiles.isEmpty else {
            tileLog.error("heights \(t.description, privacy: .public): no elevation tiles")
            return .unavailable
        }
        let nodes = ElevationColours.cells + 1
        let values = await Work.run(on: Work.contours) {
            DEMSampler(zoom: dz, tile: { tiles[$0] }).grid(minX: b.minX, minY: b.minY, maxX: b.maxX, maxY: b.maxY,
                                                          nodesX: nodes, nodesY: nodes)
        }
        guard let values = values else { return .unavailable }
        tileLog.debug("heights \(t.description, privacy: .public) complete=\(complete)")
        return .grid(ElevationGrid(nodes: nodes, values: values), complete: complete)
    }
}
