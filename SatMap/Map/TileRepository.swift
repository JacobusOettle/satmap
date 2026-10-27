import Foundation
import ImageIO
import os
import UIKit

/// Tile pipeline diagnostics (visible in Console.app, subsystem com.jacobusoettle.satmap).
let tileLog = Logger(subsystem: "com.jacobusoettle.satmap", category: "tiles")

/// Where map tiles come from: downloaded offline areas first, then the network (if online), and for zooms
/// deeper than the imagery goes, an enlarged crop of the nearest ancestor tile.
final class TileRepository {
    static let shared = TileRepository()

    enum Layer {
        case imagery
        case terrain

        var fileName: String {
            switch self {
            case .imagery: return "imagery.mbtiles"
            case .terrain: return "terrain.mbtiles"
            }
        }
    }

    struct AreaRef {
        let id: UUID
        let directory: URL
        /// Web-mercator world bounds of the area (including its buffer).
        let minX: Double
        let minY: Double
        let maxX: Double
        let maxY: Double
    }

    private final class OpenArea {
        /// Replaced when the area grows (the area along your track is updated as the track gets longer).
        var ref: AreaRef
        var stores: [String: MBTiles] = [:]
        var lastAttempt: [String: Date] = [:]

        init(ref: AreaRef) {
            self.ref = ref
        }
    }

    private let lock = NSLock()
    private var areas: [OpenArea] = []

    /// HTTP cache for on-screen tiles, so recently viewed places load again quickly.
    private let urlCache: URLCache
    /// Session for on-screen tiles.
    let browseSession: URLSession

    /// MapKit asks for dozens of tiles at once. Requests queued inside URLSession time out while they wait,
    /// so only a few are started at a time per server, newest first (the tiles for where the map is now).
    /// Imagery and elevation have separate queues so elevation (contours) isn't starved while the map moves.
    private let imageryLimiter = RequestLimiter(slots: 10)
    private let terrainLimiter = RequestLimiter(slots: 4)

    /// A network that is "connected" but can't reach the internet (e.g. a drone controller's Wi-Fi) makes every
    /// request time out. After several consecutive failures the network is skipped for a while.
    private var consecutiveFailures = 0
    private var networkPausedUntil = Date.distantPast

    // Elevation tiles decoded in memory (each 256 KB); a screen of contours at zoom 11–15 reads ~100 of them.
    private let demCache = LRUCache<TileID, DEMTile>(capacity: 160)
    /// A tile could not be loaded for a temporary reason (timeout, no internet): MapKit doesn't ask again
    /// by itself, so the map reloads its tiles later.
    private var retryNeeded = false
    private var retryNeededSince = Date.distantFuture
    /// A request that failed for a temporary reason, used to test whether the network has recovered.
    private var lastFailedURL: URL?
    private var lastSuccessAt = Date.distantPast
    private var demInFlight: [TileID: Task<(DEMTile?, TileFetchResult), Never>] = [:]
    /// Elevation tiles not to request again before the given time (after a failure or a missing tile).
    private var demRetryAfter: [TileID: Date] = [:]
    /// Imagery tiles the server said don't exist (beyond the imagery depth), to skip asking again.
    private var imageryMissing = Set<TileID>()
    /// Imagery requests under way; `started` once one has a connection (then it is shared whatever the priority).
    private var imageryInFlight: [TileID: (task: Task<TileFetchResult, Never>, priority: RequestPriority, id: Int,
                                           started: Bool)] = [:]
    private var imageryRequests = 0

