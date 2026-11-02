package com.jacobusoettle.satmap.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.logging.Logger
import kotlin.math.floor

// Wire basics for groups (docs/groups/SPEC.md §2): base64url, ids, random bytes, micro-degrees, text cleaning,
// initials, "ago" times, the upload pace constants and the live-position rounding. The iPhone core (GroupWire.swift)
// is the same, checked by the shared vectors (Tests/SatMapCoreTests/Fixtures/groups/vectors.json).

/** base64url (RFC 4648 §5) without padding (groups §2.1). */
object Base64URL {
    fun encode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** The bytes, or null for any character outside `A–Z a–z 0–9 - _` (padding, `+`, `/` and whitespace included) or
     *  a length that is 1 more than a multiple of 4. Non-zero unused trailing bits are accepted (§2.1). Decoded here,
     *  as on the iPhone, rather than by `Base64.getUrlDecoder()`, which accepts padding. */
    fun decode(text: String): ByteArray? {
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var count = 0
        for (c in text) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            count += 1
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        if (count % 4 == 1) return null
        return out.toByteArray()
    }

    /** `decode`, accepted only when it gives exactly `count` bytes: an id text (22 chars → 16 bytes, 43 → 32, §2.2). */
    fun decode(text: String, count: Int): ByteArray? = decode(text)?.takeIf { it.size == count }
}

/** Bytes from the system's cryptographic generator (§2.2). */
object GroupRandom {
    private val random = SecureRandom()

    fun bytes(count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        return ByteArray(count).also { random.nextBytes(it) }
    }

    /** A new random id: 16 bytes as 22 base64url characters (`groupId`, `memberId`, `pinId`). */
    fun id(): String = Base64URL.encode(bytes(16))
}

/** Coordinates as Int32 micro-degrees (SD19, §2.4). */
object MicroDegrees {
    /** `degrees × 1e6`, rounded half away from zero (Swift's `rounded()`, not `Math.round` or `kotlin.math.round`). */
    fun from(degrees: Double): Int {
        val x = (degrees * 1e6).rounded()
        if (!x.isFinite()) return 0
        return x.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    fun degrees(e6: Int): Double = e6.toDouble() / 1e6

    const val maxLatitude = 90_000_000
    const val maxLongitude = 180_000_000

    fun isValid(lat: Int, lon: Int): Boolean =
        kotlin.math.abs(lat.toLong()) <= maxLatitude && kotlin.math.abs(lon.toLong()) <= maxLongitude
}

/** The group text fields with limits (§2.5). */
enum class GroupTextField {
    groupName,
    displayName,
    animal,
    note,
    reportComment;

    /** Fewest code points. */
    val min: Int get() = if (this == note || this == reportComment) 0 else 1

    /** Most code points. */
    val max: Int
        get() = when (this) {
            groupName -> 40
            displayName -> 30
            animal -> 40
            note -> 140
            reportComment -> 500
        }

    /** Most UTF-8 bytes. */
    val maxBytes: Int get() = max * 4

