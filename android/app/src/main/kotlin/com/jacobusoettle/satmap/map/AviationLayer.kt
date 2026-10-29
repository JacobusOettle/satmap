package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.util.DisplayMetrics
import com.jacobusoettle.satmap.core.Aviation
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.AviationPoint
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.IOException
import java.nio.ByteBuffer
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** The aerodromes and navaids shipped with the app (assets/Aviation.tsv, from SatMap/Resources), read once, in the
 *  background (`Dispatchers.IO`), the first time drone mode needs them. `read` gives the file's text (tests read their
 *  own). */
class AviationStore internal constructor(private val read: (Context) -> String = ::readAsset) {
    private val lock = Any()
    private var loaded: AviationIndex? = null
    private val waiting = ArrayList<(AviationIndex) -> Unit>()
    private var loading = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The index if it has been read. */
    val index: AviationIndex? get() = synchronized(lock) { loaded }

    /** Calls back on the main thread with the index, reading it first if needed. */
    fun load(context: Context, done: (AviationIndex) -> Unit) {
        synchronized(lock) {
            val i = loaded
            if (i != null) {
                main.post { done(i) }
                return
            }
            waiting.add(done)
            if (loading) return
            loading = true
        }
        val app = context.applicationContext
        scope.launch {
            val index = AviationIndex(tsv = read(app))
            val callbacks = synchronized(lock) {
                loaded = index
                waiting.toList().also { waiting.clear() }
            }
            main.post { callbacks.forEach { it(index) } }
        }
    }

    companion object {
        val shared = AviationStore()

        /** The file as text; nothing when it can't be read or isn't UTF-8 (Swift's `String(contentsOf:encoding:)`). */
        private fun readAsset(context: Context): String = try {
            val bytes = context.assets.open("Aviation.tsv").use { it.readBytes() }
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: IOException) {
            "" // (CharacterCodingException is one)
        }
    }
}

/** Drone mode on a map (the phone's or the car's): the aerodromes, strips, helipads and navaids in view, more of them as
 *  the map zooms in, each labelled with how far it is from you and from the tapped spot. One point per place in the
 *  `aviation` source, its card an app-drawn image (the style has no glyphs) with the chart symbol at its top, so the
 *  whole card collides as on iOS; the ring and runway of an aerodrome are a separate map-aligned symbol
 *  (`aviation-rings`), shown only under the cards MapLibre shows, which MapLibre turns with the map. `shownRegion` is
 *  the map's region (iOS `mapView.region`), which each map gives (`MapController.region`): around the middle of its
 *  safe area, where its camera centres. Main thread only; the places for a view and the card images are found on
 *  `Dispatchers.Default`. */
