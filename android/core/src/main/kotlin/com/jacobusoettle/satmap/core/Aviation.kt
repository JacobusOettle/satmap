package com.jacobusoettle.satmap.core

import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** An aerodrome, landing strip, helipad or radio navigation aid (drone mode), from OurAirports
 *  (SatMap/Resources/Aviation.tsv, built by tools/build_aviation.py). */
data class AviationPoint(
    val kind: Kind,
    /** The ICAO code (or another code pilots use: "FASZ"), or a navaid's ident ("HGV"); may be empty. */
    val code: String,
    val name: String,
    val point: GeoPoint,
    /** Aerodromes: field elevation in feet, and the longest runway's heading (degrees true, 0–179) if known.
     *  Navaids: the frequency ("114.50 MHz"). */
    val elevationFeet: Int? = null,
    val runwayHeading: Int? = null,
    val frequency: String? = null,
) {
    enum class Kind(val rawValue: Char) {
        largeAirport('L'),
        mediumAirport('M'),
        smallAirport('S'),
        heliport('H'),
        seaplaneBase('W'),
        vor('V'),
        tacan('T'),
        dme('D'),
        ndb('B');

        val isAerodrome: Boolean
            get() = when (this) {
                largeAirport, mediumAirport, smallAirport, heliport, seaplaneBase -> true
                vor, tacan, dme, ndb -> false
            }

        /** Shown from this wide a view (km across the screen) down, so a zoomed-out map isn't crowded: airports
         *  with airline flights from far out, strips and VORs at region scale, helipads and NDBs close up. */
        val shownWithinKm: Double
            get() = when (this) {
                largeAirport -> 3_000.0
                mediumAirport -> 800.0
                vor, tacan -> 400.0
                smallAirport -> 200.0
                ndb, dme -> 120.0
                heliport, seaplaneBase -> 60.0
            }

        /** Kept first when labels collide (MapKit's display priority on the iPhone, 0–1000): below your own KML
         *  points (750). */
        val priority: Float
            get() = when (this) {
                largeAirport -> 740f
                mediumAirport -> 720f
                vor, tacan -> 650f
                smallAirport -> 600f
                ndb, dme -> 500f
                heliport, seaplaneBase -> 450f
            }

        /** "Airport", "Airstrip", "Helipad", "VOR" … */
        val title: String
            get() = when (this) {
                largeAirport, mediumAirport -> "Airport"
                smallAirport -> "Airstrip"
                heliport -> "Helipad"
                seaplaneBase -> "Seaplane base"
                vor -> "VOR"
                tacan -> "TACAN"
                dme -> "DME"
                ndb -> "NDB"
            }

        companion object {
            /** The kind written as `rawValue`, or null (Swift's `Kind(rawValue:)`). */
            operator fun invoke(rawValue: Char): Kind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** "FASZ Skukuza", the name without "Airport", "Aerodrome" … ; the name alone without a code. */
    val shortName: String
        get() {
            var n = name
            for (suffix in listOf(
                " International Airport", " Airport", " Aerodrome", " Airfield", " Airstrip", " Air Force Base",
                " Heliport", " Helipad",
            )) {
                if (!(n.endsWith(suffix) && characterCount(n) > suffix.length + 2)) continue
                n = n.dropLast(suffix.length)
                break
            }
            return n
        }

    val label: String
        get() = if (code.isEmpty()) shortName else "$code $shortName"

    companion object {
        /** Parses a line of Aviation.tsv (null for comments and lines it can't read). */
        operator fun invoke(line: String): AviationPoint? {
            val f = line.split('\t')
            if (f.size < 7) return null
            val k = f[1].firstOrNull() ?: return null
            val kind = Kind(rawValue = k) ?: return null
            val lat = parseDouble(f[4]) ?: return null
            val lon = parseDouble(f[5]) ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return if (f[0] == "A") {
                AviationPoint(
                    kind = kind, code = f[2], name = f[3], point = GeoPoint(lat = lat, lon = lon),
                    elevationFeet = parseInt(f[6]), runwayHeading = if (f.size > 7) parseInt(f[7]) else null, frequency = null,
                )
            } else {
                AviationPoint(
                    kind = kind, code = f[2], name = f[3], point = GeoPoint(lat = lat, lon = lon),
                    elevationFeet = null, runwayHeading = null, frequency = f[6].ifEmpty { null },
                )
            }
        }

        /** Swift's `Double(String)`: the whole text is the number (Kotlin's own also takes " 1.5" and "1.5d"). */
        private fun parseDouble(text: String): Double? {
            if (text.isEmpty() || text.first().isWhitespace() || text.last().isWhitespace() || text.last() in "fFdD") return null
            return text.toDoubleOrNull()
        }

        /** Swift's `Int(String)`: a sign and ASCII digits only (Kotlin's own also takes other scripts' digits). */
        private fun parseInt(text: String): Int? {
            if (text.any { it !in '0'..'9' && it != '-' && it != '+' }) return null
            return text.toIntOrNull()
        }

        /** Swift's `String.count`: characters as the user sees them (grapheme clusters), not UTF-16 units. */
        private fun characterCount(text: String): Int {
            val b = BreakIterator.getCharacterInstance()
            b.setText(text)
            var n = 0
            while (b.next() != BreakIterator.DONE) n++
            return n
        }
    }
}

/** All the aviation places, with a 1° grid to find those in a view or nearest a spot quickly. */
class AviationIndex(tsv: String) {
    val points: List<AviationPoint>
    private val cells = HashMap<Int, MutableList<Int>>()

    /** A place found by `nearest`: its index in `points` and how far it is. */
    data class Nearest(val index: Int, val meters: Double)

    init {
        val points = ArrayList<AviationPoint>(90_000)
        for (line in tsv.split('\n')) {
            if (line.isEmpty() || line.startsWith("#")) continue
            AviationPoint(line = line)?.let { points.add(it) }
        }
        this.points = points
        for ((i, p) in points.withIndex()) {
            cells.getOrPut(cell(p.point.lat, p.point.lon)) { mutableListOf() }.add(i)
        }
    }

    /** The places inside the bounds (longitude may run past ±180 for a view across the date line). */
    fun points(b: GeoBounds, keep: (AviationPoint) -> Boolean = { true }): List<Int> {
        val out = mutableListOf<Int>()
        val y0 = max(0, floor(b.minLat + 90).toInt())
        val y1 = min(179, floor(b.maxLat + 90).toInt())
        val x0 = floor(b.minLon + 180).toInt()
        val x1 = min(floor(b.maxLon + 180).toInt(), x0 + 359) // each column once, however wide the view
        if (!(y0 <= y1 && x0 <= x1)) return emptyList()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val list = cells[y * 360 + ((x % 360) + 360) % 360] ?: continue
                for (i in list) {
                    val p = points[i]
                    if (!(p.point.lat >= b.minLat && p.point.lat <= b.maxLat)) continue
                    var lon = p.point.lon
                    if (lon < b.minLon) lon += 360
                    if (lon > b.maxLon) lon -= 360
                    if (!(lon >= b.minLon && lon <= b.maxLon && keep(p))) continue
                    out.add(i)
                }
            }
        }
        return out
    }

    /** The nearest place that `keep` accepts, within `maxMeters`, and how far it is. */
    fun nearest(
        p: GeoPoint,
        maxMeters: Double = 400_000.0,
        keep: (AviationPoint) -> Boolean = { it.kind.isAerodrome },
    ): Nearest? {
        // Grow the search a degree at a time; a degree of latitude is 111 km.
        val maxRing = ceil(maxMeters / 111_000).toInt() + 1
        var best: Nearest? = null
        for (ring in 0..maxRing) {
            val latPad = ring.toDouble() + 0.5
            val lonPad = min(180.0, latPad / max(0.05, cos(p.lat * PI / 180)))
            val b = GeoBounds(
                minLat = max(-90.0, p.lat - latPad), minLon = p.lon - lonPad,
                maxLat = min(90.0, p.lat + latPad), maxLon = p.lon + lonPad,
            )
            for (i in points(b, keep)) {
                val d = Geo.distance(p, points[i].point)
                if (d <= maxMeters && d < (best?.meters ?: Double.POSITIVE_INFINITY)) best = Nearest(index = i, meters = d)
            }
            // Anything nearer would have been within this ring.
            val found = best
            if (found != null && found.meters < (ring.toDouble() + 0.5) * 111_000 * 0.9) return found
        }
        return best
    }

    private companion object {
        fun cell(lat: Double, lon: Double): Int {
            val y = min(179, max(0, floor(lat + 90).toInt()))
            val x = ((floor(lon + 180).toInt() % 360) + 360) % 360
            return y * 360 + x
        }
    }
}

object Aviation {
    const val metresPerNauticalMile = 1852.0

    /** "0.4 NM", "3.2 NM", "12.4 NM", "86 NM", "140 NM". */
    fun nauticalMiles(metres: Double): String {
        val nm = metres / metresPerNauticalMile
        if (nm < 99.95) return stringFormat("%.1f NM", nm)
        return stringFormat("%.0f NM", nm)
    }

    /** The direction from `a` to `b` on the 16-point compass: "N", "NNE", "NE" … */
    fun compass(a: GeoPoint, b: GeoPoint): String {
        val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val k = (Geo.bearing(a, b) / 22.5).rounded().toInt() % 16
        return names[(k + 16) % 16]
    }

    /** Where a spot is from a place, as a pilot reports it: "3.2 NM NE of FASZ Skukuza" (close by: "over …"). */
    fun report(spot: GeoPoint, place: AviationPoint): String {
        val d = Geo.distance(place.point, spot)
        if (d < 0.1 * metresPerNauticalMile) return "Over ${place.label}"
        return "${nauticalMiles(d)} ${compass(place.point, spot)} of ${place.label}"
    }
}
