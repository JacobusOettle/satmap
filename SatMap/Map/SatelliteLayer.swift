import ImageIO
import MapKit
import UIKit

/// Satellite imagery, so the map neither goes grey while tiles load nor flickers (`SatelliteMode`):
/// - Drawn (the default): `SatelliteRenderer` paints every spot at once with the best imagery in memory and loads
///   what it lacks; a new layer of it replaces the old one to show tiles that arrived. `ImageryPrefetch` keeps coarse
///   tiles around the screen in memory for it.
/// - Tiles: `SatelliteTileOverlay`, one of MapKit's tile layers, loads each tile itself (offline areas, then Esri),
///   over `SatelliteBackdropOverlay`, which answers at once from memory.
/// The bottom layer replaces Apple's map entirely, so MapKit doesn't download its own tiles; only that one, as MapKit
/// paints a grey loading grid where a replacing layer has no tile yet, which would hide the layers under it.

/// Imagery tiles loaded for the screen (JPEG data, 15–60 KB each), for the backdrop to build on.
enum ImageryMemory {
    static let tiles = RecentCache<TileID, Data>(capacity: 900)

    static func enteredBackground() {
        tiles.trim(to: 150)
        SatelliteRenderer.trim(to: 0)
        SatelliteBackdropOverlay.trim(to: 0)
    }

    static func trimMemory() {
        tiles.trim(to: 100)
        SatelliteRenderer.trim(to: 32)
        SatelliteBackdropOverlay.trim(to: 0)
    }
}

/// The tiles of each level: offline areas first, then Esri online, then an enlarged ancestor tile.
final class SatelliteTileOverlay: MKTileOverlay {
    /// Create with `SatelliteTileOverlay(urlTemplate: nil)`; tiles come from `loadTile`, not a URL template.
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        minimumZ = 0
        maximumZ = deepestTileZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    private let lock = NSLock()
    private var needsReload = false

    /// Whether a tile was answered with nothing or a stand-in since the last call (then a reload can improve it).
    func takeNeedsReload() -> Bool {
        lock.withLock {
            defer { needsReload = false }
            return needsReload
        }
    }

    // MapKit calls this concurrently on background queues; `result` may be called on any queue.
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        guard let t = TileID(path) else { return result(nil, TileUnavailable()) }
        // Tiles shown before come straight from memory (also when MapKit asks again after a reload).
        if let d = ImageryMemory.tiles.value(for: t) { return result(d, nil) }
        Task.detached(priority: .userInitiated) {
            let r = await TileRepository.shared.imageryForDisplay(t)
            if let r = r, r.quality == .exact {
                ImageryMemory.tiles.set(r.data, for: t)
            } else {
                self.lock.withLock { self.needsReload = true }
            }
            if let r = r { result(r.data, nil) } else { result(nil, TileUnavailable()) }
        }
    }
}