class AviationLayer(
    private val map: MapLibreMap,
    private val style: Style,
    private val context: Context,
    private val shownRegion: () -> MapController.Region?,
) {
    /** A place's card on the map: its image, the text drawn in it (so an update that changes nothing keeps it), and its
     *  frame in dp. */
    class Shown(val image: String, val text: String, val width: Float, val height: Float)

    private val density = context.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var drawing: Job? = null
    private var stopped = false

    /** The places on the map, in the order the source has them (between equal priorities, the first is kept when cards
     *  collide), and their cards. */
    private var ids: List<Int> = emptyList()
    private var shown: Map<Int, Shown> = emptyMap()
    private var generation = 0

    /** The view the map stopped at, until its places are on the map. */
    private class Query(val center: GeoPoint, val latDelta: Double, val lonDelta: Double, val km: Double)

    private var region: Query? = null

    /** Card images no longer used, removed a second after (MapLibre lays out a changed source off the main thread, so
     *  the cards before may still be on the screen for a few frames): when, and which. */
    private val retiring = ArrayDeque<Pair<Long, List<String>>>()
    private val sweep = Runnable { sweep() }

    /** Where the distances are from: you, and the tapped spot (or a selected place). */
    var me: GeoPoint? = null
        private set
    var pin: GeoPoint? = null
        private set

    /** The places whose cards MapLibre shows (the others collide): only their runway rings show. */
    var visibleIds: Set<Int> = emptySet()
        private set
    private var visibleCheckedAt = 0L
    private val refresh = Runnable { refreshVisible() }

    var isOn = false
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                AviationStore.shared.load(context) { if (!stopped) regionChanged() }
            } else {
                clear()
            }
        }

    init {
        style.addImages(AviationCard.rings(context.resources.displayMetrics))
    }

    /** The map has stopped (or drone mode came on): the places for this view. */
    fun regionChanged() {
        val index = AviationStore.shared.index
        if (!isOn || stopped || index == null) return
        val w = map.width
        val h = map.height
        if (w / density <= 100) return
        val p = map.projection
        // The screen's width on the ground, measured across its middle (the region of a turned map is wider).
        val a = p.fromScreenLocation(PointF(w / 2 - 50 * density, h / 2))
        val c = p.fromScreenLocation(PointF(w / 2 + 50 * density, h / 2))
        val km = Geo.distance(GeoPoint(a.latitude, a.longitude), GeoPoint(c.latitude, c.longitude)) / 100 * (w / density) / 1000
        // The query box and the centre the places are kept nearest to: the region's.
        val r = shownRegion() ?: return
        region = Query(r.center, r.latDelta, r.lonDelta, km)
        refreshVisible()
        redraw(index)
    }

    /** You moved, or a spot was tapped (or is being moved): the labels' distances, once either has moved at least
     *  20 m (a hundredth of a nautical mile: the labels show tenths). */
    fun distancesChanged(me: GeoPoint?, pin: GeoPoint?) {
        fun moved(a: GeoPoint?, b: GeoPoint?): Boolean = when {
            a == null && b == null -> false
            a != null && b != null -> Geo.distance(a, b) >= 20
            else -> true
        }
        if (!moved(me, this.me) && !moved(pin, this.pin)) return
        this.me = me
        this.pin = pin
        if (!isOn || stopped) return
        AviationStore.shared.index?.let { redraw(it) }
    }

    /** The map moved or turned (every frame). The runways turn with the map by themselves (their symbols are
     *  map-aligned: iOS redrew every label as the map turned); the rings follow the cards MapLibre shows, looked up at
     *  most every 100 ms. */
    fun headingChanged() {
        if (!isOn || stopped || shown.isEmpty()) return
        if (SystemClock.uptimeMillis() - visibleCheckedAt < 100) return
        refreshVisible()
    }

    /** The shown place nearest a spot on the screen (the map view's pixels): within a fingertip (24 dp) of its symbol,
     *  or on its card; only places whose card MapLibre shows (it hides cards that collide). */
    fun place(near: PointF): AviationPoint? {
        val index = AviationStore.shared.index
        if (!isOn || stopped || index == null || shown.isEmpty()) return null
        val r = 24 * density
        val candidates = shownIds(map.queryRenderedFeatures(RectF(near.x - r, near.y - r, near.x + r, near.y + r), MapIds.AVIATION))
        if (candidates.isEmpty()) return null
        val onCard by lazy { shownIds(map.queryRenderedFeatures(near, MapIds.AVIATION)) }
        var best: Pair<Int, Double>? = null
        for (id in candidates) {
            val place = index.points.getOrNull(id) ?: continue
            val p = map.projection.toScreenLocation(LatLng(place.point.lat, place.point.lon))
            var d = hypot((p.x - near.x).toDouble(), (p.y - near.y).toDouble()) / density
            if (d >= 24 && id in onCard) d = 23.0 // on its label
            if (d < 24 && d < (best?.second ?: Double.POSITIVE_INFINITY)) best = id to d
        }
        return best?.let { index.points[it.first] }
    }

    /** The map is going: nothing more is drawn or changed. */
    fun stop() {
        stopped = true
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    /** The cards for the places of the view the map stopped at (or, without a new view, those on the map), with the
     *  distances from you and the pin. Only cards whose text changed are drawn again. */
    private fun redraw(index: AviationIndex) {
        drawing?.cancel()
        val query = region
        val order = ids
        val before = shown
        val me = me
        val pin = pin
        val metrics = context.resources.displayMetrics
        val gen = ++generation
        drawing = scope.launch {
            val update = withContext(Dispatchers.Default) {
                val wanted = query?.let { select(index, it.center, it.latDelta, it.lonDelta, it.km) } ?: order
                val cards = HashMap<Int, Shown>()
                val images = HashMap<String, Bitmap>()
                for (id in wanted) {
                    ensureActive()
                    val place = index.points[id]
                    val text = AviationCard.text(place, me, pin, metrics.density)
                    val key = text.toString()
                    val old = before[id]
                    if (old != null && old.text == key) {
                        cards[id] = old
                        continue
                    }
                    val card = AviationCard.draw(place, text, metrics)
                    val image = "av/$id/$gen"
                    images[image] = card.bitmap
                    cards[id] = Shown(image, key, card.width, card.height)
                }
                Triple(wanted, cards, images)
            }
            val (wanted, cards, images) = update
            show(index, query, wanted, cards, images)
        }
    }

    /** The new cards on the map: their images added, then the source, then the images no longer used retired. */
    private fun show(index: AviationIndex, query: Query?, wanted: List<Int>, cards: Map<Int, Shown>, images: HashMap<String, Bitmap>) {
        if (!isOn || stopped) return
        if (region === query) region = null
        if (images.isNotEmpty()) style.addImages(images)
        style.getSourceAs<GeoJsonSource>(MapIds.AVIATION)?.setGeoJson(
            FeatureCollection.fromFeatures(wanted.map { feature(it, index.points[it], cards.getValue(it)) }),
        )
        val used = cards.values.mapTo(HashSet()) { it.image }
        retire(shown.values.map { it.image }.filter { it !in used })
        ids = wanted
        shown = cards
        // The cards MapLibre shows, once they have faded in or out.
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 350)
    }

    /** Drone mode went off: no places, and their images go. */
    private fun clear() {
        drawing?.cancel()
        drawing = null
        region = null
        handler.removeCallbacks(refresh)
        if (stopped) return
        style.getSourceAs<GeoJsonSource>(MapIds.AVIATION)?.setGeoJson(MapStyle.empty())
        retire(shown.values.map { it.image })
        ids = emptyList()
        shown = emptyMap()
        setVisible(emptySet())
    }

    private fun retire(images: List<String>) {
        if (images.isEmpty()) return
        val idle = retiring.isEmpty()
        retiring.addLast(SystemClock.uptimeMillis() to images)
        if (idle) handler.postDelayed(sweep, RETIRE_MILLIS) // (else a sweep is waiting, and goes on to these)
    }

    private fun sweep() {
        if (stopped) return
        val now = SystemClock.uptimeMillis()
        while (retiring.isNotEmpty() && now - retiring.first().first >= RETIRE_MILLIS) {
            for (image in retiring.removeFirst().second) style.removeImage(image)
        }
        retiring.firstOrNull()?.let { handler.postDelayed(sweep, it.first + RETIRE_MILLIS - now) }
    }

    /** Asks MapLibre which cards it shows (on the whole map view), for the runway rings. */
    private fun refreshVisible() {
        visibleCheckedAt = SystemClock.uptimeMillis()
        if (stopped) return
        if (!isOn || shown.isEmpty()) {
            setVisible(emptySet())
            return
        }
        setVisible(shownIds(map.queryRenderedFeatures(RectF(0f, 0f, map.width, map.height), MapIds.AVIATION)).toSet())
    }

    private fun setVisible(ids: Set<Int>) {
        if (ids == visibleIds) return
        visibleIds = ids
        style.getLayerAs<SymbolLayer>(MapIds.AVIATION_RINGS)?.setFilter(MapStyle.aviationRingsFilter(ids.sorted()))
    }

    /** The places of features MapLibre found that are this layer's. */
    private fun shownIds(features: List<Feature>): List<Int> =
        features.mapNotNull { f -> f.getNumberProperty("id")?.toInt()?.takeIf { it in shown } }.distinct()

    companion object {
        /** Places at most on the map at once (the nearest the middle, most important first). */
        const val maxShown = 140

        /** How far beyond the view places are looked for, as a share of its span on each side. */
        const val pad = 0.15

        /** How long a card image no longer used stays (ms). */
        const val RETIRE_MILLIS = 1000L

        /** The `kind` of the source's features (as the KML labels' "name" and "point"): for the names on the map
         *  that accessibility services read. */
        const val KIND = "aviation"

        /** The places to show for a view centred on `center` spanning `latDelta` × `lonDelta` degrees, `km` across the
         *  screen: those shown from that width down (`kind.shownWithinKm`), within the view and 15 % more on each
         *  side (latitudes kept on the globe, longitudes not), at most 140 (by priority, then by how near the middle). */
        fun select(index: AviationIndex, center: GeoPoint, latDelta: Double, lonDelta: Double, km: Double): List<Int> {
            val bounds = bounds(center, latDelta, lonDelta)
            var ids = index.points(bounds) { it.kind.shownWithinKm >= km }
            if (ids.size > maxShown) {
                ids = ids.sortedWith { a, b ->
                    val pa = index.points[a]
                    val pb = index.points[b]
                    if (pa.kind.priority != pb.kind.priority) {
                        pb.kind.priority.compareTo(pa.kind.priority)
                    } else {
                        Geo.distance(pa.point, center).compareTo(Geo.distance(pb.point, center))
                    }
                }.take(maxShown)
            }
            return ids
        }

        /** The view's region with 15 % more on each side. */
        fun bounds(center: GeoPoint, latDelta: Double, lonDelta: Double): GeoBounds = GeoBounds(
            minLat = max(-90.0, center.lat - latDelta * (0.5 + pad)),
            minLon = center.lon - lonDelta * (0.5 + pad),
            maxLat = min(90.0, center.lat + latDelta * (0.5 + pad)),
            maxLon = center.lon + lonDelta * (0.5 + pad),
        )

        /** A place's point in the `aviation` source: `id` (its index in `AviationIndex.points`), its card image `img`,
         *  `key` (1000 − priority: MapLibre places lower keys first), for aerodromes with a runway `ring` and `rwy` (the
         *  runway's heading, 45° when unknown, as iOS draws it), and for accessibility `kind`, `name` ("Airstrip FASH
         *  Stellenbosch", iOS's accessibility label) and the card's frame in dp (`w`, `h`). */
        fun feature(id: Int, place: AviationPoint, card: Shown): Feature =
            Feature.fromGeometry(Point.fromLngLat(place.point.lon, place.point.lat)).apply {
                addNumberProperty("id", id)
                addStringProperty("img", card.image)
                addNumberProperty("key", 1000 - place.kind.priority)
                AviationCard.ring(place.kind)?.let { ring ->
                    addStringProperty("ring", ring)
                    addNumberProperty("rwy", place.runwayHeading ?: 45)
                }
                addStringProperty("kind", KIND)
                addStringProperty("name", "${place.kind.title} ${place.label}")
                addNumberProperty("w", card.width)
                addNumberProperty("h", card.height)
            }
    }
}

