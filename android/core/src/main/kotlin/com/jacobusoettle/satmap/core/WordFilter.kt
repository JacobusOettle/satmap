package com.jacobusoettle.satmap.core

import java.text.Normalizer

/** The on-device word filter for names, animal names, notes and report comments (docs/groups/SPEC.md §8.12). Own
 *  input is refused when `allows` is false; everything received is shown `masked`.
 *
 *  The list has one entry per line: `word` matches a whole token, `word*` a token that starts with it, `*word*` a
 *  token that contains it and the letters-only text; `#` starts a comment. Text is normalised per original code
 *  point (NFKC, full lower-casing, then `0→o 1→i 3→e 4→a 5→s 7→t @→a $→s`), so a match can be traced back to the
 *  characters it came from. Tokens are runs of letters. The iPhone core (WordFilter.swift) matches the same way,
 *  checked by the shared vectors. */
class WordFilter(list: String) {
    private val exact: Set<List<Int>>
    private val prefixes: List<IntArray>
    private val contains: List<IntArray>

    init {
        val exact = HashSet<List<Int>>()
        val prefixes = mutableListOf<IntArray>()
        val contains = mutableListOf<IntArray>()
        // Split on U+000A itself, keeping empty lines (they are skipped below).
        for (line in list.split('\n')) {
            val entry = line.trim(' ', '\t', '\r')
            if (entry.isEmpty() || entry.startsWith("#")) continue
            val values = entry.codePoints().toArray()
            val star = '*'.code
            if (values.size > 2 && values.first() == star && values.last() == star) {
                contains.add(values.copyOfRange(1, values.size - 1))
            } else if (values.size > 1 && values.last() == star) {
                prefixes.add(values.copyOfRange(0, values.size - 1))
            } else {
                exact.add(values.toList())
            }
        }
        this.exact = exact
        this.prefixes = prefixes
        this.contains = contains
    }

    /** False if any token matches an entry, or the letters-only text holds a contains entry. */
    fun allows(text: String): Boolean {
        val tokens = tokens(text)
        if (tokens.any { matches(it.text) }) return false
        val letters = letters(tokens)
        return contains.none { find(it, letters) }
    }

    /** The text with each matching token's original characters replaced by "•••" (overlapping spans merged); the whole
     *  text becomes "•••" if only the letters-only check matched. */
    fun masked(text: String): String {
        val original = text.codePoints().toArray()
        val tokens = tokens(text)
        val spans = tokens.filter { matches(it.text) }.map { it.first to it.last }.sortedBy { it.first }
        if (spans.isEmpty()) {
            val letters = letters(tokens)
            return if (contains.any { find(it, letters) }) mask else text
        }
        val merged = mutableListOf<IntArray>()
        for ((a, b) in spans) {
            val last = merged.lastOrNull()
            if (last != null && a <= last[1]) {
                last[1] = maxOf(last[1], b)
            } else {
                merged.add(intArrayOf(a, b))
            }
        }
        val out = StringBuilder()
        var position = 0
        for ((a, b) in merged.map { it[0] to it[1] }) {
            for (i in position until a) out.appendCodePoint(original[i])
            out.append(mask)
            position = b + 1
        }
        for (i in position until original.size) out.appendCodePoint(original[i])
        return out.toString()
    }

    private fun matches(token: IntArray): Boolean =
        exact.contains(token.toList()) || prefixes.any { startsWith(token, it) } || contains.any { find(it, token) }

    private class Token(val text: IntArray, val first: Int, val last: Int)

    private fun letters(tokens: List<Token>): IntArray = tokens.flatMap { it.text.asIterable() }.toIntArray()

    companion object {
        /** What masked words become. */
        const val mask = "•••"

        private fun startsWith(token: IntArray, prefix: IntArray): Boolean =
            token.size >= prefix.size && prefix.indices.all { token[it] == prefix[it] }

        private fun find(needle: IntArray, haystack: IntArray): Boolean {
            if (needle.isEmpty()) return true
            if (haystack.size < needle.size) return false
            for (start in 0..haystack.size - needle.size) {
                if (haystack[start] == needle[0] && needle.indices.all { haystack[start + it] == needle[it] }) return true
            }
            return false
        }

        /** The normalised letter runs, each with the span of original code points it came from. */
        private fun tokens(text: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var current: MutableList<Int>? = null
            var first = 0
            var last = 0
            for ((i, c) in text.codePoints().toArray().withIndex()) {
                // NFKC of the code point alone, then the full lower-case mapping (`lowercase()` uses the root locale).
                val normalised = Normalizer.normalize(String(Character.toChars(c)), Normalizer.Form.NFKC).lowercase()
                for (n in normalised.codePoints().toArray()) {
                    val d = substitute(n)
                    if (GroupText.isLetter(d)) {
                        if (current == null) {
                            current = mutableListOf()
                            first = i
                        }
                        current.add(d)
                        last = i
                    } else if (current != null) {
                        tokens.add(Token(current.toIntArray(), first, last))
                        current = null
                    }
                }
            }
            current?.let { tokens.add(Token(it.toIntArray(), first, last)) }
            return tokens
        }

        private fun substitute(c: Int): Int = when (c) {
            '0'.code -> 'o'.code
            '1'.code -> 'i'.code
            '3'.code -> 'e'.code
            '4'.code, '@'.code -> 'a'.code
            '5'.code, '$'.code -> 's'.code
            '7'.code -> 't'.code
            else -> c
        }
    }
}
