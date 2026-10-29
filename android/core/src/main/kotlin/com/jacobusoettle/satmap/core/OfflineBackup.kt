package com.jacobusoettle.satmap.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** An offline area's settings: the app's list of areas, and the JSON inside each exported file. That JSON is the
 *  backup format on the iPhone and on Android alike (backups move between them), so its shape — Swift's own
 *  encoding of this struct: the id in capitals, dates as seconds since 1 January 2001, values not set left out —
 *  must stay as it is (`toJson`, `fromJson`). */
data class OfflineArea(
    val id: UUID,
    var name: String,
    /** Seconds since 1 January 2001, as Swift's `Date` keeps it (`timeIntervalSinceReferenceDate`), so a backup's
     *  value is read and written back unchanged. Add `referenceDate` for seconds since 1970. */
    var created: Double,
    var shape: CoverageShape,
    /** Satellite imagery only along these roads (with `Roads.corridorMeters` on each side); null = the whole
     *  area. Elevation is always downloaded for the whole area. */
    var roads: List<List<GeoPoint>>? = null,
    /** Or the roads of a set shipped with the app (`RoadPresets`), so they aren't stored again here. */
    var preset: String? = null,
    /** The area along your recorded track ("Where I've been"), updated from the latest track each time. */
    var isTrack: Boolean? = null,
    var maxZoom: Int,
    var allowCellular: Boolean,
    var status: Status,
    /** A `Long`, as Swift's `Int`. */
    var failedTiles: Long,
    var bytesOnDisk: Long,
    /** Why the last run stopped early (e.g. storage full), shown to the user. */
    var problem: String? = null,
) {
    enum class Status {
        queued,
        downloading,
        paused,
        waitingForNetwork,
        complete,
        incomplete;

        /** The saved form, as on iOS. */
        val rawValue: String get() = name
    }

    val alongRoads: Boolean get() = roads != null || preset != null

    /** Swift's `JSONEncoder` form: the keys are the property names, `id` in capitals, `created` in seconds since
     *  1 January 2001, whole numbers without a decimal point, properties not set left out. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id.toString().uppercase())
        put("name", name)
        put("created", swiftJSONNumber(created))
        put("shape", shape.toJson())
        roads?.let { r -> put("roads", buildJsonArray { r.forEach { line -> add(buildJsonArray { line.forEach { add(it.toJson()) } }) } }) }
        preset?.let { put("preset", it) }
        isTrack?.let { put("isTrack", it) }
        put("maxZoom", maxZoom)
        put("allowCellular", allowCellular)
        put("status", status.rawValue)
        put("failedTiles", failedTiles)
        put("bytesOnDisk", bytesOnDisk)
        problem?.let { put("problem", it) }
    }

    companion object {
        /** 1 January 2001 in seconds since 1970: Swift's reference date, which `created` counts from. */
        const val referenceDate = 978_307_200.0

        /** Reads an area's JSON text (a backup's `areaJSON`, for restore) as the iPhone's `JSONDecoder` does (see
         *  `SwiftJSON`). Throws if it isn't an area. */
        fun decode(text: String): OfflineArea = fromJson(SwiftJSON.parse(text))

        /** Reads `toJson()`'s form, from either app: keys in any order, the id in either case, numbers with or
         *  without a decimal point, and null for a value not set. Each value only as Swift's `JSONDecoder` takes it
         *  (no number or boolean written as a string, no number a `Double` or `Int` can't hold). Throws if it isn't
         *  one. */
        fun fromJson(json: JsonElement): OfflineArea {
            val o = json.jsonObject
            fun value(key: String): JsonElement = o[key]?.takeIf { it !is JsonNull } ?: throw IllegalArgumentException("No $key")
            fun optional(key: String): JsonElement? = o[key]?.takeIf { it !is JsonNull }
            return OfflineArea(
                id = uuid(string(value("id"))),
                name = string(value("name")),
                created = jsonDouble(value("created")),
                shape = CoverageShape.fromJson(value("shape")),
                roads = optional("roads")?.jsonArray?.map { line -> line.jsonArray.map(GeoPoint::fromJson) },
                preset = optional("preset")?.let(::string),
                isTrack = optional("isTrack")?.let(::bool),
                maxZoom = Math.toIntExact(wholeNumber(value("maxZoom"))), // throws past an Int's range
                allowCellular = bool(value("allowCellular")),
                status = Status.valueOf(string(value("status"))),
                failedTiles = wholeNumber(value("failedTiles")),
                bytesOnDisk = wholeNumber(value("bytesOnDisk")),
                problem = optional("problem")?.let(::string),
            )
        }

        /** A string, without half of a surrogate pair (which only an escape such as "\ud800" can give). */
        private fun string(json: JsonElement): String {
            val p = json.jsonPrimitive
            require(p.isString && p.content.codePoints().noneMatch { it in 0xD800..0xDFFF }) { "Not a string: $p" }
            return p.content
        }

        /** `true` or `false`, not written as a string. */
        private fun bool(json: JsonElement): Boolean {
            val p = json.jsonPrimitive
            require(!p.isString && (p.content == "true" || p.content == "false")) { "Not a boolean: $p" }
            return p.content == "true"
        }

        /** A UUID as Swift writes and reads it: 8-4-4-4-12 hex digits. */
        private fun uuid(s: String): UUID {
            require(Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}").matches(s)) { "Not a UUID: $s" }
            return UUID.fromString(s)
        }

        /** A whole number as Swift's `JSONDecoder` reads an `Int`: digits as they are; with a fraction or exponent,
         *  its `Double` when that is whole and smaller than 2^53 ("18.0", "1.8e1", "18.000000000000001" and "1e-400"
         *  are 18, 18, 18 and 0; "18.5" and "1e400" are refused), and from 2^53 on, through Foundation's `Decimal`
         *  (`decimalWhole`). */
        private fun wholeNumber(json: JsonElement): Long {
            val p = json.jsonPrimitive
            require(!p.isString && jsonNumber.matches(p.content)) { "Not a number: $p" }
            p.content.toLongOrNull()?.let { return it }
            val d = p.content.toDouble()
            require(d == floor(d) && d >= -9223372036854775808.0 && d < 9223372036854775808.0) { "Not a whole number: $p" }
            if (abs(d) < 9007199254740992.0) return d.toLong() // 2^53
            return decimalWhole(p.content) ?: throw IllegalArgumentException("Not a whole number: $p")
        }

        /** `Int64(Decimal(string:))` as Swift's `JSONDecoder` falls back to it: up to 128 bits of digits (later digits
         *  of a fraction left out, of a whole number counted in the exponent), an exponent within -128…127, trailing
         *  zeros moved into the exponent, then the digits after the point cut off. Null where Swift fails. */
        private fun decimalWhole(text: String): Long? {
            val parts = text.removePrefix("-").split('e', 'E')
            var m = BigInteger.ZERO
            var exponent = 0
            var full = false
            for (c in parts[0].substringBefore('.')) {
                val next = if (full) null else m * BigInteger.TEN + (c - '0').toBigInteger()
                if (next != null && next.bitLength() <= 128) {
                    m = next
                } else {
                    full = true
                    if (exponent == 127) return null
                    exponent += 1
                }
            }
            for (c in parts[0].substringAfter('.', "")) {
                if (full) continue
                val next = m * BigInteger.TEN + (c - '0').toBigInteger()
                if (next.bitLength() > 128) {
                    full = true
                    continue
                }
                m = next
                if (exponent == -128) return null
                exponent -= 1
            }
            if (parts.size > 1) {
                var e = 0
                for (c in parts[1].trimStart('+', '-')) {
                    e = 10 * e + (c - '0')
                    if (e > 254) return null
                }
                exponent += if (parts[1].startsWith('-')) -e else e
                if (exponent !in -128..127) return null
            }
            while (m.signum() != 0 && exponent < 127 && m.mod(BigInteger.TEN).signum() == 0) {
                m /= BigInteger.TEN
                exponent += 1
            }
            if (m.bitLength() > 64) return null
            repeat(abs(exponent)) {
                m = if (exponent < 0) m / BigInteger.TEN else m * BigInteger.TEN
                if (m.bitLength() > 64) return null
            }
            if (m.bitLength() > 63) return null
            return if (text.startsWith('-')) -m.toLong() else m.toLong()
        }
    }
}

