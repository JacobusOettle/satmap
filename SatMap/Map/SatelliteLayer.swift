import MapKit
import UIKit

/// Satellite imagery, so the map neither goes grey while tiles load nor flickers: `SatelliteRenderer` paints every
/// spot at once with the best imagery in memory and loads what it lacks; a new layer of it replaces the old one to
/// show tiles that arrived. `ImageryPrefetch` keeps coarse tiles around the screen in memory for it. The layer
/// replaces Apple's map entirely, so MapKit doesn't download its own tiles.

/// Imagery tiles loaded for the screen (JPEG data, 15–60 KB each), decoded for drawing as needed.
enum ImageryMemory {
    static let tiles = RecentCache<TileID, Data>(capacity: 900)

    static func enteredBackground() {
        tiles.trim(to: 150)
        SatelliteRenderer.trim(to: 0)
    }

    static func trimMemory() {
        tiles.trim(to: 100)
        SatelliteRenderer.trim(to: 32)
    }
}

/// The imagery, drawn by `SatelliteRenderer`: a tile layer only so that MapKit leaves out its own map (its tiles
/// aren't used). To show tiles that have arrived, a new one is put under it and it goes once the new one has
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

/// Paints the imagery (`SatelliteDrawnOverlay`): every spot at once, as MapKit asks for it, with the best imagery in memory (its own
/// tile, else the sharper ones under it after zooming out, over the nearest coarser one enlarged), and loads the tiles
/// it lacks (for a spot with nothing at all, first the tile three levels up, which covers 64 spots). It never has a
/// patch redrawn, which MapKit empties first (grey): when tiles have arrived that would improve what it shows, a new
/// layer replaces it. MapKit calls `draw` on several threads at once.
/// It draws the contour lines over the imagery too (when switched on): a tile layer of them over this one would hold
/// up its paints after each change of zoom level (MapKit's, 0.2–0.4 s of grey). Contour tiles are at the level MapKit
/// asked for them as a tile layer (`MapController.imageryLevels`).
final class SatelliteRenderer: MKOverlayRenderer {
    /// Decoded tiles (256 KB each) drawn from, so each is decoded once: those in focus, and some (`setFocus` makes
    /// room for them all).
    private static let decoded = RecentCache<TileID, UIImage>(capacity: 400)
    /// Decoded contour tiles (256 KB each; `ContourRenderer`), and those with nothing to draw.
    private static let contourImages = RecentCache<TileID, (image: UIImage, complete: Bool)>(capacity: 240)
    private static let noContours = RecentCache<TileID, Bool>(capacity: 4000)

    enum Content { case imagery, contours }

    /// Called on the main queue when a tile has arrived in memory (loaded here or by `ImageryPrefetch`), or its
    /// contour lines.
    static var onArrival: ((TileID, Content) -> Void)?

    /// Tiles being loaded, and those to leave for a while (failed, or deeper than the imagery goes): shared by the
    /// layers, so a new one doesn't ask again for what its predecessor is loading.
    private static let loads = NSLock()
    private static var loading = Set<TileID>()
    private static var retryAfter: [TileID: (until: CFTimeInterval, beyondDepth: Bool)] = [:]
    private static var contoursLoading = Set<TileID>()
    private static var contoursRetryAfter: [TileID: CFTimeInterval] = [:]
    /// Around the screen and the level drawn there (set by `MapController` as the map moves). MapKit paints in
    /// large patches, mostly off screen, and keeps patches of other zooms: only spots in focus are loaded, decoded and
    /// improved by a new layer.
    private static var focus = (rect: MKMapRect.null, level: -1)
    private static var contourLevel = -1

    static func setFocus(_ rect: MKMapRect, level: Int, contourLevel: Int) {
        loads.withLock {
            focus = (rect, level)
            Self.contourLevel = contourLevel
        }
        // Room for every spot in focus, or a new layer would find some not decoded any more and bring another, and so
        // on for ever (a turned map's focus is larger).
        let size = MKMapSize.world.width / Double(1 << max(0, level))
        let spots = rect.isNull ? 0 : (rect.width / size + 1) * (rect.height / size + 1)
        decoded.resize(to: max(400, Int(min(spots, 4000)) * 3 / 2))
    }

    /// The focus for imagery tiles, and for contour tiles.
    private static var focuses: (imagery: (rect: MKMapRect, level: Int), contours: (rect: MKMapRect, level: Int)) {
        loads.withLock { (imagery: focus, contours: (rect: focus.rect, level: contourLevel)) }
    }

