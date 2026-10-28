import Combine
import MapKit
import SwiftUI
import UIKit

/// Owns the MKMapView: the satellite imagery with contour lines and elevation colours, the recorded track, the
/// measuring line, imported blocks, your position with the compass and the lasso used to choose offline areas.
final class MapController: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    let container = MapContainerView()
    let mapView = MKMapView()
    private let lasso = LassoView()
    /// While aiming the measuring tool: the dashed line from you to the crosshair. Drawn over the map rather than as
    /// a map overlay, so it follows a pan frame by frame.
    private let aimLineView = UIView()
    private let aimLine = CAShapeLayer()
    private let model: AppModel

    /// The imagery SatMap draws, with the contour lines and elevation colours.
    private var imagery: DrawnImagery!
    private var blocks: BlockLayers!
    private let track = TrackOverlay()
    private let measureLine = MeasureLineOverlay()
    private var trackRenderer: TrackRenderer?
    private var measureRenderer: MeasureLineRenderer?

    /// A single tap on the map. It waits until the tap can't be the start of a double-tap zoom, so where the
    /// finger was is recorded straight away by `touchRecorder` (the map may move meanwhile, e.g. following you).
    private let tapRecognizer = UITapGestureRecognizer()
    private let touchRecorder = UITapGestureRecognizer()
    private var lastTouch: (point: CGPoint, coordinate: CLLocationCoordinate2D)?
    private var userAnnotation: UserLocationAnnotation?
    private var markerAnnotations: [MeasureMarkerAnnotation] = []
    private var selectedAnnotation: SelectedPointAnnotation?
    private var draftOverlay: DraftAreaPolygon?
    private var drawnTrackCount = 0
    /// Where the phone points (the compass), in degrees from north.
    private var heading: CLLocationDirection?

    /// Whether the phone's own screen shows the map (its window scene in the foreground). With CarPlay showing SatMap
    /// the app stays in the foreground while the phone is locked.
    private var isShown = UIApplication.shared.applicationState != .background
    var isOnScreen: Bool { isShown }
    private var cancellables = Set<AnyCancellable>()
    /// No saved camera yet: zoom in on the first GPS fix.
    private let firstLaunch = UserDefaults.standard.array(forKey: "camera") == nil
    private var hasCentredOnFirstFix = false
    /// Until then a new position doesn't recentre the map, which would cut short a zoom onto you.
    private var keepCameraUntil: CFTimeInterval = 0

    init(model: AppModel) {
        self.model = model
        super.init()

        let config = MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .muted)
        config.pointOfInterestFilter = .excludingAll
        config.showsTraffic = false
        mapView.preferredConfiguration = config
        mapView.selectableMapFeatures = []
        mapView.delegate = self
        mapView.showsCompass = false // shown in the controls column instead, where panels don't cover it
        mapView.showsScale = false
        mapView.isPitchEnabled = false
        mapView.showsUserLocation = false // MapKit's own location tracking would keep the GPS on at full power
        mapView.register(UserDotView.self, forAnnotationViewWithReuseIdentifier: UserDotView.reuseID)
        mapView.register(MeasureMarkerView.self, forAnnotationViewWithReuseIdentifier: MeasureMarkerView.reuseID)
        mapView.register(SelectedPointView.self, forAnnotationViewWithReuseIdentifier: SelectedPointView.reuseID)
        mapView.register(BlockLabelView.self, forAnnotationViewWithReuseIdentifier: BlockLabelView.reuseID)

        // Later overlays draw on top: imagery with contours, (blocks inserted below the track), track, measuring line.
        imagery = DrawnImagery(mapView: mapView, isVisible: { [weak self] in self?.isShown ?? false },
                               terrain: { [weak model] in model?.terrainMode ?? .contours })
        imagery.onKey = { [weak model] key in model?.legend.update(key) }
        imagery.addToMap()
        track.reset(model.track)
        drawnTrackCount = model.track.count
        mapView.addOverlay(track, level: .aboveLabels)
        mapView.addOverlay(measureLine, level: .aboveLabels)
        blocks = BlockLayers(mapView: mapView, below: track)

        container.addSubview(mapView)
        mapView.translatesAutoresizingMaskIntoConstraints = false
        aimLineView.frame = container.bounds
        aimLineView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        aimLineView.isUserInteractionEnabled = false
        aimLine.strokeColor = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 1).cgColor // as MeasureLineRenderer
        aimLine.fillColor = nil
        aimLine.lineWidth = 3
        aimLine.lineDashPattern = [10, 7]
        aimLineView.layer.addSublayer(aimLine)
        container.addSubview(aimLineView)
        lasso.translatesAutoresizingMaskIntoConstraints = false
        lasso.isHidden = true
        container.addSubview(lasso)
        NSLayoutConstraint.activate([
            mapView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            mapView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            mapView.topAnchor.constraint(equalTo: container.topAnchor),
            mapView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            lasso.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            lasso.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            lasso.topAnchor.constraint(equalTo: container.topAnchor),
            lasso.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
        lasso.onFinish = { [weak self] pts in self?.lassoFinished(pts) }
        // Laid out again when the screen turns: the compass follows its top.
        container.onLayout = { [weak self] in
            guard let self = self, let o = self.container.window?.windowScene?.interfaceOrientation else { return }
            self.model.recorder.screenOrientation = o
        }

        tapRecognizer.addTarget(self, action: #selector(mapTapped(_:)))
        tapRecognizer.delegate = self
        mapView.addGestureRecognizer(tapRecognizer)
        // A tap then a second press is a double-tap zoom or a one-finger zoom (tap, then drag): not a tap on a spot.
        let tapThenPress = UILongPressGestureRecognizer(target: nil, action: nil)
        tapThenPress.numberOfTapsRequired = 1
        tapThenPress.minimumPressDuration = 0
        tapThenPress.cancelsTouchesInView = false
        tapThenPress.delaysTouchesEnded = false
        tapThenPress.delegate = self
        mapView.addGestureRecognizer(tapThenPress)
        tapRecognizer.require(toFail: tapThenPress)
        touchRecorder.addTarget(self, action: #selector(touchEnded(_:)))
        touchRecorder.delegate = self
        touchRecorder.cancelsTouchesInView = false
        mapView.addGestureRecognizer(touchRecorder)

        restoreCamera()
        bind()
        ZoomScript.runIfAsked(on: mapView, model: model)
        if let loc = model.recorder.location { locationUpdated(loc) }

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(sceneDidEnterBackground(_:)), name: UIScene.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(sceneWillEnterForeground(_:)), name: UIScene.willEnterForegroundNotification, object: nil)
        model.recorder.headingWanted = isShown
    }

    private func bind() {
        model.$trackRevision
            .sink { [weak self] _ in DispatchQueue.main.async { self?.trackChanged() } }
            .store(in: &cancellables)
        model.$measure
            .removeDuplicates()
            .sink { [weak self] m in DispatchQueue.main.async { self?.measureChanged(m) } }
            .store(in: &cancellables)
        model.$selectedPoint.combineLatest(model.$measure.map { $0 == .off })
            .sink { [weak self] point, notMeasuring in
                DispatchQueue.main.async { self?.selectedPointChanged(notMeasuring ? point : nil) }
            }
            .store(in: &cancellables)
        model.$terrainMode
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] mode in DispatchQueue.main.async { self?.imagery.terrainChanged(mode) } }
            .store(in: &cancellables)
        model.kml.$files.combineLatest(model.kml.$useFileColours)
            .sink { [weak self] files, colours in DispatchQueue.main.async { self?.blocks.update(files, useFileColours: colours) } }
            .store(in: &cancellables)
        model.$isDrawingArea.combineLatest(model.$lassoActive, model.$draftArea.map { $0 != nil })
            .sink { [weak self] drawing, lassoActive, hasDraft in
                DispatchQueue.main.async { self?.lasso.isHidden = !(drawing && lassoActive && !hasDraft) }
            }
            .store(in: &cancellables)
        model.$isDrawingArea
            .removeDuplicates()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.lasso.reset() } }
            .store(in: &cancellables)
        model.$draftArea
            .sink { [weak self] ring in DispatchQueue.main.async { self?.draftChanged(ring) } }
            .store(in: &cancellables)
        model.recorder.$power
            .removeDuplicates()
            .sink { [weak self] power in DispatchQueue.main.async { self?.powerChanged(power) } }
            .store(in: &cancellables)
    }

    /// Tiles may be had again (`AppModel.requestReload`): a new layer asks again for those shown with a stand-in.
    func reloadImagery() {
        imagery.reload()
    }

    /// The coordinate under the crosshair (the middle of the view). `mapView.centerCoordinate` can be offset by
    /// MapKit's safe-area margins, which differ at the top and bottom on notched iPhones.
    var centerCoordinate: CLLocationCoordinate2D {
        mapView.convert(CGPoint(x: mapView.bounds.midX, y: mapView.bounds.midY), toCoordinateFrom: mapView)
    }

    /// How far apart two places are on the screen, in points.
    func pointsApart(_ a: GeoPoint, _ b: GeoPoint) -> CGFloat {
        let pa = mapView.convert(CLLocationCoordinate2D(latitude: a.lat, longitude: a.lon), toPointTo: mapView)
        let pb = mapView.convert(CLLocationCoordinate2D(latitude: b.lat, longitude: b.lon), toPointTo: mapView)
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /// Map points per screen point at the current zoom, for redraw margins.
    private var mapPointsPerPoint: Double {
        mapView.bounds.width > 0 ? mapView.visibleMapRect.width / Double(mapView.bounds.width) : 1
    }

    // MARK: - Camera

    func center(on c: CLLocationCoordinate2D, animated: Bool) {
        mapView.setCenter(c, animated: animated)
    }

    /// Centres on a point (the map is already near it) with the screen this many metres across, however the map is
    /// turned: the camera height scaled by how far the screen reaches now, rather than asking for a 300 m rect, which
    /// MapKit lays out within the safe area (it centres there), so it could come out wider on a notched iPhone.
    func zoom(onto c: CLLocationCoordinate2D, metersAcross: Double) {
        let size = mapView.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        keepCameraUntil = CACurrentMediaTime() + 1
        guard let perPoint = metersPerPoint, let camera = mapView.camera.copy() as? MKMapCamera else { return }
        let across = perPoint * Double(size.width)
        camera.centerCoordinate = c
        camera.centerCoordinateDistance *= metersAcross / across
        mapView.setCamera(camera, animated: true)
    }

    func zoom(to b: GeoBounds) {
        let span = MKCoordinateSpan(latitudeDelta: max(0.002, (b.maxLat - b.minLat) * 1.25),
                                    longitudeDelta: max(0.002, (b.maxLon - b.minLon) * 1.25))
        model.following = false
        mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: b.center.lat, longitude: b.center.lon), span: span), animated: true)
    }

    private func restoreCamera() {
        if let v = UserDefaults.standard.array(forKey: "camera") as? [Double], v.count == 4 {
            mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: v[0], longitude: v[1]),
                                                 span: MKCoordinateSpan(latitudeDelta: v[2], longitudeDelta: v[3])), animated: false)
        } else {
            // First launch: South Africa until the first GPS fix arrives.
            mapView.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -28.5, longitude: 24.7),
                                                 span: MKCoordinateSpan(latitudeDelta: 14, longitudeDelta: 14)), animated: false)
        }
    }

    private func saveCamera() {
        let r = mapView.region
        UserDefaults.standard.set([r.center.latitude, r.center.longitude, r.span.latitudeDelta, r.span.longitudeDelta], forKey: "camera")
    }

    func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
        if userIsTouchingMap() { model.following = false }
        imagery?.regionWillChange()
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        saveCamera()
        updateAim()
        updateScale()
        imagery?.regionDidChange()
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        updateAim()
        updateScale()
        if heading != nil { updateArrow() } // the map may have turned
        imagery?.visibleRegionChanged()
    }

    private func updateScale() {
        if let m = metersPerPoint { model.scale.update(metersPerPoint: m) }
    }

    /// Ground metres per screen point at the crosshair, measured across 100 points around it: right however the map
    /// is turned, and at any zoom (edge to edge, a zoomed-out map's sides can be half the globe apart or more).
    private var metersPerPoint: Double? {
        let b = mapView.bounds
        guard b.width > 100 else { return nil }
        let l = mapView.convert(CGPoint(x: b.midX - 50, y: b.midY), toCoordinateFrom: mapView)
        let r = mapView.convert(CGPoint(x: b.midX + 50, y: b.midY), toCoordinateFrom: mapView)
        let m = Geo.distance(GeoPoint(lat: l.latitude, lon: l.longitude), GeoPoint(lat: r.latitude, lon: r.longitude))
        return m > 0 ? m / 100 : nil
    }

    private func userIsTouchingMap() -> Bool {
        for view in mapView.subviews {
            for g in view.gestureRecognizers ?? [] where g.state == .began || g.state == .changed || g.state == .ended {
                return true
            }
        }
        return false
    }

    // MARK: - Location

    func locationUpdated(_ loc: CLLocation) {
        guard isShown else { return }
        if let a = userAnnotation {
            a.coordinate = loc.coordinate
        } else {
            let a = UserLocationAnnotation(coordinate: loc.coordinate)
            userAnnotation = a
            mapView.addAnnotation(a)
        }
        if model.following {
            if firstLaunch && !hasCentredOnFirstFix {
                mapView.setRegion(MKCoordinateRegion(center: loc.coordinate, latitudinalMeters: 1500, longitudinalMeters: 1500), animated: false)
            } else if CACurrentMediaTime() >= keepCameraUntil {
                mapView.setCenter(loc.coordinate, animated: true)
            }
            hasCentredOnFirstFix = true
        }
        updateMeasureLine()
        updateAim()
    }

    private func powerChanged(_ power: LocationRecorder.Power) {
        guard let a = userAnnotation, let v = mapView.view(for: a) as? UserDotView else { return }
        v.sleeping = power == .sleeping
    }

    /// The compass: the arrow by your dot points where the phone points (nil: no arrow).
    func headingUpdated(_ h: CLLocationDirection?) {
        heading = h
        updateArrow()
    }

    /// The arrow's angle on the screen: the heading less the map's own turn.
    private func updateArrow() {
        guard let a = userAnnotation, let v = mapView.view(for: a) as? UserDotView else { return }
        v.arrowAngle = arrowAngle
    }

    private var arrowAngle: Double? {
        heading.map { ($0 - mapView.camera.heading + 360).truncatingRemainder(dividingBy: 360) }
    }

    // MARK: - Track

    private func trackChanged() {
        guard isShown else { return }
        let points = model.track
        if points.count < drawnTrackCount {
            // Cleared.
            track.reset(points)
            drawnTrackCount = points.count
            trackRenderer?.setNeedsDisplay()
            return
        }
        guard points.count > drawnTrackCount else { return }
        var dirty = MKMapRect.null
        for p in points[drawnTrackCount...] { dirty = dirty.union(track.append(p)) }
        drawnTrackCount = points.count
        let margin = 12 * mapPointsPerPoint
        trackRenderer?.setNeedsDisplay(dirty.insetBy(dx: -margin, dy: -margin))
    }

    // MARK: - Tapping the map

    /// A single tap (not the first tap of a double-tap zoom) on the map, outside lasso drawing.
    @objc private func touchEnded(_ g: UITapGestureRecognizer) {
        guard g.state == .ended else { return }
        let point = g.location(in: mapView)
        lastTouch = (point, mapView.convert(point, toCoordinateFrom: mapView))
    }

    @objc private func mapTapped(_ g: UITapGestureRecognizer) {
        guard g.state == .ended, !model.isDrawingArea else { return }
        let point = g.location(in: mapView)
        var c = mapView.convert(point, toCoordinateFrom: mapView)
        if let t = lastTouch, hypot(t.point.x - point.x, t.point.y - point.y) < 1 { c = t.coordinate }
        lastTouch = nil
        // A tap on point A, with three or more points, closes the shape.
        if case .path(let pts) = model.measure, pts.count >= 3 {
            let a = mapView.convert(CLLocationCoordinate2D(latitude: pts[0].lat, longitude: pts[0].lon), toPointTo: mapView)
            if hypot(a.x - point.x, a.y - point.y) < 30 {
                model.closeMeasureShape()
                return
            }
        }
        model.mapTapped(GeoPoint(lat: c.latitude, lon: c.longitude))
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
        g === tapRecognizer && (other as? UITapGestureRecognizer)?.numberOfTapsRequired == 2
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    private func selectedPointChanged(_ p: GeoPoint?) {
        if let a = selectedAnnotation {
            mapView.removeAnnotation(a)
            selectedAnnotation = nil
        }
        guard let p = p else { return }
        let a = SelectedPointAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon))
        selectedAnnotation = a
        mapView.addAnnotation(a)
    }

    // MARK: - Measuring

    private func measureChanged(_ m: MeasureMode) {
        mapView.removeAnnotations(markerAnnotations)
        let points = m.points
        markerAnnotations = points.enumerated().map { i, p in
            MeasureMarkerAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon),
                                    label: points.count >= 2 ? MeasureMode.label(i) : nil)
        }
        mapView.addAnnotations(markerAnnotations)
        updateMeasureLine()
        updateAim()
    }

    /// While aiming: the spot under the crosshair (for the live distance and height) and the line from you to it.
    private func updateAim() {
        guard model.measure == .aiming else {
            if aimLine.path != nil { aimLine.path = nil }
            return
        }
        let c = centerCoordinate
        model.aim.set(GeoPoint(lat: c.latitude, lon: c.longitude))
        var path: CGPath?
        var phase: CGFloat = 0
        if let me = model.recorder.location?.coordinate {
            let a = mapView.convert(me, toPointTo: aimLineView)
            let b = CGPoint(x: aimLineView.bounds.midX, y: aimLineView.bounds.midY)
            let length = hypot(b.x - a.x, b.y - a.y)
            let dot: CGFloat = 11 // from the edge of your dot
            if length > dot + 4 {
                let edge = CGPoint(x: a.x + (b.x - a.x) / length * dot, y: a.y + (b.y - a.y) / length * dot)
                // You off screen: from where the line comes onto it, with the dashes where they'd be.
                let start = Self.entry(from: edge, to: b, into: aimLineView.bounds.insetBy(dx: -20, dy: -20))
                phase = hypot(start.x - edge.x, start.y - edge.y).truncatingRemainder(dividingBy: 17)
                let p = CGMutablePath()
                p.move(to: start)
                p.addLine(to: b)
                path = p
            }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        aimLine.path = path
        aimLine.lineDashPhase = phase
        CATransaction.commit()
    }

    /// Where the line from `a` to `b` (inside `r`) comes into `r`; `a` itself when it is inside.
    private static func entry(from a: CGPoint, to b: CGPoint, into r: CGRect) -> CGPoint {
        var t: CGFloat = 0
        if a.x < r.minX { t = max(t, (r.minX - a.x) / (b.x - a.x)) }
        if a.x > r.maxX { t = max(t, (a.x - r.maxX) / (a.x - b.x)) }
        if a.y < r.minY { t = max(t, (r.minY - a.y) / (b.y - a.y)) }
        if a.y > r.maxY { t = max(t, (a.y - r.maxY) / (a.y - b.y)) }
        return CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
    }

    /// From you to the point, or through the points (back to the first when the shape is closed).
    private func updateMeasureLine() {
        var line: [CLLocationCoordinate2D] = []
        var closed = false
        switch model.measure {
        case .placed(let p):
            if let me = model.recorder.location?.coordinate {
                line = [CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), me]
            }
        case .path(let pts):
            line = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        case .closed(let pts):
            line = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
            closed = true
        case .off, .aiming:
            break
        }
        let margin = 8 * mapPointsPerPoint
        for rect in measureLine.set(line, closed: closed) {
            measureRenderer?.setNeedsDisplay(rect.insetBy(dx: -margin, dy: -margin))
        }
    }

    // MARK: - Lasso (choose an offline area)

    private func lassoFinished(_ pts: [CGPoint]) {
        let simplified = Simplify.douglasPeucker(pts.map { (x: Double($0.x), y: Double($0.y)) }, tolerance: 4)
        guard pts.count >= 3, simplified.count >= 3 else {
            // A tap or a straight stroke isn't an area: clear it and let the user draw a loop.
            lasso.reset()
            model.finishDrawing([])
            return
        }
        let ring = simplified.map { p -> GeoPoint in
            let c = mapView.convert(CGPoint(x: p.x, y: p.y), toCoordinateFrom: mapView)
            return GeoPoint(lat: c.latitude, lon: c.longitude)
        }
        model.finishDrawing(ring)
    }

    private func draftChanged(_ ring: [GeoPoint]?) {
        if let d = draftOverlay {
            mapView.removeOverlay(d)
            draftOverlay = nil
        }
        guard let ring = ring, ring.count >= 3 else { return }
        lasso.reset()
        let c = ring.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        let poly = DraftAreaPolygon(coordinates: c, count: c.count)
        draftOverlay = poly
        mapView.addOverlay(poly, level: .aboveLabels)
    }

    // MARK: - App state

    /// The phone's screen (its window scene; the car's is a CarPlay scene) went to the background or came back. The
    /// app-wide work for the background is in `AppModel`.
    @objc private func sceneDidEnterBackground(_ n: Notification) {
        guard n.object is UIWindowScene else { return }
        isShown = false
        model.recorder.headingWanted = false
        saveCamera()
    }

    @objc private func sceneWillEnterForeground(_ n: Notification) {
        guard n.object is UIWindowScene else { return }
        isShown = true
        model.recorder.headingWanted = true
        imagery.becameVisible()
        trackChanged()
        if let loc = model.recorder.location { locationUpdated(loc) }
    }

    // MARK: - MKMapViewDelegate rendering

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        switch overlay {
        case let o as SatelliteDrawnOverlay:
            return imagery.renderer(for: o)
        case let o as TrackOverlay:
            let r = TrackRenderer(overlay: o)
            trackRenderer = r
            return r
        case let o as MeasureLineOverlay:
            let r = MeasureLineRenderer(overlay: o)
            measureRenderer = r
            return r
        case let o as BlockPolygons:
            return BlockLayers.renderer(o)
        case let o as BlockLines:
            return BlockLayers.renderer(o)
        case let o as DraftAreaPolygon:
            let r = MKPolygonRenderer(polygon: o)
            r.strokeColor = .white
            r.fillColor = UIColor(white: 1, alpha: 0.15)
            r.lineWidth = 2.5
            r.lineDashPattern = [8, 6]
            return r
        default:
            return MKOverlayRenderer(overlay: overlay)
        }
    }

    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        switch annotation {
        case is UserLocationAnnotation:
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: UserDotView.reuseID, for: annotation) as? UserDotView
            v?.sleeping = model.recorder.power == .sleeping
            v?.arrowAngle = arrowAngle
            return v
        case is MeasureMarkerAnnotation:
            return mapView.dequeueReusableAnnotationView(withIdentifier: MeasureMarkerView.reuseID, for: annotation)
        case is SelectedPointAnnotation:
            return mapView.dequeueReusableAnnotationView(withIdentifier: SelectedPointView.reuseID, for: annotation)
        case is BlockLabelAnnotation:
            return mapView.dequeueReusableAnnotationView(withIdentifier: BlockLabelView.reuseID, for: annotation)
        default:
            return nil
        }
    }
}

/// The map's view in the SwiftUI screen, which says when it is laid out (as when the screen turns).
final class MapContainerView: UIView {
    var onLayout: (() -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

/// Hosts the MapController in SwiftUI.
struct MapViewRepresentable: UIViewRepresentable {
    let model: AppModel

    func makeCoordinator() -> MapController {
        MapController(model: model)
    }

    func makeUIView(context: Context) -> UIView {
        ContourRenderer.shared.screenScale = Double(context.environment.displayScale)
        model.map = context.coordinator
        return context.coordinator.container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
