package com.jacobusoettle.satmap.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate. Kept independent of Android's `Location` so the core logic is portable and testable. */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** The Swift `Codable` form, `{"lat":…,"lon":…}`, so saved areas read the same on both platforms. */
    fun toJson(): JsonObject = buildJsonObject {
        put("lat", swiftJSONNumber(lat))
        put("lon", swiftJSONNumber(lon))
    }

    companion object {
        /** Reads `toJson()`'s form; throws if it isn't one. */
        fun fromJson(json: JsonElement): GeoPoint {
            val o = json.jsonObject
            return GeoPoint(lat = jsonDouble(o.getValue("lat")), lon = jsonDouble(o.getValue("lon")))
        }
    }
}

/** A `Double` as Swift's `JSONDecoder` reads one: a JSON number (not a string, nor NaN, hex, "1.5d", "+1", ".5"),
 *  that a `Double` holds (not overflowing to infinity, nor a non-zero number underflowing to 0). Throws otherwise. */
internal fun jsonDouble(json: JsonElement): Double {
    val p = json.jsonPrimitive
    require(!p.isString && jsonNumber.matches(p.content)) { "Not a number: $p" }
    val d = p.content.toDouble()
    require(d.isFinite() && (d != 0.0 || p.content.substringBefore('e').substringBefore('E').none { it in '1'..'9' })) {
        "Number ${p.content} is not representable"
    }
    return d
}

private val jsonNumber = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

/** A Double as Swift's JSONEncoder writes it: its `description` without a trailing ".0"
 *  ("150", "0.000123", "1e-07", "1.2345678901234568e+17", "-0"). */
