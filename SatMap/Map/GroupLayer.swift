import MapKit
import UIKit

/// The shown group on the phone's map (docs/groups/SPEC.md §9.5): the other members' dots (moving smoothly to each
/// new position), their trails, and the group's pins with the animal sightings' labels. Built from
/// `GroupHub.mapContent(now:)`, where blocked members, expired pins and the Trails setting are already applied.
/// Not shown on CarPlay (SD7).
final class GroupLayer {
    private unowned let mapView: MKMapView
    /// Trails go directly under this overlay (your track); blocks go under `anchor`, which sits just below the track
    /// too, so trails stay above the KML blocks and below your track.
    private let below: MKOverlay
    let anchor = GroupLayerAnchor()
    private var members: [String: GroupMemberAnnotation] = [:]
    private var pins: [String: GroupPinAnnotation] = [:]
    private var trails: [String: GroupTrailOverlay] = [:]
    private var groupId: String?
    private var lastHeading = 0.0

    init(mapView: MKMapView, below: MKOverlay) {
        self.mapView = mapView
        self.below = below
        mapView.register(GroupMemberView.self, forAnnotationViewWithReuseIdentifier: GroupMemberView.reuseID)
        mapView.register(GroupPinView.self, forAnnotationViewWithReuseIdentifier: GroupPinView.reuseID)
    }

    /// The map's turn, for the heading triangles.
    private var mapHeading: Double { mapView.camera.heading }

    /// Shows this content (nil: no group shown).
    func update(_ content: GroupMapContent?) {
        if content?.groupId != groupId {
            // Another group: nothing of the last one stays (and nothing animates across).
            clear()
            groupId = content?.groupId
        }
        guard let content = content else { return }
        updateMembers(content.members)
        updatePins(content.pins)
        updateTrails(content.trails)
    }

    private func clear() {
        mapView.removeAnnotations(Array(members.values) + Array(pins.values))
        mapView.removeOverlays(Array(trails.values))
        members = [:]
        pins = [:]
        trails = [:]
    }

    private func updateMembers(_ list: [MapMember]) {
        var seen = Set<String>()
        var added: [GroupMemberAnnotation] = []
        for m in list {
            seen.insert(m.memberId)
            let c = CLLocationCoordinate2D(latitude: m.position.lat, longitude: m.position.lon)
            if let a = members[m.memberId] {
                let moved = a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude
                a.member = m
                if moved {
                    UIView.animate(withDuration: 0.8, delay: 0, options: [.curveEaseInOut, .allowUserInteraction]) { a.coordinate = c }
                }
                (mapView.view(for: a) as? GroupMemberView)?.show(m, mapHeading: mapHeading)
            } else {
                let a = GroupMemberAnnotation(member: m)
                members[m.memberId] = a
                added.append(a)
            }
        }
        let gone = members.filter { !seen.contains($0.key) }
        mapView.removeAnnotations(Array(gone.values))
        for id in gone.keys { members[id] = nil }
        mapView.addAnnotations(added)
    }

    private func updatePins(_ list: [MapPin]) {
        var seen = Set<String>()
        var added: [GroupPinAnnotation] = []
        for p in list {
            seen.insert(p.pinId)
            if let a = pins[p.pinId] {
                if a.pin != p {
                    let c = CLLocationCoordinate2D(latitude: p.position.lat, longitude: p.position.lon)
                    if a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude { a.coordinate = c }
                    a.pin = p
                    (mapView.view(for: a) as? GroupPinView)?.show(p)
                }
            } else {
                let a = GroupPinAnnotation(pin: p)
                pins[p.pinId] = a
                added.append(a)
            }
        }
        let gone = pins.filter { !seen.contains($0.key) }
        mapView.removeAnnotations(Array(gone.values))
        for id in gone.keys { pins[id] = nil }
        mapView.addAnnotations(added)
    }

