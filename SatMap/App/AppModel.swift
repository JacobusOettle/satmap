import Combine
import CoreLocation
import Foundation
import UIKit

/// The measuring tool. A point is placed at the crosshair (ruler button) or where the map is tapped.
/// off → aiming → placed (distance from you to A) → path (A → B → C …, total distance) → closed (back to A: area).
enum MeasureMode: Equatable {
    case off
    /// Crosshair shown, waiting for the first point.
    case aiming
    /// One point: the live distance and height from you to it.
    case placed(GeoPoint)
    /// Two or more points: the line through them, its total length and the height from first to last.
    case path([GeoPoint])
    /// Three or more points joined back to the first: the shape's area and perimeter.
    case closed([GeoPoint])

    var points: [GeoPoint] {
        switch self {
        case .off, .aiming: return []
        case .placed(let a): return [a]
        case .path(let pts), .closed(let pts): return pts
        }
    }

    /// "A", "B", … "Z", "AA", "AB", … "ZZ", "AAA", …
    static func label(_ i: Int) -> String {
        let letters = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        var n = i + 1, s = ""
        while n > 0 {
            n -= 1
            s = String(letters[n % 26]) + s
            n /= 26
        }
        return s
    }
}

/// While aiming the measuring tool: the spot under the crosshair and its height, which change as the map moves. Kept
/// out of `AppModel` so a pan redraws only the measuring panel, not the whole screen.
final class CrosshairAim: ObservableObject, @unchecked Sendable {
    @Published private(set) var point: GeoPoint?
    /// The height last looked up and the spot it is for: while the map moves, an earlier spot's.
    @Published private(set) var elevation: Double?
    @Published private(set) var elevationPoint: GeoPoint?
    private var lookingUp = false
    private var lookedUp: GeoPoint?
    /// Bumped when aiming ends, so a lookup still under way then isn't shown when aiming again.
    private var session = 0
    private var retries = 0

    /// The spot under the crosshair (nil when not aiming).
    func set(_ p: GeoPoint?) {
        guard p != point else { return }
        point = p
        retries = 0
        if p == nil {
            session += 1
            elevation = nil
            elevationPoint = nil
            lookedUp = nil
        }
        lookUp()
    }

    /// The network is back: looks up a height that couldn't be found.
    func retry() {
        guard point != nil, elevation == nil else { return }
        lookedUp = nil
        lookUp()
    }

    /// One lookup at a time, always for the latest spot, so a pan doesn't queue one for every frame. A failed lookup
    /// (no signal) is tried again a few times while the crosshair stays on the spot, 31 s apart: after the elevation
    /// tile's own 30 s wait, which would otherwise answer the same.
    private func lookUp() {
        guard !lookingUp, let p = point, p != lookedUp else { return }
        lookingUp = true
        lookedUp = p
        let s = session
        Task.detached(priority: .userInitiated) {
            let e = await TileRepository.shared.elevation(at: p)
            DispatchQueue.main.async {
                self.lookingUp = false
                if s == self.session {
                    self.elevation = e
                    self.elevationPoint = p
                    if e == nil, self.point == p, self.retries < 4 {
                        self.retries += 1
                        DispatchQueue.main.asyncAfter(deadline: .now() + 31) {
                            guard s == self.session, self.point == p,
                                  UIApplication.shared.applicationState == .active else { return }
                            self.retry()
                        }
                    }
                }
                self.lookUp()
            }
        }
    }
}

/// A shape waiting for the user to confirm an offline download.
struct PendingArea: Identifiable {
    let id = UUID()
    var name: String
    var shape: CoverageShape
}

enum ActiveSheet: String, Identifiable {
    case blocks
    case offline
    case coordinates

    var id: String { rawValue }
}

/// App state shared by the map and the controls. Used on the main thread only
/// (background work hops back with DispatchQueue.main).
final class AppModel: ObservableObject, @unchecked Sendable {
    static let shared = AppModel()

    let recorder = LocationRecorder()
    let kml = KMLLibrary()
    let offline = OfflineManager()
    private let trackLog = TrackLog(url: AppPaths.track)

    /// The recorded track. Changes are announced through `trackRevision` (cheaper than publishing the array).
    private(set) var track: [TrackPoint] = []
    @Published private(set) var trackRevision = 0

    @Published var following = true
    /// Contour lines on the map (side button). Remembered between launches. While off, no elevation tiles are
    /// fetched for the map; the measuring tool still looks up heights.
    @Published var showContours = UserDefaults.standard.object(forKey: "showContours") == nil
        || UserDefaults.standard.bool(forKey: "showContours") { // bool(forKey:) also reads "NO" given at launch (tests)
        didSet { UserDefaults.standard.set(showContours, forKey: "showContours") }
    }
    @Published private(set) var measure: MeasureMode = .off
    /// Elevation of the first measuring point, of the last one (with two or more), and of where you are.
    @Published private(set) var markerElevation: Double?
    @Published private(set) var lastElevation: Double?
    @Published private(set) var userElevation: Double?
    /// The spot under the crosshair while aiming.
    let aim = CrosshairAim()
    /// The last point tapped on the map (or placed for measuring); its coordinates are shown at the top.
    @Published var selectedPoint: GeoPoint?

