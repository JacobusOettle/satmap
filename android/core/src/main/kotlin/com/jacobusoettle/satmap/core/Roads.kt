package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Roads and tracks from OpenStreetMap (Overpass API), for offline areas that keep satellite imagery only
 *  along the roads. */
object Roads {
    /** Imagery kept on each side of a road's centre line, in metres. */
    const val corridorMeters = 15.0

    /** OSM `highway` types a vehicle drives on: public roads of every class, service roads (yards, driveways)
     *  and tracks (farm and forest roads, mostly gravel or dirt). Footpaths and cycle paths are left out. */
    val highwayTypes = listOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "living_street", "service", "track", "road",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    /** More boxes than this are replaced by one box around the whole area. */
    private const val maxQueryBoxes = 40

    /** Overpass QL for the roads in boxes around each part of the area (so a KML file with blocks far apart
     *  doesn't fetch every road between them). Each box is the part grown by its buffer and the corridor. */
    fun overpassQuery(area: CoverageShape): String? {
        val margin = area.bufferMeters + corridorMeters
        val polygonMargin = area.polygonBuffer + corridorMeters
        var boxes = mutableListOf<GeoBounds>()
        for (ring in area.polygons) {
            if (ring.size < 3) continue
            GeoBounds(points = ring)?.let { boxes.add(it.expanded(polygonMargin)) }
        }
        for (line in area.lines) {
            if (line.isEmpty()) continue
            GeoBounds(points = line)?.let { boxes.add(it.expanded(margin)) }
        }
        for (p in area.points) {
            boxes.add(GeoBounds(minLat = p.lat, minLon = p.lon, maxLat = p.lat, maxLon = p.lon).expanded(margin))
        }
        val all = area.geometryBounds
        if (boxes.size > maxQueryBoxes && all != null) {
            boxes = mutableListOf(all.expanded(max(margin, polygonMargin)))
        }
        if (boxes.isEmpty()) return null
        val filter = "way[\"highway\"~\"^(${highwayTypes.joinToString("|")})$\"]"
        val parts = boxes.map { b ->
            filter + stringFormat("(%.6f,%.6f,%.6f,%.6f);", b.minLat, b.minLon, b.maxLat, b.maxLon)
        }
        return "[out:json][timeout:60];(${parts.joinToString("")});out geom;"
    }

    class ServerError(override val message: String) : Exception(message)

    /** Road centre lines from an Overpass `out geom` JSON response. Throws `ServerError` when the server stopped
     *  early, and `SerializationException` when the answer isn't one (Swift's `DecodingError`). */
    fun parse(data: ByteArray): List<List<GeoPoint>> {
        // Read as Swift's `JSONDecoder` reads {"elements": [{"type", "geometry": [{"lat", "lon"} or null]?}],
        // "remark"?}: a missing or mistyped value throws.
        val root = decodeObject(SwiftJson.parse(data))
        val elements = decodeArray(root["elements"]).map { element ->
            val e = decodeObject(element)
            val type = decodeString(e["type"])
            val geometry = e["geometry"].let { if (it == null || it is SwiftJson.Null) emptyList() else decodeArray(it) }
            type to geometry.map { node ->
                if (node is SwiftJson.Null) null else decodeObject(node).let {
                    GeoPoint(lat = decodeNumber(it["lat"]), lon = decodeNumber(it["lon"]))
                }
            }
        }
        // Set when the server stopped early (timeout, out of memory); the elements are then incomplete.
        val remark = root["remark"].let { if (it == null || it is SwiftJson.Null) null else decodeString(it) }
        if (remark != null && foundationContains(remark.lowercase(), "error")) {
            throw ServerError(message = remark)
        }
        val lines = mutableListOf<List<GeoPoint>>()
        for ((type, geometry) in elements) {
            if (type != "way") continue
            var run = mutableListOf<GeoPoint>()
            for (node in geometry) {
                if (node != null) {
                    run.add(node)
                } else {
                    if (run.size >= 2) lines.add(run)
                    run = mutableListOf()
                }
            }
            if (run.size >= 2) lines.add(run)
        }
        return lines
    }

