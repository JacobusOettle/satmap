package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The area to make available offline: filled polygons, lines and points, each grown by a buffer.
 *  A lasso-drawn area is a single polygon; a KML file contributes all of its geometry. */
data class CoverageShape(
    val polygons: List<List<GeoPoint>> = emptyList(),
    val lines: List<List<GeoPoint>> = emptyList(),
    val points: List<GeoPoint> = emptyList(),
    val bufferMeters: Double = 0.0,
) {
    val isEmpty: Boolean
        get() = polygons.all { it.size < 3 } && lines.all { it.isEmpty() } && points.isEmpty()

    /** Bounds of the geometry, without the buffer. */
    val geometryBounds: GeoBounds?
        get() = GeoBounds(points = polygons.flatten() + lines.flatten() + points)

    /** Bounds including the buffer. */
    val bounds: GeoBounds?
        get() = geometryBounds?.expanded(bufferMeters)

    /** Area of the filled polygons in square metres (lines/points are not counted). */
    val polygonAreaSquareMeters: Double
        get() = polygons.fold(0.0) { total, ring -> total + Geo.ringArea(ring) }

    /** The Swift `Codable` form, `{"polygons":[[{"lat":…,"lon":…}]],"lines":[…],"points":[…],"bufferMeters":…}`,
     *  so saved areas (and the area in a backup) read the same on both platforms. */
    fun toJson(): JsonObject = buildJsonObject {
        put("polygons", buildJsonArray { polygons.forEach { add(pointsJson(it)) } })
        put("lines", buildJsonArray { lines.forEach { add(pointsJson(it)) } })
        put("points", pointsJson(points))
        put("bufferMeters", bufferMeters)
    }

    companion object {
        /** Reads `toJson()`'s form; throws if it isn't one. */
        fun fromJson(json: JsonElement): CoverageShape {
            val o = json.jsonObject
            return CoverageShape(
                polygons = o.getValue("polygons").jsonArray.map(::pointsFromJson),
                lines = o.getValue("lines").jsonArray.map(::pointsFromJson),
                points = pointsFromJson(o.getValue("points")),
                bufferMeters = o.getValue("bufferMeters").jsonPrimitive.double,
            )
        }

        private fun pointsJson(points: List<GeoPoint>): JsonArray = buildJsonArray { points.forEach { add(it.toJson()) } }

        private fun pointsFromJson(json: JsonElement): List<GeoPoint> = json.jsonArray.map(GeoPoint::fromJson)
    }
}

/** The exact set of tiles at one zoom level that intersect a `CoverageShape`,
 *  stored as merged inclusive x-ranges per tile row so that huge areas stay cheap. */
class TileCoverage(shape: CoverageShape, val zoom: Int) {
    data class Row(val y: Int, val ranges: List<IntRange>)

    /** Rows sorted by y; each row's ranges are sorted and non-overlapping. */
    val rows: List<Row>

    /** A `Long`: a large area at the deepest zooms has more tiles than an `Int` holds. */
    val tileCount: Long

    /** An x-extent in tile units. */
    private class Extent(val lo: Double, val hi: Double)

    /** A polygon edge crossing a horizontal line, and which way it goes. */
    private class Crossing(val x: Double, val dir: Int)

