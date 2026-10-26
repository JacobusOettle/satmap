import Combine
import Foundation
import UIKit

/// Offline areas: each is a shape (lasso or KML) downloaded as satellite imagery up to `maxZoom`
/// plus elevation tiles for contours and height differences. Downloads run one at a time, resume
/// where they stopped, wait for the network (or Wi-Fi) and continue after the app restarts.
final class OfflineManager: ObservableObject {
    struct Area: Codable, Identifiable, Equatable {
        enum Status: String, Codable {
            case queued
            case downloading
            case paused
            case waitingForNetwork
            case complete
            case incomplete
        }

        let id: UUID
        var name: String
        var created: Date
        var shape: CoverageShape
        var maxZoom: Int
        var allowCellular: Bool
        var status: Status
        var failedTiles: Int
        var bytesOnDisk: Int64

        var directory: URL { AppPaths.offlineArea(id) }
    }

    @Published private(set) var areas: [Area] = []
    @Published private(set) var progress: DownloadProgress?
    @Published private(set) var activeID: UUID?

    private var task: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private let indexURL = AppPaths.offline.appendingPathComponent("areas.json")

    /// Wi-Fi only session: requests fail (and the area waits) on cellular or personal hotspot.
    private let wifiSession: URLSession = OfflineManager.makeSession(allowExpensive: false)
    private let anyNetworkSession: URLSession = OfflineManager.makeSession(allowExpensive: true)

    private static func makeSession(allowExpensive: Bool) -> URLSession {
        let config = URLSessionConfiguration.default
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpMaximumConnectionsPerHost = 8
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = false
        config.allowsExpensiveNetworkAccess = allowExpensive
        return URLSession(configuration: config)
    }

