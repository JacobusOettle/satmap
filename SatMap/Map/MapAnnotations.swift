import MapKit
import UIKit

// MARK: - Overlay types (distinct classes so the renderer can style them)

/// A KML file's polygons, and its lines, with one style (`KMLStyle`; nil: the app's own look).
final class BlockPolygons: MKMultiPolygon {
    var style: KMLStyle?
}

final class BlockLines: MKMultiPolyline {
    var style: KMLStyle?
}
final class DraftAreaPolygon: MKPolygon {}

// MARK: - Annotations

final class UserLocationAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D

    init(coordinate: CLLocationCoordinate2D) {
        self.coordinate = coordinate
        super.init()
    }
}

final class MeasureMarkerAnnotation: NSObject, MKAnnotation {
    /// Changes as the point is moved (`PointAdjuster`).
    @objc dynamic var coordinate: CLLocationCoordinate2D
    /// "A", "B", "C" … when two or more points are measured.
    let label: String?

    init(coordinate: CLLocationCoordinate2D, label: String?) {
        self.coordinate = coordinate
        self.label = label
        super.init()
    }
}

/// A spot tapped on the map, or a KML point (its coordinates are shown at the bottom); it can be moved.
final class SelectedPointAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D

    init(coordinate: CLLocationCoordinate2D) {
        self.coordinate = coordinate
        super.init()
    }
}

final class BlockLabelAnnotation: NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let isPoint: Bool
    /// A point's colour in its file (its icon's), if it has one.
    let color: UIColor?

    init(coordinate: CLLocationCoordinate2D, title: String?, isPoint: Bool, color: UIColor? = nil) {
        self.coordinate = coordinate
        self.title = title
        self.isPoint = isPoint
        self.color = color
        super.init()
    }
}

extension UIColor {
    convenience init(kml c: KMLColor) {
        self.init(red: CGFloat(c.red), green: CGFloat(c.green), blue: CGFloat(c.blue), alpha: CGFloat(c.alpha))
    }
}

// MARK: - Annotation views

/// Blue dot (grey while the GPS sleeps), with a small arrow beside it pointing where the phone points (the compass).
final class UserDotView: MKAnnotationView {
    static let reuseID = "user"
    private let dot = UIView()
    private let arrow = CAShapeLayer()

    var sleeping = false {
        didSet { updateColour() }
    }

    /// Where the arrow points on the screen, in degrees clockwise from up; nil hides it.
    var arrowAngle: Double? {
        didSet {
            guard arrowAngle != oldValue else { return }
            turnArrow(from: oldValue)
        }
    }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 22, height: 22)
        // A triangle just outside the dot, its base 16 points wide and its tip 12 points out, turning about the dot's
        // middle.
        arrow.frame = bounds
        let c = CGPoint(x: bounds.midX, y: bounds.midY)
        let base = c.y - 11 - 2.5
        let path = CGMutablePath()
        path.move(to: CGPoint(x: c.x, y: base - 12))
        path.addLine(to: CGPoint(x: c.x + 8, y: base))
        path.addLine(to: CGPoint(x: c.x - 8, y: base))
        path.closeSubpath()
        arrow.path = path
        arrow.lineWidth = 2
        arrow.lineJoin = .round
        arrow.strokeColor = UIColor.white.cgColor
        arrow.shadowColor = UIColor.black.cgColor
        arrow.shadowOpacity = 0.4
        arrow.shadowRadius = 2
        arrow.shadowOffset = .zero
        arrow.isHidden = true
        layer.addSublayer(arrow)
        dot.frame = bounds
        dot.layer.cornerRadius = 11
        dot.layer.borderWidth = 3
        dot.layer.borderColor = UIColor.white.cgColor
        dot.layer.shadowColor = UIColor.black.cgColor
        dot.layer.shadowOpacity = 0.4
        dot.layer.shadowRadius = 2
        dot.layer.shadowOffset = .zero
        addSubview(dot)
        updateColour()
        canShowCallout = false
        zPriority = .max
        displayPriority = .required
        collisionMode = .circle
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    private func updateColour() {
        let colour: UIColor = sleeping ? .systemGray : .systemBlue
        dot.backgroundColor = colour
        arrow.fillColor = colour.cgColor
    }

    /// Small turns glide (the compass reports every 2°); larger ones, and the arrow appearing, jump.
    private func turnArrow(from old: Double?) {
        CATransaction.begin()
        defer { CATransaction.commit() }
        guard let angle = arrowAngle else {
            CATransaction.setDisableActions(true)
            arrow.isHidden = true
            return
        }
        var turn = abs((old ?? angle + 180) - angle).truncatingRemainder(dividingBy: 360)
        if turn > 180 { turn = 360 - turn }
        if turn > 45 || arrow.isHidden {
            CATransaction.setDisableActions(true)
        } else {
            CATransaction.setAnimationDuration(0.2)
        }
        arrow.isHidden = false
        arrow.setAffineTransform(CGAffineTransform(rotationAngle: CGFloat(angle * .pi / 180)))
    }
}

/// A measuring point: a red ring with a centre point, labelled A or B when there are two.
final class MeasureMarkerView: MKAnnotationView {
    static let reuseID = "marker"
    private let letter = UILabel()

