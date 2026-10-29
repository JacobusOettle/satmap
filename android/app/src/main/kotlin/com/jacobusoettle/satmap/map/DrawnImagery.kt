package com.jacobusoettle.satmap.map

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jacobusoettle.satmap.app.TileSources
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

/** The satellite imagery (with the contour lines and elevation colours) of one map view: the style's raster sources and
 *  layers, each a generation of `TileUrls` whose tiles `TileInterceptor` answers. The phone's map has one, and with
 *  Android Auto the car's map another (the tiles in memory are shared). MapLibre replaces a tile in place when a better
 *  answer comes (`Cache-Control`); a whole new layer, put under the shown one and shown once it has its tiles, is only
 *  for tiles to be asked for again at once (`reload`) and (M2) a new colour range.
 *  `MapController` (or `CarMapController`) calls the hooks; this registers no listeners (DESIGN.md D18), and holds it
 *  as long as the map view lives: the sources' registrations (`TileLayers`) hold `drawnMap` only weakly, and go with
 *  it. Main thread only. */
class DrawnImagery(
    private val mapView: MapView,
    private val map: MapLibreMap,
    /** Whether the map is on screen: new layers wait otherwise. */
    private val isVisible: () -> Boolean,
    /** Whether contour lines and elevation colours are drawn. */
    private val terrain: () -> TerrainMode,
) {
    val drawnMap = DrawnMap()

    /** The heights the elevation colours run between, chosen from those on screen (M2: `updateColourRange`). */
    var colourRange: ElevationRange? = null
        private set

    /** Told what the legends should show as it changes (the phone's map has them). */
    var onKey: ((TerrainKey) -> Unit)? = null

    /** A source and its layer (same id) of one kind: its generation, and for colours its range. */
    private data class Drawn(val gen: Int, val range: ElevationRange? = null)

    /** The layers shown, and while new ones replace them (`swap`), the ones coming (shown once they have their tiles). */
    private val shown = HashMap<TileKind, Drawn>()
    private val incoming = HashMap<TileKind, Drawn>()

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
    private val main = Handler(Looper.getMainLooper())

    // (M2: heights arriving update the colour range: `drawnMap.onArrival`. Imagery arriving needs nothing here:
    // MapLibre asks again for a stand-in when its max-age is up.)

    /** Adds the imagery (M2: then the colours, visibility from the mode, and the contours), each a fresh generation,
     *  in that order: `MapStyle.builder` puts them above the background and under every overlay. */
    fun addTo(builder: Style.Builder) {
        // A new style: the sources of an earlier one went with it.
        for (d in shown.values + incoming.values) TileLayers.remove(d.gen)
        shown.clear()
        incoming.clear()
        wanted.clear()
        val gen = TileUrls.nextGeneration()
        TileLayers.add(TileLayer(gen, TileKind.imagery, drawnMap))
        builder.withSource(source(TileKind.imagery, gen)).withLayer(layer(TileKind.imagery, gen))
        shown[TileKind.imagery] = Drawn(gen)
    }

    // The map moving

    fun regionWillChange() {
        if (!moving) movingSince = now()
        moving = true
    }

    fun regionDidChange() {
        moving = false
        if (swapWaiting) {
            swapWaiting = false
            scheduleSwap()
        }
        mapStopped()
    }

    fun visibleRegionChanged() {
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
     *  for the next zoom or pan (`ImageryPrefetch`). */
    private fun mapStopped() {
        stopCount += 1
        val n = stopCount
        main.postDelayed({
            if (mapView.isDestroyed || n != stopCount || !isVisible()) return@postDelayed
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
     *  a stand-in or nothing in focus. */
    fun reload() {
        val f = drawnMap.focuses
        val mode = terrain()
        for ((kind, d) in shown) {
            val drawn = when (kind) {
                TileKind.imagery -> true
                TileKind.contours -> mode.showsContours
                TileKind.colours -> mode.showsColours
            }
            val focus = if (kind == TileKind.imagery) f.imagery else f.contours
            val lacking = TileLayers[d.gen]?.lacking ?: continue
            if (drawn && lacking.keys.any { SatelliteRenderer.inFocus(it, focus) }) wanted.add(kind)
        }
        if (wanted.isNotEmpty()) swap()
    }

    fun terrainChanged(mode: TerrainMode) {
        // M2: the colour range, the colours' and contours' visibility, a stale hidden colours layer replaced, the key.
    }

    /** Back on screen: a new layer owed meanwhile, and (M2) the colours' range for heights that arrived. */
    fun becameVisible() {
        if (swapWaiting) {
            swapWaiting = false
            scheduleSwap()
        }
        updateColourRange()
    }

    // Elevation colours

    private fun updateColourRange() {
        // M2: rendering spec §1.9, exactly as Swift.
    }

    private fun reportKey() {
        // M2: rendering spec §1.10, exactly as Swift.
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
     *  has some opacity). The shown ones go once the new ones have their tiles on screen. */
    private fun swap() {
        if (mapView.isDestroyed) return
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
        val mode = terrain()
        for (kind in kinds) {
            val old = shown.getValue(kind)
            val new = Drawn(TileUrls.nextGeneration(), if (kind == TileKind.colours) colourRange else null)
            TileLayers.add(TileLayer(new.gen, kind, drawnMap))
            style.addSource(source(kind, new.gen, new.range))
            when (kind) {
                TileKind.imagery -> style.addLayerBelow(layer(kind, new.gen), id(kind, old.gen))
                TileKind.colours, TileKind.contours -> {
                    val visible = if (kind == TileKind.colours) mode.showsColours else mode.showsContours
                    if (!visible) {
                        // Nothing on screen to keep: replaced at once.
                        style.addLayerAbove(layer(kind, new.gen, visible = false), id(kind, old.gen))
                        remove(style, kind, old)
                        shown[kind] = new
                        continue
                    }
                    style.addLayerAbove(layer(kind, new.gen, opacity = 0.01f), id(kind, old.gen))
                }
            }
            incoming[kind] = new
        }
        if (incoming.isNotEmpty()) retire(ticks = 0)
    }

    /** Shows the new layers once they have their tiles under the screen, and removes the old ones. If they haven't in
     *  2 s on screen, the new ones go instead, the old ones stay, and the replacement is tried again. */
    private fun retire(ticks: Int) {
        main.postDelayed({
            if (mapView.isDestroyed || incoming.isEmpty()) return@postDelayed
            val visible = isVisible()
            val levels = if (visible) levels() else null
            val points = if (levels != null) screenPoints() else emptyList()
            val ready = levels != null && incoming.all { (kind, d) -> hasTiles(kind, d.gen, points, levels) }
            if (ready) {
                // A moment more for MapLibre to show the last tiles answered; then all in one go, so in one frame.
                val coming = HashMap(incoming)
                main.postDelayed({
                    if (mapView.isDestroyed || incoming != coming) return@postDelayed
                    val style = map.style ?: return@postDelayed
                    for ((kind, new) in coming) {
                        if (kind != TileKind.imagery) style.getLayer(id(kind, new.gen))?.setProperties(rasterOpacity(1f))
                        shown[kind]?.let { remove(style, kind, it) }
                        shown[kind] = new
                    }
                    incoming.clear()
                }, 400)
            } else if (visible && ticks >= 20) {
                val style = map.style
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
     *  is below the source's). */
    private fun hasTiles(kind: TileKind, gen: Int, points: List<XY>, levels: Levels): Boolean {
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
        TileKind.contours -> TileLevels.contourMinZoom(TileLevels.contourScale(mapView.pixelRatio))
    }

    private fun source(kind: TileKind, gen: Int, range: ElevationRange? = null): RasterSource {
        val ratio = mapView.pixelRatio
        val url = when (kind) {
            TileKind.imagery -> TileUrls.imagery(gen)
            TileKind.contours -> TileUrls.contours(gen, TileLevels.contourScale(ratio))
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

    private fun layer(kind: TileKind, gen: Int, visible: Boolean = true, opacity: Float = 1f): RasterLayer =
        RasterLayer(id(kind, gen), id(kind, gen)).withProperties(
            rasterOpacity(opacity),
            rasterFadeDuration(0f),
            rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            visibility(if (visible) Property.VISIBLE else Property.NONE),
        ).apply { rasterOpacityTransition = TransitionOptions(0, 0, false) }

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    companion object {
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