    private init() {
        urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 1 << 30,
                            directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                                .appendingPathComponent("tiles-http"))
        let config = URLSessionConfiguration.default
        config.urlCache = urlCache
        config.requestCachePolicy = .useProtocolCachePolicy
        config.httpMaximumConnectionsPerHost = 10
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        browseSession = URLSession(configuration: config)
    }

    // MARK: - Offline areas

    func setAreas(_ refs: [AreaRef]) {
        lock.lock()
        defer { lock.unlock() }
        let keep = Set(refs.map(\.id))
        for a in areas where !keep.contains(a.ref.id) {
            a.stores.values.forEach { $0.close() }
        }
        var next: [OpenArea] = []
        for r in refs {
            if let open = areas.first(where: { $0.ref.id == r.id }) {
                open.ref = r // bounds may have changed; the open stores stay valid
                next.append(open)
            } else {
                next.append(OpenArea(ref: r))
            }
        }
        areas = next
    }

    /// Closes the readers of an area (before its files are deleted).
    func closeArea(_ id: UUID) {
        lock.lock()
        defer { lock.unlock() }
        if let a = areas.first(where: { $0.ref.id == id }) {
            a.stores.values.forEach { $0.close() }
            a.stores = [:]
        }
        areas.removeAll { $0.ref.id == id }
    }

    func offlineTile(_ t: TileID, layer: Layer) -> Data? {
        let b = t.worldBounds
        lock.lock()
        let candidates = areas.filter { $0.ref.minX < b.maxX && $0.ref.maxX > b.minX && $0.ref.minY < b.maxY && $0.ref.maxY > b.minY }
        var stores: [MBTiles] = []
        for a in candidates {
            if let s = store(a, layer) { stores.append(s) }
        }
        lock.unlock()
        for s in stores {
            if let d = s.tile(t) { return d }
        }
        return nil
    }

    /// True if a downloaded area recorded this tile, or an ancestor of it, as not existing on the server.
    private func offlineKnowsMissing(_ t: TileID) -> Bool {
        let b = t.worldBounds
        lock.lock()
        let stores = areas
            .filter { $0.ref.minX < b.maxX && $0.ref.maxX > b.minX && $0.ref.minY < b.maxY && $0.ref.maxY > b.minY }
            .compactMap { store($0, .imagery) }
        lock.unlock()
        for s in stores {
            for k in 0...min(4, t.z) {
                let a = t.ancestor(levels: k)
                if s.isMissing(a) { return true }
                if k > 0 && s.contains(a) { break }
            }
        }
        return false
    }

    /// Opens an area's store lazily (call with lock held). The file appears once its download starts.
    private func store(_ a: OpenArea, _ layer: Layer) -> MBTiles? {
        if let s = a.stores[layer.fileName] { return s }
        if let last = a.lastAttempt[layer.fileName], Date().timeIntervalSince(last) < 10 { return nil }
        a.lastAttempt[layer.fileName] = Date()
        let url = a.ref.directory.appendingPathComponent(layer.fileName)
        guard FileManager.default.fileExists(atPath: url.path), let s = try? MBTiles(url: url, create: false) else { return nil }
        a.stores[layer.fileName] = s
        return s
    }

    // MARK: - Imagery for display

    enum ImageryQuality {
        case exact
        /// Part of an ancestor enlarged, as the imagery doesn't go this deep here.
        case enlargedForGood
        /// Part of an ancestor enlarged for now: the tile isn't saved offline and couldn't be loaded.
        case enlargedForNow
    }

    /// The imagery for an on-screen tile. `urgent` requests go before the others.
    func imageryForDisplay(_ t: TileID, urgent: Bool = false) async -> (data: Data, quality: ImageryQuality)? {
        guard let r = await nearestImagery(t, urgent: urgent) else { return nil }
        if r.tile == t { return (r.data, .exact) }
        // Enlarge part of the nearest ancestor that exists.
        let a = r.tile, k = t.z - a.z
        let enlarged = await Work.run(on: Work.images) {
            ImageTools.enlarge(r.data, levels: k, x: t.x - (a.x << k), y: t.y - (a.y << k))
        }
        return enlarged.map { ($0, r.beyondDepth ? .enlargedForGood : .enlargedForNow) }
    }

    /// The tile's own imagery, or if it can't be had (deeper than the imagery goes, or not saved offline and not
    /// loaded) the nearest coarser tile that exists, as it is: the drawn layer enlarges what it has itself.
    /// `beyondDepth`: the tile itself doesn't exist.
    func nearestImagery(_ t: TileID, urgent: Bool = false) async -> (tile: TileID, data: Data, beyondDepth: Bool)? {
        let priority: RequestPriority = urgent ? .urgent : .normal
        if let d = offlineTile(t, layer: .imagery) { return (t, d, false) }
        // Inside a downloaded area the downloader already learnt where the imagery ends: don't wait on the
        // network for tiles it knows don't exist.
        var beyondDepth = offlineKnowsMissing(t)
        var online = networkUsable && !beyondDepth
        // Not tried because of a pause after failures: reloaded once the network is back (as for elevation).
        if !online && !beyondDepth && Connectivity.shared.isOnlineNow { noteRetryNeeded() }
        if online {
            switch await onlineImagery(t, priority: priority) {
            case .data(let d): return (t, d, false)
            case .missing: beyondDepth = true // deeper than the imagery goes here: an ancestor below
            case .failed, .offline:
                // Don't show (and have MapKit keep) a blurry stand-in for a tile that exists but failed
                // to load; only offline ancestors are used, and the tile is asked for again later.
                online = false
                noteRetryNeeded()
            }
        }
        // The nearest ancestor that exists.
        for k in 1...max(1, min(8, t.z)) where t.z - k >= 0 {
            let a = t.ancestor(levels: k)
            var data = offlineTile(a, layer: .imagery)
            if data == nil && online {
                switch await onlineImagery(a, priority: priority) {
                case .data(let d): data = d
                case .missing: break
                case .failed, .offline:
                    // Same rule as above: no coarser stand-in for an ancestor that failed to load.
                    online = false
                    noteRetryNeeded()
                }
            }
            if let d = data { return (a, d, beyondDepth) }
        }
        return nil
    }

    /// A tile's imagery only if it is at hand (offline areas, or online in the background, after on-screen tiles):
    /// for getting ready for the next zoom or pan. Nil rather than a stand-in.
    func imageryInBackground(_ t: TileID) async -> Data? {
        if let d = offlineTile(t, layer: .imagery) { return d }
        guard networkUsable, !offlineKnowsMissing(t) else { return nil }
        if case .data(let d) = await onlineImagery(t, priority: .background) { return d }
        return nil
    }

    /// One network request per imagery tile at a time (many deep tiles share the same ancestor), unless a tile wanted
    /// sooner is still waiting its turn behind on-screen ones: then it is asked for again, at its own priority, and
    /// the request it overtook fetches nothing and answers with the new one's result. Tiles the server doesn't have
    /// are remembered.
    private func onlineImagery(_ t: TileID, priority: RequestPriority) async -> TileFetchResult {
        let pending: (task: Task<TileFetchResult, Never>, id: Int)? = lock.withLock {
            if imageryMissing.contains(t) { return nil }
            if let existing = imageryInFlight[t], existing.started || existing.priority >= priority {
                return (existing.task, existing.id)
            }
            imageryRequests += 1
            let id = imageryRequests
            let url = TileSources.imageryURL(t)
            let qos: TaskPriority = priority == .background ? .utility : .userInitiated
            let task = Task.detached(priority: qos) { [self] () -> TileFetchResult in
                let fetched = await self.limitedFetch(url, priority: priority) {
                    self.lock.withLock {
                        guard self.imageryInFlight[t]?.id == id else { return false }
                        self.imageryInFlight[t]?.started = true
                        return true
                    }
                }
                if let r = fetched { return r }
                // Overtaken while waiting.
                if let newer = self.lock.withLock({ self.imageryInFlight[t]?.task }) { return await newer.value }
                return await self.limitedFetch(url, priority: priority)
            }
            imageryInFlight[t] = (task, priority, id, false)
            return (task, id)
        }
        guard let request = pending else { return .missing }
        let result = await request.task.value
        lock.withLock {
            if imageryInFlight[t]?.id == request.id { imageryInFlight[t] = nil }
            if case .missing = result {
                if imageryMissing.count > 20_000 { imageryMissing.removeAll() }
                imageryMissing.insert(t)
            }
        }
        return result
    }

    /// Whether to try the network for on-screen tiles right now.
    private var networkUsable: Bool {
        Connectivity.shared.isOnlineNow && lock.withLock { Date() >= networkPausedUntil }
    }

    private func limitedFetch(_ url: URL, priority: RequestPriority = .normal) async -> TileFetchResult {
        await limitedFetch(url, priority: priority, start: { true }) ?? .failed
    }

    /// A request when a connection is free. `start` is asked then: if it says no, nothing is fetched (nil). Failures
    /// in the background don't count towards pausing the network: only on-screen ones say it is down.
    private func limitedFetch(_ url: URL, priority: RequestPriority, start: () -> Bool) async -> TileFetchResult? {
        let limiter = url.host?.hasPrefix("elevation") == true ? terrainLimiter : imageryLimiter
        let asked = Date()
        await limiter.acquire(priority)
        defer { limiter.release(priority) }
        guard start() else { return nil }
        guard networkUsable else { return .offline }
        let started = Date()
        let result = await HTTPTileFetcher.fetch(url, session: browseSession)
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        if url.host?.hasPrefix("elevation") != true {
            let waited = Int(started.timeIntervalSince(asked) * 1000)
            let outcome = String(String(describing: result).prefix(7))
            let kind = String(describing: priority)
            tileLog.debug(
                "imagery \(kind, privacy: .public) waited \(waited) ms, \(outcome, privacy: .public) in \(ms) ms: \(url.path, privacy: .public)")
        }
        lock.withLock {
            switch result {
            case .data, .missing:
                consecutiveFailures = 0
                lastSuccessAt = Date()
            case .failed, .offline:
                tileLog.error("fetch failed (\(String(describing: result), privacy: .public)) after \(ms) ms: \(url.path, privacy: .public)")
                guard priority != .background else { break }
                lastFailedURL = url
                consecutiveFailures += 1
                if consecutiveFailures >= 8 {
                    tileLog.error("network paused for 45 s after repeated failures")
                    networkPausedUntil = Date().addingTimeInterval(45)
                    consecutiveFailures = 0
                }
            }
        }
        if url.host?.hasPrefix("elevation") == true {
            let outcome = String(String(describing: result).prefix(7))
            tileLog.debug("elevation fetch \(outcome, privacy: .public) in \(ms) ms: \(url.path, privacy: .public)")
        }
        return result
    }

    private func noteRetryNeeded() {
        lock.withLock { markRetryNeededLocked() }
    }

    private func markRetryNeededLocked() {
        if !retryNeeded { retryNeededSince = Date() }
        retryNeeded = true
    }

    /// True if tiles failed for temporary reasons and the network has recovered (a request that failed before now
    /// succeeds), so reloading the map's tiles is worthwhile. On a still-poor connection this stays false.
    func probeRecovery() async -> Bool {
        guard networkUsable else { return false }
        let (needed, since, probe, success) = lock.withLock { (retryNeeded, retryNeededSince, lastFailedURL, lastSuccessAt) }
        guard needed else { return false }
        // Some request has already succeeded since the failures: recovered. Otherwise ask again for one that failed.
        var recovered = success > since
        if !recovered, let url = probe {
            switch await limitedFetch(url) {
            case .data, .missing: recovered = true
            case .failed, .offline: recovered = false
            }
        }
        if recovered || probe == nil {
            lock.withLock {
                retryNeeded = false
                retryNeededSince = .distantFuture
                lastFailedURL = nil
            }
        }
        return recovered
    }

    /// Forget temporary failures (when the network comes back). What the server said doesn't exist is kept.
    func resetFailureState() {
        lock.withLock {
            demRetryAfter.removeAll()
            consecutiveFailures = 0
            networkPausedUntil = .distantPast
        }
    }

    /// Free memory while in the background, where large apps are the first to be terminated
    /// (which would also stop track recording).
    func trimForBackground() {
        demCache.removeAll()
        urlCache.memoryCapacity = 0
        lock.withLock {
            imageryMissing.removeAll()
            for a in areas {
                a.stores.values.forEach { $0.close() }
                a.stores = [:]
                a.lastAttempt = [:]
            }
        }
    }

    func restoreAfterBackground() {
        urlCache.memoryCapacity = 16 << 20
    }

    // MARK: - Elevation

    func demTile(_ t: TileID) async -> DEMTile? {
        if let c = demCache.value(for: t) { return c }
        if let d = offlineTile(t, layer: .terrain), let tile = await Work.run(on: Work.contours, { Terrarium.decode(d, id: t) }) {
            demCache.set(tile, for: t)
            return tile
        }
        guard networkUsable else {
            if Connectivity.shared.isOnlineNow { noteRetryNeeded() }
            return nil
        }

        // One network request per tile, shared by every caller that needs it meanwhile.
        let pending: Task<(DEMTile?, TileFetchResult), Never>? = lock.withLock {
            if let after = demRetryAfter[t], Date() < after { return nil }
            if let existing = demInFlight[t] { return existing }
            let task = Task.detached(priority: .userInitiated) { [self] () -> (DEMTile?, TileFetchResult) in
                let result = await self.limitedFetch(TileSources.terrainURL(t))
                guard case .data(let d) = result else { return (nil, result) }
                return (await Work.run(on: Work.contours) { Terrarium.decode(d, id: t) }, result)
            }
            demInFlight[t] = task
            return task
        }
        guard let task = pending else { return nil }

        let (tile, result) = await task.value
        lock.withLock {
            demInFlight[t] = nil
            switch (tile, result) {
            case (.some, _):
                demRetryAfter[t] = nil
            case (nil, .failed), (nil, .offline):
                // Temporary: try again soon, and reload the map once the network works.
                demRetryAfter[t] = Date().addingTimeInterval(30)
                markRetryNeededLocked()
            default:
                // Missing on the server or undecodable: retrying won't help for a while.
                demRetryAfter[t] = Date().addingTimeInterval(600)
            }
        }
        if let tile = tile { demCache.set(tile, for: t) }
        return tile
    }

    /// Fetches (concurrently) every elevation tile a sampler at `zoom` needs for the world rectangle.
    /// `complete` is false if any of them couldn't be loaded.
    func demTiles(zoom: Int, minX: Double, minY: Double, maxX: Double, maxY: Double) async -> (tiles: [TileID: DEMTile], complete: Bool) {
        let n = 1 << zoom
        let scale = Double(256 << zoom)
        let worldPixels = 256 * n
        let px0 = max(0, Int(floor(minX * scale - 0.5))), py0 = max(0, Int(floor(minY * scale - 0.5)))
        let px1 = min(worldPixels - 1, Int(floor(maxX * scale - 0.5)) + 1)
        let py1 = min(worldPixels - 1, Int(floor(maxY * scale - 0.5)) + 1)
        guard px0 <= px1, py0 <= py1 else { return ([:], false) }
        var ids: [TileID] = []
        for ty in (py0 / 256)...(py1 / 256) {
            for tx in (px0 / 256)...(px1 / 256) { ids.append(TileID(z: zoom, x: tx, y: ty)) }
        }
        let tiles = await withTaskGroup(of: (TileID, DEMTile?).self) { group -> [TileID: DEMTile] in
            for id in ids { group.addTask { (id, await self.demTile(id)) } }
            var out: [TileID: DEMTile] = [:]
            for await (id, tile) in group {
                if let tile = tile { out[id] = tile }
            }
            return out
        }
        return (tiles, tiles.count == ids.count)
    }

    /// Ground elevation in metres (bilinear on the finest available terrain zoom), or nil without data.
    func elevation(at p: GeoPoint) async -> Double? {
        let w = WebMercator.world(p)
        var z = Terrarium.maxZoom
        while z >= 10 {
            let tiles = await demTiles(zoom: z, minX: w.x, minY: w.y, maxX: w.x, maxY: w.y).tiles
            if !tiles.isEmpty, let e = DEMSampler(zoom: z, tile: { tiles[$0] }).elevation(worldX: w.x, worldY: w.y) {
                return e
            }
            z -= 1
        }
        return nil
    }

    func trimMemory() {
        demCache.removeAll()
    }
}

