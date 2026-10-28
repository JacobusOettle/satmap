import MapKit
import SwiftUI
import UIKit

/// A point being moved precisely: the tapped spot's pin, or a measuring point (by its index).
enum MovablePoint: Equatable {
    case selected
    case measure(Int)
}

/// Moving a placed point precisely. Pressed and held, the point gets a glass square above the finger showing 15 m × 15 m
/// of ground around it, magnified (the imagery, the blocks and the measuring line). While the finger stays down the
/// point follows it at the square's scale, up to 5 m from the square's middle; beyond that the square and the map
/// under it scroll that way, faster the further the finger goes, carrying the point along.
final class PointAdjuster: NSObject {
    /// Ground across the square, and how far the point goes from its middle before the map scrolls.
    static let metresAcross = 15.0
    static let freeRadius = 5.0

    /// Told where the point is as it moves (`done`: the finger was lifted).
    var onMove: ((MovablePoint, GeoPoint, _ done: Bool) -> Void)?

    private unowned let mapView: MKMapView
    private let loupe = LoupeView()
    private var frameView: UIView?
    private var link: CADisplayLink?

    private var which: MovablePoint?
    private var start = MKMapPoint()
    private var touchStart = CGPoint.zero
    private var finger = CGVector.zero
    /// The ground at the square's middle, and the point.
    private var centre = MKMapPoint()
    private var point = MKMapPoint()
    /// Map points per metre there, map points per point of the square, and the map's directions for the screen's
    /// right and down (the map may be turned).
    private var perMetre = 1.0
    private var perLoupePoint = 1.0
    private var right = (x: 1.0, y: 0.0)
    private var down = (x: 0.0, y: 1.0)
    private var lastTick: CFTimeInterval = 0

    init(mapView: MKMapView) {
        self.mapView = mapView
        super.init()
    }

    var isActive: Bool { which != nil }

