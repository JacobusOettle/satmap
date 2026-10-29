package com.jacobusoettle.satmap.map

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.ContourPolicy
import com.jacobusoettle.satmap.core.ElevationColours
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.TerrainMode
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.core.rounded
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.floor
import kotlin.math.max

/** What a map's legends show (`DrawnImagery.onKey`): the contour interval drawn at its zoom, and the heights the
 *  elevation colours run between (blue to red); null where they aren't drawn. */
data class TerrainKey(val contours: ContourInterval? = null, val colours: ElevationRange? = null)

/** Metres between contour lines, and between the bold ones (labelled with their height). */
data class ContourInterval(val step: Double, val index: Double)

/** The satellite imagery, with the contour lines and elevation colours, of one map view: the style's raster sources
 *  and layers (imagery, colours, contours), each a generation of `TileUrls` whose tiles `TileInterceptor` answers. The
 *  phone's map has one, and with Android Auto the car's map another (the tiles in memory are shared). MapLibre replaces
 *  a tile in place when a better answer comes (`Cache-Control`); a whole new layer, shown once it has its tiles, is only
 *  for a new colour range and for tiles to be asked for again at once (`reload`).
 *  `MapController` (or `CarMapController`) calls the hooks; this registers no listeners (DESIGN.md D18), and holds it
 *  as long as the map view lives (`destroy` when it goes): the sources' registrations (`TileLayers`) hold `drawnMap`
 *  only weakly. Main thread only. */