/** Backups of offline areas as plain MBTiles files, one per layer ("<area> - satellite.mbtiles" and
 *  "<area> - elevation.mbtiles"). Each file carries the area's settings in its metadata, so the files can be
 *  restored into the app later, and other map apps can open them meanwhile. */
object OfflineBackup {
    data class Layer(
        /** File name inside the area's folder. */
        val fileName: String,
        /** Stored in the file's metadata to tell the layers apart on restore. */
        val key: String,
        /** Part of the exported file's name. */
        val label: String,
    )

    /** What an exported file holds: its layer's key and the area's settings (`OfflineArea` JSON). */
    class Contents(val layerKey: String, val areaJSON: ByteArray)

    const val areaKey = "satmap_area"
    const val layerKey = "satmap_layer"

    /** The Swift `Failure` cases; the message is the Swift `errorDescription`. */
    sealed class Failure(val errorDescription: String) : Exception(errorDescription) {
        class Busy(val name: String) : Failure("Couldn't prepare $name for export. Try again in a moment.")
        class NotABackup(val name: String) : Failure("$name isn't a SatMap backup.")
    }

    /** Writes the area's settings into its tile files, folds their write-ahead logs into them, and copies them into
     *  `into` under readable names. (On the iPhone a copy is an APFS clone, taking almost no space; here it is a
     *  plain copy. Either way a download that resumes in the area can't change a copy the system may still be
     *  sending.) Layers not downloaded yet are skipped. `usedNames` keeps file names unique. */
    fun export(areaFolder: File, layers: List<Layer>, name: String, areaJSON: String, into: File,
               usedNames: MutableSet<String>): List<File> {
        if (!into.isDirectory && !into.mkdirs()) throw IOException("Couldn't create $into")
        var base = fileSafe(name)
        var n = 2
        while (swiftLowercased(base) in usedNames) {
            base = "${fileSafe(name)} ($n)"
            n += 1
        }
        usedNames.add(swiftLowercased(base))

        val out = mutableListOf<File>()
        for (layer in layers) {
            val source = File(areaFolder, layer.fileName)
            if (!source.exists()) continue
            val store = MBTiles(source, create = false)
            store.setMetadata(mapOf(areaKey to areaJSON, layerKey to layer.key))
            val ready = store.checkpoint()
            store.close()
            if (!ready) throw Failure.Busy(name)
            val target = File(into, "$base - ${layer.label}.mbtiles")
            target.delete()
            source.copyTo(target)
            out.add(target)
        }
        return out
    }