    /// The finger has held the point long enough: the square appears above it (or below, near the top of the screen).
    /// `scene` is what the square draws besides the imagery.
    func begin(_ which: MovablePoint, at p: GeoPoint, touch: CGPoint, in window: UIWindow, scene: LoupeView.Scene) {
        end(cancelled: true)
        self.which = which
        start = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon))
        centre = start
        point = start
        touchStart = touch
        finger = .zero
        perMetre = MKMapPointsPerMeterAtLatitude(p.lat)

        // The map's directions for the screen's axes, from the map itself.
        let c = CGPoint(x: mapView.bounds.midX, y: mapView.bounds.midY)
        let m0 = MKMapPoint(mapView.convert(c, toCoordinateFrom: mapView))
        let mx = MKMapPoint(mapView.convert(CGPoint(x: c.x + 100, y: c.y), toCoordinateFrom: mapView))
        let my = MKMapPoint(mapView.convert(CGPoint(x: c.x, y: c.y + 100), toCoordinateFrom: mapView))
        right = Self.unit(mx.x - m0.x, mx.y - m0.y) ?? (1, 0)
        down = Self.unit(my.x - m0.x, my.y - m0.y) ?? (0, 1)

        let side: CGFloat = 232, border: CGFloat = 8
        let content = side - 2 * border
        perLoupePoint = Self.metresAcross * perMetre / Double(content)

        let frame = Self.makeFrame(side: side)
        frame.center = Self.place(side: side, gap: 44, touch: touch, in: window.bounds.inset(by: window.safeAreaInsets))
        loupe.frame = CGRect(x: border, y: border, width: content, height: content)
        loupe.layer.cornerRadius = 24
        loupe.layer.cornerCurve = .continuous
        loupe.clipsToBounds = true
        loupe.scene = scene
        loupe.heading = mapView.camera.heading
        loupe.marker = which == .selected ? .pin : .measure
        frame.addSubview(loupe)
        window.addSubview(frame)
        frameView = frame
        update()

        frame.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
        frame.alpha = 0
        UIView.animate(withDuration: 0.25, delay: 0, usingSpringWithDamping: 0.75, initialSpringVelocity: 0, options: [],
                       animations: {
                           frame.transform = .identity
                           frame.alpha = 1
                       }, completion: nil)
        lastTick = CACurrentMediaTime()
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    /// The finger moved (window coordinates).
    func moved(touch: CGPoint) {
        guard which != nil else { return }
        finger = CGVector(dx: touch.x - touchStart.x, dy: touch.y - touchStart.y)
        place()
    }

    /// The finger was lifted (or the press cancelled: the point goes back).
    func end(cancelled: Bool = false) {
        link?.invalidate()
        link = nil
        if let frame = frameView {
            frameView = nil
            UIView.animate(withDuration: 0.18, animations: {
                frame.alpha = 0
                frame.transform = CGAffineTransform(scaleX: 0.85, y: 0.85)
            }, completion: { _ in frame.removeFromSuperview() })
        }
        guard let w = which else { return }
        which = nil
        let final = cancelled ? start : point
        onMove?(w, GeoPoint(lat: final.coordinate.latitude, lon: final.coordinate.longitude), true)
    }

    /// The point from the finger: as far as it went at the square's scale, up to 5 m from the square's middle.
    private func place() {
        guard let w = which else { return }
        let (dir, metres) = fingerGround()
        let along = min(metres, Self.freeRadius) * perMetre
        point = MKMapPoint(x: centre.x + dir.x * along, y: centre.y + dir.y * along)
        update()
        onMove?(w, GeoPoint(lat: point.coordinate.latitude, lon: point.coordinate.longitude), false)
    }

    /// Beyond 5 m the square and the map under it scroll toward the finger: 1.5 m/s for every metre further, up to
    /// 20 m/s.
    @objc private func tick(_ l: CADisplayLink) {
        let now = l.timestamp
        let dt = min(0.1, max(0, now - lastTick))
        lastTick = now
        let (dir, metres) = fingerGround()
        guard metres > Self.freeRadius else { return }
        let step = min(20, 1.5 * (metres - Self.freeRadius)) * dt * perMetre
        let dx = dir.x * step, dy = dir.y * step
        centre = MKMapPoint(x: centre.x + dx, y: centre.y + dy)
        let c = MKMapPoint(mapView.centerCoordinate)
        mapView.setCenter(MKMapPoint(x: c.x + dx, y: c.y + dy).coordinate, animated: false)
        place()
    }

    /// The finger's way from where it pressed, on the ground: a direction in map points and a distance in metres at
    /// the square's scale.
    private func fingerGround() -> (dir: (x: Double, y: Double), metres: Double) {
        let gx = Double(finger.dx) * right.x + Double(finger.dy) * down.x
        let gy = Double(finger.dx) * right.y + Double(finger.dy) * down.y
        guard let dir = Self.unit(gx, gy) else { return ((0, 0), 0) }
        return (dir, hypot(Double(finger.dx), Double(finger.dy)) * perLoupePoint / perMetre)
    }

    private func update() {
        loupe.centre = centre
        loupe.point = point
        loupe.metresAcross = Self.metresAcross
        loupe.moved = start.distance(to: point)
        loupe.setNeedsDisplay()
    }

    /// Where the square goes: above the finger, else below it, else (landscape) beside it, on the side with more room;
    /// always on screen, and never under the finger when it fits anywhere.
    private static func place(side: CGFloat, gap: CGFloat, touch: CGPoint, in area: CGRect) -> CGPoint {
        let r = area.insetBy(dx: 8, dy: 8)
        let half = side / 2
        func clampX(_ x: CGFloat) -> CGFloat { min(max(x, r.minX + half), r.maxX - half) }
        func clampY(_ y: CGFloat) -> CGFloat { min(max(y, r.minY + half), r.maxY - half) }
        if touch.y - gap - side >= r.minY { return CGPoint(x: clampX(touch.x), y: touch.y - gap - half) }
        if touch.y + gap + side <= r.maxY { return CGPoint(x: clampX(touch.x), y: touch.y + gap + half) }
        let left = touch.x - r.minX, right = r.maxX - touch.x
        let x = left >= right ? touch.x - gap - half : touch.x + gap + half
        return CGPoint(x: clampX(x), y: clampY(touch.y))
    }

    private static func unit(_ x: Double, _ y: Double) -> (x: Double, y: Double)? {
        let l = hypot(x, y)
        return l > 1e-12 ? (x / l, y / l) : nil
    }

    /// The square's frame: Liquid Glass (iOS 26), else a frosted material, with a shadow.
    private static func makeFrame(side: CGFloat) -> UIView {
        let frame = LoupeFrame(frame: CGRect(x: 0, y: 0, width: side, height: side))
        frame.isUserInteractionEnabled = false
        frame.layer.shadowColor = UIColor.black.cgColor
        frame.layer.shadowOpacity = 0.35
        frame.layer.shadowRadius = 14
        frame.layer.shadowOffset = CGSize(width: 0, height: 6)
        // The shape given, so the shadow isn't worked out again from the content at every frame.
        frame.layer.shadowPath = UIBezierPath(roundedRect: frame.bounds, cornerRadius: 32).cgPath
        var glass: UIView?
        #if compiler(>=6.2) // the iOS 26 SDK (Xcode 26)
        if #available(iOS 26.0, *) {
            let host = UIHostingController(rootView: LoupeGlass())
            host.view.backgroundColor = .clear
            frame.host = host
            glass = host.view
        }
        #endif
        let background = glass ?? {
            let v = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterialDark))
            v.layer.cornerRadius = 32
            v.layer.cornerCurve = .continuous
            v.clipsToBounds = true
            v.layer.borderColor = UIColor(white: 1, alpha: 0.45).cgColor
            v.layer.borderWidth = 1
            return v
        }()
        background.frame = frame.bounds
        background.isUserInteractionEnabled = false
        frame.addSubview(background)
        return frame
    }
}