    /// Lasso drawing mode for choosing an offline area.
    @Published var isDrawingArea = false
    /// While drawing: true = finger draws the lasso, false = finger moves the map.
    @Published var lassoActive = true
    /// The lasso polygon, shown on the map until confirmed or discarded.
    @Published var draftArea: [GeoPoint]?
    @Published var pendingArea: PendingArea?
    @Published var sheet: ActiveSheet?

    /// Set by the map so the model can read the centre of the screen and move the camera.
    weak var map: MapController?

    private var cancellables = Set<AnyCancellable>()
    private var elevationTask: Task<Void, Never>?

    private init() {
        track = trackLog.loadAll()
        recorder.seed(lastRecorded: track.last)
        recorder.onTrackPoint = { [weak self] p in self?.appendTrack(p) }
        recorder.onFix = { [weak self] loc in self?.locationUpdated(loc) }

        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            // Elevations aren't looked up in the background; catch up when the app is opened.
            guard let self = self else { return }
            if self.markerElevation == nil || self.lastElevation == nil { self.refreshPointElevations() }
            if self.measureIsFromYou, let loc = self.recorder.location { self.refreshUserElevation(loc) }
        }

        // Re-publish nested objects so SwiftUI views observing the model update.
        for publisher in [kml.objectWillChange.eraseToAnyPublisher(), offline.objectWillChange.eraseToAnyPublisher(),
                          recorder.objectWillChange.eraseToAnyPublisher()] {
            publisher.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        }
        recorder.start()
    }

    // MARK: - Track

    private func appendTrack(_ p: TrackPoint) {
        track.append(p)
        trackLog.append(p)
        trackRevision += 1
    }

    func clearTrack() {
        track = []
        trackLog.clear()
        recorder.seed(lastRecorded: nil)
        trackRevision += 1
    }

    // MARK: - Location

    private func locationUpdated(_ loc: CLLocation) {
        map?.locationUpdated(loc)
        if measureIsFromYou, UIApplication.shared.applicationState == .active { refreshUserElevation(loc) }
    }

    /// My location: centres the map on you and follows you. Tapped again while it does (also a quick second tap), it
    /// zooms in until the screen is 300 m across.
    func centerOnUser() {
        if following, let loc = recorder.location {
            map?.zoom(onto: loc.coordinate, metersAcross: 300)
            return
        }
        following = true
        if let loc = recorder.location { map?.center(on: loc.coordinate, animated: true) }
    }

    // MARK: - Measuring

    /// The measure button: shows the crosshair, then places a point at it each time (A, B, C …); after a shape is
    /// closed it clears.
    func measureButtonTapped() {
        switch measure {
        case .off:
            setMeasure(.aiming)
        case .aiming, .placed, .path:
            guard let c = map?.centerCoordinate else { return }
            addMeasurePoint(GeoPoint(lat: c.latitude, lon: c.longitude))
        case .closed:
            clearMeasure()
        }
    }

    /// A tap on the map: shows the spot's coordinates, and while measuring places a point there.
    func mapTapped(_ p: GeoPoint) {
        if measure == .off {
            selectedPoint = p
        } else {
            addMeasurePoint(p)
        }
    }

    private func addMeasurePoint(_ p: GeoPoint) {
        selectedPoint = p
        switch measure {
        case .placed(let a):
            setMeasure(.path([a, p]))
        case .path(let pts):
            setMeasure(.path(pts + [p]))
        default:
            // The first point, or a new start after a closed shape.
            setMeasure(.placed(p))
        }
    }

    /// Joins the last point back to the first (tap on A, or "Close shape"): the area.
    func closeMeasureShape() {
        guard case .path(let pts) = measure, pts.count >= 3 else { return }
        setMeasure(.closed(pts))
    }

    /// Removes the last point (or reopens a closed shape).
    func undoMeasurePoint() {
        switch measure {
        case .closed(let pts):
            setMeasure(.path(pts))
        case .path(let pts) where pts.count > 2:
            setMeasure(.path(Array(pts.dropLast())))
        case .path(let pts):
            setMeasure(.placed(pts[0]))
        case .placed:
            setMeasure(.aiming)
        case .off, .aiming:
            return
        }
        selectedPoint = measure.points.last
    }

    private func setMeasure(_ m: MeasureMode) {
        let old = measure.points
        measure = m
        // Faster GPS checks while asleep only matter when the distance is from you.
        recorder.measuring = measureIsFromYou
        if m != .aiming { aim.set(nil) }
        let new = m.points
        if new.first != old.first { markerElevation = nil }
        if new.count < 2 || new.last != old.last || old.count < 2 { lastElevation = nil }
        if m == .off { userElevation = nil }
        refreshPointElevations()
        if measureIsFromYou, let loc = recorder.location { refreshUserElevation(loc) }
    }

    /// Aiming (to the crosshair) or one point: the distance and height are from you.
    private var measureIsFromYou: Bool {
        switch measure {
        case .aiming, .placed: return true
        case .off, .path, .closed: return false
        }
    }

    /// Distance from you to the point, or the total length of the line through the points (around the shape when
    /// it is closed).
    var markerDistance: Double? {
        switch measure {
        case .placed(let p):
            guard let loc = recorder.location else { return nil }
            return loc.distance(from: CLLocation(latitude: p.lat, longitude: p.lon))
        case .path(let pts):
            return Geo.pathLength(pts)
        case .closed(let pts):
            return Geo.pathLength(pts + [pts[0]])
        case .off, .aiming:
            return nil
        }
    }

    /// The closed shape's area in square metres.
    var measuredArea: Double? {
        guard case .closed(let pts) = measure else { return nil }
        return Geo.ringArea(pts)
    }

    /// Looks up the elevations of the first and last measuring points that aren't known yet (not for a closed
    /// shape). A failed lookup (no signal) is tried again a few times, 15 s apart, while the point stays and the app
    /// is on screen.
    private func refreshPointElevations(attempt: Int = 0) {
        if case .closed = measure { return }
        let points = measure.points
        guard let first = points.first else { return }
        var wanted: [(isFirst: Bool, point: GeoPoint)] = []
        if markerElevation == nil { wanted.append((true, first)) }
        if points.count >= 2, lastElevation == nil, let last = points.last { wanted.append((false, last)) }
        for (isFirst, p) in wanted {
            Task.detached(priority: .userInitiated) {
                let e = await TileRepository.shared.elevation(at: p)
                DispatchQueue.main.async {
                    let now = self.measure.points
                    guard (isFirst ? now.first : (now.count >= 2 ? now.last : nil)) == p else { return }
                    if isFirst { self.markerElevation = e } else { self.lastElevation = e }
                    if e == nil, attempt < 4 {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 15) {
                            guard UIApplication.shared.applicationState == .active else { return }
                            self.refreshPointElevations(attempt: attempt + 1)
                        }
                    }
                }
            }
        }
    }

    /// A position typed or pasted in: measuring starts from it (point A, with the live distance from you), and the map
    /// shows it, with you too if you are within 50 km.
    func measure(from p: GeoPoint) {
        sheet = nil
        following = false
        setMeasure(.placed(p))
        selectedPoint = p
        if let loc = recorder.location {
            let me = GeoPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
            if Geo.distance(me, p) < 50_000, let b = GeoBounds(points: [me, p]) {
                map?.zoom(to: b)
                return
            }
        }
        map?.center(on: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), animated: true)
    }

    /// The ✕ on the measuring panel.
    func clearMeasure() {
        setMeasure(.off)
        selectedPoint = nil
    }

    private func refreshUserElevation(_ loc: CLLocation) {
        let p = GeoPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
        elevationTask?.cancel()
        elevationTask = Task.detached(priority: .utility) {
            let e = await TileRepository.shared.elevation(at: p)
            guard !Task.isCancelled else { return }
            DispatchQueue.main.async {
                self.userElevation = e
                if self.markerElevation == nil { self.refreshPointElevations() }
            }
        }
    }

    // MARK: - Offline areas

    func startDrawingArea() {
        sheet = nil
        draftArea = nil
        following = false
        lassoActive = true
        isDrawingArea = true
    }

    func finishDrawing(_ ring: [GeoPoint]) {
        draftArea = ring.count >= 3 ? ring : nil
    }

    func cancelDrawing() {
        isDrawingArea = false
        draftArea = nil
    }

    func confirmDrawnArea() {
        guard let ring = draftArea else { return }
        isDrawingArea = false
        pendingArea = PendingArea(name: "Area \(offline.areas.count + 1)", shape: CoverageShape(polygons: [ring]))
    }

    /// Offline area around every block, line and point in a KML file (with a 150 m margin).
    func prepareArea(from file: KMLLibrary.File) {
        sheet = nil
        pendingArea = PendingArea(name: file.name, shape: file.document.coverageShape(bufferMeters: 150))
    }

    /// `roads` or `preset`: download satellite imagery only along these roads (or a road set shipped with the app).
    func startDownload(_ area: PendingArea, roads: [[GeoPoint]]?, preset: String? = nil, maxZoom: Int, allowCellular: Bool) {
        offline.add(name: area.name, shape: area.shape, roads: roads, preset: preset, maxZoom: maxZoom, allowCellular: allowCellular)
        pendingArea = nil
        draftArea = nil
    }

    /// "Where I've been": imagery along the recorded track, added to (or creating) the track area.
    func startTrackDownload(name: String, lines: [[GeoPoint]], maxZoom: Int, allowCellular: Bool) {
        offline.updateTrackArea(name: name, lines: lines, maxZoom: maxZoom, allowCellular: allowCellular)
    }

    func discardPendingArea() {
        pendingArea = nil
        draftArea = nil
    }
}