/// Fetches a single tile over HTTP and classifies the response for the downloader and the map.
final class HTTPTileFetcher: TileFetching {
    let session: URLSession
    let url: (TileID) -> URL

    init(session: URLSession, url: @escaping (TileID) -> URL) {
        self.session = session
        self.url = url
    }

    func fetch(_ tile: TileID) async -> TileFetchResult {
        await Self.fetch(url(tile), session: session)
    }

    static func fetch(_ url: URL, session: URLSession) async -> TileFetchResult {
        var request = URLRequest(url: url)
        request.setValue("SatMap/1.0 (iOS)", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed }
            switch http.statusCode {
            case 200:
                if data.isEmpty { return .missing }
                return ImageTools.looksLikeImage(data) ? .data(data) : .failed
            case 204, 404, 410:
                return .missing
            default:
                return .failed
            }
        } catch let e as URLError {
            switch e.code {
            case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
                return .offline
            default:
                return .failed
            }
        } catch {
            return .failed
        }
    }
}

enum ImageTools {
    static func looksLikeImage(_ d: Data) -> Bool {
        guard d.count >= 4 else { return false }
        let b = [UInt8](d.prefix(4))
        return (b[0] == 0xFF && b[1] == 0xD8) || (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47)
    }

    /// Crops the (x, y) sub-tile `levels` zooms below an ancestor tile and scales it up to 256 px.
    static func enlarge(_ data: Data, levels: Int, x: Int, y: Int) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        let size = CGFloat(image.width) / CGFloat(1 << levels)
        let rect = CGRect(x: CGFloat(x) * size, y: CGFloat(y) * size, width: size, height: size).integral
        guard rect.width >= 1, let sub = image.cropping(to: rect) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format)
        return renderer.jpegData(withCompressionQuality: 0.9) { ctx in
            ctx.cgContext.interpolationQuality = .high
            UIImage(cgImage: sub).draw(in: CGRect(x: 0, y: 0, width: 256, height: 256))
        }
    }
}

