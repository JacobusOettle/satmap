package com.jacobusoettle.satmap.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** Which contours to draw at each map scale.
 *
 *  On iOS, MapKit asks for 256-pixel tiles, so on a 2x/3x screen it requests tiles 1–1.6 zoom levels deeper than a
 *  web map would show at the same on-screen scale. Intervals are therefore chosen from the "point zoom" (the web-map
 *  zoom with 256-point tiles), so lines are equally dense on every phone. */
object ContourPolicy {
    /** (interval between lines, interval between labelled index lines) in metres. */
    data class Intervals(val step: Double, val index: Double)

    /** Tile zooms below this never get contours (MapKit's overlay minimum). */
    const val minTileZoom = 11

    /** Web-equivalent zoom of a tile zoom requested with 256-pixel tiles on a screen of `screenScale`. */
    fun pointZoom(tileZoom: Int, screenScale: Double): Double =
        tileZoom.toDouble() - log2(max(1.0, screenScale))

    /** (interval between lines, interval between labelled index lines) in metres, or null for no contours. */
    fun intervals(forPointZoom: Double): Intervals? {
        val zp = forPointZoom
        return when {
            zp < 11 -> null
            zp < 12 -> Intervals(100.0, 500.0)
            zp < 13 -> Intervals(50.0, 250.0)
            zp < 14 -> Intervals(20.0, 100.0)
            zp < 16.5 -> Intervals(10.0, 50.0)
            else -> Intervals(5.0, 25.0)
        }
    }

    /** Elevation tile zoom used to draw a map tile at zoom z. */
    fun demZoom(forTileZoom: Int): Int =
        min(forTileZoom, Terrarium.maxZoom)

    /** Number of grid cells across a 256-pixel map tile: one per 2 pixels is smooth on screen and finer than the
     *  elevation data itself (about 30 m SRTM in most of Africa). */
    @Suppress("UNUSED_PARAMETER")
    fun gridCells(forTileZoom: Int): Int =
        128
}

data class ContourLine(
    var level: Double,
    /** Points in grid-node units: x ∈ [0, width−1], y ∈ [0, height−1], y grows downwards. */
    var points: List<XY>,
    var isClosed: Boolean,
)

object MarchingSquares {
    /** A chain of edge keys, and whether it closes on itself: the Swift `(keys: [Int], closed: Bool)` tuple. */
    data class Chain(val keys: List<Int>, val closed: Boolean)

    /** Traces iso-lines at every multiple of `step` through a `width`×`height` grid of node values
     *  (row-major, row 0 at the top) and joins cell segments into polylines. */
    fun contours(values: FloatArray, width: Int, height: Int, step: Double): List<ContourLine> {
        if (!(width >= 2 && height >= 2 && values.size == width * height && step > 0)) return emptyList()
        // Terrarium elevations are multiples of 1/256 m and often whole metres, so grid values frequently equal
        // a contour level exactly, which yields degenerate or diagonal segments. Tracing each level a fraction
        // of a millimetre higher (not a multiple of 1/256) avoids that without any visible shift.
        val offset = 0.000_7

        // Segments per level index; each segment joins two grid edges identified by integer keys:
        // horizontal edge from node (i,j) to (i+1,j): (j*width+i)*2; vertical edge from (i,j) to (i,j+1): (j*width+i)*2+1.
        val segments = HashMap<Int, MutableList<Pair<Int, Int>>>()

        for (j in 0 until height - 1) {
            for (i in 0 until width - 1) {
                val tl = values[j * width + i].toDouble()
                val tr = values[j * width + i + 1].toDouble()
                val bl = values[(j + 1) * width + i].toDouble()
                val br = values[(j + 1) * width + i + 1].toDouble()
                val lo = min(min(tl, tr), min(bl, br))
                val hi = max(max(tl, tr), max(bl, br))
                if (!(hi > lo)) continue
                val kLo = ceil((lo - offset) / step).toInt()
                val kHi = floor((hi - offset) / step).toInt()
                if (!(kLo <= kHi)) continue

                val top = (j * width + i) * 2
                val bottom = ((j + 1) * width + i) * 2
                val left = (j * width + i) * 2 + 1
                val right = (j * width + i + 1) * 2 + 1

                for (k in kLo..kHi) {
                    val level = k.toDouble() * step + offset
                    val a = tl >= level
                    val b = tr >= level
                    val c = br >= level
                    val d = bl >= level
                    val crossed = ArrayList<Int>(4)
                    if (a != b) crossed.add(top)
                    if (b != c) crossed.add(right)
                    if (d != c) crossed.add(bottom)
                    if (a != d) crossed.add(left)
                    if (crossed.size == 2) {
                        segments.getOrPut(k) { ArrayList() }.add(Pair(crossed[0], crossed[1]))
                    } else if (crossed.size == 4) {
                        // Saddle: resolve with the cell-centre value.
                        val centreAbove = (tl + tr + bl + br) / 4 >= level
                        // a == c here (diagonal corners agree).
                        if (a == centreAbove) {
                            // tl/br corners connect through the centre; cut off tr and bl.
                            segments.getOrPut(k) { ArrayList() }.add(Pair(top, right))
                            segments.getOrPut(k) { ArrayList() }.add(Pair(left, bottom))
                        } else {
                            // tr/bl corners connect; cut off tl and br.
                            segments.getOrPut(k) { ArrayList() }.add(Pair(top, left))
                            segments.getOrPut(k) { ArrayList() }.add(Pair(right, bottom))
                        }
                    }
                }
            }
        }

        val lines = ArrayList<ContourLine>()
        for (k in segments.keys.sorted()) {
            val level = k.toDouble() * step + offset
            val segs = segments.getValue(k)
            fun position(key: Int): XY {
                val node = key shr 1
                val i = node % width
                val j = node / width
                val v0 = values[node].toDouble()
                if (key and 1 == 0) {
                    val v1 = values[node + 1].toDouble()
                    val t = if (v1 == v0) 0.5 else (level - v0) / (v1 - v0)
                    return XY(i.toDouble() + t, j.toDouble())
                } else {
                    val v1 = values[node + width].toDouble()
                    val t = if (v1 == v0) 0.5 else (level - v0) / (v1 - v0)
                    return XY(i.toDouble(), j.toDouble() + t)
                }
            }
            for (chain in join(segs)) {
                val pts = chain.keys.map(::position)
                if (!(ContourGeometry.length(pts) > 0.01)) continue
                lines.add(ContourLine(level = k.toDouble() * step, points = pts, isClosed = chain.closed))
            }
        }
        return lines
    }