@OptIn(ExperimentalSerializationApi::class)
internal fun swiftJSONNumber(v: Double): JsonPrimitive {
    if (!v.isFinite()) return JsonPrimitive(v) // Swift's encoder throws; never written
    if (v == 0.0) return JsonUnquotedLiteral(if (1.0 / v < 0) "-0" else "0")
    // Shortest digits that read back as v, the closest of them, even on a tie (Swift's rule); not Double.toString,
    // which can differ (4.9E-324) and isn't always shortest on Android.
    val exact = BigDecimal(v)
    val d = (1..17).firstNotNullOf { length ->
        val nearest = exact.round(MathContext(length, RoundingMode.HALF_EVEN))
        // At a power of two the gap below is half the gap above: there the farther neighbour may read back alone.
        val other = exact.round(MathContext(length, if (nearest < exact) RoundingMode.CEILING else RoundingMode.FLOOR))
        listOf(nearest, other).firstOrNull { it.toDouble() == v }
    }.stripTrailingZeros()
    val exp = d.precision() - d.scale() - 1
    if (exp >= -4 && abs(v) <= 9007199254740992.0) return JsonUnquotedLiteral(d.toPlainString())
    val digits = d.unscaledValue().abs().toString()
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    val e = (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(2, '0')
    return JsonUnquotedLiteral((if (v < 0) "-" else "") + mantissa + "e" + e)
}

/** A point in a plane: the Swift code's `(x: Double, y: Double)` tuple (world units, tile units, metres or pixels). */
data class XY(val x: Double, val y: Double)

/** A rectangle in normalized world units: the Swift code's `(minX:, minY:, maxX:, maxY:)` tuple of `Double`s. */
data class WorldRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** An inclusive range of tile columns and rows: the Swift `(minX:, minY:, maxX:, maxY:)` tuple of `Int`s. */
data class TileRange(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

/** Spherical Web Mercator in normalized "world" units: x in [0, 1) west→east, y in [0, 1) north→south.
 *  Multiply by 2^z to get fractional XYZ tile coordinates at zoom z. */
object WebMercator {
    const val maxLatitude = 85.051_128_779_806_59

    fun world(p: GeoPoint): XY {
        val lat = min(max(p.lat, -maxLatitude), maxLatitude)
        val x = (p.lon + 180.0) / 360.0
        val s = sin(lat * PI / 180.0)
        val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        return XY(x, y)
    }

    fun geo(worldX: Double, worldY: Double): GeoPoint {
        val lon = worldX * 360.0 - 180.0
        val n = PI - 2.0 * PI * worldY
        val lat = 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
        return GeoPoint(lat = lat, lon = lon)
    }
}

/** An XYZ ("slippy map") tile address. y counts from the top (north), as used by Esri, OSM and MapLibre. */
data class TileID(val z: Int, val x: Int, val y: Int) {
    val description: String get() = "$z/$x/$y"

    override fun toString() = description

    val parent: TileID?
        get() = if (z > 0) TileID(z = z - 1, x = x shr 1, y = y shr 1) else null

    /** The ancestor `levels` zoom levels up (levels >= 0). */
    fun ancestor(levels: Int): TileID = TileID(z = z - levels, x = x shr levels, y = y shr levels)

    /** World-unit bounds of this tile. */
    val worldBounds: WorldRect
        get() {
            val n = (1 shl z).toDouble()
            return WorldRect(x.toDouble() / n, y.toDouble() / n, (x + 1).toDouble() / n, (y + 1).toDouble() / n)
        }

    /** The Swift `Codable` form, `{"z":…,"x":…,"y":…}`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("z", z)
        put("x", x)
        put("y", y)
    }

    companion object {
        fun containing(p: GeoPoint, z: Int): TileID {
            val w = WebMercator.world(p)
            val n = 1 shl z
            val x = min(max(floor(w.x * n.toDouble()).toInt(), 0), n - 1)
            val y = min(max(floor(w.y * n.toDouble()).toInt(), 0), n - 1)
            return TileID(z = z, x = x, y = y)
        }

        /** Reads `toJson()`'s form; throws if it isn't one. */
        fun fromJson(json: JsonElement): TileID {
            val o = json.jsonObject
            return TileID(
                z = o.getValue("z").jsonPrimitive.int,
                x = o.getValue("x").jsonPrimitive.int,
                y = o.getValue("y").jsonPrimitive.int,
            )
        }
    }
}

object Geo {
    /** Mean Earth radius (IUGG), metres. */
    const val earthRadius = 6_371_008.8

    /** Equatorial circumference used by Web Mercator, metres. */
    const val mercatorCircumference = 2 * PI * 6_378_137.0

    /** Great-circle distance in metres (haversine). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val φ1 = a.lat * PI / 180
        val φ2 = b.lat * PI / 180
        val dφ = φ2 - φ1
        val dλ = (b.lon - a.lon) * PI / 180
        val h = sin(dφ / 2) * sin(dφ / 2) + cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
        return 2 * earthRadius * asin(swiftMin(1.0, sqrt(h)))
    }

    /** The initial great-circle bearing from `a` to `b`, degrees clockwise from true north (0–360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val φ1 = a.lat * PI / 180
        val φ2 = b.lat * PI / 180
        val dλ = (b.lon - a.lon) * PI / 180
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        val deg = atan2(y, x) * 180 / PI
        return if (deg < 0) deg + 360 else deg
    }

    /** Whether a spot is inside a ring (in latitude and longitude, as small areas are drawn). */
    fun contains(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Length of the line through the points, in metres. */
    fun pathLength(points: List<GeoPoint>): Double =
        points.zipWithNext().fold(0.0) { total, (a, b) -> total + distance(a, b) }

    /** Ground metres represented by one normalized world unit at a given latitude. */
    fun metersPerWorldUnit(lat: Double): Double = mercatorCircumference * cos(lat * PI / 180)

    /** Approximate area of a closed ring in square metres (spherical excess on a sphere). */
    fun ringArea(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        var total = 0.0
        for (i in ring.indices) {
            val p1 = ring[i]
            val p2 = ring[(i + 1) % ring.size]
            total += (p2.lon - p1.lon) * PI / 180 * (2 + sin(p1.lat * PI / 180) + sin(p2.lat * PI / 180))
        }
        return abs(total * earthRadius * earthRadius / 2)
    }
}

/** Axis-aligned bounds in degrees. */
data class GeoBounds(var minLat: Double, var minLon: Double, var maxLat: Double, var maxLon: Double) {
    fun include(p: GeoPoint) {
        minLat = min(minLat, p.lat)
        maxLat = max(maxLat, p.lat)
        minLon = min(minLon, p.lon)
        maxLon = max(maxLon, p.lon)
    }

    fun union(o: GeoBounds): GeoBounds = GeoBounds(
        minLat = min(minLat, o.minLat), minLon = min(minLon, o.minLon),
        maxLat = max(maxLat, o.maxLat), maxLon = max(maxLon, o.maxLon),
    )

    val center: GeoPoint get() = GeoPoint(lat = (minLat + maxLat) / 2, lon = (minLon + maxLon) / 2)

    /** Expands the bounds by a distance in metres on every side. */
    fun expanded(m: Double): GeoBounds {
        val dLat = m / 111_320.0
        val dLon = m / max(1.0, 111_320.0 * cos(center.lat * PI / 180))
        return GeoBounds(
            minLat = max(-90.0, minLat - dLat), minLon = max(-180.0, minLon - dLon),
            maxLat = min(90.0, maxLat + dLat), maxLon = min(180.0, maxLon + dLon),
        )
    }

    /** Inclusive tile index range covered by these bounds at zoom z. */
    fun tileRange(z: Int): TileRange {
        val a = TileID.containing(GeoPoint(lat = maxLat, lon = minLon), z = z)
        val b = TileID.containing(GeoPoint(lat = minLat, lon = maxLon), z = z)
        return TileRange(a.x, a.y, b.x, b.y)
    }

    /** The Swift `Codable` form, `{"minLat":…,"minLon":…,"maxLat":…,"maxLon":…}`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("minLat", swiftJSONNumber(minLat))
        put("minLon", swiftJSONNumber(minLon))
        put("maxLat", swiftJSONNumber(maxLat))
        put("maxLon", swiftJSONNumber(maxLon))
    }

    companion object {
        /** The bounds of the points, or null when there are none (Swift's `GeoBounds(points:)`). */
        operator fun invoke(points: Iterable<GeoPoint>): GeoBounds? {
            val it = points.iterator()
            if (!it.hasNext()) return null
            val first = it.next()
            val b = GeoBounds(minLat = first.lat, minLon = first.lon, maxLat = first.lat, maxLon = first.lon)
            while (it.hasNext()) b.include(it.next())
            return b
        }

        /** Reads `toJson()`'s form; throws if it isn't one. */
        fun fromJson(json: JsonElement): GeoBounds {
            val o = json.jsonObject
            return GeoBounds(
                minLat = jsonDouble(o.getValue("minLat")),
                minLon = jsonDouble(o.getValue("minLon")),
                maxLat = jsonDouble(o.getValue("maxLat")),
                maxLon = jsonDouble(o.getValue("maxLon")),
            )
        }
    }
}

object PolygonLabel {
    /** A point inside the polygon (outer ring minus holes) for a name label: the area centroid if it is inside,
     *  otherwise the middle of the widest horizontal span across the polygon (L- and U-shaped blocks). */
    fun interiorPoint(outer: List<GeoPoint>, holes: List<List<GeoPoint>> = emptyList()): GeoPoint? {
        if (outer.size < 3) return null
        val b = GeoBounds(points = outer) ?: return null
        // Work relative to the first vertex for precision.
        val o = outer[0]
        fun local(p: GeoPoint) = XY(p.lon - o.lon, p.lat - o.lat)
        val ring = outer.map(::local)
        val holeRings = holes.map { it.map(::local) }

        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            val cross = p.x * q.y - q.x * p.y
            a += cross
            cx += (p.x + q.x) * cross
            cy += (p.y + q.y) * cross
        }
        if (abs(a) > 1e-18) {
            val c = XY(cx / (3 * a), cy / (3 * a))
            if (inside(c, ring) && holeRings.none { inside(c, it) }) {
                return GeoPoint(lat = o.lat + c.y, lon = o.lon + c.x)
            }
        }

        // Widest interior span on a few horizontal lines.
        var best: Span? = null
        for (k in 1..15) {
            val y = (b.minLat - o.lat) + (b.maxLat - b.minLat) * k.toDouble() / 16
            val xs = mutableListOf<Double>()
            for (r in listOf(ring) + holeRings) {
                for (i in r.indices) {
                    val p = r[i]
                    val q = r[(i + 1) % r.size]
                    if ((p.y > y) != (q.y > y)) xs.add(p.x + (y - p.y) / (q.y - p.y) * (q.x - p.x))
                }
            }
            xs.sort()
            var i = 0
            while (i + 1 < xs.size) {
                val w = xs[i + 1] - xs[i]
                if (w > (best?.width ?: 0.0)) best = Span(w, (xs[i] + xs[i + 1]) / 2, y)
                i += 2
            }
        }
        best?.let { return GeoPoint(lat = o.lat + it.y, lon = o.lon + it.x) }
        return b.center
    }

    /** A span across the polygon: its width and middle. */
    private data class Span(val width: Double, val x: Double, val y: Double)

    private fun inside(p: XY, ring: List<XY>): Boolean {
        var c = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }
}

