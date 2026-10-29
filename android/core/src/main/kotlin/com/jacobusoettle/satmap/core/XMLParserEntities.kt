package com.jacobusoettle.satmap.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How XMLParser reports the general entities a file's internal DTD declares. It expands them (libxml2 with entity
 *  substitution), but Foundation's getEntity hook raises a flag for each one referenced in element content (and for
 *  each one referenced in an attribute inside another entity's text, where the parser is still in content), and its
 *  characters callback drops the next run of text and lowers the flag. So an entity's first run of text is lost or,
 *  for one without text (empty, only markup, or external, which XMLParser doesn't load), the next run after it.
 *  Expat and Xerces expand entities without that and don't show where libxml2's runs end, so such a file is handed to
 *  them as a String with its references in content already expanded here and the dropped runs left out. Null (parse
 *  the file as it is) when it declares no general entity, or holds something this doesn't follow (a parameter entity,
 *  an undeclared or unparsed entity in content, deep nesting). */
internal object XMLParserEntities {
    private sealed interface Entity
    private class Internal(val text: String) : Entity
    private object External : Entity
    private object Unparsed : Entity

    private val predefined = setOf("lt", "gt", "amp", "apos", "quot")

    /** `declared`: the encoding the file's XML declaration names, when not UTF-8 or UTF-16 (`KMLParser`'s). */
    fun expand(data: ByteArray, declared: Charset?): String? {
        val text = decode(data, declared) ?: return null
        if (!text.contains("<!ENTITY")) return null
        val (entities, contentStart) = declarations(text) ?: return null
        if (entities.keys.all { it in predefined }) return null
        val out = StringBuilder(text.length)
        out.append(text, 0, contentStart)
        val expander = Expander(entities, limit = 10 * text.length + (1 shl 20))
        if (!expander.content(text, contentStart, text.length, 0, out)) return null
        return out.toString()
    }

