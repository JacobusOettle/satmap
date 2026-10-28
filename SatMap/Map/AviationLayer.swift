import MapKit
import UIKit

/// The aerodromes and navaids shipped with the app (Resources/Aviation.tsv), read once, in the background, the first
/// time drone mode needs them.
final class AviationStore {
    static let shared = AviationStore()

    private let lock = NSLock()
    private var loaded: AviationIndex?
    private var waiting: [(AviationIndex) -> Void] = []
    private var loading = false

    /// The index if it has been read.
    var index: AviationIndex? { lock.withLock { loaded } }

    /// Calls back on the main queue with the index, reading it first if needed.
    func load(_ done: @escaping (AviationIndex) -> Void) {
        let start: Bool = lock.withLock {
            if let i = loaded {
                DispatchQueue.main.async { done(i) }
                return false
            }
            waiting.append(done)
            if loading { return false }
            loading = true
            return true
        }
        guard start else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let text = Bundle.main.url(forResource: "Aviation", withExtension: "tsv")
                .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
            let index = AviationIndex(tsv: text)
            let callbacks: [(AviationIndex) -> Void] = self.lock.withLock {
                self.loaded = index
                defer { self.waiting = [] }
                return self.waiting
            }
            DispatchQueue.main.async { callbacks.forEach { $0(index) } }
        }
    }
}

/// An aviation place on the map.
final class AviationAnnotation: NSObject, MKAnnotation {
    let id: Int
    let place: AviationPoint
    let coordinate: CLLocationCoordinate2D

    var title: String? { place.label }

    init(id: Int, place: AviationPoint) {
        self.id = id
        self.place = place
        coordinate = CLLocationCoordinate2D(latitude: place.point.lat, longitude: place.point.lon)
        super.init()
    }
}

/// Drone mode on a map (the phone's or the car's): the aerodromes, strips, helipads and navaids in view, more of them as
/// the map zooms in, each labelled with how far it is from you and from the tapped spot.
final class AviationLayer {
    private unowned let mapView: MKMapView
    private var shown: [Int: AviationAnnotation] = [:]
    /// Where the distances are from: you, and the tapped spot (or a selected place).
    private(set) var me: GeoPoint?
    private(set) var pin: GeoPoint?

    /// Places at most on the map at once (the nearest the middle, most important first).
    private static let maxShown = 140

    var isOn = false {
        didSet {
            guard isOn != oldValue else { return }
            if isOn {
                AviationStore.shared.load { [weak self] _ in self?.regionChanged() }
            } else {
                mapView.removeAnnotations(Array(shown.values))
                shown = [:]
            }
        }
    }

    init(mapView: MKMapView) {
        self.mapView = mapView
    }

    /// The map has stopped (or drone mode came on): the places for this view.
    func regionChanged() {
        guard isOn, let index = AviationStore.shared.index, mapView.bounds.width > 0 else { return }
        let r = mapView.region
        let km = Geo.distance(GeoPoint(lat: r.center.latitude, lon: r.center.longitude - r.span.longitudeDelta / 2),
                              GeoPoint(lat: r.center.latitude, lon: r.center.longitude + r.span.longitudeDelta / 2)) / 1000
        let pad = 0.15
        let bounds = GeoBounds(minLat: max(-90, r.center.latitude - r.span.latitudeDelta * (0.5 + pad)),
                               minLon: r.center.longitude - r.span.longitudeDelta * (0.5 + pad),
                               maxLat: min(90, r.center.latitude + r.span.latitudeDelta * (0.5 + pad)),
                               maxLon: r.center.longitude + r.span.longitudeDelta * (0.5 + pad))
        var ids = index.points(in: bounds) { $0.kind.shownWithinKm >= km }
        if ids.count > Self.maxShown {
            let centre = GeoPoint(lat: r.center.latitude, lon: r.center.longitude)
            ids.sort { a, b in
                let pa = index.points[a], pb = index.points[b]
                if pa.kind.priority != pb.kind.priority { return pa.kind.priority > pb.kind.priority }
                return Geo.distance(pa.point, centre) < Geo.distance(pb.point, centre)
            }
            ids = Array(ids.prefix(Self.maxShown))
        }
        let wanted = Set(ids)
        let gone = shown.filter { !wanted.contains($0.key) }
        mapView.removeAnnotations(Array(gone.values))
        for id in gone.keys { shown[id] = nil }
        let new = ids.filter { shown[$0] == nil }.map { AviationAnnotation(id: $0, place: index.points[$0]) }
        for a in new { shown[a.id] = a }
        mapView.addAnnotations(new)
    }

