package com.jacobusoettle.satmap.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the terrain button shows over the imagery: contour lines, the heights in colour, both, the places pilots and
 *  drone operators go by (drone mode: aerodromes, strips, helipads and navaids), or none of these. Each tap moves on
 *  to the next. */
enum class TerrainMode {
    contours,
    contoursAndColours,
    colours,
    drone,
    off;

    /** The saved form ("contours", "contoursAndColours", "colours", "drone", "off"), as on iOS. */
    val rawValue: String get() = name

    val next: TerrainMode
        get() = when (this) {
            contours -> contoursAndColours
            contoursAndColours -> colours
            colours -> drone
            drone -> off
            off -> contours
        }

    val showsContours: Boolean get() = this == contours || this == contoursAndColours
    val showsColours: Boolean get() = this == colours || this == contoursAndColours
    val showsAviation: Boolean get() = this == drone

    val title: String
        get() = when (this) {
            contours -> "Contour lines"
            contoursAndColours -> "Contour lines and elevation colours"
            colours -> "Elevation colours"
            drone -> "Drone mode: airfields and navaids"
            off -> "Contours and colours off"
        }

    companion object {
        /** The mode saved as `rawValue`, or null (Swift's `TerrainMode(rawValue:)`). */
        operator fun invoke(rawValue: String): TerrainMode? = entries.firstOrNull { it.rawValue == rawValue }

        /** The mode saved under `terrainMode`, or from before there were colours (`showContours`, on unless set to
         *  off). */
        fun saved(mode: String?, showContours: Boolean?): TerrainMode {
            mode?.let { TerrainMode(it) }?.let { return it }
            return if (showContours == false) off else contours
        }
    }
}

/** The heights coloured from blue (`low` and below) to red (`high` and above). */
data class ElevationRange(val low: Double, val high: Double)

/** Heights at a tile's grid nodes, `nodes` × `nodes`, row-major from the north edge, the first and last row and column
 *  on the tile's edges (so neighbouring tiles share their edge nodes). A class so a drawn image can tell it is still
 *  for the same grid. */
class ElevationGrid(
    val nodes: Int,
    val values: FloatArray,
) {
    /** The lowest and highest heights: the Swift `(low:, high:)` tuple. */
    data class Extremes(val low: Float, val high: Float)

    /** The lowest and highest heights of the nodes inside a world rectangle (the tile's own `bounds`), leaving out
     *  the sea (below 0 m: the elevation tiles give the sea floor there). Null if no land node lies inside. */
    fun extremes(tile: WorldRect, within: WorldRect): Extremes? {
        val bounds = tile
        val r = within
        if (!(nodes >= 2 && values.size == nodes * nodes)) return null
        val step = (bounds.maxX - bounds.minX) / (nodes - 1).toDouble()
        if (!(step > 0)) return null
        val i0 = max(0, ceil((r.minX - bounds.minX) / step - 1e-9).toInt())
        val i1 = min(nodes - 1, floor((r.maxX - bounds.minX) / step + 1e-9).toInt())
        val j0 = max(0, ceil((r.minY - bounds.minY) / step - 1e-9).toInt())
        val j1 = min(nodes - 1, floor((r.maxY - bounds.minY) / step + 1e-9).toInt())
        if (!(i0 <= i1 && j0 <= j1)) return null
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (j in j0..j1) {
            val row = j * nodes
            for (i in i0..i1) {
                val v = values[row + i]
                if (!(v >= 0)) continue
                if (v < low) low = v
                if (v > high) high = v
            }
        }
        return if (low <= high) Extremes(low, high) else null
    }
}

/** The elevation colours over the imagery: each height between the range's low and high gets a colour from blue
 *  (lowest) through cyan, green, yellow and orange to red (highest), the range chosen from the heights on screen. */
object ElevationColours {
    /** A point on the colour ramp: the Swift `(position, red, green, blue)` tuple. */
    data class Stop(val position: Double, val red: Double, val green: Double, val blue: Double)

    /** A colour, each component 0…1: the Swift `(red:, green:, blue:)` tuple. */
    data class Colour(val red: Double, val green: Double, val blue: Double)

    /** Tiles of lower zooms get no colours (each needs a whole elevation tile of its own). */
    const val minTileZoom = 8
    /** Grid cells across a tile (its colours are drawn smoothly between the nodes). */
    const val cells = 64
    /** How much of the imagery shows through. */
    const val alpha = 0.5
    /** A range spans at least this much, so nearly flat ground isn't coloured from blue to red by noise in the data. */
    const val minimumSpan = 10.0