/// The square's frame, keeping what draws its glass.
private final class LoupeFrame: UIView {
    var host: AnyObject?
}

#if compiler(>=6.2)
/// The square's Liquid Glass frame.
@available(iOS 26.0, *)
private struct LoupeGlass: View {
    var body: some View {
        Color.clear
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
    }
}
#endif

/// The magnified ground inside the square: the imagery (`SatelliteRenderer.drawImagery`) turned as the map is, the
/// blocks, the measuring line and points, you, the point being moved, a ring 5 m from the middle (beyond it the map
/// scrolls) and how far the point has moved.
final class LoupeView: UIView {
    /// What is drawn over the imagery, in map points: lines (with their colour), other points, and you.
    struct Scene {
        var lines: [(points: [MKMapPoint], colour: UIColor, closed: Bool, bounds: MKMapRect)] = []
        var dots: [(point: MKMapPoint, colour: UIColor)] = []
        /// The measuring line through the points (the one being moved at `moving`), and from you for a single point.
        var measure: [MKMapPoint] = []
        var measureClosed = false
        var moving: Int?
        var user: MKMapPoint?
    }

    enum Marker { case pin, measure }

    var scene = Scene()
    var centre = MKMapPoint()
    var point = MKMapPoint()
    var heading: CLLocationDirection = 0
    var metresAcross = 15.0
    var moved = 0.0
    var marker = Marker.measure
    /// The imagery level drawn, so tiles that arrive for it show (`DrawnMap.announce`).
    private let map = DrawnMap()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false // its corners are rounded off
        backgroundColor = .black
        contentMode = .redraw
        map.onArrival = { [weak self] _, content in
            if content == .imagery { self?.setNeedsDisplay() }
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), bounds.width > 0 else { return }
        let side = Double(bounds.width)
        let perMetre = MKMapPointsPerMeterAtLatitude(centre.coordinate.latitude)
        let k = side / (metresAcross * perMetre) // points per map point
        let measureColour = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 1)
        func local(_ m: MKMapPoint) -> CGPoint {
            CGPoint(x: (m.x - centre.x) * k, y: (m.y - centre.y) * k)
        }

        ctx.saveGState()
        ctx.translateBy(x: bounds.midX, y: bounds.midY)
        ctx.rotate(by: CGFloat(-heading * .pi / 180)) // as the map is turned
        // The ground under the square, turned: wide enough to fill its corners.
        let half = side * 0.75 / k
        let ground = MKMapRect(x: centre.x - half, y: centre.y - half, width: 2 * half, height: 2 * half)
        let level = SatelliteRenderer.level(pixelsPerMapPoint: k * Double(contentScaleFactor))
        map.setFocus(ground, level: level, contourLevel: -1)
        SatelliteRenderer.drawImagery(ground, level: level, into: ctx,
                                      rect: CGRect(x: -half * k, y: -half * k, width: 2 * half * k, height: 2 * half * k))

        ctx.setLineJoin(.round)
        ctx.setLineCap(.round)
        let margin = 4 / k
        for line in scene.lines where line.points.count >= 2 && Self.overlap(line.bounds, ground.insetBy(dx: -margin, dy: -margin)) {
            let path = CGMutablePath()
            path.addLines(between: line.points.map(local))
            if line.closed { path.closeSubpath() }
            ctx.addPath(path)
            ctx.setStrokeColor(UIColor(white: 0, alpha: 0.4).cgColor)
            ctx.setLineWidth(4)
            ctx.strokePath()
            ctx.addPath(path)
            ctx.setStrokeColor(line.colour.cgColor)
            ctx.setLineWidth(2)
            ctx.strokePath()
        }
        for d in scene.dots {
            let p = local(d.point)
            ctx.setFillColor(d.colour.cgColor)
            ctx.setStrokeColor(UIColor.black.cgColor)
            ctx.setLineWidth(1)
            ctx.addEllipse(in: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10))
            ctx.drawPath(using: .fillStroke)
        }
        // The measuring line, with the point being moved where it is now.
        var measure = scene.measure
        if let i = scene.moving, measure.indices.contains(i) { measure[i] = point }
        if let user = scene.user, measure.count == 1 { measure.insert(user, at: 0) }
        if measure.count >= 2 {
            let path = CGMutablePath()
            path.addLines(between: measure.map(local))
            if scene.measureClosed { path.closeSubpath() }
            ctx.addPath(path)
            ctx.setStrokeColor(measureColour.cgColor)
            ctx.setLineWidth(3)
            ctx.setLineDash(phase: 0, lengths: [8, 6])
            ctx.strokePath()
            ctx.setLineDash(phase: 0, lengths: [])
        }
        for (i, m) in scene.measure.enumerated() where i != scene.moving {
            let p = local(m)
            ctx.setStrokeColor(measureColour.cgColor)
            ctx.setLineWidth(3)
            ctx.strokeEllipse(in: CGRect(x: p.x - 9, y: p.y - 9, width: 18, height: 18))
        }
        if let user = scene.user {
            let p = local(user)
            ctx.setFillColor(UIColor.systemBlue.cgColor)
            ctx.setStrokeColor(UIColor.white.cgColor)
            ctx.setLineWidth(2.5)
            ctx.addEllipse(in: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16))
            ctx.drawPath(using: .fillStroke)
        }

        // The point being moved: a ring with a fine cross, so its exact spot shows.
        let p = local(point)
        let ring = marker == .pin ? UIColor.white : measureColour
        ctx.setStrokeColor(UIColor(white: 0, alpha: 0.55).cgColor)
        ctx.setLineWidth(5)
        ctx.strokeEllipse(in: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26))
        ctx.setStrokeColor(ring.cgColor)
        ctx.setLineWidth(3)
        ctx.strokeEllipse(in: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26))
        ctx.setLineWidth(1)
        ctx.setStrokeColor(UIColor.white.cgColor)
        for (a, b) in [(CGPoint(x: p.x - 20, y: p.y), CGPoint(x: p.x - 4, y: p.y)),
                       (CGPoint(x: p.x + 4, y: p.y), CGPoint(x: p.x + 20, y: p.y)),
                       (CGPoint(x: p.x, y: p.y - 20), CGPoint(x: p.x, y: p.y - 4)),
                       (CGPoint(x: p.x, y: p.y + 4), CGPoint(x: p.x, y: p.y + 20))] {
            ctx.move(to: a)
            ctx.addLine(to: b)
        }
        ctx.strokePath()
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - 1.5, y: p.y - 1.5, width: 3, height: 3))
        ctx.restoreGState()

        // Where the map starts to scroll: 5 m from the middle.
        let r = CGFloat(PointAdjuster.freeRadius / metresAcross * side)
        ctx.setStrokeColor(UIColor(white: 1, alpha: 0.55).cgColor)
        ctx.setLineWidth(1)
        ctx.setLineDash(phase: 0, lengths: [4, 4])
        ctx.strokeEllipse(in: CGRect(x: bounds.midX - r, y: bounds.midY - r, width: 2 * r, height: 2 * r))
        ctx.setLineDash(phase: 0, lengths: [])

        caption("\(Int(metresAcross)) m", at: CGPoint(x: 8, y: 6), alignRight: false)
        caption(String(format: "moved %.1f m", moved), at: CGPoint(x: bounds.width - 8, y: bounds.height - 24), alignRight: true)
    }

    /// Whether two map rects meet (also for a line's flat box, which MKMapRect.intersects may not count).
    static func overlap(_ a: MKMapRect, _ b: MKMapRect) -> Bool {
        !a.isNull && !b.isNull && a.minX <= b.maxX && a.maxX >= b.minX && a.minY <= b.maxY && a.maxY >= b.minY
    }

    /// The box around points, from their own extremes.
    static func box(_ pts: [MKMapPoint]) -> MKMapRect {
        guard let f = pts.first else { return .null }
        var x0 = f.x, x1 = f.x, y0 = f.y, y1 = f.y
        for p in pts {
            x0 = min(x0, p.x)
            x1 = max(x1, p.x)
            y0 = min(y0, p.y)
            y1 = max(y1, p.y)
        }
        return MKMapRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    private func caption(_ text: String, at p: CGPoint, alignRight: Bool) {
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .semibold),
            .foregroundColor: UIColor.white,
        ]
        let s = NSAttributedString(string: text, attributes: attrs)
        let size = s.size()
        let origin = CGPoint(x: alignRight ? p.x - size.width : p.x, y: p.y)
        let box = CGRect(origin: origin, size: size).insetBy(dx: -6, dy: -2)
        UIColor(white: 0, alpha: 0.55).setFill()
        UIBezierPath(roundedRect: box, cornerRadius: box.height / 2).fill()
        s.draw(at: origin)
    }
}