    /** The layer key and area settings stored in an exported file (opened from the app's own storage). */
    fun inspect(file: File): Contents {
        val store = MBTiles(file, create = false)
        try {
            val layer = store.metadata(layerKey)
            val json = store.metadata(areaKey)
            if (layer == null || json == null) throw Failure.NotABackup(file.name)
            return Contents(layerKey = layer, areaJSON = json.toByteArray(Charsets.UTF_8))
        } finally {
            store.close()
        }
    }

    /** The name without characters that file systems reject. */
    fun fileSafe(name: String): String {
        // Each of these (and each control or format character) becomes a "-".
        val cleaned = StringBuilder()
        name.codePoints().forEach { c ->
            val type = Character.getType(c)
            if (c < 0x80 && "/\\:?*\"<>|".indexOf(c.toChar()) >= 0 ||
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            ) {
                cleaned.append('-')
            } else {
                cleaned.appendCodePoint(c)
            }
        }
        val trimmed = trimSpacesAndDots(cleaned.toString())
        return if (trimmed.isEmpty()) "Offline area" else prefixCharacters(trimmed, 80)
    }

    /** Without white space, line breaks and dots at either end (Foundation's `whitespacesAndNewlines` and "."). */
    private fun trimSpacesAndDots(s: String): String {
        fun trimmable(c: Int): Boolean {
            val type = Character.getType(c)
            return c == '.'.code || c in 0x09..0x0D || c == 0x85 || type == Character.SPACE_SEPARATOR.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        }
        var start = 0
        var end = s.length
        while (start < end && trimmable(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start))
        while (end > start && trimmable(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end))
        return s.substring(start, end)
    }