    /** The text as libxml2 decodes it: by its byte order mark, else the encoding its XML declaration names, else UTF-8. */
    private fun decode(data: ByteArray, declared: Charset?): String? {
        fun b(i: Int) = if (i < data.size) data[i].toInt() and 0xff else -1
        val (charset, skip) = when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> Charsets.UTF_8 to 3
            b(0) == 0xFE && b(1) == 0xFF -> Charsets.UTF_16BE to 2
            b(0) == 0xFF && b(1) == 0xFE -> Charsets.UTF_16LE to 2
            else -> (declared ?: Charsets.UTF_8) to 0
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, skip, data.size - skip)).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun Char.isXmlSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r'

    private fun isNameChar(c: Char, first: Boolean) =
        c.isLetter() || c == '_' || c == ':' || c.code > 0x7f ||
            (!first && (c.isDigit() || c == '-' || c == '.'))

    private fun name(s: String, at: Int): String? {
        var i = at
        while (i < s.length && isNameChar(s[i], i == at)) i++
        return if (i > at) s.substring(at, i) else null
    }

    private fun after(s: String, at: Int, end: String): Int? = s.indexOf(end, at).takeIf { it >= 0 }?.plus(end.length)

    /** The index after the '>' closing a declaration or tag at `at` (quoted text may hold '>'). */
    private fun closing(s: String, at: Int, to: Int = s.length): Int? {
        var i = at
        while (i < to) {
            when (val c = s[i]) {
                '"', '\'' -> i = s.indexOf(c, i + 1).takeIf { it in 0 until to }?.plus(1) ?: return null
                '>' -> return i + 1
                else -> i++
            }
        }
        return null
    }

    /** The general entities the internal DTD declares (a name's first declaration counts) and where the DOCTYPE ends. */
    private fun declarations(s: String): Pair<Map<String, Entity>, Int>? {
        var i = 0
        while (true) {
            while (i < s.length && s[i].isXmlSpace()) i++
            i = when {
                s.startsWith("<!--", i) -> after(s, i + 4, "-->") ?: return null
                s.startsWith("<?", i) -> after(s, i + 2, "?>") ?: return null
                else -> break
            }
        }
        if (!s.startsWith("<!DOCTYPE", i)) return null
        i += 9
        while (true) {
            if (i >= s.length) return null
            when (val c = s[i]) {
                '"', '\'' -> i = s.indexOf(c, i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                '[' -> { i++; break }
                '>' -> return null
                else -> i++
            }
        }
        val entities = mutableMapOf<String, Entity>()
        while (true) {
            while (i < s.length && s[i].isXmlSpace()) i++
            if (i >= s.length) return null
            i = when {
                s[i] == ']' -> break
                s.startsWith("<!--", i) -> after(s, i + 4, "-->") ?: return null
                s.startsWith("<?", i) -> after(s, i + 2, "?>") ?: return null
                s.startsWith("<!ENTITY", i) -> entity(s, i + 8, entities) ?: return null
                s.startsWith("<!", i) -> closing(s, i) ?: return null
                else -> return null // a parameter entity reference, or not a DTD
            }
        }
        i++
        while (i < s.length && s[i].isXmlSpace()) i++
        if (i >= s.length || s[i] != '>') return null
        return entities to i + 1
    }

    private fun entity(s: String, at: Int, entities: MutableMap<String, Entity>): Int? {
        var i = at
        fun space(): Boolean {
            val from = i
            while (i < s.length && s[i].isXmlSpace()) i++
            return i > from
        }
        fun literal(): String? {
            val q = s.getOrNull(i)
            if (q != '"' && q != '\'') return null
            val end = s.indexOf(q, i + 1).takeIf { it >= 0 } ?: return null
            return s.substring(i + 1, end).also { i = end + 1 }
        }
        if (!space()) return null
        if (s.getOrNull(i) == '%') return closing(s, i) // a parameter entity
        val name = name(s, i) ?: return null
        i += name.length
        if (!space()) return null
        val entity: Entity
        if (s.getOrNull(i) == '"' || s.getOrNull(i) == '\'') {
            entity = Internal(replacementText(literal() ?: return null) ?: return null)
        } else {
            when {
                s.startsWith("SYSTEM", i) -> { i += 6; if (!space()) return null; literal() ?: return null }
                s.startsWith("PUBLIC", i) -> {
                    i += 6
                    if (!space()) return null
                    literal() ?: return null
                    if (!space()) return null
                    literal() ?: return null
                }
                else -> return null
            }
            entity = if (space() && s.startsWith("NDATA", i)) Unparsed else External
            if (entity == Unparsed) {
                i += 5
                if (!space()) return null
                i += (name(s, i) ?: return null).length
            }
        }
        space()
        if (s.getOrNull(i) != '>') return null
        entities.putIfAbsent(name, entity)
        return i + 1
    }

    /** An entity value's replacement text: line ends as LF, character references replaced, general ones kept. */
    private fun replacementText(value: String): String? {
        val v = value.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            when {
                v[i] == '%' -> return null
                v.startsWith("&#", i) -> {
                    val end = v.indexOf(';', i).takeIf { it >= 0 } ?: return null
                    out.appendCodePoint(charReference(v.substring(i + 2, end)) ?: return null)
                    i = end + 1
                }
                else -> out.append(v[i++])
            }
        }
        return out.toString()
    }

    private fun charReference(body: String): Int? {
        val hex = body.startsWith("x")
        val digits = if (hex) body.substring(1) else body
        if (digits.isEmpty() || !digits.all { if (hex) it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' else it in '0'..'9' }) return null
        val v = digits.toLongOrNull(if (hex) 16 else 10) ?: return null
        val ok = v == 0x9L || v == 0xAL || v == 0xDL || v in 0x20L..0xD7FFL || v in 0xE000L..0xFFFDL || v in 0x10000L..0x10FFFFL
        return if (ok) v.toInt() else null
    }

    private class Expander(val entities: Map<String, Entity>, val limit: Int) {
        /** XMLParser's flag: drop the next characters callback. */
        var dropNext = false
        private var references = 0

        /** Copies s[from, to) to out as element content, entity references in it expanded and dropped runs left out;
         *  false for something this doesn't follow. */
        fun content(s: String, from: Int, to: Int, depth: Int, out: StringBuilder): Boolean {
            var i = from
            while (i < to) {
                when (s[i]) {
                    '<' -> {
                        val end = when {
                            s.startsWith("<!--", i) -> after(s, i + 4, "-->")
                            s.startsWith("<![CDATA[", i) -> after(s, i + 9, "]]>")
                            s.startsWith("<?", i) -> after(s, i + 2, "?>")
                            s.startsWith("<!", i) -> null
                            else -> closing(s, i, to)?.also { if (depth > 0 && hasReference(s, i, it)) dropNext = true }
                        }
                        if (end == null || end > to) return false
                        out.append(s, i, end)
                        i = end
                    }
                    '&' -> {
                        val end = s.indexOf(';', i).takeIf { it in 0 until to } ?: return false
                        val ref = s.substring(i + 1, end)
                        if (ref.startsWith("#") || ref in predefined) {
                            // a characters callback of its own
                            if (dropNext) dropNext = false else out.append(s, i, end + 1)
                        } else {
                            if (++references > 1_000_000) return false // libxml2's own expansion limits: not followed
                            when (val entity = entities[ref] ?: return false) {
                                is Internal -> {
                                    dropNext = true
                                    if (depth >= 40 || !content(entity.text, 0, entity.text.length, depth + 1, out)) return false
                                }
                                External -> dropNext = true
                                Unparsed -> return false
                            }
                        }
                        i = end + 1
                    }
                    else -> {
                        var end = i
                        while (end < to && s[end] != '<' && s[end] != '&') end++
                        val keep = if (dropNext) firstCallbackEnd(s, i, end).also { dropNext = false } else i
                        out.append(s, keep, end)
                        i = end
                    }
                }
                if (out.length > limit) return false
            }
            return true
        }

        /** Whether a tag's attributes refer to a declared general entity. */
        private fun hasReference(s: String, from: Int, to: Int): Boolean {
            var i = s.indexOf('&', from)
            while (i in from until to) {
                val end = s.indexOf(';', i)
                if (end in i until to) {
                    val ref = s.substring(i + 1, end)
                    if (!ref.startsWith("#") && ref !in predefined && ref in entities) return true
                }
                i = s.indexOf('&', i + 1)
            }
            return false
        }
    }

    /** Where the first characters callback libxml2 2.9's xmlParseCharData makes for the text s[start, end) ends. ASCII
     *  text (with tabs and LFs) goes up to a CR or another character; a CR LF first goes on from its LF; from a lone
     *  CR or another character (or a CR LF followed by one) the slow path takes the rest, a callback each 300 bytes
     *  of UTF-8 (a CR LF or CR counts as one byte). */
    private fun firstCallbackEnd(s: String, start: Int, end: Int): Int {
        fun fast(c: Char) = c == '\t' || c == '\n' || (c in ' '..'\u007f' && c != '<' && c != '&')
        var from = start
        var i = start
        while (i < end && fast(s[i])) i++
        if (i > from) return i
        if (s[i] == '\r' && i + 1 < end && s[i + 1] == '\n') {
            from = i + 1
            i += 2
            if (i >= end) return end
            if (fast(s[i])) {
                while (i < end && fast(s[i])) i++
                return i
            }
        }
        var bytes = 0
        var p = from
        while (p < end) {
            if (s[p] == '\r') {
                p += if (p + 1 < end && s[p + 1] == '\n') 2 else 1
                bytes += 1
            } else {
                val cp = s.codePointAt(p)
                p += Character.charCount(cp)
                bytes += when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
            }
            if (bytes >= 300) return p
        }
        return end
    }
}
