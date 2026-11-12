import Combine
import MapKit
import UIKit

/// The map on the car's screen (CarPlay): the satellite imagery with contour lines and elevation colours (as on the
/// phone), the blocks, the track and your position, following you until it is moved. It lies under CarPlay's map template, which has the buttons
/// (`CarPlaySceneDelegate`): the car's screen doesn't take touches on the map itself.
final class CarPlayMapController: UIViewController, MKMapViewDelegate {
    private let model: AppModel
    private let mapView = MKMapView()
    private var imagery: DrawnImagery!
    private var blocks: BlockLayers!
    private var aviation: AviationLayer!
    private let track = TrackOverlay()
    private var trackRenderer: TrackRenderer?
    private var drawnTrackCount = 0
    private var userAnnotation: UserLocationAnnotation?
    private var cancellables = Set<AnyCancellable>()
    /// Whether the map keeps you in the middle (until it is moved).
    private(set) var following = true
    /// Whether the car's screen shows the map (MapKit doesn't paint it otherwise).
    var isShown = false {
        didSet { if isShown && !oldValue { imagery?.becameVisible() } }
    }
    /// Where the map was when a drag on a touch screen began.
    private var dragStart: CLLocationCoordinate2D?
    /// Over the car's map while test builds are switched off (`TestBuildGate`): a short line, nothing else.
    private let lockView = CarPlayLockView()