/// Underneath: whatever imagery is at hand for each tile, straight away.
final class SatelliteBackdropOverlay: MKTileOverlay {
    /// Create with `SatelliteBackdropOverlay(urlTemplate: nil)`.
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        minimumZ = 0
        maximumZ = deepestTileZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        guard let t = TileID(path) else { return result(nil, TileUnavailable()) }
        Task.detached(priority: .userInitiated) {
            if let data = await Self.backdrop(for: t) {
                result(data, nil)
            } else {
                result(nil, TileUnavailable())
            }
        }
    }

    /// How far up to look for a coarser tile in memory, and which one to ask for in a new place.
    private static let ancestorLevels = 8
    private static let seedLevels = 3
    /// Decoded tiles the backdrop cuts from (many tiles share one), so each is decoded once.
    private static let decoded = RecentCache<TileID, CGImage>(capacity: 64)

    static func trim(to count: Int) {
        decoded.trim(to: count)
    }

    private static func backdrop(for t: TileID) async -> Data? {
        let memory = ImageryMemory.tiles
        if let own = memory.value(for: t) { return own }
        // After zooming out: the four sharper tiles under this one.
        if t.z < TileSources.imageryMaxZoom {
            let children = (0..<4).map { TileID(z: t.z + 1, x: t.x * 2 + $0 % 2, y: t.y * 2 + $0 / 2) }
            let found = children.compactMap { memory.value(for: $0) }
            if found.count == 4 {
                return await Work.run(on: Work.backdrop) { ImageTools.combine(found) }
            }
        }
        // After zooming in: the nearest coarser tile, the part of it under this one.
        if let a = TileFallback.ancestor(of: t, maxLevels: ancestorLevels, has: { memory.contains($0) }),
           let d = memory.value(for: a) {
            return await cut(d, from: a, to: t)
        }
        // A new place: one tile a few levels up, asked for before the others.
        let seed = t.ancestor(levels: min(seedLevels, t.z))
        guard let r = await TileRepository.shared.imageryForDisplay(seed, urgent: true) else { return nil }
        if r.quality == .exact { memory.set(r.data, for: seed) }
        return seed == t ? r.data : await cut(r.data, from: seed, to: t)
    }

    /// The part of ancestor `a`'s image under tile `t`, at its own size: MapKit enlarges it to the tile, which is much
    /// quicker than enlarging it here (the backdrop has to answer a whole screen of tiles at once).
    private static func cut(_ data: Data, from a: TileID, to t: TileID) async -> Data? {
        await Work.run(on: Work.backdrop) {
            let image: CGImage
            if let i = decoded.value(for: a) {
                image = i
            } else {
                guard let src = CGImageSourceCreateWithData(data as CFData, nil),
                      let i = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
                else { return nil }
                decoded.set(i, for: a)
                image = i
            }
            let k = t.z - a.z
            let side = max(1, image.width >> k)
            let rect = CGRect(x: (t.x - (a.x << k)) * side, y: (t.y - (a.y << k)) * side, width: side, height: side)
            guard let part = image.cropping(to: rect) else { return nil }
            return ImageTools.jpeg(part)
        }
    }
}

extension TileID {
    /// The tile MapKit asks for, with x wrapped around the world; nil above or below it.
    init?(_ path: MKTileOverlayPath) {
        let n = 1 << path.z
        guard path.y >= 0, path.y < n else { return nil }
        self.init(z: path.z, x: ((path.x % n) + n) % n, y: path.y)
    }
}

/// Which way the imagery is drawn: a setting while the ways are tried out against each other (Offline maps, and the
/// zoom test, see ZoomTests).
enum SatelliteMode: String {
    /// One tile layer, as before the backdrop (only in the zoom test).
    case single
    /// "Tiles": a backdrop tile layer under the tile layer (`SatelliteBackdropOverlay`).
    case backdrop
    /// SatMap draws the imagery itself (`SatelliteRenderer`): the default.
    case renderer

    static let changed = Notification.Name("SatelliteModeChanged")

    static var current: SatelliteMode {
        UserDefaults.standard.string(forKey: "satelliteMode").flatMap(SatelliteMode.init) ?? .renderer
    }
}

/// Drawn mode's imagery, drawn by `SatelliteRenderer`: a tile layer only so that MapKit leaves out its own map (its
/// tiles aren't used). To show tiles that have arrived, a new one is put under it and it goes once the new one has
/// painted the screen (`MapController.swapDrawn`), so nothing on screen is emptied.
final class SatelliteDrawnOverlay: MKTileOverlay {
    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        result(nil, TileUnavailable())
    }
}

/// Paints Drawn mode's imagery (`SatelliteDrawnOverlay`): every spot at once, as MapKit asks for it, with the best imagery in memory (its own
/// tile, else the sharper ones under it after zooming out, over the nearest coarser one enlarged), and loads the tiles
/// it lacks (for a spot with nothing at all, first the tile three levels up, which covers 64 spots). It never has a
/// patch redrawn, which MapKit empties first (grey): when tiles have arrived that would improve what it shows, a new
/// layer replaces it. MapKit calls `draw` on several threads at once.
final class SatelliteRenderer: MKOverlayRenderer {
    /// Decoded tiles (256 KB each) drawn from, so each is decoded once: those in focus, and some.
    private static let decoded = RecentCache<TileID, UIImage>(capacity: 400)

    /// Called on the main queue when a tile has arrived in memory (loaded here or by `ImageryPrefetch`).
    static var onArrival: ((TileID) -> Void)?