    private fun decodeObject(json: SwiftJson?): SwiftJson.Obj =
        json as? SwiftJson.Obj ?: throw SerializationException("Expected an object, found $json")

    private fun decodeArray(json: SwiftJson?): List<SwiftJson> =
        (json as? SwiftJson.Arr)?.items ?: throw SerializationException("Expected an array, found $json")

    private fun decodeString(json: SwiftJson?): String =
        (json as? SwiftJson.Str)?.string() ?: throw SerializationException("Expected a string, found $json")

    private fun decodeNumber(json: SwiftJson?): Double =
        (json as? SwiftJson.Num)?.double() ?: throw SerializationException("Expected a number, found $json")

    /** Swift's `contains(_:)` with Foundation (NSString's `range(of:)`) for an ASCII word: a match may start anywhere,
     *  but not end inside a character, before a mark that belongs to its last letter ("erroŕ", with a combining
     *  accent, doesn't contain "error"; with a zero-width joiner or a spacing mark after it, it does). */
    private fun foundationContains(text: String, word: String): Boolean {
        var i = text.indexOf(word)
        while (i >= 0) {
            val end = i + word.length
            if (end == text.length || !extendsCharacter(text.codePointAt(end))) return true
            i = text.indexOf(word, i + 1)
        }
        return false
    }

    /** Unicode's grapheme cluster break Extend: a mark (Mn, Me), one of the other grapheme extenders, or an emoji skin
     *  tone. */
    private fun extendsCharacter(c: Int): Boolean {
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
            otherExtenders.any { c in it }
    }

    /** Other_Grapheme_Extend (Unicode 15) and the emoji modifiers. */
    private val otherExtenders = listOf(
        0x09BE..0x09BE, 0x09D7..0x09D7, 0x0B3E..0x0B3E, 0x0B57..0x0B57, 0x0BBE..0x0BBE, 0x0BD7..0x0BD7,
        0x0CC2..0x0CC2, 0x0CD5..0x0CD6, 0x0D3E..0x0D3E, 0x0D57..0x0D57, 0x0DCF..0x0DCF, 0x0DDF..0x0DDF,
        0x1B35..0x1B35, 0x200C..0x200C, 0x302E..0x302F, 0xFF9E..0xFF9F, 0x1133E..0x1133E, 0x11357..0x11357,
        0x114B0..0x114B0, 0x114BD..0x114BD, 0x115AF..0x115AF, 0x11930..0x11930, 0x1D165..0x1D165,
        0x1D16E..0x1D172, 0x1F3FB..0x1F3FF, 0xE0020..0xE007F,
    )

    /** The parts of the roads inside the area: each road is checked every 10 m or less against the area's
     *  tiles at zoom 21 (about 15 m), and the result is simplified to within 1 m. */
    fun clip(roads: List<List<GeoPoint>>, area: CoverageShape): List<List<GeoPoint>> {
        val z = 21
        val coverage = TileCoverage(shape = area, zoom = z)
        return parts(roads.filter { it.size >= 2 }, step = 10.0) { coverage.contains(TileID.containing(it, z = z)) }
    }

