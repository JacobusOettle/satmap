package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.floor

// The plaintexts that groups seal (docs/groups/SPEC.md §4): trail chunks, live positions, pins and the two profiles.
// The iPhone core (GroupRecords.swift) writes and reads the same bytes; the shared vectors check both.

/** A record that doesn't follow its format; the reader drops it and logs one line. */
sealed class GroupRecordError(message: String) : Exception(message) {
    class Malformed : GroupRecordError("The record doesn't follow its format")
    class UnsupportedVersion : GroupRecordError("Another record format version")
}

/** One trail point (§4.2): whole seconds, micro-degrees, and whether a new line segment starts here. */
data class TrailPoint(val time: Long, val lat: Int, val lon: Int, val startsSegment: Boolean)

/** One member's trail points in one 10-minute window (§4.1, §4.2): a two-byte header (format 1, encoding), then the
 *  body as it is (encoding 0) or as raw DEFLATE after its decompressed length (encoding 1). The body is the point
 *  count and, per point, `(dt << 1) | s`, then the latitude and longitude as zigzag deltas. */
object TrailChunk {
    const val format: Byte = 0x01

    /** The start of the window that holds a time: `t − (t mod 600)`. */
    fun windowStart(time: Long): Long {
        val w = GroupPolicy.windowSeconds
        return time - ((time % w) + w) % w
    }

    /** The chunk plaintext. With `compress`, a body of 256 bytes or more is deflated when that is smaller. Throws
     *  for 0 or more than 600 points, a time outside the window or earlier than the one before, or a coordinate out
     *  of range. */
    fun encode(points: List<TrailPoint>, windowStart: Long, compress: Boolean): ByteArray {
        val body = encodeBody(points, windowStart)
        if (compress && body.size >= GroupPolicy.compressFrom) {
            val deflated = Deflate.raw(body)
            if (deflated != null) {
                val out = ByteArrayOutputStream()
                out.write(format.toInt())
                out.write(0x01)
                GroupVarint.append(body.size.toLong(), out)
                if (out.size() + deflated.size < 2 + body.size) {
                    out.write(deflated)
                    return out.toByteArray()
                }
            }
        }
        return byteArrayOf(format, 0x00) + body
    }

    /** The points of a chunk plaintext, checked as §4.2 says (DEFLATE inflated exactly). Throws if it breaks a rule. */
    fun decode(plain: ByteArray, windowStart: Long): List<TrailPoint> {
        if (plain.size < 2) throw GroupRecordError.Malformed()
        if (plain[0] != format) throw GroupRecordError.UnsupportedVersion()
        val body: ByteArray = when (plain[1].toInt()) {
            0x00 -> plain.copyOfRange(2, plain.size)
            0x01 -> {
                val cursor = GroupVarint.Cursor(2)
                val n = GroupVarint.read(plain, cursor)
                if (n == null || n < 1 || n > GroupPolicy.maxChunkBody || cursor.index >= plain.size) throw GroupRecordError.Malformed()
                Deflate.inflateExact(plain.copyOfRange(cursor.index, plain.size), n.toInt()) ?: throw GroupRecordError.Malformed()
            }
            else -> throw GroupRecordError.UnsupportedVersion()
        }
        if (body.size > GroupPolicy.maxChunkBody) throw GroupRecordError.Malformed()
        return decodeBody(body, windowStart)
    }

    private fun encodeBody(points: List<TrailPoint>, windowStart: Long): ByteArray {
        if (points.isEmpty() || points.size > GroupPolicy.maxPointsPerChunk) throw GroupRecordError.Malformed()
        val out = ByteArrayOutputStream()
        GroupVarint.append(points.size.toLong(), out)
        var previousTime = windowStart
        var previousLat = 0
        var previousLon = 0
        for (p in points) {
            val dt = p.time - previousTime
            if (dt < 0 || p.time >= windowStart + GroupPolicy.windowSeconds || !MicroDegrees.isValid(p.lat, p.lon)) {
                throw GroupRecordError.Malformed()
            }
            GroupVarint.append((dt shl 1) or (if (p.startsSegment) 1L else 0L), out)
            GroupVarint.append(GroupVarint.zigzag(p.lat - previousLat), out)
            GroupVarint.append(GroupVarint.zigzag(p.lon - previousLon), out)
            previousTime = p.time
            previousLat = p.lat
            previousLon = p.lon
        }
        if (out.size() > GroupPolicy.maxChunkBody) throw GroupRecordError.Malformed()
        return out.toByteArray()
    }

