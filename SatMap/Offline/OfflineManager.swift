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
        /// Satellite imagery only along these roads (with `Roads.corridorMeters` on each side); nil = the whole
        /// area. Elevation is always downloaded for the whole area.
        var roads: [[GeoPoint]]?
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
                // Left over from an export, or from a restore interrupted by the app closing (can be GBs).
                try? FileManager.default.removeItem(at: OfflineManager.exportFolder)
                try? FileManager.default.removeItem(at: OfflineManager.restoreFolder)
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
    func add(name: String, shape: CoverageShape, roads: [[GeoPoint]]?, maxZoom: Int, allowCellular: Bool) -> Area {
        let area = Area(id: UUID(), name: name, created: Date(), shape: shape, roads: roads, maxZoom: maxZoom,
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
        // An exported copy shares the area's storage: remove it too, or the space isn't freed.
        try? FileManager.default.removeItem(at: OfflineManager.exportFolder)
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

    // MARK: - Backup

    private static let backupLayers = [
        OfflineBackup.Layer(fileName: TileRepository.Layer.imagery.fileName, key: "imagery", label: "satellite"),
        OfflineBackup.Layer(fileName: TileRepository.Layer.terrain.fileName, key: "terrain", label: "elevation"),
    ]
    /// Files handed to the share sheet: clones of the areas' files, sharing their storage until either changes.
    static let exportFolder = FileManager.default.temporaryDirectory.appendingPathComponent("Export", isDirectory: true)
    /// Copies of picked backup files are made here before they are checked and moved into place.
    private static let restoreFolder = AppPaths.offline.appendingPathComponent("Restoring", isDirectory: true)

    /// What a backup task is doing ("Preparing export…"), or nil. One runs at a time.
    @Published private(set) var backupStatus: String?
    /// The outcome of the last backup task, for an alert; cleared by `clearBackupMessage`.
    @Published private(set) var backupMessage: String?
    /// From an export starting until its share sheet closes no download starts: an exported area's files must not
    /// change while they are prepared or copied.
    private var holdDownloads = false
    /// The download paused for an export, resumed by `finishExport`.
    private var pausedForExport: UUID?

    func clearBackupMessage() {
        backupMessage = nil
    }

    /// Exports areas: pauses a download running in one of them, puts each area's files (satellite and elevation,
    /// with the area's settings inside) into `exportFolder` and opens the share sheet. Its own presentation, so it
    /// works even if the Offline maps sheet is closed meanwhile.
    func export(_ ids: [UUID]) {
        guard backupStatus == nil else { return }
        backupStatus = "Preparing export…"
        holdDownloads = true
        prepareExport(ids) { [weak self] result in
            guard let self = self else { return }
            self.backupStatus = nil
            switch result {
            case .success(let urls) where !urls.isEmpty:
                SharePresenter.present(urls) { [weak self] in self?.finishExport() }
            case .success:
                self.finishExport()
                self.backupMessage = "Nothing has been downloaded yet to export."
            case .failure(let error):
                self.finishExport()
                self.backupMessage = (error as? LocalizedError)?.errorDescription ?? "Couldn't export."
            }
        }
    }

    private func prepareExport(_ ids: [UUID], completion: @escaping (Result<[URL], Error>) -> Void) {
        let chosen = areas.filter { ids.contains($0.id) }
        var waitFor: Task<Void, Never>?
        if let active = activeID, ids.contains(active), let running = task {
            pause(active)
            pausedForExport = active
            waitFor = running
        }
        let encoder = JSONEncoder()
        let items = chosen.map { a -> (folder: URL, name: String, json: String) in
            var saved = a
            saved.status = a.status == .complete ? .complete : .paused
            let json = (try? encoder.encode(saved)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            return (a.directory, a.name, json)
        }
        Task.detached(priority: .userInitiated) {
            await waitFor?.value
            let result = Result<[URL], Error> {
                try? FileManager.default.removeItem(at: OfflineManager.exportFolder)
                var used = Set<String>()
                return try items.flatMap { item in
                    try OfflineBackup.export(areaFolder: item.folder, layers: OfflineManager.backupLayers, name: item.name,
                                             areaJSON: item.json, into: OfflineManager.exportFolder, usedNames: &used)
                }
            }
            DispatchQueue.main.async { completion(result) }
        }
    }

    /// The share sheet closed: downloads may run again, including one paused for the export. The exported copies
    /// stay until the next export, a deleted area or the next launch, in case the system is still sending them.
    private func finishExport() {
        holdDownloads = false
        if let id = pausedForExport, areas.contains(where: { $0.id == id && $0.status == .paused }) {
            pausedForExport = nil
            resume(id)
        } else {
            pausedForExport = nil
            startNextIfIdle()
        }
    }

    /// Adds the areas in exported files (normally both files of each area; the satellite file alone also works,
    /// and ↻ then downloads the elevation). The summary for the user ends up in `backupMessage`.
    func restore(_ urls: [URL]) {
        guard backupStatus == nil, !urls.isEmpty else { return }
        backupStatus = "Restoring…"
        let known = Set(areas.map(\.id))
        let layers = OfflineManager.backupLayers
        DispatchQueue.global(qos: .userInitiated).async {
            let fm = FileManager.default
            let staging = OfflineManager.restoreFolder
            try? fm.removeItem(at: staging)
            try? fm.createDirectory(at: staging, withIntermediateDirectories: true)
            defer { try? fm.removeItem(at: staging) }

            var problems: [String] = []
            // Copy each file in first: files from iCloud Drive or another app can't be opened in place.
            var found: [UUID: (area: Area, files: [String: URL])] = [:]
            for (i, url) in urls.enumerated() {
                let local = staging.appendingPathComponent("\(i).mbtiles")
                let scoped = url.startAccessingSecurityScopedResource()
                var copyError: NSError?
                var copied = false
                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &copyError) { readable in
                    copied = (try? fm.copyItem(at: readable, to: local)) != nil
                }
                if scoped { url.stopAccessingSecurityScopedResource() }
                guard copied else {
                    problems.append("Couldn't copy \(url.lastPathComponent) (is there enough free space?).")
                    continue
                }
                do {
                    let info = try OfflineBackup.inspect(local)
                    let area = try JSONDecoder().decode(Area.self, from: info.areaJSON)
                    found[area.id, default: (area, [:])].files[info.layerKey] = local
                } catch {
                    problems.append((error as? LocalizedError)?.errorDescription ?? "\(url.lastPathComponent) isn't a SatMap backup.")
                }
            }

            var restored: [Area] = []
            for (id, item) in found {
                guard !known.contains(id) else {
                    problems.append("\(item.area.name) is already in SatMap.")
                    continue
                }
                let folder = AppPaths.offlineArea(id)
                var complete = true
                for layer in layers {
                    let target = folder.appendingPathComponent(layer.fileName)
                    try? fm.removeItem(at: target)
                    if let file = item.files[layer.key], (try? fm.moveItem(at: file, to: target)) != nil {
                        continue
                    }
                    complete = false
                }
                var area = item.area
                area.status = complete && area.status == .complete ? .complete : .paused
                area.problem = nil
                area.bytesOnDisk = AppPaths.size(of: folder)
                restored.append(area)
            }

            DispatchQueue.main.async {
                let added = restored.filter { a in !self.areas.contains { $0.id == a.id } }
                self.areas.append(contentsOf: added)
                self.save()
                self.publishToMap()
                if !added.isEmpty { self.completedRevision += 1 }
                var lines: [String] = []
                if !added.isEmpty {
                    lines.append("Restored \(added.map(\.name).joined(separator: ", ")).")
                }
                if added.contains(where: { $0.status != .complete }) {
                    lines.append("Tap ↻ on an area marked Paused to download what its backup didn't include.")
                }
                lines += problems
                self.backupStatus = nil
                self.backupMessage = lines.isEmpty ? "Nothing to restore." : lines.joined(separator: "\n")
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
            guard let self = self, self.areas.contains(where: { $0.status == .waitingForNetwork }) else { return }
            if self.task != nil {
                self.scheduleNetworkRetry() // another area is downloading; check again later
            } else {
                self.networkChanged()
            }
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
            update(id) { if $0.status == .downloading { $0.status = .queued } }
            task?.cancel()
        }
    }

    @objc private func willEnterForeground() {
        inForeground = true
        networkChanged()
    }

    private func startNextIfIdle() {
        guard task == nil, inForeground, !holdDownloads else { return }
        if let free = AppPaths.freeDiskSpace(), free < OfflineManager.minimumFreeSpace {
            for a in areas where a.status == .queued {
                update(a.id) { $0.status = .incomplete; $0.problem = "Less than 500 MB free on the iPhone. Free up space, then tap ↻." }
            }
            updateIdleTimer()
            return
        }
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
            update(area.id) {
                $0.status = .incomplete
                $0.problem = (error as? LocalizedError)?.errorDescription ?? "Couldn't open the tile database."
            }
            DispatchQueue.main.async { [weak self] in self?.startNextIfIdle() }
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
                          zooms: 0...area.maxZoom, pruneByParent: true, shape: area.roads.map(Roads.corridor)),
        ], concurrency: 8)

        let id = area.id
        activeID = id
        progress = DownloadProgress()
        update(id) { $0.status = .downloading }
        updateIdleTimer()

        task = Task.detached(priority: .utility) { [weak self] in
            var lastSpaceCheck = Date.distantPast
            let outcome = await job.run { p in
                DispatchQueue.main.async { if self?.activeID == id { self?.progress = p } }
                // Never fill the phone completely: the track log and the rest of the phone need room too.
                if Date().timeIntervalSince(lastSpaceCheck) > 10 {
                    lastSpaceCheck = Date()
                    if let free = AppPaths.freeDiskSpace(), free < OfflineManager.minimumFreeSpace {
                        DispatchQueue.main.async { self?.stopForLowSpace(id) }
                    }
                }
            }
            imagery.close()
            terrain.close()
            let size = AppPaths.size(of: dir)
            DispatchQueue.main.async {
                self?.finished(id, outcome: outcome, size: size)
            }
        }
    }

    static let minimumFreeSpace: Int64 = 500 << 20
    private var lowSpaceStop: UUID?

    private func stopForLowSpace(_ id: UUID) {
        guard activeID == id, lowSpaceStop == nil else { return }
        lowSpaceStop = id
        task?.cancel()
    }

    private func finished(_ id: UUID, outcome: DownloadOutcome, size: Int64) {
        let storedSomething = (progress?.fetched ?? 0) > 0
        if lowSpaceStop == id {
            lowSpaceStop = nil
            update(id) {
                $0.bytesOnDisk = size
                $0.status = .incomplete
                $0.problem = "Less than 500 MB free on the iPhone. Free up space, then tap ↻."
            }
            task = nil
            activeID = nil
            progress = nil
            completedRevision += storedSomething ? 1 : 0
            startNextIfIdle()
            return
        }
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