    /** Notes and report comments may hold up to 3 newlines (U+000A). */
    val allowsNewlines: Boolean get() = this == note || this == reportComment
}

object GroupText {
    /** The Unicode White_Space code points, written out (§2.5): not Kotlin's `trim()` (which also strips
     *  U+001C–U+001F) or `Character.isWhitespace`, which differ. */
    fun isWhiteSpace(c: Int): Boolean = when (c) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** The text as it is saved and sent (§2.5): NFC, White_Space trimmed at both ends, then checked (no control
     *  characters, U+2028 or U+2029; at most 3 newlines in notes and report comments) and held to the field's limits.
     *  Null when it is invalid. An empty note or report comment is valid and gives "".
     *
     *  Half of a surrogate pair (which a Swift string can't hold) is invalid too. */
    fun clean(text: String, field: GroupTextField): String? {
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (hasLoneSurrogate(normalised)) return null
        val all = normalised.codePoints().toArray()
        var start = 0
        var end = all.size
        while (start < end && isWhiteSpace(all[start])) start += 1
        while (end > start && isWhiteSpace(all[end - 1])) end -= 1
        val scalars = all.copyOfRange(start, end)
        var newlines = 0
        for (c in scalars) {
            if (c == 0x2028 || c == 0x2029) return null
            if (Character.getType(c) == Character.CONTROL.toInt()) {
                if (c != 0x0A || !field.allowsNewlines) return null
                newlines += 1
            }
        }
        if (newlines > 3) return null
        val cleaned = String(scalars, 0, scalars.size)
        if (scalars.size < field.min || scalars.size > field.max || cleaned.toByteArray(Charsets.UTF_8).size > field.maxBytes) return null
        return cleaned
    }

    /** The length in Unicode scalar values (code points), as every limit counts it. */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** The letters on a member's dot (§9.5): a leading emoji as it is, otherwise the first letter or digit of the
     *  first and the last word, upper-cased; "?" when there is none. */
    fun initials(name: String): String {
        val cps = name.codePoints().toArray()
        if (cps.isNotEmpty() && isPictographic(cps[0])) {
            val head = emojiHead(cps)
            return String(head, 0, head.size)
        }
        val words = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (c in cps) {
            if (isWhiteSpace(c)) {
                if (current.isNotEmpty()) words.add(current)
                current = mutableListOf()
            } else {
                current.add(c)
            }
        }
        if (current.isNotEmpty()) words.add(current)
        val firsts = words.mapNotNull { word -> word.firstOrNull { isLetter(it) || Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() } }
        val first = firsts.firstOrNull() ?: return "?"
        val picked = mutableListOf(first)
        if (firsts.size >= 2) picked.add(firsts.last())
        return picked.joinToString("") { String(Character.toChars(it)).uppercase() }
    }

    /** Whether the code point is a letter: general category L* (Swift's five letter categories). */
    fun isLetter(c: Int): Boolean = Character.isLetter(c)

    private fun isPictographic(c: Int): Boolean = c in 0x2600..0x27BF || c in 0x1F000..0x1FAFF

    private fun isRegionalIndicator(c: Int): Boolean = c in 0x1F1E6..0x1F1FF

    /** The emoji at the start: the code point plus its variation selectors, keycap, skin tones, tags and ZWJ joins; a
     *  flag's two regional indicators. */
    private fun emojiHead(cps: IntArray): IntArray {
        val out = mutableListOf(cps[0])
        var i = 1
        if (isRegionalIndicator(cps[0])) {
            if (i < cps.size && isRegionalIndicator(cps[i])) out.add(cps[i])
            return out.toIntArray()
        }
        while (i < cps.size) {
            val v = cps[i]
            if (v == 0xFE0E || v == 0xFE0F || v == 0x20E3 || v in 0x1F3FB..0x1F3FF || v in 0xE0020..0xE007F) {
                out.add(v)
                i += 1
            } else if (v == 0x200D && i + 1 < cps.size) {
                out.add(v)
                out.add(cps[i + 1])
                i += 2
            } else {
                break
            }
        }
        return out.toIntArray()
    }

    /** Whether the text holds half of a surrogate pair. */
    internal fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i += 1
            }
        }
        return false
    }
}

object GroupFormat {
    /** "just now", "{m} min ago", "{h} h ago" or "{d} d ago" for an age in whole seconds (§9.3 "Times"); a negative
     *  age (clocks differ) is "just now". */
    fun ago(seconds: Long): String {
        if (seconds < 60) return "just now"
        if (seconds < 3600) return "${seconds / 60} min ago"
        if (seconds < 172_800) return "${seconds / 3600} h ago"
        return "${seconds / 86_400} d ago"
    }
}

/** The pace and size constants (§8.6), plus the other fixed numbers of the client. */
object GroupPolicy {
    const val uploadInterval: Long = 60
    const val liveWatchedForeground: Long = 5
    const val liveWatchedBackground: Long = 15

    /** Metres. */
    const val liveMinMove: Double = 5.0
    const val liveHeartbeat: Long = 30
    const val staleAfter: Long = 600
    const val liveHiddenAfter: Long = 86_400
    const val animalPinLifetime: Long = 43_200
    const val maxChunksPerUpload = 50
    const val maxUploadBytes = 196_608