    init() {
        if let data = try? Data(contentsOf: indexURL), let saved = try? JSONDecoder().decode([Area].self, from: data) {
            areas = saved.map { a in
                var a = a
                // A download that was running when the app was closed continues.
                if a.status == .downloading { a.status = .queued }
                return a
            }
        }
        publishToMap()
        Connectivity.shared.$state
            .removeDuplicates()
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.networkChanged() }
            }
            .store(in: &cancellables)
        DispatchQueue.main.async { self.startNextIfIdle() }
    }

    // MARK: - Public actions

    @discardableResult
    func add(name: String, shape: CoverageShape, maxZoom: Int, allowCellular: Bool) -> Area {
        let area = Area(id: UUID(), name: name, created: Date(), shape: shape, maxZoom: maxZoom,
                        allowCellular: allowCellular, status: .queued, failedTiles: 0, bytesOnDisk: 0)
        areas.append(area)
        save()
        publishToMap()
        startNextIfIdle()
        return area
    }

    func pause(_ id: UUID) {
        if activeID == id {
            task?.cancel() // the run loop marks it paused when the job stops
        }
        update(id) { $0.status = .paused }
    }

    func resume(_ id: UUID) {
        update(id) { $0.status = .queued; $0.failedTiles = 0 }
        startNextIfIdle()
    }

    func delete(_ id: UUID) {
        let wasActive = activeID == id
        let running = task
        areas.removeAll { $0.id == id }
        save()
        publishToMap()
        TileRepository.shared.closeArea(id)
        let dir = AppPaths.offline.appendingPathComponent(id.uuidString, isDirectory: true)
        if wasActive, let running = running {
            running.cancel()
            Task {
                await running.value
                try? FileManager.default.removeItem(at: dir)
            }
        } else {
            try? FileManager.default.removeItem(at: dir)
        }
    }

    func refreshSizes() {
        let snapshot = areas
        DispatchQueue.global(qos: .utility).async {
            let sizes = Dictionary(uniqueKeysWithValues: snapshot.map { ($0.id, AppPaths.size(of: $0.directory)) })
            DispatchQueue.main.async {
                for (id, size) in sizes { self.update(id, persist: false) { $0.bytesOnDisk = size } }
                self.save()
            }
        }
    }

    // MARK: - Running downloads

    /// Whether an area may download on the current network.
    func canDownloadNow(_ a: Area) -> Bool {
        let net = Connectivity.shared.state
        return net.isOnline && (a.allowCellular || !net.isExpensive)
    }

    private func networkChanged() {
        for a in areas where a.status == .waitingForNetwork && canDownloadNow(a) {
            update(a.id) { $0.status = .queued }
        }
        startNextIfIdle()
    }

    private func startNextIfIdle() {
        guard task == nil else { return }
        // Areas that can't use the current network wait; the first one that can, runs.
        for a in areas where a.status == .queued && !canDownloadNow(a) {
            update(a.id) { $0.status = .waitingForNetwork }
        }
        if let next = areas.first(where: { $0.status == .queued }) {
            run(next)
        } else {
            updateIdleTimer()
        }
    }

    private func run(_ area: Area) {
        let dir = area.directory
        let imagery: MBTiles
        let terrain: MBTiles
        do {
            imagery = try MBTiles(url: dir.appendingPathComponent(TileRepository.Layer.imagery.fileName), create: true)
            terrain = try MBTiles(url: dir.appendingPathComponent(TileRepository.Layer.terrain.fileName), create: true)
        } catch {
            update(area.id) { $0.status = .incomplete }
            return
        }
        imagery.setMetadata(["name": area.name, "format": "jpg", "type": "baselayer", "minzoom": "0",
                             "maxzoom": String(area.maxZoom), "attribution": TileSources.attribution])
        terrain.setMetadata(["name": area.name + " terrain", "format": "png", "encoding": "terrarium",
                             "minzoom": "0", "maxzoom": String(Terrarium.maxZoom)])

        let session = area.allowCellular ? anyNetworkSession : wifiSession
        let job = TileDownloadJob(shape: area.shape, layers: [
            DownloadLayer(name: "Elevation", store: terrain,
                          fetcher: HTTPTileFetcher(session: session, url: TileSources.terrainURL),
                          zooms: 0...Terrarium.maxZoom, pruneByParent: false),
            DownloadLayer(name: "Satellite", store: imagery,
                          fetcher: HTTPTileFetcher(session: session, url: TileSources.imageryURL),
                          zooms: 0...area.maxZoom, pruneByParent: true),
        ], concurrency: 8)

        let id = area.id
        activeID = id
        progress = DownloadProgress()
        update(id) { $0.status = .downloading }
        updateIdleTimer()

        task = Task.detached(priority: .utility) { [weak self] in
            let outcome = await job.run { p in
                DispatchQueue.main.async { if self?.activeID == id { self?.progress = p } }
            }
            imagery.close()
            terrain.close()
            let size = AppPaths.size(of: dir)
            DispatchQueue.main.async {
                self?.finished(id, outcome: outcome, size: size)
            }
        }
    }

    private func finished(_ id: UUID, outcome: DownloadOutcome, size: Int64) {
        task = nil
        activeID = nil
        progress = nil
        update(id) { a in
            a.bytesOnDisk = size
            switch outcome {
            case .completed(let failed):
                a.failedTiles = failed
                a.status = failed == 0 ? .complete : .incomplete
            case .offline:
                a.status = .waitingForNetwork
            case .cancelled:
                if a.status == .downloading { a.status = .paused }
            }
        }
        startNextIfIdle()
    }

    // MARK: - Helpers

    private func update(_ id: UUID, persist: Bool = true, _ change: (inout Area) -> Void) {
        guard let i = areas.firstIndex(where: { $0.id == id }) else { return }
        change(&areas[i])
        if persist { save() }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(areas) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }

    private func publishToMap() {
        TileRepository.shared.setAreas(areas.compactMap { a in
            guard let b = a.shape.bounds else { return nil }
            let nw = WebMercator.world(GeoPoint(lat: b.maxLat, lon: b.minLon))
            let se = WebMercator.world(GeoPoint(lat: b.minLat, lon: b.maxLon))
            return TileRepository.AreaRef(id: a.id, directory: a.directory, minX: nw.x, minY: nw.y, maxX: se.x, maxY: se.y)
        })
    }

    /// Keep the screen on while a download runs in the foreground so it isn't interrupted.
    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = activeID != nil
    }
}
