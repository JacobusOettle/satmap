import Combine
import CoreLocation
import Foundation
import UIKit

/// The measuring tool: off → aiming (crosshair) → placed (marker) → off.
enum MeasureMode: Equatable {
    case off
    case aiming
    case placed(GeoPoint)
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
    @Published private(set) var markerElevation: Double?
    @Published private(set) var userElevation: Double?

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
            guard let self = self, case .placed = self.measure else { return }
            if self.markerElevation == nil { self.refreshMarkerElevation() }
            if let loc = self.recorder.location { self.refreshUserElevation(loc) }
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

    /// The measure button: first tap shows the crosshair, second drops the marker at the screen centre, third clears it.
    func measureButtonTapped() {
        switch measure {
        case .off:
            measure = .aiming
        case .aiming:
            guard let c = map?.centerCoordinate else { return }
            let p = GeoPoint(lat: c.latitude, lon: c.longitude)
            measure = .placed(p)
            recorder.measuring = true
            markerElevation = nil
            refreshMarkerElevation()
            if let loc = recorder.location { refreshUserElevation(loc) }
        case .placed:
            measure = .off
            recorder.measuring = false
            markerElevation = nil
            userElevation = nil
        }
    }

    var markerDistance: Double? {
        guard case .placed(let p) = measure, let loc = recorder.location else { return nil }
        return loc.distance(from: CLLocation(latitude: p.lat, longitude: p.lon))
    }

    private func refreshMarkerElevation() {
        guard case .placed(let p) = measure else { return }
        Task.detached(priority: .userInitiated) {
            let e = await TileRepository.shared.elevation(at: p)
            DispatchQueue.main.async {
                if case .placed(let current) = self.measure, current == p { self.markerElevation = e }
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
                if self.markerElevation == nil { self.refreshMarkerElevation() }
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

    /// `roads`: download satellite imagery only along these roads.
    func startDownload(_ area: PendingArea, roads: [[GeoPoint]]?, maxZoom: Int, allowCellular: Bool) {
        offline.add(name: area.name, shape: area.shape, roads: roads, maxZoom: maxZoom, allowCellular: allowCellular)
        pendingArea = nil
        draftArea = nil
    }

    func discardPendingArea() {
        pendingArea = nil
        draftArea = nil
    }
}