    private func updateTrails(_ list: [MapTrail]) {
        var seen = Set<String>()
        for t in list {
            seen.insert(t.memberId)
            if let o = trails[t.memberId] {
                if o.set(t) { (mapView.renderer(for: o) as? GroupTrailRenderer)?.setNeedsDisplay() }
            } else {
                let o = GroupTrailOverlay()
                _ = o.set(t)
                trails[t.memberId] = o
                mapView.insertOverlay(o, below: below)
            }
        }
        let gone = trails.filter { !seen.contains($0.key) }
        mapView.removeOverlays(Array(gone.values))
        for id in gone.keys { trails[id] = nil }
    }

    /// The map turned: the heading triangles turn with it.
    func headingChanged() {
        let h = mapHeading
        guard abs(h - lastHeading) >= 0.5 else { return }
        lastHeading = h
        for a in members.values {
            (mapView.view(for: a) as? GroupMemberView)?.show(a.member, mapHeading: h)
        }
    }

    // MARK: Taps (§9.1: a member dot, then a group pin, within 22 pt)

    func member(near spot: CGPoint) -> MapMember? {
        nearest(Array(members.values), spot)?.member
    }

    func pin(near spot: CGPoint) -> MapPin? {
        nearest(Array(pins.values), spot)?.pin
    }

    var shownGroupId: String? { groupId }

    private func nearest<A: MKAnnotation>(_ list: [A], _ spot: CGPoint) -> A? {
        var best: (a: A, d: CGFloat)?
        for a in list {
            let p = mapView.convert(a.coordinate, toPointTo: mapView)
            let d = hypot(p.x - spot.x, p.y - spot.y)
            if d <= 22, d < (best?.d ?? .infinity) { best = (a, d) }
        }
        return best?.a
    }

    // MARK: Views and renderers

    func view(for a: GroupMemberAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: GroupMemberView.reuseID, for: a) as? GroupMemberView
        v?.show(a.member, mapHeading: mapHeading)
        return v
    }

    func view(for a: GroupPinAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: GroupPinView.reuseID, for: a) as? GroupPinView
        v?.show(a.pin)
        return v
    }
}

// MARK: - Annotations and overlays

final class GroupMemberAnnotation: NSObject, MKAnnotation {
    /// Moves smoothly to a new position (animated by `GroupLayer`).
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var member: MapMember

    init(member: MapMember) {
        self.member = member
        coordinate = CLLocationCoordinate2D(latitude: member.position.lat, longitude: member.position.lon)
        super.init()
    }
}

final class GroupPinAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var pin: MapPin

    init(pin: MapPin) {
        self.pin = pin
        coordinate = CLLocationCoordinate2D(latitude: pin.position.lat, longitude: pin.position.lon)
        super.init()
    }
}

/// An empty overlay that marks where group trails start: KML blocks are inserted below it.
final class GroupLayerAnchor: NSObject, MKOverlay {
    let coordinate = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    let boundingMapRect = MKMapRect(x: 0, y: 0, width: 1, height: 1)
}

/// One member's trail: solid segments in the member's colour, 85 % opaque (§9.5). Like `TrackOverlay`, it is changed
/// in place and redrawn, rather than replaced (replacing overlays makes the map flicker).
final class GroupTrailOverlay: NSObject, MKOverlay {
    struct Segment {
        var points: [MKMapPoint]
        var rect: MKMapRect
    }

    private let lock = NSLock()
    private var segments: [Segment] = []
    private var colour = UIColor.white
    private var width = 3.0
    private var source: MapTrail?

    let coordinate = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    let boundingMapRect = MKMapRect.world

    var snapshot: (segments: [Segment], colour: UIColor, width: Double) {
        lock.withLock { (segments, colour, width) }
    }

