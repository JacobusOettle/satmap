import MapKit
import UIKit

// MARK: - Overlay types (distinct classes so the renderer can style them)

final class BlockPolygons: MKMultiPolygon {}
final class BlockLines: MKMultiPolyline {}
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
    let coordinate: CLLocationCoordinate2D

    init(coordinate: CLLocationCoordinate2D) {
        self.coordinate = coordinate
        super.init()
    }
}

final class BlockLabelAnnotation: NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let isPoint: Bool

    init(coordinate: CLLocationCoordinate2D, title: String?, isPoint: Bool) {
        self.coordinate = coordinate
        self.title = title
        self.isPoint = isPoint
        super.init()
    }
}

// MARK: - Annotation views

/// Blue dot (grey while the GPS sleeps).
final class UserDotView: MKAnnotationView {
    static let reuseID = "user"
    private let dot = UIView()

    var sleeping = false {
        didSet { dot.backgroundColor = sleeping ? .systemGray : .systemBlue }
    }

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 22, height: 22)
        dot.frame = bounds
        dot.layer.cornerRadius = 11
        dot.layer.borderWidth = 3
        dot.layer.borderColor = UIColor.white.cgColor
        dot.backgroundColor = .systemBlue
        dot.layer.shadowColor = UIColor.black.cgColor
        dot.layer.shadowOpacity = 0.4
        dot.layer.shadowRadius = 2
        dot.layer.shadowOffset = .zero
        addSubview(dot)
        canShowCallout = false
        zPriority = .max
        displayPriority = .required
        collisionMode = .circle
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }
}

/// The measuring marker: a red ring with a centre point, placed where the crosshair was.
final class MeasureMarkerView: MKAnnotationView {
    static let reuseID = "marker"

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 30, height: 30)
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

/// Block name label (or a point placemark); MapKit hides labels that collide.
final class BlockLabelView: MKAnnotationView {
    static let reuseID = "blockLabel"
    private let label = UILabel()
    private let dot = UIView()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        label.font = .systemFont(ofSize: 12, weight: .semibold)
        label.textColor = .white
        label.shadowColor = UIColor(white: 0, alpha: 0.9)
        label.shadowOffset = CGSize(width: 0, height: 1)
        addSubview(label)
        dot.backgroundColor = .white
        dot.layer.cornerRadius = 4
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
        label.text = a.title
        label.sizeToFit()
        dot.isHidden = !a.isPoint
        let w = max(label.bounds.width, 8)
        let h = label.bounds.height + (a.isPoint ? 10 : 0)
        frame = CGRect(x: 0, y: 0, width: w, height: h)
        if a.isPoint {
            dot.frame = CGRect(x: w / 2 - 4, y: 0, width: 8, height: 8)
            label.frame.origin = CGPoint(x: 0, y: 10)
            centerOffset = CGPoint(x: 0, y: h / 2 - 4)
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
