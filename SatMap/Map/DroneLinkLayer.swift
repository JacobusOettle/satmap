import MapKit
import UIKit

/// Drone link on the phone's map, in drone mode only, above the airfields: each drone (turned to its heading, in its own
/// colour, "Drone 1" and its height above take-off), its home point, and the ADS-B aircraft it hears (an icon by type,
/// turned to the heading, the callsign or ICAO address, altitude in feet, speed in knots and a climb arrow). Low aircraft
/// close to you or a drone are drawn red. Drones grey after 10 s without a position, aircraft fade after 15 s
/// (`DroneLink`). Not shown on CarPlay.
final class DroneLinkLayer {
    private unowned let mapView: MKMapView
    private var drones: [UInt8: DroneAnnotation] = [:]
    private var homes: [UInt8: DroneHomeAnnotation] = [:]
    private var aircraft: [UInt32: AircraftAnnotation] = [:]
    private var lastHeading = 0.0

    var isOn = false {
        didSet {
            guard isOn != oldValue, !isOn else { return }
            mapView.removeAnnotations(Array(drones.values) + Array(homes.values) + Array(aircraft.values))
            drones = [:]
            homes = [:]
            aircraft = [:]
        }
    }

    init(mapView: MKMapView) {
        self.mapView = mapView
        mapView.register(DroneView.self, forAnnotationViewWithReuseIdentifier: DroneView.reuseID)
        mapView.register(DroneHomeView.self, forAnnotationViewWithReuseIdentifier: DroneHomeView.reuseID)
        mapView.register(AircraftView.self, forAnnotationViewWithReuseIdentifier: AircraftView.reuseID)
    }

    private var mapHeading: Double { mapView.camera.heading }

    /// Shows what the link has now. `me` and `meGroundM`: you and the ground's height where you are, for the red
    /// highlight; `heightUnit` for the drones' heights.
    func update(_ traffic: DroneLink.Traffic, selection: DroneLinkModel.Selection?, me: GeoPoint?, meGroundM: Double?,
                heightUnit: HeightUnit, now: Date) {
        guard isOn else { return }
        var refs: [(point: GeoPoint, groundM: Double?)] = []
        if let me = me { refs.append((me, meGroundM)) }

        var added: [MKAnnotation] = []
        var removed: [MKAnnotation] = []
        var seenDrones = Set<UInt8>(), seenHomes = Set<UInt8>()
        for d in traffic.sortedDrones {
            if let home = d.homePoint {
                seenHomes.insert(d.sysid)
                let c = CLLocationCoordinate2D(latitude: home.lat, longitude: home.lon)
                if let a = homes[d.sysid] {
                    if a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude { a.coordinate = c }
                } else {
                    let a = DroneHomeAnnotation(sysid: d.sysid, colourIndex: d.colourIndex, coordinate: c)
                    homes[d.sysid] = a
                    added.append(a)
                }
            }
            guard let p = d.point else { continue }
            refs.append((p, d.takeOffGroundM))
            seenDrones.insert(d.sysid)
            let state = DroneAnnotation.State(drone: d, stale: d.isStale(now), selected: selection == .drone(d.sysid),
                                              heightUnit: heightUnit)
            let c = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
            if let a = drones[d.sysid] {
                if a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude { a.coordinate = c }
                if a.state != state {
                    a.state = state
                    (mapView.view(for: a) as? DroneView)?.show(state, mapHeading: mapHeading)
                }
            } else {
                let a = DroneAnnotation(state: state, coordinate: c)
                drones[d.sysid] = a
                added.append(a)
            }
        }
        for (id, a) in drones where !seenDrones.contains(id) {
            removed.append(a)
            drones[id] = nil
        }
        for (id, a) in homes where !seenHomes.contains(id) {
            removed.append(a)
            homes[id] = nil
        }

        var seenAircraft = Set<UInt32>()
        for (icao, plane) in traffic.aircraft {
            seenAircraft.insert(icao)
            let state = AircraftAnnotation.State(aircraft: plane, stale: plane.isStale(now),
                                                 highlight: DroneLink.isLowAndClose(plane, references: refs),
                                                 selected: selection == .aircraft(icao))
            let c = CLLocationCoordinate2D(latitude: plane.position.lat, longitude: plane.position.lon)
            if let a = aircraft[icao] {
                if a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude { a.coordinate = c }
                if a.state != state {
                    a.state = state
                    (mapView.view(for: a) as? AircraftView)?.show(state, mapHeading: mapHeading)
                }
            } else {
                let a = AircraftAnnotation(state: state, coordinate: c)
                aircraft[icao] = a
                added.append(a)
            }
        }
        for (id, a) in aircraft where !seenAircraft.contains(id) {
            removed.append(a)
            aircraft[id] = nil
        }
        if !removed.isEmpty { mapView.removeAnnotations(removed) }
        if !added.isEmpty { mapView.addAnnotations(added) }
    }

