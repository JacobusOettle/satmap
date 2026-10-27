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
    /// While aiming the measuring tool: the dashed line from you to the crosshair. Drawn over the map rather than as
    /// a map overlay, so it follows a pan frame by frame.
    private let aimLineView = UIView()
    private let aimLine = CAShapeLayer()
    private let model: AppModel

    private var satelliteMode = SatelliteMode.current
    /// The imagery (`SatelliteMode`). Tiles: MapKit's tile layer over a backdrop (Single: without).
    private var satellite: SatelliteTileOverlay?
    private var backdrop: SatelliteBackdropOverlay?
    /// Drawn: the imagery SatMap draws, and while a new layer of it replaces that one (`swapDrawn`), the one going (on
    /// top until the new one has painted).
    private var drawn: SatelliteDrawnOverlay?
    private var drawnOutgoing: SatelliteDrawnOverlay?
    private var swapScheduled = false
    private var lastSwap: CFTimeInterval = 0
    /// Swaps put off while the shown layer's own tiles are decoded for the new one.
    private var swapDeferrals = 0
    /// Between MapKit's region-will-change and did-change: swaps wait, leaving MapKit's drawing to what comes into view.
    private var mapMoving = false
    private var movingSince: CFTimeInterval = 0
    private var swapWaiting = false
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

        // Later overlays draw on top: imagery, contours (when switched on), (blocks inserted below the track),
        // track, measuring line.
        addImagery()
        NotificationCenter.default.addObserver(forName: SatelliteMode.changed, object: nil, queue: .main) { [weak self] _ in
            self?.satelliteModeChanged()
        }
        SatelliteRenderer.onArrival = { [weak self] t, content in self?.tileArrived(t, content) }
        contoursChanged(model.showContours)
        track.reset(model.track)
        drawnTrackCount = model.track.count
        mapView.addOverlay(track, level: .aboveLabels)
        mapView.addOverlay(measureLine, level: .aboveLabels)

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
            // Only when the imagery layer showed something it should replace: a reload empties it for a moment.
            if self.satellite?.takeNeedsReload() == true { self.reloadSatelliteTiles() }
            SatelliteRenderer.reload()
            // Drawn: a new layer asks again for the tiles shown with a stand-in.
            if let d = self.drawn, (self.mapView.renderer(for: d) as? SatelliteRenderer)?.hasStandIns == true {
                self.swapDrawn()
            }
            (self.mapView.renderer(for: self.contours) as? MKTileOverlayRenderer)?.reloadData()
            self.model.aim.retry()
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
        if !mapMoving { movingSince = CACurrentMediaTime() }
        mapMoving = true
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        saveCamera()
        updateAim()
        updateScale()
        mapMoving = false
        if swapWaiting {
            swapWaiting = false
            scheduleSwap()
        } else if let d = drawn, drawnOutgoing == nil,
                  (mapView.renderer(for: d) as? SatelliteRenderer)?.hasPainted(screenPoints) == false {
            // MapKit can take a second or more to paint what a pan brought into view (it paints a new layer at once).
            tileLog.debug("drawn layer hasn't painted the screen: new layer")
            swapDrawn()
        }
        mapStopped()
    }

    private var stopCount = 0

    /// The map has stopped: once it has been still a moment (so the tiles on screen go first), gets the imagery ready
    /// for the next zoom or pan (`ImageryPrefetch`).
    private func mapStopped() {
        stopCount += 1
        let n = stopCount
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self = self, n == self.stopCount, self.isForeground, let levels = self.imageryLevels else { return }
            let visible = self.mapView.visibleMapRect
            if self.satelliteMode == .renderer {
                SatelliteRenderer.setFocus(visible.insetBy(dx: -visible.width / 4, dy: -visible.height / 4),
                                           level: levels.imagery, contourLevel: levels.contours)
            }
            ImageryPrefetch.mapStopped(visible: visible, level: levels.imagery, decode: self.satelliteMode == .renderer)
            if let d = self.drawn { (self.mapView.renderer(for: d) as? SatelliteRenderer)?.loadStandIns() }
        }
    }

    /// The imagery level for the zoom on screen: tiles about 256 pixels across on screen (a tile pixel 0.7 to 1.4
    /// screen pixels, at the drawn layer's pixels per point, from its latest paint). Not at the zoom MapKit paints at,
    /// the next whole zoom level at or above the one on screen (the zoom test showed paints at a zoom scale of 2⁻⁶ with
    /// 2⁻⁶·⁰⁹ on screen): there, tiles would show at half to full size, up to four times as many for a screen.
    /// And the contour level: tiles 256 points across at the zoom MapKit paints at, as it asked for them as a tile layer.
    private var imageryLevels: (imagery: Int, contours: Int)? {
        guard let perPoint = crosshairMapPointsPerPoint else { return nil }
        let onScreen = 1 / perPoint // points per map point
        let painted = pow(2, ceil(log2(onScreen) - 1e-6))
        var pixelsPerPoint = Double(max(1, mapView.window?.screen.scale ?? mapView.traitCollection.displayScale))
        let paint = SatelliteRenderer.lastPaintScale
        if satelliteMode == .renderer, paint.pixelsPerPoint > 0 { pixelsPerPoint = Double(paint.pixelsPerPoint) }
        return (SatelliteRenderer.level(pixelsPerMapPoint: onScreen * pixelsPerPoint),
                SatelliteRenderer.level(pixelsPerMapPoint: painted))
    }

    /// Map points per screen point at the crosshair, measured like `metersPerPoint` (right however the map is turned).
    private var crosshairMapPointsPerPoint: Double? {
        let b = mapView.bounds
        guard b.width > 100 else { return nil }
        let l = MKMapPoint(mapView.convert(CGPoint(x: b.midX - 50, y: b.midY), toCoordinateFrom: mapView))
        let r = MKMapPoint(mapView.convert(CGPoint(x: b.midX + 50, y: b.midY), toCoordinateFrom: mapView))
        let world = MKMapSize.world.width
        let dx = abs(r.x - l.x), dy = r.y - l.y
        let d = hypot(min(dx, world - dx), dy) // the short way round, across the date line
        return d > 0 ? d / 100 : nil
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        updateAim()
        updateScale()
        if satelliteMode == .renderer, let levels = imageryLevels {
            let v = mapView.visibleMapRect
            SatelliteRenderer.setFocus(v.insetBy(dx: -v.width / 4, dy: -v.height / 4),
                                       level: levels.imagery, contourLevel: levels.contours)
        }
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

    // MARK: - Satellite imagery

    /// Puts the imagery layers for the mode at the bottom of the map (under contours, blocks, track …). Only the bottom
    /// one replaces Apple's map: where a layer that does has no tile yet, MapKit paints its grey loading grid, which
    /// would hide what is under it.
    private func addImagery() {
        var layers: [MKOverlay] = []
        switch satelliteMode {
        case .renderer:
            let imagery = SatelliteDrawnOverlay(urlTemplate: nil)
            drawn = imagery
            layers = [imagery]
        case .backdrop, .single:
            let tiles = SatelliteTileOverlay(urlTemplate: nil)
            if satelliteMode == .backdrop {
                let b = SatelliteBackdropOverlay(urlTemplate: nil)
                backdrop = b
                layers.append(b)
                tiles.canReplaceMapContent = false
            }
            satellite = tiles
            layers.append(tiles)
        }
        for (i, layer) in layers.enumerated() { mapView.insertOverlay(layer, at: i, level: .aboveLabels) }
    }

    /// The top imagery layer, for putting contours over it.
    private var topImagery: MKOverlay? {
        drawnOutgoing ?? drawn ?? satellite
    }

    /// The way of drawing the imagery was switched (Offline maps, while it is being tried out).
    private func satelliteModeChanged() {
        let mode = SatelliteMode.current
        guard mode != satelliteMode else { return }
        satelliteMode = mode
        let old: [MKOverlay?] = [satellite, backdrop, drawn, drawnOutgoing]
        mapView.removeOverlays(old.compactMap { $0 })
        satellite = nil
        backdrop = nil
        drawn = nil
        drawnOutgoing = nil
        addImagery()
        contoursChanged(model.showContours) // Drawn draws them itself
    }

    /// Loads the tile layer's tiles again. Over a backdrop, without emptying it first (which would show the backdrop's
    /// stand-ins for a moment, often the whole screen): a new tile layer goes on top, its tiles in memory come at once
    /// and the others are asked for again, and the old one goes once the new one has had time to fill in.
    private func reloadSatelliteTiles() {
        guard let old = satellite else { return }
        guard backdrop != nil else {
            (mapView.renderer(for: old) as? MKTileOverlayRenderer)?.reloadData()
            return
        }
        let new = SatelliteTileOverlay(urlTemplate: nil)
        new.canReplaceMapContent = false
        satellite = new
        mapView.insertOverlay(new, above: old)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.mapView.removeOverlay(old) }
    }

    /// Drawn: a tile has arrived in memory. If the drawn layer shows a stand-in where it would show better, a new
    /// layer replaces it.
    private func tileArrived(_ t: TileID, _ content: SatelliteRenderer.Content) {
        guard let d = drawn, let r = mapView.renderer(for: d) as? SatelliteRenderer, r.wouldImprove(with: t, content) else { return }
        scheduleSwap()
    }

    /// At most about once a second, so tiles arriving one after another come in together; not while the map moves.
    private func scheduleSwap() {
        if mapMoving, CACurrentMediaTime() - movingSince < 3 { // (in case MapKit misses a region-did-change)
            swapWaiting = true
            return
        }
        guard !swapScheduled else { return }
        swapScheduled = true
        let wait = max(0.25, 1 - (CACurrentMediaTime() - lastSwap))
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self = self else { return }
            self.swapScheduled = false
            self.swapDrawn()
        }
    }

    /// Shows what arrived with a freshly drawn layer (redrawing part of a layer would empty it first: grey): it goes
    /// under the drawn one, which covers it while it paints, and that one goes once it has painted the screen.
    private func swapDrawn() {
        guard let old = drawn else { return }
        guard isForeground else {
            swapWaiting = true // on return
            return
        }
        guard drawnOutgoing == nil else { return scheduleSwap() } // one at a time
        // The new layer paints only decoded tiles: not before the ones shown are (a few tries at most).
        if let r = mapView.renderer(for: old) as? SatelliteRenderer, swapDeferrals < 3,
           !r.ownSpotsReady(contours: model.showContours) {
            swapDeferrals += 1
            return scheduleSwap()
        }
        swapDeferrals = 0
        lastSwap = CACurrentMediaTime()
        let new = SatelliteDrawnOverlay(urlTemplate: nil)
        drawn = new
        drawnOutgoing = old
        mapView.insertOverlay(new, below: old)
        retire(old, once: new, ticks: 0)
    }

    /// Removes the old drawn layer once the new one has painted the whole screen. If it hasn't in 2 s (on screen:
    /// MapKit doesn't paint in the background), the new one goes instead and the old one stays.
    private func retire(_ old: SatelliteDrawnOverlay, once new: SatelliteDrawnOverlay, ticks: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self = self, self.drawnOutgoing === old else { return }
            let painted = self.isForeground
                && (self.mapView.renderer(for: new) as? SatelliteRenderer)?.hasPainted(self.screenPoints) == true
            if painted {
                // A moment more for MapKit to show the last patch painted; less when the old layer shows grey on part of
                // the screen (what a pan or zoom brought into view), as it hides the new one there until it goes.
                let points = self.screenPoints
                let shown = (self.mapView.renderer(for: old) as? SatelliteRenderer)?.paintedShare(points) ?? 1
                DispatchQueue.main.asyncAfter(deadline: .now() + (shown < 0.95 ? 0.1 : 0.4)) { [weak self] in
                    guard let self = self, self.drawnOutgoing === old else { return }
                    self.mapView.removeOverlay(old)
                    self.drawnOutgoing = nil
                }
            } else if self.isForeground, ticks >= 20 {
                self.mapView.removeOverlay(new)
                self.drawn = old
                self.drawnOutgoing = nil
                self.scheduleSwap()
            } else {
                self.retire(old, once: new, ticks: self.isForeground ? ticks + 1 : ticks)
            }
        }
    }

    /// Points across the screen to its edges, closer together than MapKit's patches, as map points (right however
    /// the map is turned).
    private var screenPoints: [MKMapPoint] {
        let b = mapView.bounds.insetBy(dx: 1, dy: 1)
        return (0..<81).map { i in
            let p = CGPoint(x: b.minX + b.width * CGFloat(i % 9) / 8, y: b.minY + b.height * CGFloat(i / 9) / 8)
            return MKMapPoint(mapView.convert(p, toCoordinateFrom: mapView))
        }
    }

    // MARK: - Contours

    /// Removing the overlay (rather than hiding it) stops MapKit asking for contour tiles, so no elevation is
    /// downloaded or processed for them. Rendered tiles stay cached, so switching back on is quick.
    /// Drawn: the drawn layer draws them, so a new one comes, with or without.
    private func contoursChanged(_ show: Bool) {
        if let d = drawn, let r = mapView.renderer(for: d) as? SatelliteRenderer, r.drawsContours != show { swapDrawn() }
        let shown = mapView.overlays.contains { $0 === contours }
        let overlay = show && satelliteMode != .renderer
        if overlay && !shown {
            // Under the blocks, track and measuring line.
            if let top = topImagery {
                mapView.insertOverlay(contours, above: top)
            } else {
                mapView.insertOverlay(contours, at: 0, level: .aboveLabels)
            }
        } else if !overlay && shown {
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
        // A new layer owed for tiles that arrived meanwhile.
        if swapWaiting {
            swapWaiting = false
            scheduleSwap()
        }
        trackChanged()
        if let loc = model.recorder.location { locationUpdated(loc) }
    }

    // MARK: - MKMapViewDelegate rendering

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        switch overlay {
        case let o as SatelliteDrawnOverlay:
            return SatelliteRenderer(overlay: o, contours: model.showContours)
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