    init(model: AppModel) {
        self.model = model
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func loadView() {
        view = mapView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let config = MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .muted)
        config.pointOfInterestFilter = .excludingAll
        config.showsTraffic = false
        mapView.preferredConfiguration = config
        mapView.selectableMapFeatures = []
        mapView.delegate = self
        mapView.showsCompass = false
        mapView.showsScale = false
        mapView.isPitchEnabled = false
        mapView.isRotateEnabled = false
        mapView.showsUserLocation = false // the app's own dot, as on the phone
        mapView.register(UserDotView.self, forAnnotationViewWithReuseIdentifier: UserDotView.reuseID)
        mapView.register(BlockLabelView.self, forAnnotationViewWithReuseIdentifier: BlockLabelView.reuseID)
        mapView.register(AviationView.self, forAnnotationViewWithReuseIdentifier: AviationView.reuseID)

        // Imagery with contours and colours, blocks (inserted below the track), track.
        imagery = DrawnImagery(mapView: mapView, isVisible: { [weak self] in self?.isShown ?? false },
                               terrain: { [weak model] in model?.terrainMode ?? .contours })
        imagery.addToMap()
        track.reset(model.track)
        drawnTrackCount = model.track.count
        mapView.addOverlay(track, level: .aboveLabels)
        blocks = BlockLayers(mapView: mapView, below: track)
        aviation = AviationLayer(mapView: mapView)
        aviation.isOn = model.terrainMode.showsAviation

        // Around you, or where the phone's map is, about 1.5 km across.
        let start = model.recorder.location?.coordinate ?? model.map?.centerCoordinate
            ?? CLLocationCoordinate2D(latitude: -24.99, longitude: 31.59)
        mapView.setRegion(MKCoordinateRegion(center: start, latitudinalMeters: 1500, longitudinalMeters: 1500), animated: false)
        if let loc = model.recorder.location { locationUpdated(loc) }
        lockView.translatesAutoresizingMaskIntoConstraints = false
        lockView.isHidden = true
        view.addSubview(lockView)
        NSLayoutConstraint.activate([
            lockView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            lockView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            lockView.topAnchor.constraint(equalTo: view.topAnchor),
            lockView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        setLocked(model.testBuild.lock)
        bind()
    }

    /// The car's empty state while test builds are switched off: a short line over the map (`TestBuildGate`).
    func setLocked(_ lock: TestBuildSwitch.Lock?) {
        lockView.show(lock)
        view.bringSubviewToFront(lockView)
    }

    private func bind() {
        model.$trackRevision
            .sink { [weak self] _ in DispatchQueue.main.async { self?.trackChanged() } }
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
        model.$selectedPoint
            .sink { [weak self] p in DispatchQueue.main.async { self?.aviationDistances(pin: p) } }
            .store(in: &cancellables)
        model.kml.$files.combineLatest(model.kml.$useFileColours)
            .sink { [weak self] files, colours in DispatchQueue.main.async { self?.blocks.update(files, useFileColours: colours) } }
            .store(in: &cancellables)
    }

    // MARK: - Moving the map (the template's buttons)

    func locationUpdated(_ loc: CLLocation) {
        if let a = userAnnotation {
            a.coordinate = loc.coordinate
        } else {
            let a = UserLocationAnnotation(coordinate: loc.coordinate)
            userAnnotation = a
            mapView.addAnnotation(a)
        }
        if following && isShown { mapView.setCenter(loc.coordinate, animated: true) }
        aviationDistances(pin: model.selectedPoint)
    }

    /// Drone mode's labels: how far each place is from you and from the spot tapped on the phone.
    private func aviationDistances(pin: GeoPoint?) {
        let me = model.recorder.location.map { GeoPoint(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
        aviation?.distancesChanged(me: me, pin: pin)
    }

    /// Zooms in (factor below 1) or out around the middle of the map.
    func zoom(by factor: Double) {
        guard let camera = mapView.camera.copy() as? MKMapCamera else { return }
        camera.centerCoordinateDistance = min(4_000_000, max(150, camera.centerCoordinateDistance * factor))
        mapView.setCamera(camera, animated: true)
    }

    /// Back to following you.
    func recentre() {
        following = true
        if let loc = model.recorder.location { mapView.setCenter(loc.coordinate, animated: true) }
    }

    /// Moves the map a third of the screen in the arrow's direction (the panning buttons).
    func pan(dx: Double, dy: Double) {
        following = false
        let r = mapView.visibleMapRect
        let c = MKMapPoint(mapView.centerCoordinate)
        let moved = MKMapPoint(x: c.x + dx * r.width / 3, y: c.y + dy * r.height / 3)
        mapView.setCenter(moved.coordinate, animated: true)
    }

    /// A drag on a touch screen: the map follows the finger.
    func dragBegan() {
        following = false
        dragStart = mapView.centerCoordinate
    }

    func dragged(by translation: CGPoint) {
        guard let start = dragStart else { return }
        let perPoint = mapView.visibleMapRect.width / Double(max(1, mapView.bounds.width))
        let c = MKMapPoint(start)
        let moved = MKMapPoint(x: c.x - Double(translation.x) * perPoint, y: c.y - Double(translation.y) * perPoint)
        mapView.setCenter(moved.coordinate, animated: false)
    }

    func dragEnded() {
        dragStart = nil
    }

    /// Tiles may be had again (the network is back, an offline area finished).
    func reload() {
        imagery.reload()
    }

    // MARK: - Track

    private func trackChanged() {
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
        let margin = 12 * mapView.visibleMapRect.width / Double(max(1, mapView.bounds.width))
        trackRenderer?.setNeedsDisplay(dirty.insetBy(dx: -margin, dy: -margin))
    }

    // MARK: - MKMapViewDelegate

    func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
        imagery?.regionWillChange()
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        imagery?.regionDidChange()
        aviation?.regionChanged()
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        imagery?.visibleRegionChanged()
    }

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        switch overlay {
        case let o as SatelliteDrawnOverlay:
            return imagery.renderer(for: o)
        case let o as TrackOverlay:
            let r = TrackRenderer(overlay: o)
            trackRenderer = r
            return r
        case let o as BlockPolygons:
            return BlockLayers.renderer(o)
        case let o as BlockLines:
            return BlockLayers.renderer(o)
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
        case is BlockLabelAnnotation:
            return mapView.dequeueReusableAnnotationView(withIdentifier: BlockLabelView.reuseID, for: annotation)
        case let a as AviationAnnotation:
            return aviation.view(for: a)
        default:
            return nil
        }
    }
}

/// The car's simplest empty state while test builds are switched off: a dark panel with a short line.
final class CarPlayLockView: UIView {
    private let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        label.font = .preferredFont(forTextStyle: .title3)
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// Shows the lock's title (or the default) with a short follow-up; hidden when there is no lock.
    func show(_ lock: TestBuildSwitch.Lock?) {
        guard let lock = lock else {
            isHidden = true
            return
        }
        label.text = lock.title + "\n\nGet Spoor from the App Store on your phone to keep using it."
        isHidden = false
    }
}
