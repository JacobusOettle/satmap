import Foundation
import ImageIO
import UIKit

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
        let ref: AreaRef
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
    /// so at most 6 are started at a time, newest first (the tiles for where the map is now).
    private let limiter = RequestLimiter(slots: 6)

    /// A network that is "connected" but can't reach the internet (e.g. a drone controller's Wi-Fi) makes every
    /// request time out. After several consecutive failures the network is skipped for a while.
    private var consecutiveFailures = 0
    private var networkPausedUntil = Date.distantPast

    // Elevation tiles decoded in memory (each 256 KB); a screen of contours at zoom 11–15 reads ~100 of them.
    private let demCache = LRUCache<TileID, DEMTile>(capacity: 160)
    /// A tile could not be loaded for a temporary reason (timeout, no internet): MapKit doesn't ask again
    /// by itself, so the map reloads its tiles later.
    private var retryNeeded = false
    /// A request that failed for a temporary reason, used to test whether the network has recovered.
    private var lastFailedURL: URL?
    private var demInFlight: [TileID: Task<DEMTile?, Never>] = [:]
    private var demFailedAt: [TileID: Date] = [:]
    /// Imagery tiles the server said don't exist (beyond the imagery depth), to skip asking again.
    private var imageryMissing = Set<TileID>()
    private var imageryInFlight: [TileID: Task<TileFetchResult, Never>] = [:]

    private init() {
        urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 1 << 30,
                            directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                                .appendingPathComponent("tiles-http"))
        let config = URLSessionConfiguration.default
        config.urlCache = urlCache
        config.requestCachePolicy = .useProtocolCachePolicy
        config.httpMaximumConnectionsPerHost = 6
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
            next.append(areas.first(where: { $0.ref.id == r.id }) ?? OpenArea(ref: r))
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

    func imageryForDisplay(_ t: TileID) async -> Data? {
        if let d = offlineTile(t, layer: .imagery) { return d }
        // Inside a downloaded area the downloader already learnt where the imagery ends: don't wait on the
        // network for tiles it knows don't exist.
        var online = networkUsable && !offlineKnowsMissing(t)
        if online {
            switch await onlineImagery(t) {
            case .data(let d): return d
            case .missing: break // deeper than the imagery goes here: enlarge an ancestor below
            case .failed, .offline:
                // Don't show (and have MapKit keep) a blurry stand-in for a tile that exists but failed
                // to load; only offline ancestors are used, and the tile is asked for again later.
                online = false
                noteRetryNeeded()
            }
        }
        // Enlarge part of the nearest ancestor that exists.
        for k in 1...max(1, min(8, t.z)) where t.z - k >= 0 {
            let a = t.ancestor(levels: k)
            var data = offlineTile(a, layer: .imagery)
            if data == nil && online {
                switch await onlineImagery(a) {
                case .data(let d): data = d
                case .missing: break
                case .failed, .offline:
                    // Same rule as above: no coarser stand-in for an ancestor that failed to load.
                    online = false
                    noteRetryNeeded()
                }
            }
            if let d = data {
                return await Work.run(on: Work.images) {
                    ImageTools.enlarge(d, levels: k, x: t.x - (a.x << k), y: t.y - (a.y << k))
                }
            }
        }
        return nil
    }

    /// One network request per imagery tile at a time (many deep tiles share the same ancestor);
    /// tiles the server doesn't have are remembered.
    private func onlineImagery(_ t: TileID) async -> TileFetchResult {
        let pending: Task<TileFetchResult, Never>? = lock.withLock {
            if imageryMissing.contains(t) { return nil }
            if let existing = imageryInFlight[t] { return existing }
            let task = Task.detached(priority: .userInitiated) { [self] in
                await self.limitedFetch(TileSources.imageryURL(t))
            }
            imageryInFlight[t] = task
            return task
        }
        guard let task = pending else { return .missing }
        let result = await task.value
        lock.withLock {
            imageryInFlight[t] = nil
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

    private func limitedFetch(_ url: URL) async -> TileFetchResult {
        await limiter.acquire()
        defer { limiter.release() }
        guard networkUsable else { return .offline }
        let result = await HTTPTileFetcher.fetch(url, session: browseSession)
        lock.withLock {
            switch result {
            case .data, .missing:
                consecutiveFailures = 0
            case .failed, .offline:
                lastFailedURL = url
                consecutiveFailures += 1
                if consecutiveFailures >= 8 {
                    networkPausedUntil = Date().addingTimeInterval(45)
                    consecutiveFailures = 0
                }
            }
        }
        return result
    }

    private func noteRetryNeeded() {
        lock.withLock { retryNeeded = true }
    }

    /// True if tiles failed for temporary reasons and the network has recovered (a request that failed before now
    /// succeeds), so reloading the map's tiles is worthwhile. On a still-poor connection this stays false.
    func probeRecovery() async -> Bool {
        guard networkUsable else { return false }
        let (needed, probe) = lock.withLock { (retryNeeded, lastFailedURL) }
        guard needed else { return false }
        if let url = probe {
            switch await limitedFetch(url) {
            case .data, .missing: break
            case .failed, .offline: return false
            }
        }
        lock.withLock {
            retryNeeded = false
            lastFailedURL = nil
        }
        return true
    }

    /// Forget temporary failures (when the network comes back). What the server said doesn't exist is kept.
    func resetFailureState() {
        lock.withLock {
            demFailedAt.removeAll()
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
        let pending: Task<DEMTile?, Never>? = lock.withLock {
            if let failed = demFailedAt[t], Date().timeIntervalSince(failed) < 30 { return nil }
            if let existing = demInFlight[t] { return existing }
            let task = Task.detached(priority: .utility) { [self] () -> DEMTile? in
                guard case .data(let d) = await self.limitedFetch(TileSources.terrainURL(t)) else { return nil }
                return await Work.run(on: Work.contours) { Terrarium.decode(d, id: t) }
            }
            demInFlight[t] = task
            return task
        }
        guard let task = pending else { return nil }

        let tile = await task.value
        lock.withLock {
            demInFlight[t] = nil
            if tile == nil {
                demFailedAt[t] = Date()
                retryNeeded = true
            } else {
                demFailedAt[t] = nil
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

/// Dedicated queues for CPU-heavy tile work, so it doesn't occupy Swift's small cooperative thread pool
/// (which the downloads and elevation lookups also need).
enum Work {
    static let images: OperationQueue = makeQueue("satmap.images", 3, .userInitiated)
    static let contours: OperationQueue = makeQueue("satmap.contours", 2, .utility)

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

/// Limits concurrent requests; waiting callers are served newest first.
final class RequestLimiter {
    private let lock = NSLock()
    private var free: Int
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(slots: Int) {
        free = slots
    }

    func acquire() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            let now: Bool = lock.withLock {
                if free > 0 {
                    free -= 1
                    return true
                }
                waiting.append(c)
                return false
            }
            if now { c.resume() }
        }
    }

    func release() {
        let next: CheckedContinuation<Void, Never>? = lock.withLock {
            if let c = waiting.popLast() { return c }
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