object Simplify {
    /** Douglas–Peucker simplification of a polyline in planar coordinates. */
    fun douglasPeucker(pts: List<XY>, tolerance: Double): List<XY> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = mutableListOf(0 to pts.size - 1)
        val tol2 = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeAt(stack.lastIndex)
            if (e <= s + 1) continue
            val a = pts[s]
            val b = pts[e]
            var maxD = -1.0
            var idx = s
            for (i in (s + 1) until e) {
                val d = segmentDistanceSquared(pts[i], a, b)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > tol2) {
                keep[idx] = true
                stack.add(s to idx)
                stack.add(idx to e)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    fun segmentDistanceSquared(p: XY, a: XY, b: XY): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        var t = 0.0
        if (len2 > 0) t = max(0.0, min(1.0, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2))
        val qx = a.x + t * dx - p.x
        val qy = a.y + t * dy - p.y
        return qx * qx + qy * qy
    }
}

/** The map's scale bar: the longest round distance (1, 2 or 5 × a power of ten metres) that fits in the bar. */
object ScaleBar {
    /** A scale bar's distance and its length in points. */
    data class Choice(val meters: Double, val width: Double)

    /** The distance and the bar's length in points, given the ground metres per screen point. */
    fun choose(metersPerPoint: Double, maxWidth: Double): Choice? {
        if (!(metersPerPoint > 0 && metersPerPoint.isFinite() && maxWidth > 0)) return null
        val most = metersPerPoint * maxWidth
        val power = 10.0.pow(floor(log10(most)))
        val meters = listOf(5.0, 2.0, 1.0).map { it * power }.firstOrNull { it <= most * (1 + 1e-9) } ?: power
        return Choice(meters, meters / metersPerPoint)
    }

    /** "50 cm", "200 m", "5 km". */
    fun label(meters: Double): String {
        if (meters >= 1000) return "${(meters / 1000).rounded().toLong()} km"
        if (meters >= 1) return "${meters.rounded().toLong()} m"
        return "${(meters * 100).rounded().toLong()} cm"
    }
}