    /// You moved, or a spot was tapped: the labels' distances.
    func distancesChanged(me: GeoPoint?, pin: GeoPoint?) {
        guard me != self.me || pin != self.pin else { return }
        self.me = me
        self.pin = pin
        guard isOn else { return }
        for a in shown.values {
            (mapView.view(for: a) as? AviationView)?.show(a.place, me: me, pin: pin)
        }
    }

    /// The view for one of its places (from the map view delegate).
    func view(for a: AviationAnnotation) -> MKAnnotationView? {
        let v = mapView.dequeueReusableAnnotationView(withIdentifier: AviationView.reuseID, for: a) as? AviationView
        v?.show(a.place, me: me, pin: pin)
        return v
    }

    /// The place whose symbol (or label) is at a spot on the map, within a fingertip.
    func place(near spot: CGPoint) -> AviationAnnotation? {
        guard isOn else { return nil }
        var best: (a: AviationAnnotation, d: CGFloat)?
        for a in shown.values {
            let p = mapView.convert(a.coordinate, toPointTo: mapView)
            var d = hypot(p.x - spot.x, p.y - spot.y)
            if d >= 24, let v = mapView.view(for: a), !v.isHidden, v.alpha > 0.01,
               v.convert(v.bounds, to: mapView).contains(spot) {
                d = 23 // on its label
            }
            if d < 24, d < (best?.d ?? .infinity) { best = (a, d) }
        }
        return best?.a
    }
}

/// A place in drone mode: its chart symbol on the spot, and under it a label with its code and name, how far it is from
/// you (cyan) and from the tapped spot (amber), in nautical miles.
final class AviationView: MKAnnotationView {
    static let reuseID = "aviation"
    static let youColour = UIColor(red: 0.35, green: 0.86, blue: 1.0, alpha: 1)
    static let pinColour = UIColor(red: 1.0, green: 0.72, blue: 0.2, alpha: 1)

    private let symbol = CAShapeLayer()
    private let symbolHalo = CAShapeLayer()
    private let letter = CATextLayer()
    private let label = UILabel()
    private let card = UIView()
    private static let symbolSize: CGFloat = 22

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        symbolHalo.strokeColor = UIColor(white: 0, alpha: 0.65).cgColor
        symbolHalo.fillColor = UIColor(white: 0, alpha: 0.35).cgColor
        symbolHalo.lineWidth = 4.5
        layer.addSublayer(symbolHalo)
        symbol.strokeColor = UIColor.white.cgColor
        symbol.fillColor = nil
        symbol.lineWidth = 2
        symbol.lineCap = .round
        layer.addSublayer(symbol)
        letter.alignmentMode = .center
        letter.contentsScale = UIScreen.main.scale
        letter.foregroundColor = UIColor.white.cgColor
        letter.font = UIFont.systemFont(ofSize: 12, weight: .heavy)
        letter.fontSize = 12
        layer.addSublayer(letter)
        card.backgroundColor = UIColor(white: 0.05, alpha: 0.74)
        card.layer.cornerRadius = 8
        card.layer.cornerCurve = .continuous
        card.layer.borderColor = UIColor(white: 1, alpha: 0.18).cgColor
        card.layer.borderWidth = 0.5
        addSubview(card)
        label.numberOfLines = 0
        card.addSubview(label)
        canShowCallout = false
        collisionMode = .rectangle
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    /// The place and its distances.
    func show(_ place: AviationPoint, me: GeoPoint?, pin: GeoPoint?) {
        displayPriority = MKFeatureDisplayPriority(rawValue: place.kind.priority)
        drawSymbol(place)
        label.attributedText = Self.text(place, me: me, pin: pin)
        let size = label.sizeThatFits(CGSize(width: 190, height: 200))
        let s = Self.symbolSize
        let cardSize = CGSize(width: ceil(size.width) + 12, height: ceil(size.height) + 6)
        let w = max(s, cardSize.width), h = s + 3 + cardSize.height
        frame = CGRect(x: 0, y: 0, width: w, height: h)
        let symbolRect = CGRect(x: (w - s) / 2, y: 0, width: s, height: s)
        for l in [symbol, symbolHalo, letter] as [CALayer] { l.frame = symbolRect }
        card.frame = CGRect(x: (w - cardSize.width) / 2, y: s + 3, width: cardSize.width, height: cardSize.height)
        label.frame = card.bounds.insetBy(dx: 6, dy: 3)
        // The symbol's middle on the place.
        centerOffset = CGPoint(x: 0, y: h / 2 - s / 2)
        accessibilityLabel = "\(place.kind.title) \(place.label)"
    }