    /** (position, red, green, blue) */
    val stops: List<Stop> = listOf(
        Stop(0.0, 0.16, 0.32, 0.95),
        Stop(0.2, 0.10, 0.72, 0.97),
        Stop(0.4, 0.22, 0.80, 0.36),
        Stop(0.6, 0.97, 0.90, 0.20),
        Stop(0.8, 0.99, 0.55, 0.10),
        Stop(1.0, 0.90, 0.12, 0.12),
    )

    /** The colour at a place along the ramp (0: lowest, blue; 1: highest, red). */
    fun colour(at: Double): Colour {
        val f = at
        val x = min(1.0, max(0.0, if (f.isFinite()) f else 0.0))
        for (k in 1 until stops.size) {
            if (!(x <= stops[k].position)) continue
            val a = stops[k - 1]
            val b = stops[k]
            val t = (x - a.position) / (b.position - a.position)
            return Colour(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
        }
        val last = stops[stops.size - 1]
        return Colour(last.red, last.green, last.blue)
    }

    /** The range to colour with when the screen shows heights from `low` to `high`: the one shown (`current`) while
     *  they still fit in it and fill most of it (nearly flat ground: most of the least span), so panning around
     *  doesn't keep changing the colours; else one just around them, at round heights. While some tiles on screen have
     *  no heights yet (`complete` false), the range shown only grows. */
    fun range(low: Double, high: Double, current: ElevationRange?, complete: Boolean = true): ElevationRange {
        if (current != null) {
            val c = current
            val fits = low >= c.low && high <= c.high
            if (fits && (!complete || max(high - low, minimumSpan) >= 0.6 * (c.high - c.low))) return c
        }
        var lo = low
        var hi = high
        if (!complete && current != null) {
            val c = current
            lo = min(lo, c.low)
            hi = max(hi, c.high)
        }
        if (hi - lo < minimumSpan) {
            // Around the middle, but not below sea level for land above it (the sea isn't coloured).
            lo = (lo + hi) / 2 - minimumSpan / 2
            if (low >= 0) lo = max(0.0, lo)
            hi = lo + minimumSpan
        }
        val step = niceStep((hi - lo) / 10)
        return ElevationRange(low = floor(lo / step) * step, high = ceil(hi / step) * step)
    }

    /** 1, 2 or 5 times a power of ten, at least `x`. */
    fun niceStep(x: Double): Double {
        if (!(x > 0 && x.isFinite())) return 1.0
        val p = 10.0.pow(floor(log10(x)))
        for (m in listOf(1.0, 2.0, 5.0, 10.0)) {
            if (m * p >= x * (1 - 1e-9)) return m * p
        }
        return 10 * p
    }

    /** Premultiplied RGBA pixels (4 bytes each), one per grid node, with the sea (below 0 m) left clear. */
    fun pixels(grid: ElevationGrid, range: ElevationRange, alpha: Double = this.alpha): ByteArray {
        val table = lookupTable(alpha = alpha)
        val span = max(range.high - range.low, 1e-6)
        val scale = (255 / span).toFloat()
        val low = range.low.toFloat()
        val out = ByteArray(grid.values.size * 4)
        for ((n, v) in grid.values.withIndex()) {
            if (!(v >= 0)) continue
            val k = min(255f, max(0f, ((v - low) * scale).rounded())).toInt()
            out[n * 4] = table[k * 4]
            out[n * 4 + 1] = table[k * 4 + 1]
            out[n * 4 + 2] = table[k * 4 + 2]
            out[n * 4 + 3] = table[k * 4 + 3]
        }
        return out
    }

    /** 256 premultiplied RGBA colours along the ramp. */
    private fun lookupTable(alpha: Double): ByteArray {
        val a = min(1.0, max(0.0, alpha))
        val table = ByteArray(256 * 4)
        for (k in 0 until 256) {
            val c = colour(at = k.toDouble() / 255)
            table[k * 4] = (c.red * a * 255).rounded().toInt().toByte()
            table[k * 4 + 1] = (c.green * a * 255).rounded().toInt().toByte()
            table[k * 4 + 2] = (c.blue * a * 255).rounded().toInt().toByte()
            table[k * 4 + 3] = (a * 255).rounded().toInt().toByte()
        }
        return table
    }
}