    /** Metres. */
    const val liveMaxAccuracy: Double = 100.0
    const val socketPing: Long = 30

    /** Trail windows are 10 minutes, aligned to UTC (§4.1). */
    const val windowSeconds: Long = 600
    const val maxPointsPerChunk = 600
    const val maxChunkBody = 8192

    /** Writers try DEFLATE for bodies of at least this many bytes (§4.2). */
    const val compressFrom = 256

    /** The default "Trails" setting shows the last 24 hours of other members' trails (SD16). */
    const val dayTrail: Long = 86_400

    /** The terms version this core knows (§8.13). */
    const val termsVersion = 1

    /** The member colour palette (§4.6), index 0–11. */
    val palette = listOf("#E5484D", "#46A758", "#8E4EC6", "#A18072", "#12A594", "#6E56CF",
                         "#AB4ABA", "#5C7C2F", "#C2298A", "#3E63DD", "#B54548", "#2F7C57")

    /** "Unknown member" and stale dots are grey (§8.9, §9.5). */
    const val grey = "#8E8E93"

    /** Socket reconnect backoff in seconds (§6.6), ±20 % jitter. */
    val socketBackoff: List<Long> = listOf(1, 2, 4, 8, 16, 32, 60)

    /** Retry backoff for network failures and 5xx (§6.7). */
    val retryBackoff: List<Long> = listOf(2, 4, 8, 16, 32, 64, 128, 256, 300)
}

/** A fix from the recorder, as a live position (§4.4). */
object LiveFix {
    /** The live position for a fix: micro-degree coordinates, accuracy and speed rounded and capped at 65535, the
     *  heading from the course when moving at 0.5 m/s or more, else the compass heading. A negative or missing value
     *  is unknown. */
    fun position(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?, heading: Double?,
                 time: Double): LivePosition {
        val acc = accuracy?.let { if (it >= 0 && it.isFinite()) minOf(65535.0, it.rounded()).toInt() else null }
        val spd = speed?.let { if (it >= 0 && it.isFinite()) minOf(65535.0, (it * 100).rounded()).toInt() else null }
        var hdg: Int? = null
        if (speed != null && speed >= 0.5 && course != null && course >= 0 && course.isFinite()) {
            hdg = course.rounded().toInt() % 360
        } else if (heading != null && heading >= 0 && heading.isFinite()) {
            hdg = heading.rounded().toInt() % 360
        }
        return LivePosition(time = floor(time).toLong(), lat = MicroDegrees.from(lat), lon = MicroDegrees.from(lon),
                            accuracy = acc, heading = hdg, speed = spd)
    }
}

/** `uvarint` and `svarint` (§2.7). Values are `Long`s holding an unsigned 32-bit number (Swift's `UInt32`). */
object GroupVarint {
    /** Where a reader is in its bytes (Swift's `inout Int` index). */
    class Cursor(var index: Int = 0)

    fun append(value: Long, out: ByteArrayOutputStream) {
        var v = value and 0xFFFF_FFFFL
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /** Reads a `uvarint` at the cursor, moving it past. Null for a truncated value, a sixth byte, or 2^32 or more. */
    fun read(bytes: ByteArray, cursor: Cursor): Long? {
        var value = 0L
        var shift = 0
        for (n in 0 until 5) {
            if (cursor.index >= bytes.size) return null
            val b = bytes[cursor.index].toInt() and 0xFF
            cursor.index += 1
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                return if (value <= 0xFFFF_FFFFL) value else null
            }
            shift += 7
            if (n == 4) return null
        }
        return null
    }

    fun zigzag(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFF_FFFFL

    fun unzigzag(u: Long): Int = ((u ushr 1) xor -(u and 1)).toInt()
}

/** One log line without content (§3.4): never names, places, notes, ids or passes. */
object GroupLog {
    private val logger = Logger.getLogger("groups")

    fun note(message: String) {
        logger.info(message)
    }
}