    /// Tiles being loaded, and those to leave for a while (failed, or deeper than the imagery goes): shared by the
    /// layers, so a new one doesn't ask again for what its predecessor is loading.
    private static let loads = NSLock()
    private static var loading = Set<TileID>()
    private static var retryAfter: [TileID: CFTimeInterval] = [:]
    /// Around the screen and the level drawn there (set by `MapController` as the map moves). MapKit paints in
    /// large patches, mostly off screen, and keeps patches of other zooms: only spots in focus are loaded, decoded and
    /// improved by a new layer.
    private static var focus = (rect: MKMapRect.null, level: -1)

    static func setFocus(_ rect: MKMapRect, level: Int) {
        loads.withLock { focus = (rect, level) }
    }

    private static func inFocus(_ t: TileID, _ f: (rect: MKMapRect, level: Int)) -> Bool {
        abs(t.z - f.level) <= 1 && f.rect.intersects(mapRect(t))
    }

    private let lock = NSLock()
    /// Spots painted without their own tile, with the level of the coarse tile shown there (-1: nothing).
    private var standIns: [TileID: Int] = [:]
    /// The patches painted at the latest zoom.
    private var painted: [MKMapRect] = []
    private var paintedScale: MKZoomScale = 0

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        let world = MKMapSize.world.width
        let z = Self.level(pixelsPerMapPoint: Double(zoomScale) * Double(contentScaleFactor))
        let n = 1 << z
        let size = world / Double(n)
        let x0 = max(0, Int(floor(mapRect.minX / size))), x1 = min(n - 1, Int(ceil(mapRect.maxX / size)) - 1)
        let y0 = max(0, Int(floor(mapRect.minY / size))), y1 = min(n - 1, Int(ceil(mapRect.maxY / size)) - 1)
        guard x0 <= x1, y0 <= y1 else { return }
        let began = CACurrentMediaTime()
        let focus = Self.loads.withLock { Self.focus }
        // Only decoded tiles are copied here, so a patch paints quickly; tiles in memory not decoded yet are decoded
        // off this thread and come with the next layer. Each coarse tile is drawn once, under all the spots it serves.
        var own: [TileID] = [], shownStandIns: [TileID: Int] = [:]
        var coarse: [TileID: [TileID]] = [:], sharper: [TileID] = []
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        context.interpolationQuality = .medium
        for y in y0...y1 {
            for x in x0...x1 {
                let t = TileID(z: z, x: x, y: y)
                if let image = Self.decoded.value(for: t) {
                    image.draw(in: self.rect(for: Self.mapRect(t)))
                    own.append(t)
                    continue
                }
                let isNear = Self.inFocus(t, focus)
                if isNear {
                    if ImageryMemory.tiles.contains(t) { Self.prepare([t]) } else { Self.load(t, urgent: false) }
                }
                let deeper = sharperTiles(t, decodeMissing: isNear)
                sharper += deeper.tiles
                var shown: Int? = deeper.tiles.isEmpty ? nil : t.z + 1
                if !deeper.covered, let a = TileFallback.ancestor(of: t, maxLevels: 10, has: Self.has) {
                    coarse[a, default: []].append(t)
                    shown = a.z
                }
                shownStandIns[t] = shown ?? -1
                if shown == nil, isNear, t.z >= 1 { Self.load(t.ancestor(levels: min(3, t.z)), urgent: true) }
            }
        }
        for (a, spots) in coarse {
            guard let image = Self.image(a) else {
                for t in spots where shownStandIns[t] == a.z { shownStandIns[t] = -1 }
                continue
            }
            context.saveGState()
            context.clip(to: spots.map { self.rect(for: Self.mapRect($0)) })
            image.draw(in: self.rect(for: Self.mapRect(a)))
            context.restoreGState()
        }
        for c in sharper {
            Self.decoded.value(for: c)?.draw(in: self.rect(for: Self.mapRect(c)))
        }
        lock.withLock {
            if zoomScale != paintedScale {
                painted = []
                paintedScale = zoomScale
            }
            painted.append(mapRect)
            for t in own { standIns[t] = nil }
            standIns.merge(shownStandIns) { _, new in new }
        }
        let ms = Int((CACurrentMediaTime() - began) * 1000)
        let empty = shownStandIns.values.filter { $0 < 0 }.count
        tileLog.debug("drawn z\(z): \(own.count) own, \(shownStandIns.count - empty) stand-in, \(empty) empty in \(ms) ms")
    }

    /// The imagery level drawn at a zoom: tiles about 256 pixels across on screen.
    static func level(pixelsPerMapPoint: Double) -> Int {
        let pixels = MKMapSize.world.width * pixelsPerMapPoint
        guard pixels > 0, pixels.isFinite else { return 0 }
        return Int(min(Double(TileSources.imageryMaxZoom), max(0, log2(pixels / 256).rounded())))
    }

    /// Whether `t` would improve what this layer shows in focus: a spot's own tile, a coarser tile than the one shown
    /// there (or than nothing), or a sharper one under it.
    func wouldImprove(with t: TileID) -> Bool {
        let focus = Self.loads.withLock { Self.focus }
        return lock.withLock {
            standIns.contains { spot, shown in
                guard Self.inFocus(spot, focus) else { return false }
                if spot == t { return true }
                if t.z < spot.z { return t.z > shown && spot.ancestor(levels: spot.z - t.z) == t }
                return t.z - spot.z <= 2 && t.ancestor(levels: t.z - spot.z) == spot
            }
        }
    }

    /// Whether this layer shows anything other than spots' own tiles in focus (then a reload may improve it).
    var hasStandIns: Bool {
        let focus = Self.loads.withLock { Self.focus }
        return lock.withLock { standIns.keys.contains { Self.inFocus($0, focus) } }
    }

    /// Loads (or decodes) what is missing for the spots in focus that this layer shows with a stand-in: the map has
    /// stopped, and MapKit doesn't repaint patches it has painted (maybe before they came into focus).
    func loadStandIns() {
        let focus = Self.loads.withLock { Self.focus }
        let spots = lock.withLock { standIns.filter { Self.inFocus($0.key, focus) } }
        for (t, shown) in spots {
            if ImageryMemory.tiles.contains(t) {
                Self.prepare([t])
            } else {
                Self.load(t, urgent: false)
                if shown < 0, t.z >= 1 { Self.load(t.ancestor(levels: min(3, t.z)), urgent: true) }
            }
        }
    }

    /// Whether this layer has painted all these points at the latest zoom.
    func hasPainted(_ points: [MKMapPoint]) -> Bool {
        lock.withLock {
            !painted.isEmpty && points.allSatisfy { p in painted.contains { $0.contains(p) } }
        }
    }

    /// Loads a tile into memory, once at a time; `urgent` ones before the others.
    private static func load(_ t: TileID, urgent: Bool) {
        let now = CACurrentMediaTime()
        let start: Bool = loads.withLock {
            if let after = retryAfter[t], now < after { return false }
            return loading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) {
            let r = await TileRepository.shared.imageryForDisplay(t, urgent: urgent)
            let exact = r?.quality == .exact
            if let r = r, exact { ImageryMemory.tiles.set(r.data, for: t) }
            loads.withLock {
                loading.remove(t)
                // Not there for now: asked for again in a while, or much later if the imagery doesn't go this deep.
                if !exact { retryAfter[t] = CACurrentMediaTime() + (r?.quality == .enlargedForGood ? 600 : 30) }
            }
            if exact { prepare([t]) }
        }
    }

    /// Tiles that failed may be asked for again at once (the network is back).
    static func reload() {
        loads.withLock { retryAfter.removeAll() }
    }

    /// Decodes tiles in memory (off the drawing threads), so they can be drawn at once: each is announced
    /// (`onArrival`) when it is ready.
    static func prepare(_ tiles: [TileID]) {
        let wanted: [TileID] = loads.withLock {
            let w = tiles.filter { !decoding.contains($0) && !decoded.contains($0) && ImageryMemory.tiles.contains($0) }
            decoding.formUnion(w)
            return w
        }
        for t in wanted {
            Work.prepare.addOperation {
                let ready = image(t) != nil
                _ = loads.withLock { decoding.remove(t) }
                if ready { DispatchQueue.main.async { onArrival?(t) } }
            }
        }
    }

    private static var decoding = Set<TileID>()

    /// Whether a tile can be drawn from memory (its data, or its decoded image).
    private static func has(_ t: TileID) -> Bool {
        ImageryMemory.tiles.contains(t) || decoded.contains(t)
    }

    private static func image(_ t: TileID) -> UIImage? {
        if let i = decoded.value(for: t) {
            ImageryMemory.tiles.touch(t) // drawn from: keep its data too
            return i
        }
        guard let d = ImageryMemory.tiles.value(for: t), let i = UIImage(data: d)?.preparingForDisplay() else { return nil }
        decoded.set(i, for: t)
        return i
    }

    /// The decoded sharper tiles under a spot (after zooming out): each quarter's tile one level down, else what
    /// there is of its four two levels down; and whether they cover it all. With `decodeMissing`, quarters in memory
    /// not decoded yet are decoded for the next layer.
    private func sharperTiles(_ t: TileID, decodeMissing: Bool) -> (tiles: [TileID], covered: Bool) {
        let deepest = TileSources.imageryMaxZoom
        guard t.z < deepest else { return ([], false) }
        func quarters(_ p: TileID) -> [TileID] {
            (0..<4).map { TileID(z: p.z + 1, x: p.x * 2 + $0 % 2, y: p.y * 2 + $0 / 2) }
        }
        var tiles: [TileID] = []
        var covered = true
        for c in quarters(t) {
            if Self.decoded.contains(c) {
                tiles.append(c)
            } else {
                covered = false
                if decodeMissing, ImageryMemory.tiles.contains(c) { Self.prepare([c]) }
                if c.z < deepest { tiles += quarters(c).filter { Self.decoded.contains($0) } }
            }
        }
        return (tiles, covered)
    }

    static func trim(to count: Int) {
        decoded.trim(to: count)
    }

    private static func mapRect(_ t: TileID) -> MKMapRect {
        let size = MKMapSize.world.width / Double(1 << t.z)
        return MKMapRect(x: Double(t.x) * size, y: Double(t.y) * size, width: size, height: size)
    }
}