    /** The parts of the lines where `keep` holds, checked every `step` metres or less and simplified to within 1 m.
     *  A one-point line is kept whole or not at all. */
    private fun parts(lines: List<List<GeoPoint>>, step: Double, keep: (GeoPoint) -> Boolean): List<List<GeoPoint>> {
        fun lerp(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint =
            GeoPoint(lat = a.lat + (b.lat - a.lat) * t, lon = a.lon + (b.lon - a.lon) * t)

        val out = mutableListOf<List<GeoPoint>>()
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.size == 1) {
                if (keep(line[0])) out.add(line)
                continue
            }
            var run = mutableListOf<GeoPoint>()
            fun flush() {
                if (run.size >= 2) out.add(simplify(run, toleranceMeters = 1.0))
                run = mutableListOf()
            }
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val b = line[i + 1]
                val n = max(1, ceil(Geo.distance(a, b) / step).toInt())
                for (k in 0 until n) {
                    if (keep(lerp(a, b, (k.toDouble() + 0.5) / n.toDouble()))) {
                        if (run.isEmpty()) run.add(lerp(a, b, k.toDouble() / n.toDouble()))
                        run.add(lerp(a, b, (k + 1).toDouble() / n.toDouble()))
                    } else {
                        flush()
                    }
                }
            }
            flush()
        }
        return out
    }

    /** The area to download imagery for: every road with `corridorMeters` on each side (a one-point line: that far
     *  around its point, as a file's dots are saved along roads). */
    fun corridor(roads: List<List<GeoPoint>>): CoverageShape = CoverageShape(lines = roads, bufferMeters = corridorMeters)

    /** Total length in metres. */
    fun length(roads: List<List<GeoPoint>>): Double =
        roads.fold(0.0) { total, line ->
            line.zipWithNext().fold(total) { sum, (a, b) -> sum + Geo.distance(a, b) }
        }

    /** The recorded track as lines (a new line wherever the dotted line breaks), simplified to within 2 m, for
     *  downloading imagery along where you have been. A lone point stays a one-point line (its corridor is a square). */
    fun trackLines(track: List<TrackPoint>): List<List<GeoPoint>> {
        val lines = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        for (p in track) {
            if (p.startsSegment && current.isNotEmpty()) {
                lines.add(current)
                current = mutableListOf()
            }
            current.add(p.geo)
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.map { simplify(it, toleranceMeters = 2.0) }
    }

    /** How close a part of an earlier track must be to the latest one to count as the same path (both lie within 2 m
     *  of where you were, after simplifying). */
    const val sameTrackMeters = 5.0

    /** The lines of the area along your track after an update: the latest track plus the parts of the earlier lines
     *  away from it (the track may have been cleared since), so imagery saved before stays part of the area. */
    fun mergeTrack(previous: List<List<GeoPoint>>, latest: List<List<GeoPoint>>): List<List<GeoPoint>> {
        if (previous.isEmpty()) return latest
        if (latest.isEmpty()) return previous
        return latest + newParts(previous, others = latest)
    }

    /** The parts of `lines` farther than `sameTrackMeters` from every one of `others` (checked every 5 m or less). */
    fun newParts(lines: List<List<GeoPoint>>, others: List<List<GeoPoint>>): List<List<GeoPoint>> {
        if (others.isEmpty()) return lines
        val near = NearLines(others, meters = sameTrackMeters)
        return parts(lines, step = 5.0) { !near.contains(it) }
    }

    /** Douglas–Peucker in local metres around the line's first point. */
    private fun simplify(line: List<GeoPoint>, toleranceMeters: Double): List<GeoPoint> {
        if (line.size <= 2) return line
        val o = line[0]
        val mx = 111_320.0 * cos(o.lat * PI / 180)
        val my = 110_574.0
        val planar = line.map { XY(x = (it.lon - o.lon) * mx, y = (it.lat - o.lat) * my) }
        return Simplify.douglasPeucker(planar, tolerance = toleranceMeters).map {
            GeoPoint(lat = o.lat + it.y / my, lon = o.lon + it.x / mx)
        }
    }
}

/** Whether a point is within some metres of a set of lines, quick for long tracks: the segments are filed under the
 *  zoom-16 tiles (about 600 m) they come near. */
private class NearLines(lines: List<List<GeoPoint>>, private val meters: Double) {
    private val cells = HashMap<Long, MutableList<Pair<GeoPoint, GeoPoint>>>()

    init {
        for (line in lines) {
            if (line.isEmpty()) continue
            val segments = if (line.size == 1) listOf(line[0] to line[0]) else line.zipWithNext()
            for ((a, b) in segments) {
                val dLat = meters / 110_574
                val dLon = meters / (111_320 * max(0.01, cos(max(abs(a.lat), abs(b.lat)) * PI / 180)))
                val nw = TileID.containing(GeoPoint(lat = max(a.lat, b.lat) + dLat, lon = min(a.lon, b.lon) - dLon), z = z)
                val se = TileID.containing(GeoPoint(lat = min(a.lat, b.lat) - dLat, lon = max(a.lon, b.lon) + dLon), z = z)
                for (y in nw.y..se.y) {
                    for (x in nw.x..se.x) cells.getOrPut(key(x, y)) { mutableListOf() }.add(a to b)
                }
            }
        }
    }