    private func drawSymbol(_ place: AviationPoint) {
        let s = Self.symbolSize
        let c = CGPoint(x: s / 2, y: s / 2)
        let path = CGMutablePath()
        var text = ""
        switch place.kind {
        case .largeAirport, .mediumAirport, .smallAirport, .seaplaneBase:
            // A ring with the runway through it, turned as the runway is.
            let r: CGFloat = place.kind == .smallAirport ? 6.5 : 8
            path.addEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            let a = CGFloat(Double(place.runwayHeading ?? 45) * .pi / 180)
            let l = r + 3
            path.move(to: CGPoint(x: c.x - sin(a) * l, y: c.y + cos(a) * l))
            path.addLine(to: CGPoint(x: c.x + sin(a) * l, y: c.y - cos(a) * l))
            if place.kind == .seaplaneBase { text = "~" }
        case .heliport:
            path.addEllipse(in: CGRect(x: c.x - 8, y: c.y - 8, width: 16, height: 16))
            text = "H"
        case .vor, .tacan:
            // A hexagon with a dot.
            for k in 0..<6 {
                let a = CGFloat(k) * .pi / 3
                let p = CGPoint(x: c.x + cos(a) * 8, y: c.y + sin(a) * 8)
                if k == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            path.closeSubpath()
            path.addEllipse(in: CGRect(x: c.x - 1.5, y: c.y - 1.5, width: 3, height: 3))
        case .dme:
            path.addRect(CGRect(x: c.x - 6.5, y: c.y - 6.5, width: 13, height: 13))
        case .ndb:
            // A ring of dots.
            for k in 0..<10 {
                let a = CGFloat(k) * .pi / 5
                let p = CGPoint(x: c.x + cos(a) * 7.5, y: c.y + sin(a) * 7.5)
                path.addEllipse(in: CGRect(x: p.x - 1.2, y: p.y - 1.2, width: 2.4, height: 2.4))
            }
            path.addEllipse(in: CGRect(x: c.x - 2, y: c.y - 2, width: 4, height: 4))
        }
        symbol.path = path
        symbolHalo.path = path
        letter.string = text
        letter.frame = CGRect(x: 0, y: 3, width: s, height: s - 3)
    }

    /// "FASZ Skukuza" (or "HGV VOR Hoedspruit"), then the distance from you, then from the tapped spot.
    private static func text(_ place: AviationPoint, me: GeoPoint?, pin: GeoPoint?) -> NSAttributedString {
        let out = NSMutableAttributedString()
        let bold = UIFont.systemFont(ofSize: 13, weight: .bold)
        let small = UIFont.systemFont(ofSize: 11.5, weight: .semibold)
        let digits = UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .bold)
        var name = place.shortName
        if name.count > 24 { name = String(name.prefix(23)) + "…" }
        if !place.code.isEmpty {
            out.append(NSAttributedString(string: place.code, attributes: [.font: bold, .foregroundColor: UIColor.white]))
            if !place.kind.isAerodrome {
                out.append(NSAttributedString(string: " " + place.kind.title, attributes: [.font: small, .foregroundColor: UIColor(white: 1, alpha: 0.75)]))
            }
            out.append(NSAttributedString(string: "  " + name, attributes: [.font: small, .foregroundColor: UIColor(white: 1, alpha: 0.85)]))
        } else {
            out.append(NSAttributedString(string: name, attributes: [.font: small, .foregroundColor: UIColor.white]))
        }
        func line(_ from: GeoPoint, _ symbol: String, _ colour: UIColor) {
            let d = Geo.distance(from, place.point)
            guard d >= 0.05 * Aviation.metresPerNauticalMile else { return } // the place itself
            out.append(NSAttributedString(string: "\n"))
            if let image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 10, weight: .bold))?
                .withTintColor(colour, renderingMode: .alwaysOriginal) {
                out.append(NSAttributedString(attachment: NSTextAttachment(image: image)))
                out.append(NSAttributedString(string: " "))
            }
            out.append(NSAttributedString(string: Aviation.nauticalMiles(d), attributes: [.font: digits, .foregroundColor: colour]))
        }
        if let me = me { line(me, "location.fill", youColour) }
        if let pin = pin { line(pin, "mappin", pinColour) }
        return out
    }
}
