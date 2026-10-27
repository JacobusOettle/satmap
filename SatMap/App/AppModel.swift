import Combine
import CoreLocation
import Foundation
import UIKit

/// The measuring tool. A point is placed at the crosshair (ruler button) or where the map is tapped.
/// off → aiming → placed (distance from you to the point) → pair (distance between two points) → off.
enum MeasureMode: Equatable {
    case off
    /// Crosshair shown, waiting for the first point.
    case aiming
    /// One point: the live distance and height from you to it.
    case placed(GeoPoint)
    /// Two points: the distance and height between them.
    case pair(GeoPoint, GeoPoint)

    var points: [GeoPoint] {
        switch self {
        case .off, .aiming: return []
        case .placed(let a): return [a]
        case .pair(let a, let b): return [a, b]
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
    @Published var showContours = UserDefaults.standard.object(forKey: "showContours") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showContours, forKey: "showContours") }
    }
    @Published private(set) var measure: MeasureMode = .off
    /// Elevation of the first measuring point, of the second, and of where you are.
    @Published private(set) var markerElevation: Double?
    @Published private(set) var secondElevation: Double?
    @Published private(set) var userElevation: Double?
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
            if self.markerElevation == nil || self.secondElevation == nil { self.refreshPointElevations() }
            if case .placed = self.measure, let loc = self.recorder.location { self.refreshUserElevation(loc) }
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
        if case .placed = measure, UIApplication.shared.applicationState == .active { refreshUserElevation(loc) }
    }

    func centerOnUser() {
        following = true
        if let loc = recorder.location { map?.center(on: loc.coordinate, animated: true) }
    }

    // MARK: - Measuring

    /// The measure button: shows the crosshair, then places a point at it (distance from you), then a second point
    /// (distance between the two), then clears.
    func measureButtonTapped() {
        switch measure {
        case .off:
            measure = .aiming
        case .aiming, .placed:
            guard let c = map?.centerCoordinate else { return }
            addMeasurePoint(GeoPoint(lat: c.latitude, lon: c.longitude))
        case .pair:
            setMeasure(.off)
            selectedPoint = nil
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
            setMeasure(.pair(a, p))
        default:
            // The first point, or a new start after two points.
            setMeasure(.placed(p))
        }
    }

    private func setMeasure(_ m: MeasureMode) {
        let old = measure.points
        measure = m
        // Faster GPS checks while asleep only matter when the distance is from you.
        if case .placed = m { recorder.measuring = true } else { recorder.measuring = false }
        let new = m.points
        if new.first != old.first { markerElevation = nil }
        if new.count < 2 || new.last != old.last || old.count < 2 { secondElevation = nil }
        if new.isEmpty { userElevation = nil }
        refreshPointElevations()
        if case .placed = m, let loc = recorder.location { refreshUserElevation(loc) }
    }

    /// Distance from you to the point, or between the two points.
    var markerDistance: Double? {
        switch measure {
        case .placed(let p):
            guard let loc = recorder.location else { return nil }
            return loc.distance(from: CLLocation(latitude: p.lat, longitude: p.lon))
        case .pair(let a, let b):
            return Geo.distance(a, b)
        case .off, .aiming:
            return nil
        }
    }

    private func refreshPointElevations() {
        let points = measure.points
        for (i, p) in points.enumerated() where (i == 0 ? markerElevation : secondElevation) == nil {
            Task.detached(priority: .userInitiated) {
                let e = await TileRepository.shared.elevation(at: p)
                DispatchQueue.main.async {
                    let now = self.measure.points
                    guard i < now.count, now[i] == p else { return }
                    if i == 0 { self.markerElevation = e } else { self.secondElevation = e }
                }
            }
        }
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

    func discardPendingArea() {
        pendingArea = nil
        draftArea = nil
    }
}