    override var annotation: MKAnnotation? {
        didSet { letter.text = (annotation as? MeasureMarkerAnnotation)?.label }
    }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 30, height: 30)
        letter.frame = CGRect(x: 18, y: -14, width: 34, height: 18)
        letter.font = .systemFont(ofSize: 15, weight: .heavy)
        letter.textColor = .white
        letter.shadowColor = UIColor(white: 0, alpha: 0.9)
        letter.shadowOffset = CGSize(width: 0, height: 1)
        letter.text = (annotation as? MeasureMarkerAnnotation)?.label
        addSubview(letter)
        let ring = CAShapeLayer()
        ring.path = UIBezierPath(ovalIn: bounds.insetBy(dx: 3, dy: 3)).cgPath
        ring.fillColor = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 0.25).cgColor
        ring.strokeColor = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 1).cgColor
        ring.lineWidth = 3
        layer.addSublayer(ring)
        let centre = CAShapeLayer()
        centre.path = UIBezierPath(ovalIn: CGRect(x: 12, y: 12, width: 6, height: 6)).cgPath
        centre.fillColor = UIColor.white.cgColor
        layer.addSublayer(centre)
        canShowCallout = false
        zPriority = .defaultSelected
        displayPriority = .required
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }
}

/// A tapped spot: a small white ring with a dark centre.
final class SelectedPointView: MKAnnotationView {
    static let reuseID = "selected"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 22, height: 22)
        let ring = CAShapeLayer()
        ring.path = UIBezierPath(ovalIn: bounds.insetBy(dx: 2, dy: 2)).cgPath
        ring.fillColor = UIColor(white: 0, alpha: 0.25).cgColor
        ring.strokeColor = UIColor.white.cgColor
        ring.lineWidth = 3
        ring.shadowColor = UIColor.black.cgColor
        ring.shadowOpacity = 0.5
        ring.shadowRadius = 2
        ring.shadowOffset = .zero
        layer.addSublayer(ring)
        let centre = CAShapeLayer()
        centre.path = UIBezierPath(ovalIn: CGRect(x: 9, y: 9, width: 4, height: 4)).cgPath
        centre.fillColor = UIColor.white.cgColor
        layer.addSublayer(centre)
        canShowCallout = false
        zPriority = .defaultSelected
        displayPriority = .required
        collisionMode = .none // a selected KML point keeps its own dot and name under the ring
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }
}

/// Block name label, or a point placemark (a launch point …: a larger name, kept before block names when they
/// collide; a tap selects it); MapKit hides labels that collide.
final class BlockLabelView: MKAnnotationView {
    static let reuseID = "blockLabel"
    private let label = UILabel()
    private let dot = UIView()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        label.textColor = .white
        label.shadowColor = UIColor(white: 0, alpha: 0.9)
        label.shadowOffset = CGSize(width: 0, height: 1)
        addSubview(label)
        dot.backgroundColor = .white
        dot.layer.cornerRadius = 5
        dot.layer.borderColor = UIColor.black.cgColor
        dot.layer.borderWidth = 1
        addSubview(dot)
        canShowCallout = false
        collisionMode = .rectangle
        displayPriority = .defaultLow
        isUserInteractionEnabled = false
        configure()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override var annotation: MKAnnotation? {
        didSet { configure() }
    }

    private func configure() {
        guard let a = annotation as? BlockLabelAnnotation else { return }
        label.font = .systemFont(ofSize: a.isPoint ? 15 : 13, weight: a.isPoint ? .bold : .semibold)
        label.text = a.title
        label.sizeToFit()
        dot.isHidden = !a.isPoint
        dot.backgroundColor = a.color ?? .white
        displayPriority = a.isPoint ? .defaultHigh : .defaultLow
        let w = max(label.bounds.width, 10)
        let h = label.bounds.height + (a.isPoint ? 12 : 0)
        frame = CGRect(x: 0, y: 0, width: w, height: h)
        if a.isPoint {
            // The dot on the point itself, the name under it.
            dot.frame = CGRect(x: w / 2 - 5, y: 0, width: 10, height: 10)
            label.frame.origin = CGPoint(x: 0, y: 12)
            centerOffset = CGPoint(x: 0, y: h / 2 - 5)
        } else {
            label.frame.origin = .zero
            centerOffset = .zero
        }
    }
}

// MARK: - Lasso

/// Captures one finger drawing a closed loop around the area to download.
final class LassoView: UIView {
    var onFinish: (([CGPoint]) -> Void)?
    private var points: [CGPoint] = []
    private let shape = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 0, alpha: 0.08)
        shape.strokeColor = UIColor.white.cgColor
        shape.fillColor = UIColor(white: 1, alpha: 0.15).cgColor
        shape.lineWidth = 3
        shape.lineJoin = .round
        shape.lineCap = .round
        shape.lineDashPattern = [8, 6]
        layer.addSublayer(shape)
        isMultipleTouchEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func reset() {
        points = []
        shape.path = nil
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        points = [t.location(in: self)]
        redraw()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        let p = t.location(in: self)
        if let last = points.last, hypot(p.x - last.x, p.y - last.y) < 3 { return }
        points.append(p)
        redraw()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let done = points
        if done.count >= 3 {
            let path = UIBezierPath()
            path.move(to: done[0])
            done.dropFirst().forEach { path.addLine(to: $0) }
            path.close()
            shape.path = path.cgPath
        }
        onFinish?(done)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        reset()
    }

    private func redraw() {
        let path = UIBezierPath()
        guard let first = points.first else { return }
        path.move(to: first)
        points.dropFirst().forEach { path.addLine(to: $0) }
        shape.path = path.cgPath
    }
}