    /// The map turned: the icons turn with it.
    func headingChanged() {
        let h = mapHeading
        guard isOn, abs(h - lastHeading) >= 0.5 else { return }
        lastHeading = h
        for a in drones.values { (mapView.view(for: a) as? DroneView)?.show(a.state, mapHeading: h) }
        for a in aircraft.values { (mapView.view(for: a) as? AircraftView)?.show(a.state, mapHeading: h) }
    }

    /// The drone or aircraft at a spot on the map, within a fingertip of its icon (or on its label); drones first.
    func item(near spot: CGPoint) -> DroneLinkModel.Selection? {
        guard isOn else { return nil }
        func distance(_ a: MKAnnotation) -> CGFloat {
            let p = mapView.convert(a.coordinate, toPointTo: mapView)
            var d = hypot(p.x - spot.x, p.y - spot.y)
            if d >= 26, let v = mapView.view(for: a), !v.isHidden, v.convert(v.bounds, to: mapView).contains(spot) { d = 25 }
            return d
        }
        var best: (s: DroneLinkModel.Selection, d: CGFloat)?
        for a in drones.values {
            let d = distance(a)
            if d < 26, d < (best?.d ?? .infinity) { best = (.drone(a.state.drone.sysid), d) }
        }
        if let b = best { return b.s }
        for a in aircraft.values {
            let d = distance(a)
            if d < 26, d < (best?.d ?? .infinity) { best = (.aircraft(a.state.aircraft.icaoAddress), d) }
        }
        return best?.s
    }

    // MARK: Views

    func view(for a: DroneAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: DroneView.reuseID, for: a) as? DroneView
        v?.show(a.state, mapHeading: mapHeading)
        return v
    }

    func view(for a: DroneHomeAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: DroneHomeView.reuseID, for: a) as? DroneHomeView
        v?.show(a)
        return v
    }

    func view(for a: AircraftAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: AircraftView.reuseID, for: a) as? AircraftView
        v?.show(a.state, mapHeading: mapHeading)
        return v
    }

    // MARK: Colours

    /// One colour per drone, in the order they were first heard (none of them your cyan or the pin's amber).
    static let droneColours: [UIColor] = [
        UIColor(red: 1.0, green: 0.31, blue: 0.85, alpha: 1), // magenta
        UIColor(red: 0.49, green: 1.0, blue: 0.31, alpha: 1), // lime
        UIColor(red: 0.62, green: 0.48, blue: 1.0, alpha: 1), // violet
        UIColor(red: 1.0, green: 0.95, blue: 0.35, alpha: 1), // yellow
        UIColor(red: 0.2, green: 0.9, blue: 0.7, alpha: 1),   // teal
    ]

    static func colour(_ index: Int) -> UIColor { droneColours[index % droneColours.count] }
    static let staleColour = UIColor(white: 0.62, alpha: 1)
    static let highlightColour = UIColor(red: 1.0, green: 0.27, blue: 0.23, alpha: 1)
}

// MARK: - Annotations

final class DroneAnnotation: NSObject, MKAnnotation {
    struct State: Equatable {
        let drone: DroneLink.Drone
        let stale: Bool
        let selected: Bool
        let heightUnit: HeightUnit
    }

    @objc dynamic var coordinate: CLLocationCoordinate2D
    var state: State
    var title: String? { state.drone.label }

    init(state: State, coordinate: CLLocationCoordinate2D) {
        self.state = state
        self.coordinate = coordinate
        super.init()
    }
}

final class DroneHomeAnnotation: NSObject, MKAnnotation {
    let sysid: UInt8
    let colourIndex: Int
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var title: String? { "Home of Drone \(sysid)" }

    init(sysid: UInt8, colourIndex: Int, coordinate: CLLocationCoordinate2D) {
        self.sysid = sysid
        self.colourIndex = colourIndex
        self.coordinate = coordinate
        super.init()
    }
}

