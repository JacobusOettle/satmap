package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupMapContent
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.ui.groups.drawIcon
import com.jacobusoettle.satmap.ui.groups.pinIcon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** The shown group on the phone's map (docs/groups/SPEC.md §9.5): the members' trails (one MultiLineString per member
 *  in `group-trails`, in their colours, 85 % opaque, 3 or 2 dp at MapKit's tile scale), the pins (`group-pins`: a
 *  30 dp disc in the type's colour with its white glyph, and an animal pin's "{animal} · {ago}" label under it), and
 *  the members' dots (`group-members`: 26 dp in their colour with a white ring and initials, grey when stale, with the
 *  heading's triangle in `group-member-arrows`). Every image is app-drawn (the style has no glyphs). Your own dot and
 *  track stay as they are. iOS's `GroupLayer` draws the same with annotations and overlays.
 *
 *  `prepare` builds the features and images off the main thread; `apply` hands them to MapLibre on it (DESIGN.md §2.4
 *  rule 3). Main thread only otherwise. */
class GroupLayers(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** What `prepare` made: the sources' features, the images they name, and the trails' width. */
    class Prepared(
        val content: GroupMapContent?,
        val trails: FeatureCollection,
        val pins: FeatureCollection,
        val members: FeatureCollection,
        val images: Map<String, Bitmap>,
        val widthPt: Double,
    )

    /** What is shown now (for taps, the walkthrough's screen points and accessibility). */
    var shown: GroupMapContent? = null
        private set
    private val images = HashSet<String>()
    private var widthPt = 3.0

    /** Builds what `content` shows; any thread. `known` are the images the style has already. */
    fun prepare(content: GroupMapContent?, known: Set<String> = images.toSet()): Prepared {
        val m = context.resources.displayMetrics
        val needed = HashMap<String, Bitmap>()
        if (content != null) {
            if (ARROW !in known) needed[ARROW] = arrow(m)
            for (p in content.pins) {
                val id = pinImageId(p.knownType)
                if (id !in known && id !in needed) needed[id] = pinImage(m, p.knownType)
                val label = p.label ?: continue
                val lid = labelImageId(label)
                if (lid !in known && lid !in needed) needed[lid] = labelImage(m, label)
            }
            for (mm in content.members) {
                val id = memberImageId(mm.colourHex, mm.initials, mm.isStale)
                if (id !in known && id !in needed) needed[id] = memberImage(m, mm.colourHex, mm.initials, mm.isStale)
            }
        }
        return Prepared(content, trailFeatures(content), pinFeatures(content), memberFeatures(content), needed,
                        content?.trails?.firstOrNull()?.widthPt ?: 3.0)
    }

    /** Shows what `prepare` made (main thread). */
    fun apply(p: Prepared) {
        if (p.images.isNotEmpty()) {
            style.addImages(HashMap(p.images))
            images += p.images.keys
        }
        style.getSourceAs<GeoJsonSource>(MapIds.GROUP_TRAILS)?.setGeoJson(p.trails)
        style.getSourceAs<GeoJsonSource>(MapIds.GROUP_PINS)?.setGeoJson(p.pins)
        style.getSourceAs<GeoJsonSource>(MapIds.GROUP_MEMBERS)?.setGeoJson(p.members)
        if (p.widthPt != widthPt) {
            widthPt = p.widthPt
            style.getLayerAs<LineLayer>(MapIds.GROUP_TRAILS)?.setProperties(lineWidth(MapStyle.zoomScaled(widthPt.toFloat())))
        }
        shown = p.content
        // Labels change every minute ("Kudu · 2 h ago"): the ones no longer shown go.
        val labels = p.content?.pins?.mapNotNull { it.label?.let(::labelImageId) }?.toSet().orEmpty()
        for (id in images.filter { it.startsWith(LABEL_PREFIX) && it !in labels }) {
            style.removeImage(id)
            images -= id
        }
    }

    /** KML files shown after the style was made put their layers against `track` and `measure-markers` (BlockLayers),
     *  which would put them over the group's: the group's go back to just under those anchors (§9.5 layer order). */
    fun restack() {
        val ids = style.layers.map { it.id }
        if (trailsMisplaced(ids)) {
            style.removeLayer(MapIds.GROUP_TRAILS)
            MapStyle.groupLayer(MapIds.GROUP_TRAILS)?.let { style.addLayerBelow(it, MapIds.TRACK) }
            style.getLayerAs<LineLayer>(MapIds.GROUP_TRAILS)?.setProperties(lineWidth(MapStyle.zoomScaled(widthPt.toFloat())))
        }
        if (symbolsMisplaced(ids)) {
            for (id in MapStyle.groupSymbolLayerIds) style.removeLayer(id)
            for (id in MapStyle.groupSymbolLayerIds) MapStyle.groupLayer(id)?.let { style.addLayerBelow(it, MapIds.MEASURE_MARKERS) }
        }
    }

    /** A member dot (first) or a pin within 22 dp of `spot` (the map view's pixels), the nearest. */
    fun hit(spot: PointF): Hit? {
        val c = shown ?: return null
        val d = context.resources.displayMetrics.density
        fun apart(lat: Double, lon: Double): Double {
            val s = map.projection.toScreenLocation(LatLng(lat, lon))
            return hypot((s.x - spot.x).toDouble(), (s.y - spot.y).toDouble()) / d
        }
        c.members.map { it to apart(it.position.lat, it.position.lon) }.filter { it.second <= HIT_DP }.minByOrNull { it.second }
            ?.let { return Hit.Member(c.groupId, it.first.memberId) }
        c.pins.map { it to apart(it.position.lat, it.position.lon) }.filter { it.second <= HIT_DP }.minByOrNull { it.second }
            ?.let { return Hit.Pin(c.groupId, it.first.pinId) }
        return null
    }

    sealed interface Hit {
        data class Member(val groupId: String, val memberId: String) : Hit
        data class Pin(val groupId: String, val pinId: String) : Hit
    }

    /** Each dot's and pin's point in the map view (pixels), with its kind, id and accessibility label. */
    fun points(): List<ItemPoint> {
        val c = shown ?: return emptyList()
        fun at(lat: Double, lon: Double) = map.projection.toScreenLocation(LatLng(lat, lon))
        return c.members.map { ItemPoint("member", it.memberId, it.a11yLabel, at(it.position.lat, it.position.lon), MEMBER_DP / 2) } +
            c.pins.map { ItemPoint("pin", it.pinId, it.a11yLabel, at(it.position.lat, it.position.lon), PIN_DP / 2) }
    }

    /** A dot or pin on the screen: what it is, its id, what TalkBack says, its centre and its radius in dp. */
    data class ItemPoint(val kind: String, val id: String, val label: String, val point: PointF, val radiusDp: Float)

    companion object {
        /** How near a tap must be to a dot or pin, dp (§9.1). */
        const val HIT_DP = 22.0
        const val MEMBER_DP = 26f
        const val PIN_DP = 30f
        const val ARROW = "group-member-arrow"
        private const val LABEL_PREFIX = "group-pin-label/"

        /** `group-trails` isn't right under `track` (`ids` bottom to top, as `Style.layers` lists them). */
        fun trailsMisplaced(ids: List<String>): Boolean {
            val track = ids.indexOf(MapIds.TRACK)
            return track > 0 && ids[track - 1] != MapIds.GROUP_TRAILS
        }

        /** The group's symbol layers aren't right under `measure-markers`, in their order. */
        fun symbolsMisplaced(ids: List<String>): Boolean {
            val markers = ids.indexOf(MapIds.MEASURE_MARKERS)
            val wanted = MapStyle.groupSymbolLayerIds
            return markers >= wanted.size && ids.subList(markers - wanted.size, markers) != wanted
        }

        /** What is out of the §9.5 order in `ids` (bottom to top): every KML fill and line under `group-trails`, which
         *  is under `track`; every KML points layer under the group's symbol layers, which are under
         *  `measure-markers`. Empty when all is well (the walkthrough checks the map with it). */
        fun orderProblems(ids: List<String>): List<String> {
            val problems = ArrayList<String>()
            val trails = ids.indexOf(MapIds.GROUP_TRAILS)
            val track = ids.indexOf(MapIds.TRACK)
            val symbols = MapStyle.groupSymbolLayerIds.map { ids.indexOf(it) }
            val markers = ids.indexOf(MapIds.MEASURE_MARKERS)
            if (trails < 0 || track < 0 || trails > track) problems += "${MapIds.GROUP_TRAILS} isn't under ${MapIds.TRACK}"
            if (symbols.any { it < 0 } || markers < 0 || symbols.any { it > markers }) {
                problems += "the group's symbols aren't under ${MapIds.MEASURE_MARKERS}"
            }
            val firstSymbol = symbols.minOrNull() ?: -1
            for ((i, id) in ids.withIndex()) {
                if (!id.startsWith("kml/")) continue
                if ((id.endsWith("/fill") || id.endsWith("/line")) && i > trails) problems += "$id is over ${MapIds.GROUP_TRAILS}"
                if (id.endsWith("/points") && i > firstSymbol) problems += "$id is over ${MapIds.GROUP_PINS}"
            }
            return problems
        }

        fun pinImageId(type: PinType?) = "group-pin/${type?.rawValue ?: "unknown"}"
        fun labelImageId(label: String) = LABEL_PREFIX + label
        fun memberImageId(colour: String, initials: String, stale: Boolean) = "group-member/$colour/$initials/${if (stale) "stale" else "live"}"

        /** One MultiLineString per member trail, with its `colour` ("#RRGGBB"). */
        fun trailFeatures(c: GroupMapContent?): FeatureCollection = FeatureCollection.fromFeatures(
            c?.trails.orEmpty().filter { t -> t.segments.any { it.size >= 2 } }.map { t ->
                val lines = t.segments.filter { it.size >= 2 }.map { seg -> LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) }) }
                Feature.fromGeometry(MultiLineString.fromLineStrings(lines)).apply {
                    addStringProperty("colour", t.colourHex)
                    addStringProperty("member", t.memberId)
                }
            },
        )

        /** A point per pin: its image and, for an animal pin, its label's image. */
        fun pinFeatures(c: GroupMapContent?): FeatureCollection = FeatureCollection.fromFeatures(
            c?.pins.orEmpty().map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.position.lon, p.position.lat)).apply {
                    addStringProperty("id", p.pinId)
                    addStringProperty("img", pinImageId(p.knownType))
                    p.label?.let { addStringProperty("label", labelImageId(it)) }
                }
            },
        )

        /** A point per member dot: its image, and the heading (degrees from north) when known and not stale. */
        fun memberFeatures(c: GroupMapContent?): FeatureCollection = FeatureCollection.fromFeatures(
            c?.members.orEmpty().map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.position.lon, m.position.lat)).apply {
                    addStringProperty("id", m.memberId)
                    addStringProperty("img", memberImageId(m.colourHex, m.initials, m.isStale))
                    addStringProperty("arrow", ARROW)
                    val h = m.headingDeg
                    if (h != null && !m.isStale) addNumberProperty("heading", ((h % 360) + 360) % 360)
                }
            },
        )

        /** The camera fit (§9.5): the points' bounds, grown about their middle to at least `minMeters` each way. */
        fun fitBounds(points: List<GeoPoint>, minMeters: Double = 300.0): GeoBounds? {
            val b = GeoBounds(points = points) ?: return null
            val c = b.center
            val metresPerLat = 111_320.0
            val metresPerLon = max(1.0, metresPerLat * cos(c.lat * PI / 180))
            val halfLat = max((b.maxLat - b.minLat) / 2, minMeters / 2 / metresPerLat)
            val halfLon = max((b.maxLon - b.minLon) / 2, minMeters / 2 / metresPerLon)
            return GeoBounds(minLat = c.lat - halfLat, minLon = c.lon - halfLon, maxLat = c.lat + halfLat, maxLon = c.lon + halfLon)
        }

        /** How far across `b` is east–west, metres (the fit test). */
        fun widthMeters(b: GeoBounds): Double = Geo.distance(GeoPoint(b.center.lat, b.minLon), GeoPoint(b.center.lat, b.maxLon))

        private fun parse(hex: String): Int = try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            GREY
        }

        private const val GREY = 0xFF8E8E93.toInt()

        /** A member's dot: 26 dp in their colour inside a 2 dp white ring, their initials in white bold 11 sp; stale, a
         *  grey fill and the ring in their colour. */
        fun memberImage(m: DisplayMetrics, colourHex: String, initials: String, stale: Boolean): Bitmap = image(m, MEMBER_DP, MEMBER_DP) { d ->
            val c = MEMBER_DP / 2 * d
            val colour = parse(colourHex)
            drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (stale) colour else Color.WHITE })
            drawCircle(c, c, c - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (stale) GREY else colour })
            val text = MapImages.textPaint(11 * d, MapImages.BOLD).apply { textAlign = Paint.Align.CENTER }
            val fm = text.fontMetrics
            drawText(initials, c, c - (fm.ascent + fm.descent) / 2, text)
        }

        /** The heading's triangle, pointing north: 8 dp wide and 6 dp deep, just outside a dot's ring; the image is
         *  centred on the dot, so it turns about it. */
        fun arrow(m: DisplayMetrics): Bitmap = image(m, 44f, 44f) { d ->
            val cx = 22 * d
            val base = cx - (MEMBER_DP / 2 + 1) * d
            val path = Path().apply {
                moveTo(cx, base - 6 * d)
                lineTo(cx + 4 * d, base)
                lineTo(cx - 4 * d, base)
                close()
            }
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(1.5f * d, 0f, 0f, 0x66000000) })
        }

        /** A pin: a 30 dp disc in the type's colour with a 2 dp white ring and its 16 dp white glyph (§9.4). */
        fun pinImage(m: DisplayMetrics, type: PinType?): Bitmap = image(m, PIN_DP, PIN_DP) { d ->
            val c = PIN_DP / 2 * d
            drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawCircle(c, c, c - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColour(type) })
            val g = 8 * d
            drawIcon(this, pinIcon(type), RectF(c - g, c - g, c + g, c + g), Color.WHITE)
        }

        fun pinColour(type: PinType?): Int = when (type) {
            PinType.animal -> 0xFF2E7D32.toInt()
            PinType.shelter -> 0xFF8D6E63.toInt()
            PinType.water -> 0xFF1E88E5.toInt()
            PinType.danger -> 0xFFE53935.toInt()
            PinType.pothole -> 0xFFF57C00.toInt()
            PinType.police -> 0xFF3949AB.toInt()
            null -> 0xFF757575.toInt()
        }

        /** An animal pin's label: white bold 12 sp on a black 65 % capsule. */
        fun labelImage(m: DisplayMetrics, label: String): Bitmap {
            val d = m.density
            val paint = MapImages.textPaint(12 * d, MapImages.BOLD)
            val fm = paint.fontMetrics
            val w = ceil(paint.measureText(label) / d) + 16
            val h = ceil((fm.descent - fm.ascent) / d) + 6
            return image(m, w, h) { dd ->
                drawRoundRect(RectF(0f, 0f, w * dd, h * dd), h * dd / 2, h * dd / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() })
                drawText(label, 8 * dd, h * dd / 2 - (fm.ascent + fm.descent) / 2, paint)
            }
        }

        private fun image(m: DisplayMetrics, w: Float, h: Float, draw: Canvas.(Float) -> Unit): Bitmap {
            val d = m.density
            val bitmap = Bitmap.createBitmap(ceil(w * d).toInt().coerceAtLeast(1), ceil(h * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.density = m.densityDpi
            Canvas(bitmap).draw(d)
            return bitmap
        }
    }
}
