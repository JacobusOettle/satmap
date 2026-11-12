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
import android.text.TextPaint
import android.util.DisplayMetrics
import com.jacobusoettle.satmap.app.DroneLinkModel
import com.jacobusoettle.satmap.core.DroneLink
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconPadding
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** Drone link on the phone's map, in drone mode only, above the airfields (android/docs/spec/drone-link.md §3): each
 *  drone (turned to its heading, in its own colour, "Drone 1" and its height above take-off), its home point, and the
 *  ADS-B aircraft it hears (an icon by type, turned to the heading, the callsign or ICAO address, altitude in feet,
 *  speed in knots and a climb arrow). Low aircraft close to you or a drone are drawn red. Drones grey after 10 s
 *  without a position, aircraft fade after 15 s (`DroneLink`). Not on Android Auto. The iPhone's `DroneLinkLayer`.
 *
 *  One GeoJSON source, `drone-link`, and its symbol layers just above `aviation`: the homes, a ring under the selected
 *  item, the icons (MapLibre turns them with `icon-rotate`, map-aligned; balloons and the generic symbol stay upright
 *  in their own layer) and the label cards under them, which stay upright. Every image is app-drawn (the style has no
 *  glyphs). Main thread only. */
class DroneLinkLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** An item on the map now (for taps, accessibility and the walkthrough). */
    sealed interface Item {
        val position: GeoPoint
        val label: String

        data class Drone(val sysid: Int, override val position: GeoPoint, override val label: String) : Item
        data class Home(val sysid: Int, override val position: GeoPoint, override val label: String) : Item
        data class Aircraft(val icao: Long, override val position: GeoPoint, override val label: String) : Item
    }

    var shown: List<Item> = emptyList()
        private set
    private val images = HashSet<String>()

    var isOn = false
        set(value) {
            if (value == field) return
            field = value
            if (!value) clear()
        }

    init {
        install(style)
    }

    /** Shows what the link has now. `me` and `meGroundM`: you and the ground's height where you are, for the red
     *  highlight; `heightUnit` for the drones' heights. */
    fun update(
        traffic: DroneLink.Traffic,
        selection: DroneLinkModel.Selection?,
        me: GeoPoint?,
        meGroundM: Double?,
        heightUnit: HeightUnit,
        now: Double,
    ) {
        if (!isOn) return
        val m = context.resources.displayMetrics
        val refs = ArrayList<DroneLink.Reference>()
        if (me != null) refs.add(DroneLink.Reference(me, meGroundM))
        val features = ArrayList<Feature>()
        val items = ArrayList<Item>()
        val wanted = HashMap<String, () -> Bitmap>()
        fun need(id: String, make: () -> Bitmap) {
            if (id !in images && id !in wanted) wanted[id] = make
        }
        need(RING_DRONE) { ringImage(m, DRONE_SIZE) }
        need(RING_AIRCRAFT) { ringImage(m, AIRCRAFT_SIZE) }
        for (d in traffic.sortedDrones) {
            d.homePoint?.let { home ->
                val colour = colour(d.colourIndex)
                val id = "dl-home/" + Integer.toHexString(colour)
                need(id) { homeImage(m, colour) }
                features += point(home).apply {
                    addStringProperty("kind", "home")
                    addStringProperty("icon", id)
                }
                items += Item.Home(d.sysid, home, "Home of ${d.label}")
            }
            val p = d.point ?: continue
            refs.add(DroneLink.Reference(p, d.takeOffGroundM))
            val stale = d.isStale(now)
            val colour = if (stale) staleColour else colour(d.colourIndex)
            val icon = "dl-drone/" + Integer.toHexString(colour)
            need(icon) { iconImage(m, DroneLinkShapes.quad, colour, DRONE_SIZE) }
            val lines = listOfNotNull(d.label, d.position?.let { "${heightUnit.whole(it.relativeAltM)} ${heightUnit.symbol}" })
            val label = labelImageId(lines, colour, null)
            need(label) { labelImage(m, lines, colour, null) }
            features += point(p).apply {
                addStringProperty("kind", "drone")
                addNumberProperty("sysid", d.sysid)
                addStringProperty("icon", icon)
                addStringProperty("label", label)
                d.headingDeg?.let { addNumberProperty("hdg", (it % 360 + 360) % 360) }
                if (selection == DroneLinkModel.Selection.Drone(d.sysid)) addBooleanProperty("selected", true)
                addNumberProperty("alpha", 1.0)
                addNumberProperty("z", 2)
            }
            items += Item.Drone(d.sysid, p, d.label)
        }
        for (a in traffic.aircraft.values.sortedBy { it.icaoAddress }) {
            val highlight = DroneLink.isLowAndClose(a, refs)
            val fill = if (highlight) highlightColour else Color.WHITE
            val icon = "dl-aircraft/${a.kind.name}/" + Integer.toHexString(fill)
            need(icon) { iconImage(m, DroneLinkShapes.path(a.kind), fill, AIRCRAFT_SIZE) }
            val details = listOfNotNull(a.altitudeText, a.speedText, a.climbArrow).joinToString(" · ")
            val lines = listOf(a.label) + if (details.isEmpty()) emptyList() else listOf(details)
            val border = if (highlight) highlightColour else null
            val label = labelImageId(lines, Color.WHITE, border)
            need(label) { labelImage(m, lines, Color.WHITE, border) }
            features += point(a.position).apply {
                addStringProperty("kind", "aircraft")
                addNumberProperty("icao", a.icaoAddress)
                addStringProperty("icon", icon)
                addStringProperty("label", label)
                if (a.kind.rotates) a.headingDeg?.let { addNumberProperty("hdg", (it % 360 + 360) % 360) }
                if (selection == DroneLinkModel.Selection.Aircraft(a.icaoAddress)) addBooleanProperty("selected", true)
                addNumberProperty("alpha", if (a.isStale(now)) 0.45 else 1.0)
                addNumberProperty("z", 1)
            }
            items += Item.Aircraft(a.icaoAddress, a.position, "${a.kind.title} ${a.label}")
        }
        if (wanted.isNotEmpty()) {
            style.addImages(HashMap(wanted.mapValues { it.value() }))
            images += wanted.keys
        }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        shown = items
        // Labels change with every height: those no longer used go (icons are few and stay).
        val used = features.mapNotNull { it.getStringProperty("label") }.toSet()
        for (id in images.filter { it.startsWith(LABEL_PREFIX) && it !in used }) {
            style.removeImage(id)
            images -= id
        }
    }

    /** The drone or aircraft at a spot on the screen (the map view's pixels), within a fingertip (26 dp) of its icon;
     *  drones first. */
    fun item(near: PointF): DroneLinkModel.Selection? {
        if (!isOn) return null
        val d = context.resources.displayMetrics.density
        fun apart(p: GeoPoint): Double {
            val s = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
            return hypot((s.x - near.x).toDouble(), (s.y - near.y).toDouble()) / d
        }
        shown.filterIsInstance<Item.Drone>().map { it to apart(it.position) }.filter { it.second < HIT_DP }
            .minByOrNull { it.second }?.let { return DroneLinkModel.Selection.Drone(it.first.sysid) }
        shown.filterIsInstance<Item.Aircraft>().map { it to apart(it.position) }.filter { it.second < HIT_DP }
            .minByOrNull { it.second }?.let { return DroneLinkModel.Selection.Aircraft(it.first.icao) }
        return null
    }

    /** Where an item is in the map view (pixels). */
    fun screenPoint(item: Item): PointF = map.projection.toScreenLocation(LatLng(item.position.lat, item.position.lon))

    private fun clear() {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(MapStyle.empty())
        shown = emptyList()
        for (id in images.filter { it.startsWith(LABEL_PREFIX) }) {
            style.removeImage(id)
            images -= id
        }
    }

    companion object {
        const val SOURCE = "drone-link"
        const val HOMES = "drone-link-homes"
        const val RINGS = "drone-link-rings"
        const val ICONS = "drone-link-icons"
        const val UPRIGHT_ICONS = "drone-link-upright-icons"
        const val LABELS = "drone-link-labels"

        /** Its layers, bottom to top. */
        val layerIds = listOf(HOMES, RINGS, ICONS, UPRIGHT_ICONS, LABELS)

        /** The icons' sizes (dp): drones 32, aircraft 28, as on the iPhone. */
        const val DRONE_SIZE = 32f
        const val AIRCRAFT_SIZE = 28f
        const val HIT_DP = 26.0

        private const val RING_DRONE = "dl-ring/drone"
        private const val RING_AIRCRAFT = "dl-ring/aircraft"
        private const val LABEL_PREFIX = "dl-label/"

        /** One colour per drone, in the order they were first heard (none of them your cyan or the pin's amber):
         *  magenta, lime, violet, yellow, teal. */
        val droneColours: List<Int> = listOf(
            rgb(1.0, 0.31, 0.85), rgb(0.49, 1.0, 0.31), rgb(0.62, 0.48, 1.0), rgb(1.0, 0.95, 0.35), rgb(0.2, 0.9, 0.7),
        )
        val staleColour: Int = rgb(0.62, 0.62, 0.62)
        val highlightColour: Int = rgb(1.0, 0.27, 0.23)

        fun colour(index: Int): Int = droneColours[((index % droneColours.size) + droneColours.size) % droneColours.size]

        private fun rgb(r: Double, g: Double, b: Double): Int =
            Color.rgb(Math.round(r * 255).toInt(), Math.round(g * 255).toInt(), Math.round(b * 255).toInt())

        /** The source and the layers, just above the airfields' cards (`aviation`); idempotent. */
        fun install(style: Style) {
            if (style.getSource(SOURCE) == null) {
                style.addSource(GeoJsonSource(SOURCE, MapStyle.empty(), GeoJsonOptions().withSynchronousUpdate(true)))
            }
            var below = MapIds.AVIATION
            for (id in layerIds) {
                if (style.getLayer(id) == null) {
                    if (style.getLayer(below) != null) style.addLayerAbove(layer(id), below) else style.addLayer(layer(id))
                }
                below = id
            }
        }

        private val notHome: Expression get() = not(eq(get("kind"), literal("home")))

        fun layer(id: String): Layer = when (id) {
            HOMES -> SymbolLayer(HOMES, SOURCE).withFilter(eq(get("kind"), literal("home"))).withProperties(
                iconImage(get("icon")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
            RINGS -> SymbolLayer(RINGS, SOURCE).withFilter(Expression.all(notHome, has("selected"))).withProperties(
                iconImage(Expression.switchCase(eq(get("kind"), literal("drone")), literal(RING_DRONE), literal(RING_AIRCRAFT))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
            ICONS -> SymbolLayer(ICONS, SOURCE).withFilter(Expression.all(notHome, has("hdg"))).withProperties(
                iconImage(get("icon")),
                iconRotate(get("hdg")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconOpacity(get("alpha")),
                symbolSortKey(get("z")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
            UPRIGHT_ICONS -> SymbolLayer(UPRIGHT_ICONS, SOURCE).withFilter(Expression.all(notHome, not(has("hdg")))).withProperties(
                iconImage(get("icon")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                iconOpacity(get("alpha")),
                symbolSortKey(get("z")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
            // The card's top 3 dp under the icon: half the icon (16 for a drone, 14 for an aircraft) + 3.
            LABELS -> SymbolLayer(LABELS, SOURCE).withFilter(has("label")).withProperties(
                iconImage(get("label")),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                iconOffset(
                    Expression.switchCase(
                        eq(get("kind"), literal("drone")), literal(arrayOf(0f, DRONE_SIZE / 2 + 3)),
                        literal(arrayOf(0f, AIRCRAFT_SIZE / 2 + 3)),
                    ),
                ),
                iconOpacity(get("alpha")),
                symbolSortKey(get("z")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconPadding(0f),
            )
            else -> error("not a drone link layer: $id")
        }

        private fun point(p: GeoPoint) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))

        fun labelImageId(lines: List<String>, colour: Int, border: Int?) =
            LABEL_PREFIX + Integer.toHexString(colour) + "/" + (border?.let { Integer.toHexString(it) } ?: "-") + "/" + lines.joinToString("\n")

        /** A transparent bitmap `w` × `h` dp at the screen's density, drawn by `draw` (given the density). */
        private fun image(m: DisplayMetrics, w: Float, h: Float, draw: Canvas.(Float) -> Unit): Bitmap =
            Bitmap.createBitmap(ceil(w * m.density).toInt(), ceil(h * m.density).toInt(), Bitmap.Config.ARGB_8888).apply {
                density = m.densityDpi
                Canvas(this).draw(m.density)
            }

        /** Room round an icon for its shadow (dp). */
        private const val SHADOW = 3f

        /** An icon: `path` (a 24-unit box, nose up) drawn `size` dp, filled with `fill`, a dark 1.2 dp outline and a
         *  soft shadow, as the iPhone's `CAShapeLayer`. */
        fun iconImage(m: DisplayMetrics, path: Path, fill: Int, size: Float): Bitmap = image(m, size + 2 * SHADOW, size + 2 * SHADOW) { d ->
            val scaled = Path(path).apply {
                transform(Matrix().apply {
                    setScale(size / 24 * d, size / 24 * d)
                    postTranslate(SHADOW * d, SHADOW * d)
                })
            }
            drawPath(scaled, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                setShadowLayer(2 * d, 0f, 0f, 0x80000000.toInt())
            })
            drawPath(scaled, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * d
                strokeJoin = Paint.Join.ROUND
                color = 0xBF000000.toInt()
            })
        }

        /** The selected item's ring: 4 dp round its icon, white 22 % with a 2 dp white line. */
        fun ringImage(m: DisplayMetrics, size: Float): Bitmap {
            val r = size / 2 + 4
            return image(m, 2 * r + 2, 2 * r + 2) { d ->
                val c = (r + 1) * d
                drawCircle(c, c, r * d - d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF })
                drawCircle(c, c, r * d - d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2 * d
                    color = Color.WHITE
                })
            }
        }

        /** A drone's home point: a 22 dp dark disc with a 2.5 dp ring and an "H" in its colour. */
        fun homeImage(m: DisplayMetrics, colour: Int): Bitmap = image(m, 22f, 22f) { d ->
            drawCircle(11 * d, 11 * d, 11 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() })
            drawCircle(11 * d, 11 * d, 9.75f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f * d
                color = colour
            })
            val p = MapImages.textPaint(12 * d, MapImages.HEAVY).apply {
                textAlign = Paint.Align.CENTER
                color = colour
            }
            drawText("H", 11 * d, 11 * d - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2, p)
        }

        /** A label card: the first line bold 13 in `colour`, the rest 11.5 semibold with tabular digits in white,
         *  centred, on a dark card (white 5 % at 78 %) with 8 dp corners and a faint border (1.5 dp in `border`). */
        fun labelImage(m: DisplayMetrics, lines: List<String>, colour: Int, border: Int?): Bitmap {
            val d = m.density
            val bold = MapImages.textPaint(13f * d, MapImages.BOLD).apply { color = colour }
            val small = MapImages.textPaint(11.5f * d, MapImages.SEMIBOLD).apply {
                color = Color.WHITE
                fontFeatureSettings = "tnum"
            }
            val paints = lines.indices.map { if (it == 0) bold else small }
            var width = 0f
            var height = 0f
            for ((i, l) in lines.withIndex()) {
                width = max(width, paints[i].measureText(l) / d)
                height += (paints[i].fontMetrics.descent - paints[i].fontMetrics.ascent) / d
            }
            val w = ceil(width) + 12
            val h = ceil(height) + 6
            return image(m, w, h) { dd ->
                val r = RectF(0f, 0f, w * dd, h * dd)
                drawRoundRect(r, 8 * dd, 8 * dd, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC70D0D0D.toInt() })
                val line = (if (border != null) 1.5f else 0.5f) * dd
                drawRoundRect(
                    RectF(r.left + line / 2, r.top + line / 2, r.right - line / 2, r.bottom - line / 2), 8 * dd, 8 * dd,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = line
                        color = border ?: 0x2EFFFFFF
                    },
                )
                var y = 3 * dd
                for ((i, l) in lines.withIndex()) {
                    val p = TextPaint(paints[i]).apply { textAlign = Paint.Align.CENTER }
                    y -= p.fontMetrics.ascent
                    drawText(l, w * dd / 2, y, p)
                    y += p.fontMetrics.descent
                }
            }
        }
    }
}

