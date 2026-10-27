import Combine
import MapKit
import SwiftUI
import UIKit

/// Owns the MKMapView: satellite + contour tiles, the recorded track, the measuring line,
/// imported blocks and the lasso used to choose offline areas.
final class MapController: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    let container = UIView()
    let mapView = MKMapView()
    private let lasso = LassoView()
    private let model: AppModel

    private let satelliteMode = SatelliteMode.current
    private let backdrop = SatelliteBackdropOverlay(urlTemplate: nil)
    private lazy var satellite: MKTileOverlay = satelliteMode == .renderer
        ? SatelliteDrawnOverlay(urlTemplate: nil) : SatelliteTileOverlay(urlTemplate: nil)
    private var satelliteRenderer: SatelliteRenderer?
    private let contours = ContourTileOverlay(urlTemplate: nil)
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

    private var blockOverlays: [UUID: [MKOverlay]] = [:]
    private var blockLabels: [UUID: [MKAnnotation]] = [:]

    private var isForeground = UIApplication.shared.applicationState != .background
    private var cancellables = Set<AnyCancellable>()
    /// No saved camera yet: zoom in on the first GPS fix.
    private let firstLaunch = UserDefaults.standard.array(forKey: "camera") == nil
    private var hasCentredOnFirstFix = false

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

        // Later overlays draw on top: imagery, contours (when switched on), (blocks inserted below the track),
        // track, measuring line.
        if satelliteMode == .backdrop { mapView.addOverlay(backdrop, level: .aboveLabels) }
        mapView.addOverlay(satellite, level: .aboveLabels)
        if model.showContours { mapView.addOverlay(contours, level: .aboveLabels) }
        track.reset(model.track)
        drawnTrackCount = model.track.count
        mapView.addOverlay(track, level: .aboveLabels)
        mapView.addOverlay(measureLine, level: .aboveLabels)

        container.addSubview(mapView)
        mapView.translatesAutoresizingMaskIntoConstraints = false
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
        nc.addObserver(self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
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
        model.$showContours
            .removeDuplicates()
            .sink { [weak self] show in DispatchQueue.main.async { self?.contoursChanged(show) } }
            .store(in: &cancellables)
        model.kml.$files
            .sink { [weak self] files in DispatchQueue.main.async { self?.blocksChanged(files) } }
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
        // MapKit doesn't ask again for tiles that failed. When the network comes back, after a temporary
        // failure, or when an offline area finishes, reload the tiles (debounced, on screen only).
        Connectivity.shared.$state
            .map(\.isOnline)
            .removeDuplicates()
            .dropFirst()
            .filter { $0 }
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    TileRepository.shared.resetFailureState()
                    self?.requestReload()
                }
            }
            .store(in: &cancellables)
        model.offline.$completedRevision
            .dropFirst()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.requestReload() } }
            .store(in: &cancellables)
        startRetryTimer()
    }

    private var retryTimer: Timer?

    /// Every 30 s on screen: reload tiles if some failed for temporary reasons. Not run in the background.
    private func startRetryTimer() {
        retryTimer?.invalidate()
        let t = Timer(timeInterval: 30, repeats: true) { [weak self] _ in
            Task.detached(priority: .utility) {
                guard await TileRepository.shared.probeRecovery() else { return }
                DispatchQueue.main.async { self?.requestReload() }
            }
        }
        t.tolerance = 10
        RunLoop.main.add(t, forMode: .common)
        retryTimer = t
    }
    private var reloadPending = false
    private var lastReload = Date.distantPast

    private func requestReload() {
        reloadPending = true
        guard isForeground else { return } // done on return to the foreground
        let wait = max(0, 20 - Date().timeIntervalSince(lastReload))
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self = self, self.reloadPending, self.isForeground else { return }
            self.reloadPending = false
            self.lastReload = Date()
            (self.mapView.renderer(for: self.satellite) as? MKTileOverlayRenderer)?.reloadData()
            self.satelliteRenderer?.reload()
            (self.mapView.renderer(for: self.contours) as? MKTileOverlayRenderer)?.reloadData()
        }
    }

    /// The coordinate under the crosshair (the middle of the view). `mapView.centerCoordinate` can be offset by
    /// MapKit's safe-area margins, which differ at the top and bottom on notched iPhones.
    var centerCoordinate: CLLocationCoordinate2D {
        mapView.convert(CGPoint(x: mapView.bounds.midX, y: mapView.bounds.midY), toCoordinateFrom: mapView)
    }

    /// Map points per screen point at the current zoom, for redraw margins.
    private var mapPointsPerPoint: Double {
        mapView.bounds.width > 0 ? mapView.visibleMapRect.width / Double(mapView.bounds.width) : 1
    }

    // MARK: - Camera

    func center(on c: CLLocationCoordinate2D, animated: Bool) {
        mapView.setCenter(c, animated: animated)
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
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        saveCamera()
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
        guard isForeground else { return }
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
            } else {
                mapView.setCenter(loc.coordinate, animated: true)
            }
            hasCentredOnFirstFix = true
        }
        updateMeasureLine()
    }

    private func powerChanged(_ power: LocationRecorder.Power) {
        guard let a = userAnnotation, let v = mapView.view(for: a) as? UserDotView else { return }
        v.sleeping = power == .sleeping
    }

    // MARK: - Contours

    /// Removing the overlay (rather than hiding it) stops MapKit asking for contour tiles, so no elevation is
    /// downloaded or processed for them. Rendered tiles stay cached, so switching back on is quick.
    private func contoursChanged(_ show: Bool) {
        let shown = mapView.overlays.contains { $0 === contours }
        if show && !shown {
            mapView.insertOverlay(contours, above: satellite) // under the blocks, track and measuring line
        } else if !show && shown {
            mapView.removeOverlay(contours)
        }
    }

    // MARK: - Track

    private func trackChanged() {
        guard isForeground else { return }
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

    // MARK: - Blocks (KML)

    private func blocksChanged(_ files: [KMLLibrary.File]) {
        let ids = Set(files.map(\.id))
        for (id, overlays) in blockOverlays where !ids.contains(id) {
            mapView.removeOverlays(overlays)
            blockOverlays[id] = nil
        }
        for (id, labels) in blockLabels where !ids.contains(id) {
            mapView.removeAnnotations(labels)
            blockLabels[id] = nil
        }
        for f in files where blockOverlays[f.id] == nil {
            var polygons: [MKPolygon] = []
            var lines: [MKPolyline] = []
            var labels: [MKAnnotation] = []
            for feature in f.document.features {
                switch feature.geometry {
                case .polygon(let outer, let holes):
                    let interiors = holes.map { h -> MKPolygon in
                        let c = h.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                        return MKPolygon(coordinates: c, count: c.count)
                    }
                    let c = outer.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    polygons.append(MKPolygon(coordinates: c, count: c.count, interiorPolygons: interiors))
                    if let name = feature.name, let p = PolygonLabel.interiorPoint(outer: outer, holes: holes) {
                        labels.append(BlockLabelAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon),
                                                           title: name, isPoint: false))
                    }
                case .line(let pts):
                    let c = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    lines.append(MKPolyline(coordinates: c, count: c.count))
                case .point(let p):
                    labels.append(BlockLabelAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon),
                                                       title: feature.name ?? "", isPoint: true))
                }
            }
            var overlays: [MKOverlay] = []
            if !polygons.isEmpty { overlays.append(BlockPolygons(polygons)) }
            if !lines.isEmpty { overlays.append(BlockLines(lines)) }
            // Keep blocks above the contours and under the track and the measuring line.
            for o in overlays { mapView.insertOverlay(o, below: track) }
            mapView.addAnnotations(labels)
            blockOverlays[f.id] = overlays
            blockLabels[f.id] = labels
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

    @objc private func didEnterBackground() {
        isForeground = false
        retryTimer?.invalidate()
        retryTimer = nil
        saveCamera()
        TileRepository.shared.trimForBackground()
        ImageryMemory.enteredBackground()
    }

    @objc private func willEnterForeground() {
        isForeground = true
        TileRepository.shared.restoreAfterBackground()
        startRetryTimer()
        if reloadPending { requestReload() }
        trackChanged()
        if let loc = model.recorder.location { locationUpdated(loc) }
    }

    // MARK: - MKMapViewDelegate rendering

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        switch overlay {
        case let o as SatelliteDrawnOverlay:
            let r = SatelliteRenderer(overlay: o)
            satelliteRenderer = r
            return r
        case let o as MKTileOverlay:
            return MKTileOverlayRenderer(tileOverlay: o)
        case let o as TrackOverlay:
            let r = TrackRenderer(overlay: o)
            trackRenderer = r
            return r
        case let o as MeasureLineOverlay:
            let r = MeasureLineRenderer(overlay: o)
            measureRenderer = r
            return r
        case let o as BlockPolygons:
            // White: yellow/orange is used by contours, cyan by the track and pink by the measuring line.
            let r = MKMultiPolygonRenderer(multiPolygon: o)
            r.strokeColor = UIColor(white: 1, alpha: 0.95)
            r.fillColor = UIColor(white: 1, alpha: 0.07)
            r.lineWidth = 2.5
            return r
        case let o as BlockLines:
            let r = MKMultiPolylineRenderer(multiPolyline: o)
            r.strokeColor = UIColor(white: 1, alpha: 0.95)
            r.lineWidth = 2.5
            return r
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
