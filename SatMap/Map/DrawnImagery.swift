import MapKit
import UIKit

/// What a map's legends show (`DrawnImagery.onKey`): the contour interval drawn at its zoom, and the heights the
/// elevation colours run between (blue to red); nil where they aren't drawn.
struct TerrainKey: Equatable {
    var contours: ContourInterval?
    var colours: ElevationRange?
}

/// Metres between contour lines, and between the bold ones (labelled with their height).
struct ContourInterval: Equatable {
    let step: Double
    let index: Double
}

/// The satellite imagery (with the contour lines and elevation colours) of one map view, which SatMap draws itself (`SatelliteRenderer`):
/// the phone's map has one, and with CarPlay the car's map another (the tiles in memory are shared). Tiles that arrive
/// are shown with a freshly drawn layer put under the shown one, which goes once the new one has painted the screen:
/// redrawing part of a layer would empty it first (grey).
final class DrawnImagery {
    private unowned let mapView: MKMapView
    /// Whether the map is on screen: MapKit doesn't paint otherwise.
    private let isVisible: () -> Bool
    /// Whether contour lines and elevation colours are drawn (a new layer comes when it changes: `terrainChanged`).
    private let terrain: () -> TerrainMode
    let map = DrawnMap()
    /// The heights the elevation colours run between, chosen from those on screen (`updateColourRange`); where it was
    /// last chosen or kept (one from somewhere else isn't kept); and since when the first range for a place has waited
    /// for the rest of the screen's heights.
    private(set) var colourRange: ElevationRange?
    private var rangeArea = MKMapRect.null
    private var rangeWaitSince: CFTimeInterval?
    /// Whether the screen has land with heights in memory (else nothing is coloured, and the legend goes).
    private var heightsOnScreen = false
    /// Told what the legends should show as it changes (the phone's map has them).
    var onKey: ((TerrainKey) -> Void)?

    /// The layer shown, and while a new one replaces it (`swap`), the one going (on top until the new one has painted).
    private var drawn: SatelliteDrawnOverlay
    private var outgoing: SatelliteDrawnOverlay?
    private var swapScheduled = false
    private var lastSwap: CFTimeInterval = 0
    /// Swaps put off while the shown layer's own tiles are decoded for the new one.
    private var swapDeferrals = 0
    /// Between MapKit's region-will-change and did-change: swaps wait, leaving MapKit's drawing to what comes into view.
    private var moving = false
    private var movingSince: CFTimeInterval = 0
    /// A new layer owed for tiles that arrived while the map moved or was off screen.
    private var swapWaiting = false
    private var stopCount = 0

    init(mapView: MKMapView, isVisible: @escaping () -> Bool, terrain: @escaping () -> TerrainMode) {
        self.mapView = mapView
        self.isVisible = isVisible
        self.terrain = terrain
        drawn = SatelliteDrawnOverlay(urlTemplate: nil)
        map.onArrival = { [weak self] t, content in self?.tileArrived(t, content) }
    }

    /// Puts the imagery at the bottom of the map (under blocks, track …): it replaces Apple's map. Once the map view's
    /// delegate holds this, as MapKit may ask it for the layer's renderer straight away.
    func addToMap() {
        mapView.insertOverlay(drawn, at: 0, level: .aboveLabels)
    }

    /// The renderer for a layer of the imagery (from the map view delegate).
    func renderer(for overlay: SatelliteDrawnOverlay) -> MKOverlayRenderer {
        let mode = terrain()
        return SatelliteRenderer(overlay: overlay, map: map, contours: mode.showsContours, colours: mode.showsColours,
                                 range: colourRange)
    }

    private func renderer(_ o: SatelliteDrawnOverlay) -> SatelliteRenderer? {
        mapView.renderer(for: o) as? SatelliteRenderer
    }

    // MARK: - The map moving

    func regionWillChange() {
        if !moving { movingSince = CACurrentMediaTime() }
        moving = true
    }

    func regionDidChange() {
        moving = false
        if swapWaiting {
            swapWaiting = false
            scheduleSwap()
        } else if outgoing == nil, renderer(drawn)?.hasPainted(screenPoints) == false {
            // MapKit can take a second or more to paint what a pan brought into view (it paints a new layer at once).
            tileLog.debug("drawn layer hasn't painted the screen: new layer")
            swap()
        }
        mapStopped()
    }

    func visibleRegionChanged() {
        if let levels = levels { setFocus(levels) }
        reportKey()
    }

    private func setFocus(_ levels: (imagery: Int, contours: Int)) {
        let v = mapView.visibleMapRect
        map.setFocus(v.insetBy(dx: -v.width / 4, dy: -v.height / 4), level: levels.imagery, contourLevel: levels.contours)
    }