class DrawnImagery(
    private val mapView: MapView,
    private val map: MapLibreMap,
    /** Whether the map is on screen: new layers wait otherwise. */
    private val isVisible: () -> Boolean,
    /** Whether contour lines and elevation colours are drawn (their layers are shown or hidden: `terrainChanged`). */
    private val terrain: () -> TerrainMode,
    /** The scale the contour tiles are drawn at (in their URL), which the legend's interval and the source's first level
     *  follow: the iPhone's on the phone (`TileLevels.PHONE_CONTOUR_SCALE`), the screen's in the car. */
    private val contourScale: Double,
) {
    val drawnMap = DrawnMap()

    /** The colours' range and how it was chosen (`updateColourRange`). */
    private val colours = ColourRangeChoice()

    /** The heights the elevation colours run between, chosen from those on screen (`updateColourRange`). */
    val colourRange: ElevationRange? get() = colours.range

    /** Told what the legends should show as it changes (the phone's map has them). */
    var onKey: ((TerrainKey) -> Unit)? = null

    /** A source and its layer (same id) of one kind: its generation, and for colours its range. */
    private data class Drawn(val gen: Int, val range: ElevationRange? = null)

    /** The layers shown, and while new ones replace them (`swap`), the ones coming (shown once they have their tiles). */
    private val shown = HashMap<TileKind, Drawn>()
    private val incoming = HashMap<TileKind, Drawn>()

    /** The kinds whose layers are hidden (contours or colours not shown): they ask for no tiles. */
    private val hidden = HashSet<TileKind>()

    /** Kinds to replace at the next `swap`. */
    private val wanted = HashSet<TileKind>()
    private var swapScheduled = false
    private var lastSwap = 0.0

    /** Between the camera starting to move and coming to rest: new layers wait, leaving MapLibre's loading to what
     *  comes into view. */
    private var moving = false
    private var movingSince = 0.0

    /** A new layer owed for a replacement asked for while the map moved or was off screen. */
    private var swapWaiting = false
    private var stopCount = 0
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())

    init {
        // Heights that arrive may change the colours' range. (Imagery and contour lines that arrive need nothing here:
        // MapLibre asks again for a tile when its max-age is up, and shows the old one until the new one is drawn.)
        drawnMap.onArrival = { _, content ->
            if (content == SatelliteRenderer.Content.colours) updateColourRange()
        }
    }

    /** Adds the imagery, then the colours and the contours (shown or hidden as the terrain mode says), each a fresh
     *  generation, in that order: `MapStyle.builder` puts them above the background and under every overlay. */
    fun addTo(builder: Style.Builder) {
        // A new style: the sources of an earlier one went with it.
        for (d in shown.values + incoming.values) TileLayers.remove(d.gen)
        shown.clear()
        incoming.clear()
        wanted.clear()
        hidden.clear()
        val mode = terrain()
        for (kind in listOf(TileKind.imagery, TileKind.colours, TileKind.contours)) {
            if (!shows(kind, mode)) hidden.add(kind)
            val d = Drawn(TileUrls.nextGeneration(), if (kind == TileKind.colours) colourRange else null)
            TileLayers.add(TileLayer(d.gen, kind, drawnMap))
            builder.withSource(source(kind, d.gen, d.range)).withLayer(layer(kind, d.gen, visible = kind !in hidden))
            shown[kind] = d
        }
    }

    /** The map view is going (`MapController.onDestroy`, before `MapView.onDestroy`): no more timers (the map stopped,
     *  the colours' re-check, new layers and their retirement), arrivals or style calls, and its sources are no longer
     *  answered. */
    fun destroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        drawnMap.onArrival = null
        onKey = null
        for (d in shown.values + incoming.values) TileLayers.remove(d.gen)
        shown.clear()
        incoming.clear()
        wanted.clear()
    }

    // The map moving

    fun regionWillChange() {
        if (!moving) movingSince = now()
        moving = true
    }

    fun regionDidChange() {
        if (destroyed) return
        moving = false
        if (swapWaiting) {
            swapWaiting = false
            scheduleSwap()
        }
        mapStopped()
    }

    fun visibleRegionChanged() {
        if (destroyed) return
        levels()?.let { setFocus(it) }
        reportKey()
    }

    private fun setFocus(levels: Levels) {
        val v = visibleWorldRect() ?: return
        val dx = (v.maxX - v.minX) / 4
        val dy = (v.maxY - v.minY) / 4
        drawnMap.setFocus(WorldRect(v.minX - dx, v.minY - dy, v.maxX + dx, v.maxY + dy), levels.imagery, levels.contours)
    }

    /** The map has stopped: once it has been still a moment (so the tiles on screen go first), gets the imagery ready
     *  for the next zoom or pan (`ImageryPrefetch`), and the colours' range for the heights on screen. */
    private fun mapStopped() {
        stopCount += 1
        val n = stopCount
        main.postDelayed({
            if (destroyed || mapView.isDestroyed || n != stopCount || !isVisible()) return@postDelayed
            val levels = levels() ?: return@postDelayed
            setFocus(levels)
            visibleWorldRect()?.let { ImageryPrefetch.mapStopped(it, levels.imagery) }
            updateColourRange()
        }, 400)
    }

    private data class Levels(val imagery: Int, val contours: Int)

    /** The levels MapLibre asks for at this zoom (`TileLevels`, rendering spec §2.4): the imagery's, tiles about 256
     *  pixels across on screen, and the contour and colour tiles', 128 to 256 dp across. Null while the map is narrower
     *  than 100 dp. */
    private fun levels(): Levels? {
        val ratio = mapView.pixelRatio
        if (mapView.width < 100 * ratio) return null
        val zoom = map.cameraPosition.zoom
        return Levels(TileLevels.level(zoom, TileLevels.imageryTileSize(ratio)), TileLevels.level(zoom, TileLevels.TERRAIN_TILE_SIZE))
    }

    /** The world rect around the map on screen (however it is turned). */
    private fun visibleWorldRect(): WorldRect? {
        val v = map.projection.visibleRegion
        val corners = listOf(v.farLeft, v.farRight, v.nearLeft, v.nearRight).map { c ->
            c ?: return null
            GeoPoint(c.latitude, c.longitude)
        }
        val centre = map.cameraPosition.target?.let { GeoPoint(it.latitude, it.longitude) } ?: corners.first()
        return worldRect(corners, centre)
    }

    /** Points across the map to its edges (9 × 9, inset by a pixel), as world points (right however the map is
     *  turned). */
    private fun screenPoints(): List<XY> {
        val w = mapView.width.toFloat() - 2
        val h = mapView.height.toFloat() - 2
        val projection = map.projection
        return (0 until 81).map { i ->
            val c = projection.fromScreenLocation(PointF(1 + w * (i % 9) / 8, 1 + h * (i / 9) / 8)).wrap()
            WebMercator.world(GeoPoint(c.latitude, c.longitude))
        }
    }

    // New layers

    /** Tiles may be had again (the network is back, an area finished): a new layer asks again for those answered with
     *  a stand-in or nothing in focus (imagery), or without their own complete heights (contour lines and colours, as
     *  they are shown). */
    fun reload() {
        if (destroyed) return
        val f = drawnMap.focuses
        val mode = terrain()
        for ((kind, d) in shown) {
            val focus = if (kind == TileKind.imagery) f.imagery else f.contours
            val lacking = TileLayers[d.gen]?.lacking ?: continue
            if (shows(kind, mode) && lacking.keys.any { SatelliteRenderer.inFocus(it, focus) }) wanted.add(kind)
        }
        if (wanted.isNotEmpty()) swap()
    }

    /** Contour lines and colours switched on or off: their layers are shown or hidden (a hidden layer asks for no
     *  tiles, so with neither shown no elevation is downloaded). A hidden colours layer whose range no longer suits is
     *  replaced as it is shown, so the old colours don't show first. */
    fun terrainChanged(mode: TerrainMode) {
        if (destroyed) return
        if (mode.showsColours) updateColourRange() // from heights already in memory
        val style = map.style?.takeIf { it.isFullyLoaded }
        if (style != null) {
            val c = shown[TileKind.colours]
            if (mode.showsColours && TileKind.colours in hidden && c != null && c.range != colourRange &&
                TileKind.colours !in incoming
            ) {
                wanted.remove(TileKind.colours)
                replaceNow(style, TileKind.colours, c)
            }
            for (kind in listOf(TileKind.colours, TileKind.contours)) {
                val show = shows(kind, mode)
                if (show) hidden.remove(kind) else hidden.add(kind)
                for (d in listOfNotNull(shown[kind], incoming[kind])) {
                    style.getLayer(id(kind, d.gen))?.setProperties(visibility(if (show) Property.VISIBLE else Property.NONE))
                }
            }
        }
        reportKey()
    }

    /** Back on screen: a new layer owed meanwhile, and the colours' range for heights that arrived. */
    fun becameVisible() {
        if (destroyed) return
        if (swapWaiting) {
            swapWaiting = false
            scheduleSwap()
        }
        updateColourRange()
    }

    // Elevation colours

    /** Looks again whether the first range for a place can be chosen (`ColourRangeChoice` waits for the rest of the
     *  screen's heights). */
    private val recheckColours = Runnable { updateColourRange() }

    /** Chooses the colours' range from the heights in memory for the screen (keeping the one shown while it still
     *  suits: `ElevationColours.range`); a new one brings a new layer. A range from somewhere else (the colours were
     *  off, or the map was zoomed out, while it moved) is dropped, and the first one for a place waits a moment for the
     *  rest of the screen's heights, so the colours don't step through several. */
    private fun updateColourRange() {
        if (destroyed || mapView.isDestroyed) return
        if (!terrain().showsColours || !isVisible()) return
        val levels = levels() ?: return
        if (levels.contours < ElevationColours.minTileZoom) return
        val visible = visibleWorldRect() ?: return
        val range = colours.update(
            visible,
            SatelliteRenderer.heightExtremes(visible, levels.contours, screenPoints()),
            now(),
            reportKey = ::reportKey,
            recheck = { main.postDelayed(recheckColours, 1600) },
        ) ?: return
        if (shown[TileKind.colours]?.range != range) {
            wanted.add(TileKind.colours)
            scheduleSwap()
        }
    }

    /** Tells the legends the contour interval at this zoom (as the contour tiles draw them) and the colours' range. */
    private fun reportKey() {
        val report = onKey ?: return
        report(
            key(
                terrain(),
                levels()?.contours,
                contourScale,
                colours.heightsOnScreen,
                colourRange,
            ),
        )
    }

    /** At most about once a second, so replacements asked for one after another come in together; not while the map
     *  moves. */
    private fun scheduleSwap() {
        if (moving && now() - movingSince < 3) { // (in case MapLibre misses a camera-idle)
            swapWaiting = true
            return
        }
        if (swapScheduled) return
        swapScheduled = true
        val wait = max(0.25, 1 - (now() - lastSwap))
        main.postDelayed({
            swapScheduled = false
            swap()
        }, (wait * 1000).toLong())
    }

    /** Asks again for the wanted kinds' tiles with fresh layers: the imagery's goes under the shown one, which covers it
     *  while it loads; translucent ones go over theirs, all but invisible (MapLibre loads a layer's tiles only while it
     *  has some opacity). The shown ones go once the new ones have their tiles on screen. A hidden layer is replaced at
     *  once. */
    private fun swap() {
        if (destroyed || mapView.isDestroyed) return
        if (!isVisible()) {
            swapWaiting = true // on return
            return
        }
        if (incoming.isNotEmpty()) return scheduleSwap() // one at a time
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return scheduleSwap()
        val kinds = wanted.filter { it in shown }
        wanted.clear()
        if (kinds.isEmpty()) return
        lastSwap = now()
        for (kind in kinds) {
            val old = shown.getValue(kind)
            if (kind in hidden) {
                // Nothing on screen to keep.
                replaceNow(style, kind, old)
                continue
            }
            val new = Drawn(TileUrls.nextGeneration(), if (kind == TileKind.colours) colourRange else null)
            TileLayers.add(TileLayer(new.gen, kind, drawnMap))
            style.addSource(source(kind, new.gen, new.range))
            when (kind) {
                TileKind.imagery -> style.addLayerBelow(layer(kind, new.gen), id(kind, old.gen))
                // At 1 % opacity MapLibre loads the layer's tiles (at 0 it would not: a layer with nothing to draw
                // isn't needed); over the shown one, which draws the same, it doesn't show.
                TileKind.colours, TileKind.contours -> style.addLayerAbove(layer(kind, new.gen, opacity = 0.01f), id(kind, old.gen))
            }
            incoming[kind] = new
        }
        if (incoming.isNotEmpty()) retire(ticks = 0)
    }

    /** Replaces a layer at once with a new one (for colours, in the range now), as hidden or shown as it was. */
    private fun replaceNow(style: Style, kind: TileKind, old: Drawn) {
        val new = Drawn(TileUrls.nextGeneration(), if (kind == TileKind.colours) colourRange else null)
        TileLayers.add(TileLayer(new.gen, kind, drawnMap))
        style.addSource(source(kind, new.gen, new.range))
        style.addLayerAbove(layer(kind, new.gen, visible = kind !in hidden), id(kind, old.gen))
        remove(style, kind, old)
        shown[kind] = new
    }

    /** Shows the new layers once they have their tiles under the screen, and removes the old ones. If they haven't in
     *  2 s on screen, the new ones go instead, the old ones stay, and the replacement is tried again. */
    private fun retire(ticks: Int) {
        main.postDelayed({
            if (destroyed || mapView.isDestroyed || incoming.isEmpty()) return@postDelayed
            val visible = isVisible()
            val levels = if (visible) levels() else null
            val points = if (levels != null) screenPoints() else emptyList()
            val ready = levels != null && incoming.all { (kind, d) -> hasTiles(kind, d.gen, points, levels) }
            if (ready) {
                // A moment more for MapLibre to show the last tiles answered; then all in one go, so in one frame.
                val coming = HashMap(incoming)
                main.postDelayed({
                    if (destroyed || mapView.isDestroyed || incoming != coming) return@postDelayed
                    val style = map.style?.takeIf { it.isFullyLoaded } ?: return@postDelayed
                    for ((kind, new) in coming) {
                        if (kind != TileKind.imagery) style.getLayer(id(kind, new.gen))?.setProperties(rasterOpacity(1f))
                        shown[kind]?.let { remove(style, kind, it) }
                        shown[kind] = new
                    }
                    incoming.clear()
                }, 400)
            } else if (visible && ticks >= 20) {
                val style = map.style?.takeIf { it.isFullyLoaded }
                for ((kind, new) in incoming) {
                    if (style != null) remove(style, kind, new) else TileLayers.remove(new.gen)
                    wanted.add(kind)
                }
                incoming.clear()
                scheduleSwap()
            } else {
                retire(if (visible) ticks + 1 else ticks)
            }
        }, 100)
    }

    /** Whether MapLibre has had every tile of a layer under these points answered at the layer's level (or the level
     *  is below the source's, or the layer is hidden: then it asks for none). */
    private fun hasTiles(kind: TileKind, gen: Int, points: List<XY>, levels: Levels): Boolean {
        if (kind in hidden) return true
        val layer = TileLayers[gen] ?: return false
        val z = if (kind == TileKind.imagery) levels.imagery else levels.contours
        if (z < minZoom(kind)) return true
        return tilesUnder(points, z).all { it in layer.answered }
    }

    private fun remove(style: Style, kind: TileKind, d: Drawn) {
        style.removeLayer(id(kind, d.gen))
        style.removeSource(id(kind, d.gen))
        TileLayers.remove(d.gen)
    }

    // The style's sources and layers

    private fun id(kind: TileKind, gen: Int) = "${kind.id}-$gen"

    private fun minZoom(kind: TileKind): Int = when (kind) {
        TileKind.imagery -> 0
        TileKind.colours -> ElevationColours.minTileZoom
        TileKind.contours -> TileLevels.contourMinZoom(contourScale)
    }

    private fun source(kind: TileKind, gen: Int, range: ElevationRange? = null): RasterSource {
        val ratio = mapView.pixelRatio
        val url = when (kind) {
            TileKind.imagery -> TileUrls.imagery(gen)
            TileKind.contours -> TileUrls.contours(gen, contourScale)
            TileKind.colours -> TileUrls.colours(gen, range)
        }
        val tileSize = if (kind == TileKind.imagery) TileLevels.imageryTileSize(ratio) else TileLevels.TERRAIN_TILE_SIZE
        val tiles = TileSet("2.1.0", url).apply {
            setMinZoom(minZoom(kind).toFloat())
            setMaxZoom(TileSources.imageryMaxZoom.toFloat())
        }
        return RasterSource(id(kind, gen), tiles, tileSize).apply {
            setVolatile(true) // never in MapLibre's own SQLite cache: TileRepository caches
            // Imagery: the tile three levels up first (iOS's urgent coarse tile), and coarser tiles up to 10 levels up
            // shown meanwhile. Contours and colours: their parent only.
            setPrefetchZoomDelta(if (kind == TileKind.imagery) 3 else 0)
            setMaxOverscaleFactorForParentTiles(if (kind == TileKind.imagery) 10 else 1)
        }
    }

    /** A raster layer, at full opacity but while it comes in: the colours' half transparency is in their pixels. */
    private fun layer(kind: TileKind, gen: Int, visible: Boolean = true, opacity: Float = 1f): RasterLayer =
        RasterLayer(id(kind, gen), id(kind, gen)).withProperties(
            rasterOpacity(opacity),
            rasterFadeDuration(0f),
            rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            visibility(if (visible) Property.VISIBLE else Property.NONE),
        ).apply { rasterOpacityTransition = TransitionOptions(0, 0, false) }

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    companion object {
        /** Whether a mode shows a kind's layer (the imagery always). */
        internal fun shows(kind: TileKind, mode: TerrainMode): Boolean = when (kind) {
            TileKind.imagery -> true
            TileKind.contours -> mode.showsContours
            TileKind.colours -> mode.showsColours
        }

        /** What the legends show: the contour interval at the contour level (as `ContourRenderer` draws them at the
         *  scale `s`) and the colours' range while there are heights on screen, as the mode shows them; nothing without
         *  levels (the map narrower than 100 dp). */
        internal fun key(mode: TerrainMode, contourLevel: Int?, s: Double, heightsOnScreen: Boolean, range: ElevationRange?): TerrainKey {
            val cz = contourLevel ?: return TerrainKey()
            var contours: ContourInterval? = null
            if (mode.showsContours && cz >= ContourPolicy.minTileZoom) {
                ContourPolicy.intervals(ContourPolicy.pointZoom(cz, s))?.let { contours = ContourInterval(it.step, it.index) }
            }
            val colours = if (mode.showsColours && heightsOnScreen && cz >= ElevationColours.minTileZoom) range else null
            return TerrainKey(contours, colours)
        }

        /** The world rect around points on the map (its corners), with x unwrapped around the centre's, so it is one
         *  rect across the date line. */
        internal fun worldRect(corners: List<GeoPoint>, centre: GeoPoint): WorldRect {
            val c = WebMercator.world(centre).x
            val ps = corners.map { WebMercator.world(it) }.map { XY(it.x + (c - it.x).rounded(), it.y) }
            return WorldRect(ps.minOf { it.x }, ps.minOf { it.y }, ps.maxOf { it.x }, ps.maxOf { it.y })
        }

        /** The tiles of a level under world points (clamped to the world). */
        internal fun tilesUnder(points: List<XY>, z: Int): Set<TileID> {
            val n = 1 shl z
            return points.mapTo(HashSet()) { p ->
                TileID(z = z, x = floor(p.x * n).toInt().coerceIn(0, n - 1), y = floor(p.y * n).toInt().coerceIn(0, n - 1))
            }
        }
    }
}