final class AircraftAnnotation: NSObject, MKAnnotation {
    struct State: Equatable {
        let aircraft: DroneLink.Aircraft
        let stale: Bool
        let highlight: Bool
        let selected: Bool
    }

    @objc dynamic var coordinate: CLLocationCoordinate2D
    var state: State
    var title: String? { state.aircraft.label }

    init(state: State, coordinate: CLLocationCoordinate2D) {
        self.state = state
        self.coordinate = coordinate
        super.init()
    }
}

// MARK: - Views

/// The shared look of the drone and aircraft views: an icon on the spot, turned to the heading, and under it a dark card
/// with the label, as drone mode's airfields have.
class DroneLinkMarkerView: MKAnnotationView {
    let icon = CAShapeLayer()
    let ring = CAShapeLayer()
    let label = UILabel()
    let card = UIView()
    var iconSize: CGFloat { 28 }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        ring.fillColor = UIColor(white: 1, alpha: 0.22).cgColor
        ring.strokeColor = UIColor.white.cgColor
        ring.lineWidth = 2
        layer.addSublayer(ring)
        icon.strokeColor = UIColor(white: 0, alpha: 0.75).cgColor
        icon.lineWidth = 1.2
        icon.lineJoin = .round
        icon.shadowColor = UIColor.black.cgColor
        icon.shadowOpacity = 0.5
        icon.shadowRadius = 2
        icon.shadowOffset = .zero
        layer.addSublayer(icon)
        card.backgroundColor = UIColor(white: 0.05, alpha: 0.78)
        card.layer.cornerRadius = 8
        card.layer.cornerCurve = .continuous
        card.layer.borderColor = UIColor(white: 1, alpha: 0.18).cgColor
        card.layer.borderWidth = 0.5
        addSubview(card)
        label.numberOfLines = 0
        label.textAlignment = .center
        card.addSubview(label)
        canShowCallout = false
        isUserInteractionEnabled = false // taps are the map's (`MapController.mapTapped`)
        collisionMode = .circle
        displayPriority = .required
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    /// Lays out the icon (the path drawn in a 24-unit box, nose up) turned by `angle` degrees, and the label card.
    func layout(path: CGPath, fill: UIColor, angle: Double?, text: NSAttributedString, cardBorder: UIColor?, selected: Bool,
                alpha: CGFloat) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        label.attributedText = text
        let size = label.sizeThatFits(CGSize(width: 180, height: 200))
        let s = iconSize
        let cardSize = CGSize(width: ceil(size.width) + 12, height: ceil(size.height) + 6)
        let w = max(s + 8, cardSize.width), h = s + 3 + cardSize.height
        bounds = CGRect(x: 0, y: 0, width: w, height: h)
        let iconRect = CGRect(x: (w - s) / 2, y: 0, width: s, height: s)
        // Bounds and position, not frame: the layer may still be turned from its last use.
        icon.bounds = CGRect(origin: .zero, size: iconRect.size)
        icon.position = CGPoint(x: iconRect.midX, y: iconRect.midY)
        var t = CGAffineTransform(scaleX: s / 24, y: s / 24)
        icon.path = path.copy(using: &t)
        icon.fillColor = fill.cgColor
        icon.setAffineTransform(angle.map { CGAffineTransform(rotationAngle: CGFloat($0 * .pi / 180)) } ?? .identity)
        ring.frame = iconRect.insetBy(dx: -4, dy: -4)
        ring.path = CGPath(ellipseIn: CGRect(origin: .zero, size: ring.frame.size), transform: nil)
        ring.isHidden = !selected
        card.frame = CGRect(x: (w - cardSize.width) / 2, y: s + 3, width: cardSize.width, height: cardSize.height)
        card.layer.borderColor = (cardBorder ?? UIColor(white: 1, alpha: 0.18)).cgColor
        card.layer.borderWidth = cardBorder == nil ? 0.5 : 1.5
        label.frame = card.bounds.insetBy(dx: 6, dy: 3)
        centerOffset = CGPoint(x: 0, y: h / 2 - s / 2)
        self.alpha = alpha
    }

    static func line(_ text: String, bold: Bool, colour: UIColor = .white) -> NSAttributedString {
        NSAttributedString(string: text, attributes: [
            .font: bold ? UIFont.systemFont(ofSize: 13, weight: .bold) : UIFont.monospacedDigitSystemFont(ofSize: 11.5, weight: .semibold),
            .foregroundColor: colour,
        ])
    }
}