    /// The map has stopped: once it has been still a moment (so the tiles on screen go first), gets the imagery ready
    /// for the next zoom or pan (`ImageryPrefetch`).
    private func mapStopped() {
        stopCount += 1
        let n = stopCount
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self = self, n == self.stopCount, self.isVisible(), let levels = self.levels else { return }
            self.setFocus(levels)
            ImageryPrefetch.mapStopped(visible: self.mapView.visibleMapRect, level: levels.imagery)
            self.renderer(self.drawn)?.loadStandIns()
            self.updateColourRange()
        }
    }

    /// The imagery level for the zoom on screen: tiles about 256 pixels across on screen (a tile pixel 0.7 to 1.4
    /// screen pixels, at the layer's pixels per point, from its latest paint). Not at the zoom MapKit paints at, the
    /// next whole zoom level at or above the one on screen (the zoom test showed paints at a zoom scale of 2⁻⁶ with
    /// 2⁻⁶·⁰⁹ on screen): there, tiles would show at half to full size, up to four times as many for a screen.
    /// And the contour level: tiles 256 points across at the zoom MapKit paints at, as it asked for them as a tile layer.
    private var levels: (imagery: Int, contours: Int)? {
        guard let perPoint = centreMapPointsPerPoint else { return nil }
        let onScreen = 1 / perPoint // points per map point
        let painted = pow(2, ceil(log2(onScreen) - 1e-6))
        var pixelsPerPoint = Double(max(1, mapView.window?.screen.scale ?? mapView.traitCollection.displayScale))
        let paint = map.lastPaintScale
        if paint.pixelsPerPoint > 0 { pixelsPerPoint = Double(paint.pixelsPerPoint) }
        return (SatelliteRenderer.level(pixelsPerMapPoint: onScreen * pixelsPerPoint),
                SatelliteRenderer.level(pixelsPerMapPoint: painted))
    }

    /// Map points per screen point in the middle of the map, measured across 100 points (right however the map is
    /// turned).
    private var centreMapPointsPerPoint: Double? {
        let b = mapView.bounds
        guard b.width > 100 else { return nil }
        let l = MKMapPoint(mapView.convert(CGPoint(x: b.midX - 50, y: b.midY), toCoordinateFrom: mapView))
        let r = MKMapPoint(mapView.convert(CGPoint(x: b.midX + 50, y: b.midY), toCoordinateFrom: mapView))
        let world = MKMapSize.world.width
        let dx = abs(r.x - l.x), dy = r.y - l.y
        let d = hypot(min(dx, world - dx), dy) // the short way round, across the date line
        return d > 0 ? d / 100 : nil
    }

    // MARK: - New layers

    /// Tiles may be had again (the network is back, an area finished): a new layer asks again for those shown with a
    /// stand-in.
    func reload() {
        if renderer(drawn)?.hasStandIns == true { swap() }
    }

    /// The layer draws the contour lines and colours, so a new one comes, with or without.
    func terrainChanged(_ mode: TerrainMode) {
        if mode.showsColours { updateColourRange() } // from heights already in memory
        reportKey()
        guard let r = renderer(drawn) else { return }
        if r.drawsContours != mode.showsContours || r.drawsColours != mode.showsColours
            || (mode.showsColours && r.colourRange != colourRange) {
            swap()
        }
    }

    // MARK: - Elevation colours

    /// Chooses the colours' range from the heights in memory for the screen (keeping the one shown while it still
    /// suits: `ElevationColours.range`); a new one brings a new layer. A range from somewhere else (the colours were
    /// off, or the map was zoomed out, while it moved) is dropped, and the first one for a place waits a moment for
    /// the rest of the screen's heights, so the colours don't step through several.
    private func updateColourRange() {
        guard terrain().showsColours, isVisible(), let levels = levels, levels.contours >= ElevationColours.minTileZoom else { return }
        let visible = mapView.visibleMapRect
        let current = rangeArea.intersects(visible) ? colourRange : nil
        if current == nil && colourRange != nil {
            colourRange = nil
            rangeArea = .null
            reportKey()
        }
        let e = SatelliteRenderer.heightExtremes(in: visible, level: levels.contours, onScreen: screenPoints)
        if (e != nil) != heightsOnScreen {
            heightsOnScreen = e != nil
            reportKey()
        }
        guard let e = e else { return }
        let now = CACurrentMediaTime()
        if let since = rangeWaitSince, now - since > 5 { rangeWaitSince = nil } // an earlier wait
        if current == nil && !e.complete {
            if rangeWaitSince == nil {
                rangeWaitSince = now
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in self?.updateColourRange() }
            }
            if now - (rangeWaitSince ?? now) < 1.5 { return }
        }
        rangeWaitSince = nil
        rangeArea = visible
        let range = ElevationColours.range(low: e.low, high: e.high, current: current, complete: e.complete)
        guard range != colourRange else { return }
        tileLog.debug("colours from \(Int(range.low)) to \(Int(range.high)) m (screen \(Int(e.low))–\(Int(e.high)) m, complete=\(e.complete))")
        colourRange = range
        reportKey()
        if let r = renderer(drawn), r.colourRange != range { scheduleSwap() }
    }

    /// Tells the legends the contour interval at this zoom (as the layer draws them) and the colours' range.
    private func reportKey() {
        guard let report = onKey else { return }
        let mode = terrain()
        guard let levels = levels else { return report(TerrainKey()) }
        var pixelsPerPoint = Double(max(1, mapView.window?.screen.scale ?? mapView.traitCollection.displayScale))
        let paint = map.lastPaintScale
        if paint.pixelsPerPoint > 0 { pixelsPerPoint = Double(paint.pixelsPerPoint) }
        var key = TerrainKey()
        if mode.showsContours, levels.contours >= ContourPolicy.minTileZoom, let i = ContourPolicy.intervals(
            forPointZoom: ContourPolicy.pointZoom(tileZoom: levels.contours, screenScale: pixelsPerPoint)) {
            key.contours = ContourInterval(step: i.step, index: i.index)
        }
        if mode.showsColours, heightsOnScreen, levels.contours >= ElevationColours.minTileZoom { key.colours = colourRange }
        report(key)
    }

    /// Back on screen: a new layer owed for tiles that arrived meanwhile, and the colours' range for heights that did.
    func becameVisible() {
        if swapWaiting {
            swapWaiting = false
            scheduleSwap()
        }
        updateColourRange()
    }

    /// A tile has arrived in memory. If the layer shows a stand-in where it would show better, a new layer replaces it.
    /// Heights may change the colours' range.
    private func tileArrived(_ t: TileID, _ content: SatelliteRenderer.Content) {
        if content == .colours { updateColourRange() }
        guard let r = renderer(drawn), r.wouldImprove(with: t, content) else { return }
        scheduleSwap()
    }

    /// At most about once a second, so tiles arriving one after another come in together; not while the map moves.
    private func scheduleSwap() {
        if moving, CACurrentMediaTime() - movingSince < 3 { // (in case MapKit misses a region-did-change)
            swapWaiting = true
            return
        }
        guard !swapScheduled else { return }
        swapScheduled = true
        let wait = max(0.25, 1 - (CACurrentMediaTime() - lastSwap))
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self = self else { return }
            self.swapScheduled = false
            self.swap()
        }
    }

    /// Shows what arrived with a freshly drawn layer: it goes under the shown one, which covers it while it paints, and
    /// that one goes once it has painted the screen.
    private func swap() {
        guard isVisible() else {
            swapWaiting = true // on return
            return
        }
        guard outgoing == nil else { return scheduleSwap() } // one at a time
        let old = drawn
        // The new layer paints only decoded tiles: not before the ones shown are (a few tries at most).
        let mode = terrain()
        if let r = renderer(old), swapDeferrals < 3, !r.ownSpotsReady(contours: mode.showsContours, colours: mode.showsColours) {
            swapDeferrals += 1
            return scheduleSwap()
        }
        swapDeferrals = 0
        lastSwap = CACurrentMediaTime()
        let new = SatelliteDrawnOverlay(urlTemplate: nil)
        drawn = new
        outgoing = old
        mapView.insertOverlay(new, below: old)
        retire(old, once: new, ticks: 0)
    }

    /// Removes the old layer once the new one has painted the whole screen. If it hasn't in 2 s (on screen: MapKit
    /// doesn't paint off screen), the new one goes instead and the old one stays.
    private func retire(_ old: SatelliteDrawnOverlay, once new: SatelliteDrawnOverlay, ticks: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self = self, self.outgoing === old else { return }
            let visible = self.isVisible()
            let painted = visible && self.renderer(new)?.hasPainted(self.screenPoints) == true
            if painted {
                // A moment more for MapKit to show the last patch painted; less when the old layer shows grey on part of
                // the screen (what a pan or zoom brought into view), as it hides the new one there until it goes.
                let shown = self.renderer(old)?.paintedShare(self.screenPoints) ?? 1
                DispatchQueue.main.asyncAfter(deadline: .now() + (shown < 0.95 ? 0.1 : 0.4)) { [weak self] in
                    guard let self = self, self.outgoing === old else { return }
                    self.mapView.removeOverlay(old)
                    self.outgoing = nil
                }
            } else if visible, ticks >= 20 {
                self.mapView.removeOverlay(new)
                self.drawn = old
                self.outgoing = nil
                self.scheduleSwap()
            } else {
                self.retire(old, once: new, ticks: visible ? ticks + 1 : ticks)
            }
        }
    }

    /// Points across the map to its edges, closer together than MapKit's patches, as map points (right however the
    /// map is turned).
    private var screenPoints: [MKMapPoint] {
        let b = mapView.bounds.insetBy(dx: 1, dy: 1)
        return (0..<81).map { i in
            let p = CGPoint(x: b.minX + b.width * CGFloat(i % 9) / 8, y: b.minY + b.height * CGFloat(i / 9) / 8)
            return MKMapPoint(mapView.convert(p, toCoordinateFrom: mapView))
        }
    }
}