/** A place in drone mode as iOS's `AviationView` draws it, as bitmaps: its chart symbol on the spot, and under it a
 *  card with its code and name, how far it is from you (cyan) and from the tapped spot (amber), in nautical miles. The
 *  ring and runway of an aerodrome are drawn apart (`rings`), so the map can turn them. */
object AviationCard {
    const val symbolSize = 22f

    /** The ring images, drawn north up: the map turns them to the runway's heading. */
    const val RING_8 = "av-ring-8"
    const val RING_6_5 = "av-ring-6.5"

    /** The card's colours: its background (white 5 % at 74 %), border (white 18 %), the kind's title (white 75 %) and
     *  the name after a code (white 85 %). */
    const val background = 0xBD0D0D0D.toInt()
    const val border = 0x2EFFFFFF
    const val kindColour = 0xBFFFFFFF.toInt()
    const val nameColour = 0xD9FFFFFF.toInt()

    /** The ring a kind's symbol has, with the runway through it: aerodromes but helipads (small airports a smaller
     *  one). */
    fun ring(kind: AviationPoint.Kind): String? = when (kind) {
        AviationPoint.Kind.largeAirport, AviationPoint.Kind.mediumAirport, AviationPoint.Kind.seaplaneBase -> RING_8
        AviationPoint.Kind.smallAirport -> RING_6_5
        else -> null
    }