    private fun decodeBody(body: ByteArray, windowStart: Long): List<TrailPoint> {
        val cursor = GroupVarint.Cursor()
        val n = GroupVarint.read(body, cursor)
        if (n == null || n < 1 || n > GroupPolicy.maxPointsPerChunk) throw GroupRecordError.Malformed()
        val points = ArrayList<TrailPoint>(n.toInt())
        var time = windowStart
        var lat = 0L
        var lon = 0L
        repeat(n.toInt()) {
            val head = GroupVarint.read(body, cursor)
            val dLat = GroupVarint.read(body, cursor)
            val dLon = GroupVarint.read(body, cursor)
            if (head == null || dLat == null || dLon == null) throw GroupRecordError.Malformed()
            time += head ushr 1
            lat += GroupVarint.unzigzag(dLat)
            lon += GroupVarint.unzigzag(dLon)
            if (time >= windowStart + GroupPolicy.windowSeconds || abs(lat) > MicroDegrees.maxLatitude ||
                abs(lon) > MicroDegrees.maxLongitude) {
                throw GroupRecordError.Malformed()
            }
            points.add(TrailPoint(time = time, lat = lat.toInt(), lon = lon.toInt(), startsSegment = head and 1L == 1L))
        }
        if (cursor.index != body.size) throw GroupRecordError.Malformed()
        return points
    }
}

/** A live position (§4.4): 24 bytes, little-endian, with flags for the optional accuracy, heading and speed. */
data class LivePosition(
    val time: Long,
    val lat: Int,
    val lon: Int,
    /** Metres, 0–65535. */
    val accuracy: Int?,
    /** Whole degrees, 0–359. */
    val heading: Int?,
    /** Centimetres per second, 0–65535. */
    val speed: Int?,
) {
    fun encoded(): ByteArray {
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        var flags = 0
        if (accuracy != null) flags = flags or 1
        if (heading != null) flags = flags or 2
        if (speed != null) flags = flags or 4
        out.put(format)
        out.put(flags.toByte())
        out.putLong(time)
        out.putInt(lat)
        out.putInt(lon)
        for (value in listOf(accuracy, heading?.let { ((it % 360) + 360) % 360 }, speed)) {
            out.putShort((value ?: 0).coerceIn(0, 65535).toShort())
        }
        return out.array()
    }

    companion object {
        const val size = 24
        const val format: Byte = 0x01

        /** Throws unless it is 24 bytes of format 1 with coordinates in range and a heading of 0–359. Flag bits this
         *  version doesn't know are ignored. */
        fun decode(plain: ByteArray): LivePosition {
            if (plain.size != size) throw GroupRecordError.Malformed()
            if (plain[0] != format) throw GroupRecordError.UnsupportedVersion()
            val flags = plain[1].toInt()
            val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            fun uint16(at: Int): Int = b.getShort(at).toInt() and 0xFFFF
            val p = LivePosition(time = b.getLong(2), lat = b.getInt(10), lon = b.getInt(14),
                                 accuracy = if (flags and 1 != 0) uint16(18) else null,
                                 heading = if (flags and 2 != 0) uint16(20) else null,
                                 speed = if (flags and 4 != 0) uint16(22) else null)
            if (!MicroDegrees.isValid(p.lat, p.lon) || (p.heading ?: 0) >= 360) throw GroupRecordError.Malformed()
            return p
        }
    }
}

enum class PinType {
    animal,
    shelter,
    water,
    danger,
    pothole,
    police;

