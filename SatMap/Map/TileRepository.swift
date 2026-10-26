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

    /// Session for on-screen tiles, with an on-disk HTTP cache so recently viewed places load again quickly.
    let browseSession: URLSession = {
        let config = URLSessionConfiguration.default
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("tiles-http")
        config.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 1 << 30, directory: cacheDir)
        config.requestCachePolicy = .useProtocolCachePolicy
        config.httpMaximumConnectionsPerHost = 6
        config.timeoutIntervalForRequest = 20
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    // Elevation tiles decoded in memory (each 256 KB).
    private let demCache = LRUCache<TileID, DEMTile>(capacity: 80)
    private var demInFlight: [TileID: Task<DEMTile?, Never>] = [:]
    private var demFailedAt: [TileID: Date] = [:]
    /// Imagery tiles the server said don't exist (beyond the imagery depth), to skip asking again.
    private var imageryMissing = Set<TileID>()
    private var imageryInFlight: [TileID: Task<TileFetchResult, Never>] = [:]

    private init() {}

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
        let online = Connectivity.shared.isOnlineNow
        if online, let d = await onlineImagery(t) { return d }
        // Deeper than the imagery goes (or not available offline): enlarge part of the nearest ancestor.
        for k in 1...max(1, min(8, t.z)) where t.z - k >= 0 {
            let a = t.ancestor(levels: k)
            var data = offlineTile(a, layer: .imagery)
            if data == nil && online { data = await onlineImagery(a) }
            if let d = data {
                return ImageTools.enlarge(d, levels: k, x: t.x - (a.x << k), y: t.y - (a.y << k))
            }
        }
        return nil
    }

    /// One network request per imagery tile at a time (many deep tiles share the same ancestor);
    /// tiles the server doesn't have are remembered.
    private func onlineImagery(_ t: TileID) async -> Data? {
        let session = browseSession
        let pending: Task<TileFetchResult, Never>? = lock.withLock {
            if imageryMissing.contains(t) { return nil }
            if let existing = imageryInFlight[t] { return existing }
            let task = Task.detached(priority: .userInitiated) {
                await HTTPTileFetcher.fetch(TileSources.imageryURL(t), session: session)
            }
            imageryInFlight[t] = task
            return task
        }
        guard let task = pending else { return nil }
        let result = await task.value
        lock.withLock {
            imageryInFlight[t] = nil
            if case .missing = result {
                if imageryMissing.count > 20_000 { imageryMissing.removeAll() }
                imageryMissing.insert(t)
            }
        }
        if case .data(let d) = result { return d }
        return nil
    }

    // MARK: - Elevation

    func demTile(_ t: TileID) async -> DEMTile? {
        if let c = demCache.value(for: t) { return c }
        if let d = offlineTile(t, layer: .terrain), let tile = Terrarium.decode(d, id: t) {
            demCache.set(tile, for: t)
            return tile
        }
        guard Connectivity.shared.isOnlineNow else { return nil }

        // One network request per tile, shared by every caller that needs it meanwhile.
        let session = browseSession
        let pending: Task<DEMTile?, Never>? = lock.withLock {
            if let failed = demFailedAt[t], Date().timeIntervalSince(failed) < 30 { return nil }
            if let existing = demInFlight[t] { return existing }
            let task = Task.detached(priority: .utility) { () -> DEMTile? in
                guard case .data(let d) = await HTTPTileFetcher.fetch(TileSources.terrainURL(t), session: session) else { return nil }
                return Terrarium.decode(d, id: t)
            }
            demInFlight[t] = task
            return task
        }
        guard let task = pending else { return nil }

        let tile = await task.value
        lock.withLock {
            demInFlight[t] = nil
            if tile == nil { demFailedAt[t] = Date() } else { demFailedAt[t] = nil }
        }
        if let tile = tile { demCache.set(tile, for: t) }
        return tile
    }

    /// Fetches (concurrently) every elevation tile a sampler at `zoom` needs for the world rectangle.
    func demTiles(zoom: Int, minX: Double, minY: Double, maxX: Double, maxY: Double) async -> [TileID: DEMTile] {
        let n = 1 << zoom
        let scale = Double(256 << zoom)
        let worldPixels = 256 * n
        let px0 = max(0, Int(floor(minX * scale - 0.5))), py0 = max(0, Int(floor(minY * scale - 0.5)))
        let px1 = min(worldPixels - 1, Int(floor(maxX * scale - 0.5)) + 1)
        let py1 = min(worldPixels - 1, Int(floor(maxY * scale - 0.5)) + 1)
        guard px0 <= px1, py0 <= py1 else { return [:] }
        var ids: [TileID] = []
        for ty in (py0 / 256)...(py1 / 256) {
            for tx in (px0 / 256)...(px1 / 256) { ids.append(TileID(z: zoom, x: tx, y: ty)) }
        }
        return await withTaskGroup(of: (TileID, DEMTile?).self) { group in
            for id in ids { group.addTask { (id, await self.demTile(id)) } }
            var out: [TileID: DEMTile] = [:]
            for await (id, tile) in group {
                if let tile = tile { out[id] = tile }
            }
            return out
        }
    }

    /// Ground elevation in metres (bilinear on the finest available terrain zoom), or nil without data.
    func elevation(at p: GeoPoint) async -> Double? {
        let w = WebMercator.world(p)
        var z = Terrarium.maxZoom
        while z >= 10 {
            let tiles = await demTiles(zoom: z, minX: w.x, minY: w.y, maxX: w.x, maxY: w.y)
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