/// When the map comes to rest: gets the imagery ready for the next zoom or pan, so its new spots have something to
/// show at once rather than grey:
/// - coarse tiles around the screen, 3 levels up over twice the screen and 6 levels up over eight times it, in the
///   background (offline areas first, then online after everything on screen);
/// - for the Drawn layer, those and the tiles on screen decoded ahead (they are what a zoom in enlarges).
enum ImageryPrefetch {
    private static let lock = NSLock()
    private static var loading = Set<TileID>()

    static func mapStopped(visible: MKMapRect, level z: Int, decode: Bool) {
        let onScreen = tiles(z, in: visible)
        var surround: [TileID] = []
        for (up, grow) in [(3, 0.5), (6, 3.5)] where z - up >= 0 {
            let r = visible.insetBy(dx: -visible.width * grow, dy: -visible.height * grow)
            surround += tiles(z - up, in: r)
        }
        if decode { SatelliteRenderer.prepare(onScreen + surround) }
        let memory = ImageryMemory.tiles
        let wanted: [TileID] = lock.withLock {
            // Those already in memory are marked as just used, so the ones around the screen are kept longest.
            let w = surround.filter { !memory.touch($0) && !loading.contains($0) }
            loading.formUnion(w)
            return w
        }
        tileLog.debug("map stopped at z\(z): \(onScreen.count) on screen, \(surround.count) around, \(wanted.count) to load")
        for t in wanted {
            Task.detached(priority: .utility) {
                if let d = await TileRepository.shared.imageryInBackground(t) {
                    memory.set(d, for: t)
                    if decode { SatelliteRenderer.prepare([t]) }
                }
                _ = lock.withLock { loading.remove(t) }
            }
        }
    }

    /// The tiles of a level covering a map rect (wrapping round the world sideways).
    private static func tiles(_ z: Int, in r: MKMapRect) -> [TileID] {
        let n = 1 << z
        let size = MKMapSize.world.width / Double(n)
        let x0 = Int(floor(r.minX / size)), x1 = Int(ceil(r.maxX / size)) - 1
        let y0 = max(0, Int(floor(r.minY / size))), y1 = min(n - 1, Int(ceil(r.maxY / size)) - 1)
        guard x0 <= x1, y0 <= y1, x1 - x0 < 64, y1 - y0 < 64 else { return [] }
        var out = Set<TileID>()
        for y in y0...y1 {
            for x in x0...x1 { out.insert(TileID(z: z, x: ((x % n) + n) % n, y: y)) }
        }
        return Array(out)
    }
}
