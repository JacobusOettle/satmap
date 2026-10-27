import MapKit
import UIKit

/// The whole recorded track as a single overlay. New points are appended in place and only the area around
/// the new segment is redrawn (adding/removing polylines on every fix makes tile overlays flicker).
final class TrackOverlay: NSObject, MKOverlay {
    /// Up to ~512 points with their bounds; a new segment (after a gap) always starts a new chunk.
    struct Chunk {
        var points: [MKMapPoint]
        var rect: MKMapRect
        /// Length along the segment (map points) before this chunk's first point, keeping the dots evenly
        /// spaced across chunk and tile boundaries.
        var startDistance: Double
        var length: Double
    }

    private let lock = NSLock()
    private var chunks: [Chunk] = []
    private let chunkLimit = 512

    let coordinate = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    let boundingMapRect = MKMapRect.world

    var snapshot: [Chunk] {
        lock.withLock { chunks }
    }

    func reset(_ track: [TrackPoint]) {
        lock.withLock {
            chunks = []
            for p in track { _ = appendLocked(p) }
        }
    }

    /// Appends a point and returns the map rect that needs redrawing.
    @discardableResult
    func append(_ p: TrackPoint) -> MKMapRect {
        lock.withLock { appendLocked(p) }
    }

    private func appendLocked(_ p: TrackPoint) -> MKMapRect {
        let m = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon))
        let dot = MKMapRect(origin: m, size: MKMapSize(width: 0, height: 0))
        if p.startsSegment || chunks.isEmpty {
            chunks.append(Chunk(points: [m], rect: dot, startDistance: 0, length: 0))
            return dot
        }
        var last = chunks[chunks.count - 1]
        let prev = last.points[last.points.count - 1]
        let segment = MKMapRect(x: min(prev.x, m.x), y: min(prev.y, m.y), width: abs(prev.x - m.x), height: abs(prev.y - m.y))
        let d = hypot(prev.x - m.x, prev.y - m.y)
        if last.points.count >= chunkLimit {
            // Continue the same segment in a new chunk that starts at the previous point.
            chunks.append(Chunk(points: [prev, m], rect: segment, startDistance: last.startDistance + last.length, length: d))
        } else {
            last.points.append(m)
            last.rect = last.rect.union(segment)
            last.length += d
            chunks[chunks.count - 1] = last
        }
        return segment
    }
}

final class TrackRenderer: MKOverlayRenderer {
    private let color = UIColor(red: 0.0, green: 0.9, blue: 1.0, alpha: 1).cgColor

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let track = overlay as? TrackOverlay else { return }
        let width = 4.0 / Double(zoomScale)       // 4 points on screen
        let gap = 9.0 / Double(zoomScale)         // dot spacing
        let minStep = 1.5 / Double(zoomScale)     // skip points closer than 1.5 points
        let clip = mapRect.insetBy(dx: -width * 2, dy: -width * 2)

        context.setStrokeColor(color)
        context.setLineWidth(CGFloat(width))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        for chunk in track.snapshot where chunk.points.count >= 2 && chunk.rect.insetBy(dx: -width, dy: -width).intersects(clip) {
            let path = CGMutablePath()
            var last = chunk.points[0]
            path.move(to: point(for: last))
            for (i, p) in chunk.points.enumerated().dropFirst() {
                let isLast = i == chunk.points.count - 1
                if !isLast && abs(p.x - last.x) < minStep && abs(p.y - last.y) < minStep { continue }
                path.addLine(to: point(for: p))
                last = p
            }
            // Zero-length dashes with round caps draw dots; the phase keeps them aligned between tiles.
            context.setLineDash(phase: CGFloat(chunk.startDistance.truncatingRemainder(dividingBy: gap)), lengths: [0, CGFloat(gap)])
            context.addPath(path)
            context.strokePath()
        }
    }
}

/// The dashed measuring line: from you to the marker, or through the points A → B → C …, joined back to A (and
/// lightly filled) once the shape is closed.
final class MeasureLineOverlay: NSObject, MKOverlay {
    private let lock = NSLock()
    private var vertices: [MKMapPoint] = []
    private var closed = false

    let coordinate = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    let boundingMapRect = MKMapRect.world

    var path: (vertices: [MKMapPoint], closed: Bool) {
        lock.withLock { (vertices, closed) }
    }