    init {
        if (shape.isEmpty) {
            rows = emptyList()
            tileCount = 0
        } else {
            val n = (1 shl zoom).toDouble()
            val maxIndex = (1 shl zoom) - 1

            /** The buffer in tiles at a latitude. Each piece uses its own (at the largest |latitude| it reaches, so it
             *  is never narrower than asked): one shape can hold places far apart, e.g. trips months apart in one
             *  track area. */
            fun bufAt(lat: Double): Double =
                // Clamped as the map is: at the poles the buffer would cover the whole world.
                max(0.0, shape.bufferMeters) / Geo.metersPerWorldUnit(min(abs(lat), WebMercator.maxLatitude)) * n

            /** Per row, x-extents already grown by their buffer. */
            val extents = HashMap<Int, MutableList<Extent>>()

            fun add(row: Int, x0: Double, x1: Double, buf: Double) {
                if (row < 0 || row > maxIndex) return
                extents.getOrPut(row) { mutableListOf() }.add(Extent(min(x0, x1) - buf, max(x0, x1) + buf))
            }

            fun toTile(p: GeoPoint): XY {
                val w = WebMercator.world(p)
                return XY(w.x * n, w.y * n)
            }

            fun rowSpan(minY: Double, maxY: Double, buf: Double): IntRange? {
                val r0 = max(0, floor(minY - buf).toInt())
                val r1 = min(maxIndex, floor(maxY + buf).toInt())
                return if (r0 <= r1) r0..r1 else null
            }

            /** x-extent of segment p→q clipped to the horizontal band [top, bottom]. */
            fun clip(p: XY, q: XY, top: Double, bottom: Double): Extent? {
                if (max(p.y, q.y) < top || min(p.y, q.y) > bottom) return null
                if (p.y == q.y) return Extent(min(p.x, q.x), max(p.x, q.x))
                val tA = (top - p.y) / (q.y - p.y)
                val tB = (bottom - p.y) / (q.y - p.y)
                val lo = max(0.0, min(tA, tB))
                val hi = min(1.0, max(tA, tB))
                if (lo > hi) return null
                val xa = p.x + lo * (q.x - p.x)
                val xb = p.x + hi * (q.x - p.x)
                return Extent(min(xa, xb), max(xa, xb))
            }

            // Filled polygons: the x-projection of (polygon ∩ band) is the union of the clipped edges'
            // extents and the polygon's interior spans along the band's top and bottom lines.
            // Interior uses the non-zero winding rule, so a hand-drawn lasso that loops past its start
            // still covers the doubly-encircled part (as it looks on screen).
            for (ring in shape.polygons) {
                if (ring.size < 3) continue
                val buf = bufAt(ring.maxOf { abs(it.lat) })
                val pts = ring.map(::toTile).toMutableList()
                if (pts.first().x != pts.last().x || pts.first().y != pts.last().y) pts.add(pts.first())
                val ys = pts.map { it.y }
                val span = rowSpan(ys.min(), ys.max(), buf) ?: continue
                for (row in span) {
                    val top = row.toDouble() - buf
                    val bottom = (row + 1).toDouble() + buf
                    for (i in 0 until pts.size - 1) {
                        clip(pts[i], pts[i + 1], top, bottom)?.let { add(row, it.lo, it.hi, buf) }
                    }
                    for (lineY in listOf(top, bottom)) {
                        val crossings = mutableListOf<Crossing>()
                        for (i in 0 until pts.size - 1) {
                            val p = pts[i]
                            val q = pts[i + 1]
                            if ((p.y > lineY) != (q.y > lineY)) {
                                crossings.add(Crossing(p.x + (lineY - p.y) / (q.y - p.y) * (q.x - p.x), if (q.y > p.y) 1 else -1))
                            }
                        }
                        crossings.sortBy { it.x }
                        var winding = 0
                        var spanStart = 0.0
                        for (c in crossings) {
                            val before = winding
                            winding += c.dir
                            if (before == 0 && winding != 0) {
                                spanStart = c.x
                            } else if (before != 0 && winding == 0) {
                                add(row, spanStart, c.x, buf)
                            }
                        }
                    }
                }
            }

            for (line in shape.lines) {
                if (line.isEmpty()) continue
                val pts = line.map(::toTile)
                if (pts.size == 1) {
                    val buf = bufAt(line[0].lat)
                    rowSpan(pts[0].y, pts[0].y, buf)?.let { span ->
                        for (row in span) add(row, pts[0].x, pts[0].x, buf)
                    }
                    continue
                }
                for (i in 0 until pts.size - 1) {
                    val p = pts[i]
                    val q = pts[i + 1]
                    val buf = bufAt(max(abs(line[i].lat), abs(line[i + 1].lat)))
                    val span = rowSpan(min(p.y, q.y), max(p.y, q.y), buf) ?: continue
                    for (row in span) {
                        clip(p, q, row.toDouble() - buf, (row + 1).toDouble() + buf)?.let { add(row, it.lo, it.hi, buf) }
                    }
                }
            }

            for (point in shape.points) {
                val p = toTile(point)
                val buf = bufAt(point.lat)
                rowSpan(p.y, p.y, buf)?.let { span ->
                    for (row in span) add(row, p.x, p.x, buf)
                }
            }

            val builtRows = mutableListOf<Row>()
            var count = 0L
            for (y in extents.keys.sorted()) {
                val ranges = extents.getValue(y).map { e ->
                    val start = max(0, floor(e.lo).toInt())
                    val end = min(maxIndex, max(start, ceil(e.hi).toInt() - 1))
                    start..max(start, end)
                }.sortedBy { it.first }
                val merged = mutableListOf<IntRange>()
                for (r in ranges) {
                    val last = merged.lastOrNull()
                    if (last != null && r.first <= last.last + 1) {
                        merged[merged.size - 1] = last.first..max(last.last, r.last)
                    } else {
                        merged.add(r)
                    }
                }
                count += merged.sumOf { it.size }
                builtRows.add(Row(y, merged))
            }
            rows = builtRows
            tileCount = count
        }
    }

    /** How many of these tiles are not in `other` (same zoom): for what a download adds to an area. */
    fun count(other: TileCoverage): Long {
        require(zoom == other.zoom)
        val otherRows = HashMap<Int, List<IntRange>>()
        for (r in other.rows) otherRows[r.y] = r.ranges
        var out = 0L
        for (row in rows) {
            val theirs = otherRows[row.y]
            if (theirs == null) {
                out += row.ranges.sumOf { it.size }
                continue
            }
            for (r in row.ranges) {
                var covered = 0L
                for (o in theirs) {
                    if (o.last >= r.first && o.first <= r.last) covered += min(o.last, r.last) - max(o.first, r.first) + 1
                }
                out += r.size - covered
            }
        }
        return out
    }

    fun contains(x: Int, y: Int): Boolean {
        var lo = 0
        var hi = rows.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = rows[mid]
            if (r.y == y) {
                return rangesContain(r.ranges, x)
            } else if (r.y < y) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return false
    }

    fun contains(tile: TileID): Boolean = tile.z == zoom && contains(x = tile.x, y = tile.y)

    inline fun forEachTile(body: (TileID) -> Unit) {
        for (row in rows) {
            for (r in row.ranges) {
                for (x in r) body(TileID(z = zoom, x = x, y = row.y))
            }
        }
    }

    companion object {
        /** Upper-bound tile counts per zoom for a shape (before any "imagery doesn't exist here" pruning). */
        fun tileCounts(shape: CoverageShape, zooms: IntRange): Map<Int, Long> {
            val out = LinkedHashMap<Int, Long>()
            for (z in zooms) out[z] = TileCoverage(shape = shape, zoom = z).tileCount
            return out
        }

        private fun rangesContain(ranges: List<IntRange>, x: Int): Boolean {
            var lo = 0
            var hi = ranges.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val r = ranges[mid]
                if (x in r) return true
                if (x < r.first) hi = mid - 1 else lo = mid + 1
            }
            return false
        }

        /** The number of tiles in a range (Swift's `ClosedRange.count`; Kotlin's `count()` walks the range). */
        private val IntRange.size: Long get() = (last - first + 1).toLong()
    }
}