/// A drone: a quadcopter in its colour, turned to its heading, "Drone 1" and its height above take-off under it; grey
/// once its position is old.
final class DroneView: DroneLinkMarkerView {
    static let reuseID = "droneLinkDrone"
    override var iconSize: CGFloat { 32 }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        zPriority = .max
        accessibilityIdentifier = "droneLinkDrone"
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func show(_ s: DroneAnnotation.State, mapHeading: Double) {
        let d = s.drone
        let colour = s.stale ? DroneLinkLayer.staleColour : DroneLinkLayer.colour(d.colourIndex)
        let text = NSMutableAttributedString(attributedString: Self.line(d.label, bold: true, colour: colour))
        if let p = d.position {
            text.append(Self.line("\n\(s.heightUnit.whole(p.relativeAltM)) \(s.heightUnit.symbol)", bold: false))
        }
        let angle = d.headingDeg.map { $0 - mapHeading }
        layout(path: DroneLinkShapes.quad, fill: colour, angle: angle, text: text, cardBorder: nil, selected: s.selected,
               alpha: 1)
        accessibilityLabel = d.label
        accessibilityValue = s.stale ? "No recent position" : nil
    }
}

/// An aircraft: its type's icon turned to its heading (white, red when low and close), the callsign (or ICAO address),
/// altitude, speed and climb arrow under it; faded once its report is old.
final class AircraftView: DroneLinkMarkerView {
    static let reuseID = "droneLinkAircraft"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        zPriority = MKAnnotationViewZPriority(rawValue: 995)
        accessibilityIdentifier = "droneLinkAircraft"
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func show(_ s: AircraftAnnotation.State, mapHeading: Double) {
        let a = s.aircraft
        let fill = s.highlight ? DroneLinkLayer.highlightColour : .white
        let text = NSMutableAttributedString(attributedString: Self.line(a.label, bold: true))
        let details = [a.altitudeText, a.speedText, a.climbArrow].compactMap { $0 }.joined(separator: " · ")
        if !details.isEmpty { text.append(Self.line("\n" + details, bold: false)) }
        let angle = a.kind.rotates ? a.headingDeg.map { $0 - mapHeading } : nil
        layout(path: DroneLinkShapes.path(a.kind), fill: fill, angle: angle, text: text,
               cardBorder: s.highlight ? DroneLinkLayer.highlightColour : nil, selected: s.selected, alpha: s.stale ? 0.45 : 1)
        accessibilityLabel = "\(a.kind.title) \(a.label)"
        accessibilityValue = details
    }
}

/// A drone's home point: a ring in its colour with an H.
final class DroneHomeView: MKAnnotationView {
    static let reuseID = "droneLinkHome"
    private let letter = UILabel()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 22, height: 22)
        layer.cornerRadius = 11
        layer.borderWidth = 2.5
        backgroundColor = UIColor(white: 0, alpha: 0.55)
        letter.frame = bounds
        letter.textAlignment = .center
        letter.font = .systemFont(ofSize: 12, weight: .heavy)
        letter.text = "H"
        addSubview(letter)
        canShowCallout = false
        isUserInteractionEnabled = false
        zPriority = MKAnnotationViewZPriority(rawValue: 900)
        displayPriority = .required
        isAccessibilityElement = true
        accessibilityIdentifier = "droneLinkHome"
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func show(_ a: DroneHomeAnnotation) {
        let c = DroneLinkLayer.colour(a.colourIndex)
        layer.borderColor = c.cgColor
        letter.textColor = c
        accessibilityLabel = a.title
    }
}

/// The icons, in a 24-unit box with the nose up.
enum DroneLinkShapes {
    /// A shape symmetric about x = 12, from its right half (nose to tail).
    private static func mirrored(_ half: [(CGFloat, CGFloat)]) -> CGPath {
        let path = CGMutablePath()
        path.move(to: CGPoint(x: half[0].0, y: half[0].1))
        for p in half.dropFirst() { path.addLine(to: CGPoint(x: p.0, y: p.1)) }
        for p in half.reversed() where p.0 != 12 { path.addLine(to: CGPoint(x: 24 - p.0, y: p.1)) }
        path.closeSubpath()
        return path
    }