    /// Sets the line (fewer than two points hides it) and returns small map rects along the old and new line that
    /// need redrawing (a long diagonal line's bounding box would be most of the screen), plus the shape's bounding
    /// box when it is or was filled.
    func set(_ coordinates: [CLLocationCoordinate2D], closed: Bool) -> [MKMapRect] {
        lock.withLock {
            let new = coordinates.count >= 2 ? coordinates.map(MKMapPoint.init) : []
            let newClosed = closed && new.count >= 3
            // Unchanged (the line is set again on every GPS fix): nothing to redraw.
            if newClosed == self.closed, new.count == vertices.count,
               zip(new, vertices).allSatisfy({ $0.0.x == $0.1.x && $0.0.y == $0.1.y }) { return [] }
            var dirty = Self.dirtyRects(vertices, closed: self.closed)
            vertices = new
            self.closed = newClosed
            dirty += Self.dirtyRects(vertices, closed: self.closed)
            return dirty
        }
    }

    private static func dirtyRects(_ v: [MKMapPoint], closed: Bool) -> [MKMapRect] {
        guard v.count >= 2 else { return [] }
        let ring = closed ? v + [v[0]] : v
        var rects = zip(ring, ring.dropFirst()).flatMap { band($0.0, $0.1) }
        if closed {
            let xs = v.map(\.x), ys = v.map(\.y)
            rects.append(MKMapRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!))
        }
        return rects
    }

    /// The segment split into 16 pieces, each piece's bounding box.
    private static func band(_ a: MKMapPoint, _ b: MKMapPoint) -> [MKMapRect] {
        let n = 16
        return (0..<n).map { i in
            let t0 = Double(i) / Double(n), t1 = Double(i + 1) / Double(n)
            let x0 = a.x + (b.x - a.x) * t0, y0 = a.y + (b.y - a.y) * t0
            let x1 = a.x + (b.x - a.x) * t1, y1 = a.y + (b.y - a.y) * t1
            return MKMapRect(x: min(x0, x1), y: min(y0, y1), width: abs(x1 - x0), height: abs(y1 - y0))
        }
    }
}

final class MeasureLineRenderer: MKOverlayRenderer {
    private let color = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 1).cgColor
    private let fill = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 0.15).cgColor

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let (v, closed) = (overlay as? MeasureLineOverlay)?.path, v.count >= 2 else { return }
        if closed {
            context.setFillColor(fill)
            context.move(to: point(for: v[0]))
            for p in v.dropFirst() { context.addLine(to: point(for: p)) }
            context.closePath()
            context.fillPath()
        }
        let width = 3.0 / Double(zoomScale)
        let dash = 10.0 / Double(zoomScale), gap = 7.0 / Double(zoomScale)
        context.setStrokeColor(color)
        context.setLineWidth(CGFloat(width))
        context.setLineCap(.butt)
        // Each segment: only the part inside this tile (Liang–Barsky clip), with the dash phase measured along the
        // whole line from its start so dashes line up across tiles.
        let r = mapRect.insetBy(dx: -width, dy: -width)
        let ring = closed ? v + [v[0]] : v
        var along = 0.0
        for (a, b) in zip(ring, ring.dropFirst()) {
            let dx = b.x - a.x, dy = b.y - a.y
            defer { along += hypot(dx, dy) }
            var t0 = 0.0, t1 = 1.0
            var outside = false
            for (p, q) in [(-dx, a.x - r.minX), (dx, r.maxX - a.x), (-dy, a.y - r.minY), (dy, r.maxY - a.y)] {
                if p == 0 {
                    if q < 0 { outside = true }
                } else {
                    let t = q / p
                    if p < 0 { t0 = max(t0, t) } else { t1 = min(t1, t) }
                }
            }
            guard !outside, t0 < t1 else { continue }
            let start = MKMapPoint(x: a.x + dx * t0, y: a.y + dy * t0)
            let end = MKMapPoint(x: a.x + dx * t1, y: a.y + dy * t1)
            let phase = (along + hypot(dx, dy) * t0).truncatingRemainder(dividingBy: dash + gap)
            context.setLineDash(phase: CGFloat(phase), lengths: [CGFloat(dash), CGFloat(gap)])
            context.move(to: point(for: start))
            context.addLine(to: point(for: end))
            context.strokePath()
        }
    }
}