    /** Joins segments (pairs of edge keys) into chains of edge keys. */
    fun join(segs: List<Pair<Int, Int>>): List<Chain> {
        // Edge key -> up to two segment indices (-1 = none). In the order the keys first appear (Swift's dictionary
        // order is random, so on iOS the open chains come in any order and from either end).
        val adjacency = LinkedHashMap<Int, Pair<Int, Int>>(segs.size * 2)
        for ((idx, s) in segs.withIndex()) {
            for (key in listOf(s.first, s.second)) {
                val e = adjacency[key]
                if (e != null) {
                    if (e.second == -1) adjacency[key] = Pair(e.first, idx)
                } else {
                    adjacency[key] = Pair(idx, -1)
                }
            }
        }
        val used = BooleanArray(segs.size)
        val chains = ArrayList<Chain>()

        fun walk(startKey: Int, startSeg: Int): Chain {
            val keys = arrayListOf(startKey)
            var key = startKey
            var seg = startSeg
            while (true) {
                used[seg] = true
                val s = segs[seg]
                val next = if (s.first == key) s.second else s.first
                keys.add(next)
                if (next == startKey) return Chain(keys, true)
                val e = adjacency[next] ?: break
                val candidate = if (e.first == seg) e.second else e.first
                if (candidate < 0 || used[candidate]) break
                key = next
                seg = candidate
            }
            return Chain(keys, false)
        }

        // Open chains first, starting from edge keys used by exactly one segment.
        for ((key, e) in adjacency) {
            if (e.second == -1 && !used[e.first]) chains.add(walk(startKey = key, startSeg = e.first))
        }
        // Remaining segments form closed loops.
        for (idx in segs.indices) {
            if (!used[idx]) chains.add(walk(startKey = segs[idx].first, startSeg = idx))
        }
        return chains
    }
}

object ContourGeometry {
    /** The width and height a label must keep within: the Swift `(w:, h:)` tuple. */
    data class Bounds(val w: Double, val h: Double)

    /** A label's position and upright text angle (radians): the Swift `(x:, y:, angle:)` tuple. */
    data class Anchor(val x: Double, val y: Double, val angle: Double)

    /** One pass of Chaikin corner cutting; keeps the end points of open lines. */
    fun chaikin(pts: List<XY>, closed: Boolean): List<XY> {
        if (pts.size < 3) return pts
        val out = ArrayList<XY>(pts.size * 2)
        val n = pts.size
        if (!closed) out.add(pts[0])
        val last = if (closed) n - 1 else n - 2
        for (i in 0 until last + 1) {
            val p = pts[i]
            val q = pts[(i + 1) % n]
            if (closed && i == n - 1 && p.x == pts[0].x && p.y == pts[0].y) continue
            out.add(XY(0.75 * p.x + 0.25 * q.x, 0.75 * p.y + 0.25 * q.y))
            out.add(XY(0.25 * p.x + 0.75 * q.x, 0.25 * p.y + 0.75 * q.y))
        }
        if (!closed) out.add(pts[n - 1]) else out.firstOrNull()?.let { out.add(it) }
        return out
    }

    fun length(pts: List<XY>): Double {
        if (pts.size <= 1) return 0.0
        var total = 0.0
        for (i in 1 until pts.size) total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return total
    }

    /** A label position at the middle of a line, with an upright text angle (radians) following the line.
     *  Returns null if the line is shorter than `minLength` or the label would fall within `margin` of the bounds. */
    fun labelAnchor(pts: List<XY>, minLength: Double, bounds: Bounds, margin: Double): Anchor? {
        val total = length(pts)
        if (!(total >= minLength)) return null
        val target = total / 2
        var run = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (run + seg >= target && seg > 0) {
                val t = (target - run) / seg
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                if (!(x >= margin && y >= margin && x <= bounds.w - margin && y <= bounds.h - margin)) return null
                // Direction from points roughly ±minLength/6 along the line, for a stable angle.
                val window = minLength / 6
                val p0 = point(pts, at = max(0.0, target - window))
                val p1 = point(pts, at = min(total, target + window))
                var angle = atan2(p1.y - p0.y, p1.x - p0.x)
                if (angle > PI / 2) angle -= PI
                if (angle < -PI / 2) angle += PI
                return Anchor(x, y, angle)
            }
            run += seg
        }
        return null
    }

    fun point(pts: List<XY>, at: Double): XY {
        val distance = at
        var run = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (run + seg >= distance && seg > 0) {
                val t = (distance - run) / seg
                return XY(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            run += seg
        }
        return pts[pts.size - 1]
    }
}
