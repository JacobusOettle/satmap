import Foundation

/// The on-device word filter for names, animal names, notes and report comments (docs/groups/SPEC.md §8.12). Own
/// input is refused when `allows` is false; everything received is shown `masked`.
///
/// The list has one entry per line: `word` matches a whole token, `word*` a token that starts with it, `*word*` a
/// token that contains it and the letters-only text; `#` starts a comment. Text is normalised per original code
/// point (NFKC, full lower-casing, then `0→o 1→i 3→e 4→a 5→s 7→t @→a $→s`), so a match can be traced back to the
/// characters it came from. Tokens are runs of letters. The Android core (WordFilter.kt) matches the same way, checked
/// by the shared vectors.
struct WordFilter {
    private let exact: Set<[UInt32]>
    private let prefixes: [[UInt32]]
    private let contains: [[UInt32]]

    /// What masked words become.
    static let mask = "•••"

    init(list: String) {
        var exact = Set<[UInt32]>()
        var prefixes: [[UInt32]] = []
        var contains: [[UInt32]] = []
        // Split on U+000A itself: as Characters, "\r\n" is one and wouldn't split.
        for line in list.unicodeScalars.split(separator: "\n", omittingEmptySubsequences: false) {
            var scalars = Array(line)
            while let f = scalars.first, f == " " || f == "\t" || f == "\r" { scalars.removeFirst() }
            while let l = scalars.last, l == " " || l == "\t" || l == "\r" { scalars.removeLast() }
            guard let first = scalars.first, first != "#" else { continue }
            let values = scalars.map(\.value)
            let star = UInt32(UInt8(ascii: "*"))
            if values.count > 2, values.first == star, values.last == star {
                contains.append(Array(values.dropFirst().dropLast()))
            } else if values.count > 1, values.last == star {
                prefixes.append(Array(values.dropLast()))
            } else {
                exact.insert(values)
            }
        }
        self.exact = exact
        self.prefixes = prefixes
        self.contains = contains
    }

    /// False if any token matches an entry, or the letters-only text holds a contains entry.
    func allows(_ text: String) -> Bool {
        let tokens = Self.tokens(text)
        if tokens.contains(where: { matches($0.text) }) { return false }
        let letters = tokens.flatMap(\.text)
        return !contains.contains { Self.find($0, in: letters) }
    }

    /// The text with each matching token's original characters replaced by "•••" (overlapping spans merged); the whole
    /// text becomes "•••" if only the letters-only check matched.
    func masked(_ text: String) -> String {
        let original = Array(text.unicodeScalars)
        let tokens = Self.tokens(text)
        let spans = tokens.filter { matches($0.text) }.map { ($0.first, $0.last) }.sorted { $0.0 < $1.0 }
        if spans.isEmpty {
            let letters = tokens.flatMap(\.text)
            return contains.contains { Self.find($0, in: letters) } ? Self.mask : text
        }
        var merged: [(Int, Int)] = []
        for (a, b) in spans {
            if let last = merged.last, a <= last.1 {
                merged[merged.count - 1].1 = max(last.1, b)
            } else {
                merged.append((a, b))
            }
        }
        var out = String.UnicodeScalarView()
        var position = 0
        for (a, b) in merged {
            out.append(contentsOf: original[position..<a])
            out.append(contentsOf: Self.mask.unicodeScalars)
            position = b + 1
        }
        out.append(contentsOf: original[position...])
        return String(out)
    }

    private func matches(_ token: [UInt32]) -> Bool {
        exact.contains(token) || prefixes.contains { token.starts(with: $0) } || contains.contains { Self.find($0, in: token) }
    }

    private static func find(_ needle: [UInt32], in haystack: [UInt32]) -> Bool {
        guard !needle.isEmpty else { return true }
        guard haystack.count >= needle.count else { return false }
        for start in 0...(haystack.count - needle.count) where haystack[start] == needle[0] {
            if haystack[start..<(start + needle.count)].elementsEqual(needle) { return true }
        }
        return false
    }

    private struct Token {
        var text: [UInt32]
        /// The indexes of the original code points of its first and last letter.
        var first: Int
        var last: Int
    }

    /// The normalised letter runs, each with the span of original code points it came from.
    private static func tokens(_ text: String) -> [Token] {
        var tokens: [Token] = []
        var current: Token?
        for (i, c) in text.unicodeScalars.enumerated() {
            let normalised = String(Character(c)).precomposedStringWithCompatibilityMapping.lowercased()
            for n in normalised.unicodeScalars {
                let d = substitute(n)
                if GroupText.isLetter(d) {
                    if current == nil { current = Token(text: [], first: i, last: i) }
                    current?.text.append(d.value)
                    current?.last = i
                } else if let t = current {
                    tokens.append(t)
                    current = nil
                }
            }
        }
        if let t = current { tokens.append(t) }
        return tokens
    }

    private static func substitute(_ c: Unicode.Scalar) -> Unicode.Scalar {
        switch c {
        case "0": return "o"
        case "1": return "i"
        case "3": return "e"
        case "4", "@": return "a"
        case "5", "$": return "s"
        case "7": return "t"
        default: return c
        }
    }
}