    /** "FASZ Skukuza" (or "HGV VOR Hoedspruit"), then the distance from you, then from the tapped spot. Sizes are
     *  drawn at `density` pixels per dp. */
    fun text(place: AviationPoint, me: GeoPoint?, pin: GeoPoint?, density: Float = 1f): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        fun append(s: String, vararg spans: Any) {
            val start = out.length
            out.append(s)
            for (span in spans) out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun bold() = Font(13f, MapImages.BOLD, digits = false, density)
        fun small() = Font(11.5f, MapImages.SEMIBOLD, digits = false, density)
        var name = place.shortName
        if (characterCount(name) > 24) name = characterPrefix(name, 23) + "…"
        if (place.code.isNotEmpty()) {
            append(place.code, bold(), ForegroundColorSpan(Color.WHITE))
            if (!place.kind.isAerodrome) append(" " + place.kind.title, small(), ForegroundColorSpan(kindColour))
            append("  " + name, small(), ForegroundColorSpan(nameColour))
        } else {
            append(name, small(), ForegroundColorSpan(Color.WHITE))
        }
        fun line(from: GeoPoint, symbol: Icon.Symbol, colour: Int) {
            val d = Geo.distance(from, place.point)
            if (d < 0.05 * Aviation.metresPerNauticalMile) return // the place itself
            append("\n", LineBreak())
            append(OBJECT, Icon(symbol, colour, density))
            append(" ")
            append(Aviation.nauticalMiles(d), Font(13f, MapImages.BOLD, digits = true, density), ForegroundColorSpan(colour))
        }
        if (me != null) line(me, Icon.Symbol.location, MapColours.aviationYou)
        if (pin != null) line(pin, Icon.Symbol.mappin, MapColours.aviationPin)
        return out
    }