/** The colours' range, chosen from the heights on screen as Swift's `DrawnImagery.updateColourRange` does, apart from
 *  the map so it can be tested: the range; where it was last chosen or kept (one from somewhere else isn't kept); since
 *  when the first range for a place has waited for the rest of the screen's heights; and whether the screen has land
 *  with heights in memory (else nothing is coloured, and the legend goes). */
internal class ColourRangeChoice {
    var range: ElevationRange? = null
        private set
    private var area: WorldRect? = null
    private var waitSince: Double? = null
    var heightsOnScreen = false
        private set

    /** Chooses the range for a map showing `visible`, with these heights on screen (`SatelliteRenderer.heightExtremes`),
     *  at `now` (s): the one shown while they still suit it (`ElevationColours.range`). `reportKey` is called when what
     *  the legend shows changes, and `recheck` when the first range for a place starts waiting (to come back in 1.6 s).
     *  Returns a newly chosen range: a new layer is due for it. */
    fun update(
        visible: WorldRect,
        extremes: SatelliteRenderer.Extremes?,
        now: Double,
        reportKey: () -> Unit,
        recheck: () -> Unit,
    ): ElevationRange? {
        val current = if (area?.let { intersects(it, visible) } == true) range else null
        if (current == null && range != null) {
            range = null
            area = null
            reportKey()
        }
        val e = extremes
        if ((e != null) != heightsOnScreen) {
            heightsOnScreen = e != null
            reportKey()
        }
        if (e == null) return null
        waitSince?.let { if (now - it > 5) waitSince = null } // an earlier wait
        if (current == null && !e.complete) {
            if (waitSince == null) {
                waitSince = now
                recheck()
            }
            if (now - (waitSince ?: now) < 1.5) return null
        }
        waitSince = null
        area = visible
        val chosen = ElevationColours.range(e.low, e.high, current, e.complete)
        if (chosen == range) return null
        Log.d(
            "tiles",
            "colours from ${chosen.low.toInt()} to ${chosen.high.toInt()} m (screen ${e.low.toInt()}–${e.high.toInt()} m, " +
                "complete=${e.complete})",
        )
        range = chosen
        reportKey()
        return chosen
    }

    private fun intersects(a: WorldRect, b: WorldRect): Boolean =
        a.minX < b.maxX && a.maxX > b.minX && a.minY < b.maxY && a.maxY > b.minY
}