    static let airliner = mirrored([(12, 1), (13.2, 3), (13.2, 8.5), (22.5, 13.5), (22.5, 15.5), (13.2, 12.8), (13.2, 18),
                                    (16.8, 20.6), (16.8, 22.2), (12, 21.2)])
    static let light = mirrored([(12, 2), (13.2, 4), (13.2, 7.6), (22.5, 7.6), (22.5, 10.6), (13.2, 10.6), (12.9, 18),
                                 (16.4, 18.4), (16.4, 20.8), (12, 20.8)])
    static let glider = mirrored([(12, 3), (12.9, 5), (12.9, 8.8), (23.6, 9.4), (23.6, 11), (12.9, 11.2), (12.7, 19.2),
                                  (15.6, 19.6), (15.6, 21.2), (12, 21.2)])

    static let helicopter: CGPath = {
        let path = CGMutablePath()
        path.addEllipse(in: CGRect(x: 8.2, y: 6.5, width: 7.6, height: 9.5))
        path.addRect(CGRect(x: 11.2, y: 15, width: 1.6, height: 7))
        path.addRect(CGRect(x: 9, y: 20.2, width: 6, height: 1.6))
        let rotor = CGMutablePath()
        rotor.move(to: CGPoint(x: 3, y: 2.5))
        rotor.addLine(to: CGPoint(x: 21, y: 20))
        rotor.move(to: CGPoint(x: 21, y: 2.5))
        rotor.addLine(to: CGPoint(x: 3, y: 20))
        path.addPath(rotor.copy(strokingWithWidth: 1.8, lineCap: .round, lineJoin: .round, miterLimit: 1))
        return path
    }()

    static let balloon: CGPath = {
        let path = CGMutablePath()
        path.addEllipse(in: CGRect(x: 5, y: 1.5, width: 14, height: 15))
        let cords = CGMutablePath()
        cords.move(to: CGPoint(x: 8, y: 14.5))
        cords.addLine(to: CGPoint(x: 10.5, y: 19))
        cords.move(to: CGPoint(x: 16, y: 14.5))
        cords.addLine(to: CGPoint(x: 13.5, y: 19))
        path.addPath(cords.copy(strokingWithWidth: 1.2, lineCap: .round, lineJoin: .round, miterLimit: 1))
        path.addRect(CGRect(x: 10, y: 19, width: 4, height: 3.5))
        return path
    }()

    static let other: CGPath = {
        let path = CGMutablePath()
        path.addLines(between: [CGPoint(x: 12, y: 4), CGPoint(x: 20, y: 12), CGPoint(x: 12, y: 20), CGPoint(x: 4, y: 12)])
        path.closeSubpath()
        return path
    }()

    /// A quadcopter seen from above: four rotors on an X, a body, and a nose pointing the way it flies.
    static let quad: CGPath = {
        let path = CGMutablePath()
        let arms = CGMutablePath()
        arms.move(to: CGPoint(x: 5, y: 5))
        arms.addLine(to: CGPoint(x: 19, y: 19))
        arms.move(to: CGPoint(x: 19, y: 5))
        arms.addLine(to: CGPoint(x: 5, y: 19))
        path.addPath(arms.copy(strokingWithWidth: 2.2, lineCap: .round, lineJoin: .round, miterLimit: 1))
        for c in [CGPoint(x: 5, y: 5), CGPoint(x: 19, y: 5), CGPoint(x: 5, y: 19), CGPoint(x: 19, y: 19)] {
            let rotor = CGPath(ellipseIn: CGRect(x: c.x - 4.2, y: c.y - 4.2, width: 8.4, height: 8.4), transform: nil)
            path.addPath(rotor.copy(strokingWithWidth: 1.8, lineCap: .round, lineJoin: .round, miterLimit: 1))
        }
        path.addEllipse(in: CGRect(x: 8.5, y: 8.5, width: 7, height: 7))
        path.addLines(between: [CGPoint(x: 12, y: 0.5), CGPoint(x: 14.6, y: 5), CGPoint(x: 9.4, y: 5)])
        path.closeSubpath()
        return path
    }()

    static func path(_ kind: DroneLink.AircraftKind) -> CGPath {
        switch kind {
        case .airliner: return airliner
        case .light: return light
        case .helicopter: return helicopter
        case .glider: return glider
        case .balloon: return balloon
        case .uav: return quad
        case .other: return other
        }
    }
}