    val rawValue: String get() = name

    /** The title (§9.4). */
    val label: String
        get() = when (this) {
            animal -> "Animal sighting"
            shelter -> "Shelter"
            water -> "Water"
            danger -> "Danger"
            pothole -> "Pothole"
            police -> "Police"
        }

    /** The pin's colour (§9.4). */
    val colourHex: String
        get() = when (this) {
            animal -> "#2E7D32"
            shelter -> "#8D6E63"
            water -> "#1E88E5"
            danger -> "#E53935"
            pothole -> "#F57C00"
            police -> "#3949AB"
        }

    companion object {
        /** The type written as `rawValue`, or null (Swift's `PinType(rawValue:)`). */
        operator fun invoke(rawValue: String): PinType? = entries.firstOrNull { it.rawValue == rawValue }

        /** A pin of a type this version doesn't know is a plain "Pin" in grey (§4.5, §9.4). */
        const val unknownLabel = "Pin"
        const val unknownColourHex = "#757575"
    }
}

/** A pin's sealed JSON (§4.5). Its id, dropper and times are plain fields that the AAD binds. */
data class PinRecord(val type: String, val animal: String?, val note: String?, val lat: Int, val lon: Int) {
    val knownType: PinType? get() = PinType(type)

    /** Compact, keys sorted, padded to a multiple of 64 bytes (§2.6). An empty note is left out. */
    fun json(): ByteArray {
        val fields = mutableListOf<Pair<String, Any>>("v" to 1L, "type" to type, "lat" to lat.toLong(), "lon" to lon.toLong())
        if (animal != null) fields.add("animal" to animal)
        if (!note.isNullOrEmpty()) fields.add("note" to note)
        return GroupJSON.padded(fields, block = 64)
    }

    companion object {
        /** Throws for a wrong type, a missing field, `v` other than 1, a coordinate out of range, an animal pin without
         *  an animal, or a text over its limit. Unknown keys and `pad` are ignored. */
        fun decode(json: ByteArray): PinRecord {
            val w = GroupJSON.record(json)
            val v = GroupJSON.recordField { w.int("v") }
            val type = GroupJSON.recordField { w.string("type") }
            val lat = GroupJSON.recordField { w.int("lat") }
            val lon = GroupJSON.recordField { w.int("lon") }
            val animal = GroupJSON.recordField { w.optString("animal") }
            val note = GroupJSON.recordField { w.optString("note") }
            if (v != 1) throw GroupRecordError.UnsupportedVersion()
            if (!MicroDegrees.isValid(lat, lon) || type.isEmpty()) throw GroupRecordError.Malformed()
            if (type == PinType.animal.rawValue) {
                if (animal == null || !GroupJSON.fits(animal, GroupTextField.animal, allowEmpty = false)) throw GroupRecordError.Malformed()
            } else if (animal != null && !GroupJSON.fits(animal, GroupTextField.animal, allowEmpty = true)) {
                throw GroupRecordError.Malformed()
            }
            if (note != null && !GroupJSON.fits(note, GroupTextField.note, allowEmpty = true)) throw GroupRecordError.Malformed()
            return PinRecord(type = type, animal = if (type == PinType.animal.rawValue) animal else null,
                             note = note?.takeIf { it.isNotEmpty() }, lat = lat, lon = lon)
        }
    }
}

/** A member's sealed profile (§4.6): display name and colour index (0–11 in this version). */
data class MemberProfile(val name: String, val colour: Int) {
    fun json(): ByteArray = GroupJSON.padded(listOf("v" to 1L, "name" to name, "colour" to colour.toLong()), block = 32)