    /** Swift's `lowercased()`: each scalar's own lower-case mapping, with no context (so a final "Σ" becomes "σ", not
     *  "ς" as Java's `lowercase()` would make it). */
    private fun swiftLowercased(s: String): String = buildString {
        s.codePoints().forEach { append(String(Character.toChars(it)).lowercase(Locale.ROOT)) }
    }

    /** The first `count` characters as Swift counts them (each letter with its accents, each emoji, each Indic
     *  conjunct, as one). */
    private fun prefixCharacters(s: String, count: Int): String {
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(s)
        var found = 0
        while (true) {
            val end = characters.next()
            if (end == BreakIterator.DONE) return s
            if (end < s.length && insideConjunct(s, end)) continue // Swift (GB9c) doesn't break here
            found += 1
            if (found == count) return s.substring(0, end)
        }
    }

    /** Swift's rule GB9c: no break between a consonant and virama (with combining marks around it) and the next
     *  consonant. `BreakIterator` breaks there on the JVM and on Android before ICU 74; where it doesn't, nothing is
     *  skipped. The tables are Swift 6.0.3's: its consonants are every letter with Indic_Syllabic_Category Consonant,
     *  its marks Grapheme_Cluster_Break Extend with a combining class, and ZWJ. */
    private fun insideConjunct(s: String, i: Int): Boolean {
        if (!inRanges(conjunctConsonants, s.codePointAt(i))) return false
        var j = i
        var linker = false
        while (j > 0) {
            val c = s.codePointBefore(j)
            when {
                c == 0x094D || c == 0x09CD || c == 0x0ACD || c == 0x0B4D || c == 0x0C4D || c == 0x0D4D -> linker = true
                inRanges(conjunctExtends, c) -> {}
                else -> return linker && inRanges(conjunctConsonants, c)
            }
            j -= Character.charCount(c)
        }
        return false
    }

