import Combine
import MapKit
import UIKit

/// The map on the car's screen (CarPlay): the satellite imagery with contour lines, the blocks, the track and your
/// position, following you until it is moved. It lies under CarPlay's map template, which has the buttons
/// (`CarPlaySceneDelegate`): the car's screen doesn't take touches on the map itself.
final class CarPlayMapController: UIViewController, MKMapViewDelegate {
    private let model: AppModel
    private let mapView = MKMapView()
    private var imagery: DrawnImagery!
    private let track = TrackOverlay()
    private var trackRenderer: TrackRenderer?
    private var drawnTrackCount = 0
    private var userAnnotation: UserLocationAnnotation?
    private var blockOverlays: [UUID: [MKOverlay]] = [:]
    private var blockLabels: [UUID: [MKAnnotation]] = [:]
    private var cancellables = Set<AnyCancellable>()
    /// Whether the map keeps you in the middle (until it is moved).
    private(set) var following = true
    /// Whether the car's screen shows the map (MapKit doesn't paint it otherwise).
    var isShown = false {
        didSet { if isShown && !oldValue { imagery?.becameVisible() } }
    }
    /// Where the map was when a drag on a touch screen began.
    private var dragStart: CLLocationCoordinate2D?

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

        // Imagery with contours, blocks (inserted below the track), track.
        imagery = DrawnImagery(mapView: mapView, isVisible: { [weak self] in self?.isShown ?? false },
                               showContours: { [weak model] in model?.showContours ?? true })
        track.reset(model.track)
        drawnTrackCount = model.track.count
        mapView.addOverlay(track, level: .aboveLabels)

        // Around you, or where the phone's map is, about 1.5 km across.
        let start = model.recorder.location?.coordinate ?? model.map?.centerCoordinate
            ?? CLLocationCoordinate2D(latitude: -24.99, longitude: 31.59)
        mapView.setRegion(MKCoordinateRegion(center: start, latitudinalMeters: 1500, longitudinalMeters: 1500), animated: false)
        if let loc = model.recorder.location { locationUpdated(loc) }
        bind()
    }

    private func bind() {
        model.$trackRevision
            .sink { [weak self] _ in DispatchQueue.main.async { self?.trackChanged() } }
            .store(in: &cancellables)
        model.$showContours
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] show in DispatchQueue.main.async { self?.imagery.contoursChanged(show) } }
            .store(in: &cancellables)
        model.kml.$files
            .sink { [weak self] files in DispatchQueue.main.async { self?.blocksChanged(files) } }
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

    // MARK: - Track and blocks

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
            let (overlays, labels) = MapController.blockShapes(f)
            for o in overlays { mapView.insertOverlay(o, below: track) }
            mapView.addAnnotations(labels)
            blockOverlays[f.id] = overlays
            blockLabels[f.id] = labels
        }
    }

    // MARK: - MKMapViewDelegate

    func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
        imagery.regionWillChange()
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        imagery.regionDidChange()
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        imagery.visibleRegionChanged()
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
            return MapController.blockRenderer(o)
        case let o as BlockLines:
            return MapController.blockRenderer(o)
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
        default:
            return nil
        }
    }
}
