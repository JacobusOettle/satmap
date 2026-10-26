import Combine
import Foundation
import UIKit

/// Offline areas: each is a shape (lasso or KML) downloaded as satellite imagery up to `maxZoom`
/// plus elevation tiles for contours and height differences. Downloads run one at a time, resume
/// where they stopped, wait for the network (or Wi-Fi) and continue after the app restarts.
/// Used on the main thread only.
final class OfflineManager: ObservableObject, @unchecked Sendable {
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
        /// Why the last run stopped early (e.g. storage full), shown to the user.
        var problem: String?

        var directory: URL { AppPaths.offlineArea(id) }
    }

    @Published private(set) var areas: [Area] = []
    @Published private(set) var progress: DownloadProgress?
    @Published private(set) var activeID: UUID?
    /// Bumped whenever a download run ends, so the map can reload tiles that weren't available before.
    @Published private(set) var completedRevision = 0
    /// Downloads only run while the app is open: in the background they would compete with track
    /// recording for battery and for the CPU time iOS allows background apps.
    private var inForeground = UIApplication.shared.applicationState != .background

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
        if let data = try? Data(contentsOf: indexURL) {
            if let saved = try? JSONDecoder().decode([Area].self, from: data) {
                areas = saved.map { a in
                    var a = a
                    // A download that was running when the app was closed continues.
                    if a.status == .downloading { a.status = .queued }
                    return a
                }
                removeOrphanedDirectories()
            } else {
                // Never overwrite an index we can't read (that would orphan every downloaded area).
                try? FileManager.default.moveItem(at: indexURL, to: indexURL.appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970))"))
            }
        }
        publishToMap()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
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
                        allowCellular: allowCellular, status: .queued, failedTiles: 0, bytesOnDisk: 0, problem: nil)
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
        update(id) { $0.status = .queued; $0.failedTiles = 0; $0.problem = nil }
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

    /// Deletes area folders that no longer belong to an area (e.g. the app was closed while deleting one).
    private func removeOrphanedDirectories() {
        let known = Set(areas.map { $0.id.uuidString })
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: AppPaths.offline, includingPropertiesForKeys: [.isDirectoryKey])) ?? [] {
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if isDir, UUID(uuidString: url.lastPathComponent) != nil, !known.contains(url.lastPathComponent) {
                try? fm.removeItem(at: url)
            }
        }
    }

    // MARK: - Running downloads

    private var networkRetryDelay: TimeInterval = 15

    /// The phone may report "online" while requests fail (router up, internet down), and no network change will
    /// be announced when it recovers, so waiting areas are retried periodically with growing gaps.
    private func scheduleNetworkRetry() {
        let delay = networkRetryDelay
        networkRetryDelay = min(networkRetryDelay * 2, 600)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.task == nil else { return }
            if self.areas.contains(where: { $0.status == .waitingForNetwork }) { self.networkChanged() }
        }
    }

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

    @objc private func didEnterBackground() {
        inForeground = false
        if let id = activeID {
            // Stop now; it continues from where it was when the app is opened again.
            update(id) { $0.status = .queued }
            task?.cancel()
        }
    }

    @objc private func willEnterForeground() {
        inForeground = true
        startNextIfIdle()
    }

    private func startNextIfIdle() {
        guard task == nil, inForeground else { return }
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
        let storedSomething = (progress?.fetched ?? 0) > 0
        task = nil
        activeID = nil
        progress = nil
        var retry = false
        update(id) { a in
            a.bytesOnDisk = size
            // A pause by the user wins over however the run happened to end.
            if a.status == .paused, outcome == .cancelled || outcome == .offline { return }
            switch outcome {
            case .completed(let failed):
                a.failedTiles = failed
                a.status = failed == 0 ? .complete : .incomplete
                networkRetryDelay = 15
            case .offline:
                a.status = .waitingForNetwork
                retry = true
            case .cancelled:
                if a.status == .downloading { a.status = .paused }
            case .storageError(let message):
                a.status = .incomplete
                a.problem = message
            }
        }
        if storedSomething { completedRevision += 1 }
        if retry { scheduleNetworkRetry() }
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