    /** A card's image and its frame in dp: max(22, card width) × (22 + 3 + card height), the symbol centred at the top
     *  (its middle goes on the place) and the card under it. */
    class Image(val bitmap: Bitmap, val width: Float, val height: Float)

    /** Draws a place's card with its `text` at the map's density: the text at most 190 dp wide (wrapped by word, as a
     *  `UILabel`), padded 6 × 3 dp, on a dark card with 8 dp corners and a faint border; above it, in the symbol's
     *  22 dp square, the chart symbol of kinds without a runway, or a seaplane base's "~". */
    fun draw(place: AviationPoint, text: CharSequence, m: DisplayMetrics): Image {
        val d = m.density
        // The text without a size of its own (the space after an icon): a UILabel's own font, the 17 point system font.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 17 * d
            typeface = Typeface.DEFAULT
            color = Color.WHITE
        }
        val wrapped = layout(text, paint, ceil(190 * d).toInt())
        var textWidth = 0f
        for (i in 0 until wrapped.lineCount) textWidth = max(textWidth, wrapped.getLineMax(i))
        // Laid out again as wide as it is, so a right-to-left name stays on the card (the label is that wide).
        val laid = layout(text, paint, max(1, ceil(textWidth).toInt()))
        val s = symbolSize
        val cardWidth = ceil(textWidth / d) + 12
        val cardHeight = ceil(laid.height / d) + 6
        val w = max(s, cardWidth)
        val h = s + 3 + cardHeight
        val bitmap = image(m, w, h)
        val c = Canvas(bitmap)
        c.save()
        c.translate((w - s) / 2 * d, 0f)
        drawSymbol(c, place, d)
        c.restore()
        val left = (w - cardWidth) / 2 * d
        val top = (s + 3) * d
        val card = RectF(left, top, left + cardWidth * d, top + cardHeight * d)
        c.drawRoundRect(card, 8 * d, 8 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        // A layer's border is drawn inside its bounds.
        val half = 0.25f * d
        c.drawRoundRect(
            RectF(card.left + half, card.top + half, card.right - half, card.bottom - half), 8 * d - half, 8 * d - half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f * d
                color = border
            },
        )
        c.translate(left + 6 * d, top + 3 * d)
        laid.draw(c)
        return Image(bitmap, w, h)
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /** The chart symbol in its 22 dp square, for kinds without a runway: a ring with "H" for a helipad, a hexagon with a
     *  dot for a VOR or TACAN, a square for a DME, a ring of dots for an NDB; a seaplane base's "~" (its ring is apart).
     *  White 2 dp lines with round caps over a halo: the same path in black 65 % 4.5 dp wide, closed shapes filled
     *  black 35 %. */
    private fun drawSymbol(c: Canvas, place: AviationPoint, d: Float) {
        val s = symbolSize
        val cx = s / 2
        val cy = s / 2
        val path = Path()
        var text = ""
        when (place.kind) {
            AviationPoint.Kind.largeAirport, AviationPoint.Kind.mediumAirport, AviationPoint.Kind.smallAirport -> {}
            AviationPoint.Kind.seaplaneBase -> text = "~"
            AviationPoint.Kind.heliport -> {
                path.addOval(RectF(cx - 8, cy - 8, cx + 8, cy + 8), Path.Direction.CW)
                text = "H"
            }
            AviationPoint.Kind.vor, AviationPoint.Kind.tacan -> {
                // A hexagon with a dot.
                for (k in 0 until 6) {
                    val a = k * PI / 3
                    val x = (cx + cos(a) * 8).toFloat()
                    val y = (cy + sin(a) * 8).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                path.addOval(RectF(cx - 1.5f, cy - 1.5f, cx + 1.5f, cy + 1.5f), Path.Direction.CW)
            }
            AviationPoint.Kind.dme -> path.addRect(RectF(cx - 6.5f, cy - 6.5f, cx + 6.5f, cy + 6.5f), Path.Direction.CW)
            AviationPoint.Kind.ndb -> {
                // A ring of dots.
                for (k in 0 until 10) {
                    val a = k * PI / 5
                    val x = (cx + cos(a) * 7.5).toFloat()
                    val y = (cy + sin(a) * 7.5).toFloat()
                    path.addOval(RectF(x - 1.2f, y - 1.2f, x + 1.2f, y + 1.2f), Path.Direction.CW)
                }
                path.addOval(RectF(cx - 2, cy - 2, cx + 2, cy + 2), Path.Direction.CW)
            }
        }
        if (!path.isEmpty) drawHaloed(c, path, d)
        if (text.isEmpty()) return
        // 12 point heavy white letters, centred, the line's top 4 points down.
        val paint = MapImages.textPaint(12 * d, MapImages.HEAVY).apply { textAlign = Paint.Align.CENTER }
        c.drawText(text, cx * d, 4 * d - paint.fontMetrics.ascent, paint)
    }

    /** A symbol path (dp) drawn at `d` pixels per dp: the halo's fill and line, then the white line. */
    private fun drawHaloed(c: Canvas, path: Path, d: Float) {
        val scaled = Path(path).apply { transform(Matrix().apply { setScale(d, d) }) }
        c.drawPath(scaled, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 })
        c.drawPath(
            scaled,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xA6000000.toInt()
                strokeWidth = 4.5f * d
                strokeMiter = 10f
            },
        )
        c.drawPath(
            scaled,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = 2 * d
                strokeCap = Paint.Cap.ROUND
                strokeMiter = 10f
            },
        )
    }

    /** The ring images (radius 8, and 6.5 for small airports) with the runway through them, north up, haloed as the
     *  other symbols. 24 dp square, 1 dp wider than iOS's symbol on each side, so the runway's round ends aren't cut
     *  off; centred on the place. */
    fun rings(m: DisplayMetrics): HashMap<String, Bitmap> = hashMapOf(RING_8 to ringImage(m, 8f), RING_6_5 to ringImage(m, 6.5f))

    private fun ringImage(m: DisplayMetrics, r: Float): Bitmap {
        val bitmap = image(m, 24f, 24f)
        val c = 12f
        val l = r + 3
        val path = Path().apply {
            addOval(RectF(c - r, c - r, c + r, c + r), Path.Direction.CW)
            moveTo(c, c + l)
            lineTo(c, c - l)
        }
        drawHaloed(Canvas(bitmap), path, m.density)
        return bitmap
    }

    /** A transparent bitmap `w` × `h` dp at the map's density (MapLibre shows it `w` × `h` dp). */
    private fun image(m: DisplayMetrics, w: Float, h: Float): Bitmap =
        Bitmap.createBitmap(ceil(w * m.density).toInt(), ceil(h * m.density).toInt(), Bitmap.Config.ARGB_8888).apply {
            density = m.densityDpi
        }

    /** The object replacement character, as iOS's text attachment. */
    private const val OBJECT = "￼"

    /** A run's font: the system font at `size` dp and a weight (iOS names), with tabular figures for `digits`. */
    class Font(val size: Float, val weight: Int, val digits: Boolean, private val density: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        override fun updateDrawState(tp: TextPaint) = apply(tp)

        private fun apply(tp: TextPaint) {
            tp.textSize = size * density
            tp.typeface = MapImages.typeface(weight)
            tp.fontFeatureSettings = if (digits) "tnum" else null
        }
    }

    /** A line break takes no height of its own: TextKit gives the card's first line only its text's height, where
     *  StaticLayout would count the break in the label's 17 point font too. */
    class LineBreak : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) {
            tp.textSize = 1f
        }

        override fun updateDrawState(tp: TextPaint) {
            tp.textSize = 1f
        }
    }

    /** The icon before a distance, SF Symbols' `location.fill` (you) or `mappin` (the pin), filled in `colour`. iOS
     *  draws a symbol in text at the size of the font around it, here the label's own 17 point font (the attachment
     *  has none), not the 10 points it was made at: the shapes, sizes and places below are those of the iPhone's
     *  screenshot (docs/screenshots/25-drone-mode.jpg), in dp from the start of the icon and the baseline, and the
     *  advances put the distances where the iPhone has them. The line takes the 17 point font's height, as on iOS. */
    class Icon(val symbol: Symbol, val colour: Int, private val density: Float) : ReplacementSpan() {
        enum class Symbol(val advance: Float) {
            /** An arrow pointing up and to the right, 15.8 dp across, its tips rounded. */
            location(20.2f),

            /** A ball of radius 3.1 with a hole in its upper left, on a needle narrowing from 1.7 to 1.1 dp: 17.4 dp
             *  tall, from 14.9 above the baseline to 2.5 below. */
            mappin(17.0f),
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            // (StaticLayout passes metrics left from another run.)
            if (fm != null) paint.getFontMetricsInt(fm)
            return ceil(symbol.advance * density).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
            val d = density
            fun px(u: Float) = x + u * d
            fun py(v: Float) = y + v * d
            when (symbol) {
                Symbol.location -> canvas.drawPath(rounded(ARROW, ARROW_ROUNDING, x, y.toFloat(), d), fill)
                Symbol.mappin -> {
                    val path = Path().apply {
                        addCircle(px(8.1f), py(-11.75f), 3.1f * d, Path.Direction.CW)
                        // The hole turns the other way, so it stays empty.
                        addCircle(px(7.2f), py(-12.65f), 1f * d, Path.Direction.CCW)
                        // The needle, from inside the ball (clear of the hole).
                        moveTo(px(7.25f), py(-11.25f))
                        lineTo(px(8.95f), py(-11.25f))
                        lineTo(px(8.65f), py(2.5f))
                        lineTo(px(7.55f), py(2.5f))
                        close()
                    }
                    canvas.drawPath(path, fill)
                }
            }
        }

        private companion object {
            /** The arrow's corners, x and y in turn: its tip, the wing on the left, the notch at its back and the wing
             *  below; and the radius each is rounded to (the notch is sharp). */
            val ARROW = floatArrayOf(17.8f, -14.7f, -0.6f, -6.4f, 8.4f, -5.3f, 9.5f, 3.7f)
            val ARROW_ROUNDING = floatArrayOf(0.8f, 0.7f, 0f, 0.7f)

            /** A polygon through `corners` (dp, x and y in turn) from (`x`, `y`) at `d` pixels per dp, each corner
             *  rounded to an arc of its radius, which meets both sides. */
            fun rounded(corners: FloatArray, radii: FloatArray, x: Float, y: Float, d: Float): Path {
                val path = Path()
                val n = radii.size
                for (i in 0 until n) {
                    val cx = corners[2 * i]
                    val cy = corners[2 * i + 1]
                    val r = radii[i]
                    if (r <= 0f) {
                        if (path.isEmpty) path.moveTo(x + cx * d, y + cy * d) else path.lineTo(x + cx * d, y + cy * d)
                        continue
                    }
                    // Unit vectors along the two sides, towards the corners before and after.
                    val p = (i + n - 1) % n
                    val q = (i + 1) % n
                    val ul = hypot(corners[2 * p] - cx, corners[2 * p + 1] - cy)
                    val vl = hypot(corners[2 * q] - cx, corners[2 * q + 1] - cy)
                    val ux = (corners[2 * p] - cx) / ul
                    val uy = (corners[2 * p + 1] - cy) / ul
                    val vx = (corners[2 * q] - cx) / vl
                    val vy = (corners[2 * q + 1] - cy) / vl
                    val half = acos((ux * vx + uy * vy).coerceIn(-1f, 1f)) / 2
                    // The arc's centre is on the corner's bisector; it meets the sides r / tan(half) from the corner.
                    val bl = hypot(ux + vx, uy + vy)
                    val ox = cx + (ux + vx) / bl * r / sin(half)
                    val oy = cy + (uy + vy) / bl * r / sin(half)
                    val t = r / tan(half)
                    val from = atan2(cy + uy * t - oy, cx + ux * t - ox)
                    var sweep = atan2(cy + vy * t - oy, cx + vx * t - ox) - from
                    if (sweep > PI) sweep -= (2 * PI).toFloat()
                    if (sweep < -PI) sweep += (2 * PI).toFloat()
                    val oval = RectF(x + (ox - r) * d, y + (oy - r) * d, x + (ox + r) * d, y + (oy + r) * d)
                    path.arcTo(oval, Math.toDegrees(from.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false)
                }
                path.close()
                return path
            }
        }
    }

    /** Swift's `String.count`: characters as the user sees them, not UTF-16 units. */
    private fun characterCount(text: String): Int {
        val b = BreakIterator.getCharacterInstance()
        b.setText(text)
        var n = 0
        while (b.next() != BreakIterator.DONE) n++
        return n
    }

    /** Swift's `prefix(n)` of a string: its first `n` characters. */
    private fun characterPrefix(text: String, n: Int): String {
        val b = BreakIterator.getCharacterInstance()
        b.setText(text)
        var end = 0
        for (i in 0 until n) {
            val next = b.next()
            if (next == BreakIterator.DONE) break
            end = next
        }
        return text.substring(0, end)
    }
}