extension ImageTools {
    /// JPEG data of an image as it is.
    static func jpeg(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }

    /// Four tiles (top left, top right, bottom left, bottom right) shrunk into one 256 px tile.
    static func combine(_ quarters: [Data]) -> Data? {
        let images = quarters.compactMap { d -> CGImage? in
            guard let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
            return CGImageSourceCreateImageAtIndex(src, 0, nil)
        }
        guard images.count == 4 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format)
        return renderer.jpegData(withCompressionQuality: 0.9) { ctx in
            ctx.cgContext.interpolationQuality = .high
            for (i, image) in images.enumerated() {
                UIImage(cgImage: image).draw(in: CGRect(x: CGFloat(i % 2) * 128, y: CGFloat(i / 2) * 128, width: 128, height: 128))
            }
        }
    }
}

/// Dedicated queues for CPU-heavy tile work, so it doesn't occupy Swift's small cooperative thread pool
/// (which the downloads and elevation lookups also need).
enum Work {
    static let images: OperationQueue = makeQueue("satmap.images", 3, .userInitiated)
    // Contours are on-screen content the user is waiting for, like imagery.
    static let contours: OperationQueue = makeQueue("satmap.contours", 3, .userInitiated)
    // The backdrop under the imagery answers from memory; it must keep up with a whole screen of tiles at once.
    static let backdrop: OperationQueue = makeQueue("satmap.backdrop", 4, .userInitiated)
    // Decoding imagery for the drawn layer, off the threads MapKit draws on.
    static let prepare: OperationQueue = makeQueue("satmap.prepare", 2, .userInitiated)

