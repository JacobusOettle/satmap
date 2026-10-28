package com.jacobusoettle.satmap.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Reads a position typed or pasted in almost any common form:
 *  - decimal degrees, signed or with N/S/E/W before or after ("-25.096893, 31.437121", "25.0969 S 31.4371 E");
 *  - degrees and decimal minutes ("25° 05.8136' S, 31° 26.2273' E") and degrees, minutes and seconds
 *    ("25°05'48.8\"S 31°26'13.6\"E", "25-05-48.8S 031-26-13.6E", "40d41m21sN", "25 05m 48.8s S", "S24.59.48.9",
 *    "S24°59.48.9"), with any of the usual symbols or spaces;
 *  - decimal commas ("-25,096893; 31,437121"), digits in other scripts, labels ("lat"/"lon" in either order, "X"/"Y", a
 *    "Lat/Long:" heading or a table's header row, "id,lat,lon"), a photo's details ("GPS Latitude Ref : South" on a
 *    line of its own, the same place again on a "GPS Position" line, Windows' "24; 59; 48.95", libexif's "North or South
 *    Latitude" and "24, 59, 48.95", "GPSLatitude - 24.99693"), both letters together ("GPS (S/E): 24.99693, 31.59232",
 *    or in a heading or a header row: "Lat (S)\tLong (E)", "LATITUDE,N/S,LONGITUDE,E/W"), an OziExplorer waypoint file,
 *    and directions and labels in English, Afrikaans, Dutch, German, French, Spanish, Italian and Portuguese ("sud",
 *    "Oeste", "ZB", "Breite"), with a lone O for east or west (see `latLon`); altitude, accuracy, a heading and the like
 *    next to the position are left out ("…, 31.59231 heading W"), and so are a height, distance, time or speed with its
 *    unit and the direction after it ("…, 31.59231 (3 km N of Skukuza)", "…, 31.59231 Bft 4"), a direction of travel or
 *    from a place ("moving W -24.99693, 31.59231", "S24 59 48.9 E31 35 32.3 N of camp", "24.99693, 31.59231 (W of the
 *    bridge)"), a wind's direction ("Wind S 10 kt"), a road's name or marker ("S3", "H4-1", "H1-3 km 14", "… km 12"), a
 *    time or count written with letters ("06h45", "4x4"), a count, name, time, date or temperature before it ("3 rhino S
 *    24° 59' 48.9\" E …", "WGS 84: S24 59 48.9 E31 35 32.3", "[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "38°C
 *    S24 59 48.9 E31 35 32.3"), and a time, date, phone number, count or temperature after it ("…, 06:45", "…, 3 males",
 *    "…, 32°C");
 *  - GPS (NMEA) fields or whole sentences ("2505.8136,S,03126.2273,E", "$GPGGA,…"), aviation style ("0536.22N 00011.22W")
 *    and ISO 6709 ("-2505.8136+03126.2273/", "+40.6892-074.0445+545.400/");
 *  - map links (Google Maps, Apple Maps, OpenStreetMap, OsmAnd, Bing, Waze, Yandex, ArcGIS, GeoHack and others with the
 *    position in them, also a search typed in another form, "?q=24+59.816+S+31+35.539+E"), geo: links, Google navigation
 *    links and the vCard iMessage sends;
 *  - GeoJSON, KML and WKT ("POINT (31.437121 -25.096893)"), which put the longitude first, as does code that names the
 *    order ("ST_MakePoint(31.437121, -25.096893)", "LngLat(…)"); labelled values in GPX, JSON or XML whatever else is
 *    with them (a <time>, a name, a zoom);
 *  - UTM ("36J 342401 7223411", "30 N 699568 5709428", "UTM 36 South 342402 7223412"), MGRS ("36J UT 42401 23411",
 *    "36JUT42402341") and plus codes ("5G6HWC3P+6R", or a short "WC3P+6R" near `near`);
 *  - a position inside other text, as in a chat message ("[12:31, 27/10/2026] Jaco: Lions on H4-1 at -25.0969, 31.4371").
 *  Without letters or labels the first number is the latitude, unless only the second can be one.
 *  A text that could be two different places, with nothing in it to say which, isn't read (null), in whatever forms they
 *  are written (two links, a link and a position typed next to it, a grid reference and a position, a list of them). Nor
 *  are numbers that could as well be times, dates, years, counts, temperatures or distances, nor a latitude or longitude
 *  on its own ("S 24° 59.815'"). Two exceptions say in the format how they were read: a lone "O" (east or west) and a
 *  directions link to a place by name (the map's centre).
 *
 *  The patterns are the Swift code's, unchanged. On Android `java.util.regex` is ICU's engine, as `NSRegularExpression`
 *  is; on the JVM (the core tests) the patterns are compiled with Unicode character classes, so that `\d`, `\w`, `\s`
 *  and `\b` mean what they mean in ICU. (Two things only ICU does remain different on the JVM: its `\b` passes over a
 *  combining mark or a format character, a stray accent or a zero-width joiner, and its case-insensitive matching doesn't
 *  take the Turkish "ı" and "İ" for an "i".) The text is read character by character as Swift reads it: by grapheme
 *  cluster (see `Ch`), and by UTF-16 offsets where the Swift code uses `NSString` and `NSRange`, which Kotlin strings
 *  index too. */
object CoordinateParser {
    data class Result(
        val point: GeoPoint,
        /** The notation it was read as, to show the user. */
        val format: String,
    )

    fun parse(text: String, near: GeoPoint? = null): Result? {
        val t = normalise(text)
        if (!containsNumber(t)) return null
        val found = place(t, near) as? Found.Place ?: return null
        val r = found.result
        val resolution = found.resolution
        // Another place in the rest of the text, in any form ("36J 342401 7223411 and -24.9961, 31.5911", a link and a
        // position typed next to it, two plus codes): it isn't clear which is meant. The same place written again is fine,
        // as closely as the less precise of the two is written, however often it is written. Two whole numbers left on
        // their own ("27/10", "12/3") are a date or a count next to it, not another place, nor are numbers each named by
        // words (see `reading`).
        var rest = t
        var read = found.used
        while (true) {
            val left = blanked(rest, read)
            // (What a reader read is always blanked; were it not, the same would be read again.)
            if (left == rest) return null
            rest = left
            if (!containsNumber(rest) || has("""^[^\p{L}\d+-]*\d+[^\p{L}\d.,+-]+\d+[^\p{L}\d+-]*$""", rest)) {
                return r
            }
            when (val other = place(rest, near, beside = true)) {
                null -> return r
                Found.Unclear -> return null
                is Found.Place -> {
                    if (!(Geo.distance(other.result.point, r.point) < 30 + max(resolution, other.resolution) * 111_320)) return null
                    read = other.used
                }
            }
        }
    }

    /** What a text says, as one of the readers reads it. */
    private sealed interface Found {
        /** A place, the parts of the text it was read from, and how precisely it is written there (in degrees). */
        data class Place(val result: Result, val used: List<NSRange>, val resolution: Double) : Found

        /** Positions that aren't one place (two plus codes, a track): none of them is meant. */
        data object Unclear : Found
    }

    /** The first reader that reads the text, and what it read. `beside`: whether the text is what is left beside a place
     *  read in it before (see `reading`). */
    private fun place(t: String, near: GeoPoint?, beside: Boolean = false): Found? {
        (fromLinks(t, near) ?: plusCodes(t, near))?.let { return it }
        // The numbers in a link that couldn't be read (a short link, a place by name) don't say where. A small "o" before a
        // word in small letters is Portuguese "the" or Spanish "or", not a direction ("…, 31.4371 o ponto de encontro").
        // (Both keep the text's length, so that what a reader read is where it is in the text.)
        val rest = replacing("""(?<!\p{L})o(?=[ \t]+\p{Ll})""", withoutLinks(t), " ")
        // GeoJSON, KML or WKT that doesn't say one place (a line, two placemarks) isn't read in any other way either.
        lonFirst(rest)?.let { return it }
        val whole = listOf(NSRange(0, rest.length))
        // A text with more numbers than a position and a message around it can have (a chat log, a file) isn't read as
        // one position as a whole, only by what is in it (a pair that can only be a position, a grid reference, a label).
        val wholeText: (String) -> Found? = { s ->
            if (numberGroups(s) > 40) null
            else latLon(s, near, beside = beside)?.let { Found.Place(it.result, whole, it.resolution) }
        }
        val readers: List<(String) -> Found?> = listOf(
            ::nmea, { s -> iso6709(s)?.let { Found.Place(it.result, whole, it.resolution) } },
            ::mgrs, ::utm, ::table, ::labelled, wholeText, { s -> inText(s, near) },
        )
        for (reader in readers) reader(rest)?.let { return it }
        return null
    }

    /** How many runs of digits the text has. */
    private fun numberGroups(t: String): Int {
        var count = 0
        var inDigits = false
        for (c in t) {
            val digit = c in '0'..'9'
            if (digit && !inDigits) count += 1
            inDigits = digit
        }
        return count
    }

    /** The text with the parts that were read blanked out (the same length, so that the other parts stay where they are). */
    private fun blanked(t: String, parts: List<NSRange>): String {
        val s = StringBuilder(t)
        for (r in parts) {
            if (r.end > s.length) continue
            for (k in r.location until r.end) s.setCharAt(k, ' ')
        }
        return s.toString()
    }

    // Normalising

    /** Signs written in more than one way, as the one the readers take. */
    private val sameSign: Map<Int, String> = mapOf(
        0x2212 to "-", 0x02D7 to "-", 0x00A0 to " ", 0x2009 to " ", 0x202F to " ", 0xFEFF to "", 0x200B to "",
        0x200E to "", 0x200F to "", '\r'.code to "\n", 'º'.code to "°", '˚'.code to "°", '⁰'.code to "°", 'ᵒ'.code to "°",
        '′'.code to "'", '’'.code to "'", '‘'.code to "'", '`'.code to "'", '´'.code to "'", '″'.code to "\"",
        '”'.code to "\"", '“'.code to "\"", '℃'.code to "°C", '℉'.code to "°F",
        // Arabic and fullwidth decimal points, commas and signs.
        0x066B to ".", 0x060C to ",", 0xFF0E to ".", 0xFF0C to ",", 0xFF0B to "+", 0xFF0F to "/",
    )

    private fun normalise(s: String): String {
        var t = s
        // Links come percent-encoded ("%2C", "%C2%B0", "%2B" in a plus code). A space in one ("address=Apple%20Park") is
        // kept as a +, as in a query, so that the link stays in one piece. Each run of codes on its own, so that a "%" in
        // the message ("Battery 15%") doesn't keep the link encoded.
        if ('%' in t && has("%[0-9A-Fa-f]{2}", t)) {
            val ns = replacingOccurrences(t, "%20", "+")
            val decoded = StringBuilder()
            var at = 0
            for (m in matchRanges("(?:%[0-9A-Fa-f]{2})+", ns)) {
                val run = ns.substring(m[0])
                decoded.append(ns, at, m[0].location).append(removingPercentEncoding(run) ?: run)
                at = m[0].end
            }
            t = decoded.toString() + ns.substring(at)
        }
        // A vCard escapes its commas ("ll=-24.996931\,31.592312" in the location iMessage sends).
        if ('\\' in t) {
            t = replacing("""(?<=\d)\\,(?=\s*[-+]?\d)""", t, ",")
        }
        // In one pass (a long text is read as it is typed): the signs above, a new line written "\r\n" as one, two
        // apostrophes as a second mark, a dash of any kind as "-" ("‐25.0969" with a hyphen, or one that keeps the line
        // together), a degree sign read in the wrong encoding ("25Â°") as one, digits in other scripts ("٤٠", "４０") as
        // 0-9, which is what the readers and `swiftDouble` take, and other numbers ("½", "²", "①") as a letter: they
        // aren't a coordinate's digits but other text.
        val scalars = codePoints(t)
        val out = StringBuilder()
        for ((k, u) in scalars.withIndex()) {
            if (u == '\r'.code && k + 1 < scalars.size && scalars[k + 1] == '\n'.code) continue
            if (u == 'Â'.code && k + 1 < scalars.size && (scalars[k + 1] == '°'.code || scalars[k + 1] == 'º'.code)) continue
            var mapped: IntArray = sameSign[u]?.let(::codePoints) ?: intArrayOf(u)
            if (u > 127 && Character.getType(u) == Character.DASH_PUNCTUATION.toInt()) {
                mapped = intArrayOf('-'.code)
            } else if (u > 127 && Character.getType(u) == Character.DECIMAL_DIGIT_NUMBER.toInt() && Character.digit(u, 10) >= 0) {
                mapped = intArrayOf(48 + Character.digit(u, 10))
            } else if (u > 127 && sameSign[u] == null && hasNumericType(u)) {
                mapped = intArrayOf('Ж'.code)
            }
            for (m in mapped) {
                if (m == '\''.code && out.isNotEmpty() && out[out.length - 1] == '\'') {
                    out.setLength(out.length - 1)
                    out.append('"')
                } else {
                    out.appendCodePoint(m)
                }
            }
        }
        // Letters in square brackets as in round ones ("[S] 24.99693 [E] 31.59231", "…, 31.592312 [S/E]").
        var r = replacing("""(?i)\[[ \t]*([NSEW](?:[ \t]*[/,][ \t]*[NSEW])?)[ \t]*\]""", out.toString(), "($1)")
        // A photo's hemispheres on lines or in fields of their own ("GPS Latitude Ref : South", "GPSLatitudeRef - S", as
        // exiftool shows them, before or after the values; libexif's "North or South Latitude"; "N/S: S", "Hemisphere:
        // South", these at the start of a line): in front of the values they are for, unless those have their own letter.
        // (The runs of spaces possessive, so that a long one with no value after it isn't split every way.)
        val separator = """[ \t]*+(?:[:=]|-(?=[ \t]))?[ \t]*+"""
        if (has("""(?i)ref|hemisphere|[NE][ \t]*/[ \t]*[SW]|[ \t]or[ \t]""", r)) {
            for ((k, letter) in listOf("N", "S", "E", "W").withIndex()) {
                val axis = "(?:" + (if (k < 2) latWords else lonWords) + ")"
                val both = if (k < 2) "N(?:ORTH)?+(?:[ \\t]*+/[ \\t]*+|[ \\t]++OR[ \\t]++)S(?:OUTH)?+"
                else "E(?:AST)?+(?:[ \\t]*+/[ \\t]*+|[ \\t]++OR[ \\t]++)W(?:EST)?+"
                val ref = "(?i)(?:" + axis + "[ \\t]*+REF(?:ERENCE)?|(?<![^\\n])[ \\t]*+(?:" + both + "(?:[ \\t]++" + axis +
                    ")?|HEMISPHERE))" + separator + "(?:" + directionWords[k] + "|" + letter + ")(?![A-Z])"
                if (!has(ref, r)) continue
                r = replacing(
                    "(?i)(" + axis + separator + """)(?=\d)(?![^\n]*(?<![A-Z])[NSEW](?![A-Z]))""",
                    replacing(ref, r, " "), "$1$letter ",
                )
            }
        }
        return trimming(r, ::inWhitespacesAndNewlines)
    }

    private fun matches(pattern: String, text: String, caseInsensitive: Boolean = false): List<List<String?>> =
        matchRanges(pattern, text, caseInsensitive).map { m ->
            m.map { r -> if (r.location == notFound) null else text.substring(r) }
        }

    // (The JVM's engine recurses where ICU keeps its own stack, and so can run out of stack on a long text that ICU reads,
    // ", , , …" in a link. It stops there as ICU stops when its stack is full: no more matches.)

    private fun matchRanges(pattern: String, text: String, caseInsensitive: Boolean = false): List<List<NSRange>> {
        val re = regex(pattern, caseInsensitive) ?: return emptyList()
        val m = re.matcher(text)
        val out = ArrayList<List<NSRange>>()
        try {
            while (m.find()) {
                out.add((0..m.groupCount()).map { g -> if (m.start(g) < 0) NSRange(notFound, 0) else NSRange(m.start(g), m.end(g) - m.start(g)) })
            }
        } catch (e: StackOverflowError) {
            return out
        }
        return out
    }

    /** Whether the pattern is found in the text. */
    private fun has(pattern: String, text: String): Boolean {
        val re = regex(pattern, false) ?: return false
        return try {
            re.matcher(text).find()
        } catch (e: StackOverflowError) {
            false
        }
    }

    /** The text with each match of the pattern replaced ($1 for its first group). */
    private fun replacing(pattern: String, text: String, template: String): String {
        val re = regex(pattern, false) ?: return text
        return try {
            re.matcher(text).replaceAll(template)
        } catch (e: StackOverflowError) {
            text
        }
    }

    /** The patterns, each compiled once: compiling one takes longer than matching it, and the text is read several times
     *  (once for each pair of numbers in a long one). (A pattern that doesn't compile matches nothing, as in Swift.) */
    private val compiled = HashMap<String, Pattern?>()

    private fun regex(pattern: String, caseInsensitive: Boolean): Pattern? {
        val key = (if (caseInsensitive) "1 " else "0 ") + pattern
        synchronized(compiled) {
            if (compiled.containsKey(key)) return compiled[key]
            val re = try {
                Pattern.compile(
                    if (unicodeClasses == 0) pattern else pattern + wholeCharacters,
                    unicodeClasses or (if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0),
                )
            } catch (e: PatternSyntaxException) {
                null
            }
            compiled[key] = re
            return re
        }
    }

    /** ICU's `\d`, `\w`, `\s` and `\b` (and case-insensitive matching) take all of Unicode: on the JVM with this flag.
     *  Android's regular expressions are ICU's and don't take the flag. */
    private val unicodeClasses: Int = try {
        Pattern.compile("a", Pattern.UNICODE_CHARACTER_CLASS)
        Pattern.UNICODE_CHARACTER_CLASS
    } catch (e: RuntimeException) {
        0
    }

    /** On the JVM, a look-behind steps back by UTF-16 units, so that "(?<!\w)" before "3" in "𝐒3" sees half of the 𝐒,
     *  unless the pattern has a character outside the BMP in it: then it steps back by whole characters, as ICU does.
     *  This part of the pattern has one and matches nothing more (U+10000 can't both follow and not follow). */
    private const val wholeCharacters = "(?:(?=𐀀)(?!𐀀))?"

    private fun valid(lat: Double, lon: Double): GeoPoint? {
        if (!(lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180)) return null
        return GeoPoint(lat = lat, lon = lon)
    }

    /** The size of one unit of a number's last digit: 0.01 for "-24.99", 1 for "25". */
    private fun lastDigit(number: String?): Double {
        val n = characters(number ?: return 1.0)
        val point = n.indexOfLast { it.isChar('.') || it.isChar(',') }
        if (point < 0) return 1.0
        var count = 0
        while (point + 1 + count < n.size && n[point + 1 + count].isNumber) count += 1
        return 10.0.pow(-count.toDouble())
    }

    /** Whether two readings are the same place, as closely as the less precise of the two is written. */
    private fun samePlace(a: Reading, b: Reading): Boolean =
        Geo.distance(a.result.point, b.result.point) < 30 + max(a.resolution, b.resolution) * 111_320

    /** The text in capitals, each letter in the same number of UTF-16 units ("ß" stays as it is, not "SS"), so that a
     *  place found in it is the same place in the text. */
    private fun capitals(t: String): String {
        val u = t.uppercase()
        if (u.length == t.length) return u
        return characters(t).joinToString("") { c ->
            val up = c.text.uppercase()
            if (characters(up).size == 1 && up.length == c.text.length) up else c.text
        }
    }

    // Map links

    /** Each link in the text on its own: they must all be one place. */
    private fun fromLinks(t: String, near: GeoPoint?): Found? {
        val links = linkRanges(t)
        var read: List<Pair<Reading, List<NSRange>>> = links.mapNotNull { r -> fromLink(t.substring(r), near)?.let { it to listOf(r) } }
        // A link with a space in it ("geo:-25.0969 31.4371", "?q=-25.0969, 31.4371"), or a part of one ("?ll=…&z=15"), is
        // read from the whole text. (Only when it has what each of them needs, which is quicker to look for in a long text.)
        // What it was read from is each run of characters with what a link has, with the number after a space that
        // completes a pair at its end, so that a different place typed next to it is still looked for.
        val parts = """[?&][a-z_\[\]%]+=|navigation:|/maps/|![23]d|geo:|cp=|#map=|://|www\.|/@"""
        if (read.isEmpty() && has("(?i)$parts", t)) {
            val r = fromLink(t, near)
            if (r != null) {
                val runs = matchRanges("""(?i)(?<!\S)\S*?(?:""" + parts + """)\S*+(?:(?<=[\d,+])\s+[-+]?\d+(?:\.\d+)?\S*+)?""", t)
                read = listOf(r to (if (runs.isEmpty()) listOf(NSRange(0, t.length)) else runs.map { it[0] }))
            }
        }
        val first = read.firstOrNull()?.first ?: return null
        if (!read.all { samePlace(it.first, first) }) return Found.Unclear
        return Found.Place(first.result, read.flatMap { it.second }, read.maxOf { it.first.resolution })
    }

    private fun fromLink(t: String, near: GeoPoint?): Reading? {
        val num = """([-+]?\d{1,3}(?:\.\d+)?)(?!\d)"""

        // A parameter whose whole value is "lat,lon" ("lat+lon", "lat lon" once decoded), maybe after "loc:" (Google) or
        // "ll." (Waze); "google.navigation:q=" too.
        fun param(names: String, typed: Boolean = false): String =
            """(?:[?&]|navigation:)(?:""" + names + """)=(?:loc:|ll\.)?\s*""" + num + """(?:,|%2C|\+|\s|%20)+""" + num +
                """(?!\w|\.\d)""" +
                (if (typed) """(?!(?:,|%2C|\+|[ \t]|%20)+(?:[NSEWnsew](?![A-Za-z])|\d+(?:\.\d+)?(?![\d.A-Za-z])))""" else "")

        // The position, and how precisely it is written there.
        fun find(pattern: String, lonFirst: Boolean = false): Pair<GeoPoint, Double>? {
            for (m in matches(pattern, t, caseInsensitive = true)) {
                val a = m[1]?.let(::swiftDouble)
                val b = m[2]?.let(::swiftDouble)
                if (a != null && b != null) {
                    val p = if (lonFirst) valid(b, a) else valid(a, b)
                    if (p != null) return p to max(lastDigit(m[1]), lastDigit(m[2]))
                }
            }
            return null
        }

        // A pin, a search or a destination as it was typed ("q=24+59.816+S+31+35.539+E", "query=S24.996931,E31.592312",
        // "q=24°59'48.9"S+31°35'32.3"E"): its first two numbers are "lat,lon" only when no letter or number goes on with
        // them (there they are degrees and minutes), else the whole value is read, as a place in a link's path is.
        fun typed(names: String): Pair<GeoPoint, Double>? =
            find(param(names, typed = true))
                ?: matches("""(?:[?&]|navigation:)(?:""" + names + """)=([^&#\n]+)""", t, caseInsensitive = true)
                    .firstOrNull()?.get(1)?.let(::pathPoint)

        val link = { p: Pair<GeoPoint, Double> ->
            Reading(Result(p.first, if (hasPrefix(t.lowercase(), "geo:")) "geo: link" else "map link"), p.second)
        }

        // Directions: where they go to (Waze's to=ll.…, the last stop of OpenStreetMap's route=), not where they start.
        (typed("daddr|destination|to") ?: find("""[?&]route=[^&#\s]*;""" + num + "," + num + """(?=&|#|\s|$)"""))?.let {
            return link(it)
        }
        val dir = matches("""/maps/dir/([^?#]*)""", t, caseInsensitive = true).firstOrNull()
        val path = dir?.get(1)
        if (path != null) {
            // "/maps/dir/<from>/<to>/@<map centre>/data=…": the last stop, from the path or else from the stops' positions
            // in the data (!1d lon !2d lat or !3d lat !4d lon each). Without either, the map's centre is somewhere along
            // the way, and the format says so.
            val stops = components(path, "/").takeWhile { !hasPrefix(it, "@") && !hasPrefix(it, "data=") }
            stops.lastOrNull { it.isNotEmpty() }?.let(::pathPoint)?.let { return link(it) }
            val positions = "!1d" + num + "!2d" + num + "|!3d" + num + "!4d" + num
            val data = matches(positions, t).mapNotNull { m ->
                val lon = m[1]?.let(::swiftDouble)
                val lat = m[2]?.let(::swiftDouble)
                if (lon != null && lat != null) {
                    return@mapNotNull valid(lat, lon)?.let { it to max(lastDigit(m[1]), lastDigit(m[2])) }
                }
                val lat3 = m[3]?.let(::swiftDouble)
                val lon4 = m[4]?.let(::swiftDouble)
                if (lat3 != null && lon4 != null) {
                    return@mapNotNull valid(lat3, lon4)?.let { it to max(lastDigit(m[3]), lastDigit(m[4])) }
                }
                null
            }
            data.lastOrNull()?.let { return link(it) }
            return find("/@$num,$num")?.let { Reading(Result(it.first, "map link (centre of the route)"), it.second) }
        }
        // Yandex (ll=, pt=) and ArcGIS (center=, marker=) put the longitude first. The pin (pt=, whatshere[point]=,
        // marker=) rather than the map's centre (ll=, center=), whichever comes first in the link.
        if (has("""(?i)\b(?:yandex\.[a-z.]+|arcgis\.com)/""", t)) {
            (find(param("""pt|marker|whatshere\[point\]"""), lonFirst = true) ?: find(param("ll|center"), lonFirst = true))?.let {
                return link(it)
            }
        }
        // Most specific first: Google's dropped pin (!3d…!4d…) and an embedded Google map (!2d lon !3d lat).
        find("!3d$num!4d$num")?.let { return link(it) }
        find("!2d$num!3d$num", lonFirst = true)?.let { return link(it) }
        // The place: a pin or search (also a name with the position after an @), Bing's pushpin (sp=point.lat_lon_name),
        // OpenStreetMap's marker (mlat=, mlon=, not the map's centre in lat=, lon=) and OsmAnd's lat=…&lon=… (in either
        // order, with other parameters between them).
        val place = listOf("""[?&](?:q|query)=[^&#\s@]*@""" + num + """(?:,|\s)+""" + num, """[?&]sp=point\.""" + num + "_" + num)
        (typed("q|query|pin|point|loc|address") ?: place.firstNotNullOfOrNull { find(it) })?.let { return link(it) }

        fun value(names: String): Pair<Double, Double>? {
            val v = matches("[?&](?:$names)=$num", t, caseInsensitive = true).firstOrNull()?.get(1)
            return v?.let(::swiftDouble)?.let { it to lastDigit(v) }
        }
        for ((lat, lon) in listOf("mlat" to "mlon|mlng", "lat|latitude" to "lon|lng|long|longitude")) {
            val a = value(lat)
            val b = value(lon)
            if (a != null && b != null) {
                valid(a.first, b.first)?.let { return link(it to max(a.second, b.second)) }
            }
        }
        // GeoHack (Wikipedia's map links): params=24_59_46_S_31_35_28_E_… or params=-24.99611_31.59111_….
        val geoHack = matches("""[?&]params=([\d._]+_[NS])_([\d._]+_[EW])(?![A-Z0-9])""", t, caseInsensitive = true).firstOrNull()
        if (geoHack != null) {
            val a = geoHack[1]
            val b = geoHack[2]
            if (a != null && b != null) {
                latLon(replacingOccurrences("$a $b", "_", " "), null)?.let { return link(it.result.point to it.resolution) }
            }
        }
        find("[?&]params=" + num + "_" + num + """(?=_[A-Za-z]|&|#|\s|$)""")?.let { return link(it) }
        // "/maps/place/<lat,lon, DMS or plus code>/@<map centre>": the place, not where the map was centred.
        val placePart = matches("""/maps/(?:place|search)/([^/?#@]+)""", t, caseInsensitive = true).firstOrNull()?.get(1)
        if (placePart != null) {
            pathPoint(placePart)?.let { return link(it) }
            plusCode(placePart, near)?.let { return it }
        }
        // geo:lat,lon, unless it is geo:0,0, which Android writes when only q= says where (and q= had no position).
        val geo = """geo:\s*""" + num + """(?:\s*,\s*|\s+)""" + num
        val g = matches(geo, t, caseInsensitive = true).firstOrNull()
        if (g != null) {
            val a = g[1]?.let(::swiftDouble)
            val b = g[2]?.let(::swiftDouble)
            if (a != null && b != null && (a != 0.0 || b != 0.0)) {
                valid(a, b)?.let { return link(it to max(lastDigit(g[1]), lastDigit(g[2]))) }
            }
        }
        // ll= (Apple's pin, Google's map centre), Apple's coordinate= and Street View's viewpoint=, then the map's centre
        // (a "#15/lat/lon" only inside a link, not "Ref #27/10/26" in a message), and last where directions start from.
        val rest = listOf(
            param("ll"), param("coordinates?|viewpoint"), param("sll|center|near|map"), "cp=$num~$num",
            """#map=\d{1,2}(?:\.\d+)?/""" + num + "/" + num,
            """(?:://|www\.)[^\s#]*#\d{1,2}(?:\.\d+)?/""" + num + "/" + num, "/@$num,$num",
            param("saddr|source|from"),
        )
        rest.firstNotNullOfOrNull { find(it) }?.let { return link(it) }
        // Any other link with "lat,lon" as a part of its path (MapQuest's /latlng/…, HERE's /l/…; Mapbox's static maps put
        // the longitude first) or as the whole value of a parameter, with decimals, and not part of a longer list (a
        // bounding box, a route).
        val decimal = """([-+]?\d{1,3}\.\d+)"""
        val linkPath = """(?:://|www\.)\S*?/""" + decimal + """\s*,\s*""" + decimal +
            """(?![\d.])(?=[,/?#&]|\s|$)(?!,\s*[-+]?\d*\.\d)"""
        find(linkPath, lonFirst = has("""(?i)mapbox\.com/""", t))?.let { return link(it) }
        return find("""[?&][A-Za-z_]+=""" + decimal + """\s*,\s*""" + decimal + """(?=&|#|\s|$)""")?.let(link)
    }

    /** A position as part of a link's path: "-25.096893,31.437121", "-25.096893+31.437121" or "25°05'48.8"S+31°26'13.6"E". */
    private fun pathPoint(part: String): Pair<GeoPoint, Double>? {
        val s = replacingOccurrences(part, "+", " ")
        if (!has("""^[-\d.,;°'"\sNSEWnsew]+$""", s)) return null
        return latLon(s, null)?.let { it.result.point to it.resolution }
    }

    /** Where the links are. "geo:" is a link only before a number, not in "<geo:lat>" (W3C Basic Geo) or "geo:lon=" (a
     *  photo's machine tags). */
    private fun linkRanges(t: String): List<NSRange> {
        // (What each has is looked for first, as that is quicker in a long text.)
        if (!has("""(?i)://|www\.|geo:|navigation:|\.[a-z]{2,}/""", t)) return emptyList()
        // Each starts where a word starts (the look-behind), so a long run of characters isn't tried from every place in it.
        return matchRanges(
            """(?i)(?<![a-z0-9+.-])(?:[a-z][a-z0-9+.-]*+://|www\.|geo:(?=\s*[-+\d])|google\.navigation:)\S*""" +
                """|(?<![a-z0-9.-])(?:[a-z0-9-]++\.)++[a-z]{2,}/\S*""",
            t,
        ).map { it[0] }
    }

    /** The text without links (the ones `fromLink` couldn't read). */
    private fun withoutLinks(t: String): String = blanked(t, linkRanges(t))

    // Plus codes (Open Location Code)

    private const val olcAlphabet = "23456789CFGHJMPQRVWX"

    private const val codePattern =
        """(?<![0-9A-Z])([23456789CFGHJMPQRVWX0]{2,8})\+([23456789CFGHJMPQRVWX]{0,7})(?![0-9A-Z])"""

    private fun plusCode(t: String, near: GeoPoint?): Reading? {
        val u = t.uppercase()
        return matches(codePattern, u).firstNotNullOfOrNull { m -> plusCode(m, u, near) }
    }

    /** Every plus code in the text: one place, or two codes ("Lions WC3P+6R, leopard XC5P+2Q") and none of them is meant.
     *  One that can't be read (a short code shortened against a place name) counts as another place too. */
    private fun plusCodes(t: String, near: GeoPoint?): Found? {
        if ('+' !in t) return null
        val u = capitals(t)
        val found = matchRanges(codePattern, u).map { m ->
            m to plusCode(m.map { if (it.location == notFound) null else u.substring(it) }, u, near)
        }
        val first = found.firstNotNullOfOrNull { it.second } ?: return null
        // Only what can be a code: "25+" or "22+22" (a count, a sum) isn't one.
        val codes = found.filter { it.second != null || (it.first[1].length >= 4 && it.first[2].length >= 2) }
        if (!codes.all { c -> c.second?.let { samePlace(it, first) } ?: false }) return Found.Unclear
        return Found.Place(first.result, codes.map { it.first[0] }, first.resolution)
    }

    /** A code, and the height of its area in degrees. */
    private fun plusCode(m: List<String?>, u: String, near: GeoPoint?): Reading? {
        val head = m[1] ?: return null
        val tail = m[2] ?: return null
        // (A short code is read as a full one of 8 characters before the "+".)
        val digits = 8 + tail.length
        val size = if (digits <= 10) 20.0.pow(2 - (digits / 2).toDouble()) else 20.0.pow(-3.0) / 5.0.pow((digits - 10).toDouble())
        return plusCodePoint(m, head, tail, u, near)?.let { Reading(it, size) }
    }

    private fun plusCodePoint(m: List<String?>, head: String, tail: String, u: String, near: GeoPoint?): Result? {
        // A full code padded with zeros is an area 5 km across or more ("5G6HWC00+", "5G000000+"), not a place, and a
        // number with a plus is more likely ("25000000+ visitors").
        if ('0' in head) return null
        if (head.length < 8) {
            // A short code: the missing first part comes from the reference point (your position). Google writes them
            // with 4 or 6 characters before the "+" and 2 or more after it, so "25+" or "22+22" (a count, a sum) isn't one.
            val ref = near ?: return null
            if (head.length < 4 || tail.length < 2) return null
            // A place name after it ("WC3P+6R Skukuza") means the code was shortened against that place, which can't be
            // looked up offline. Near you the same short code is a square up to 1° from that place (at Letaba, 110 km
            // north of the Skukuza one; near Cape Town, 1,600 km away), and nothing says which, so it isn't read.
            if (after(u, m[0] ?: "")?.any { it.isLetter } == true) return null
            val padding = 8 - head.length
            if (padding % 2 != 0) return null
            val resolution = 20.0.pow(2 - padding.toDouble() / 2)
            val prefix = olcEncodePairs(
                lat = floor(ref.lat / resolution) * resolution, lon = floor(ref.lon / resolution) * resolution, length = padding,
            )
            val decoded = olcDecode(prefix + head + tail) ?: return null
            var lat = decoded.lat
            var lon = decoded.lon
            val half = resolution / 2
            if (ref.lat + half < lat && lat - resolution >= -90) lat -= resolution
            else if (ref.lat - half > lat && lat + resolution <= 90) lat += resolution
            if (ref.lon + half < lon) lon -= resolution else if (ref.lon - half > lon) lon += resolution
            if (lon > 180) lon -= 360 else if (lon < -180) lon += 360
            return valid(lat, lon)?.let { Result(it, "plus code (near you)") }
        }
        val p = olcDecode(head + tail) ?: return null
        return valid(p.lat, p.lon)?.let { Result(it, "plus code") }
    }

    /** The first `length` (even, up to 10) characters of a full code for a point. */
    private fun olcEncodePairs(lat: Double, lon: Double, length: Int): String {
        var la = min(max(lat, -90.0), 90 - 1e-9) + 90
        var lo = lon + 180
        lo %= 360.0
        if (lo < 0) lo += 360
        val out = StringBuilder()
        var res = 20.0
        repeat(length / 2) {
            val a = (la / res).toInt()
            val b = (lo / res).toInt()
            out.append(olcAlphabet[min(a, 19)])
            out.append(olcAlphabet[min(b, 19)])
            la -= a.toDouble() * res
            lo -= b.toDouble() * res
            res /= 20
        }
        return out.toString()
    }

    /** The centre of the area of a full code (separator removed). */
    private fun olcDecode(code: String): GeoPoint? {
        val chars = characters(code)
        if (chars.size < 2) return null
        var lat = -90.0
        var lon = -180.0
        var res = 20.0
        var i = 0
        var latSize = 20.0
        var lonSize = 20.0
        while (i + 1 < chars.size && i < 10) {
            val a = olcAlphabet.indexOfCharacter(chars[i])
            val b = olcAlphabet.indexOfCharacter(chars[i + 1])
            if (a < 0 || b < 0) return null
            lat += a.toDouble() * res
            lon += b.toDouble() * res
            latSize = res
            lonSize = res
            res /= 20
            i += 2
        }
        if (i < chars.size && i < 10) return null // an odd character in the pairs
        // Grid refinement: 5 rows by 4 columns per character.
        var rowSize = latSize
        var colSize = lonSize
        while (i < chars.size) {
            val v = olcAlphabet.indexOfCharacter(chars[i])
            if (v < 0) return null
            rowSize /= 5
            colSize /= 4
            lat += (v / 4).toDouble() * rowSize
            lon += (v % 4).toDouble() * colSize
            latSize = rowSize
            lonSize = colSize
            i += 1
        }
        return GeoPoint(lat = lat + latSize / 2, lon = lon + lonSize / 2)
    }

    // GeoJSON, KML and WKT, NMEA sentences, ISO 6709

    /** Formats that always put the longitude first: GeoJSON ("coordinates": [lon, lat, alt], keys quoted or not, nested for
     *  a MultiPoint), KML (<coordinates>lon,lat,alt, <gx:coord>lon lat alt), WKT ("POINT (lon lat)", "SRID=4326;POINT Z
     *  (lon lat alt)", "MULTIPOINT ((lon lat))") and code that names the order ("ST_MakePoint(lon, lat)" in PostGIS,
     *  Mapbox's "LngLat(lon, lat)", OpenLayers' "fromLonLat([lon, lat])"). Two different places in it (two placemarks,
     *  a line) aren't read (unclear), and null when the text is in none of these formats. */
    private fun lonFirst(t: String): Found? {
        val num = """([-+]?\d+(?:\.\d+)?)"""
        val lead = """(?<![\d.])""" + num
        // Where the positions are (the first group), and how each one is written in there. WKT's POINT in any case, but
        // not after a word, as "Meeting point (-25.0969 31.4371)" isn't WKT. GeoJSON also when it was copied without
        // its closing bracket ("coordinates": [\n 31.4371,\n -25.0969). (A position starts where a number starts, so that
        // a long run of digits isn't tried from every digit in it.)
        val formats = listOf(
            Triple(
                """["']?\bcoordinates["']?\s*:\s*(\[[\[\]\s\d.,+-]*(?:\]|$))""",
                """\[\s*""" + num + """\s*,\s*""" + num + """[^\[\]]*(?:\]|$)""", "GeoJSON",
            ),
            Triple("""<coordinates>([^<]*)""", lead + """\s*,\s*""" + num + """(?:\s*,\s*[-+]?\d+(?:\.\d+)?)?""", "KML"),
            Triple("""<gx:coord>([^<]*)""", lead + """\s+""" + num, "KML"),
            Triple(
                """(?i)(?<![A-Z0-9_])(?<![A-Z][ \t]{1,3})(?:MULTI)?POINT\s*(?:ZM|Z|M)?\s*(\([\s\d.,+()-]*\))""",
                lead + """\s+""" + num + """(?:\s+[-+]?\d+(?:\.\d+)?){0,2}""", "WKT",
            ),
            Triple(
                """(?i:\bST_(?:Make)?Point|(?:LNG|LON|LONG)LAT\w*)\s*[(\[=:]\s*[(\[]?\s*(""" + num + """\s*,\s*""" + num + ")",
                lead + """\s*,\s*""" + num, "decimal degrees",
            ),
        )
        for ((area, position, name) in formats) {
            val areas = matchRanges(area, t)
            var resolution = 0.0
            val points: List<GeoPoint?> = areas.filter { it[1].location != notFound }.flatMap { a ->
                matches(position, t.substring(a[1])).map { m ->
                    val lon = m[1]?.let(::swiftDouble)
                    val lat = m[2]?.let(::swiftDouble)
                    if (lon == null || lat == null) {
                        null
                    } else {
                        resolution = maxOf(resolution, lastDigit(m[1]), lastDigit(m[2]))
                        valid(lat, lon)
                    }
                }
            }
            if (points.isEmpty()) continue
            val p = points[0]
            if (p == null || !points.all { q -> q?.let { Geo.distance(it, p) < 30 } ?: false }) return Found.Unclear
            // "coordinates: [-25.0969, 31.4371]" on its own, with the key not in quotes, no "type" and no nesting, may
            // as well be a person's or another tool's lat, lon: when both orders are positions, it can't be told which.
            if (name == "GeoJSON" && valid(p.lon, p.lat) != null && !has("""["']coordinates["']|\btype["']?\s*:|\[\s*\[""", t)) {
                return Found.Unclear
            }
            // A KML's <LookAt> or <Camera> is where the view looks from, not another place.
            val camera = if (name == "KML") matchRanges("""(?is)<(LookAt|Camera)\b.*?</\1>""", t).map { it[0] } else emptyList()
            return Found.Place(Result(p, name), areas.map { it[0] } + camera, resolution)
        }
        return null
    }

    /** GPS (NMEA) fields, alone or in whole sentences ($GPGGA, $GPRMC, $GPGLL…): ddmm.mmmm,N/S,dddmm.mmmm,E/W. Several
     *  (a log) must all be one place. */
    private fun nmea(t: String): Found? {
        val found = matchRanges(
            """(?<![\d.])(\d{2})(\d{2}(?:\.\d+)?),\s*([NS]),\s*(\d{3})(\d{2}(?:\.\d+)?),\s*([EW])(?![A-Z])""", t,
            caseInsensitive = true,
        )
        val fixes = found.map { r ->
            val m = r.map { t.substring(it) }
            val latD = swiftDouble(m[1])
            val latM = swiftDouble(m[2])
            val lonD = swiftDouble(m[4])
            val lonM = swiftDouble(m[5])
            if (latD == null || latM == null || !(latM < 60) || lonD == null || lonM == null || !(lonM < 60)) {
                null
            } else {
                val lat = (latD + latM / 60) * (if (m[3].uppercase() == "S") -1.0 else 1.0)
                val lon = (lonD + lonM / 60) * (if (m[6].uppercase() == "W") -1.0 else 1.0)
                valid(lat, lon)
            }
        }
        if (fixes.isEmpty()) return null
        val p = fixes[0]
        if (p == null || !fixes.all { q -> q?.let { Geo.distance(it, p) < 30 } ?: false }) return Found.Unclear
        val minutes = found.flatMap { r -> listOf(r[2], r[5]).map { lastDigit(t.substring(it)) } }
        return Found.Place(Result(p, "GPS (NMEA)"), found.map { it[0] }, (minutes.maxOrNull() ?: 0.0) / 60)
    }

    /** ISO 6709, as in photo and video metadata and the time zone database: "+40.6892-074.0445+545.400/",
     *  "-2505.8136+03126.2273/", "-0117+03649". The latitude has 2 digits of degrees and the longitude 3, then 2 of
     *  minutes and 2 of seconds if there are more; an altitude and "CRS…" after them are left out. */
    private fun iso6709(t: String): Reading? {
        val lat = "([+-])([0-9]{2})([0-9]{2})?([0-9]{2})?([.][0-9]+)?"
        val lon = "([+-])([0-9]{3})([0-9]{2})?([0-9]{2})?([.][0-9]+)?"
        val pattern = "^" + lat + lon + """(?:[+-][0-9]+(?:\.[0-9]+)?)?(?:CRS[A-Z0-9_:]+)?/?$"""
        val m = matches(pattern, t, caseInsensitive = true).firstOrNull() ?: return null

        // The value, and the size of one unit of its last digit in degrees.
        fun value(i: Int): Pair<Double, Double>? {
            val fields = (i + 1..i + 3).mapNotNull { m[it]?.let(::swiftDouble) }.toMutableList() // degrees, minutes, seconds
            if (fields.isEmpty()) return null
            fields[fields.size - 1] += m[i + 4]?.let { swiftDouble("0$it") } ?: 0.0
            if (!fields.drop(1).all { it < 60 }) return null
            var v = 0.0
            for ((offset, element) in fields.withIndex()) v += element / 60.0.pow(offset.toDouble())
            return (if (m[i] == "-") -v else v) to lastDigit(m[i + 4] ?: "") / 60.0.pow((fields.size - 1).toDouble())
        }
        val la = value(1) ?: return null
        val lo = value(6) ?: return null
        val p = valid(la.first, lo.first) ?: return null
        return Reading(Result(p, "ISO 6709"), max(la.second, lo.second))
    }

    // UTM and MGRS

    /** Latitude band letters from 80° S, 8° each (X is 12°). */
    private const val bands = "CDEFGHJKLMNPQRSTUVWX"

    private fun bandRange(band: Ch): ClosedFloatingPointRange<Double>? {
        val i = bands.indexOfCharacter(band)
        if (i < 0) return null
        val bottom = -80 + i.toDouble() * 8
        return bottom..(if (band.isChar('X')) 84.0 else bottom + 8)
    }

    /** WGS84 UTM to latitude and longitude. */
    fun utmToLatLon(zone: Int, north: Boolean, easting: Double, northing: Double): GeoPoint? {
        if (zone !in 1..60) return null
        val a = 6_378_137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val x = easting - 500_000
        val y = if (north) northing else northing - 10_000_000
        val mu = y / k0 / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3.0) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4.0) / 32) * sin(4 * mu) +
            (151 * e1.pow(3.0) / 96) * sin(6 * mu) + (1097 * e1.pow(4.0) / 512) * sin(8 * mu)
        val s = sin(phi1)
        val c = cos(phi1)
        val tn = tan(phi1)
        val n1 = a / sqrt(1 - e2 * s * s)
        val t1 = tn * tn
        val c1 = ep2 * c * c
        val r1 = a * (1 - e2) / (1 - e2 * s * s).pow(1.5)
        val d = x / (n1 * k0)
        val lat = phi1 - (n1 * tn / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4.0) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6.0) / 720
            )
        val lon0 = (zone - 1).toDouble() * 6 - 180 + 3
        val lon = lon0 * PI / 180 + (
            d - (1 + 2 * t1 + c1) * d.pow(3.0) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5.0) / 120
            ) / c
        return valid(lat * 180 / PI, lon * 180 / PI)
    }

    /** The easting and northing of a UTM grid reference: 5 to 8 digits, not glued to letters other than an "E" or "N"
     *  label or an "m" ("E342401", "342401mE"; "W252525S" isn't one). */
    private const val gridNumber = """(?<![\d.A-DF-MO-Z])(\d{5,8}(?:\.\d+)?)(?![\dA-DF-LO-Z])"""

    /** A UTM grid reference: in a list, the first one that reads (the others are read after it, to be the same place). */
    private fun utm(t: String): Found? {
        val u = capitals(t)
        val numbers = matchRanges(gridNumber, u)
        if (numbers.isEmpty()) return null
        val zones = matchRanges(
            """(?<![\d.])(\d{1,2})\s*(NORTH|SOUTH|NOORD|SUID)(?![A-Z])|(?<![\d.])(\d{1,2})\s?([C-HJ-NP-X])(?![A-Z])""", u,
        )
        for (number in numbers) {
            utm(u, number[0].location, zones)?.let { return it }
        }
        return null
    }

    /** The grid reference whose easting (or northing) starts at `firstNumber`, after its zone (one of `zones`). */
    private fun utm(ns: String, firstNumber: Int, zones: List<List<NSRange>>): Found? {
        // The zone and a band letter, or the hemisphere in words ("UTM 36 South …"), right before the easting and
        // northing (or their labels), so that "Waypoint 12 N: 36J …" or the road in "R40 N to 36J …" isn't the zone:
        // it ends in what may be between them (spaces, ",:;=", an "E" or "N" label), which is found from the easting back.
        var gap = firstNumber
        // (A UTF-16 unit, as `NSString.character(at:)` gives it: half of a surrogate pair is no scalar, and so a letter.)
        fun isLetter(k: Int): Boolean = ns[k].isSurrogate() || Character.isAlphabetic(ns[k].code)
        fun skipSeparators() {
            while (gap > 0 && !ns[gap - 1].isSurrogate() && (isUnicodeWhiteSpace(ns[gap - 1].code) || ns[gap - 1] in ",:;=")) {
                gap -= 1
            }
        }
        skipSeparators()
        var eastLabelled = false
        for (label in listOf("EASTING", "NORTHING", "E", "N")) {
            if (gap >= label.length && ns.substring(gap - label.length, gap) == label &&
                (gap == label.length || !isLetter(gap - label.length - 1))
            ) {
                gap -= label.length
                eastLabelled = label.startsWith("E")
                skipSeparators()
                break
            }
        }
        // Within 10 km north of the equator the northing has fewer digits, and its label may come first ("37N N 1500 E
        // 285000"). (The runs of spaces possessive, so that a long one after a label with no number isn't split every way.)
        var shortFirst: List<NSRange>? = null
        val from = max(0, gap - 100)
        if (eastLabelled) {
            val m = matchRanges("""(?<![\dA-Z])N(?:ORTHING)?\s*+[:=]?\s*+(\d{1,4}(?:\.\d+)?)[\s,;]*$""", ns.substring(from, gap))
                .firstOrNull()
            if (m != null) {
                shortFirst = m.map { it.shifted(from) }
                gap = m[0].location + from
                skipSeparators()
            }
        }
        val zb = zones.lastOrNull { it[0].end in gap..firstNumber } ?: return null
        val spelled = zb[1].location != notFound
        val zone = swiftInt(ns.substring(if (spelled) zb[1] else zb[3])) ?: return null
        val letter = characters(ns.substring(if (spelled) zb[2] else zb[4])).firstOrNull() ?: return null
        // The rest after it, so that the "N" of "30 N 699568 5709428" isn't taken for a northing label below, up to the
        // next zone: one grid reference of a list ("36J 342401 7223411\n36J 352401 7233411"), and the others are read after
        // it, to be the same place or else not clear.
        val start = zb[0].end
        val end = zones.firstOrNull { it[0].location >= start }?.get(0)?.location ?: ns.length
        val rest = ns.substring(start, end)
        val big = matchRanges(gridNumber, rest).toMutableList()
        // That short northing only north of the equator, and only right after the easting ("37N 285000 1500", "37N 285000
        // E 1500 N") or labelled before it.
        if (big.size == 1 && letter.isChar('N')) {
            if (shortFirst != null) {
                big.add(0, shortFirst.map { it.shifted(-start) })
            } else {
                val after = big[0][0].end
                val short = matchRanges(
                    """^\s?(?:M?E(?:ASTING)?)?[\s,;]*(?:N(?:ORTHING)?\s*[:=]?\s*)?(\d{1,4}(?:\.\d+)?)(?![\d.])""", rest.substring(after),
                ).firstOrNull()
                if (short != null) big.add(short.map { it.shifted(after) })
            }
        }
        if (big.size != 2) return null
        val e0 = swiftDouble(rest.substring(big[0][1])) ?: return null
        val n0 = swiftDouble(rest.substring(big[1][1])) ?: return null
        val used = listOf(NSRange(zb[0].location, start + big[1][0].end - zb[0].location))
        // (In metres: 1 m is about 0.00001°.)
        val resolution = big.map { lastDigit(rest.substring(it[1])) }.maxOrNull()?.let { it / 111_320 } ?: 0.0
        fun found(p: GeoPoint): Found = Found.Place(Result(p, "UTM"), used, resolution)
        // Easting first, unless labelled the other way round or only the first can be a northing.
        var e = e0
        var n = n0
        // "N 7223411 E 342401": labels, not the band letter right after the zone number.
        val northLabel = matchRanges("""(?<![\dA-Z])N(?:ORTHING)?\s*+[:=]?\s*+\d""", rest).firstOrNull()?.get(0)?.location
        val eastLabel = matchRanges("""(?<![\dA-Z])E(?:ASTING)?\s*+[:=]?\s*+\d""", rest).firstOrNull()?.get(0)?.location
        val northingFirst = northLabel?.let { nl -> eastLabel?.let { nl < it } ?: true } ?: false
        if (northingFirst || (e >= 1_000_000 && n < 1_000_000)) {
            val swap = e
            e = n
            n = swap
        }
        if (e !in 100_000.0..900_000.0 || n !in 0.0..10_000_000.0) return null
        if (spelled) {
            return utmToLatLon(zone, north = letter.isChar('N'), easting = e, northing = n)?.let(::found)
        }
        // The letter is a latitude band; "N"/"S" can also mean the hemisphere (used by some tools).
        val range = bandRange(letter)
        if (range != null) {
            val p = utmToLatLon(zone, north = letter.key >= "N", easting = e, northing = n)
            if (p != null && p.lat in (range.start - 1)..(range.endInclusive + 1)) return found(p)
        }
        if (letter.isChar('N') || letter.isChar('S')) {
            utmToLatLon(zone, north = letter.isChar('N'), easting = e, northing = n)?.let { return found(it) }
        }
        return null
    }

    private fun mgrs(t: String): Found? {
        // Anywhere in the text ("MGRS: 36J UT 42401 23411", "36J UT 42401 23411 (MGRS)") that has no other numbers. The
        // zone number and band letter are written together, as every tool writes them, and the letters all in capitals or
        // all in small letters, so "Arrive 7 Dec 25" or "4 Mar 26" isn't one. The digits are the easting and northing (in
        // metres, maybe with an "m"), in two groups or one run split in half ("36JUT42402341"). Several (a list) must all
        // be one place.
        val pattern = """(?<![A-Z0-9])(\d{1,2})([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*(\d{1,10})M?(?:\s+(\d{1,5})M?)?(?![A-Z0-9])"""
        val found = matchRanges(pattern, t, caseInsensitive = true)
        if (found.isEmpty() || containsNumber(blanked(t, found.map { it[0] }))) return null
        val read = found.mapNotNull { m -> mgrs(m.map { if (it.location == notFound) null else t.substring(it) }) }
        if (read.size != found.size) return null
        val first = read.firstOrNull() ?: return null
        if (!read.all { samePlace(it, first) }) return Found.Unclear
        return Found.Place(first.result, found.map { it[0] }, read.maxOf { it.resolution })
    }

    /** One MGRS grid reference, from its parts as `mgrs` matches them. */
    private fun mgrs(m: List<String?>): Reading? {
        val letters = (2..4).joinToString("") { m[it] ?: "" }
        val square = characters(letters.uppercase())
        if (!(letters == letters.uppercase() || letters == letters.lowercase()) || square.size != 3) return null
        val zone = m[1]?.let(::swiftInt) ?: return null
        if (zone !in 1..60) return null
        var es = m[5] ?: return null
        val band = square[0]
        val col = square[1]
        val row = square[2]
        var ns = m[6] ?: ""
        if (ns.isEmpty()) {
            val digits = characters(es)
            if (digits.size % 2 != 0) return null
            ns = string(digits, digits.size - digits.size / 2, digits.size)
            es = string(digits, 0, digits.size / 2)
        }
        val eastCount = characters(es).size
        if (eastCount != characters(ns).size || eastCount > 5) return null
        val ev = swiftDouble(es) ?: return null
        val nv = swiftDouble(ns) ?: return null
        val scale = 10.0.pow((5 - eastCount).toDouble())
        // 100 km squares: column letters repeat every 3 zones, row letters every 2 (offset by 5 in even zones).
        val set = (zone - 1) % 3
        val colLetters = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")[set]
        val ci = colLetters.indexOfCharacter(col)
        if (ci < 0) return null
        var rowLetters = "ABCDEFGHJKLMNPQRSTUV"
        if (zone % 2 == 0) rowLetters = rowLetters.substring(5) + rowLetters.substring(0, 5)
        val ri = rowLetters.indexOfCharacter(row)
        val range = bandRange(band)
        if (ri < 0 || range == null) return null
        val easting = (ci + 1).toDouble() * 100_000 + ev * scale + scale / 2
        val north = band.key >= "N"
        val base = ri.toDouble() * 100_000 + nv * scale + scale / 2
        // The northing repeats every 2,000 km: take the one inside the latitude band.
        for (k in 0 until 6) {
            val northing = base + k.toDouble() * 2_000_000
            val p = utmToLatLon(zone, north = north, easting = easting, northing = northing)
            if (p != null && p.lat in (range.start - 0.5)..(range.endInclusive + 0.5)) {
                return Reading(Result(p, "MGRS"), scale / 111_320)
            }
        }
        return null
    }

    // Labelled values

    /** Values each named by a label right before them, whatever else is in the text: GPX (<trkpt lat="…" lon="…"> with its
     *  <ele> and <time>), JSON ({"name": "Camp 14", "lat": …, "lng": …, "zoom": 12}), XML (<geo:lat>…</geo:lat>) and
     *  "Breite: 52,52 Länge: 13,405", also with a degree sign ("Lat: -24.99693°") or with the hemisphere a photo's "Ref"
     *  line gives in front ("GPS Latitude : S 24.99693", see `normalise`). Only plain values: one with minutes, seconds or
     *  a letter after it, or (unless in quotes) a direction or unit in words or brackets ("25 deg 5' 48.81\" S", "25.0969
     *  South", "25.0969 (S)", "120 cm"), is read by `latLon`, as are whole degrees with more fields after them ("24; 59;
     *  48.95", as Windows shows a photo's position). A number after a value with decimals isn't its minutes, nor is a
     *  road's marker after it its unit ("Lat -24.99693 Lon 31.59231 06:45", "… km 12"). Several (a track) must all be one
     *  place. */
    private fun labelled(t: String): Found? {
        val u = capitals(t)
        val value = if (hasDecimalPoint(u)) """([-+]?\d+(?:\.\d+)?)""" else """([-+]?\d+(?:,\d+)?)"""
        // (A degree, minute or second in words also with its direction right after it, "25.096893 degS"; not "de", a word.)
        val unit = (directionWords + listOf(degreeWords, minuteWords, secondWords, lengthWords, "D")).joinToString("|") +
            "|(?:" + listOf(degreeWords, minuteWords, secondWords).joinToString("|") + ")[NSEW]"

        // (The runs of spaces possessive, so that a long one after a label with no value isn't split every way.)
        fun values(names: String, letters: String): List<Triple<Double, NSRange, Double>> =
            matchRanges(
                """(?<![A-Z])(?:""" + names + """)(?![A-Z])[ \t]*+(["']?)[ \t]*+[:=>]?[ \t]*+(["']?)[ \t]*+(?:([""" + letters +
                    """])[ \t]++(?=\d))?""" + value + """(?!\d)\2(?:(?<=[.,]\d{1,30})[ \t]?°|[ \t]?°(?![ \t]*+(?:[-+]?\d|['"])))?""" +
                    """(?![ \t]*(?:[°'"]|[NSEWOZ](?![A-Z])|[.,]\d))(?:(?<=[.,]\d{1,30}["'°]?)|(?![ \t]*[;,]?[ \t]*\d))""" +
                    """(?:(?<=["'])|(?=[ \t]*KM[ \t]*-?[ \t]*\d)|(?![ \t]*(?:(?:""" + unit +
                    """)(?!\p{L})|\(\s*[NSEWOZ]\s*\))))""",
                u,
            ).mapNotNull { m ->
                val v = u.substring(m[4])
                val south = m[3].location != notFound && "SW".contains(u.substring(m[3]))
                swiftDouble(replacingOccurrences(v, ",", "."))?.let { Triple(if (south) -it else it, m[0], lastDigit(v)) }
            }
        val lats = values(latWords, "NS")
        val lons = values(lonWords, "EW")
        if (lats.isEmpty() || lats.size != lons.size) return null
        val points = lats.zip(lons).mapNotNull { (a, b) -> valid(a.first, b.first) }
        if (points.size != lats.size) return null
        if (!points.all { Geo.distance(it, points[0]) < 30 }) return Found.Unclear
        return Found.Place(
            Result(points[0], "decimal degrees"), (lats + lons).map { it.second },
            (lats + lons).maxOfOrNull { it.third } ?: 0.0,
        )
    }

    /** A column of a table's header row (see `table`): the column of the latitude's or longitude's values, its
     *  hemisphere's sign if its name gives it, and the column of its letters ("N/S"). */
    private class Column(val values: Int, val sign: Double?, val letters: Int?)

    /** A table whose header row names the latitude and longitude columns ("id,lat,lon", "Name,Count,Lat,Lon", GIS's "X;Y",
     *  ArcGIS's "FID,POINT_X,POINT_Y", rows copied from a sheet with their header), whatever is in the other columns (an
     *  id, a count, a name): the values in those two columns of the rows under it, each with as many columns. The
     *  hemisphere may be in a column's name ("Lat (S)", "Latitude (South)"; or a unit or datum: "Lat (deg)", "lon_dd") or
     *  in a column of its own ("LATITUDE,N/S,LONGITUDE,E/W", as a GPS logger writes them). Several rows must all be one
     *  place. */
    private fun table(t: String): Found? {
        val u = capitals(t)
        if (!u.contains("\n")) return null
        val lines = matchRanges("""[^\n]+""", u).map { it[0] }
        fun columns(line: NSRange, delimiter: String): List<String> =
            components(u.substring(line), delimiter).map { column -> trimming(column) { inWhitespaces(it) || it == '"'.code || it == '\''.code } }

        // The sign a hemisphere's letter or word gives the latitude (axis 0) or the longitude (1), if it is one of theirs.
        fun sign(s: String, axis: Int): Double? =
            if (has("^(?:" + directionWords[2 * axis] + "|" + listOf("N", "E")[axis] + ")$", s)) 1.0
            else if (has("^(?:" + directionWords[2 * axis + 1] + "|" + listOf("S", "W")[axis] + ")$", s)) -1.0
            else null
        val label = "(?<![A-Z])(?:$latWords|Y)(?![A-Z])"
        if (!has(label, u)) return null
        for ((k, header) in lines.withIndex()) {
            if (k == lines.size - 1) break
            if (!has(label, u.substring(header))) continue
            for (delimiter in listOf("\t", ",", ";", "|")) {
                val names = columns(header, delimiter)

                // The column of the latitude (axis 0) or the longitude (1), its hemisphere's sign if its name gives it, and
                // the column of its letters ("N/S").
                fun column(axis: Int): Column? {
                    val found = names.indices.mapNotNull { i ->
                        val m = matches(
                            "^(?:" + listOf("$latWords|(?:POINT_)?Y", "$lonWords|(?:POINT_)?X")[axis] +
                                """)(?:(?:[ \t]*\(|[ \t_]+)[ \t]*([\p{L}\d]+)[ \t]*\)?)?$""",
                            names[i],
                        ).firstOrNull() ?: return@mapNotNull null
                        val word = m[1] ?: return@mapNotNull Pair<Int, Double?>(i, null)
                        // Else a unit or a datum ("Lat (deg)", "lon_dd", "Lat (WGS84)"), not the other one's direction.
                        val s = sign(word, axis)
                        if (s != null) return@mapNotNull Pair<Int, Double?>(i, s)
                        if (sign(word, 1 - axis) == null) Pair<Int, Double?>(i, null) else null
                    }
                    val n = listOf("N", "E")[axis]
                    val s = listOf("S", "W")[axis]
                    val letters = names.indices.filter {
                        has("^(?:" + n + """[ \t]*[/-]?[ \t]*""" + s + "|" + s + """[ \t]*[/-]?[ \t]*""" + n + ")$", names[it])
                    }
                    if (found.size != 1 || letters.size > 1) return null
                    return Column(found[0].first, found[0].second, letters.firstOrNull())
                }
                val lat = column(0) ?: continue
                val lon = column(1) ?: continue
                val points = mutableListOf<GeoPoint>()
                val used = mutableListOf(header)
                var resolution = 0.0
                for (row in lines.subList(k + 1, lines.size)) {
                    val values = columns(row, delimiter)
                    if (values.size != names.size) break
                    // (A Markdown table's line under its header: "|---|---|".)
                    if (values.all { v -> characters(v).all { it.isIn("-:") } }) continue

                    // A value with the sign its hemisphere gives (with a minus sign, N or E says otherwise).
                    fun value(c: Column, axis: Int): Double? {
                        val v = swiftDouble(replacingOccurrences(values[c.values], ",", ".")) ?: return null
                        val s = (if (c.letters != null) sign(values[c.letters], axis) else c.sign)
                            ?: return if (c.letters == null) v else null
                        return if (v < 0) (if (s < 0) v else null) else s * v
                    }
                    val a = values[lat.values]
                    val b = values[lon.values]
                    val la = value(lat, 0) ?: return null
                    val lo = value(lon, 1) ?: return null
                    val p = valid(la, lo) ?: return null
                    points.add(p)
                    used.add(row)
                    resolution = maxOf(resolution, lastDigit(a), lastDigit(b))
                }
                val first = points.firstOrNull() ?: continue
                if (!points.all { Geo.distance(it, first) < 30 }) return Found.Unclear
                return Found.Place(Result(first, "decimal degrees"), used, resolution)
            }
        }
        return null
    }

    // Latitude and longitude

    @Suppress("EnumEntryName")
    private enum class Axis { lat, lon }

    private sealed interface Token {
        /** A number: its value, its digits before and after the point, and whether a minus sign was before it. */
        data class Number(val value: Double, val intDigits: Int, val places: Int, val negative: Boolean) : Token
        data object Degree : Token
        data object Minute : Token
        data object Second : Token

        /** N, S, E or W, whether a number is written right after it ("S28"), whether it is written right after a number or
         *  its mark ("70.6693W", "13.6\"W"), and whether a place follows it ("W of the bridge", "van Skukuza"). */
        data class Hemisphere(val letter: Char, val glued: Boolean, val attached: Boolean, val from: Boolean) : Token

        /** A latitude's and a longitude's letter written together before or after the numbers ("GPS (S/E): 24.99693,
         *  31.59232", "…, 31.59232 (S, E)"): the halves' letters, in their order. */
        data class Tag(val first: Char, val second: Char) : Token
        data object Separator : Token

        /** A slash right between two numbers ("27/10", "3/4", "-25.0969/31.4371"): a separator, or a date's or a fraction's,
         *  which a half with its letter after it doesn't start right after (see `backStart`). */
        data object Slash : Token

        /** Other text between the numbers: words, a name, a date, a time written with letters ("06h45"), or a colon after
         *  a number ("km 14: …"); whether it has letters. */
        data class Text(val word: Boolean) : Token

        /** A dash or colon between the fields of one half ("25-05-48.8", "25:05:48.8"), or of a time ("12:30"). */
        data class Joiner(val colon: Boolean) : Token

        /** "lat", "lon", or (null) altitude, accuracy and the like. */
        data class Label(val axis: Axis?) : Token
    }

    /** One half of a position, as `component` reads it. */
    private class Half(
        /** With its sign. */
        var value: Double,
        /** From its letter or label, if it has one. */
        var axis: Axis?,
        /** 0 decimal degrees, 1 decimal minutes, 2 seconds, 3 GPS (NMEA). */
        val kind: Int,
        val numbers: Int,
        /** Whether it can be a time: fields joined by colons, the last one whole, without a letter or label ("05:30",
         *  "12:30:45", "+02:00"; not "25:05:48 S"). */
        val time: Boolean,
        /** Whether something says where it starts: a letter or label before it, a minus sign on its first number, or a
         *  degree sign there with its letter or its other numbers' marks (not "Leopard 32° 40.6892, -74.0445": a
         *  temperature or an angle before a position). */
        val startMarked: Boolean,
        /** Whether it can only be a coordinate: it has a label, a minute or second mark, 3 decimal places or more, or a
         *  letter in front or after more than a whole number ("S 25", "25.0969 S", "25° S"). (A degree sign alone, or a
         *  degree in words, is also a temperature or an angle: "38°C", "25 grade"; a letter after a whole number a
         *  distance and its direction: "Lions 5 N of Satara".) */
        val strong: Boolean,
        /** Whether its letter is written right before its first number ("S28 25.0969"), as a road's name is. */
        val gluedLetter: Boolean,
        /** Whether a minus sign is before its first number. */
        val signed: Boolean,
        /** The size, in degrees, of one unit of its last digit. */
        val resolution: Double,
    )

    /** A position read from text, and how precisely it is written there (in degrees, the coarser of its two halves). */
    private class Reading(val result: Result, val resolution: Double)

    /** Symbols the words are replaced with before reading: a latitude, longitude and other label, and "and" between two
     *  numbers. */
    private const val latSymbol = '\u24D0'
    private const val lonSymbol = '\u24DE'
    private const val otherSymbol = '\u24E9'
    private const val andSymbol = '\u24D3'

    /** Labels in English, Afrikaans, Dutch, German, Spanish, Italian, French and Portuguese. */
    private const val latWords = "LATITUDE|LATITUD|LATITUDINE|LAT|BREEDTEGRAAD|BREEDTE|BREITENGRAD|BREITE"
    private const val lonWords = "LONGITUDE|LONGITUD|LONGITUDINE|LONG|LON|LNG|LENGTEGRAAD|LENGTE|LÄNGENGRAD|LÄNGE|LAENGE"

    /** Directions in the same languages (Dutch ZB, OL: zuiderbreedte, oosterlengte), north, south, east and west; a lone O
     *  is left for `latLon`. */
    private val directionWords = listOf(
        "NORTH|NTH|NOORD|NB|NORD|NORTE", "SOUTH|STH|SUID|ZUID|ZB|Z|SÜD|SUED|SUD|SUR|SUL",
        "EAST|OOS|OOST|OL|OST|EST|ESTE|LESTE", "WEST|WES|WL|OUEST|OESTE|OVEST",
    )

    /** Units: degrees, minutes and seconds in words, and lengths. */
    private const val degreeWords = "DEGREES|DEGREE|DEG|GRADEN|GRADE|GRAAD|GRADOS|GRADI|GRAUS|GRAD"
    private const val minuteWords = "MINUTES|MINUTE|MINUTOS|MINUTEN|MINUTI|MINS|MIN"
    private const val secondWords = "SECONDS|SECOND|SEGUNDOS|SEKUNDEN|SECONDES|SECONDEN|SECONDI|SEKONDES|SECS|SEC"
    private const val lengthWords = "MM|CM|M|KMS|KM|KILOMETERS?|KILOMETRES?|METERS?|METRES?|FT|FEET"

    /** Pairs of numbers, with their letters, labels and units. `language` is the text whose words may say which way a
     *  lone O is meant, when `t` is only a part of it. */
    private fun latLon(t: String, near: GeoPoint?, language: String? = null, beside: Boolean = false): Reading? {
        // "51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W" (Wikipedia): the same place written in more than one way.
        // The parts that read on their own must agree as closely as the least precise of them is written (Wikipedia
        // gives "31°08′E" as "31.133°E", 34 m from it), and the most precise one is taken. A part with a lone O is read
        // the way that agrees ("46°38′10″O / -46.6361" is west), the likelier way if both do. Two parts with one letter
        // each are the halves of one position ("S25 05.8136 / E31 26.2273"). (The " / " only from the start of a run of
        // spaces, so that a long run isn't tried from every place in it.)
        val pieces = components(replacing("""(?<!\s)\s+/\s+""", t, "\u0001"), "\u0001")
        val lang = language ?: t
        val oneEach = pieces.size == 2 && pieces.all { tokens(characters(prepared(it)), commaDecimal = false)?.letters?.size == 1 }
        val options = if (pieces.size in 2..4 && !oneEach) {
            pieces.map { latLonOnce(it, near, lang, beside) }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        if (options.size <= 1) return latLonOnce(t, near, lang, beside).firstOrNull()
        // Each way of taking one reading of each part, those with fewer second choices first.
        var ways: List<List<Int>> = listOf(emptyList())
        for (o in options) {
            ways = ways.flatMap { w -> o.indices.map { w + it } }
        }
        for (way in ways.sortedBy { it.sum() }) {
            val read = options.zip(way).map { (o, k) -> o[k] }
            val best = read.minByOrNull { it.resolution } ?: continue
            val tolerance = 30 + (read.maxOfOrNull { it.resolution } ?: 0.0) * 111_320
            if (read.all { Geo.distance(it.result.point, best.result.point) < tolerance }) return best
        }
        return null
    }

    /** The readings of a text, the likelier first: two when it has a lone O (east or west), else one or none. */
    private fun latLonOnce(t: String, near: GeoPoint?, language: String, beside: Boolean): List<Reading> {
        // Decimal commas only when there are no decimal points; if that reading fails, commas separate.
        val commaDecimal = !hasDecimalPoint(t) && has("""\d,\d""", t)
        val r = latLon(t, commaDecimal, near, language, beside)
        return if (r.isEmpty() && commaDecimal) latLon(t, false, near, language, beside) else r
    }

    /** Whether a number in the text has a decimal point: not a full stop at the end of a sentence ("31,59."), nor the
     *  points of a date or version ("27.10.2026", "2.5.1"). */
    private fun hasDecimalPoint(t: String): Boolean = has("""(?<![\d.])\d+\.\d+(?!\d|\.\d)""", t)

    private fun latLon(text: String, commaDecimal: Boolean, near: GeoPoint?, language: String, beside: Boolean): List<Reading> {
        val r = latLonPrepared(prepared(text), commaDecimal, near, language, beside)
        // "Secs" or "mins" read as the unit with south after it (see `prepared`), where the text isn't a position that
        // way: plurals, as the half has its letter in front ("S 24°59 mins 48.95 secs, E 31°35 mins 32.32 secE").
        if (r.isNotEmpty() || !has(unitWithDirection, text.uppercase())) return r
        return latLonPrepared(prepared(text, southAfterUnits = false), commaDecimal, near, language, beside)
    }

    /** Swift's `latLon(prepared:…)`. */
    private fun latLonPrepared(s: String, commaDecimal: Boolean, near: GeoPoint?, language: String, beside: Boolean): List<Reading> {
        // A lone O is east in Afrikaans, Dutch and German (Oos, Oost, Ost) but west in French, Spanish, Italian and
        // Portuguese (Ouest, Oeste, Ovest), and all of them write decimal commas. What decides, in this order:
        // - another word in the text from only one of the two groups ("Z", "Süd", "Breite", "Koordinaten": east; "Sur",
        //   "sud", "Latitud", "Coordenadas", "Estamos": west);
        // - else your position: the reading nearer to you. That is mostly the west one where O is written for west and
        //   the east one where it is written for east, as Spain, Portugal and Latin America lie west of Greenwich and
        //   Germany, the Netherlands and southern Africa east of it (France and Italy need one of their words);
        // - else east, as this app's users write it in Afrikaans.
        // The format says which way it was read.
        val lone = """(?<!\p{L})O(?![\p{L}'])""" // not the O of "O'Brien", "Posição" or "año"
        if (!has(lone, s)) {
            return listOfNotNull(read(s, commaDecimal, beside))
        }

        // (Glued to its number as the O was: "O31 35 32.3" is "E31 35 32.3", a letter in front.)
        fun readAs(letter: String): Reading? =
            read(replacing(lone, replacing(lone + """(?=\d)""", s, " $letter"), " $letter "), commaDecimal, beside)
        val east = readAs("E")
        val west = readAs("W")
        // Read the same either way, it isn't a direction (Portuguese "O ponto fica em …": "the").
        if (east != null && west != null && sameResult(east.result, west.result)) return listOf(east)
        var isWest = east == null
        if (east != null && west != null) {
            val u = language.uppercase()
            fun uses(words: String): Boolean = has("""\b(?:""" + words + """)\b""", u)
            val eastWords = uses(
                "SUID|NOORD|ZUID|ZB|NB|Z|SÜD|SUED|BREEDTE(?:GRAAD)?|LENGTE(?:GRAAD)?|BREITE(?:NGRAD)?" +
                    "|LÄNGE(?:NGRAD)?|LAENGE|KOORDINATEN?|KOÖRDINATES?|STANDORT|LIGGING|POSISIE|POSITIE|LOCATIE" +
                    "|GRADEN|GRAAD|UND|WIR|SIND|IST|BEI|IM|DER|MIT|AUF|ONS|NAAR|WIJ|ZIJN|BIJ|HET|EEN",
            )
            val westWords = uses(
                "SUR|SUL|SUD|NORTE|LATITUD|LONGITUD|LATITUDINE|LONGITUDINE|COORDENADAS|COORDONN[ÉE]ES" +
                    "|UBICACI[ÓO]N|LOCALIZA(?:ÇÃO|CAO|CI[ÓO]N)|POSI(?:ÇÃO|CAO|CI[ÓO]N|ZIONE)|DIRECCI[ÓO]N" +
                    "|ENDEREÇO|ESTAMOS|ESTOY|ESTOU|AQU[ÍI]|ICI|NOUS|SOMMES|SIAMO|QUI|PUNTO|PONTO|GRADOS|GRAUS" +
                    "|GRADI|EL|LOS|LAS|DEL|LE|LA|LES|DU|DES|IL|ET|CON|POR|PARA|AVEC|POUR|UNA|UMA|UNE|EM|NÃO",
            )
            if (eastWords != westWords) {
                isWest = westWords
            } else if (near != null) {
                isWest = Geo.distance(near, west.result.point) < Geo.distance(near, east.result.point)
            }
        }
        fun tagged(r: Reading?, west: Boolean): Reading? =
            r?.let { Reading(Result(it.result.point, it.result.format + if (west) " (O = west)" else " (O = east)"), it.resolution) }
        return listOfNotNull(tagged(if (isWest) west else east, isWest), tagged(if (isWest) east else west, !isWest))
    }

    /** A minute or second in words, a mark or a number with the direction right after it ("31°35min32.32secE",
     *  "31°35'32.32\"E", "31 35 32.32 E"). */
    private const val unitWithDirection = """(?:\d[ \t]?(?:SECONDS|SEC|MINUTES|MIN)|[\d'"][ \t]?)[NEW](?![A-Z])"""

    /** Upper case, with labels, directions and units as single symbols, and without the words that aren't part of the
     *  position. `southAfterUnits`: see below. */
    private fun prepared(text: String, southAfterUnits: Boolean = true): String {
        var s = " " + text.uppercase() + " "
        // The full stop of a unit's short form is part of it ("24°59 min. 48.95 sec. S", "24 deg. 59.816 min.").
        s = replacing("""(?<![A-Z])(DEGS?|MINS?|SECS?)\.""", s, "$1 ")
        // "Secs" and "mins" are plurals, or the unit with south right after it: south when a unit, mark or number in the
        // text has its direction right after it ("24°59min48.95secS, 31°35min32.32secE N of camp", "24°59'48.95secS
        // 31°35'32.32\"E"), at the end of the half, before the other half or the end of the text (not "mins" before the
        // seconds: "-24°59 mins 48.95 secs, 31°35 mins 32.32 secE"; nor "seen 10 mins ago"), unless that isn't a position
        // (see `latLon`).
        if (southAfterUnits && has(unitWithDirection, s)) {
            s = replacing(
                """(\d[ \t]?(?:SEC|MIN))S(?![A-Z])(?![ \t]*\d+(?:[.,]\d+)?[ \t]*(?:SEC|S(?![A-Z])|"))""" +
                    """(?=[\s,;/]*+(?:[-+]?\d|$))""",
                s, "$1 S",
            )
        }
        // An altitude's, a speed's or the like's label before its value, when that is left out with it.
        val otherLabel = "(?:$otherSymbol[ \\t:=]*+)?"
        fun fields(separator: String): String =
            """(\d{1,3})""" + separator + """[ \t]*([0-5]?\d)""" + separator + """[ \t]*([0-5]?\d(?:""" +
                (if (separator == ";") "[.,]" else """\.""") + """\d+)?)(?!\d|[;,][ \t]*\d)"""
        val commas = listOf(latWords, lonWords).all {
            has("""\b(?:""" + it + """)\b[ \t:=]*(?:[NSEW][ \t]+)?""" + fields(","), s)
        }
        val spacedFront = """(?<![A-Z\d.])(?<!\d[ \t])([NSEW])[ \t](\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)(?!\d|\.\d)"""
        val spacedAfter = """(?<![\d.])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)[ \t]([NSEW])(?![A-Z\d]|[ \t]+\d{1,3}\.\d)"""
        val words: List<Pair<String, String>> = listOf(
            // Degrees, minutes and seconds written with points, with the letter next to them ("S24.59.48.9", "24.59.48.9S",
            // "S 24.59.48.9", "24.59.48.9 S"), or with a degree sign and seconds with decimals ("S24°59.48.9"): not a date
            // or a version number, nor decimal minutes ("24°59.48'"); a full stop after them ends the sentence. A letter with
            // a space on each side goes with the one that has no other value on its other side, and then with the other ("S
            // 24.59.48.9 E 31.35.32.3").
            """(?<![A-Z\d.])([NSEW])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)(?!\d|\.\d)""" to "$1$2°$3'$4\"",
            """(?<![\d.])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)([NSEW])(?![A-Z])""" to "$1°$2'$3\"$4",
            spacedFront to "$1$2°$3'$4\"", spacedAfter to "$1°$2'$3\"$4", spacedFront to "$1$2°$3'$4\"", spacedAfter to "$1°$2'$3\"$4",
            """(?<![\d.])(\d{1,3})[ \t]?°[ \t]?([0-5]\d)\.([0-5]\d\.\d+)(?!\d|\.\d|')""" to "$1°$2'$3\"",
            // Quotes around a value, as in JSON, CSV and GPX ("lat":"-25.0969", lat="-25.0969"): not minutes or seconds.
            """(?<=[\s=:,;\[({])(["'])\s*([-+]?\d+(?:[.,]\d+)?)\s*\1(?=[\s,;:)}\]/>])""" to " $2 ",
            // Names with digits in them ("CLLocationCoordinate2D"): not numbers. Not a unit glued to the next field ("24
            // deg59.816' S").
            """(?<![A-Z0-9])(?!(?:""" + listOf(degreeWords, minuteWords, secondWords).joinToString("|") +
                """)\d{1,2}(?!\d))[A-Z]{2,}\d[A-Z0-9_]*""" to " ",
            // Words that only say a position follows ("GPS:", "Location:").
            """\b(GPS|POSITION|POS|LOCATION|LOC|COORDINATES|COORDINATE|COORDS|KOÖRDINATES|KOÖRDINATE|KOORDINATEN|COORDENADAS|COORDONNÉES)\b""" to
                " ",
            """\b(""" + latWords + """)\b""" to " $latSymbol ", """\b(""" + lonWords + """)\b""" to " $lonSymbol ",
            // Two labels joined by "and" are a heading, as "Lat/Long:" is ("Lat & Long:", "Breite und Länge:", "Latitud y
            // longitud:").
            "([$latSymbol$lonSymbol])[ \\t]*(?:&|\\+|AND|UND|EN|Y|E|ET)[ \\t]*(?=[$latSymbol$lonSymbol])" to "$1 ",
            // Länge and Lengte, Breite and Breedte also mean length and width: before a value in a unit of length they are
            // words, not labels ("Länge: 120 cm, Breite: 60 cm").
            "[$latSymbol$lonSymbol](?=[ \\t\"'=:]*[-+]?\\d+(?:[.,]\\d+)?[ \\t]*(?:$lengthWords)(?![A-Z]))" to " SIZE ",
            // Degrees, minutes and seconds with semicolons between them after a label, as Windows shows a photo's position
            // ("Latitude 24; 59; 48.95"), or with commas, as libexif does, when both labels have them ("Latitude: 24, 59,
            // 48.95\nLongitude: 31, 35, 32.32").
            "([$latSymbol$lonSymbol][ \\t:=]*(?:[NSEW][ \\t]+)?)" + fields(if (commas) "[;,]" else ";") to "$1$2°$3'$4\"",
            """(?<![A-Z])(ALTITUDE|ALT|ELEVATION|ELEV|ELE|HEIGHT|HOOGTE|ACCURACY|ACC|HDOP|SPEED|HEADING|BEARING|COURSE|ZOOM)(?![A-Z])""" to
                " $otherSymbol ",
            // A height above sea level, with its label ("GPS Altitude : 285.4 m Above Sea Level", as exiftool shows it). (Not
            // from inside a number, so that a long run of digits isn't tried from every digit in it.)
            otherLabel + """(?<![\d.,])[-+]?\d+(?:[.,]\d+)?\s*M\s+(?:ABOVE|BELOW)\s+SEA\s+LEVEL""" to " | ",
        )
        val directions: List<Pair<String, String>> =
            directionWords.zip(listOf("N", "S", "E", "W")).map { (w, l) -> """\b(""" + w + """)\b""" to " $l " }
        // Units in words and letters: the marks first, so that the minutes and seconds in words after them are read as
        // theirs ("25d 05 min 48.8 sec S", "25°05m 48.8 sec S"), not as a time left out below.
        val units: List<Pair<String, String>> = listOf(
            """(?:\b|(?<=\d))(""" + degreeWords + """)(?![A-Z])""" to "°",
            // Also with the direction right after it ("25.096893 degS").
            """(?<=\d|\d[ \t])DEG(?:REES?)?(?=[NSEW](?![A-Z]))""" to "°",
            // A temperature ("38°C", "32 deg C", "℃"): never part of a position.
            """(?<![\d.,])[-+]?\d+(?:[.,]\d+)?[ \t]*°[ \t]*[CF](?![A-Z])""" to " | ",
            """(\d)\s*D(?=\s*(?:\d|[NSEW](?![A-Z])|[,;]|$))""" to "$1°",
            // The letter written after the degrees of degrees and decimal minutes, or degrees, minutes and seconds, in words
            // goes after them ("24 deg S 59.816 min", "24 deg S 59 min 48.95 sec", "24degS 59min 48.95sec"), so that they
            // are read as its minutes and seconds (not whole minutes alone: "Heading 90° E 10 min ago" is a time).
            """(°[ \t]*)([NSEW])[ \t]*(\d{1,2}(?:[.,]\d+[ \t]*(?:""" + minuteWords + """)|[ \t]*(?:""" + minuteWords +
                """)[ \t]*\d{1,2}(?:[.,]\d+)?[ \t]*(?:""" + secondWords + """)))(?![A-Z])""" to "$1$3 $2",
            // S for seconds, right after the number and before the direction: after minutes written with an M
            // ("40d41m21sN", "25°05m48.8s S") or with the direction right after it ("40°41'21sN", below the minutes in
            // words). Not in "25°05'48.8S E 031°26'13.6", where it is south and the E starts the other half (see `read`).
            """(\d\s*M\s*\d+(?:[.,]\d+)?)S(?=\s*[NSEW](?![A-Z]))""" to "$1\"",
            // M for minutes only after degrees ("25d 5.8136m S", "25 05m S", "25 5m 48.8s S", "S24 59.816m E31 35.539m from
            // Pieter", "-24 deg59.816m | 31 deg35.539m"); elsewhere it is metres.
            """(°\s*\d+(?:[.,]\d+)?)\s*M(?![A-Z])""" to "$1'",
            """(?<![\d.,])(\d{1,3}[ \t]+[0-5]?\d(?:[.,]\d+)?)[ \t]*M(?![A-Z])""" to "$1'",
            // Minutes and seconds in words only after degrees and minutes ("25 deg 5 min 48.81 sec S", "25 05 48 sec S",
            // "25°05min48.8s"), also with the direction right after them ("…48.95secE"), not after a half's fields ("E31 26
            // 10 min ago"); else a time, left out below ("10 min S of Satara").
            """(°\s*\d+(?:[.,]\d+)?|(?<![\d.,]|\d[ \t]{1,3})\d{1,3}[ \t]+[0-5]?\d(?:[.,]\d+)?)\s*(?:""" + minuteWords +
                """)(?=[NSEW]?(?![A-Z]))""" to "$1'",
            """('\s*\d+(?:[.,]\d+)?|(?<![\d.,]|\d[ \t]{1,3})\d{1,3}[ \t]+[0-5]?\d[ \t]+[0-5]?\d(?:[.,]\d+)?)\s*(?:""" + secondWords +
                """)(?=[NSEW]?(?![A-Z]))""" to "$1\"",
            """('\s*\d+(?:[.,]\d+)?)[ \t]?S(?=[NSEW](?![A-Z]))""" to "$1\"",
            // A distance with its direction and no unit ("Lions 5 N of Satara", "3 S/W of Satara", "W 1 van die brug", "2.5 S
            // of Satara"): with two decimals only when not right after another half ("2.25 N of Satara", not "24.99 S 31.59
            // E from Pieter"), and not the minutes or seconds of a half ("31 35.5 E from Pieter", "31°35'32.32s from Pieter",
            // "E031-35-32.3 N of camp").
            """(?<![\p{L}\d.,°'"]|\d-[0-5]\d-)(?<![\d°'"][ \t]{1,3})(?:\d+(?:[.,]\d)?|(?<![\d°'"][ \t]{0,3}[NSEWO][ \t,;]{0,3})\d+[.,]\d\d)""" +
                """[ \t]*[NSEWO]""" +
                """(?:[ \t]*[/,][ \t]*[NSEWO])?[ \t]+(?:OF|VAN|FROM|VANAF)\b""" to " | ",
            """(?<![\p{L}\d.,])[NSEWO][ \t]*\d+(?:[.,]\d)?[ \t]+(?:OF|VAN|FROM|VANAF)\b""" to " | ",
            // Two letters before "of", a direction from a place ("… S/W of camp"), aren't the halves' letters.
            """(?<![\p{L}\d])[NSEW][ \t]*/[ \t]*[NSEW][ \t]+(?=(?:OF|VAN|FROM|VANAF)[ \t]+\p{L})""" to " ",
            // A distance, a height, a time or a speed ("Drive S 25 km", "…, 270 m", "S 30° W, distance 120 m", "10 min S
            // of Satara", "Wind S 10 kt, later E 15 kt"), or a range of them ("15-20 kts"): not part of the position. It
            // ends the position before it, so that the direction after it ("…, 31.59231 (3 km N of Skukuza)") isn't taken
            // for that position's letter. Not the end of a road's name ("H1-3 km 14", "S100 km 7"), nor a number before a
            // road's marker ("…, 31.59231 km 12": the marker is after "km") or before a unit written before its own value
            // when it has two decimals or more, as a coordinate has, or is minutes or seconds after another number ("…,
            // 31.592312 Bft 4", "…32.32 Bft 4", "… min 5", "S24 59 48.9 E31 35 32.3 Bft 4", "… 35 32 knots 15"), nor one
            // right after a degree sign, which is a position's minutes or seconds without their mark ("-24° 5948.95 sec").
            // An altitude's or an accuracy's label goes with it ("Altitude: 285.4 m\nLatitude: …").
            otherLabel + """(?<![\p{L}\d.,]|[\p{L}\d]-)(?<!°[ \t]{0,3})""" +
                """(?!(?:[-+]?\d+[.,]\d{2,}|(?<=\d[ \t]{1,3})[0-5]?\d(?:[.,]\d+)?)\s*[A-Z]+[ \t]*-?[ \t]*\d)""" +
                """[-+]?\d+(?:[.,]\d+)?(?:-\d+(?:[.,]\d+)?)?\s*(?!KM[ \t]*-?[ \t]*\d)(?:""" +
                lengthWords + """|MI|MILES?|NM|YDS?|YARDS?|MYL|HOURS?|HRS?|UUR|MINUTES?|MINS?|SECONDS?|SECS?|SEKONDES?""" +
                """|KTS?|KN|KNOTS?|KNOPE|BFT|MPH|KPH|KMH)(?![A-Z\d'"°])""" to " | ",
            // A speed written with its unit before it ("…, 31.592312 Bft 4", "kt 10").
            """(?<![\p{L}\d.,])(?:BFT|KTS?|KNOTS?|KNOPE|KN|MPH|KPH|KMH)[ \t]*\d+(?:[.,]\d+)?(?![\d.,'"°])""" to " | ",
            // The direction a wind blows from ("Wind S 24, tomorrow E 31", "Wind S 24.9, E 31.5 (km/h)"): not a position's,
            // which a number follows that a wind's speed doesn't have, with two decimals or more, a mark or another number
            // ("…out of the wind S24 59.816 E31 35.539", "Wind: S 24.99 E 31.59").
            """(?<![A-Z])(WIND(?:RIGTING|RICHTUNG)?|BREEZE)[ \t]*:?[ \t]*(?:FROM[ \t]+)?[NSEW]{1,3}(?![A-Z])""" +
                """(?![ \t]*\d+(?:[.,]\d{2}|[ \t]*[°'"]|[ \t]+\d))""" to " $1 ",
            // "And" between two numbers ("Lions at -25.0969 and 31.4371") separates them, as a comma does; it is still
            // a word ("25° and 31°" aren't a position).
            """(?<=[\d°'"])[ \t]+(?:AND|EN|UND|ET|&)[ \t]+(?=[-+]?\d)""" to " $andSymbol ",
        )
        for ((p, r) in words + directions + units) {
            s = replacing(p, s, r)
        }
        // A heading's or a bearing's direction, as or after its value ("heading W", "Heading 180° S"): not a position's
        // letter. As its only value, it goes with its label when a position follows (a number with another or with a
        // letter after it, a minus sign, or the position's labels), so that the label doesn't take a value of the position
        // ("Heading W: 24 59.816 S …", "Lions heading W 24°59'48.95s, …", "Heading W. -25.0969, 31.4371", "Heading W:
        // Lat/Long: …"), and when no number follows it, so that the label isn't a column of a header row ("Heading:
        // W\nSpecies: Lion\nLat/Long: …"); else the label keeps the value after it ("heading W: 270°", "course S: 3
        // males"). Written right before a number ("Heading: S24.99693 E31.59231"), or before a position whose other half
        // has its letter in front too and none after it ("Heading S 24 59.816 E 31 35.539", "Heading S 24.99693, E
        // 31.59231"), it is the position's letter. After a value, it is the position's letter when a number without a
        // sign follows it, and that value the position's too, not the label's, when the letter is written right after
        // it ("Lions heading 24.99693S 31.59231E at 06:45").
        val position = """\s*+(?:[.:,;][ \t.:,;-]*+)?+(?:\d++(?:[.,]\d++)?+[ \t]*+°?+(?:[ \t,;/]*+[-+]?\d|[ \t]*+[NSEW](?![A-Z]))|-\d|[""" +
            "$latSymbol$lonSymbol])"
        val notPositionLetter = """(?![A-Z\d])(?![ \t]*+\d[\d.,°'" \t]*+(?<![A-Z])[NSEW](?![A-Z])[ \t]*+\d[\d.,°'"]*+""" +
            """(?:[ \t]++\d[\d.,°'"]*+)*+(?![ \t]*+[NSEW](?![A-Z])))"""
        s = replacing("$otherSymbol[ \\t:]*[NSEW]{1,3}$notPositionLetter(?=$position)", s, " ")
        s = replacing("$otherSymbol[ \\t:]*[NSEW]{1,3}(?![A-Z])(?![ \\t:]*[-+.]?\\d)", s, " ")
        s = replacing("($otherSymbol[ \\t:]*)[NSEW]{1,3}$notPositionLetter", s, "$1")
        s = replacing("($otherSymbol[ \\t:]*\\d+(?:[.,]\\d+)?[ \\t]*°?[ \\t]*)[NSEW]{1,3}(?![A-Z])(?![ \\t]*[+.]?\\d)", s, "$1")
        s = replacing("$otherSymbol[ \\t:]*(?=\\d+(?:[.,]\\d+)?[°'\"]?[NSEW](?![A-Z\\d])[ \\t]+[+.]?\\d)", s, " ")
        // A label only before a value or another label, or at the end of a line (a header row): "Long day!" isn't one.
        val symbols = "$latSymbol$lonSymbol$otherSymbol"
        s = replacing("[$symbols](?![ \\t\"'=:>(\\[{/|\\\\,;]*(?:[-+.\\d$symbols]|\\n|$)|\\s+[-+.]?\\d)", s, " ")
        // X and Y as GIS writes them in degrees: X the longitude, Y the latitude, also ArcGIS's POINT_X and POINT_Y. Only
        // together, so a lone "x" isn't one.
        val xy = listOf(
            """(?<![A-Z0-9_.])(?:POINT_)?X(?![A-Z0-9_])""" to lonSymbol, """(?<![A-Z0-9_.])(?:POINT_)?Y(?![A-Z0-9_])""" to latSymbol,
        )
        if (xy.all { has(it.first, s) }) {
            for ((p, symbol) in xy) {
                s = replacing(p, s, " $symbol ")
            }
        }
        // A dash with a space on each side between labels and their values is a separator when a value after one has a
        // sign or a letter of its own ("Lat - -24.99693\nLon - 31.59231", "Latitude - 24.99693 S"); else it isn't clear
        // whether it is a minus sign (see `tokens`).
        val dash = "([$symbols])[ \\t]+-[ \\t]+"
        if (has(dash + """(?:[-+]|[NSEW](?![A-Z])|\d[\d.,°'" \t]*[NSEW](?![A-Z]))""", s)) {
            s = replacing(dash, s, "$1: ")
        }
        return s
    }

    private fun isWordLetter(c: Ch?): Boolean =
        c != null && c.isLetter && !c.isChar(latSymbol) && !c.isChar(lonSymbol) && !c.isChar(otherSymbol) && !c.isChar(andSymbol)

    /** Reads a prepared text. An S right after seconds ("25°05'48.815s", "25 05 48.8s") is south, or else, when the text
     *  isn't a position that way and has letters or a minus sign that say the hemispheres, their mark ("S 25°05'48.815s
     *  E 31°26'13.636s", "-25°05'48.815s, 31°26'13.636s", "25°05'48s S, 31°26'13s E"). */
    private fun read(s: String, commaDecimal: Boolean, beside: Boolean): Reading? {
        readLetters(s, commaDecimal, beside)?.let { return it }
        val marked = replacing("""((?:'[ \t]*|(?<![\d.,])\d{1,3}[ \t]+[0-5]?\d[ \t]+)[0-5]?\d(?:[.,]\d+)?)S(?![A-Z])""", s, "$1\"")
        if (marked == s || !has("""(?<![A-Z])[NSEW](?![A-Z])|(?<![\d.])-[ \t]*\d""", marked)) return null
        return readLetters(marked, commaDecimal, beside)
    }

    /** With three letters, one of the outer two isn't the position's: the first letter of a road's name, written right
     *  before a whole number ("S3 24 59.816 S 31 35.539 E", "Lions S25 07.178 E31 55.057 S28 3 males"), or a direction, of
     *  travel before a position with its letters after the numbers ("Lions walking W 24 59.816 S 31 35.539 E") or from a
     *  place after one with its letters in front ("S24 59 48.9 E31 35 32.3 N of camp"). Each is tried as such. A reading
     *  with the latitude's letter first, as positions are written, is taken rather than one with the longitude's first
     *  (only one of them can have it); a direction is only taken so ("S 25°05'48.8\" E 31°26'13.6s" isn't 25° E, 31° S
     *  after a direction). Else the readings of names must be one place, or else a half's letter is written twice.
     *  With four on more than one line, the same place may be written twice, as exiftool shows a photo's position ("GPS
     *  Latitude : 24 deg 59' 48.95\" S", "GPS Longitude : …", "GPS Position : … S, … E"): it is read on each side of the
     *  line break between the second and the third letter, and the two must be one place. */
    private fun readLetters(s: String, commaDecimal: Boolean, beside: Boolean): Reading? {
        val chars = characters(s)
        val all = tokens(chars, commaDecimal) ?: return null
        val letters = all.letters
        if (letters.size == 4) {
            val cut = (letters[1] until letters[2]).lastOrNull { chars[it].isChar('\n') }
            if (cut != null) {
                val a = readLetters(string(chars, 0, cut), commaDecimal, beside)
                val b = readLetters(string(chars, cut, chars.size), commaDecimal, beside)
                if (a == null || b == null || !samePlace(a, b)) return null
                return if (a.resolution <= b.resolution) a else b
            }
        }
        if (letters.size != 3) {
            return if (letters.size > 3) null else reading(all.tokens, all.words, beside)
        }

        // Whether the letter at `i` is written right after a number or its mark ("59.816 S", "48.9\"S"), or right before a
        // number ("S24", "S 24", "S: -24").
        fun afterNumber(i: Int): Boolean {
            var k = i - 1
            while (k >= 0 && chars[k].isIn(" \t(")) k -= 1
            return k >= 0 && (chars[k].isNumber || chars[k].isIn("°'\""))
        }
        fun beforeNumber(i: Int): Boolean {
            var k = i + 1
            while (k < chars.size && chars[k].isIn(" \t:)")) k += 1
            return k < chars.size && (chars[k].isNumber || (k + 1 < chars.size && chars[k].isIn("-+.") && chars[k + 1].isNumber))
        }
        // (A direction is a word of its own, not glued to a number or mark: "03.4°S", "W24".)
        val first = letters[0]
        val middle = letters[1]
        val last = letters[2]
        val outer = listOf(
            Triple(first, afterNumber(middle) && afterNumber(last) && chars[first + 1].isIn(" \t\n"), middle to last),
            Triple(
                last, beforeNumber(first) && beforeNumber(middle) && !beforeNumber(last) && chars[last - 1].isIn(" \t\n("),
                first to middle,
            ),
        )
        val found = mutableListOf<Reading>()
        for ((i, direction, rest) in outer) {
            if (!(direction || nameShaped(chars, i))) continue
            val t = tokens(chars, commaDecimal, names = setOf(i)) ?: continue
            val r = reading(t.tokens, t.words, beside) ?: continue
            if (chars[rest.first].isIn("NS") && chars[rest.second].isIn("EW")) return r
            if (nameShaped(chars, i)) found.add(r)
        }
        // Else one half's letter may be written both before and right after its numbers ("S 24°59' 48.95\", E 31°35'
        // 32.32\"E"): it is read once.
        for ((a, b) in listOf(first to middle, middle to last)) {
            if (!(found.isEmpty() && chars[a].key == chars[b].key && !chars[b - 1].isWhitespace &&
                    (a + 1 until b).all { chars[it].isNumber || chars[it].isIn(" .,°'\"") })
            ) {
                continue
            }
            val once = chars.toMutableList()
            once[b] = characters(" ")[0]
            val t = tokens(once, commaDecimal)
            if (t != null) reading(t.tokens, t.words, beside)?.let { return it }
        }
        val f = found.firstOrNull() ?: return null
        if (!found.all { samePlace(it, f) }) return null
        return f
    }

    /** What `tokens` read. */
    private class Tokens(val tokens: List<Token>, val letters: List<Int>, val words: Boolean)

    /** The numbers, letters, labels, marks and other text of a prepared text, where its N, S, E and W letters are, and
     *  whether it has other words. The letters at `names` start a name. Null when a number has a grid zone's letter after
     *  it ("36J 342401 …", "36JUT4240123411"): `utm`, `mgrs` and `inText` read that text, and the other place is looked for. */
    private fun tokens(chars: List<Ch>, commaDecimal: Boolean, names: Set<Int> = emptySet()): Tokens? {
        val tokens = mutableListOf<Token>()
        val letters = mutableListOf<Int>()
        var i = 0
        var pendingNegative = false
        // Letters that aren't a direction, label or unit: other words around the numbers.
        var words = false
        fun text(word: Boolean = true) {
            words = words || word
            val before = tokens.lastOrNull()
            if (before is Token.Text) {
                tokens[tokens.size - 1] = Token.Text(word = before.word || word)
            } else {
                tokens.add(Token.Text(word = word))
            }
        }
        while (i < chars.size) {
            val c = chars[i]
            if (c.isNumber || (c.isChar('.') && i + 1 < chars.size && chars[i + 1].isNumber)) {
                // Glued to a letter other than N/S/E/W: a name ("H4-1", "R40", a plus code), other text, not a number of
                // the position.
                if (i > 0 && isWordLetter(chars[i - 1]) && !chars[i - 1].isIn("NSEW")) {
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                // A date ("27/10/2026", "2026/10/27", "27/10/26", "2026-10-27"): other text, not numbers of the position.
                val end = dateEnd(chars, i)
                if (end != null) {
                    i = end
                    text(word = false)
                    pendingNegative = false
                    continue
                }
                // A road's marker after its name and "km" ("H4-1 km 3 24 59.816 S …"), not after a distance ("5 km 24 …"):
                // other text.
                if (tokens.lastOrNull() is Token.Text && !(tokens.size > 1 && isNumber(tokens[tokens.size - 2])) && afterKM(chars, i)) {
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                val start = i
                val digits = StringBuilder()
                var intDigits = 0
                var places = 0
                var seenPoint = false
                while (i < chars.size) {
                    val d = chars[i]
                    if (d.isNumber) {
                        digits.append(d.text)
                        if (seenPoint) places += 1 else intDigits += 1
                    } else if (!seenPoint && (d.isChar('.') || (commaDecimal && d.isChar(','))) && i + 1 < chars.size && chars[i + 1].isNumber) {
                        digits.append(".")
                        seenPoint = true
                    } else {
                        break
                    }
                    i += 1
                }
                // A second point ("27.10.2026", "2026.10.27", "1.2.3"): a date or version, other text, not two numbers.
                if (seenPoint && i + 1 < chars.size && chars[i].isChar('.') && chars[i + 1].isNumber) {
                    while (i < chars.size && (chars[i].isNumber || (chars[i].isChar('.') && i + 1 < chars.size && chars[i + 1].isNumber))) {
                        i += 1
                    }
                    text()
                    pendingNegative = false
                    continue
                }
                val v = swiftDouble(digits.toString()) ?: return null
                if (i < chars.size && isWordLetter(chars[i]) && !chars[i].isIn("NSEW")) {
                    if (gridZone(chars, start)) return null
                    // Else a time, a count or a unit written with letters ("06h45", "9:15am", "4x4", "2x", "3rd", "5L"):
                    // other text, with the hour before a colon.
                    while (tokens.lastOrNull().let { it is Token.Joiner && it.colon }) {
                        tokens.removeAt(tokens.size - 1)
                        if (tokens.lastOrNull() is Token.Number) tokens.removeAt(tokens.size - 1)
                    }
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                tokens.add(Token.Number(value = v, intDigits = intDigits, places = places, negative = pendingNegative))
                pendingNegative = false
                continue
            }
            when {
                // Between whole degrees or minutes and 2 digits that can be minutes or seconds ("25-05-48.8S",
                // "031-26-13.6E") a dash joins the fields of one half, and right after a half that ends in its letter
                // ("25.0969 S - 31.4371 E") it separates the halves, unless it is written as a minus sign, after a space and
                // right before a number ("…E -25.0969, 31.4371": the same place again, in decimals). So it does with a space
                // on each side after a half with a minus sign before it, whose writer puts a minus sign right before its
                // number ("-25.0969 - 31.4371", "-24°59.815' - 31°35.539'"; not times, "-25:05 - 31:26", which may be a
                // range). Between degrees and a number it joins a range ("18°-31°", "18° - 31°"), unless it is written as a
                // minus sign ("Bearing 270° -25.0969, 31.4371"). Elsewhere it is a minus ("40-74.0445", "\"-25.0969\"").
                c.isChar('-') -> {
                    var back = i - 1
                    var ahead = i + 1
                    while (back > 0 && (chars[back].isChar(' ') || chars[back].isChar('\t'))) back -= 1
                    while (ahead < chars.size && (chars[ahead].isChar(' ') || chars[ahead].isChar('\t'))) ahead += 1
                    val minus = i > 0 && chars[i - 1].isWhitespace && i + 1 < chars.size &&
                        (chars[i + 1].isNumber || chars[i + 1].isChar('.'))
                    if (fieldDash(chars, i) || (
                            !minus && back > 0 && chars[back].isChar('°') && chars[back - 1].isNumber &&
                                ahead < chars.size && chars[ahead].isNumber
                            )
                    ) {
                        tokens.add(Token.Joiner(colon = false))
                    } else {
                        val spaced = i > 0 && chars[i - 1].isIn(" \t") && i + 1 < chars.size && chars[i + 1].isIn(" \t")
                        // Right between a label and its value ("Lat - 24.9969"; not "Lat: - 24.9969") it is a separator or a
                        // minus sign: it isn't clear which (see `prepared` for when it is).
                        if (spaced && (chars[back].isChar(latSymbol) || chars[back].isChar(lonSymbol))) return null
                        val previous = tokens.lastOrNull()
                        if (spaced && previous != null && (isMark(previous) || decimalPlaces(previous) > 0) && signedRun(tokens)) {
                            tokens.add(Token.Separator)
                        } else if (!minus && tokens.size > 1 && tokens[tokens.size - 1] is Token.Hemisphere &&
                            (isNumber(tokens[tokens.size - 2]) || isMark(tokens[tokens.size - 2]))
                        ) {
                            tokens.add(Token.Separator)
                        } else {
                            pendingNegative = true
                        }
                    }
                }
                c.isChar('+') -> pendingNegative = false
                // After a whole number and before another a colon joins them too: a time ("12:30") or fields ("25:05:48.8 S"),
                // not two halves. After a number and before anything else, it ends what is before it ("km 14: 24 22.872 S").
                c.isChar(':') -> {
                    val previous = tokens.lastOrNull()
                    if (i > 0 && chars[i - 1].isNumber && i + 1 < chars.size && chars[i + 1].isNumber &&
                        previous is Token.Number && previous.places == 0
                    ) {
                        tokens.add(Token.Joiner(colon = true))
                    } else if (i > 0 && chars[i - 1].isNumber) {
                        text(word = false)
                    }
                }
                c.isChar('°') -> tokens.add(Token.Degree)
                c.isChar('\'') -> tokens.add(Token.Minute)
                c.isChar('"') -> tokens.add(Token.Second)
                c.isIn(",;|\n\t") -> tokens.add(Token.Separator)
                c.isChar('/') ->
                    tokens.add(if (i > 0 && chars[i - 1].isNumber && i + 1 < chars.size && chars[i + 1].isNumber) Token.Slash else Token.Separator)
                // A number sign before a count ("#3"): other text.
                c.isChar('#') -> text(word = false)
                c.isChar(latSymbol) -> tokens.add(Token.Label(Axis.lat))
                c.isChar(lonSymbol) -> tokens.add(Token.Label(Axis.lon))
                c.isChar(otherSymbol) -> tokens.add(Token.Label(null))
                // A word that separates two numbers as a comma does, among other words.
                c.isChar(andSymbol) -> {
                    tokens.add(Token.Separator)
                    words = true
                }
                c.isIn("NSEW") -> {
                    // A road named by a letter and a number ("S3", "S100", "N4"): other text.
                    if (i in names || isName(chars, i)) {
                        i = afterName(chars, i + 1)
                        text()
                        pendingNegative = false
                        continue
                    }
                    val end = tagEnd(chars, i)
                    if (end != null) {
                        tokens.add(Token.Tag(c.key[0], chars[end - 1].key[0]))
                        i = end
                        pendingNegative = false
                        continue
                    }
                    if (isHemisphere(chars, i)) {
                        tokens.add(
                            Token.Hemisphere(
                                c.key[0], glued = i + 1 < chars.size && chars[i + 1].isNumber,
                                attached = i > 0 && (chars[i - 1].isNumber || chars[i - 1].isIn("°'\"")),
                                from = has("""^[ \t]+(?:OF|VAN|VANAF|FROM)[ \t]+\p{L}""", string(chars, i + 1, min(chars.size, i + 12))),
                            ),
                        )
                        letters.add(i)
                    } else {
                        text()
                    }
                }
                else -> if (isWordLetter(c)) text()
            }
            if (!c.isChar('-') && !c.isChar('+') && !c.isChar(' ')) pendingNegative = false
            i += 1
        }
        return Tokens(tokens, letters, words)
    }

    /** Where a date that starts at `i` ends ("27/10/2026", "2026/10/27", "27/10/26", "2026-10-27"), if one does. */
    private fun dateEnd(chars: List<Ch>, i: Int): Int? {
        val s = string(chars, i, min(chars.size, i + 14))
        val date = """^(?:\d{1,4}/\d{1,2}/\d{2,4}|\d{4}-\d{1,2}-\d{1,2}|\d{1,2}-\d{1,2}-\d{4})(?![\d/-]|\.\d)"""
        return matchRanges(date, s).firstOrNull()?.let { i + it[0].length }
    }

    /** Whether the number at `i` is right after "km" after a road's name ("H4-1 km 3", "S100 Km-12"): its marker. */
    private fun afterKM(chars: List<Ch>, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && chars[k].isIn(" \t-")) k -= 1
        if (!(k > 0 && chars[k].isChar('M') && chars[k - 1].isChar('K'))) return false
        k -= 2
        while (k >= 0 && (chars[k].isChar(' ') || chars[k].isChar('\t'))) k -= 1
        return k >= 0 && chars[k].isNumber
    }

    /** Where a latitude's and a longitude's letter written together ("S/E", "S, E", "S E") that start at `i` end, when
     *  they are before the numbers or after them, not between them ("24.99693 S/E 31.59232": each half's own letter). With
     *  a colon after them, they are before the numbers whatever is before them ("06:45 GPS (S/E): …"). So they are, in
     *  the order of the halves, written otherwise in a heading with a colon or a line break after it ("Lat (S) Long (E):
     *  …", "Lat (S)\tLong (E)\n…", "Lon (E), Lat (S)\n…"). */
    private fun tagEnd(chars: List<Ch>, i: Int): Int? {
        if (isWordLetter(if (i > 0) chars[i - 1] else null)) return null
        var k = i + 1
        while (k < chars.size && chars[k].isIn(" \t()/,;|")) k += 1
        if (!(k > i + 1 && k < chars.size && chars[k].isIn(if (chars[i].isIn("NS")) "EW" else "NS") &&
                !isWordLetter(if (k + 1 < chars.size) chars[k + 1] else null))
        ) {
            return null
        }
        var next = k + 1
        while (next < chars.size && chars[next].isIn(" \t)")) next += 1
        val before = isHemisphere(chars, k)
        val colon = next < chars.size && chars[next].isChar(':')
        // (With only a space between them, not the first right after a number: "Site S E 24.99693 31.59231", "…, 31.59231 S
        // E", not "…, 31.59231N E…".)
        val gap = chars.subList(i + 1, k)
        val separators = gap.count { it.isChar('/') || it.isChar(',') }
        val first = isHemisphere(chars, i)
        val together = chars[i].isIn("NS") && separators <= 1 && !gap.any { it.isIn("();|") }
        // (In brackets of their own between numbers they are together: "…, 31.592312 (S/E) 2 males".)
        val bracketed = i > 0 && chars[i - 1].isChar('(') && k + 1 < chars.size && chars[k + 1].isChar(')')
        if (together && (first != before || (before && (colon || bracketed))) &&
            (separators == 1 || i == 0 || chars[i - 1].isIn(" \t\n("))
        ) {
            return k + 1
        }
        val heading = colon || (next < chars.size && chars[next].isChar('\n') && !first)
        return if (heading && !(together && separators == 1)) k + 1 else null
    }

    /** The latitude's and the longitude's letter written together in the text (see `Token.Tag`), if it has them. */
    private fun tag(t: String): Pair<Char, Char>? {
        for (tok in tokens(characters(prepared(t)), commaDecimal = false)?.tokens ?: emptyList()) {
            if (tok is Token.Tag) return tok.first to tok.second
        }
        return null
    }

    /** Whether the numbers at the end of the tokens, with their marks and joiners, start with a minus sign. */
    private fun signedRun(tokens: List<Token>): Boolean {
        var k = tokens.size
        while (k > 0 && (isNumber(tokens[k - 1]) || isMark(tokens[k - 1]) || isJoiner(tokens[k - 1]))) k -= 1
        return k < tokens.size && isNegative(tokens[k])
    }

    /** Whether the number at `i` is a UTM zone with its band letter, before an easting or an MGRS square ("36J 342401",
     *  "36J UT 42401", "36JUT4240123411"), not a time or a count written with letters ("06h45", "4x4", "5L water", "12x"). */
    private fun gridZone(chars: List<Ch>, i: Int): Boolean =
        has("""^\d{1,2}[C-HJ-NP-X](?:\s*[A-HJ-NP-Z][A-HJ-NP-V]\s*\d|[\s,:;]+\d)""", string(chars, i, min(chars.size, i + 12)))

    /** A position from the tokens of a text, with other words among them or not. `beside`: whether the text is what is
     *  left beside a place read in it before. */
    private fun reading(all: List<Token>, words: Boolean, beside: Boolean = false): Reading? {
        var tokens = all.toMutableList()
        var words = words
        // An altitude's, a bearing's or the like's value before the position's labels, with no other number between them,
        // goes with its label (see below), not before their heading ("Bearing 270° Lat/Long: -24.99693, 31.59231",
        // "Heading: 270°\nSpecies: Lion\nLat/Long: …"; but "Lat Lon Zoom -24.99693 31.59231 15" is a header row).
        for (k in tokens.indices.reversed()) {
            if (!(k + 1 < tokens.size && isNumber(tokens[k + 1]))) continue
            val label = tokens[k]
            if (!(label is Token.Label && label.axis == null)) continue
            var end = k + 2
            if (end < tokens.size && isMark(tokens[end])) end += 1
            val next = (end until tokens.size).firstOrNull { val tok = tokens[it]; if (tok is Token.Label && tok.axis != null) true else isNumber(tok) }
            if (next != null && isLabel(tokens[next])) tokens.subList(k, end).clear()
        }
        // Two or more labels before the first number are a heading ("Lat/Long:", a spreadsheet's header row): they give
        // the order of the values after them, they don't label the first one.
        var headingFirst: Axis? = null
        var firstNumber = tokens.indexOfFirst(::isNumber).let { if (it < 0) tokens.size else it }
        var heading: List<Axis?> = tokens.subList(0, firstNumber).filterIsInstance<Token.Label>().map { it.axis }
        // A heading's or the like's label with no value of its own before the position's labels ("Heading:\nLatitude:
        // 24.99693 S") isn't a heading of both.
        if (heading.size >= 2 && heading.contains(null) && (!heading.contains(Axis.lat) || !heading.contains(Axis.lon))) {
            val before = firstNumber
            tokens = tokens.filterIndexed { k, tok -> if (tok is Token.Label && tok.axis == null) k >= before else true }.toMutableList()
            firstNumber = tokens.indexOfFirst(::isNumber).let { if (it < 0) tokens.size else it }
            heading = heading.filter { it != null }
        }
        if (heading.size >= 2) {
            val before = firstNumber
            tokens = tokens.filterIndexed { k, tok -> k >= before || !isLabel(tok) }.toMutableList()
            val numbers = tokens.filter(::isNumber)
            if (heading.contains(null)) {
                // With altitude and the like as well: one number each.
                val la = heading.indexOf(Axis.lat)
                val lo = heading.indexOf(Axis.lon)
                if (numbers.size != heading.size || la < 0 || lo < 0) return null
                tokens = mutableListOf(Token.Label(Axis.lat), numbers[la], Token.Separator, Token.Label(Axis.lon), numbers[lo])
            } else {
                if (heading.size != 2 || heading[0] == heading[1]) return null
                headingFirst = heading[0]
            }
        }
        // Altitude, a bearing and the like, with their values: not part of the position. One in degrees, or with another
        // number right after it, may be the first of a list ("Bearing 10° 20° 30°", "Heading 10 20 30"): then the rest is
        // read as among other words.
        var k = 0
        while (k < tokens.size) {
            val label = tokens[k]
            if (!(label is Token.Label && label.axis == null)) {
                k += 1
                continue
            }
            var end = k + 1
            while (end < tokens.size && isMark(tokens[end])) end += 1 // the quotes of "altitude": 312
            if (end < tokens.size && isNumber(tokens[end])) {
                end += 1
                if (end < tokens.size && tokens[end] is Token.Degree) words = true
                if (end < tokens.size && isMark(tokens[end])) end += 1 // its unit, "312 m" read as minutes
                if (end < tokens.size && isNumber(tokens[end])) words = true
            } else {
                end = k + 1
            }
            tokens.subList(k, end).clear()
        }
        // A latitude's and a longitude's letter written together (see `Token.Tag`), for halves without letters, and whether
        // they are after the numbers.
        val lastNumber = tokens.indexOfLast(::isNumber).let { if (it < 0) tokens.size else it }
        val tags = tokens.indices.mapNotNull { j ->
            val tag = tokens[j]
            if (tag is Token.Tag) listOf(tag.first, tag.second) to (j > lastNumber) else null
        }
        tokens.removeAll { it is Token.Tag }
        // A letter after a pair written with a minus sign is a direction, where something is going or where it is from
        // ("-24.9969, 31.5923 W of Skukuza", "… (W of the bridge)", "… W 270°", "… moving W"), not a hemisphere: it doesn't
        // change a sign. So is an S or W before it, or any letter after other words ("Wild dogs moving W -24.9969, 31.5923",
        // "N4 W to Nelspruit, -25.4753, …", "Lions going N -24.9969 31.5923"). (N or E next to a number with a minus sign
        // still says two things: "-25.0969, -31.4371 E", "N -25.0969, 31.4371".) So too, after any pair, is the only letter
        // with a place after it ("24.9969, 31.5923 (W of the bridge)"; not "24.99 S 31.59 E from Pieter").
        // A letter right after the last number, with nothing after it, may as well be the hemisphere of the second half: a
        // W of one without a minus sign ("-33.4489, 70.6693 W"), an N or S of one that can be a latitude, written
        // longitude first ("31.4371, -25.0969 S", "-74.0445, 40.6892 N"; but "-24.9969, 31.5923 N" is written the same
        // way). It isn't clear which (see below). Written right after the number or its mark, which a direction isn't, it
        // is that half's hemisphere ("-33.4489 70.6693W", "…, 31°26'13.6\"W", "31.4371, -25.0969S"), but an S so written
        // may as well be the first half's, when a minus sign before that says south too ("-24.9969, 31.5923S", latitude
        // first): it isn't clear which either (see below).
        var hemis = tokens.indices.filter { tokens[it] is Token.Hemisphere }
        var trailing: Char? = null
        var attachedSouth = false
        if (hemis.size == 1) {
            val hemisphere = tokens[hemis[0]] as Token.Hemisphere
            val first = tokens.indexOfFirst(::isNumber)
            val last = tokens.indexOfLast(::isNumber)
            if ((hemisphere.from || tokens.any(::isNegative)) && first >= 0 && last >= 0) {
                val j = hemis[0]
                val h = hemisphere.letter
                val after = j > last && !hemisphere.attached
                val before = j < first && isNegative(tokens[first])
                attachedSouth = j > last && hemisphere.attached && h == 'S'
                val afterWords = tokens.subList(0, j).any { it is Token.Text && it.word }
                if ((after && !(isNegative(tokens[last]) && h in "NE")) || (before && (h in "SW" || afterWords))) {
                    if (after && (j == last + 1 || (j == last + 2 && isMark(tokens[last + 1]))) &&
                        tokens.subList(j + 1, tokens.size).all(::isSeparator)
                    ) {
                        trailing = h
                    }
                    tokens.removeAt(j)
                    hemis = emptyList()
                }
            }
        }

        // Split into the two halves.
        val parts: List<List<Token>>
        var left: List<List<Token>> = emptyList()
        val numberCount = tokens.count(::isNumber)
        if (numberCount < 2) return null
        if (hemis.size == 2) {
            val p = byLetters(tokens, hemis) ?: return null
            parts = p.first
            left = p.second
        } else if (hemis.size > 2) {
            return null
        } else {
            val sep = splitAtSeparator(tokens)
            val deg = if (sep == null) splitAtDegrees(tokens) else null
            if (sep != null) {
                parts = sep
            } else if (deg != null) {
                parts = deg
            } else if (numberCount % 2 == 0 && numberCount <= 6) {
                var seen = 0
                var cut = 0
                for ((j, tok) in tokens.withIndex()) {
                    if (tok is Token.Number) {
                        seen += 1
                        if (seen == numberCount / 2 + 1) {
                            cut = j
                            break
                        }
                    }
                }
                while (cut > 0 && isLabel(tokens[cut - 1])) cut -= 1
                // A number right after degrees, with nothing between them, is their minutes: "24° 59.816 S" and "S 24°
                // 59.816" are one coordinate, not 24° and 59.816°. So is a number with a degree sign right after a whole number
                // ("25 05.8136 degS", the sign written after the minutes).
                if (cut > 0 && tokens[cut - 1] is Token.Degree) return null
                if (cut + 1 < tokens.size && tokens[cut + 1] is Token.Degree) {
                    val whole = tokens[cut - 1]
                    if (whole is Token.Number && whole.places == 0 && !whole.negative) return null
                }
                parts = listOf(tokens.subList(0, cut).toList(), tokens.subList(cut, tokens.size).toList())
            } else {
                return null
            }
        }
        val halves = halves(parts) ?: return null
        // (The letter left out above: "-33.4489, 70.6693 W", "31.4371, -25.0969 S"; not an N or S after a longitude a label
        // or heading names: "Lat/Long: -24.9969, 31.5923 N". The S written right after the last number, after a first half
        // with a minus sign and without a letter or label that can be a latitude: "-24.9969, 31.5923S".)
        if (trailing == 'W' && !halves[1].signed ||
            (trailing == 'N' || trailing == 'S') && abs(halves[1].value) <= 90 && halves[1].axis == null && headingFirst == null ||
            attachedSouth && halves[0].signed && halves[0].axis == null && abs(halves[0].value) <= 90
        ) {
            return null
        }
        // Those letters are the halves' in their order. (A minus sign and N or E say two things.) After a pair written with a
        // minus sign they are a direction, as one letter is, and don't change a sign ("Lions -24.3812, 31.8456 (S/W)").
        val tag = tags.firstOrNull()
        if (tag != null) {
            val axes = tag.first.map { if (it in "NS") Axis.lat else Axis.lon }
            if (!(tags.size == 1 && hemis.isEmpty() && (0 until 2).all { (halves[it].axis ?: axes[it]) == axes[it] })) return null
            val direction = tag.second && halves.any { it.signed }
            for (j in 0 until 2) {
                if (tag.first[j] == 'S' || tag.first[j] == 'W') {
                    if (!halves[j].signed && !direction) halves[j].value = -halves[j].value
                } else if (halves[j].signed) {
                    return null
                }
                halves[j].axis = axes[j]
            }
        }
        // Beside a place, two numbers without letters or labels, each named by words before it ("USD/ZAR 18.2345, EUR/ZAR
        // 19.8765. Lions at -25.0969, 31.4371"), are what the message says next to that place, not a second one. (On
        // their own they are read: the words may be labels in another language, "Широта: 55.7558 Долгота: 37.6173".)
        if (beside && halves.all { it.axis == null }) {
            val a = parts[0].indexOfFirst(::isNumber)
            val b = parts[0].indexOfLast(::isNumber)
            val c = parts[1].indexOfFirst(::isNumber)
            if (a >= 0 && b >= 0 && c >= 0) {
                fun named(part: List<Token>): Boolean = part.any { it is Token.Text && it.word }
                if (named(parts[0].subList(0, a)) && (named(parts[0].subList(b + 1, parts[0].size)) || named(parts[1].subList(0, c)))) {
                    return null
                }
            }
        }
        // Nor are two whole numbers without a sign, a letter or a label (an id, a count: "1,001, -24.996931, 31.592312").
        if (beside && halves.all { it.axis == null && !it.signed && it.numbers == 1 && it.resolution == 1.0 }) return null
        // Among other words, numbers that could as well be times, dates, counts or measurements ("Gates open 05:30 close
        // 18:00", "12 lions 3 cubs", "temp 25.5, wind 31.4", "Lions 5 N of Satara") aren't a position. Degrees, minutes and
        // seconds in both halves with a minus sign before the first ("We're at -25 05 48, 31 26 13") can't be a time or a
        // count.
        val signedSeconds = halves[0].signed && halves.all { it.numbers == 3 }
        if (words && headingFirst == null && !(halves[0].strong && halves[1].strong) && !signedSeconds) return null
        // Nor are times on their own ("05:30 - 18:30", "10:15:30, 11:20:40", "+02:00 -05:00"), with the same exception,
        // or with a minus sign before degrees that no hour or time zone has ("-25:05, 31:26"; not "-25:05 - 31:26", which
        // may be a range).
        val signedDegrees = halves[0].signed && abs(halves[0].value) > 14 && !halves[1].signed
        if (halves.any { it.time } && !signedSeconds && !signedDegrees) return null
        // A letter right before degrees and minutes, with no letter or label on the other half ("Lions on S28 25.0969,
        // 31.4371", "N4 25.4567, 30.9876"), may as well be a road's name: it isn't clear which.
        if (halves.any { it.gluedLetter && it.numbers > 1 } && halves.any { it.axis == null }) return null

        // Nor, among other words, is one right before two whole numbers without marks, unless the other half is written so
        // too, to any decimals ("S25 05 E31 26", "Lions S25 05 E31 26.2273"): a road's name and a count ("Lions on S28 3
        // females, heading E 31 deg", "op die N4 27 hyenas").
        fun plain(j: Int): Boolean = halves[j].gluedLetter && halves[j].numbers == 2 && !parts[j].any(::isMark)
        if (words) {
            val j = (0 until 2).firstOrNull { plain(it) && parts[it].all { tok -> decimalPlaces(tok) == 0 } }
            if (j != null && !plain(1 - j)) return null
        }
        // Whole or one-decimal numbers without a sign, a letter or a label, in degrees, are as likely temperatures or angles
        // ("34°/24°", "34 grade, 24 grade", "34.5°/24.5°"): not a position.
        if (halves.all { it.axis == null && !it.signed && it.numbers == 1 && it.resolution >= 0.1 } && tokens.any(::isDegree)) {
            return null
        }
        var lat = halves[0].value
        var lon = halves[1].value
        val a = halves[0].axis
        val b = halves[1].axis
        var swap = false
        if (a != null && b != null) {
            if (a == b) return null
            if (a == Axis.lon) swap = true
        } else if (a != null) {
            if (a == Axis.lon) swap = true
        } else if (b != null) {
            if (b == Axis.lat) swap = true
        } else {
            val labels = tokens.filterIsInstance<Token.Label>().mapNotNull { it.axis }
            if ((headingFirst ?: labels.firstOrNull()) == Axis.lon || (abs(lat) > 90 && abs(lon) <= 90)) swap = true
        }
        if (swap) {
            val first = lat
            lat = lon
            lon = first
        }
        val point = valid(lat, lon) ?: return null
        val kind = max(halves[0].kind, halves[1].kind)
        val format = listOf("decimal degrees", "degrees and decimal minutes", "degrees, minutes and seconds", "GPS (NMEA)")[kind]
        val r = Reading(Result(point, format), max(halves[0].resolution, halves[1].resolution))
        // What its letters left out holds a part of a position ("…E\n-25.096889, 31.437111"): the same place written
        // again, or else another place, and it isn't clear which is meant.
        for (part in left) {
            val other = reading(part, words) ?: return null
            if (!samePlace(other, r)) return null
        }
        return r
    }

    /** The two halves, if each reads as one. */
    private fun halves(parts: List<List<Token>>): List<Half>? {
        if (parts.size != 2) return null
        // Numbers joined by a dash or colon stay in one half: "45-30" or "12:30" isn't 45, 30 or 12, 30.
        if (parts[0].lastOrNull() is Token.Joiner) return null
        val degrees = parts.any { part -> part.any { it is Token.Degree } }
        val halves = parts.mapNotNull { component(it, degrees) }
        if (halves.size != 2) return null
        // "Spot 1: 51.5007, -0.1246": a number before the position (a spot or camp number) makes the first half look like
        // 1° 51.5007'. A first half with more numbers than the second is read only when something marks where it starts
        // ("40° 41' 21" N 74° 2' W", "-25 05 48.8, 31.437121", "S25 05 48.8 E31 26.2273").
        if (halves[0].numbers > halves[1].numbers && !halves[0].startMarked) return null
        return halves
    }

    /** The halves of a text with two letters: before the numbers ("S 25 … E 31 …"), after them ("25 … S 31 … E"), or one
     *  of each ("25 … S, E 31 …"), and the parts left out that hold a part of a position (see `reading`). What is left
     *  out (a count, a name, a time or a date before it; a phone number, height, date or count after it) isn't part of
     *  it. */
    private fun byLetters(tokens: List<Token>, hemis: List<Int>): Pair<List<List<Token>>, List<List<Token>>>? {
        val before = tokens.subList(0, hemis[0]).toList()
        val between = tokens.subList(hemis[0] + 1, hemis[1]).toList()
        val after = tokens.subList(hemis[1] + 1, tokens.size).toList()
        val chosen: Pair<List<List<Token>>, List<List<Token>>>
        if (!between.any(::isNumber)) {
            val (second, rest, _) = ended(tokens.subList(hemis[0] + 1, tokens.size).toList())
            val start = backStart(tokens, letter = hemis[0], count = second.count(::isNumber))
            chosen = listOf(tokens.subList(start, hemis[0] + 1).toList(), second) to listOf(tokens.subList(0, start).toList(), rest)
        } else {
            // Letters before the numbers: the second half ends where it is complete, and what is after it is other text.
            // With numbers before the first letter, only with a number right after the second ("…E, 545.4", "…E\n-24.9961"
            // is what comes after a position with its letters after the numbers). Letters after the numbers: what is
            // after the second letter is other text ("…E 2026-10-27", "…E 3 cubs").
            val (second, rest, complete) = ended(tokens.subList(hemis[1], tokens.size).toList())
            val start = backStart(tokens, letter = hemis[0], count = between.count(::isNumber))
            val front = listOf(tokens.subList(hemis[0], hemis[1]).toList(), second)
            val back = listOf(tokens.subList(start, hemis[0] + 1).toList(), tokens.subList(hemis[0] + 1, hemis[1] + 1).toList())
            fun reads(parts: List<List<Token>>): Boolean = halves(parts)?.let(::isPosition) ?: false

            // Whether its halves are written the same way (as many fields), the second complete.
            fun alike(parts: List<List<Token>>, complete: Boolean): Boolean =
                complete && (halves(parts)?.let { it[0].numbers == it[1].numbers } ?: false)
            val frontReads = after.any(::isNumber) && (!before.any(::isNumber) || after.firstOrNull()?.let(::isNumber) == true) &&
                reads(front)
            val glued = hemis.all { (tokens[it] as Token.Hemisphere).glued }
            val backTime = back[0].any { it is Token.Joiner && it.colon } &&
                back[0].lastOrNull(::isNumber)?.let { decimalPlaces(it) == 0 } == true
            // A whole number with a degree sign alone right before the first letter, which has a number with decimals right
            // after it ("Hot 32° S 24.996931 E 31.592312"), is a temperature or an angle, not that letter's degrees.
            val n = hemis[0]
            val backTemperature = n > 1 && isNumber(tokens[n - 2]) && decimalPlaces(tokens[n - 2]) == 0 &&
                isDegree(tokens[n - 1]) && decimalPlaces(tokens[n + 1]) > 0
            val backReads = before.any(::isNumber) && reads(back)
            chosen = when {
                // Each letter written right before a number ("27/10/2026 S24.99 E31.59", "38°C S24 59 48.9 E31 35 32.3",
                // "06:45 S25-05-48.8 E031-26-13.6"): the letters are in front of those numbers, whatever is before them (a date,
                // a time, a temperature, a count).
                frontReads && glued && complete -> front to listOf(before, rest)
                // Both halves written alike rather than differently: "Leopard 06.45 S 24 59.8 E 31 35.5" is 24° 59.8' S, 31°
                // 35.5' E, not 6.45° S, 24° 59.8' E; "24 59 48.9 S 31 35 32.3 E 45° bearing" isn't 31° 35' 32.3" S, 45° E.
                frontReads && backReads && alike(front, complete) != alike(back, ended(back[1]).complete) ->
                    if (alike(front, complete)) front to listOf(before, rest) else back to listOf(tokens.subList(0, start).toList(), after)
                // A time before a letter ("27/10/2026 06:45 S 25 E 31") isn't degrees and minutes when the letters can as well
                // be in front, nor is a temperature its degrees.
                frontReads && backReads && (backTime || backTemperature) -> front to listOf(before, rest)
                frontReads && backReads -> {
                    // Both can be read: the one that leaves out less of what can be a position. A count or a name (one whole
                    // number: "Stop 3 S25.0969 E31.4371", "WGS 84: S24 59 48.9 E31 35 32.3") rather than other numbers
                    // ("31 35 32.3", "24.99"), and those rather than decimals with 3 places or more or numbers with their
                    // marks ("25.0969 S 31.4371 E 3 cubs"); whole numbers before the first letter (a time, a date, counts)
                    // rather than a whole half after the second ("12:30 Lions S 24 59 48.9 E 31 35 32.3"). Leaving out as
                    // much, the letters go after the numbers when the second half is complete ("24.99 S 31.59 E 12.5"); else
                    // it isn't clear ("25 S 31 E 3": 3 cubs, or 3 minutes? "12.5 S 24 59 E 31 35").
                    val frontLeaves = leftOut(before)
                    val backLeaves = leftOut(second.drop(1))
                    val backLeavesAHalf = isHalf(second.drop(1)) && !before.any { decimalPlaces(it) > 0 }
                    if (frontLeaves < backLeaves || (backLeavesAHalf && frontLeaves < 2)) {
                        front to listOf(before, rest)
                    } else if (backLeaves < frontLeaves || (backLeaves == 1 && ended(back[1]).complete)) {
                        back to listOf(tokens.subList(0, start).toList(), after)
                    } else {
                        return null
                    }
                }
                frontReads -> front to listOf(before, rest)
                backReads -> back to listOf(tokens.subList(0, start).toList(), after)
                else -> return null
            }
        }
        // What holds (a part of) another position: decimals with 3 places or more ("…E\n-24.9961, 31.5911", "S24.99 E31.59
        // -25.1234"), or more than one number with its mark (one is a bearing or a temperature: "…E, 30° bearing").
        val holding = chosen.second.filter { part ->
            part.any { decimalPlaces(it) >= 3 } || part.indices.count { partOfAPosition(part, it) } >= 2
        }
        return chosen.first to holding
    }

    /** Where a half with its letter after it (at `letter`) starts, when more numbers are before the letter than the other
     *  half has (`count`): right after other text or a separator, or at a label, that has as many as that between it and
     *  the letter ("[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "12 buffalo at the dam\nLions 24 22.872 S …", "km
     *  14: 24.3812 S 31.8456 E", "Camp 14 lat 24.99693 S lon …"), or at the last that many when there are more than one
     *  half can have ("Camp 14 24 59 48.9 S 31 35 32.3 E"); else at the start. (A number right before them could be their
     *  first field: "Camp 1 25 05.8 S, 31 26.2 E".) */
    private fun backStart(tokens: List<Token>, letter: Int, count: Int): Int {
        val numbers = (0 until letter).filter { isNumber(tokens[it]) }
        if (!(numbers.size > count && count > 0)) return 0
        val first = numbers[numbers.size - count]
        val previous = numbers[numbers.size - count - 1]
        // (Not after the slash of a date or a fraction: "27/10 S 24.99 E 31.59" isn't 10° S.)
        val k = (0 until letter).lastOrNull { val tok = tokens[it]; isText(tok) || isLabel(tok) || (isSeparator(tok) && !isSlash(tok)) }
        if (k != null && k < first && k > previous) {
            return if (isLabel(tokens[k])) k else k + 1
        }
        // Right after a time or a short date ("06:45 24.99693 S 31.59231 E", "27/10 24.99693 S …").
        if (first == previous + 1 && previous > 0 && (isJoiner(tokens[previous - 1]) || isSlash(tokens[previous - 1]))) return first
        // At a degree sign on its first number ("Lions 3 24° 22.872' S …"). Not at one on the number before them, with no
        // mark on theirs, when the other half has none and is written as they are, to as many decimals: that is a
        // temperature or an angle ("Lions 32° 24 22.872 S 31 50.736 E", not 32° 24' 22.872"; "24° 22 52.3 S 31 50.736 E" is
        // 24° 22' 52.3").
        val other = tokens.indices.filter { it > letter && isNumber(tokens[it]) }.take(count)
        val lastDecimals = decimalPlaces(tokens[numbers[numbers.size - 1]])
        val otherDecimals = other.lastOrNull()?.let { decimalPlaces(tokens[it]) }
        fun degrees(j: Int): Boolean = j + 1 < tokens.size && tokens[j + 1] is Token.Degree
        if (degrees(first) || (degrees(previous) && !isMark(tokens[first + 1]) && !other.any(::degrees) && otherDecimals == lastDecimals)) {
            return first
        }
        // A whole number before degrees and minutes with 3 decimals or more, when the other half is written so too ("Lions
        // 3 24 22.872 S 31 50.736 E", as a GPS shows them): a count or a name's number, not 3° 24' 22.872".
        if (count == 2 && other.size == 2 && decimalPlaces(tokens[previous]) == 0 && !isMark(tokens[previous + 1]) && lastDecimals >= 3 &&
            (otherDecimals ?: 0) >= 3
        ) {
            return first
        }
        // A whole number after a word, before one number with decimals, when the other half is one number with decimals
        // too ("Leopard 2 24.99692S, 31.59231E", "Camp 14 …"): a count or a name's number, as the other half says the
        // position is written in decimal degrees, not 2° 24.99692'.
        if (count == 1 && first == previous + 1 && previous > 0 && isText(tokens[previous - 1]) && decimalPlaces(tokens[previous]) == 0 &&
            decimalPlaces(tokens[first]) > 0 &&
            tokens.subList(letter + 1, tokens.size).firstOrNull(::isNumber)?.let { decimalPlaces(it) > 0 } == true
        ) {
            return first
        }
        return if (numbers.size > 3 && count <= 3) numbers[numbers.size - count] else 0
    }

    /** How much of a position a reading leaves out: 0 a count or a name (one whole number), 1 other numbers, 2 what can be
     *  a part of a position (decimals with 3 places or more, a number with its unit mark). */
    private fun leftOut(part: List<Token>): Int {
        if (part.indices.any { partOfAPosition(part, it) }) return 2
        return if (isCount(part)) 0 else 1
    }

    /** Whether the part starts with degrees and minutes, or degrees, minutes and seconds, side by side and complete
     *  ("31 35 32.3", "31 35.53"): a half of a position without its letter. Not a time ("12:30") or a date ("27/10/26"). */
    private fun isHalf(part: List<Token>): Boolean {
        val k = part.indexOfFirst(::isNumber)
        if (k < 0) return false
        val run = part.subList(k, part.size).takeWhile(::isNumber)
        if (run.size !in 2..3 || component(run) == null) return false
        return ended(run).complete
    }

    /** Whether the token at `k` is a number with 3 decimal places or more, or with a unit mark after it. */
    private fun partOfAPosition(part: List<Token>, k: Int): Boolean =
        isNumber(part[k]) && (decimalPlaces(part[k]) >= 3 || (k + 1 < part.size && isMark(part[k + 1])))

    /** Whether two halves with their letters are a latitude and a longitude ("25 S 31 E", not "25 S 31 S" or "25 S 545 E"). */
    private fun isPosition(halves: List<Half>): Boolean {
        val a = halves[0].axis ?: return true
        val b = halves[1].axis ?: return true
        return a != b && valid(if (a == Axis.lat) halves[0].value else halves[1].value, if (a == Axis.lat) halves[1].value else halves[0].value) != null
    }

    /** What `ended` gives: the half, what is after it, and whether it is complete. */
    private data class Ended(val half: List<Token>, val rest: List<Token>, val complete: Boolean)

    /** A half that starts with its letter, up to where it is complete: after a number with decimals, its seconds or its
     *  third number, or at a separator or other text after its second ("S24 59 E31 35, 3 lions", "… E31 26 at 06:45").
     *  What is after that ("S24 59.815 E31 35.539 2 lions", "… Tel: 013 735 4152") isn't part of it. */
    private fun ended(part: List<Token>): Ended {
        var numbers = 0
        for ((k, tok) in part.withIndex()) {
            if (numbers >= 2 && (isSeparator(tok) || isText(tok))) return Ended(part.subList(0, k).toList(), part.subList(k, part.size).toList(), true)
            if (tok !is Token.Number) continue
            numbers += 1
            var end = k + 1
            val seconds = end < part.size && part[end] is Token.Second
            if (!(tok.places > 0 || seconds || numbers == 3)) continue
            if (end < part.size && isMark(part[end])) end += 1
            return Ended(part.subList(0, end).toList(), part.subList(end, part.size).toList(), true)
        }
        return Ended(part, emptyList(), false)
    }

    /** One whole number on its own (a count, a camp or waypoint number, the 84 of "WGS 84"). */
    private fun isCount(part: List<Token>): Boolean {
        val numbers = part.indices.filter { isNumber(part[it]) }
        if (numbers.size != 1 || decimalPlaces(part[numbers[0]]) != 0) return false
        return !(numbers[0] + 1 < part.size && isMark(part[numbers[0] + 1]))
    }

    /** Whether the N, S, E or W at `i` starts a name, as Kruger's roads are named ("S3", "S100", the "N4"): one whole
     *  number glued to it (see `nameShaped`), with no other number after it ("S24 59 48.9"), or one that no position has
     *  ("S100 24 22.872 S …", "E200"). */
    private fun isName(chars: List<Ch>, i: Int): Boolean {
        if (!nameShaped(chars, i)) return false
        var k = i + 1
        while (k < chars.size && chars[k].isNumber) k += 1
        if (k - i <= 4) {
            val v = swiftDouble(string(chars, i + 1, k))
            if (v != null && v > (if (chars[i].isIn("NS")) 90.0 else 180.0)) return true
        }
        while (k < chars.size && (chars[k].isChar(' ') || chars[k].isChar('\t'))) k += 1
        return !(k < chars.size && chars[k].isNumber)
    }

    /** Whether the N, S, E or W at `i` has one whole number glued to it, with nothing after it that goes on with a
     *  position (decimals, a unit mark, a dash or colon and more fields: "S25.0969", "S25-05-48.8", "E31°"). */
    private fun nameShaped(chars: List<Ch>, i: Int): Boolean {
        // (Not the letter of a number before it, "5130N00007W".)
        if (!((i == 0 || !(isWordLetter(chars[i - 1]) || chars[i - 1].isNumber || chars[i - 1].isChar('.'))) &&
                i + 1 < chars.size && chars[i + 1].isNumber)
        ) {
            return false
        }
        var k = i + 1
        while (k < chars.size && chars[k].isNumber) k += 1
        // (The mark also after a space: "S24 ° 59.816'", "S24 deg 59.816".)
        var mark = k
        while (mark < chars.size && (chars[mark].isChar(' ') || chars[mark].isChar('\t'))) mark += 1
        if (mark < chars.size && chars[mark].isIn("°'\"")) return false
        return !(k + 1 < chars.size && chars[k].isIn(".,-:") && chars[k + 1].isNumber)
    }

    /** Where a name with digits in it ends: letters and digits, and a dash, point or slash between them ("H4-1", "4x4"). */
    private fun afterName(chars: List<Ch>, i: Int): Int {
        var k = i
        fun part(c: Ch): Boolean = c.isNumber || isWordLetter(c)
        while (k < chars.size && (part(chars[k]) || (chars[k].isIn("-./") && k + 1 < chars.size && part(chars[k + 1])))) {
            k += 1
        }
        return k
    }

    /** Whether the N, S, E or W at `i` is a direction: on its own (not in a word, nor in "it's" or Afrikaans "'n") and
     *  next to a number or a unit mark ("25.0969 S", "S 25.0969", "48.8"S"), not a word in a sentence ("Heading west,
     *  lions at …", "near the east gate"). */
    private fun isHemisphere(chars: List<Ch>, i: Int): Boolean {
        if (isWordLetter(if (i > 0) chars[i - 1] else null) || isWordLetter(if (i + 1 < chars.size) chars[i + 1] else null)) {
            return false
        }
        // After an apostrophe only when that is a minute mark ("05'S").
        if (i > 0 && chars[i - 1].isChar('\'') && !(i > 1 && chars[i - 2].isNumber)) return false
        var k = i - 1
        while (k >= 0 && (chars[k].isWhitespace || chars[k].isIn(",;("))) k -= 1 // also "25.0969 (S)", "24.996931;S;…"
        if (k >= 0 && (chars[k].isNumber || chars[k].isIn("°'\""))) return true
        k = i + 1
        while (k < chars.size && (chars[k].isWhitespace || chars[k].isChar(':') || chars[k].isChar(')'))) k += 1
        if (k < chars.size && chars[k].isNumber) return true
        return k + 1 < chars.size && chars[k].isIn("-+.") && chars[k + 1].isNumber
    }

    /** Whether the dash at `i` is between whole degrees or minutes (1 to 3 digits) and 2 digits under 60, as in
     *  "25-05-48.8" (not "40-74.0445"). */
    private fun fieldDash(chars: List<Ch>, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && chars[k].isNumber) k -= 1
        if (!((i - 1 - k) in 1..3 && (k < 0 || (!chars[k].isChar('.') && !chars[k].isChar(','))))) return false
        if (i + 2 >= chars.size) return false
        val tens = chars[i + 1].wholeNumberValue ?: return false
        if (!(tens < 6 && chars[i + 2].isNumber)) return false
        return i + 3 == chars.size || !chars[i + 3].isNumber
    }

    private fun isLabel(t: Token): Boolean = t is Token.Label

    private fun isHemisphere(t: Token): Boolean = t is Token.Hemisphere

    private fun isDegree(t: Token): Boolean = t is Token.Degree

    private fun isNumber(t: Token): Boolean = t is Token.Number

    /** Whether it is a number with a minus sign before it. */
    private fun isNegative(t: Token): Boolean = t is Token.Number && t.negative

    private fun isSeparator(t: Token): Boolean = t is Token.Separator || t is Token.Slash

    private fun isSlash(t: Token): Boolean = t is Token.Slash

    private fun isJoiner(t: Token): Boolean = t is Token.Joiner

    private fun isText(t: Token): Boolean = t is Token.Text

    /** A number's digits after the point (0 for anything else). */
    private fun decimalPlaces(t: Token): Int = if (t is Token.Number) t.places else 0

    private fun isMark(t: Token): Boolean = t is Token.Degree || t is Token.Minute || t is Token.Second

    /** At a separator with numbers on both sides (a comma inside "25,5" was read as a decimal comma already). */
    private fun splitAtSeparator(tokens: List<Token>): List<List<Token>>? {
        val first = tokens.indexOfFirst(::isNumber)
        val last = tokens.indexOfLast(::isNumber)
        if (first < 0 || last < 0) return null
        val k = (first until last).firstOrNull { isSeparator(tokens[it]) } ?: return null
        return listOf(tokens.subList(0, k).toList(), tokens.subList(k + 1, tokens.size).toList())
    }

    /** Before the second number marked with a degree sign, unless that is right after whole degrees without a minus sign,
     *  or with its letter after it, and can be their minutes ("25° 05.8136 degS": the sign written after the minutes; see
     *  `reading`; but "-25° 31°"). */
    private fun splitAtDegrees(tokens: List<Token>): List<List<Token>>? {
        val marked = (0 until tokens.size - 1).filter { isNumber(tokens[it]) && tokens[it + 1] is Token.Degree }
        if (marked.size != 2) return null
        val whole = tokens[marked[0]] as Token.Number
        val minutes = tokens[marked[1]] as Token.Number
        if (marked[1] == marked[0] + 2 && whole.places == 0 && minutes.value < 60 &&
            (!whole.negative || tokens.drop(marked[1] + 2).firstOrNull()?.let(::isHemisphere) == true)
        ) {
            return null
        }
        var cut = marked[1]
        // Keep a label just before it with the second half.
        while (cut > 0 && isLabel(tokens[cut - 1])) cut -= 1
        return listOf(tokens.subList(0, cut).toList(), tokens.subList(cut, tokens.size).toList())
    }

    /** A number of a half, as `component` reads it: its value, digits, sign and unit (0 degrees, 1 minutes, 2 seconds). */
    private class Num(val value: Double, val intDigits: Int, val places: Int, val negative: Boolean, var unit: Int?)

    /** One half: its value with sign, its axis (from N/S/E/W or a label), its kind and how it is marked (see `Half`).
     *  `degrees`: whether the position has a degree sign, which makes a ' or " after a number a minute or second mark. */
    private fun component(part: List<Token>, degrees: Boolean = false): Half? {
        val nums = mutableListOf<Num>()
        var hemi: Char? = null
        var label: Axis? = null
        var lettered = false // a letter or label before its first number
        var glued = false // the letter right before it
        // A label or other words between its numbers, or a comma, semicolon or slash unless its letter comes after them
        // (CSV columns, "25,05,48.8,S,31,26,13.6,E"): they aren't the fields of one half. "-24,99 31,59." with the commas
        // read as separators isn't -24, 99° 31' 59", nor is "2026/10/27" 2026, 10° 27'. Nor is a letter after other words
        // its letter ("Warm 34°. Leeus by S24 59 48.9 …", "06.45 at S24.99 …": that S is the next half's, in front of it).
        var labelAfter = false
        var separatorAfter = false
        var separated = false
        var colon = false
        for ((k, tok) in part.withIndex()) {
            when (tok) {
                is Token.Number -> {
                    if (labelAfter) return null
                    if (separatorAfter) separated = true
                    var unit: Int? = null
                    if (k + 1 < part.size) {
                        when (part[k + 1]) {
                            Token.Degree -> unit = 0
                            Token.Minute -> unit = 1
                            Token.Second -> unit = 2
                            else -> {}
                        }
                    }
                    nums.add(Num(tok.value, tok.intDigits, tok.places, tok.negative, unit))
                }
                is Token.Hemisphere -> {
                    if (labelAfter) return null
                    hemi = tok.letter
                    if (nums.isEmpty()) {
                        lettered = true
                        glued = tok.glued
                    }
                }
                is Token.Label -> if (tok.axis != null) {
                    if (nums.isEmpty()) {
                        label = tok.axis
                        lettered = true
                    } else {
                        labelAfter = true
                    }
                }
                Token.Separator, Token.Slash -> if (nums.isNotEmpty()) separatorAfter = true
                is Token.Text -> if (nums.isNotEmpty()) labelAfter = true
                is Token.Joiner -> colon = colon || tok.colon
                else -> {}
            }
        }
        if (nums.size !in 1..3 || !(!separated || (hemi != null && !lettered))) return null
        // Only the first number has a sign: "14 -25.1" is a stray number before a position, not 14° 25.1'.
        if (nums.drop(1).any { it.negative }) return null
        // A minus sign and N or E ("-31.4371 E") say different things.
        if (nums[0].negative && (hemi == 'N' || hemi == 'E')) return null
        // Without a degree sign in the position, a ' or " after a number that can't be minutes or seconds is a quote
        // ('-25.0969'). With one, they are minutes and seconds, and a half can't start with them: "S 24° 59.815'" is one
        // coordinate, not 24° S and 59.815° E.
        if (!degrees && !nums.any { it.unit == 0 }) {
            for (k in nums.indices) {
                if ((nums[k].unit ?: 0) > nums.size - 1) nums[k].unit = null
            }
        } else if ((nums[0].unit ?: 0) > 0) {
            return null
        }
        // Degrees, then minutes, then seconds: "25°05°48.8"" (a degree sign typed for the minute mark) or "31° 32°" (a
        // list of temperatures) isn't one half.
        var lastUnit = -1
        for ((k, n) in nums.withIndex()) {
            if ((n.unit ?: k) <= lastUnit) return null
            lastUnit = n.unit ?: k
        }
        var d = 0.0
        var m = 0.0
        var sec = 0.0
        var kind = nums.size - 1
        val resolution: Double
        if (nums.size == 1 && nums[0].unit == null && nums[0].intDigits >= 4) {
            // GPS (NMEA) DDMM.mmmm or DDDMM.mmmm, and DDMMSS(.s): 4 digits or more before the point, leading zeros too
            // ("00007.4760" is 0° 07.476'), can't be whole degrees. Without decimals, only with its letter: else it is a
            // year, a time or a code ("2015-2020", "0800-1700", "1350 1242").
            if (!(nums[0].places > 0 || hemi != null)) return null
            val v = nums[0].value
            if (nums[0].intDigits <= 5) {
                d = floor(v / 100)
                m = v - d * 100
            } else {
                d = floor(v / 10_000)
                m = floor((v - d * 10_000) / 100)
                sec = v - d * 10_000 - m * 100
            }
            kind = 3
            resolution = 10.0.pow(-nums[0].places.toDouble()) / (if (nums[0].intDigits <= 5) 60 else 3600)
        } else {
            for ((k, n) in nums.withIndex()) {
                when (n.unit ?: k) {
                    0 -> d = n.value
                    1 -> m = n.value
                    else -> sec = n.value
                }
            }
            if (nums.any { it.unit == 2 }) kind = 2 else if (nums.any { it.unit == 1 }) kind = max(kind, 1)
            val last = nums[nums.size - 1]
            resolution = 10.0.pow(-last.places.toDouble()) / listOf(1, 60, 3600)[min(last.unit ?: (nums.size - 1), 2)]
        }
        if (!(m < 60 && sec < 60)) return null
        // Only the last number has a fraction: 25.5° 30' isn't a position, nor is "31.4371, -25.0969, 0" (lon, lat and
        // altitude, or lat, lon and altitude: it can't be told which).
        if (nums.dropLast(1).any { it.value != it.value.rounded() }) return null
        var value = d + m / 60 + sec / 3600
        if (nums[0].negative || hemi == 'S' || hemi == 'W') value = -value
        var axis = label
        if (hemi != null) axis = if (hemi == 'N' || hemi == 'S') Axis.lat else Axis.lon
        return Half(
            value = value, axis = axis, kind = kind, numbers = nums.size,
            time = colon && hemi == null && label == null && nums[nums.size - 1].places == 0,
            startMarked = lettered || nums[0].negative ||
                (nums[0].unit == 0 && (hemi != null || nums.drop(1).any { it.unit != null })),
            strong = label != null || (hemi != null && (lettered || !(nums.size == 1 && nums[0].places == 0 && nums[0].unit == null))) ||
                nums.any { it.unit == 1 || it.unit == 2 || it.places >= 3 },
            gluedLetter = glued, signed = nums[0].negative, resolution = resolution,
        )
    }

    // A position inside other text

    /** A position among other words and numbers ("Camp site 14: -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: …",
     *  "Lions on H4-1 at …"), where the other numbers keep the whole text from being read: a pair that can only be a
     *  position, read on its own. That is decimals with 3 places or more, values with a degree sign and minutes or a
     *  letter, or degrees with minutes with 3 decimal places or more, or with seconds, and a letter ("S24 22.872 E31
     *  50.736", as a GPS shows them), side by side and not part of a longer list of numbers. When the text has two
     *  different ones, it isn't clear which is meant, nor with more than 10 (a track, not a place), nor when another
     *  number like them is elsewhere in the text. */
    private fun inText(t: String, near: GeoPoint?): Found? {
        var found: Reading? = null
        var tried = 0
        // (Decimal commas only when a comma is between digits.)
        val decimalCommas = has("""\d,\d""", t)
        // A pair without letters in a text with both halves' letters written together ("Lions (S/E): 24.99693, 31.59232,
        // 3 males"): those are its letters, which only the whole text says. It is read on its own only with a minus sign,
        // as they are then a direction (see `reading`), and not with an N or E that says otherwise ("2 lions -24.996931,
        // 31.592312 (S/E)"). (Looked for only when two such letters are next to a number or end a line, which is quicker in a
        // long text.)
        val together = """[NSEW][ \t()/,;|]++[NSEW](?![A-Z])"""
        val tagged = if (has("""(?i)[\d°'"][\s,(]*""" + together + """|(?<![A-Z])""" + together + """(?:[\s:)]*[-+.]?\d|[ \t)]*\n)""", t)) {
            tag(t)
        } else {
            null
        }
        for ((decimal, separator) in listOf("""\.""" to """(?:\s*[,;/]\s*|\s+)""", "," to """(?:\s*[;/]\s*|\s+)""")) {
            if (decimal == "," && !decimalCommas) continue
            val fraction = "(?:" + decimal + """\d+)?"""
            val decimals = """[-+]?\d{1,3}""" + decimal + """\d{3,}\s?°?"""
            val degrees = """[-+]?\d{1,3}""" + fraction + """\s?°(?:\s?\d{1,2}""" + fraction + """\s?')?(?:\s?\d{1,2}""" + fraction +
                """\s?")?"""
            val fields = """\d{1,3}\s\d{1,2}(?:""" + decimal + """\d{3,}|\s\d{1,2}""" + fraction + ")"
            // A letter after a half, also in brackets, not the first of two written together ("…, 31.59232 S/E"). One before
            // it and one after it are read as the whole text would be ("W 24 59.816 S 31 35.539 E", "S24 59.816 E31 35.539 N
            // of the dam": one is a direction).
            val letter = """\s?(?:[NSEWO]|\([NSEWO]\))(?![A-Z]|\s*/\s*[EW](?![A-Z]))"""
            val half = """(?:[NSEWO]\s?""" + fields + "(?:" + letter + ")?|" + fields + letter + """|(?:[NSEWO]\s?)?(?:""" +
                decimals + "|" + degrees + ")(?:" + letter + ")?)"
            // (Not from the minutes of degrees with a minus sign: "-24 59.816, 31 35.539". A comma right before a number after
            // it, with decimal points, is a list's: "…, 31.592312,45958.28125".)
            val pair = """(?<![\w.,°'"+-])(?!(?<=(?:^|[^\w.])[-+]\d{1,3}[ \t])\d)""" + half + "(" + separator + ")" + half +
                """(?![\w°'"]|\.\d""" + (if (decimal == ",") """|,\d)""" else ")")
            val read = mutableListOf<NSRange>()
            for (m in matchRanges(pair, t, caseInsensitive = true)) {
                val r = m[0]
                // A degree sign alone is also a temperature or an angle ("Skukuza 38°/22°", "Bearing 10° 20° 30° 40°").
                val halves = listOf(NSRange(r.location, m[1].location - r.location), NSRange(m[1].end, r.end - m[1].end))
                if (!halves.all { has("""\d[.,]\d{3}|['"]|(?i:[NSEWO])""", t.substring(it)) }) continue
                // (Not into a pair read before it: "S 25.096893 E 31.437121\n25.096893 S 31.437121 E" is the same place twice.)
                val start = maxOf(0, r.location - 20, read.lastOrNull()?.end ?: 0)
                val end = r.end
                val before = t.substring(start, r.location)
                val after = t.substring(end, end + min(20, t.length - end))
                // Part of a longer list of numbers ("31.4371 -25.0969 545.4": longitude, latitude and altitude; "Lap times:
                // 12.345 13.456 14.567"): a number after it, or a decimal before it, joined to it by a comma or semicolon
                // or the way its own two numbers are joined. Not a number in a name ("Cheetah on H4-1, -24.9969, …") or
                // after a word ("H1-2 km 23.4, -24.9969, …"), nor a time, a date, a phone number, a temperature or a count
                // with a word, a number with a leading zero (a waypoint's) or with more than 4 digits before its point (a
                // date, an id), which aren't an altitude or a coordinate ("…, 06:45", "27/10/2026, …", "…, 082 555 1234",
                // "…, 32°C", "Wild dogs 12, …", "…, 3 males", "1,001, -24.996931, 31.592312,45958.28125, …" in an
                // OziExplorer file). With its letters a pair says which half is which, and a number after it is something
                // else ("24.9969 S 31.5923 E 545.4") or another pair.
                val sep = t.substring(m[1])
                val own = if ("/" in sep) listOf("""\s*/\s*""") else if ("," in sep || ";" in sep) emptyList() else listOf("""\s+""")
                val joiners = listOf("""\s*[,;]\s*""") + own
                val number = """(?!0\d)[-+]?\.?\d{1,4}(?:[.,]\d+)?(?![\d:/]|[.,]\d|\s*\p{L}|\s?°\s?[CF](?![A-Za-z]))"""
                val word = """(?<![\p{L}\d.,+:/-])(?<!\p{L}[ \t]{1,3})[-+]?(?!0\d)\d+"""
                // An "o" at its end with a number after it may as well be "or" ("…, 31.4371 o 31 grados").
                if (has("""(?i)(?<![A-Z])O$""", t.substring(r)) && has("""^\s*[-+]?\d""", after)) continue
                // Its only letter, at its end, with a place after it ("…, 31.59231 N of camp") is a direction from that
                // place, as in the whole text (see `reading`): the pair is read without it, as a pair without letters.
                var text = t.substring(r)
                if (has("""(?i)^[^NSEWO]*[NSEWO]\)?$""", text) && has("""(?i)^[ \t]+(?:OF|VAN|VANAF|FROM)[ \t]+\p{L}""", after)) {
                    text = replacing("""(?i)[ \t]?\(?[NSEWO]\)?$""", text, "")
                }
                val lettered = has("""(?i)[NSEWO]""", text)
                // A dash with a space on each side right before a pair without a sign or letters may as well be its minus
                // sign ("Lat/Long - 24.9969 / 31.5923"): it isn't clear which.
                if (!lettered && !t.substring(r).contains("-") && has("""(?:^|\s)-[ \t]+$""", before)) continue
                if (!lettered && tagged != null) {
                    val signed = halves.map { t.substring(it).contains("-") }
                    if (!signed.contains(true) || (signed[0] && tagged.first in "NE") || (signed[1] && tagged.second in "NE")) continue
                }
                if (has(word + "(?:" + decimal + """\d+)?\s*[,;]\s*$""", before) || joiners.any { j ->
                        has(word + decimal + """\d+""" + j + "$", before) || (!lettered && has("^" + j + number, after))
                    }
                ) {
                    continue
                }
                tried += 1
                if (tried > 10) return Found.Unclear
                // A letter after a pair written with a minus sign, with more after it on the line ("…, 31.592312 W 270°",
                // "… W of the dam"), is a direction, as in the whole text (see `reading`): the pair is read without it. (Not an
                // N or E right after a number with a minus sign, which says two things.)
                if (text.contains("-") && has("""(?i)[0-9°'"][ \t]?[NSEWO]$""", text) && has("""^[^\n]*[\p{L}\d]""", after) &&
                    !has("""(?i)(?<![\d.,])-\d+(?:[.,]\d+)?[°'"]?[ \t]?[NEO]$""", text)
                ) {
                    val chars = characters(text)
                    text = string(chars, 0, chars.size - 1)
                }
                // A letter before it is read with a heading's, bearing's or course's label before that, which may have it
                // as its value, as in the whole text ("Heading W 40.6892, -74.0445 2 males", but "Lions heading S 24.99693
                // E 31.59231 at 06:45 …", see `prepared`).
                if (has("""(?i)^[NSEW](?![A-Z])""", text)) {
                    val label = matchRanges("""(?i)(?<![A-Z])(?:HEADING|BEARING|COURSE)[ \t:]*\z""", before).firstOrNull()
                    if (label != null) text = before.substring(label[0]) + text
                }
                val p = latLon(text, near, language = t) ?: continue
                val f = found
                if (f != null && !samePlace(f, p)) return Found.Unclear
                found = f ?: p
                read.add(r)
            }
            val f = found
            if (f != null) {
                // Another number like these elsewhere in the text: a list, not a place ("Price list: 12.500 31.750 and
                // 45.250"; degrees with minutes or a letter after them, "25°05'S 31°26'E\n24°59'S 31°35'E"). Not a time, a
                // measurement ("±5.000 m"), a number with thousands separators ("1.234.567"), a temperature or a bearing
                // ("32°C", "270°").
                val others = matchRanges(
                    """(?<![\d.,:])[-+]?\d+""" + decimal + """\d{3,}(?![\d.,]|\s*(?i:k?m|ft|mi)\b)""" +
                        """|\d\s?°\s?(?:\d{1,2}(?:[.,]\d+)?\s?'|(?i:[NSEWO])(?![A-Za-z]))""",
                    t,
                )
                if (others.any { o -> !read.any { it.contains(o[0].location) } }) return Found.Unclear
                return Found.Place(f.result, read, f.resolution)
            }
        }
        return null
    }

    // Swift's String, Character and Foundation, where Kotlin's differ

    /** Foundation's `NSRange`: where a part of the text starts and how long it is, in UTF-16 units (as Kotlin strings
     *  index); `location` is `notFound` for a group that took no part in a match. */
    private data class NSRange(val location: Int, val length: Int) {
        /** `NSMaxRange`. */
        val end: Int get() = location + length

        /** `NSLocationInRange`. */
        fun contains(loc: Int): Boolean = loc >= location && loc - location < length

        fun shifted(by: Int): NSRange = if (location == notFound) this else NSRange(location + by, length)
    }

    private const val notFound = -1

    private fun String.substring(r: NSRange): String = substring(r.location, r.end)

    private fun sameResult(a: Result, b: Result): Boolean =
        a.point.lat == b.point.lat && a.point.lon == b.point.lon && a.format == b.format

    /** A Swift `Character`: one grapheme cluster (a letter with its accents, "1️⃣", a flag), which the Swift code's
     *  character-by-character reading takes as one. */
    private class Ch(val text: String) {
        /** Its canonical form: Swift compares characters by canonical equivalence (U+037E is ";"). */
        val key: String = if (text.length == 1 && text[0] < '\u00C0') text else Normalizer.normalize(text, Normalizer.Form.NFC)
        private val scalar = text.codePointAt(0)

        /** Swift's `isNumber`, `isLetter` and `isWhitespace`: by the first scalar's Unicode properties. */
        val isNumber = hasNumericType(scalar)
        val isLetter = Character.isAlphabetic(scalar)
        val isWhitespace = isUnicodeWhiteSpace(scalar)

        /** Swift's `wholeNumberValue`: a single scalar's whole numeric value. */
        val wholeNumberValue: Int?
            get() {
                if (Character.charCount(scalar) != text.length) return null
                if (scalar < 128) return if (scalar in 48..57) scalar - 48 else null
                val type = Character.getType(scalar)
                if (type != Character.DECIMAL_DIGIT_NUMBER.toInt() && type != Character.LETTER_NUMBER.toInt() &&
                    type != Character.OTHER_NUMBER.toInt()
                ) {
                    return null
                }
                val v = Character.getNumericValue(scalar)
                return if (v >= 0) v else null
            }

        fun isChar(c: Char): Boolean = key.length == 1 && key[0] == c

        fun isIn(set: String): Boolean = key.length == 1 && key[0] in set
    }

    /** The letters with a numeric value (Unicode 15.1, from the Unihan database). (Made before `plainCharacters`, as
     *  making those reads it.) */
    private val numericIdeographs: Set<Int> = listOf(
        0x3405, 0x3483, 0x382A, 0x3B4D, 0x4E00, 0x4E03, 0x4E07, 0x4E09, 0x4E24, 0x4E5D, 0x4E8C, 0x4E94, 0x4E96, 0x4EAC, 0x4EBF,
        0x4EC0, 0x4EDF, 0x4EE8, 0x4F0D, 0x4F70, 0x4FE9, 0x5006, 0x5104, 0x5146, 0x5169, 0x516B, 0x516D, 0x5341, 0x5343, 0x5344,
        0x5345, 0x534C, 0x53C1, 0x53C2, 0x53C3, 0x53C4, 0x56DB, 0x58F1, 0x58F9, 0x5E7A, 0x5EFE, 0x5EFF, 0x5F0C, 0x5F0D, 0x5F0E,
        0x5F10, 0x62D0, 0x62FE, 0x634C, 0x67D2, 0x6D1E, 0x6F06, 0x7396, 0x767E, 0x7695, 0x79ED, 0x8086, 0x842C, 0x8CAE, 0x8CB3,
        0x8D30, 0x920E, 0x94A9, 0x9621, 0x9646, 0x964C, 0x9678, 0x96F6, 0xF96B, 0xF973, 0xF978, 0xF9B2, 0xF9D1, 0xF9D3, 0xF9FD,
        0x20001, 0x20064, 0x200E2, 0x20121, 0x2092A, 0x20983, 0x2098C, 0x2099C, 0x20AEA, 0x20AFD, 0x20B19, 0x22390, 0x22998,
        0x23B1B, 0x2626D, 0x2F890,
    ).toHashSet()

    /** The characters of the ASCII and Latin letters, made once: below U+0300 every character is a cluster of its own. */
    private val plainCharacters = Array(0x300) { Ch(it.toChar().toString()) }

    /** Swift's `Array(text)`: its grapheme clusters. */
    private fun characters(s: String): List<Ch> {
        if (s.all { it < '\u0300' && it != '\r' }) return s.map { plainCharacters[it.code] }
        val out = ArrayList<Ch>(s.length)
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
        clusters.setText(s)
        var start = clusters.first()
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            val c = s.substring(start, end)
            out.add(if (c.length == 1 && c[0] < '\u0300') plainCharacters[c[0].code] else Ch(c))
            start = end
            end = clusters.next()
        }
        return out
    }

    /** `String(chars[from..<to])`. */
    private fun string(chars: List<Ch>, from: Int, to: Int): String {
        val out = StringBuilder()
        for (k in from until to) out.append(chars[k].text)
        return out.toString()
    }

    /** Whether `part` is written in `chars` at `k`, character for character. */
    private fun startsAt(chars: List<Ch>, k: Int, part: List<Ch>): Boolean {
        if (k + part.size > chars.size) return false
        for (j in part.indices) if (chars[k + j].key != part[j].key) return false
        return true
    }

    // Swift's string functions find and compare whole characters: "/" isn't found in "/́", nor "R" in "R⃣".

    /** `components(separatedBy:)`. */
    private fun components(s: String, separator: String): List<String> {
        val chars = characters(s)
        val sep = characters(separator)
        val out = ArrayList<String>()
        var start = 0
        var k = 0
        while (k < chars.size) {
            if (sep.isNotEmpty() && startsAt(chars, k, sep)) {
                out.add(string(chars, start, k))
                k += sep.size
                start = k
            } else {
                k += 1
            }
        }
        out.add(string(chars, start, chars.size))
        return out
    }

    /** `replacingOccurrences(of:with:)`. */
    private fun replacingOccurrences(s: String, target: String, replacement: String): String {
        val chars = characters(s)
        val part = characters(target)
        if (part.isEmpty()) return s
        val out = StringBuilder()
        var k = 0
        while (k < chars.size) {
            if (startsAt(chars, k, part)) {
                out.append(replacement)
                k += part.size
            } else {
                out.append(chars[k].text)
                k += 1
            }
        }
        return out.toString()
    }

    /** `hasPrefix`. */
    private fun hasPrefix(s: String, prefix: String): Boolean = startsAt(characters(s), 0, characters(prefix))

    /** `text[text.range(of: part)!.upperBound...]`: what is after the first place `part` is written, or null when it isn't. */
    private fun after(text: String, part: String): List<Ch>? {
        val chars = characters(text)
        val p = characters(part)
        if (p.isEmpty()) return null
        for (k in 0..chars.size - p.size) {
            if (startsAt(chars, k, p)) return chars.subList(k + p.size, chars.size)
        }
        return null
    }

    /** Swift's `firstIndex(of:)` on an array of characters made from these letters. */
    private fun String.indexOfCharacter(c: Ch): Int = if (c.key.length == 1) indexOf(c.key[0]) else -1

    /** Swift's `contains(where: \.isNumber)`. */
    private fun containsNumber(t: String): Boolean {
        var k = 0
        while (k < t.length) {
            val cp = t.codePointAt(k)
            if (hasNumericType(cp)) return characters(t).any { it.isNumber }
            k += Character.charCount(cp)
        }
        return false
    }

    /** Unicode's Numeric_Type is not None (Swift's `numericType != nil`): the digits and numbers of every script, and the
     *  CJK ideographs that are numbers ("一", "四", "萬"), which Java's character data has no numeric value for. */
    private fun hasNumericType(cp: Int): Boolean {
        if (cp < 128) return cp in 48..57
        return when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> cp in numericIdeographs
        }
    }

    /** Unicode's White_Space (Swift's `isWhitespace`; Java's `Character.isWhitespace` leaves out no-break spaces). */
    private fun isUnicodeWhiteSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** Foundation's `CharacterSet.whitespaces`. */
    private fun inWhitespaces(c: Int): Boolean =
        c == 0x20 || c == 0x09 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200B || c == 0x202F || c == 0x205F || c == 0x3000

    /** Foundation's `CharacterSet.whitespacesAndNewlines`. */
    private fun inWhitespacesAndNewlines(c: Int): Boolean = inWhitespaces(c) || c in 0x0A..0x0D || c == 0x85 || c == 0x2028 || c == 0x2029

    /** `trimmingCharacters(in:)`. */
    private fun trimming(s: String, set: (Int) -> Boolean): String {
        var start = 0
        var end = s.length
        while (start < end && set(s[start].code)) start += 1
        while (end > start && set(s[end - 1].code)) end -= 1
        return s.substring(start, end)
    }

    private fun codePoints(s: String): IntArray {
        val out = IntArray(s.codePointCount(0, s.length))
        var k = 0
        var n = 0
        while (k < s.length) {
            val cp = s.codePointAt(k)
            out[n++] = cp
            k += Character.charCount(cp)
        }
        return out
    }

    /** Foundation's `removingPercentEncoding` of a run of "%XX" codes: null when they aren't UTF-8. */
    private fun removingPercentEncoding(run: String): String? {
        val bytes = ByteArray(run.length / 3) { k -> run.substring(3 * k + 1, 3 * k + 3).toInt(16).toByte() }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** Swift's `Int(text)`: an optional sign and decimal digits, nothing else. */
    private fun swiftInt(s: String): Int? = if (wholeNumber.matches(s)) s.toIntOrNull() else null

    /** Swift's `Double(text)` (strtod in the C locale, the whole text): an optional sign, then decimal digits with an
     *  optional point and exponent, a hexadecimal number ("0x1p-3", "0x10"), "inf", "infinity" or "nan" in any case. No
     *  spaces around it, nor the "d" or "f" after it that Kotlin's `toDouble` takes. */
    private fun swiftDouble(s: String): Double? {
        if (decimalNumber.matches(s)) return s.toDouble()
        val hex = hexNumber.matchEntire(s)
        if (hex != null) {
            val (sign, whole, fraction, exponent) = hex.destructured
            if (whole.isEmpty() && fraction.isEmpty()) return null
            return (sign + "0x" + whole.ifEmpty { "0" } + "." + fraction.ifEmpty { "0" } + "p" + exponent.ifEmpty { "0" }).toDouble()
        }
        val negative = s.startsWith("-")
        val word = (if (s.startsWith("-") || s.startsWith("+")) s.substring(1) else s).lowercase()
        return when {
            word == "inf" || word == "infinity" -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            nanWord.matches(word) -> Double.NaN
            else -> null
        }
    }

    private val wholeNumber = Regex("[+-]?[0-9]+")
    private val decimalNumber = Regex("""[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")
    private val hexNumber = Regex("""([+-]?)0[xX]([0-9a-fA-F]*)(?:\.([0-9a-fA-F]*))?(?:[pP]([+-]?[0-9]+))?""")
    private val nanWord = Regex("""nan(?:\([0-9a-z_]*\))?""")
}