    /// MapKit's zoom scale and pixels per point in its latest paint: it paints finer than the zoom on screen, and
    /// `MapController` works out the level it paints at from these.
    private static var paintScale = (zoomScale: MKZoomScale(0), pixelsPerPoint: CGFloat(0))

    static var lastPaintScale: (zoomScale: MKZoomScale, pixelsPerPoint: CGFloat) {
        loads.withLock { paintScale }
    }

    private static func inFocus(_ t: TileID, _ f: (rect: MKMapRect, level: Int)) -> Bool {
        abs(t.z - f.level) <= 1 && f.rect.intersects(mapRect(t))
    }

    private let lock = NSLock()
    /// Spots painted without their own tile, with the level of the coarse tile shown there (-1: nothing), and those
    /// painted with it.
    private var standIns: [TileID: Int] = [:]
    private var ownSpots = Set<TileID>()
    /// The patches painted at the latest zoom, and the latest ones at any zoom.
    private var painted: [MKMapRect] = []
    private var paintedScale: MKZoomScale = 0
    private var paintedAny: [MKMapRect] = []
    /// Whether this layer draws contour lines (fixed: switching them on or off brings a new layer); the spots it drew
    /// without their own contour tile (true: with it, its edges approximated), and those with it.
    let drawsContours: Bool
    private var contoursLacking: [TileID: Bool] = [:]
    private var contourSpots = Set<TileID>()

    init(overlay: SatelliteDrawnOverlay, contours: Bool) {
        drawsContours = contours
        super.init(overlay: overlay)
    }