    private static func makeQueue(_ name: String, _ width: Int, _ qos: QualityOfService) -> OperationQueue {
        let q = OperationQueue()
        q.name = name
        q.maxConcurrentOperationCount = width
        q.qualityOfService = qos
        return q
    }

    static func run<T>(on queue: OperationQueue, _ body: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.addOperation { continuation.resume(returning: body()) }
        }
    }
}

/// Which waiting requests go first.
enum RequestPriority: Int, Comparable {
    /// Getting ready for the next zoom or pan: after everything on screen.
    case background
    case normal
    /// One coarse tile that fills a whole new place.
    case urgent

    static func < (a: RequestPriority, b: RequestPriority) -> Bool { a.rawValue < b.rawValue }
}

/// Limits concurrent requests; waiting callers are served by priority, newest first. Background requests get at most
/// two connections, so on-screen ones never wait behind a slow batch of them.
final class RequestLimiter {
    private let lock = NSLock()
    private var free: Int
    /// Indexed by `RequestPriority.rawValue`.
    private var waiting: [[CheckedContinuation<Void, Never>]] = [[], [], []]
    private var background = 0
    private static let backgroundLimit = 2

    init(slots: Int) {
        free = slots
    }

    func acquire(_ priority: RequestPriority = .normal) async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            let now: Bool = lock.withLock {
                if free > 0, priority != .background || background < Self.backgroundLimit {
                    free -= 1
                    if priority == .background { background += 1 }
                    return true
                }
                waiting[priority.rawValue].append(c)
                return false
            }
            if now { c.resume() }
        }
    }

    /// With the priority the connection was acquired with.
    func release(_ priority: RequestPriority = .normal) {
        let next: CheckedContinuation<Void, Never>? = lock.withLock {
            if priority == .background { background -= 1 }
            for p in [RequestPriority.urgent, .normal] where !waiting[p.rawValue].isEmpty { return waiting[p.rawValue].popLast() }
            if background < Self.backgroundLimit, let c = waiting[RequestPriority.background.rawValue].popLast() {
                background += 1
                return c
            }
            free += 1
            return nil
        }
        next?.resume()
    }
}

/// A small thread-safe least-recently-used cache.
final class LRUCache<Key: Hashable, Value> {
    private let capacity: Int
    private var values: [Key: Value] = [:]
    private var order: [Key] = []
    private let lock = NSLock()

    init(capacity: Int) {
        self.capacity = capacity
    }

    func value(for key: Key) -> Value? {
        lock.lock()
        defer { lock.unlock() }
        guard let v = values[key] else { return nil }
        if let i = order.firstIndex(of: key) {
            order.remove(at: i)
            order.append(key)
        }
        return v
    }

    func set(_ value: Value, for key: Key) {
        lock.lock()
        defer { lock.unlock() }
        if values.updateValue(value, forKey: key) == nil {
            order.append(key)
            if order.count > capacity {
                let evicted = order.removeFirst()
                values[evicted] = nil
            }
        } else if let i = order.firstIndex(of: key) {
            order.remove(at: i)
            order.append(key)
        }
    }

    func removeAll() {
        lock.lock()
        defer { lock.unlock() }
        values = [:]
        order = []
    }
}