    companion object {
        /** Throws for a wrong type, a missing field, `v` other than 1, or an empty or too long name. A colour index
         *  this version doesn't know is kept; it is drawn grey. */
        fun decode(json: ByteArray): MemberProfile {
            val w = GroupJSON.record(json)
            val v = GroupJSON.recordField { w.int("v") }
            val name = GroupJSON.recordField { w.string("name") }
            val colour = GroupJSON.recordField { w.long("colour") }
            if (v != 1) throw GroupRecordError.UnsupportedVersion()
            if (!GroupJSON.fits(name, GroupTextField.displayName, allowEmpty = false)) throw GroupRecordError.Malformed()
            // Swift's `Int` is 64-bit: an index past an Int's range is unknown, drawn grey like any other.
            return MemberProfile(name = name, colour = colour.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }
}

/** The group's sealed profile (§4.7): its name. */
data class GroupProfile(val name: String) {
    fun json(): ByteArray = GroupJSON.padded(listOf("v" to 1L, "name" to name), block = 32)

    companion object {
        fun decode(json: ByteArray): GroupProfile {
            val w = GroupJSON.record(json)
            val v = GroupJSON.recordField { w.int("v") }
            val name = GroupJSON.recordField { w.string("name") }
            if (v != 1) throw GroupRecordError.UnsupportedVersion()
            if (!GroupJSON.fits(name, GroupTextField.groupName, allowEmpty = false)) throw GroupRecordError.Malformed()
            return GroupProfile(name = name)
        }
    }
}

/** The JSON of the sealed records (§2.6): compact, keys sorted by code point, non-ASCII written as itself and "/" not
 *  escaped, padded with `pad`. Written by hand (as on the iPhone) so the bytes equal the iPhone core's and the
 *  vectors'. Also the reading side every group JSON shares (records and API bodies): kotlinx-serialization-json's
 *  `JsonObject`, read as Swift's `JSONDecoder` reads a `Decodable` (a wrong type or a missing key throws, unknown keys
 *  are ignored). */
object GroupJSON {
    /** The object's JSON with its keys sorted. Values are `Long`s or `String`s. */
    fun objectJson(fields: List<Pair<String, Any>>): ByteArray {
        val sorted = fields.sortedWith { a, b -> compareCodePoints(a.first, b.first) }
        val out = StringBuilder("{")
        for ((i, field) in sorted.withIndex()) {
            if (i > 0) out.append(',')
            out.append(quoted(field.first)).append(':')
            when (val v = field.second) {
                is Long -> out.append(v)
                is Int -> out.append(v)
                is String -> out.append(quoted(v))
                else -> throw IllegalArgumentException("Not a record value: $v")
            }
        }
        out.append('}')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** The object with `pad`, a string of spaces that makes its length a multiple of `block` (SD25): encoded with an
     *  empty pad of length L, then again with (B − L mod B) mod B spaces. */
    fun padded(fields: List<Pair<String, Any>>, block: Int): ByteArray {
        val base = fields.filter { it.first != "pad" }
        val length = objectJson(base + ("pad" to "")).size
        val spaces = (block - length % block) % block
        return objectJson(base + ("pad" to " ".repeat(spaces)))
    }

    /** The text as a JSON string: quote, backslash and control characters escaped as Python's `json.dumps` does
     *  (`\n`, `\r`, `\t`, `\b`, `\f`, else `\u00xx`); everything else as itself. */
    fun quoted(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when (c.code) {
                0x22 -> out.append("\\\"")
                0x5C -> out.append("\\\\")
                0x0A -> out.append("\\n")
                0x0D -> out.append("\\r")
                0x09 -> out.append("\\t")
                0x08 -> out.append("\\b")
                0x0C -> out.append("\\f")
                in 0x00 until 0x20 -> out.append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 15])
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /** Whether a received text is within its field's limits (§2.5). */
    fun fits(text: String, field: GroupTextField, allowEmpty: Boolean): Boolean {
        val n = GroupText.codePoints(text)
        return (allowEmpty || n >= 1) && n <= field.max && text.toByteArray(Charsets.UTF_8).size <= field.maxBytes
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) {
            if (x[i] != y[i]) return x[i].compareTo(y[i])
        }
        return x.size.compareTo(y.size)
    }

    // Reading

    /** A record's JSON object; any failure is `Malformed`. */
    internal fun record(json: ByteArray): Fields = try {
        Fields(parseObject(json))
    } catch (e: Exception) {
        throw GroupRecordError.Malformed()
    }

    /** Reads a record field, mapping a missing or mistyped value to `Malformed`. */
    internal inline fun <T> recordField(read: () -> T): T = try {
        read()
    } catch (e: SerializationException) {
        throw GroupRecordError.Malformed()
    }

    /** The JSON object in UTF-8 bytes. Throws `SerializationException` if it isn't one (invalid UTF-8 included). */
    fun parseObject(json: ByteArray): JsonObject = parse(json) as? JsonObject ?: throw SerializationException("Not an object")

    /** Any JSON value in UTF-8 bytes. Throws `SerializationException` if it isn't JSON. */
    fun parse(json: ByteArray): JsonElement {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString()
        } catch (e: CharacterCodingException) {
            throw SerializationException("Invalid UTF-8")
        }
        return try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message ?: "Invalid JSON")
        }
    }

    /** The values of a JSON object, each read only as Swift's `JSONDecoder` reads that type: no number written as a
     *  string or the other way round, no fraction for a whole number. Missing and `null` are the same for an
     *  optional value; a required one throws `SerializationException`. */
    class Fields(val obj: JsonObject) {
        fun has(key: String): Boolean = obj.containsKey(key)

        fun isNull(key: String): Boolean = obj[key] is JsonNull

        private fun value(key: String): JsonElement = obj[key]?.takeIf { it !is JsonNull } ?: throw SerializationException("No $key")

        private fun optional(key: String): JsonElement? = obj[key]?.takeIf { it !is JsonNull }

        fun string(key: String): String = stringValue(value(key))
        fun optString(key: String): String? = optional(key)?.let(::stringValue)
        fun long(key: String): Long = longValue(value(key))
        fun optLong(key: String): Long? = optional(key)?.let(::longValue)
        fun int(key: String): Int = intValue(value(key))
        fun optInt(key: String): Int? = optional(key)?.let(::intValue)
        fun bool(key: String): Boolean = boolValue(value(key))
        fun optBool(key: String): Boolean? = optional(key)?.let(::boolValue)
        fun obj(key: String): Fields = Fields(objectValue(value(key)))
        fun optObj(key: String): Fields? = optional(key)?.let { Fields(objectValue(it)) }
        fun array(key: String): JsonArray = arrayValue(value(key))
        fun optArray(key: String): JsonArray? = optional(key)?.let(::arrayValue)
    }

    /** A string, without half of a surrogate pair (which only an escape such as "\ud800" can give). */
    fun stringValue(json: JsonElement): String {
        val p = json as? JsonPrimitive
        if (p == null || p is JsonNull || !p.isString || GroupText.hasLoneSurrogate(p.content)) throw SerializationException("Not a string: $json")
        return p.content
    }

    /** A whole number: digits, or a fraction or exponent whose value is whole. */
    fun longValue(json: JsonElement): Long {
        val p = json as? JsonPrimitive
        if (p == null || p is JsonNull || p.isString) throw SerializationException("Not a number: $json")
        p.content.toLongOrNull()?.let { return it }
        val d = p.content.toDoubleOrNull()
        if (d == null || !d.isFinite() || d != floor(d) || abs(d) >= 9.223372036854775807E18) throw SerializationException("Not a whole number: $json")
        return d.toLong()
    }

    fun intValue(json: JsonElement): Int {
        val v = longValue(json)
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw SerializationException("Out of range: $json")
        return v.toInt()
    }

    fun boolValue(json: JsonElement): Boolean {
        val p = json as? JsonPrimitive
        if (p == null || p is JsonNull || p.isString || (p.content != "true" && p.content != "false")) throw SerializationException("Not a boolean: $json")
        return p.content == "true"
    }

    fun objectValue(json: JsonElement): JsonObject = json as? JsonObject ?: throw SerializationException("Not an object: $json")

    fun arrayValue(json: JsonElement): JsonArray = json as? JsonArray ?: throw SerializationException("Not an array: $json")
}