    override func canDraw(_ mapRect: MKMapRect, zoomScale: MKZoomScale) -> Bool {
        true
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        let began = CACurrentMediaTime()
        typealias Focus = (rect: MKMapRect, level: Int)
        let (focus, contourFocus): (Focus, Focus) = Self.loads.withLock {
            Self.paintScale = (zoomScale, contentScaleFactor)
            return (Self.focus, (rect: Self.focus.rect, level: Self.contourLevel))
        }
        // The level for the zoom on screen (`MapController.imageryLevels`): MapKit paints at the next whole zoom level
        // at or above it, so this is the level for that, or one below.
        let painting = Self.level(pixelsPerMapPoint: Double(zoomScale) * Double(contentScaleFactor))
        let z = min(painting, max(painting - 1, focus.level))
        let spots = Self.tiles(z, in: mapRect)
        guard !spots.isEmpty else { return }
        // Only decoded tiles are copied here, so a patch paints quickly; tiles in memory not decoded yet are decoded
        // off this thread and come with the next layer. Each coarse tile is drawn once, under all the spots it serves.
        var own: [TileID] = [], shownStandIns: [TileID: Int] = [:]
        var coarse: [TileID: [TileID]] = [:], sharper: [TileID] = []
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        context.interpolationQuality = .medium
        for t in spots {
            let isNear = Self.inFocus(t, focus)
            // Those out of focus aren't marked as used, so they are the first to go when there are too many.
            if let image = isNear ? Self.decoded.value(for: t) : Self.decoded.peek(t) {
                image.draw(in: self.rect(for: Self.mapRect(t)))
                if isNear { ImageryMemory.tiles.touch(t) } // on screen: keep its data longest
                own.append(t)
                continue
            }
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
        // Contour tiles 256 points across at the zoom MapKit paints at, as it asked for them as a tile layer.
        let cz = Self.level(pixelsPerMapPoint: Double(zoomScale))
        let contours = drawsContours && cz >= ContourPolicy.minTileZoom && ContourPolicy.intervals(
            forPointZoom: ContourPolicy.pointZoom(tileZoom: cz, screenScale: Double(contentScaleFactor))) != nil
        let contourTiles = contours ? Self.tiles(cz, in: mapRect) : []
        let lacking = contours ? drawContours(over: contourTiles, focus: contourFocus, in: context) : [:]
        lock.withLock {
            if zoomScale != paintedScale {
                painted = []
                paintedScale = zoomScale
            }
            painted.append(mapRect)
            paintedAny.append(mapRect)
            if paintedAny.count > 400 { paintedAny.removeFirst(100) }
            for t in own { standIns[t] = nil }
            ownSpots.formUnion(own)
            standIns.merge(shownStandIns) { _, new in new }
            ownSpots.subtract(shownStandIns.keys)
            if contours {
                for t in contourTiles { contoursLacking[t] = lacking[t] }
                contourSpots.formUnion(contourTiles.filter { lacking[$0] == nil })
                contourSpots.subtract(lacking.keys)
            }
        }
        // A tile that arrived while this patch was painting found no stand-in here to improve yet: announce it again.
        let late = shownStandIns.first { spot, shown in
            Self.decoded.contains(spot) || (TileFallback.ancestor(of: spot, maxLevels: 10, has: Self.has)?.z ?? -1) > shown
        }
        if let spot = late?.key { DispatchQueue.main.async { Self.onArrival?(spot, .imagery) } }
        if let spot = lacking.first(where: { Self.contoursBetter($0.key, than: $0.value) })?.key {
            DispatchQueue.main.async { Self.onArrival?(spot, .contours) }
        }
        let ms = Int((CACurrentMediaTime() - began) * 1000)
        let empty = shownStandIns.values.filter { $0 < 0 }.count
        tileLog.debug("drawn z\(z): \(own.count) own, \(shownStandIns.count - empty) stand-in, \(empty) empty, \(lacking.count) without contours in \(ms) ms")
    }

    /// Draws these contour tiles: each one's own lines, else for now its parent's enlarged or its children's; and
    /// loads those missing in focus. Returns the tiles drawn without their own lines (true: with them, their edges
    /// approximated).
    private func drawContours(over spots: [TileID], focus: (rect: MKMapRect, level: Int), in context: CGContext) -> [TileID: Bool] {
        var lacking: [TileID: Bool] = [:]
        var parents: [TileID: [TileID]] = [:]
        for t in spots {
            let isNear = Self.inFocus(t, focus)
            if let c = Self.contourImages.value(for: t) {
                c.image.draw(in: rect(for: Self.mapRect(t)))
                if !c.complete {
                    lacking[t] = true
                    if isNear { Self.loadContours(t, scale: contentScaleFactor) }
                }
                continue
            }
            if Self.noContours.contains(t) { continue }
            lacking[t] = false
            if isNear { Self.loadContours(t, scale: contentScaleFactor) }
            let p = t.ancestor(levels: 1)
            if p.z >= ContourPolicy.minTileZoom, Self.contourImages.contains(p) {
                parents[p, default: []].append(t)
            } else {
                for c in Self.quarters(t) { Self.contourImages.value(for: c)?.image.draw(in: rect(for: Self.mapRect(c))) }
            }
        }
        for (p, under) in parents {
            guard let c = Self.contourImages.value(for: p) else { continue }
            context.saveGState()
            context.clip(to: under.map { rect(for: Self.mapRect($0)) })
            c.image.draw(in: rect(for: Self.mapRect(p)))
            context.restoreGState()
        }
        return lacking
    }

    /// Whether the contours in memory for a spot are better than what was drawn there (`approximate`: its own tile,
    /// its edges approximated; else none of its own).
    private static func contoursBetter(_ t: TileID, than approximate: Bool) -> Bool {
        if noContours.contains(t) { return true }
        guard let c = contourImages.value(for: t) else { return false }
        return c.complete || !approximate
    }

    /// The imagery level drawn at a zoom: tiles about 256 pixels across on screen.
    static func level(pixelsPerMapPoint: Double) -> Int {
        let pixels = MKMapSize.world.width * pixelsPerMapPoint
        guard pixels > 0, pixels.isFinite else { return 0 }
        return Int(min(Double(TileSources.imageryMaxZoom), max(0, log2(pixels / 256).rounded())))
    }

    /// Whether `t` would improve what this layer shows in focus: a spot's own tile, a coarser tile than the one shown
    /// there (or than nothing), or a sharper one under it; or a spot's contour lines.
    func wouldImprove(with t: TileID, _ content: Content) -> Bool {
        let (focus, contourFocus) = Self.focuses
        if content == .contours {
            guard Self.inFocus(t, contourFocus), let approximate = lock.withLock({ contoursLacking[t] }) else {
                return false
            }
            return Self.contoursBetter(t, than: approximate)
        }
        return lock.withLock {
            standIns.contains { spot, shown in
                guard Self.inFocus(spot, focus) else { return false }
                if spot == t { return true }
                if t.z < spot.z { return t.z > shown && spot.ancestor(levels: spot.z - t.z) == t }
                return t.z - spot.z <= 2 && t.ancestor(levels: t.z - spot.z) == spot
            }
        }
    }

    /// Whether this layer shows anything other than spots' own tiles in focus, contour lines included (then a reload
    /// may improve it).
    var hasStandIns: Bool {
        let (focus, contours) = Self.focuses
        return lock.withLock {
            standIns.keys.contains { Self.inFocus($0, focus) } || contoursLacking.keys.contains { Self.inFocus($0, contours) }
        }
    }

    /// Loads (or decodes) what is missing for the spots in focus that this layer shows with a stand-in: the map has
    /// stopped, and MapKit doesn't repaint patches it has painted (maybe before they came into focus).
    func loadStandIns() {
        let (focus, cf) = Self.focuses
        let (spots, contours) = lock.withLock {
            (standIns.filter { $0.key.z == focus.level && Self.inFocus($0.key, focus) },
             contoursLacking.keys.filter { $0.z == cf.level && Self.inFocus($0, cf) })
        }
        for (t, shown) in spots {
            if ImageryMemory.tiles.contains(t) {
                Self.prepare([t])
            } else {
                Self.load(t, urgent: false)
                if shown < 0, t.z >= 1 { Self.load(t.ancestor(levels: min(3, t.z)), urgent: true) }
            }
        }
        for t in contours { Self.loadContours(t, scale: contentScaleFactor) }
    }

    /// Whether this layer has painted all these points at the latest zoom.
    func hasPainted(_ points: [MKMapPoint]) -> Bool {
        lock.withLock {
            !painted.isEmpty && points.allSatisfy { p in painted.contains { $0.contains(p) } }
        }
    }

    /// The share of these points this layer has painted at any zoom (what MapKit may show of it).
    func paintedShare(_ points: [MKMapPoint]) -> Double {
        lock.withLock {
            guard !points.isEmpty else { return 1 }
            return Double(points.filter { p in paintedAny.contains { $0.contains(p) } }.count) / Double(points.count)
        }
    }

    /// Loads a tile into memory, once at a time; `urgent` ones before the others.
    /// Loads a tile into memory, once at a time; `urgent` ones before the others. If it can't be had, the nearest
    /// coarser tile that exists is loaded instead (as it is: it is enlarged when drawn), which may be sharper than
    /// what the spot shows.
    private static func load(_ t: TileID, urgent: Bool) {
        let now = CACurrentMediaTime()
        let start: Bool = loads.withLock {
            if let after = retryAfter[t], now < after.until { return false }
            return loading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) {
            let r = await TileRepository.shared.nearestImagery(t, urgent: urgent)
            if let r = r { ImageryMemory.tiles.set(r.data, for: r.tile) }
            loads.withLock {
                loading.remove(t)
                // Not there for now: asked for again in a while, or much later if the imagery doesn't go this deep.
                if r?.tile != t {
                    let deep = r?.beyondDepth == true
                    retryAfter[t] = (CACurrentMediaTime() + (deep ? 600 : 30), deep)
                }
            }
            if let r = r { prepare([r.tile]) }
        }
    }

    /// Draws a spot's contour lines into memory (`ContourRenderer`), once at a time, and announces them. Those that
    /// can't be had, or only with their edges approximated, are asked for again in a while.
    private static func loadContours(_ t: TileID, scale: CGFloat) {
        let now = CACurrentMediaTime()
        let start: Bool = loads.withLock {
            if let until = contoursRetryAfter[t], now < until { return false }
            return contoursLoading.insert(t).inserted
        }
        guard start else { return }
        Task.detached(priority: .userInitiated) {
            let tile = await ContourRenderer.shared.tile(t, contentScale: Double(scale))
            let had = contourImages.contains(t)
            var arrived = true, again = false
            switch tile {
            case .lines(let png, let complete):
                if let i = UIImage(data: png)?.preparingForDisplay() {
                    noContours.remove(t)
                    contourImages.set((i, complete), for: t)
                    arrived = complete || !had
                } else {
                    arrived = false
                }
                again = !complete
            case .blank:
                noContours.set(true, for: t)
                contourImages.remove(t)
            case .unavailable:
                arrived = false
                again = true
            }
            loads.withLock {
                contoursLoading.remove(t)
                contoursRetryAfter[t] = again ? CACurrentMediaTime() + 30 : nil
            }
            if arrived { DispatchQueue.main.async { onArrival?(t, .contours) } }
        }
    }

    /// Tiles that failed may be asked for again at once (the network is back); not those deeper than the imagery.
    static func reload() {
        loads.withLock {
            retryAfter = retryAfter.filter { $0.value.beyondDepth }
            contoursRetryAfter = [:]
        }
    }

    /// Whether every spot this layer shows with its own tile in focus is decoded, so a new layer would show it too
    /// (after the app was in the background or memory ran low, they may not be), and its contour lines when the new
    /// layer draws them (`contours`). Those that aren't are decoded, or loaded again, for the next try.
    func ownSpotsReady(contours: Bool) -> Bool {
        let (focus, cf) = Self.focuses
        let (missing, missingContours) = lock.withLock {
            (ownSpots.filter { Self.inFocus($0, focus) && !Self.decoded.contains($0) },
             contourSpots.filter {
                 contours && Self.inFocus($0, cf) && !Self.contourImages.contains($0) && !Self.noContours.contains($0)
             })
        }
        guard !missing.isEmpty || !missingContours.isEmpty else { return true }
        for t in missing {
            if ImageryMemory.tiles.contains(t) { Self.prepare([t]) } else { Self.load(t, urgent: true) }
        }
        for t in missingContours { Self.loadContours(t, scale: contentScaleFactor) }
        return false
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
                if ready { DispatchQueue.main.async { onArrival?(t, .imagery) } }
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
        var tiles: [TileID] = []
        var covered = true
        for c in Self.quarters(t) {
            if Self.decoded.contains(c) {
                tiles.append(c)
            } else {
                covered = false
                if decodeMissing, ImageryMemory.tiles.contains(c) { Self.prepare([c]) }
                if c.z < deepest { tiles += Self.quarters(c).filter { Self.decoded.contains($0) } }
            }
        }
        return (tiles, covered)
    }

    /// The four tiles one level down.
    private static func quarters(_ p: TileID) -> [TileID] {
        (0..<4).map { TileID(z: p.z + 1, x: p.x * 2 + $0 % 2, y: p.y * 2 + $0 / 2) }
    }

    static func trim(to count: Int) {
        decoded.trim(to: count)
        contourImages.trim(to: count)
    }

    /// The tiles of a level under a map rect.
    private static func tiles(_ z: Int, in r: MKMapRect) -> [TileID] {
        let n = 1 << z
        let size = MKMapSize.world.width / Double(n)
        let x0 = max(0, Int(floor(r.minX / size))), x1 = min(n - 1, Int(ceil(r.maxX / size)) - 1)
        let y0 = max(0, Int(floor(r.minY / size))), y1 = min(n - 1, Int(ceil(r.maxY / size)) - 1)
        guard x0 <= x1, y0 <= y1 else { return [] }
        return (y0...y1).flatMap { y in (x0...x1).map { TileID(z: z, x: $0, y: y) } }
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
/// - those and the tiles on screen decoded ahead (they are what a zoom in enlarges).
enum ImageryPrefetch {
    private static let lock = NSLock()
    private static var loading = Set<TileID>()

    static func mapStopped(visible: MKMapRect, level z: Int) {
        let onScreen = tiles(z, in: visible)
        var surround: [TileID] = []
        for (up, grow) in [(3, 0.5), (6, 3.5)] where z - up >= 0 {
            let r = visible.insetBy(dx: -visible.width * grow, dy: -visible.height * grow)
            surround += tiles(z - up, in: r)
        }
        SatelliteRenderer.prepare(onScreen + surround)
        let memory = ImageryMemory.tiles
        let wanted: [TileID] = lock.withLock {
            // Those already in memory are marked as just used, so the ones around the screen are kept longest.
            let w = surround.filter { !memory.touch($0) && !loading.contains($0) }
            loading.formUnion(w)
            return w
        }
        let paint = SatelliteRenderer.lastPaintScale
        tileLog.debug("map stopped at z\(z): \(onScreen.count) on screen, \(surround.count) around, \(wanted.count) to load; last paint at zoom scale \(Double(paint.zoomScale)), \(Double(paint.pixelsPerPoint)) px/pt")
        for t in wanted {
            Task.detached(priority: .utility) {
                if let d = await TileRepository.shared.imageryInBackground(t) {
                    memory.set(d, for: t)
                    SatelliteRenderer.prepare([t])
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