    fun contains(p: GeoPoint): Boolean {
        val t = TileID.containing(p, z = z)
        val segments = cells[key(t.x, t.y)] ?: return false
        // Local metres around p.
        val mx = 111_320 * cos(p.lat * PI / 180)
        val my = 110_574.0
        for ((a, b) in segments) {
            val ax = (a.lon - p.lon) * mx
            val ay = (a.lat - p.lat) * my
            val bx = (b.lon - p.lon) * mx
            val by = (b.lat - p.lat) * my
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val s = if (len2 > 0) min(1.0, max(0.0, -(ax * dx + ay * dy) / len2)) else 0.0
            val cx = ax + s * dx
            val cy = ay + s * dy
            if (cx * cx + cy * cy <= meters * meters) return true
        }
        return false
    }

    companion object {
        private const val z = 16

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or y.toLong()
    }
}

/** Google's encoded polyline format (5 decimals, about 1 m): a compact text form for road lines. */
object Polyline {
    fun encode(points: List<GeoPoint>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e5).rounded().toLong()
            val lon = (p.lon * 1e5).rounded().toLong()
            for (delta in longArrayOf(lat - lastLat, lon - lastLon)) {
                var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
                while (v >= 0x20) {
                    out.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
                    v = v shr 5
                }
                out.append((v + 63).toInt().toChar())
            }
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    /** Null if the text isn't a valid polyline. */
    fun decode(text: String): List<GeoPoint>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var i = 0
        fun next(): Long? {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = (bytes[i].toInt() and 0xff) - 63
                i += 1
                if (!(b >= 0 && b < 64 && shift < 60)) return null
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) return if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
            }
            return null
        }
        val points = mutableListOf<GeoPoint>()
        var lat = 0L
        var lon = 0L
        while (i < bytes.size) {
            val dLat = next() ?: return null
            val dLon = next() ?: return null
            lat += dLat
            lon += dLon
            points.add(GeoPoint(lat = lat.toDouble() / 1e5, lon = lon.toDouble() / 1e5))
        }
        return points
    }
}

/** Roads shipped with the app for a place (e.g. every road in Kruger National Park), with the place's boundary.
 *  Stored as JSON: {"name", "note", "boundary": [polyline], "roads": [polyline]}. */
data class RoadPreset(
    val name: String,
    /** Where the roads come from and when, shown to the user. */
    val note: String,
    /** Outer rings of the place. */
    val boundary: List<List<GeoPoint>>,
    val roads: List<List<GeoPoint>>,
) {
    class Invalid : Exception("Not a road preset")

    companion object {
        /** Reads the JSON file (Swift's `init(json:)`); throws `Invalid` when a polyline doesn't decode or there is no
         *  boundary, and `SerializationException` when the file isn't one. */
        operator fun invoke(json: ByteArray): RoadPreset {
            val f = SwiftJson.parse(json) as? SwiftJson.Obj ?: throw SerializationException("Expected an object")
            val name = string(f["name"])
            val note = string(f["note"])
            val fileBoundary = strings(f["boundary"])
            val fileRoads = strings(f["roads"])
            val boundary = fileBoundary.mapNotNull(Polyline::decode)
            val roads = fileRoads.mapNotNull(Polyline::decode)
            if (!(boundary.size == fileBoundary.size && roads.size == fileRoads.size && boundary.isNotEmpty())) throw Invalid()
            return RoadPreset(name = name, note = note, boundary = boundary, roads = roads)
        }

        private fun string(json: SwiftJson?): String =
            (json as? SwiftJson.Str)?.string() ?: throw SerializationException("Expected a string, found $json")

        private fun strings(json: SwiftJson?): List<String> =
            ((json as? SwiftJson.Arr)?.items ?: throw SerializationException("Expected an array, found $json")).map(::string)
    }
}