    /** Whether `c` is in one of the ranges of `table` (start, end pairs, in order). */
    private fun inRanges(table: IntArray, c: Int): Boolean {
        var lo = 0
        var hi = table.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                c < table[2 * mid] -> hi = mid - 1
                c > table[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** Swift's consonants for GB9c (Swift 6.0.3), as start, end pairs. */
    private val conjunctConsonants = intArrayOf(
        0x915, 0x939, 0x958, 0x95F, 0x978, 0x97F, 0x995, 0x9A8, 0x9AA, 0x9B0, 0x9B2, 0x9B2, 0x9B6, 0x9B9, 0x9DC, 0x9DD,
        0x9DF, 0x9DF, 0x9F0, 0x9F1, 0xA15, 0xA28, 0xA2A, 0xA30, 0xA32, 0xA33, 0xA35, 0xA36, 0xA38, 0xA39, 0xA59, 0xA5C,
        0xA5E, 0xA5E, 0xA95, 0xAA8, 0xAAA, 0xAB0, 0xAB2, 0xAB3, 0xAB5, 0xAB9, 0xAF9, 0xAF9, 0xB15, 0xB28, 0xB2A, 0xB30,
        0xB32, 0xB33, 0xB35, 0xB39, 0xB5C, 0xB5D, 0xB5F, 0xB5F, 0xB71, 0xB71, 0xB95, 0xB95, 0xB99, 0xB9A, 0xB9C, 0xB9C,
        0xB9E, 0xB9F, 0xBA3, 0xBA4, 0xBA8, 0xBAA, 0xBAE, 0xBB9, 0xC15, 0xC28, 0xC2A, 0xC39, 0xC58, 0xC5A, 0xC95, 0xCA8,
        0xCAA, 0xCB3, 0xCB5, 0xCB9, 0xCDE, 0xCDE, 0xD15, 0xD3A, 0xD9A, 0xDB1, 0xDB3, 0xDBB, 0xDBD, 0xDBD, 0xDC0, 0xDC6,
        0xE01, 0xE2E, 0xE81, 0xE82, 0xE84, 0xE84, 0xE86, 0xE8A, 0xE8C, 0xEA3, 0xEA5, 0xEA5, 0xEA7, 0xEAE, 0xEDC, 0xEDF,
        0xF40, 0xF47, 0xF49, 0xF6C, 0x1000, 0x1020, 0x103F, 0x103F, 0x1050, 0x1051, 0x105A, 0x105D, 0x1061, 0x1061,
        0x1065, 0x1066, 0x106E, 0x1070, 0x1075, 0x1081, 0x108E, 0x108E, 0x1703, 0x1711, 0x171F, 0x171F, 0x1723, 0x1731,
        0x1743, 0x1751, 0x1763, 0x176C, 0x176E, 0x1770, 0x1780, 0x17A2, 0x1901, 0x191E, 0x1950, 0x1962, 0x1980, 0x19AB,
        0x1A00, 0x1A16, 0x1A20, 0x1A4C, 0x1A53, 0x1A54, 0x1B13, 0x1B33, 0x1B45, 0x1B4C, 0x1B8A, 0x1BA0, 0x1BAE, 0x1BAF,
        0x1BBB, 0x1BBD, 0x1BC0, 0x1BE3, 0x1C00, 0x1C23, 0x1C4D, 0x1C4F, 0xA807, 0xA80A, 0xA80C, 0xA822, 0xA840, 0xA85D,
        0xA862, 0xA865, 0xA869, 0xA870, 0xA872, 0xA872, 0xA892, 0xA8B3, 0xA90A, 0xA921, 0xA930, 0xA946, 0xA989, 0xA98B,
        0xA98F, 0xA9B2, 0xA9E0, 0xA9E4, 0xA9E7, 0xA9EF, 0xA9FA, 0xA9FE, 0xAA06, 0xAA28, 0xAA60, 0xAA6F, 0xAA71, 0xAA73,
        0xAA7A, 0xAA7A, 0xAA7E, 0xAAAF, 0xAAE2, 0xAAEA, 0xABC0, 0xABCD, 0xABD0, 0xABD0, 0xABD2, 0xABDA, 0x10A00, 0x10A00,
        0x10A10, 0x10A13, 0x10A15, 0x10A17, 0x10A19, 0x10A35, 0x11013, 0x11037, 0x11075, 0x11075, 0x1108D, 0x110AF,
        0x11107, 0x11126, 0x11144, 0x11144, 0x11147, 0x11147, 0x11155, 0x11172, 0x11191, 0x111B2, 0x11208, 0x11211,
        0x11213, 0x1122B, 0x1123F, 0x1123F, 0x11284, 0x11286, 0x11288, 0x11288, 0x1128A, 0x1128D, 0x1128F, 0x1129D,
        0x1129F, 0x112A8, 0x112BA, 0x112DE, 0x11315, 0x11328, 0x1132A, 0x11330, 0x11332, 0x11333, 0x11335, 0x11339,
        0x1140E, 0x11434, 0x1148F, 0x114AF, 0x1158E, 0x115AE, 0x1160E, 0x1162F, 0x1168A, 0x116AA, 0x116B8, 0x116B8,
        0x11700, 0x1171A, 0x11740, 0x11746, 0x1180A, 0x1182B, 0x1190C, 0x11913, 0x11915, 0x11916, 0x11918, 0x1192F,
        0x119AE, 0x119D0, 0x11A0B, 0x11A32, 0x11A5C, 0x11A83, 0x11C0E, 0x11C2E, 0x11C72, 0x11C8F, 0x11D0C, 0x11D30,
        0x11D6C, 0x11D89, 0x11EE0, 0x11EF1, 0x11F12, 0x11F33
    )

    /** The marks Swift lets stand around a virama in GB9c (Swift 6.0.3), as start, end pairs. */
    private val conjunctExtends = intArrayOf(
        0x300, 0x34E, 0x350, 0x36F, 0x483, 0x487, 0x591, 0x5BD, 0x5BF, 0x5BF, 0x5C1, 0x5C2, 0x5C4, 0x5C5, 0x5C7, 0x5C7,
        0x610, 0x61A, 0x64B, 0x65F, 0x670, 0x670, 0x6D6, 0x6DC, 0x6DF, 0x6E4, 0x6E7, 0x6E8, 0x6EA, 0x6ED, 0x711, 0x711,
        0x730, 0x74A, 0x7EB, 0x7F3, 0x7FD, 0x7FD, 0x816, 0x819, 0x81B, 0x823, 0x825, 0x827, 0x829, 0x82D, 0x859, 0x85B,
        0x898, 0x89F, 0x8CA, 0x8E1, 0x8E3, 0x8FF, 0x93C, 0x93C, 0x951, 0x954, 0x9BC, 0x9BC, 0x9FE, 0x9FE, 0xA3C, 0xA3C,
        0xA4D, 0xA4D, 0xABC, 0xABC, 0xB3C, 0xB3C, 0xBCD, 0xBCD, 0xC3C, 0xC3C, 0xC55, 0xC56, 0xCBC, 0xCBC, 0xCCD, 0xCCD,
        0xD3B, 0xD3C, 0xDCA, 0xDCA, 0xE38, 0xE3A, 0xE48, 0xE4B, 0xEB8, 0xEBA, 0xEC8, 0xECB, 0xF18, 0xF19, 0xF35, 0xF35,
        0xF37, 0xF37, 0xF39, 0xF39, 0xF71, 0xF72, 0xF74, 0xF74, 0xF7A, 0xF7D, 0xF80, 0xF80, 0xF82, 0xF84, 0xF86, 0xF87,
        0xFC6, 0xFC6, 0x1037, 0x1037, 0x1039, 0x103A, 0x108D, 0x108D, 0x135D, 0x135F, 0x1714, 0x1714, 0x17D2, 0x17D2,
        0x17DD, 0x17DD, 0x18A9, 0x18A9, 0x1939, 0x193B, 0x1A17, 0x1A18, 0x1A60, 0x1A60, 0x1A75, 0x1A7C, 0x1A7F, 0x1A7F,
        0x1AB0, 0x1ABD, 0x1ABF, 0x1ACE, 0x1B34, 0x1B34, 0x1B6B, 0x1B73, 0x1BAB, 0x1BAB, 0x1BE6, 0x1BE6, 0x1C37, 0x1C37,
        0x1CD0, 0x1CD2, 0x1CD4, 0x1CE0, 0x1CE2, 0x1CE8, 0x1CED, 0x1CED, 0x1CF4, 0x1CF4, 0x1CF8, 0x1CF9, 0x1DC0, 0x1DFF,
        0x200D, 0x200D, 0x20D0, 0x20DC, 0x20E1, 0x20E1, 0x20E5, 0x20F0, 0x2CEF, 0x2CF1, 0x2D7F, 0x2D7F, 0x2DE0, 0x2DFF,
        0x302A, 0x302F, 0x3099, 0x309A, 0xA66F, 0xA66F, 0xA674, 0xA67D, 0xA69E, 0xA69F, 0xA6F0, 0xA6F1, 0xA806, 0xA806,
        0xA82C, 0xA82C, 0xA8C4, 0xA8C4, 0xA8E0, 0xA8F1, 0xA92B, 0xA92D, 0xA9B3, 0xA9B3, 0xAAB0, 0xAAB0, 0xAAB2, 0xAAB4,
        0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xAAC1, 0xAAF6, 0xAAF6, 0xABED, 0xABED, 0xFB1E, 0xFB1E, 0xFE20, 0xFE2F,
        0x101FD, 0x101FD, 0x102E0, 0x102E0, 0x10376, 0x1037A, 0x10A0D, 0x10A0D, 0x10A0F, 0x10A0F, 0x10A38, 0x10A3A,
        0x10A3F, 0x10A3F, 0x10AE5, 0x10AE6, 0x10D24, 0x10D27, 0x10EAB, 0x10EAC, 0x10EFD, 0x10EFF, 0x10F46, 0x10F50,
        0x10F82, 0x10F85, 0x11046, 0x11046, 0x11070, 0x11070, 0x1107F, 0x1107F, 0x110B9, 0x110BA, 0x11100, 0x11102,
        0x11133, 0x11134, 0x11173, 0x11173, 0x111CA, 0x111CA, 0x11236, 0x11236, 0x112E9, 0x112EA, 0x1133B, 0x1133C,
        0x11366, 0x1136C, 0x11370, 0x11374, 0x11442, 0x11442, 0x11446, 0x11446, 0x1145E, 0x1145E, 0x114C2, 0x114C3,
        0x115BF, 0x115C0, 0x1163F, 0x1163F, 0x116B7, 0x116B7, 0x1172B, 0x1172B, 0x11839, 0x1183A, 0x1193E, 0x1193E,
        0x11943, 0x11943, 0x119E0, 0x119E0, 0x11A34, 0x11A34, 0x11A47, 0x11A47, 0x11A99, 0x11A99, 0x11C3F, 0x11C3F,
        0x11D42, 0x11D42, 0x11D44, 0x11D45, 0x11D97, 0x11D97, 0x11F42, 0x11F42, 0x16AF0, 0x16AF4, 0x16B30, 0x16B36,
        0x1BC9E, 0x1BC9E, 0x1D165, 0x1D165, 0x1D167, 0x1D169, 0x1D16E, 0x1D172, 0x1D17B, 0x1D182, 0x1D185, 0x1D18B,
        0x1D1AA, 0x1D1AD, 0x1D242, 0x1D244, 0x1E000, 0x1E006, 0x1E008, 0x1E018, 0x1E01B, 0x1E021, 0x1E023, 0x1E024,
        0x1E026, 0x1E02A, 0x1E08F, 0x1E08F, 0x1E130, 0x1E136, 0x1E2AE, 0x1E2AE, 0x1E2EC, 0x1E2EF, 0x1E4EC, 0x1E4EF,
        0x1E8D0, 0x1E8D6, 0x1E944, 0x1E94A
    )
}

/** JSON text read as Swift's `JSONDecoder` reads it, for the area JSON in a backup (`OfflineArea.decode`); kotlinx's
 *  parser takes NaN, TRUE, raw control characters in strings and words without quotes, keeps the last of repeated
 *  keys and refuses a byte order mark. Like Swift's, it checks the structure of the whole text, the words `true`,
 *  `false` and `null` and that each `\u` has four bytes after it, but a string or number only where it is read: a
 *  string Swift would refuse to read (a raw control character, an unknown escape, half of a surrogate pair) is kept as
 *  a literal that is an error wherever it is read, and so is an object with such a key; numbers are kept as written,
 *  for `jsonDouble` and `wholeNumber`. One byte order mark is skipped, a comma may follow the last member, nesting
 *  stops at 512 levels, and of repeated keys the first counts. (Where Swift's decoder crashes, on a key it can't read
 *  in an object it reads, this throws.) */
@OptIn(ExperimentalSerializationApi::class)
internal class SwiftJSON private constructor(private val text: String) {
    private var i = if (text.startsWith('\uFEFF')) 1 else 0
    private var depth = 0

    companion object {
        fun parse(text: String): JsonElement {
            val reader = SwiftJSON(text)
            val value = reader.value()
            reader.skipSpace()
            require(reader.i == text.length) { "Unexpected text at ${reader.i}" }
            return value
        }
    }

    private fun skipSpace() {
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i += 1
    }

    /** The next character after white space; throws at the end of the text. */
    private fun next(): Char {
        skipSpace()
        require(i < text.length) { "Unexpected end" }
        return text[i]
    }

    private fun value(): JsonElement = when (val c = next()) {
        '{' -> nested {
            val start = i - 1
            val fields = LinkedHashMap<String, JsonElement>()
            var readable = true
            members('}') {
                require(next() == '"') { "Expected a key at $i" }
                val key = string()
                require(next() == ':') { "Expected : at $i" }
                i += 1
                val member = value()
                if (!key.isString) readable = false else if (key.content !in fields) fields[key.content] = member
            }
            if (readable) JsonObject(fields) else JsonUnquotedLiteral(text.substring(start, i))
        }
        '[' -> nested {
            val items = ArrayList<JsonElement>()
            members(']') { items.add(value()) }
            JsonArray(items)
        }
        '"' -> string()
        't' -> word("true", JsonPrimitive(true))
        'f' -> word("false", JsonPrimitive(false))
        'n' -> word("null", JsonNull)
        '-', in '0'..'9' -> {
            val start = i
            i += 1
            while (i < text.length && text[i] in "0123456789.eE+-") i += 1
            JsonUnquotedLiteral(text.substring(start, i))
        }
        else -> throw IllegalArgumentException("Unexpected $c at $i")
    }

    private inline fun nested(read: () -> JsonElement): JsonElement {
        require(depth < 512) { "Nested too deeply at $i" }
        depth += 1
        i += 1
        val value = read()
        depth -= 1
        return value
    }

    /** The members of an object or array, up to `close`: separated by commas, and one may follow the last. */
    private inline fun members(close: Char, member: () -> Unit) {
        if (next() == close) {
            i += 1
            return
        }
        while (true) {
            member()
            when (next()) {
                close -> break
                ',' -> {
                    i += 1
                    if (next() == close) break
                }
                else -> throw IllegalArgumentException("Unexpected ${text[i]} at $i")
            }
        }
        i += 1
    }

    private fun word(word: String, value: JsonElement): JsonElement {
        require(text.startsWith(word, i)) { "Unexpected text at $i" }
        i += word.length
        return value
    }

    /** A string: up to the closing quote, a backslash taking the character after it along, and `\u` the next four
     *  bytes (in UTF-8), which may not hold the quote. Its value as `stringValue` reads it, or else the string as
     *  written, as a literal. */
    private fun string(): JsonPrimitive {
        val start = i
        i += 1
        while (true) {
            require(i < text.length) { "Unterminated string" }
            when (text[i++]) {
                '"' -> break
                '\\' -> {
                    require(i < text.length) { "Unterminated string" }
                    if (text[i++] == 'u') {
                        var bytes = 0
                        while (bytes < 4) {
                            require(i < text.length && text[i] != '"') { "Short \\u escape at $i" }
                            val c = text.codePointAt(i)
                            bytes += if (c < 0x80) 1 else if (c < 0x800) 2 else if (c < 0x10000) 3 else 4
                            i += Character.charCount(c)
                        }
                    }
                }
            }
        }
        val value = stringValue(text.substring(start + 1, i - 1))
        return if (value != null) JsonPrimitive(value) else JsonUnquotedLiteral(text.substring(start, i))
    }

    /** What Swift reads from a string's text, or null where it refuses it: a raw control character, an unknown escape,
     *  `\u` without four hex digits, or half of a surrogate pair. */
    private fun stringValue(raw: String): String? {
        val out = StringBuilder(raw.length)
        var j = 0
        fun hex(): Int? {
            if (j + 4 > raw.length || !(j until j + 4).all { raw[it] in "0123456789abcdefABCDEF" }) return null
            j += 4
            return raw.substring(j - 4, j).toInt(16)
        }
        while (j < raw.length) {
            val c = raw[j++]
            if (c < ' ') return null
            if (c != '\\') {
                out.append(c)
                continue
            }
            when (raw[j++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val u = hex() ?: return null
                    if (u in 0xD800..0xDBFF) {
                        if (!raw.startsWith("\\u", j)) return null
                        j += 2
                        val low = hex() ?: return null
                        if (low !in 0xDC00..0xDFFF) return null
                        out.append(u.toChar()).append(low.toChar())
                    } else {
                        if (u in 0xDC00..0xDFFF) return null
                        out.append(u.toChar())
                    }
                }
                else -> return null
            }
        }
        return out.toString()
    }
}