    /// Takes a trail; returns whether anything changed.
    func set(_ trail: MapTrail) -> Bool {
        lock.withLock {
            guard source != trail else { return false }
            source = trail
            colour = UIColor(groupHex: trail.colourHex).withAlphaComponent(0.85)
            width = trail.widthPt
            segments = trail.segments.compactMap { seg in
                let pts = seg.map { MKMapPoint(CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)) }
                guard pts.count >= 2 else { return nil }
                let xs = pts.map(\.x), ys = pts.map(\.y)
                let rect = MKMapRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
                return Segment(points: pts, rect: rect)
            }
            return true
        }
    }
}

final class GroupTrailRenderer: MKOverlayRenderer {
    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let trail = overlay as? GroupTrailOverlay else { return }
        let (segments, colour, widthPt) = trail.snapshot
        let width = widthPt / Double(zoomScale)
        let minStep = 1.0 / Double(zoomScale)
        let clip = mapRect.insetBy(dx: -width * 2, dy: -width * 2)
        context.setStrokeColor(colour.cgColor)
        context.setLineWidth(CGFloat(width))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        for s in segments where s.rect.insetBy(dx: -width, dy: -width).intersects(clip) {
            let path = CGMutablePath()
            var last = s.points[0]
            path.move(to: point(for: last))
            for (i, p) in s.points.enumerated().dropFirst() {
                if i != s.points.count - 1 && abs(p.x - last.x) < minStep && abs(p.y - last.y) < minStep { continue }
                path.addLine(to: point(for: p))
                last = p
            }
            context.addPath(path)
            context.strokePath()
        }
    }
}

// MARK: - Views

/// A member's dot (§9.5): a 26 pt circle in the member's colour with a 2 pt white ring and white bold initials, and a
/// small white triangle just outside the ring pointing the heading's way. Stale (over 10 min): grey, ringed in the
/// member's colour, without the triangle.
final class GroupMemberView: MKAnnotationView {
    static let reuseID = "groupMember"
    private static let dot: CGFloat = 26
    private let circle = UIView()
    private let initials = UILabel()
    private let arrow = CAShapeLayer()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        let side: CGFloat = Self.dot + 2 * 8
        frame = CGRect(x: 0, y: 0, width: side, height: side)
        backgroundColor = .clear
        // The triangle: 8 pt wide, 6 pt deep, its base just outside the ring, turning about the dot's middle.
        arrow.frame = bounds
        let c = CGPoint(x: bounds.midX, y: bounds.midY)
        let base = c.y - Self.dot / 2 - 1
        let path = CGMutablePath()
        path.move(to: CGPoint(x: c.x, y: base - 6))
        path.addLine(to: CGPoint(x: c.x + 4, y: base))
        path.addLine(to: CGPoint(x: c.x - 4, y: base))
        path.closeSubpath()
        arrow.path = path
        arrow.fillColor = UIColor.white.cgColor
        arrow.shadowColor = UIColor.black.cgColor
        arrow.shadowOpacity = 0.45
        arrow.shadowRadius = 1.5
        arrow.shadowOffset = .zero
        layer.addSublayer(arrow)
        circle.frame = CGRect(x: c.x - Self.dot / 2, y: c.y - Self.dot / 2, width: Self.dot, height: Self.dot)
        circle.layer.cornerRadius = Self.dot / 2
        circle.layer.borderWidth = 2
        circle.layer.shadowColor = UIColor.black.cgColor
        circle.layer.shadowOpacity = 0.4
        circle.layer.shadowRadius = 2
        circle.layer.shadowOffset = .zero
        addSubview(circle)
        initials.frame = circle.bounds.insetBy(dx: 2, dy: 2)
        initials.font = .systemFont(ofSize: 11, weight: .bold)
        initials.textColor = .white
        initials.textAlignment = .center
        initials.adjustsFontSizeToFitWidth = true
        initials.minimumScaleFactor = 0.6
        circle.addSubview(initials)
        canShowCallout = false
        isUserInteractionEnabled = false // taps are the map's (`MapController.mapTapped`)
        zPriority = MKAnnotationViewZPriority(rawValue: 990) // just under your own dot
        displayPriority = .required
        collisionMode = .circle
        isAccessibilityElement = true
        accessibilityIdentifier = "groupMember"
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func show(_ m: MapMember, mapHeading: Double) {
        let colour = UIColor(groupHex: m.colourHex)
        circle.backgroundColor = m.isStale ? UIColor(groupHex: GroupPolicy.grey) : colour
        circle.layer.borderColor = (m.isStale ? colour : .white).cgColor
        initials.text = m.initials
        accessibilityLabel = m.a11yLabel
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if let h = m.headingDeg, !m.isStale {
            arrow.isHidden = false
            let angle = (Double(h) - mapHeading) * .pi / 180
            arrow.setAffineTransform(CGAffineTransform(rotationAngle: CGFloat(angle)))
        } else {
            arrow.isHidden = true
        }
        CATransaction.commit()
    }
}

