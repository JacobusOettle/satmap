package com.jacobusoettle.satmap.core

import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** JSON read the way Swift's `JSONDecoder` (swift-foundation, iOS 17+) reads it, so a server answer the iPhone
 *  accepts is accepted here and one it refuses is refused. The layout is checked when reading: whitespace is space,
 *  tab, CR and LF; one trailing comma is allowed before `]` or `}`; at most 512 levels; nothing after the value.
 *  Strings and numbers are only checked when a value is used (`string()`, `double()`); the first of repeated keys
 *  wins. Errors are `SerializationException` (Swift's `DecodingError`). No Swift file of its own: Swift has it built
 *  in. */
internal sealed interface SwiftJson {
    class Obj internal constructor(private val keys: List<Str>, private val values: List<SwiftJson>) : SwiftJson {
        private val members: Map<String, SwiftJson> by lazy {
            val m = LinkedHashMap<String, SwiftJson>()
            keys.forEachIndexed { i, k -> m.putIfAbsent(k.string(), values[i]) }
            m
        }

        operator fun get(key: String): SwiftJson? = members[key]
    }

    class Arr internal constructor(val items: List<SwiftJson>) : SwiftJson

    class Str internal constructor(private val src: ByteArray, private val start: Int, private val end: Int) : SwiftJson {
        fun string(): String {
            val text = decode(Charsets.UTF_8, ByteBuffer.wrap(src, start, end - start)) ?: fail("Invalid UTF-8")
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                when {
                    c < ' ' -> fail("Unescaped control character")
                    c != '\\' -> out.append(c)
                    else -> when (val e = text.getOrNull(i++)) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val u = hex(text, i).also { i += 4 }
                            if (u.isLowSurrogate()) fail("Lone surrogate")
                            out.append(u)
                            if (u.isHighSurrogate()) {
                                if (text.getOrNull(i) != '\\' || text.getOrNull(i + 1) != 'u') fail("Lone surrogate")
                                val low = hex(text, i + 2).also { i += 6 }
                                if (!low.isLowSurrogate()) fail("Lone surrogate")
                                out.append(low)
                            }
                        }
                        else -> fail("Invalid escape")
                    }
                }
            }
            return out.toString()
        }

        private fun hex(s: String, at: Int): Char {
            if (at + 4 > s.length) fail("Invalid unicode escape")
            var v = 0
            for (k in at until at + 4) v = v * 16 + (Character.digit(s[k], 16).takeIf { it >= 0 } ?: fail("Invalid unicode escape"))
            return v.toChar()
        }
    }

    class Num internal constructor(private val text: String) : SwiftJson {
        /** RFC 8259 grammar; a value that rounds to infinity, or to zero from non-zero digits, is refused. */
        fun double(): Double {
            if (!grammar.matches(text)) fail("Invalid number $text")
            val d = text.toDouble()
            if (d.isInfinite()) fail("Number $text is not representable in Swift.")
            if (d == 0.0 && text.substringBefore('e').substringBefore('E').any { it in '1'..'9' }) {
                fail("Number $text is not representable in Swift.")
            }
            return d
        }

        private companion object {
            val grammar = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }

    class Bool internal constructor(val value: Boolean) : SwiftJson

    data object Null : SwiftJson

    companion object {
        private const val maxDepth = 512

        fun parse(data: ByteArray): SwiftJson {
            val bytes = utf8(data)
            val r = Reader(bytes)
            r.skipWhitespace()
            val value = r.value(0)
            r.skipWhitespace()
            if (r.i != bytes.size) fail("The given data was not valid JSON.")
            return value
        }

        /** UTF-8 as is (after a byte order mark); UTF-16 and UTF-32 found from the byte order mark or the zero bytes. */
        private fun utf8(data: ByteArray): ByteArray {
            fun startsWith(vararg b: Int) = data.size >= b.size && b.indices.all { data[it] == b[it].toByte() }
            fun from(charset: String, skip: Int): ByteArray {
                val text = decode(Charset.forName(charset), ByteBuffer.wrap(data, skip, data.size - skip))
                return (text ?: fail("The given data was not valid JSON.")).toByteArray(Charsets.UTF_8)
            }
            if (data.size >= 4) {
                if (startsWith(0xEF, 0xBB, 0xBF)) return data.copyOfRange(3, data.size)
                if (startsWith(0xFF, 0xFE)) return from("UTF-16LE", 2)
                if (startsWith(0xFE, 0xFF)) return from("UTF-16BE", 2)
                val z = (0..3).map { data[it] == 0.toByte() }
                return when {
                    z[0] && z[1] && z[2] -> from("UTF-32BE", 0)
                    z[1] && z[2] && z[3] -> from("UTF-32LE", 0)
                    z[0] && z[2] -> from("UTF-16BE", 0)
                    z[1] && z[3] -> from("UTF-16LE", 0)
                    else -> data
                }
            }
            if (data.size >= 2) {
                if (data[0] == 0.toByte()) return from("UTF-16BE", 0)
                if (data[1] == 0.toByte()) return from("UTF-16LE", 0)
            }
            return data
        }

        /** The text, or null when the bytes aren't valid in the charset. */
        private fun decode(charset: Charset, bytes: ByteBuffer): String? = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString()
        } catch (e: CharacterCodingException) {
            null
        }

        private fun fail(message: String): Nothing = throw SerializationException(message)
    }

    private class Reader(val b: ByteArray) {
        var i = 0

        private fun peek(): Int = if (i < b.size) b[i].toInt() and 0xFF else -1

        fun skipWhitespace() {
            while (peek().let { it == 0x20 || it == 0x09 || it == 0x0A || it == 0x0D }) i++
        }

        fun value(depth: Int): SwiftJson = when (val c = peek()) {
            '{'.code -> obj(depth + 1)
            '['.code -> arr(depth + 1)
            '"'.code -> str()
            't'.code -> literal("true", Bool(true))
            'f'.code -> literal("false", Bool(false))
            'n'.code -> literal("null", Null)
            else -> if (c == '-'.code || c in '0'.code..'9'.code) num() else fail("The given data was not valid JSON.")
        }

        private fun literal(word: String, v: SwiftJson): SwiftJson {
            for (ch in word) if (peek() == ch.code) i++ else fail("The given data was not valid JSON.")
            return v
        }

        private fun num(): SwiftJson {
            val start = i++
            while (peek().let { it in '0'.code..'9'.code || it == '.'.code || it == 'e'.code || it == 'E'.code || it == '+'.code || it == '-'.code }) i++
            return Num(String(b, start, i - start, Charsets.US_ASCII))
        }

        private fun str(): Str {
            val start = ++i
            while (true) {
                when (peek()) {
                    -1 -> fail("Unterminated string")
                    '"'.code -> return Str(b, start, i).also { i++ }
                    '\\'.code -> i += 2
                    else -> i++
                }
            }
        }

        private fun arr(depth: Int): Arr {
            if (depth > maxDepth) fail("Too many nested arrays or dictionaries")
            i++
            val items = mutableListOf<SwiftJson>()
            skipWhitespace()
            if (peek() == ']'.code) return Arr(items).also { i++ }
            while (true) {
                items.add(value(depth))
                skipWhitespace()
                when (peek()) {
                    ']'.code -> return Arr(items).also { i++ }
                    ','.code -> {
                        i++
                        skipWhitespace()
                        if (peek() == ']'.code) return Arr(items).also { i++ }
                    }
                    else -> fail("The given data was not valid JSON.")
                }
            }
        }

        private fun obj(depth: Int): Obj {
            if (depth > maxDepth) fail("Too many nested arrays or dictionaries")
            i++
            val keys = mutableListOf<Str>()
            val values = mutableListOf<SwiftJson>()
            skipWhitespace()
            if (peek() == '}'.code) return Obj(keys, values).also { i++ }
            while (true) {
                if (peek() != '"'.code) fail("The given data was not valid JSON.")
                keys.add(str())
                skipWhitespace()
                if (peek() != ':'.code) fail("The given data was not valid JSON.")
                i++
                skipWhitespace()
                values.add(value(depth))
                skipWhitespace()
                when (peek()) {
                    '}'.code -> return Obj(keys, values).also { i++ }
                    ','.code -> {
                        i++
                        skipWhitespace()
                        if (peek() == '}'.code) return Obj(keys, values).also { i++ }
                    }
                    else -> fail("The given data was not valid JSON.")
                }
            }
        }
    }
}