/** The icons, in a 24-unit box with the nose up: the iPhone's `DroneLinkShapes`, point for point. */
object DroneLinkShapes {
    /** A shape symmetric about x = 12, from its right half (nose to tail). */
    private fun mirrored(vararg half: Pair<Float, Float>): Path = Path().apply {
        moveTo(half[0].first, half[0].second)
        for (p in half.drop(1)) lineTo(p.first, p.second)
        for (p in half.reversed()) if (p.first != 12f) lineTo(24 - p.first, p.second)
        close()
    }

    /** `src` stroked `width` wide with round caps and joins, as a filled outline (`CGPath.copy(strokingWithWidth:)`). */
    private fun stroked(src: Path, width: Float): Path {
        val out = Path()
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }.getFillPath(src, out)
        return out
    }

    val airliner: Path get() = mirrored(
        12f to 1f, 13.2f to 3f, 13.2f to 8.5f, 22.5f to 13.5f, 22.5f to 15.5f, 13.2f to 12.8f, 13.2f to 18f,
        16.8f to 20.6f, 16.8f to 22.2f, 12f to 21.2f,
    )
    val light: Path get() = mirrored(
        12f to 2f, 13.2f to 4f, 13.2f to 7.6f, 22.5f to 7.6f, 22.5f to 10.6f, 13.2f to 10.6f, 12.9f to 18f,
        16.4f to 18.4f, 16.4f to 20.8f, 12f to 20.8f,
    )
    val glider: Path get() = mirrored(
        12f to 3f, 12.9f to 5f, 12.9f to 8.8f, 23.6f to 9.4f, 23.6f to 11f, 12.9f to 11.2f, 12.7f to 19.2f,
        15.6f to 19.6f, 15.6f to 21.2f, 12f to 21.2f,
    )

    val helicopter: Path
        get() = Path().apply {
            addOval(RectF(8.2f, 6.5f, 15.8f, 16f), Path.Direction.CW)
            addRect(RectF(11.2f, 15f, 12.8f, 22f), Path.Direction.CW)
            addRect(RectF(9f, 20.2f, 15f, 21.8f), Path.Direction.CW)
            val rotor = Path().apply {
                moveTo(3f, 2.5f)
                lineTo(21f, 20f)
                moveTo(21f, 2.5f)
                lineTo(3f, 20f)
            }
            addPath(stroked(rotor, 1.8f))
        }

    val balloon: Path
        get() = Path().apply {
            addOval(RectF(5f, 1.5f, 19f, 16.5f), Path.Direction.CW)
            val cords = Path().apply {
                moveTo(8f, 14.5f)
                lineTo(10.5f, 19f)
                moveTo(16f, 14.5f)
                lineTo(13.5f, 19f)
            }
            addPath(stroked(cords, 1.2f))
            addRect(RectF(10f, 19f, 14f, 22.5f), Path.Direction.CW)
        }

    val other: Path
        get() = Path().apply {
            moveTo(12f, 4f)
            lineTo(20f, 12f)
            lineTo(12f, 20f)
            lineTo(4f, 12f)
            close()
        }

    /** A quadcopter seen from above: four rotors on an X, a body, and a nose pointing the way it flies. */
    val quad: Path
        get() = Path().apply {
            val arms = Path().apply {
                moveTo(5f, 5f)
                lineTo(19f, 19f)
                moveTo(19f, 5f)
                lineTo(5f, 19f)
            }
            addPath(stroked(arms, 2.2f))
            for ((x, y) in listOf(5f to 5f, 19f to 5f, 5f to 19f, 19f to 19f)) {
                addPath(stroked(Path().apply { addOval(RectF(x - 4.2f, y - 4.2f, x + 4.2f, y + 4.2f), Path.Direction.CW) }, 1.8f))
            }
            addOval(RectF(8.5f, 8.5f, 15.5f, 15.5f), Path.Direction.CW)
            moveTo(12f, 0.5f)
            lineTo(14.6f, 5f)
            lineTo(9.4f, 5f)
            close()
        }

    fun path(kind: DroneLink.AircraftKind): Path = when (kind) {
        DroneLink.AircraftKind.airliner -> airliner
        DroneLink.AircraftKind.light -> light
        DroneLink.AircraftKind.helicopter -> helicopter
        DroneLink.AircraftKind.glider -> glider
        DroneLink.AircraftKind.balloon -> balloon
        DroneLink.AircraftKind.uav -> quad
        DroneLink.AircraftKind.other -> other
    }
}
