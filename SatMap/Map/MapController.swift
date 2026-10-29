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
    /// While a measuring point is moved: the measuring line, drawn over the map like the aiming line so it follows the
    /// point frame by frame (the map's own line would be repainted, blinking, at every move); the map's comes back after.
    private let dragLine = CAShapeLayer()
    private var dragLineShown = false
    private let model: AppModel

    /// The imagery SatMap draws, with the contour lines and elevation colours.
    private var imagery: DrawnImagery!
    private var blocks: BlockLayers!
    /// Drone mode: aerodromes, strips, helipads and navaids, with distances from you and the tapped spot.
    private var aviation: AviationLayer!
    private let track = TrackOverlay()
    private let measureLine = MeasureLineOverlay()
    private var trackRenderer: TrackRenderer?
    private var measureRenderer: MeasureLineRenderer?

    /// A single tap on the map. It waits until the tap can't be the start of a double-tap zoom, so where the
    /// finger was is recorded straight away by `touchRecorder` (the map may move meanwhile, e.g. following you).
    private let tapRecognizer = UITapGestureRecognizer()
    private let touchRecorder = UITapGestureRecognizer()
    /// A tap then a second press: a double-tap zoom or a one-finger zoom (tap, then drag).
    private let tapThenPress = UILongPressGestureRecognizer()
    /// Pressed and held, the tapped spot's pin or a measuring point is moved precisely (`PointAdjuster`); taps right
    /// after are the finger being lifted, not taps.
    private let adjustPress = UILongPressGestureRecognizer()
    private lazy var adjuster = PointAdjuster(mapView: mapView)
    private var lastAdjustUpdate: CFTimeInterval = 0
    private var tapsIgnoredUntil: CFTimeInterval = 0
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
        mapView.register(AviationView.self, forAnnotationViewWithReuseIdentifier: AviationView.reuseID)

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
        aviation = AviationLayer(mapView: mapView)
        aviation.isOn = model.terrainMode.showsAviation

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
        dragLine.strokeColor = aimLine.strokeColor
        dragLine.fillColor = UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 0.15).cgColor // as MeasureLineRenderer
        dragLine.lineWidth = 3
        dragLine.lineDashPattern = [10, 7]
        aimLineView.layer.addSublayer(dragLine)
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
        tapThenPress.numberOfTapsRequired = 1
        tapThenPress.minimumPressDuration = 0
        tapThenPress.cancelsTouchesInView = false
        tapThenPress.delaysTouchesEnded = false
        tapThenPress.delegate = self
        mapView.addGestureRecognizer(tapThenPress)
        tapRecognizer.require(toFail: tapThenPress)
        adjustPress.addTarget(self, action: #selector(adjustPressed(_:)))
        adjustPress.minimumPressDuration = 0.35
        adjustPress.delegate = self
        mapView.addGestureRecognizer(adjustPress)
        // Lifting the finger after moving a point isn't a tap (which would move the pin or add a measuring point).
        tapRecognizer.require(toFail: adjustPress)
        adjuster.onMove = { [weak self] which, p, done in self?.adjusted(which, to: p, done: done) }
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
            .sink { [weak self] mode in
                DispatchQueue.main.async {
                    self?.imagery.terrainChanged(mode)
                    self?.aviation.isOn = mode.showsAviation
                }
            }
            .store(in: &cancellables)
        // Drone mode's distances: from you and from the tapped spot.
        model.$selectedPoint
            .sink { [weak self] p in DispatchQueue.main.async { self?.aviationDistances(pin: p) } }
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
        aviation?.regionChanged()
    }

    /// Drone mode's labels: how far each place is from you and from the tapped spot.
    private func aviationDistances(pin: GeoPoint?) {
        let me = model.recorder.location.map { GeoPoint(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
        aviation?.distancesChanged(me: me, pin: pin)
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        updateAim()
        if dragLineShown { updateDragLine() } // the map scrolls while a point is moved
        updateScale()
        if heading != nil { updateArrow() } // the map may have turned
        aviation?.headingChanged()
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
        aviationDistances(pin: model.selectedPoint)
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
        guard g.state == .ended, !model.isDrawingArea, CACurrentMediaTime() >= tapsIgnoredUntil, !adjuster.isActive else { return }
        let point = g.location(in: mapView)
        var c = mapView.convert(point, toCoordinateFrom: mapView)
        if let t = lastTouch, hypot(t.point.x - point.x, t.point.y - point.y) < 1 { c = t.coordinate }
        lastTouch = nil
        // A tap on a point in a KML file (a launch point …) selects it: its own position, with its name. Where the finger
        // was on the map, which may have moved since (following you).
        let spot = mapView.convert(c, toPointTo: mapView)
        if model.measure == .off, let k = kmlPoint(near: spot) {
            model.pointTapped(GeoPoint(lat: k.coordinate.latitude, lon: k.coordinate.longitude), name: k.title)
            return
        }
        // In drone mode, an aerodrome or navaid likewise: the distances to the others are then from it.
        if model.measure == .off, let a = aviation.place(near: spot) {
            model.pointTapped(a.place.point, name: "\(a.place.label) · \(a.place.kind.title)")
            return
        }
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

    /// The KML point whose dot is nearest a spot on the screen: within a fingertip of the dot, or on its name.
    private func kmlPoint(near spot: CGPoint) -> BlockLabelAnnotation? {
        var best: (a: BlockLabelAnnotation, d: CGFloat)?
        for case let a as BlockLabelAnnotation in mapView.annotations where a.isPoint {
            let p = mapView.convert(a.coordinate, toPointTo: mapView)
            var d = hypot(p.x - spot.x, p.y - spot.y)
            if d >= 26, let v = mapView.view(for: a), !v.isHidden, v.alpha > 0.01,
               v.convert(v.bounds, to: mapView).insetBy(dx: -6, dy: -6).contains(spot) {
                d = 25 // on its name
            }
            if d < 26, d < (best?.d ?? .infinity) { best = (a, d) }
        }
        return best?.a
    }

    /// A tap on a spot waits until it can't be a double-tap zoom, nor a two-finger tap (MapKit's zoom out).
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
        guard g === tapRecognizer, let tap = other as? UITapGestureRecognizer, tap !== tapRecognizer, tap !== touchRecorder else {
            return false
        }
        return tap.numberOfTapsRequired == 2 || tap.numberOfTouchesRequired == 2
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard g === adjustPress else { return true }
        // Not the press of a one-finger zoom (tap, then press) that happens to pause on a point.
        guard tapThenPress.state != .began && tapThenPress.state != .changed else { return false }
        return !model.isDrawingArea && movablePoint(near: g.location(in: mapView)) != nil
    }

    private func selectedPointChanged(_ p: GeoPoint?) {
        guard let p = p else {
            if let a = selectedAnnotation {
                mapView.removeAnnotation(a)
                selectedAnnotation = nil
            }
            return
        }
        let c = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
        if let a = selectedAnnotation {
            a.coordinate = c // moved (a new spot tapped, or dragged)
            return
        }
        let a = SelectedPointAnnotation(coordinate: c)
        selectedAnnotation = a
        mapView.addAnnotation(a)
    }

    // MARK: - Moving a point precisely

    /// The point a press would move: a measuring point, or the tapped spot's pin, within a fingertip of the spot.
    private func movablePoint(near spot: CGPoint) -> (which: MovablePoint, point: GeoPoint)? {
        func apart(_ p: GeoPoint) -> CGFloat {
            let s = mapView.convert(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), toPointTo: mapView)
            return hypot(s.x - spot.x, s.y - spot.y)
        }
        let points = model.measure.points
        if let i = points.indices.min(by: { apart(points[$0]) < apart(points[$1]) }), apart(points[i]) < 30 {
            return (.measure(i), points[i])
        }
        if model.measure == .off, let p = model.selectedPoint, apart(p) < 30 { return (.selected, p) }
        return nil
    }

    @objc private func adjustPressed(_ g: UILongPressGestureRecognizer) {
        guard let window = mapView.window else { return }
        switch g.state {
        case .began:
            guard let target = movablePoint(near: g.location(in: mapView)) else { return }
            // The map holds still under the finger (it scrolls only with the point: `PointAdjuster`).
            mapView.isScrollEnabled = false
            mapView.isZoomEnabled = false
            mapView.isRotateEnabled = false
            model.following = false
            if case .measure = target.which {
                dragLineShown = true
                updateMeasureLine()
                updateDragLine()
            }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            adjuster.begin(target.which, at: target.point, touch: g.location(in: window), in: window,
                           scene: loupeScene(around: target.point, moving: target.which))
        case .changed:
            adjuster.moved(touch: g.location(in: window))
        case .ended, .cancelled, .failed:
            adjuster.end(cancelled: g.state != .ended)
            mapView.isScrollEnabled = true
            mapView.isZoomEnabled = true
            mapView.isRotateEnabled = true
            tapsIgnoredUntil = CACurrentMediaTime() + 0.4
        default:
            break
        }
    }

    /// The point moved: on the map at once; for the panels (distances, coordinates, heights) up to 15 times a second,
    /// and when the finger is lifted.
    private func adjusted(_ which: MovablePoint, to p: GeoPoint, done: Bool) {
        let c = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
        switch which {
        case .measure(let i):
            if markerAnnotations.indices.contains(i) { markerAnnotations[i].coordinate = c }
            if done { dragLineShown = false }
            updateDragLine()
        case .selected:
            selectedAnnotation?.coordinate = c
        }
        let now = CACurrentMediaTime()
        guard done || now - lastAdjustUpdate >= 1.0 / 15 else { return }
        lastAdjustUpdate = now
        model.movePoint(which, to: p, done: done)
        if done {
            updateMeasureLine() // the map's own line again, through where the point was left
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    /// The measuring line over the map while a point is moved: through the markers as they are now (from you, for a
    /// single point; joined back and filled for a closed shape).
    private func updateDragLine() {
        guard dragLineShown else {
            if dragLine.path != nil { dragLine.path = nil }
            return
        }
        var line = markerAnnotations.map(\.coordinate)
        if line.count == 1, let me = model.recorder.location?.coordinate { line.insert(me, at: 0) }
        var closed = false
        if case .closed = model.measure { closed = line.count >= 3 }
        let path = CGMutablePath()
        if line.count >= 2 {
            path.addLines(between: line.map { mapView.convert($0, toPointTo: aimLineView) })
            if closed { path.closeSubpath() }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        dragLine.path = path
        dragLine.fillColor = closed ? UIColor(red: 1, green: 0.18, blue: 0.4, alpha: 0.15).cgColor : nil
        CATransaction.commit()
    }

    /// What the square shows besides the imagery: the blocks near the point, the measuring line and points, and you.
    private func loupeScene(around p: GeoPoint, moving: MovablePoint) -> LoupeView.Scene {
        var scene = LoupeView.Scene()
        let m = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon))
        let reach = 2_000 * MKMapPointsPerMeterAtLatitude(p.lat) // far enough for the map to scroll a while
        let near = MKMapRect(x: m.x - reach, y: m.y - reach, width: 2 * reach, height: 2 * reach)
        func mapPoints(_ pts: [GeoPoint]) -> [MKMapPoint] {
            pts.map { MKMapPoint(CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)) }
        }

        let colours = model.kml.useFileColours
        for f in model.kml.files where f.isShown {
            for feature in f.document.features {
                let style = colours ? feature.style : nil
                let line = style?.lineColor.map(UIColor.init(kml:)) ?? UIColor(white: 1, alpha: 0.95)
                switch feature.geometry {
                // By their boxes: a long edge passing the point counts, though its corners are far away.
                case .polygon(let outer, let holes):
                    for ring in [outer] + holes {
                        let pts = mapPoints(ring)
                        let b = LoupeView.box(pts)
                        if LoupeView.overlap(b, near) { scene.lines.append((pts, line, true, b)) }
                    }
                case .line(let pts):
                    let mp = mapPoints(pts)
                    let b = LoupeView.box(mp)
                    if LoupeView.overlap(b, near) { scene.lines.append((mp, line, false, b)) }
                case .point(let q):
                    let mp = mapPoints([q])[0]
                    if near.contains(mp) {
                        scene.dots.append((mp, style?.iconColor.map(UIColor.init(kml:)) ?? .white))
                    }
                }
            }
        }
        scene.measure = mapPoints(model.measure.points)
        if case .closed = model.measure { scene.measureClosed = true }
        if case .measure(let i) = moving { scene.moving = i }
        if let loc = model.recorder.location { scene.user = MKMapPoint(loc.coordinate) }
        return scene
    }

    // MARK: - Measuring

    private func measureChanged(_ m: MeasureMode) {
        let points = m.points
        let labels = points.indices.map { points.count >= 2 ? MeasureMode.label($0) : nil }
        if markerAnnotations.map(\.label) == labels {
            // The same points, one of them moved: moved on the map rather than put there again.
            for (a, p) in zip(markerAnnotations, points) {
                let c = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                if a.coordinate.latitude != c.latitude || a.coordinate.longitude != c.longitude { a.coordinate = c }
            }
        } else {
            mapView.removeAnnotations(markerAnnotations)
            markerAnnotations = points.enumerated().map { i, p in
                MeasureMarkerAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), label: labels[i])
            }
            mapView.addAnnotations(markerAnnotations)
        }
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
        if dragLineShown { line = [] } // drawn over the map meanwhile (`updateDragLine`)
        let hadLine = !measureLine.path.vertices.isEmpty
        let dirty = measureLine.set(line, closed: closed)
        if hadLine && measureLine.path.vertices.isEmpty {
            // Gone: MapKit can go on showing the tiles drawn before, however they are invalidated (seconds after
            // measuring was switched off). Taken off the map and put back where it was, above the track (blocks go
            // below it, a drawn area above), the line loses them and gets a new renderer.
            mapView.removeOverlay(measureLine)
            measureRenderer = nil
            mapView.insertOverlay(measureLine, above: track)
            return
        }
        let margin = 8 * mapPointsPerPoint
        for rect in dirty {
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
        case let a as AviationAnnotation:
            return aviation.view(for: a)
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