/// A group pin (§9.5): a 30 pt circle in the type's colour with a 2 pt white ring and a white glyph; an animal
/// sighting also has its label ("Kudu · 2 h ago") under it.
final class GroupPinView: MKAnnotationView {
    static let reuseID = "groupPin"
    private static let size: CGFloat = 30
    private let circle = UIView()
    private let glyph = UIImageView()
    private let label = PaddedLabel()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        circle.layer.cornerRadius = Self.size / 2
        circle.layer.borderWidth = 2
        circle.layer.borderColor = UIColor.white.cgColor
        circle.layer.shadowColor = UIColor.black.cgColor
        circle.layer.shadowOpacity = 0.4
        circle.layer.shadowRadius = 2
        circle.layer.shadowOffset = .zero
        addSubview(circle)
        glyph.tintColor = .white
        glyph.contentMode = .center
        glyph.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        circle.addSubview(glyph)
        label.font = .systemFont(ofSize: 12, weight: .bold)
        label.textColor = .white
        label.backgroundColor = UIColor(white: 0, alpha: 0.65)
        label.layer.masksToBounds = true
        label.textAlignment = .center
        addSubview(label)
        canShowCallout = false
        isUserInteractionEnabled = false // taps are the map's (`MapController.mapTapped`)
        zPriority = MKAnnotationViewZPriority(rawValue: 980) // under the member dots
        displayPriority = .required
        collisionMode = .circle
        isAccessibilityElement = true
        accessibilityIdentifier = "groupPin"
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func show(_ p: MapPin) {
        circle.backgroundColor = UIColor(groupHex: p.colourHex)
        glyph.image = UIImage(systemName: GroupTexts.pinSymbol(p.knownType))
        accessibilityLabel = p.a11yLabel
        label.text = p.label
        label.isHidden = p.label == nil
        let labelSize = p.label == nil ? .zero : label.intrinsicContentSize
        let width = max(Self.size, labelSize.width)
        let height = Self.size + (p.label == nil ? 0 : labelSize.height + 3)
        frame = CGRect(x: 0, y: 0, width: width, height: height)
        circle.frame = CGRect(x: (width - Self.size) / 2, y: 0, width: Self.size, height: Self.size)
        glyph.frame = circle.bounds
        label.frame = CGRect(x: (width - labelSize.width) / 2, y: Self.size + 3, width: labelSize.width, height: labelSize.height)
        label.layer.cornerRadius = labelSize.height / 2
        // The circle's middle on the pin's spot.
        centerOffset = CGPoint(x: 0, y: (height - Self.size) / 2)
    }

    private final class PaddedLabel: UILabel {
        override var intrinsicContentSize: CGSize {
            let s = super.intrinsicContentSize
            return CGSize(width: ceil(s.width) + 14, height: ceil(s.height) + 4)
        }
    }
}

extension UIColor {
    /// A "#RRGGBB" colour from core (grey if it isn't one).
    convenience init(groupHex: String) {
        let c = GroupColour.rgb(groupHex) ?? GroupColour.rgb(GroupPolicy.grey)!
        self.init(red: CGFloat(c.red), green: CGFloat(c.green), blue: CGFloat(c.blue), alpha: 1)
    }
}
