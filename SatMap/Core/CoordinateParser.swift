import Foundation

/// Reads a position typed or pasted in almost any common form:
/// - decimal degrees, signed or with N/S/E/W before or after ("-25.096893, 31.437121", "25.0969 S 31.4371 E");
/// - degrees and decimal minutes ("25° 05.8136' S, 31° 26.2273' E") and degrees, minutes and seconds
///   ("25°05'48.8\"S 31°26'13.6\"E", "25-05-48.8S 031-26-13.6E", "40d41m21sN", "25 05m 48.8s S", "S24.59.48.9",
///   "S24°59.48.9"), with any of the usual symbols or spaces;
/// - decimal commas ("-25,096893; 31,437121"), digits in other scripts, labels ("lat"/"lon" in either order, "X"/"Y", a
///   "Lat/Long:" heading or a table's header row, "id,lat,lon"), a photo's details ("GPS Latitude Ref : South" on a
///   line of its own, the same place again on a "GPS Position" line, Windows' "24; 59; 48.95"), both letters together
///   ("GPS (S/E): 24.99693, 31.59232"), and directions and labels in English, Afrikaans, Dutch, German, French, Spanish,
///   Italian and Portuguese ("sud", "Oeste", "ZB", "Breite"), with a lone O for east or west (see `latLon`); altitude,
///   accuracy, a heading and the like next to the position are left out ("…, 31.59231 heading W"), and so are a
///   height, distance, time or speed with its unit and the direction after it ("…, 31.59231 (3 km N of Skukuza)"), a
///   direction of travel or from a place ("moving W -24.99693, 31.59231", "S24 59 48.9 E31 35 32.3 N of camp"), a
///   wind's direction ("Wind S 10 kt"), a road's name or marker ("S3", "H4-1", "H1-3 km 14", "… km 12"), a time or
///   count written with letters ("06h45", "4x4"), a count, name, time, date or temperature before it ("3 rhino S 24°
///   59' 48.9\" E …", "WGS 84: S24 59 48.9 E31 35 32.3", "[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "38°C S24
///   59 48.9 E31 35 32.3"), and a time, date, phone number, count or temperature after it ("…, 06:45", "…, 3 males",
///   "…, 32°C");
/// - GPS (NMEA) fields or whole sentences ("2505.8136,S,03126.2273,E", "$GPGGA,…"), aviation style ("0536.22N 00011.22W")
///   and ISO 6709 ("-2505.8136+03126.2273/", "+40.6892-074.0445+545.400/");
/// - map links (Google Maps, Apple Maps, OpenStreetMap, OsmAnd, Bing, Waze, Yandex, ArcGIS, GeoHack and others with the
///   position in them, also a search typed in another form, "?q=24+59.816+S+31+35.539+E"), geo: links, Google navigation
///   links and the vCard iMessage sends;
/// - GeoJSON, KML and WKT ("POINT (31.437121 -25.096893)"), which put the longitude first, as does code that names the
///   order ("ST_MakePoint(31.437121, -25.096893)", "LngLat(…)"); labelled values in GPX, JSON or XML whatever else is
///   with them (a <time>, a name, a zoom);
/// - UTM ("36J 342401 7223411", "30 N 699568 5709428", "UTM 36 South 342402 7223412"), MGRS ("36J UT 42401 23411",
///   "36JUT42402341") and plus codes ("5G6HWC3P+6R", or a short "WC3P+6R" near `near`);
/// - a position inside other text, as in a chat message ("[12:31, 27/10/2026] Jaco: Lions on H4-1 at -25.0969, 31.4371").
/// Without letters or labels the first number is the latitude, unless only the second can be one.
/// A text that could be two different places, with nothing in it to say which, isn't read (nil), in whatever forms they are
/// written (two links, a link and a position typed next to it, a grid reference and a position, a list of them). Nor are
/// numbers that could as well be times, dates, years, counts, temperatures or distances, nor a latitude or longitude on its
/// own ("S 24° 59.815'"). Two exceptions say in the format how they were read: a lone "O" (east or west) and a directions
/// link to a place by name (the map's centre).
enum CoordinateParser {
    struct Result: Equatable {
        let point: GeoPoint
        /// The notation it was read as, to show the user.
        let format: String
    }

    static func parse(_ text: String, near: GeoPoint? = nil) -> Result? {
        let t = normalise(text)
        guard t.contains(where: \.isNumber), case let .place(r, used, resolution)? = place(in: t, near: near) else { return nil }
        // Another place in the rest of the text, in any form ("36J 342401 7223411 and -24.9961, 31.5911", a link and a
        // position typed next to it, two plus codes): it isn't clear which is meant. The same place written again is fine,
        // as closely as the less precise of the two is written, however often it is written. Two whole numbers left on
        // their own ("27/10", "12/3") are a date or a count next to it, not another place, nor are numbers each named by
        // words (see `reading`).
        var rest = t, read = used
        while true {
            let left = blanked(rest, read)
            // (What a reader read is always blanked; were it not, the same would be read again.)
            guard left != rest else { return nil }
            rest = left
            guard rest.contains(where: \.isNumber), !has(#"^[^\p{L}\d+-]*\d+[^\p{L}\d.,+-]+\d+[^\p{L}\d+-]*$"#, in: rest) else {
                return r
            }
            switch place(in: rest, near: near, beside: true) {
            case nil: return r
            case .unclear?: return nil
            case let .place(other, otherUsed, otherResolution)?:
                guard Geo.distance(other.point, r.point) < 30 + max(resolution, otherResolution) * 111_320 else { return nil }
                read = otherUsed
            }
        }
    }

    /// What a text says, as one of the readers reads it.
    private enum Found {
        /// A place, the parts of the text it was read from, and how precisely it is written there (in degrees).
        case place(Result, used: [NSRange], resolution: Double)
        /// Positions that aren't one place (two plus codes, a track): none of them is meant.
        case unclear
    }

    /// The first reader that reads the text, and what it read. `beside`: whether the text is what is left beside a place
    /// read in it before (see `reading`).
    private static func place(in t: String, near: GeoPoint?, beside: Bool = false) -> Found? {
        if let found = fromLinks(t, near: near) ?? plusCodes(t, near: near) { return found }
        // The numbers in a link that couldn't be read (a short link, a place by name) don't say where. A small "o" before a
        // word in small letters is Portuguese "the" or Spanish "or", not a direction ("…, 31.4371 o ponto de encontro").
        // (Both keep the text's length, so that what a reader read is where it is in the text.)
        let rest = replacing(#"(?<!\p{L})o(?=[ \t]+\p{Ll})"#, in: withoutLinks(t), with: " ")
        // GeoJSON, KML or WKT that doesn't say one place (a line, two placemarks) isn't read in any other way either.
        if let shaped = lonFirst(rest) { return shaped }
        let whole = [NSRange(location: 0, length: (rest as NSString).length)]
        // A text with more numbers than a position and a message around it can have (a chat log, a file) isn't read as
        // one position as a whole, only by what is in it (a pair that can only be a position, a grid reference, a label).
        let wholeText: (String) -> Found? = { numberGroups(in: $0) > 40 ? nil
            : latLon($0, near: near, beside: beside).map { .place($0.result, used: whole, resolution: $0.resolution) } }
        let readers: [(String) -> Found?] = [nmea, { iso6709($0).map { .place($0.result, used: whole, resolution: $0.resolution) } },
                                             mgrs, utm, table, labelled, wholeText, { inText($0, near: near) }]
        return readers.lazy.compactMap { $0(rest) }.first
    }

    /// How many runs of digits the text has.
    private static func numberGroups(in t: String) -> Int {
        var count = 0, inDigits = false
        for c in t.utf8 {
            let digit = c >= 48 && c <= 57
            if digit && !inDigits { count += 1 }
            inDigits = digit
        }
        return count
    }

    /// The text with the parts that were read blanked out (the same length, so that the other parts stay where they are).
    private static func blanked(_ t: String, _ parts: [NSRange]) -> String {
        let s = NSMutableString(string: t)
        for r in parts where NSMaxRange(r) <= s.length {
            s.replaceCharacters(in: r, with: String(repeating: " ", count: r.length))
        }
        return s as String
    }

    // MARK: - Normalising

    /// Signs written in more than one way, as the one the readers take.
    private static let sameSign: [Unicode.Scalar: String] = [
        "\u{2212}": "-", "\u{02D7}": "-", "\u{00A0}": " ", "\u{2009}": " ", "\u{202F}": " ", "\u{FEFF}": "", "\u{200B}": "",
        "\u{200E}": "", "\u{200F}": "", "\r": "\n", "º": "°", "˚": "°", "⁰": "°", "ᵒ": "°", "′": "'", "’": "'", "‘": "'", "`": "'",
        "´": "'", "″": "\"", "”": "\"", "“": "\"", "℃": "°C", "℉": "°F",
        // Arabic and fullwidth decimal points, commas and signs.
        "\u{066B}": ".", "\u{060C}": ",", "\u{FF0E}": ".", "\u{FF0C}": ",", "\u{FF0B}": "+", "\u{FF0F}": "/",
    ]

    private static func normalise(_ s: String) -> String {
        var t = s
        // Links come percent-encoded ("%2C", "%C2%B0", "%2B" in a plus code). A space in one ("address=Apple%20Park") is
        // kept as a +, as in a query, so that the link stays in one piece. Each run of codes on its own, so that a "%" in
        // the message ("Battery 15%") doesn't keep the link encoded.
        if t.unicodeScalars.contains("%"), has("%[0-9A-Fa-f]{2}", in: t) {
            let ns = t.replacingOccurrences(of: "%20", with: "+") as NSString
            var decoded = "", at = 0
            for m in matchRanges("(?:%[0-9A-Fa-f]{2})+", in: ns as String) {
                let run = ns.substring(with: m[0])
                decoded += ns.substring(with: NSRange(location: at, length: m[0].location - at)) + (run.removingPercentEncoding ?? run)
                at = NSMaxRange(m[0])
            }
            t = decoded + ns.substring(from: at)
        }
        // A vCard escapes its commas ("ll=-24.996931\,31.592312" in the location iMessage sends).
        if t.unicodeScalars.contains("\\") {
            t = replacing(#"(?<=\d)\\,(?=\s*[-+]?\d)"#, in: t, with: ",")
        }
        // In one pass (a long text is read as it is typed): the signs above, a new line written "\r\n" as one, two
        // apostrophes as a second mark, a dash of any kind as "-" ("‐25.0969" with a hyphen, or one that keeps the line
        // together), a degree sign read in the wrong encoding ("25Â°") as one, digits in other scripts ("٤٠", "４０") as
        // 0-9, which is what the readers and Double() take, and other numbers ("½", "²", "①") as a letter: they aren't a
        // coordinate's digits but other text.
        let scalars = Array(t.unicodeScalars)
        var out = String.UnicodeScalarView()
        for (k, u) in scalars.enumerated() {
            if u == "\r", k + 1 < scalars.count, scalars[k + 1] == "\n" { continue }
            if u == "Â", k + 1 < scalars.count, scalars[k + 1] == "°" || scalars[k + 1] == "º" { continue }
            var mapped = sameSign[u].map { Array($0.unicodeScalars) } ?? [u]
            if u.value > 127, u.properties.generalCategory == .dashPunctuation {
                mapped = ["-"]
            } else if u.value > 127, u.properties.numericType == .decimal, let v = u.properties.numericValue,
                      let ascii = Unicode.Scalar(48 + UInt32(v)) {
                mapped = [ascii]
            } else if u.value > 127, sameSign[u] == nil, u.properties.numericType != nil {
                mapped = ["Ж"]
            }
            for m in mapped {
                if m == "'", out.last == "'" {
                    out.removeLast()
                    out.append("\"")
                } else {
                    out.append(m)
                }
            }
        }
        // A photo's hemispheres on lines of their own ("GPS Latitude Ref : South", "GPSLatitudeRef: S", as exiftool shows
        // them, before or after the values): in front of the values they are for, unless those have their own letter.
        // (The runs of spaces possessive, so that a long one with no value after it isn't split every way.)
        var r = String(out)
        if r.range(of: "ref", options: .caseInsensitive) != nil {
            for (k, letter) in ["N", "S", "E", "W"].enumerated() {
                let axis = "(?:" + (k < 2 ? latWords : lonWords) + ")"
                let ref = "(?i)" + axis + #"[ \t]*+REF(?:ERENCE)?[ \t]*+[:=]?[ \t]*+(?:"# + directionWords[k] + "|" + letter
                    + ")(?![A-Z])"
                guard has(ref, in: r) else { continue }
                r = replacing("(?i)(" + axis + #"[ \t]*+[:=]?[ \t]*+)(?=\d)(?![^\n]*(?<![A-Z])[NSEW](?![A-Z]))"#,
                              in: replacing(ref, in: r, with: " "), with: "$1" + letter + " ")
            }
        }
        return r.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matches(_ pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [[String?]] {
        let ns = text as NSString
        return matchRanges(pattern, in: text, options: options).map { m in
            m.map { r in r.location == NSNotFound ? nil : ns.substring(with: r) }
        }
    }

    private static func matchRanges(_ pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [[NSRange]] {
        guard let re = patterns.regex(pattern, options) else { return [] }
        return re.matches(in: text, range: NSRange(location: 0, length: (text as NSString).length)).map { m in
            (0..<m.numberOfRanges).map { m.range(at: $0) }
        }
    }

    /// Whether the pattern is found in the text.
    private static func has(_ pattern: String, in text: String) -> Bool {
        patterns.regex(pattern, [])?.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    /// The text with each match of the pattern replaced ($1 for its first group).
    private static func replacing(_ pattern: String, in text: String, with template: String) -> String {
        guard let re = patterns.regex(pattern, []) else { return text }
        return re.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length), withTemplate: template)
    }

    /// The patterns, each compiled once: compiling one takes longer than matching it, and the text is read several times
    /// (once for each pair of numbers in a long one).
    private static let patterns = Patterns()

    private final class Patterns: @unchecked Sendable {
        private var compiled: [String: NSRegularExpression] = [:]
        private let lock = NSLock()

        func regex(_ pattern: String, _ options: NSRegularExpression.Options) -> NSRegularExpression? {
            let key = "\(options.rawValue) " + pattern
            lock.lock()
            defer { lock.unlock() }
            if let re = compiled[key] { return re }
            let re = try? NSRegularExpression(pattern: pattern, options: options)
            compiled[key] = re
            return re
        }
    }

    private static func valid(_ lat: Double, _ lon: Double) -> GeoPoint? {
        guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        return GeoPoint(lat: lat, lon: lon)
    }

    /// The size of one unit of a number's last digit: 0.01 for "-24.99", 1 for "25".
    private static func lastDigit(_ number: String?) -> Double {
        guard let n = number, let point = n.lastIndex(where: { $0 == "." || $0 == "," }) else { return 1 }
        return pow(10, -Double(n[n.index(after: point)...].prefix(while: \.isNumber).count))
    }

    /// Whether two readings are the same place, as closely as the less precise of the two is written.
    private static func samePlace(_ a: Reading, _ b: Reading) -> Bool {
        Geo.distance(a.result.point, b.result.point) < 30 + max(a.resolution, b.resolution) * 111_320
    }

    /// The text in capitals, each letter in the same number of UTF-16 units ("ß" stays as it is, not "SS"), so that a
    /// place found in it is the same place in the text.
    private static func capitals(_ t: String) -> String {
        let u = t.uppercased()
        guard u.utf16.count != t.utf16.count else { return u }
        return String(t.map { c -> Character in
            let up = String(c).uppercased()
            return up.count == 1 && up.utf16.count == String(c).utf16.count ? Character(up) : c
        })
    }

    // MARK: - Map links

    /// Each link in the text on its own: they must all be one place.
    private static func fromLinks(_ t: String, near: GeoPoint?) -> Found? {
        let ns = t as NSString
        let links = linkRanges(t)
        var read = links.compactMap { r in fromLink(ns.substring(with: r), near: near).map { (reading: $0, used: [r]) } }
        // A link with a space in it ("geo:-25.0969 31.4371", "?q=-25.0969, 31.4371"), or a part of one ("?ll=…&z=15"), is
        // read from the whole text. (Only when it has what each of them needs, which is quicker to look for in a long text.)
        // What it was read from is each run of characters with what a link has, with the number after a space that
        // completes a pair at its end, so that a different place typed next to it is still looked for.
        let parts = #"[?&][a-z_\[\]%]+=|navigation:|/maps/|![23]d|geo:|cp=|#map=|://|www\.|/@"#
        if read.isEmpty, has("(?i)" + parts, in: t), let r = fromLink(t, near: near) {
            let runs = matchRanges(#"(?i)(?<!\S)\S*?(?:"# + parts + #")\S*+(?:(?<=[\d,+])\s+[-+]?\d+(?:\.\d+)?\S*+)?"#, in: t)
            read = [(r, runs.isEmpty ? [NSRange(location: 0, length: ns.length)] : runs.map { $0[0] })]
        }
        guard let first = read.first?.reading else { return nil }
        guard read.allSatisfy({ samePlace($0.reading, first) }) else { return .unclear }
        return .place(first.result, used: read.flatMap { $0.used }, resolution: read.map { $0.reading.resolution }.max() ?? 0)
    }

    private static func fromLink(_ t: String, near: GeoPoint?) -> Reading? {
        let num = #"([-+]?\d{1,3}(?:\.\d+)?)(?!\d)"#
        // A parameter whose whole value is "lat,lon" ("lat+lon", "lat lon" once decoded), maybe after "loc:" (Google) or
        // "ll." (Waze); "google.navigation:q=" too.
        func param(_ names: String, typed: Bool = false) -> String {
            #"(?:[?&]|navigation:)(?:"# + names + #")=(?:loc:|ll\.)?\s*"# + num + #"(?:,|%2C|\+|\s|%20)+"# + num
                + #"(?!\w|\.\d)"# + (typed ? #"(?!(?:,|%2C|\+|[ \t]|%20)+(?:[NSEWnsew](?![A-Za-z])|\d+(?:\.\d+)?(?![\d.A-Za-z])))"# : "")
        }
        // The position, and how precisely it is written there.
        func find(_ pattern: String, lonFirst: Bool = false) -> (GeoPoint, Double)? {
            for m in matches(pattern, in: t, options: [.caseInsensitive]) {
                if let a = m[1].flatMap(Double.init), let b = m[2].flatMap(Double.init),
                   let p = lonFirst ? valid(b, a) : valid(a, b) { return (p, max(lastDigit(m[1]), lastDigit(m[2]))) }
            }
            return nil
        }
        // A pin, a search or a destination as it was typed ("q=24+59.816+S+31+35.539+E", "query=S24.996931,E31.592312",
        // "q=24°59'48.9"S+31°35'32.3"E"): its first two numbers are "lat,lon" only when no letter or number goes on with
        // them (there they are degrees and minutes), else the whole value is read, as a place in a link's path is.
        func typed(_ names: String) -> (GeoPoint, Double)? {
            find(param(names, typed: true)) ?? matches(#"(?:[?&]|navigation:)(?:"# + names + #")=([^&#\n]+)"#, in: t,
                                                       options: [.caseInsensitive]).first?[1].flatMap(pathPoint)
        }
        let link = { (p: (GeoPoint, Double)) -> Reading in
            (Result(point: p.0, format: t.lowercased().hasPrefix("geo:") ? "geo: link" : "map link"), p.1)
        }

        // Directions: where they go to (Waze's to=ll.…, the last stop of OpenStreetMap's route=), not where they start.
        if let p = typed("daddr|destination|to") ?? find(#"[?&]route=[^&#\s]*;"# + num + "," + num + #"(?=&|#|\s|$)"#) {
            return link(p)
        }
        if let m = matches(#"/maps/dir/([^?#]*)"#, in: t, options: [.caseInsensitive]).first, let path = m[1] {
            // "/maps/dir/<from>/<to>/@<map centre>/data=…": the last stop, from the path or else from the stops' positions
            // in the data (!1d lon !2d lat or !3d lat !4d lon each). Without either, the map's centre is somewhere along
            // the way, and the format says so.
            let stops = path.components(separatedBy: "/").prefix { !$0.hasPrefix("@") && !$0.hasPrefix("data=") }
            if let last = stops.last(where: { !$0.isEmpty }), let p = pathPoint(last) { return link(p) }
            let positions: String = "!1d" + num + "!2d" + num + "|!3d" + num + "!4d" + num
            let data = matches(positions, in: t).compactMap { m -> (GeoPoint, Double)? in
                if let lon = m[1].flatMap(Double.init), let lat = m[2].flatMap(Double.init) {
                    return valid(lat, lon).map { ($0, max(lastDigit(m[1]), lastDigit(m[2]))) }
                }
                if let lat = m[3].flatMap(Double.init), let lon = m[4].flatMap(Double.init) {
                    return valid(lat, lon).map { ($0, max(lastDigit(m[3]), lastDigit(m[4]))) }
                }
                return nil
            }
            if let p = data.last { return link(p) }
            return find("/@" + num + "," + num).map { (Result(point: $0.0, format: "map link (centre of the route)"), $0.1) }
        }
        // Yandex (ll=, pt=) and ArcGIS (center=, marker=) put the longitude first. The pin (pt=, whatshere[point]=,
        // marker=) rather than the map's centre (ll=, center=), whichever comes first in the link.
        if has(#"(?i)\b(?:yandex\.[a-z.]+|arcgis\.com)/"#, in: t),
           let p = find(param(#"pt|marker|whatshere\[point\]"#), lonFirst: true) ?? find(param("ll|center"), lonFirst: true) {
            return link(p)
        }
        // Most specific first: Google's dropped pin (!3d…!4d…) and an embedded Google map (!2d lon !3d lat).
        if let p = find("!3d" + num + "!4d" + num) { return link(p) }
        if let p = find("!2d" + num + "!3d" + num, lonFirst: true) { return link(p) }
        // The place: a pin or search (also a name with the position after an @), Bing's pushpin (sp=point.lat_lon_name),
        // OpenStreetMap's marker (mlat=, mlon=, not the map's centre in lat=, lon=) and OsmAnd's lat=…&lon=… (in either
        // order, with other parameters between them).
        let place: [String] = [#"[?&](?:q|query)=[^&#\s@]*@"# + num + #"(?:,|\s)+"# + num, #"[?&]sp=point\."# + num + "_" + num]
        if let p = typed("q|query|pin|point|loc|address") ?? place.lazy.compactMap({ find($0) }).first { return link(p) }
        func value(_ names: String) -> (Double, Double)? {
            let v = matches("[?&](?:" + names + ")=" + num, in: t, options: [.caseInsensitive]).first?[1]
            return v.flatMap(Double.init).map { ($0, lastDigit(v)) }
        }
        for (lat, lon) in [("mlat", "mlon|mlng"), ("lat|latitude", "lon|lng|long|longitude")] {
            if let a = value(lat), let b = value(lon), let p = valid(a.0, b.0) { return link((p, max(a.1, b.1))) }
        }
        // GeoHack (Wikipedia's map links): params=24_59_46_S_31_35_28_E_… or params=-24.99611_31.59111_….
        if let m = matches(#"[?&]params=([\d._]+_[NS])_([\d._]+_[EW])(?![A-Z0-9])"#, in: t, options: [.caseInsensitive]).first,
           let a = m[1], let b = m[2], let r = latLon((a + " " + b).replacingOccurrences(of: "_", with: " "), near: nil) {
            return link((r.result.point, r.resolution))
        }
        if let p = find("[?&]params=" + num + "_" + num + #"(?=_[A-Za-z]|&|#|\s|$)"#) { return link(p) }
        // "/maps/place/<lat,lon, DMS or plus code>/@<map centre>": the place, not where the map was centred.
        if let m = matches(#"/maps/(?:place|search)/([^/?#@]+)"#, in: t, options: [.caseInsensitive]).first, let part = m[1] {
            if let p = pathPoint(part) { return link(p) }
            if let r = plusCode(part, near: near) { return r }
        }
        // geo:lat,lon, unless it is geo:0,0, which Android writes when only q= says where (and q= had no position).
        let geo: String = #"geo:\s*"# + num + #"(?:\s*,\s*|\s+)"# + num
        if let m = matches(geo, in: t, options: [.caseInsensitive]).first, let a = m[1].flatMap(Double.init),
           let b = m[2].flatMap(Double.init), a != 0 || b != 0, let p = valid(a, b) {
            return link((p, max(lastDigit(m[1]), lastDigit(m[2]))))
        }
        // ll= (Apple's pin, Google's map centre), Apple's coordinate= and Street View's viewpoint=, then the map's centre
        // (a "#15/lat/lon" only inside a link, not "Ref #27/10/26" in a message), and last where directions start from.
        let rest: [String] = [param("ll"), param("coordinates?|viewpoint"), param("sll|center|near|map"), "cp=" + num + "~" + num,
                              #"#map=\d{1,2}(?:\.\d+)?/"# + num + "/" + num,
                              #"(?:://|www\.)[^\s#]*#\d{1,2}(?:\.\d+)?/"# + num + "/" + num, "/@" + num + "," + num,
                              param("saddr|source|from")]
        if let p = rest.lazy.compactMap({ find($0) }).first { return link(p) }
        // Any other link with "lat,lon" as a part of its path (MapQuest's /latlng/…, HERE's /l/…; Mapbox's static maps put
        // the longitude first) or as the whole value of a parameter, with decimals, and not part of a longer list (a
        // bounding box, a route).
        let decimal = #"([-+]?\d{1,3}\.\d+)"#
        let path: String = #"(?:://|www\.)\S*?/"# + decimal + #"\s*,\s*"# + decimal + #"(?![\d.])(?=[,/?#&]|\s|$)(?!,\s*[-+]?\d*\.\d)"#
        if let p = find(path, lonFirst: has(#"(?i)mapbox\.com/"#, in: t)) { return link(p) }
        return find(#"[?&][A-Za-z_]+="# + decimal + #"\s*,\s*"# + decimal + #"(?=&|#|\s|$)"#).map(link)
    }

    /// A position as part of a link's path: "-25.096893,31.437121", "-25.096893+31.437121" or "25°05'48.8"S+31°26'13.6"E".
    private static func pathPoint(_ part: String) -> (GeoPoint, Double)? {
        let s = part.replacingOccurrences(of: "+", with: " ")
        guard has(#"^[-\d.,;°'"\sNSEWnsew]+$"#, in: s) else { return nil }
        return latLon(s, near: nil).map { ($0.result.point, $0.resolution) }
    }

    /// Where the links are. "geo:" is a link only before a number, not in "<geo:lat>" (W3C Basic Geo) or "geo:lon=" (a
    /// photo's machine tags).
    private static func linkRanges(_ t: String) -> [NSRange] {
        // (What each has is looked for first, as that is quicker in a long text.)
        guard has(#"(?i)://|www\.|geo:|navigation:|\.[a-z]{2,}/"#, in: t) else { return [] }
        // Each starts where a word starts (the look-behind), so a long run of characters isn't tried from every place in it.
        return matchRanges(#"(?i)(?<![a-z0-9+.-])(?:[a-z][a-z0-9+.-]*+://|www\.|geo:(?=\s*[-+\d])|google\.navigation:)\S*"#
                        + #"|(?<![a-z0-9.-])(?:[a-z0-9-]++\.)++[a-z]{2,}/\S*"#, in: t).map { $0[0] }
    }

    /// The text without links (the ones `fromLink` couldn't read).
    private static func withoutLinks(_ t: String) -> String {
        blanked(t, linkRanges(t))
    }

    // MARK: - Plus codes (Open Location Code)

    private static let olcAlphabet = Array("23456789CFGHJMPQRVWX")

    private static let codePattern = #"(?<![0-9A-Z])([23456789CFGHJMPQRVWX0]{2,8})\+([23456789CFGHJMPQRVWX]{0,7})(?![0-9A-Z])"#

    private static func plusCode(_ t: String, near: GeoPoint?) -> Reading? {
        let u = t.uppercased()
        return matches(codePattern, in: u).lazy.compactMap { m in plusCode(m, in: u, near: near) }.first
    }

    /// Every plus code in the text: one place, or two codes ("Lions WC3P+6R, leopard XC5P+2Q") and none of them is meant.
    /// One that can't be read (a short code shortened against a place name) counts as another place too.
    private static func plusCodes(_ t: String, near: GeoPoint?) -> Found? {
        guard t.unicodeScalars.contains("+") else { return nil }
        let u = capitals(t), ns = u as NSString
        let found = matchRanges(codePattern, in: u).map { m in
            (code: m, read: plusCode(m.map { $0.location == NSNotFound ? nil : ns.substring(with: $0) }, in: u, near: near))
        }
        guard let first = found.lazy.compactMap({ $0.read }).first else { return nil }
        // Only what can be a code: "25+" or "22+22" (a count, a sum) isn't one.
        let codes = found.filter { $0.read != nil || ($0.code[1].length >= 4 && $0.code[2].length >= 2) }
        guard codes.allSatisfy({ $0.read.map { samePlace($0, first) } ?? false }) else { return .unclear }
        return .place(first.result, used: codes.map { $0.code[0] }, resolution: first.resolution)
    }

    /// A code, and the height of its area in degrees.
    private static func plusCode(_ m: [String?], in u: String, near: GeoPoint?) -> Reading? {
        guard let head = m[1], let tail = m[2] else { return nil }
        // (A short code is read as a full one of 8 characters before the "+".)
        let digits = 8 + tail.count
        let size = digits <= 10 ? pow(20, 2 - Double(digits / 2)) : pow(20, -3) / pow(5, Double(digits - 10))
        return plusCodePoint(m, head: head, tail: tail, in: u, near: near).map { ($0, size) }
    }

    private static func plusCodePoint(_ m: [String?], head: String, tail: String, in u: String, near: GeoPoint?) -> Result? {
        // A full code padded with zeros is an area 5 km across or more ("5G6HWC00+", "5G000000+"), not a place, and a
        // number with a plus is more likely ("25000000+ visitors").
        guard !head.contains("0") else { return nil }
        if head.count < 8 {
            // A short code: the missing first part comes from the reference point (your position). Google writes them
            // with 4 or 6 characters before the "+" and 2 or more after it, so "25+" or "22+22" (a count, a sum) isn't one.
            guard let ref = near, head.count >= 4, tail.count >= 2 else { return nil }
            // A place name after it ("WC3P+6R Skukuza") means the code was shortened against that place, which can't be
            // looked up offline. Near you the same short code is a square up to 1° from that place (at Letaba, 110 km
            // north of the Skukuza one; near Cape Town, 1,600 km away), and nothing says which, so it isn't read.
            if let r = u.range(of: m[0] ?? ""), u[r.upperBound...].contains(where: \.isLetter) { return nil }
            let padding = 8 - head.count
            guard padding % 2 == 0 else { return nil }
            let resolution = pow(20.0, 2 - Double(padding) / 2)
            let prefix = olcEncodePairs(lat: (ref.lat / resolution).rounded(.down) * resolution,
                                        lon: (ref.lon / resolution).rounded(.down) * resolution, length: padding)
            guard var p = olcDecode(prefix + head + tail) else { return nil }
            let half = resolution / 2
            if ref.lat + half < p.lat, p.lat - resolution >= -90 { p.lat -= resolution }
            else if ref.lat - half > p.lat, p.lat + resolution <= 90 { p.lat += resolution }
            if ref.lon + half < p.lon { p.lon -= resolution } else if ref.lon - half > p.lon { p.lon += resolution }
            if p.lon > 180 { p.lon -= 360 } else if p.lon < -180 { p.lon += 360 }
            return valid(p.lat, p.lon).map { Result(point: $0, format: "plus code (near you)") }
        }
        guard let p = olcDecode(head + tail) else { return nil }
        return valid(p.lat, p.lon).map { Result(point: $0, format: "plus code") }
    }

    /// The first `length` (even, up to 10) characters of a full code for a point.
    private static func olcEncodePairs(lat: Double, lon: Double, length: Int) -> String {
        var la = min(max(lat, -90), 90 - 1e-9) + 90
        var lo = lon + 180
        lo = lo.truncatingRemainder(dividingBy: 360)
        if lo < 0 { lo += 360 }
        var out = ""
        var res = 20.0
        for _ in 0..<(length / 2) {
            let a = Int(la / res), b = Int(lo / res)
            out.append(olcAlphabet[min(a, 19)])
            out.append(olcAlphabet[min(b, 19)])
            la -= Double(a) * res
            lo -= Double(b) * res
            res /= 20
        }
        return out
    }

    /// The centre of the area of a full code (separator removed).
    private static func olcDecode(_ code: String) -> GeoPoint? {
        let chars = Array(code)
        guard chars.count >= 2 else { return nil }
        var lat = -90.0, lon = -180.0
        var res = 20.0
        var i = 0
        var latSize = 20.0, lonSize = 20.0
        while i + 1 < chars.count && i < 10 {
            guard let a = olcAlphabet.firstIndex(of: chars[i]), let b = olcAlphabet.firstIndex(of: chars[i + 1]) else { return nil }
            lat += Double(a) * res
            lon += Double(b) * res
            latSize = res
            lonSize = res
            res /= 20
            i += 2
        }
        if i < chars.count && i < 10 { return nil } // an odd character in the pairs
        // Grid refinement: 5 rows by 4 columns per character.
        var rowSize = latSize, colSize = lonSize
        while i < chars.count {
            guard let v = olcAlphabet.firstIndex(of: chars[i]) else { return nil }
            rowSize /= 5
            colSize /= 4
            lat += Double(v / 4) * rowSize
            lon += Double(v % 4) * colSize
            latSize = rowSize
            lonSize = colSize
            i += 1
        }
        return GeoPoint(lat: lat + latSize / 2, lon: lon + lonSize / 2)
    }

    // MARK: - GeoJSON, KML and WKT, NMEA sentences, ISO 6709

    /// Formats that always put the longitude first: GeoJSON ("coordinates": [lon, lat, alt], keys quoted or not, nested for
    /// a MultiPoint), KML (<coordinates>lon,lat,alt, <gx:coord>lon lat alt), WKT ("POINT (lon lat)", "SRID=4326;POINT Z
    /// (lon lat alt)", "MULTIPOINT ((lon lat))") and code that names the order ("ST_MakePoint(lon, lat)" in PostGIS,
    /// Mapbox's "LngLat(lon, lat)", OpenLayers' "fromLonLat([lon, lat])"). Two different places in it (two placemarks,
    /// a line) aren't read (unclear), and nil when the text is in none of these formats.
    private static func lonFirst(_ t: String) -> Found? {
        let num = #"([-+]?\d+(?:\.\d+)?)"#, lead = #"(?<![\d.])"# + num
        // Where the positions are (the first group), and how each one is written in there. WKT's POINT in any case, but
        // not after a word, as "Meeting point (-25.0969 31.4371)" isn't WKT. GeoJSON also when it was copied without
        // its closing bracket ("coordinates": [\n 31.4371,\n -25.0969). (A position starts where a number starts, so that
        // a long run of digits isn't tried from every digit in it.)
        let formats: [(area: String, position: String, name: String)] = [
            (#"["']?\bcoordinates["']?\s*:\s*(\[[\[\]\s\d.,+-]*(?:\]|$))"#, #"\[\s*"# + num + #"\s*,\s*"# + num + #"[^\[\]]*(?:\]|$)"#,
             "GeoJSON"),
            (#"<coordinates>([^<]*)"#, lead + #"\s*,\s*"# + num + #"(?:\s*,\s*[-+]?\d+(?:\.\d+)?)?"#, "KML"),
            (#"<gx:coord>([^<]*)"#, lead + #"\s+"# + num, "KML"),
            (#"(?i)(?<![A-Z0-9_])(?<![A-Z][ \t]{1,3})(?:MULTI)?POINT\s*(?:ZM|Z|M)?\s*(\([\s\d.,+()-]*\))"#,
             lead + #"\s+"# + num + #"(?:\s+[-+]?\d+(?:\.\d+)?){0,2}"#, "WKT"),
            (#"(?i:\bST_(?:Make)?Point|(?:LNG|LON|LONG)LAT\w*)\s*[(\[=:]\s*[(\[]?\s*("# + num + #"\s*,\s*"# + num + ")",
             lead + #"\s*,\s*"# + num, "decimal degrees"),
        ]
        let ns = t as NSString
        for (area, position, name) in formats {
            let areas = matchRanges(area, in: t)
            var resolution = 0.0
            let points: [GeoPoint?] = areas.filter { $0[1].location != NSNotFound }.flatMap { a in
                matches(position, in: ns.substring(with: a[1])).map { m -> GeoPoint? in
                    guard let lon = m[1].flatMap(Double.init), let lat = m[2].flatMap(Double.init) else { return nil }
                    resolution = max(resolution, lastDigit(m[1]), lastDigit(m[2]))
                    return valid(lat, lon)
                }
            }
            guard let first = points.first else { continue }
            guard let p = first, points.allSatisfy({ $0.map { Geo.distance($0, p) < 30 } ?? false }) else { return .unclear }
            // "coordinates: [-25.0969, 31.4371]" on its own, with the key not in quotes, no "type" and no nesting, may
            // as well be a person's or another tool's lat, lon: when both orders are positions, it can't be told which.
            if name == "GeoJSON", valid(p.lon, p.lat) != nil,
               !has(#"["']coordinates["']|\btype["']?\s*:|\[\s*\["#, in: t) {
                return .unclear
            }
            // A KML's <LookAt> or <Camera> is where the view looks from, not another place.
            let camera = name == "KML" ? matchRanges(#"(?is)<(LookAt|Camera)\b.*?</\1>"#, in: t).map { $0[0] } : []
            return .place(Result(point: p, format: name), used: areas.map { $0[0] } + camera, resolution: resolution)
        }
        return nil
    }

    /// GPS (NMEA) fields, alone or in whole sentences ($GPGGA, $GPRMC, $GPGLL…): ddmm.mmmm,N/S,dddmm.mmmm,E/W. Several
    /// (a log) must all be one place.
    private static func nmea(_ t: String) -> Found? {
        let ns = t as NSString
        let found = matchRanges(#"(?<![\d.])(\d{2})(\d{2}(?:\.\d+)?),\s*([NS]),\s*(\d{3})(\d{2}(?:\.\d+)?),\s*([EW])(?![A-Z])"#,
                                in: t, options: [.caseInsensitive])
        let fixes = found.map { r -> GeoPoint? in
            let m = r.map { ns.substring(with: $0) }
            guard let latD = Double(m[1]), let latM = Double(m[2]), latM < 60,
                  let lonD = Double(m[4]), let lonM = Double(m[5]), lonM < 60 else { return nil }
            let lat = (latD + latM / 60) * (m[3].uppercased() == "S" ? -1 : 1)
            let lon = (lonD + lonM / 60) * (m[6].uppercased() == "W" ? -1 : 1)
            return valid(lat, lon)
        }
        guard let first = fixes.first else { return nil }
        guard let p = first, fixes.allSatisfy({ $0.map { Geo.distance($0, p) < 30 } ?? false }) else { return .unclear }
        let minutes = found.flatMap { r in [r[2], r[5]].map { lastDigit(ns.substring(with: $0)) } }
        return .place(Result(point: p, format: "GPS (NMEA)"), used: found.map { $0[0] }, resolution: (minutes.max() ?? 0) / 60)
    }

    /// ISO 6709, as in photo and video metadata and the time zone database: "+40.6892-074.0445+545.400/",
    /// "-2505.8136+03126.2273/", "-0117+03649". The latitude has 2 digits of degrees and the longitude 3, then 2 of
    /// minutes and 2 of seconds if there are more; an altitude and "CRS…" after them are left out.
    private static func iso6709(_ t: String) -> Reading? {
        let lat = "([+-])([0-9]{2})([0-9]{2})?([0-9]{2})?([.][0-9]+)?", lon = "([+-])([0-9]{3})([0-9]{2})?([0-9]{2})?([.][0-9]+)?"
        let pattern: String = "^" + lat + lon + #"(?:[+-][0-9]+(?:\.[0-9]+)?)?(?:CRS[A-Z0-9_:]+)?/?$"#
        guard let m = matches(pattern, in: t, options: [.caseInsensitive]).first else { return nil }
        // The value, and the size of one unit of its last digit in degrees.
        func value(_ i: Int) -> (Double, Double)? {
            var fields = m[(i + 1)...(i + 3)].compactMap { $0.flatMap(Double.init) } // degrees, minutes, seconds
            guard !fields.isEmpty else { return nil }
            fields[fields.count - 1] += m[i + 4].flatMap { Double("0" + $0) } ?? 0
            guard fields.dropFirst().allSatisfy({ $0 < 60 }) else { return nil }
            let v = fields.enumerated().reduce(0) { $0 + $1.element / pow(60, Double($1.offset)) }
            return (m[i] == "-" ? -v : v, lastDigit(m[i + 4] ?? "") / pow(60, Double(fields.count - 1)))
        }
        guard let la = value(1), let lo = value(6), let p = valid(la.0, lo.0) else { return nil }
        return (Result(point: p, format: "ISO 6709"), max(la.1, lo.1))
    }

    // MARK: - UTM and MGRS

    /// Latitude band letters from 80° S, 8° each (X is 12°).
    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")

    private static func bandRange(_ band: Character) -> ClosedRange<Double>? {
        guard let i = bands.firstIndex(of: band) else { return nil }
        let bottom = -80 + Double(i) * 8
        return bottom...(band == "X" ? 84 : bottom + 8)
    }

    /// WGS84 UTM to latitude and longitude.
    static func utmToLatLon(zone: Int, north: Bool, easting: Double, northing: Double) -> GeoPoint? {
        guard (1...60).contains(zone) else { return nil }
        let a = 6_378_137.0, f = 1 / 298.257223563, k0 = 0.9996
        let e2 = f * (2 - f), ep2 = e2 / (1 - e2)
        let x = easting - 500_000
        let y = north ? northing : northing - 10_000_000
        let mu = y / k0 / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))
        let e1 = (1 - (1 - e2).squareRoot()) / (1 + (1 - e2).squareRoot())
        let phi1 = mu + (3 * e1 / 2 - 27 * pow(e1, 3) / 32) * sin(2 * mu)
            + (21 * e1 * e1 / 16 - 55 * pow(e1, 4) / 32) * sin(4 * mu)
            + (151 * pow(e1, 3) / 96) * sin(6 * mu) + (1097 * pow(e1, 4) / 512) * sin(8 * mu)
        let s = sin(phi1), c = cos(phi1), tn = tan(phi1)
        let n1 = a / (1 - e2 * s * s).squareRoot()
        let t1 = tn * tn, c1 = ep2 * c * c
        let r1 = a * (1 - e2) / pow(1 - e2 * s * s, 1.5)
        let d = x / (n1 * k0)
        let lat = phi1 - (n1 * tn / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * pow(d, 4) / 24
            + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * pow(d, 6) / 720)
        let lon0 = Double(zone - 1) * 6 - 180 + 3
        let lon = lon0 * .pi / 180 + (d - (1 + 2 * t1 + c1) * pow(d, 3) / 6
            + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * pow(d, 5) / 120) / c
        return valid(lat * 180 / .pi, lon * 180 / .pi)
    }

    /// The easting and northing of a UTM grid reference: 5 to 8 digits, not glued to letters other than an "E" or "N"
    /// label or an "m" ("E342401", "342401mE"; "W252525S" isn't one).
    private static let gridNumber = #"(?<![\d.A-DF-MO-Z])(\d{5,8}(?:\.\d+)?)(?![\dA-DF-LO-Z])"#

    /// A UTM grid reference: in a list, the first one that reads (the others are read after it, to be the same place).
    private static func utm(_ t: String) -> Found? {
        let u = capitals(t), ns = u as NSString
        let numbers = matchRanges(gridNumber, in: u)
        guard !numbers.isEmpty else { return nil }
        let zones = matchRanges(#"(?<![\d.])(\d{1,2})\s*(NORTH|SOUTH|NOORD|SUID)(?![A-Z])|(?<![\d.])(\d{1,2})\s?([C-HJ-NP-X])(?![A-Z])"#,
                                in: u)
        for number in numbers {
            if let found = utm(ns, easting: number[0].location, zones: zones) { return found }
        }
        return nil
    }

    /// The grid reference whose easting (or northing) starts at `firstNumber`, after its zone (one of `zones`).
    private static func utm(_ ns: NSString, easting firstNumber: Int, zones: [[NSRange]]) -> Found? {
        // The zone and a band letter, or the hemisphere in words ("UTM 36 South …"), right before the easting and
        // northing (or their labels), so that "Waypoint 12 N: 36J …" or the road in "R40 N to 36J …" isn't the zone:
        // it ends in what may be between them (spaces, ",:;=", an "E" or "N" label), which is found from the easting back.
        var gap = firstNumber
        func isLetter(at k: Int) -> Bool { Unicode.Scalar(ns.character(at: k)).map { Character($0).isLetter } ?? true }
        func skipSeparators() {
            while gap > 0, let c = Unicode.Scalar(ns.character(at: gap - 1)),
                  Character(c).isWhitespace || ",:;=".unicodeScalars.contains(c) {
                gap -= 1
            }
        }
        skipSeparators()
        var eastLabelled = false
        for label in ["EASTING", "NORTHING", "E", "N"] where gap >= label.utf16.count
            && ns.substring(with: NSRange(location: gap - label.utf16.count, length: label.utf16.count)) == label
            && (gap == label.utf16.count || !isLetter(at: gap - label.utf16.count - 1)) {
            gap -= label.utf16.count
            eastLabelled = label.hasPrefix("E")
            skipSeparators()
            break
        }
        // Within 10 km north of the equator the northing has fewer digits, and its label may come first ("37N N 1500 E
        // 285000"). (The runs of spaces possessive, so that a long one after a label with no number isn't split every way.)
        var shortFirst: [NSRange]?
        let from = max(0, gap - 100)
        if eastLabelled, let m = matchRanges(#"(?<![\dA-Z])N(?:ORTHING)?\s*+[:=]?\s*+(\d{1,4}(?:\.\d+)?)[\s,;]*$"#,
                                             in: ns.substring(with: NSRange(location: from, length: gap - from))).first {
            shortFirst = m.map { NSRange(location: $0.location + from, length: $0.length) }
            gap = m[0].location + from
            skipSeparators()
        }
        guard let zb = zones.last(where: { (gap...firstNumber).contains(NSMaxRange($0[0])) }) else { return nil }
        let spelled = zb[1].location != NSNotFound
        guard let zone = Int(ns.substring(with: spelled ? zb[1] : zb[3])),
              let letter = ns.substring(with: spelled ? zb[2] : zb[4]).first else { return nil }
        // The rest after it, so that the "N" of "30 N 699568 5709428" isn't taken for a northing label below, up to the
        // next zone: one grid reference of a list ("36J 342401 7223411\n36J 352401 7233411"), and the others are read after
        // it, to be the same place or else not clear.
        let start = NSMaxRange(zb[0]), end = zones.first { $0[0].location >= start }?[0].location ?? ns.length
        let rest = ns.substring(with: NSRange(location: start, length: end - start))
        var big = matchRanges(gridNumber, in: rest)
        // That short northing only north of the equator, and only right after the easting ("37N 285000 1500", "37N 285000
        // E 1500 N") or labelled before it.
        if big.count == 1, letter == "N" {
            if let m = shortFirst {
                big.insert(m.map { NSRange(location: $0.location - start, length: $0.length) }, at: 0)
            } else if let short = matchRanges(#"^\s?(?:M?E(?:ASTING)?)?[\s,;]*(?:N(?:ORTHING)?\s*[:=]?\s*)?(\d{1,4}(?:\.\d+)?)(?![\d.])"#,
                                              in: (rest as NSString).substring(from: NSMaxRange(big[0][0]))).first {
                big.append(short.map { NSRange(location: $0.location + NSMaxRange(big[0][0]), length: $0.length) })
            }
        }
        guard big.count == 2, let e0 = Double((rest as NSString).substring(with: big[0][1])),
              let n0 = Double((rest as NSString).substring(with: big[1][1])) else { return nil }
        let used = [NSRange(location: zb[0].location, length: start + NSMaxRange(big[1][0]) - zb[0].location)]
        // (In metres: 1 m is about 0.00001°.)
        let resolution = big.map { lastDigit((rest as NSString).substring(with: $0[1])) }.max().map { $0 / 111_320 } ?? 0
        func found(_ p: GeoPoint) -> Found { .place(Result(point: p, format: "UTM"), used: used, resolution: resolution) }
        // Easting first, unless labelled the other way round or only the first can be a northing.
        var e = e0, n = n0
        // "N 7223411 E 342401": labels, not the band letter right after the zone number.
        let northLabel = matchRanges(#"(?<![\dA-Z])N(?:ORTHING)?\s*+[:=]?\s*+\d"#, in: rest).first?[0].location
        let eastLabel = matchRanges(#"(?<![\dA-Z])E(?:ASTING)?\s*+[:=]?\s*+\d"#, in: rest).first?[0].location
        let northingFirst = northLabel.map { n in eastLabel.map { n < $0 } ?? true } ?? false
        if northingFirst || (e >= 1_000_000 && n < 1_000_000) { swap(&e, &n) }
        guard (100_000...900_000).contains(e), (0...10_000_000).contains(n) else { return nil }
        if spelled {
            return utmToLatLon(zone: zone, north: letter == "N", easting: e, northing: n).map(found)
        }
        // The letter is a latitude band; "N"/"S" can also mean the hemisphere (used by some tools).
        if let range = bandRange(letter), let p = utmToLatLon(zone: zone, north: letter >= "N", easting: e, northing: n),
           (range.lowerBound - 1...range.upperBound + 1).contains(p.lat) {
            return found(p)
        }
        if letter == "N" || letter == "S", let p = utmToLatLon(zone: zone, north: letter == "N", easting: e, northing: n) {
            return found(p)
        }
        return nil
    }

    private static func mgrs(_ t: String) -> Found? {
        // Anywhere in the text ("MGRS: 36J UT 42401 23411", "36J UT 42401 23411 (MGRS)") that has no other numbers. The
        // zone number and band letter are written together, as every tool writes them, and the letters all in capitals or
        // all in small letters, so "Arrive 7 Dec 25" or "4 Mar 26" isn't one. The digits are the easting and northing (in
        // metres, maybe with an "m"), in two groups or one run split in half ("36JUT42402341"). Several (a list) must all
        // be one place.
        let pattern = #"(?<![A-Z0-9])(\d{1,2})([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*(\d{1,10})M?(?:\s+(\d{1,5})M?)?(?![A-Z0-9])"#
        let found = matchRanges(pattern, in: t, options: [.caseInsensitive]), ns = t as NSString
        guard !found.isEmpty, !blanked(t, found.map { $0[0] }).contains(where: \.isNumber) else { return nil }
        let read = found.compactMap { m in mgrs(m.map { $0.location == NSNotFound ? nil : ns.substring(with: $0) }) }
        guard read.count == found.count, let first = read.first else { return nil }
        guard read.allSatisfy({ samePlace($0, first) }) else { return .unclear }
        return .place(first.result, used: found.map { $0[0] }, resolution: read.map(\.resolution).max() ?? 0)
    }

    /// One MGRS grid reference, from its parts as `mgrs` matches them.
    private static func mgrs(_ m: [String?]) -> Reading? {
        let letters = m[2...4].map { $0 ?? "" }.joined()
        let square = Array(letters.uppercased())
        guard letters == letters.uppercased() || letters == letters.lowercased(), square.count == 3,
              let zone = m[1].flatMap(Int.init), (1...60).contains(zone), var es = m[5] else { return nil }
        let band = square[0], col = square[1], row = square[2]
        var ns = m[6] ?? ""
        if ns.isEmpty {
            guard es.count % 2 == 0 else { return nil }
            ns = String(es.suffix(es.count / 2))
            es = String(es.prefix(es.count / 2))
        }
        guard es.count == ns.count, es.count <= 5, let ev = Double(es), let nv = Double(ns) else { return nil }
        let scale = pow(10, Double(5 - es.count))
        // 100 km squares: column letters repeat every 3 zones, row letters every 2 (offset by 5 in even zones).
        let set = (zone - 1) % 3
        let colLetters = [Array("ABCDEFGH"), Array("JKLMNPQR"), Array("STUVWXYZ")][set]
        guard let ci = colLetters.firstIndex(of: col) else { return nil }
        var rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")
        if zone % 2 == 0 { rowLetters = Array(rowLetters[5...] + rowLetters[..<5]) }
        guard let ri = rowLetters.firstIndex(of: row), let range = bandRange(band) else { return nil }
        let easting = Double(ci + 1) * 100_000 + ev * scale + scale / 2
        let north = band >= "N"
        let base = Double(ri) * 100_000 + nv * scale + scale / 2
        // The northing repeats every 2,000 km: take the one inside the latitude band.
        for k in 0..<6 {
            let northing = base + Double(k) * 2_000_000
            if let p = utmToLatLon(zone: zone, north: north, easting: easting, northing: northing),
               (range.lowerBound - 0.5...range.upperBound + 0.5).contains(p.lat) {
                return (Result(point: p, format: "MGRS"), scale / 111_320)
            }
        }
        return nil
    }

    // MARK: - Labelled values

    /// Values each named by a label right before them, whatever else is in the text: GPX (<trkpt lat="…" lon="…"> with its
    /// <ele> and <time>), JSON ({"name": "Camp 14", "lat": …, "lng": …, "zoom": 12}), XML (<geo:lat>…</geo:lat>) and
    /// "Breite: 52,52 Länge: 13,405". Only plain values: one with minutes, seconds or a letter after it, or (unless in
    /// quotes) a direction or unit in words or brackets ("25 deg 5' 48.81\" S", "25.0969 South", "25.0969 (S)", "120 cm"),
    /// is read by `latLon`, as are whole degrees with more fields after them ("24; 59; 48.95", as Windows shows a photo's
    /// position). A number after a value with decimals isn't its minutes, nor is a road's marker after it its unit ("Lat
    /// -24.99693 Lon 31.59231 06:45", "… km 12"). Several (a track) must all be one place.
    private static func labelled(_ t: String) -> Found? {
        let u = capitals(t), ns = u as NSString
        let value = hasDecimalPoint(u) ? #"([-+]?\d+(?:\.\d+)?)"# : #"([-+]?\d+(?:,\d+)?)"#
        // (A degree, minute or second in words also with its direction right after it, "25.096893 degS"; not "de", a word.)
        let unit = (directionWords + [degreeWords, minuteWords, secondWords, lengthWords, "D"]).joined(separator: "|")
            + "|(?:" + [degreeWords, minuteWords, secondWords].joined(separator: "|") + ")[NSEW]"
        // (The runs of spaces possessive, so that a long one after a label with no value isn't split every way.)
        func values(_ names: String) -> [(value: Double, range: NSRange, unit: Double)] {
            matchRanges(#"(?<![A-Z])(?:"# + names + #")(?![A-Z])[ \t]*+(["']?)[ \t]*+[:=>]?[ \t]*+(["']?)[ \t]*+"# + value
                            + #"(?!\d)\2(?![ \t]*(?:[°'"]|[NSEWOZ](?![A-Z])|[.,]\d))(?:(?<=[.,]\d{1,30}["']?)|(?![ \t]*;?[ \t]*\d))"#
                            + #"(?:(?<=["'])|(?=[ \t]*KM[ \t]*-?[ \t]*\d)|(?![ \t]*(?:(?:"# + unit
                            + #")(?!\p{L})|\(\s*[NSEWOZ]\s*\))))"#, in: u)
                .compactMap { m in
                    let v = ns.substring(with: m[3])
                    return Double(v.replacingOccurrences(of: ",", with: ".")).map { ($0, m[0], lastDigit(v)) }
                }
        }
        let lats = values(latWords), lons = values(lonWords)
        guard !lats.isEmpty, lats.count == lons.count else { return nil }
        let points = zip(lats, lons).compactMap { valid($0.value, $1.value) }
        guard points.count == lats.count else { return nil }
        guard points.allSatisfy({ Geo.distance($0, points[0]) < 30 }) else { return .unclear }
        return .place(Result(point: points[0], format: "decimal degrees"), used: (lats + lons).map { $0.range },
                      resolution: (lats + lons).map { $0.unit }.max() ?? 0)
    }

    /// A table whose header row names the latitude and longitude columns ("id,lat,lon", "Name,Count,Lat,Lon", rows copied
    /// from a sheet with their header), whatever is in the other columns (an id, a count, a name): the values in those two
    /// columns of the rows under it, each with as many columns. Several rows must all be one place.
    private static func table(_ t: String) -> Found? {
        let u = capitals(t), ns = u as NSString
        guard u.contains("\n") else { return nil }
        let lines = matchRanges(#"[^\n]+"#, in: u).map { $0[0] }
        func columns(_ line: NSRange, _ delimiter: String) -> [String] {
            ns.substring(with: line).components(separatedBy: delimiter)
                .map { $0.trimmingCharacters(in: CharacterSet.whitespaces.union(CharacterSet(charactersIn: "\"'"))) }
        }
        let label = "(?<![A-Z])(?:" + latWords + ")(?![A-Z])"
        guard has(label, in: u) else { return nil }
        for (k, header) in lines.enumerated().dropLast() where has(label, in: ns.substring(with: header)) {
            for delimiter in ["\t", ",", ";", "|"] {
                let names = columns(header, delimiter)
                let lat = names.indices.filter { has("^(?:" + latWords + ")$", in: names[$0]) }
                let lon = names.indices.filter { has("^(?:" + lonWords + ")$", in: names[$0]) }
                guard lat.count == 1, lon.count == 1 else { continue }
                var points: [GeoPoint] = [], used = [header], resolution = 0.0
                for row in lines[(k + 1)...] {
                    let values = columns(row, delimiter)
                    guard values.count == names.count else { break }
                    // (A Markdown table's line under its header: "|---|---|".)
                    if values.allSatisfy({ $0.allSatisfy { "-:".contains($0) } }) { continue }
                    let a = values[lat[0]], b = values[lon[0]]
                    guard let la = Double(a.replacingOccurrences(of: ",", with: ".")),
                          let lo = Double(b.replacingOccurrences(of: ",", with: ".")), let p = valid(la, lo) else { return nil }
                    points.append(p)
                    used.append(row)
                    resolution = max(resolution, lastDigit(a), lastDigit(b))
                }
                guard let first = points.first else { continue }
                guard points.allSatisfy({ Geo.distance($0, first) < 30 }) else { return .unclear }
                return .place(Result(point: first, format: "decimal degrees"), used: used, resolution: resolution)
            }
        }
        return nil
    }

    // MARK: - Latitude and longitude

    private enum Axis { case lat, lon }

    private enum Token {
        /// A number: its value, its digits before and after the point, and whether a minus sign was before it.
        case number(value: Double, intDigits: Int, places: Int, negative: Bool)
        case degree, minute, second
        /// N, S, E or W, and whether a number is written right after it ("S28").
        case hemisphere(Character, glued: Bool)
        /// A latitude's and a longitude's letter written together before or after the numbers ("GPS (S/E): 24.99693,
        /// 31.59232", "…, 31.59232 (S, E)"): the halves' letters, in their order.
        case tag(Character, Character)
        case separator
        /// A slash right between two numbers ("27/10", "3/4", "-25.0969/31.4371"): a separator, or a date's or a fraction's,
        /// which a half with its letter after it doesn't start right after (see `backStart`).
        case slash
        /// Other text between the numbers: words, a name, a date, a time written with letters ("06h45"), or a colon after
        /// a number ("km 14: …"); whether it has letters.
        case text(word: Bool)
        /// A dash or colon between the fields of one half ("25-05-48.8", "25:05:48.8"), or of a time ("12:30").
        case joiner(colon: Bool)
        /// "lat", "lon", or (nil) altitude, accuracy and the like.
        case label(Axis?)
    }

    /// One half of a position, as `component` reads it.
    private struct Half {
        /// With its sign.
        var value: Double
        /// From its letter or label, if it has one.
        var axis: Axis?
        /// 0 decimal degrees, 1 decimal minutes, 2 seconds, 3 GPS (NMEA).
        var kind: Int
        var numbers: Int
        /// Whether it can be a time: fields joined by colons, the last one whole, without a letter or label ("05:30",
        /// "12:30:45", "+02:00"; not "25:05:48 S").
        var time: Bool
        /// Whether something says where it starts: a letter or label before it, or a degree or minus sign on its first
        /// number.
        var startMarked: Bool
        /// Whether it can only be a coordinate: it has a label, a minute or second mark, 3 decimal places or more, or a
        /// letter in front or after more than a whole number ("S 25", "25.0969 S", "25° S"). (A degree sign alone, or a
        /// degree in words, is also a temperature or an angle: "38°C", "25 grade"; a letter after a whole number a
        /// distance and its direction: "Lions 5 N of Satara".)
        var strong: Bool
        /// Whether its letter is written right before its first number ("S28 25.0969"), as a road's name is.
        var gluedLetter: Bool
        /// Whether a minus sign is before its first number.
        var signed: Bool
        /// The size, in degrees, of one unit of its last digit.
        var resolution: Double
    }

    /// A position read from text, and how precisely it is written there (in degrees, the coarser of its two halves).
    private typealias Reading = (result: Result, resolution: Double)

    /// Symbols the words are replaced with before reading: a latitude, longitude and other label, and "and" between two
    /// numbers.
    private static let latSymbol: Character = "\u{24D0}", lonSymbol: Character = "\u{24DE}",
                       otherSymbol: Character = "\u{24E9}", andSymbol: Character = "\u{24D3}"

    /// Labels in English, Afrikaans, Dutch, German, Spanish, Italian, French and Portuguese.
    private static let latWords = "LATITUDE|LATITUD|LATITUDINE|LAT|BREEDTEGRAAD|BREEDTE|BREITENGRAD|BREITE",
                       lonWords = "LONGITUDE|LONGITUD|LONGITUDINE|LONG|LON|LNG|LENGTEGRAAD|LENGTE|LÄNGENGRAD|LÄNGE|LAENGE"
    /// Directions in the same languages (Dutch ZB, OL: zuiderbreedte, oosterlengte), north, south, east and west; a lone O
    /// is left for `latLon`.
    private static let directionWords = ["NORTH|NTH|NOORD|NB|NORD|NORTE", "SOUTH|STH|SUID|ZUID|ZB|Z|SÜD|SUED|SUD|SUR|SUL",
                                         "EAST|OOS|OOST|OL|OST|EST|ESTE|LESTE", "WEST|WES|WL|OUEST|OESTE|OVEST"]
    /// Units: degrees, minutes and seconds in words, and lengths.
    private static let degreeWords = "DEGREES|DEGREE|DEG|GRADEN|GRADE|GRAAD|GRADOS|GRADI|GRAUS|GRAD",
                       minuteWords = "MINUTES|MINUTE|MINUTOS|MINUTEN|MINUTI|MINS|MIN",
                       secondWords = "SECONDS|SECOND|SEGUNDOS|SEKUNDEN|SECONDES|SECONDEN|SECONDI|SEKONDES|SECS|SEC",
                       lengthWords = "MM|CM|M|KMS|KM|KILOMETERS?|KILOMETRES?|METERS?|METRES?|FT|FEET"

    /// Pairs of numbers, with their letters, labels and units. `language` is the text whose words may say which way a
    /// lone O is meant, when `t` is only a part of it.
    private static func latLon(_ t: String, near: GeoPoint?, language: String? = nil, beside: Bool = false) -> Reading? {
        // "51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W" (Wikipedia): the same place written in more than one way.
        // The parts that read on their own must agree as closely as the least precise of them is written (Wikipedia
        // gives "31°08′E" as "31.133°E", 34 m from it), and the most precise one is taken. A part with a lone O is read
        // the way that agrees ("46°38′10″O / -46.6361" is west), the likelier way if both do. Two parts with one letter
        // each are the halves of one position ("S25 05.8136 / E31 26.2273"). (The " / " only from the start of a run of
        // spaces, so that a long run isn't tried from every place in it.)
        let pieces = replacing(#"(?<!\s)\s+/\s+"#, in: t, with: "\u{1}")
            .components(separatedBy: "\u{1}")
        let language = language ?? t
        let oneEach = pieces.count == 2 && pieces.allSatisfy { tokens(Array(prepared($0)), commaDecimal: false)?.letters.count == 1 }
        let options = (2...4).contains(pieces.count) && !oneEach
            ? pieces.map { latLonOnce($0, near: near, language: language, beside: beside) }.filter { !$0.isEmpty } : []
        guard options.count > 1 else { return latLonOnce(t, near: near, language: language, beside: beside).first }
        // Each way of taking one reading of each part, those with fewer second choices first.
        var ways: [[Int]] = [[]]
        for o in options {
            ways = ways.flatMap { w in o.indices.map { w + [$0] } }
        }
        for way in ways.sorted(by: { $0.reduce(0, +) < $1.reduce(0, +) }) {
            let read = zip(options, way).map { $0[$1] }
            guard let best = read.min(by: { $0.resolution < $1.resolution }) else { continue }
            let tolerance = 30 + (read.map(\.resolution).max() ?? 0) * 111_320
            if read.allSatisfy({ Geo.distance($0.result.point, best.result.point) < tolerance }) { return best }
        }
        return nil
    }

    /// The readings of a text, the likelier first: two when it has a lone O (east or west), else one or none.
    private static func latLonOnce(_ t: String, near: GeoPoint?, language: String, beside: Bool) -> [Reading] {
        // Decimal commas only when there are no decimal points; if that reading fails, commas separate.
        let commaDecimal = !hasDecimalPoint(t) && has(#"\d,\d"#, in: t)
        let r = latLon(t, commaDecimal: commaDecimal, near: near, language: language, beside: beside)
        return r.isEmpty && commaDecimal ? latLon(t, commaDecimal: false, near: near, language: language, beside: beside) : r
    }

    /// Whether a number in the text has a decimal point: not a full stop at the end of a sentence ("31,59."), nor the
    /// points of a date or version ("27.10.2026", "2.5.1").
    private static func hasDecimalPoint(_ t: String) -> Bool {
        has(#"(?<![\d.])\d+\.\d+(?!\d|\.\d)"#, in: t)
    }

    private static func latLon(_ text: String, commaDecimal: Bool, near: GeoPoint?, language: String, beside: Bool) -> [Reading] {
        let s = prepared(text)
        // A lone O is east in Afrikaans, Dutch and German (Oos, Oost, Ost) but west in French, Spanish, Italian and
        // Portuguese (Ouest, Oeste, Ovest), and all of them write decimal commas. What decides, in this order:
        // - another word in the text from only one of the two groups ("Z", "Süd", "Breite", "Koordinaten": east; "Sur",
        //   "sud", "Latitud", "Coordenadas", "Estamos": west);
        // - else your position: the reading nearer to you. That is mostly the west one where O is written for west and
        //   the east one where it is written for east, as Spain, Portugal and Latin America lie west of Greenwich and
        //   Germany, the Netherlands and southern Africa east of it (France and Italy need one of their words);
        // - else east, as this app's users write it in Afrikaans.
        // The format says which way it was read.
        let lone = #"(?<!\p{L})O(?![\p{L}'])"# // not the O of "O'Brien", "Posição" or "año"
        guard has(lone, in: s) else {
            return read(s, commaDecimal: commaDecimal, beside: beside).map { [$0] } ?? []
        }
        // (Glued to its number as the O was: "O31 35 32.3" is "E31 35 32.3", a letter in front.)
        func readAs(_ letter: String) -> Reading? {
            read(replacing(lone, in: replacing(lone + #"(?=\d)"#, in: s, with: " \(letter)"), with: " \(letter) "),
                 commaDecimal: commaDecimal, beside: beside)
        }
        let east = readAs("E"), west = readAs("W")
        // Read the same either way, it isn't a direction (Portuguese "O ponto fica em …": "the").
        if let e = east, let w = west, e.result == w.result { return [e] }
        var isWest = east == nil
        if let e = east, let w = west {
            let u = language.uppercased()
            func uses(_ words: String) -> Bool { has(#"\b(?:"# + words + #")\b"#, in: u) }
            let eastWords = uses("SUID|NOORD|ZUID|ZB|NB|Z|SÜD|SUED|BREEDTE(?:GRAAD)?|LENGTE(?:GRAAD)?|BREITE(?:NGRAD)?"
                                + "|LÄNGE(?:NGRAD)?|LAENGE|KOORDINATEN?|KOÖRDINATES?|STANDORT|LIGGING|POSISIE|POSITIE|LOCATIE"
                                + "|GRADEN|GRAAD|UND|WIR|SIND|IST|BEI|IM|DER|MIT|AUF|ONS|NAAR|WIJ|ZIJN|BIJ|HET|EEN")
            let westWords = uses("SUR|SUL|SUD|NORTE|LATITUD|LONGITUD|LATITUDINE|LONGITUDINE|COORDENADAS|COORDONN[ÉE]ES"
                                + "|UBICACI[ÓO]N|LOCALIZA(?:ÇÃO|CAO|CI[ÓO]N)|POSI(?:ÇÃO|CAO|CI[ÓO]N|ZIONE)|DIRECCI[ÓO]N"
                                + "|ENDEREÇO|ESTAMOS|ESTOY|ESTOU|AQU[ÍI]|ICI|NOUS|SOMMES|SIAMO|QUI|PUNTO|PONTO|GRADOS|GRAUS"
                                + "|GRADI|EL|LOS|LAS|DEL|LE|LA|LES|DU|DES|IL|ET|CON|POR|PARA|AVEC|POUR|UNA|UMA|UNE|EM|NÃO")
            if eastWords != westWords {
                isWest = westWords
            } else if let ref = near {
                isWest = Geo.distance(ref, w.result.point) < Geo.distance(ref, e.result.point)
            }
        }
        func tagged(_ r: Reading?, west: Bool) -> Reading? {
            r.map { (Result(point: $0.result.point, format: $0.result.format + (west ? " (O = west)" : " (O = east)")),
                     $0.resolution) }
        }
        return [tagged(isWest ? west : east, west: isWest), tagged(isWest ? east : west, west: !isWest)].compactMap { $0 }
    }

    /// Upper case, with labels, directions and units as single symbols, and without the words that aren't part of the
    /// position.
    private static func prepared(_ text: String) -> String {
        var s = " " + text.uppercased() + " "
        // "Secs" and "mins" are plurals, or the unit with south right after it: south when a unit in the text has its
        // direction right after it ("24°59min48.95secS, 31°35min32.32secE").
        if has(#"\d[ \t]?(?:SECONDS|SEC|MINUTES|MIN)[NEW](?![A-Z])"#, in: s) {
            s = replacing(#"(\d[ \t]?(?:SEC|MIN))S(?![A-Z])"#, in: s, with: "$1 S")
        }
        // An altitude's, a speed's or the like's label before its value, when that is left out with it.
        let otherLabel = "(?:\(otherSymbol)[ \\t:=]*+)?"
        let spacedFront = #"(?<![A-Z\d.])(?<!\d[ \t])([NSEW])[ \t](\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)(?!\d|\.\d)"#,
            spacedAfter = #"(?<![\d.])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)[ \t]([NSEW])(?![A-Z\d]|[ \t]+\d{1,3}\.\d)"#
        let words: [(String, String)] = [
            // Degrees, minutes and seconds written with points, with the letter next to them ("S24.59.48.9", "24.59.48.9S",
            // "S 24.59.48.9", "24.59.48.9 S"), or with a degree sign and seconds with decimals ("S24°59.48.9"): not a date
            // or a version number, nor decimal minutes ("24°59.48'"); a full stop after them ends the sentence. A letter with
            // a space on each side goes with the one that has no other value on its other side, and then with the other ("S
            // 24.59.48.9 E 31.35.32.3").
            (#"(?<![A-Z\d.])([NSEW])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)(?!\d|\.\d)"#, "$1$2°$3'$4\""),
            (#"(?<![\d.])(\d{1,3})\.([0-5]\d)\.([0-5]\d(?:\.\d+)?)([NSEW])(?![A-Z])"#, "$1°$2'$3\"$4"),
            (spacedFront, "$1$2°$3'$4\""), (spacedAfter, "$1°$2'$3\"$4"), (spacedFront, "$1$2°$3'$4\""), (spacedAfter, "$1°$2'$3\"$4"),
            (#"(?<![\d.])(\d{1,3})[ \t]?°[ \t]?([0-5]\d)\.([0-5]\d\.\d+)(?!\d|\.\d|')"#, "$1°$2'$3\""),
            // Quotes around a value, as in JSON, CSV and GPX ("lat":"-25.0969", lat="-25.0969"): not minutes or seconds.
            (#"(?<=[\s=:,;\[({])(["'])\s*([-+]?\d+(?:[.,]\d+)?)\s*\1(?=[\s,;:)}\]/>])"#, " $2 "),
            // Names with digits in them ("CLLocationCoordinate2D"): not numbers.
            (#"(?<![A-Z0-9])[A-Z]{2,}\d[A-Z0-9_]*"#, " "),
            // Words that only say a position follows ("GPS:", "Location:").
            (#"\b(GPS|POSITION|POS|LOCATION|LOC|COORDINATES|COORDINATE|COORDS|KOÖRDINATES|KOÖRDINATE|KOORDINATEN|COORDENADAS|COORDONNÉES)\b"#,
             " "),
            (#"\b("# + latWords + #")\b"#, " \(latSymbol) "), (#"\b("# + lonWords + #")\b"#, " \(lonSymbol) "),
            // Two labels joined by "and" are a heading, as "Lat/Long:" is ("Lat & Long:", "Breite und Länge:", "Latitud y
            // longitud:").
            ("([\(latSymbol)\(lonSymbol)])[ \\t]*(?:&|\\+|AND|UND|EN|Y|E|ET)[ \\t]*(?=[\(latSymbol)\(lonSymbol)])", "$1 "),
            // Länge and Lengte, Breite and Breedte also mean length and width: before a value in a unit of length they are
            // words, not labels ("Länge: 120 cm, Breite: 60 cm").
            ("[\(latSymbol)\(lonSymbol)](?=[ \\t\"'=:]*[-+]?\\d+(?:[.,]\\d+)?[ \\t]*(?:\(lengthWords))(?![A-Z]))", " SIZE "),
            // Degrees, minutes and seconds with semicolons between them after a label, as Windows shows a photo's position
            // ("Latitude 24; 59; 48.95").
            ("([\(latSymbol)\(lonSymbol)][ \\t:=]*(?:[NSEW][ \\t]+)?)(\\d{1,3});[ \\t]*([0-5]?\\d);[ \\t]*([0-5]?\\d(?:[.,]\\d+)?)(?![\\d;])",
             "$1$2°$3'$4\""),
            (#"(?<![A-Z])(ALTITUDE|ALT|ELEVATION|ELEV|ELE|HEIGHT|HOOGTE|ACCURACY|ACC|HDOP|SPEED|HEADING|BEARING|COURSE|ZOOM)(?![A-Z])"#,
             " \(otherSymbol) "),
            // A height above sea level, with its label ("GPS Altitude : 285.4 m Above Sea Level", as exiftool shows it). (Not
            // from inside a number, so that a long run of digits isn't tried from every digit in it.)
            (otherLabel + #"(?<![\d.,])[-+]?\d+(?:[.,]\d+)?\s*M\s+(?:ABOVE|BELOW)\s+SEA\s+LEVEL"#, " | "),
        ]
        let directions: [(String, String)] = zip(directionWords, ["N", "S", "E", "W"]).map { (#"\b("# + $0 + #")\b"#, " \($1) ") }
        // Units in words and letters: the marks first, so that the minutes and seconds in words after them are read as
        // theirs ("25d 05 min 48.8 sec S", "25°05m 48.8 sec S"), not as a time left out below.
        let units: [(String, String)] = [
            (#"(?:\b|(?<=\d))("# + degreeWords + #")(?![A-Z])"#, "°"),
            // Also with the direction right after it ("25.096893 degS").
            (#"(?<=\d|\d[ \t])DEG(?:REES?)?(?=[NSEW](?![A-Z]))"#, "°"),
            // A temperature ("38°C", "32 deg C", "℃"): never part of a position.
            (#"(?<![\d.,])[-+]?\d+(?:[.,]\d+)?[ \t]*°[ \t]*[CF](?![A-Z])"#, " | "),
            (#"(\d)\s*D(?=\s*(?:\d|[NSEW](?![A-Z])|[,;]|$))"#, "$1°"),
            // S for seconds, right after the number and before the direction: after minutes written with an M
            // ("40d41m21sN", "25°05m48.8s S") or with the direction right after it ("40°41'21sN", below the minutes in
            // words). Not in "25°05'48.8S E 031°26'13.6", where it is south and the E starts the other half (see `read`).
            (#"(\d\s*M\s*\d+(?:[.,]\d+)?)S(?=\s*[NSEW](?![A-Z]))"#, "$1\""),
            // M for minutes only after degrees ("25d 5.8136m S", "25 05m S", "25 5m 48.8s S"); elsewhere it is metres.
            // (The letter of the next half may be written right before its number: "S24 59.816m E31 35.539m".)
            (#"(°\s*\d+(?:[.,]\d+)?)\s*M(?=\s*(?:\d|[NSEW](?![A-Z])|[,;]|$))"#, "$1'"),
            (#"(?<![\d.,])(\d{1,3}[ \t]+[0-5]?\d(?:[.,]\d+)?)[ \t]*M(?=\s*(?:\d|[NSEW](?![A-Z])|[,;]|$))"#, "$1'"),
            // Minutes and seconds in words only after degrees and minutes ("25 deg 5 min 48.81 sec S", "25 05 48 sec S",
            // "25°05min48.8s"), also with the direction right after them ("…48.95secE"); else a time, left out below
            // ("10 min S of Satara").
            (#"(°\s*\d+(?:[.,]\d+)?|(?<![\d.,])\d{1,3}[ \t]+[0-5]?\d(?:[.,]\d+)?)\s*(?:"# + minuteWords
                + #")(?=[NSEW]?(?![A-Z]))"#, "$1'"),
            (#"('\s*\d+(?:[.,]\d+)?|(?<![\d.,])\d{1,3}[ \t]+[0-5]?\d[ \t]+[0-5]?\d(?:[.,]\d+)?)\s*(?:"# + secondWords
                + #")(?=[NSEW]?(?![A-Z]))"#, "$1\""),
            (#"('\s*\d+(?:[.,]\d+)?)[ \t]?S(?=[NSEW](?![A-Z]))"#, "$1\""),
            // A distance with its direction and no unit ("Lions 5 N of Satara", "3 S/W of Satara", "W 1 van die brug", "2.5 S
            // of Satara"): with two decimals only when not right after another half ("2.25 N of Satara", not "24.99 S 31.59
            // E from Pieter"), and not the minutes of a half ("31 35.5 E from Pieter").
            (#"(?<![\p{L}\d.,])(?<!\d[ \t]{1,3})(?:\d+(?:[.,]\d)?|(?<![\d°'"][ \t]{0,3}[NSEWO][ \t,;]{0,3})\d+[.,]\d\d)[ \t]*[NSEWO]"#
                + #"(?:[ \t]*[/,][ \t]*[NSEWO])?[ \t]+(?:OF|VAN|FROM|VANAF)\b"#, " | "),
            (#"(?<![\p{L}\d.,])[NSEWO][ \t]*\d+(?:[.,]\d)?[ \t]+(?:OF|VAN|FROM|VANAF)\b"#, " | "),
            // A distance, a height, a time or a speed ("Drive S 25 km", "…, 270 m", "S 30° W, distance 120 m", "10 min S
            // of Satara", "Wind S 10 kt, later E 15 kt"), or a range of them ("15-20 kts"): not part of the position. It
            // ends the position before it, so that the direction after it ("…, 31.59231 (3 km N of Skukuza)") isn't taken
            // for that position's letter. Not the end of a road's name ("H1-3 km 14", "S100 km 7"), nor a number before a
            // road's marker ("…, 31.59231 km 12": the marker is after "km").
            // An altitude's or an accuracy's label goes with it ("Altitude: 285.4 m\nLatitude: …").
            (otherLabel + #"(?<![\p{L}\d.,]|[\p{L}\d]-)[-+]?\d+(?:[.,]\d+)?(?:-\d+(?:[.,]\d+)?)?\s*(?!KM[ \t]*-?[ \t]*\d)(?:"#
                + lengthWords + #"|MI|MILES?|NM|YDS?|YARDS?|MYL|HOURS?|HRS?|UUR|MINUTES?|MINS?|SECONDS?|SECS?|SEKONDES?"#
                + #"|KTS?|KN|KNOTS?|KNOPE|BFT|MPH|KPH|KMH)(?![A-Z\d'"°])"#, " | "),
            // The direction a wind blows from ("Wind S 24, tomorrow E 31"): not a position's, which a number follows that
            // only a coordinate has ("…out of the wind S24 59.816 E31 35.539", "Wind: S 24.99693 E 31.59231").
            (#"(?<![A-Z])(WIND(?:RIGTING|RICHTUNG)?|BREEZE)[ \t]*:?[ \t]*(?:FROM[ \t]+)?[NSEW]{1,3}(?![A-Z])"#
                + #"(?![ \t]*\d+(?:[.,]\d{3}|[ \t]*[°'"]|[ \t]+\d))"#, " $1 "),
            // "And" between two numbers ("Lions at -25.0969 and 31.4371") separates them, as a comma does; it is still
            // a word ("25° and 31°" aren't a position).
            (#"(?<=[\d°'"])[ \t]+(?:AND|EN|UND|ET|&)[ \t]+(?=[-+]?\d)"#, " \(andSymbol) "),
        ]
        for (p, r) in words + directions + units {
            s = replacing(p, in: s, with: r)
        }
        // A heading's or a bearing's direction, as or after its value ("heading W", "Heading 180° S"): not a position's
        // letter, unless a number follows it. A heading that is only a direction goes with it when a position follows
        // after a stop or a colon (a number with another or with a letter after it), so that it doesn't label the
        // position's first number ("Heading W: 24 59.816 S …", "Heading W. -25.0969, 31.4371"; but "heading W: 270°",
        // "course S: 3 males").
        let position = #"[ \t]*+[.:,;][ \t.:,;-]*+\d++(?:[.,]\d++)?+[ \t]*+°?+(?:[ \t,;/]*+[-+]?\d|[ \t]*+[NSEW](?![A-Z]))"#
        s = replacing("\(otherSymbol)[ \\t:]*[NSEW]{1,3}(?![A-Z])(?=" + position + ")", in: s, with: " ")
        s = replacing("(\(otherSymbol)[ \\t:]*(?:\\d+(?:[.,]\\d+)?[ \\t]*°?[ \\t]*)?)[NSEW]{1,3}(?![A-Z])(?![ \\t]*[-+.]?\\d)", in: s,
                      with: "$1")
        // A label only before a value or another label, or at the end of a line (a header row): "Long day!" isn't one.
        let symbols = "\(latSymbol)\(lonSymbol)\(otherSymbol)"
        s = replacing("[\(symbols)](?![ \\t\"'=:>(\\[{/|\\\\,;]*(?:[-+.\\d\(symbols)]|\\n|$)|\\s+[-+.]?\\d)", in: s, with: " ")
        // X and Y as GIS writes them in degrees: X the longitude, Y the latitude. Only together, so a lone "x" isn't one.
        let xy = [(#"(?<![A-Z0-9_.])X(?![A-Z0-9_])"#, lonSymbol), (#"(?<![A-Z0-9_.])Y(?![A-Z0-9_])"#, latSymbol)]
        if xy.allSatisfy({ has($0.0, in: s) }) {
            for (p, symbol) in xy {
                s = replacing(p, in: s, with: " \(symbol) ")
            }
        }
        return s
    }

    private static func isWordLetter(_ c: Character?) -> Bool {
        c.map { $0.isLetter && $0 != latSymbol && $0 != lonSymbol && $0 != otherSymbol && $0 != andSymbol } ?? false
    }

    /// Reads a prepared text. An S right after seconds ("25°05'48.815s", "25 05 48.8s") is south, or else, when the text
    /// isn't a position that way and has letters or a minus sign that say the hemispheres, their mark ("S 25°05'48.815s
    /// E 31°26'13.636s", "-25°05'48.815s, 31°26'13.636s", "25°05'48s S, 31°26'13s E").
    private static func read(_ s: String, commaDecimal: Bool, beside: Bool) -> Reading? {
        if let r = readLetters(s, commaDecimal: commaDecimal, beside: beside) { return r }
        let marked = replacing(#"((?:'[ \t]*|(?<![\d.,])\d{1,3}[ \t]+[0-5]?\d[ \t]+)[0-5]?\d(?:[.,]\d+)?)S(?![A-Z])"#, in: s,
                               with: "$1\"")
        guard marked != s, has(#"(?<![A-Z])[NSEW](?![A-Z])|(?<![\d.])-[ \t]*\d"#, in: marked) else { return nil }
        return readLetters(marked, commaDecimal: commaDecimal, beside: beside)
    }

    /// With three letters, one of the outer two isn't the position's: the first letter of a road's name, written right
    /// before a whole number ("S3 24 59.816 S 31 35.539 E", "Lions S25 07.178 E31 55.057 S28 3 males"), or a direction, of
    /// travel before a position with its letters after the numbers ("Lions walking W 24 59.816 S 31 35.539 E") or from a
    /// place after one with its letters in front ("S24 59 48.9 E31 35 32.3 N of camp"). Each is tried as such. A reading
    /// with the latitude's letter first, as positions are written, is taken rather than one with the longitude's first
    /// (only one of them can have it); a direction is only taken so ("S 25°05'48.8\" E 31°26'13.6s" isn't 25° E, 31° S
    /// after a direction). Else the readings of names must be one place.
    /// With four on more than one line, the same place may be written twice, as exiftool shows a photo's position ("GPS
    /// Latitude : 24 deg 59' 48.95\" S", "GPS Longitude : …", "GPS Position : … S, … E"): it is read on each side of the
    /// line break between the second and the third letter, and the two must be one place.
    private static func readLetters(_ s: String, commaDecimal: Bool, beside: Bool) -> Reading? {
        let chars = Array(s)
        guard let all = tokens(chars, commaDecimal: commaDecimal) else { return nil }
        let letters = all.letters
        if letters.count == 4, let cut = chars[letters[1]..<letters[2]].lastIndex(of: "\n") {
            guard let a = readLetters(String(chars[..<cut]), commaDecimal: commaDecimal, beside: beside),
                  let b = readLetters(String(chars[cut...]), commaDecimal: commaDecimal, beside: beside), samePlace(a, b) else {
                return nil
            }
            return a.resolution <= b.resolution ? a : b
        }
        guard letters.count == 3 else {
            return letters.count > 3 ? nil : reading(all.tokens, words: all.words, beside: beside)
        }
        // Whether the letter at `i` is written right after a number or its mark ("59.816 S", "48.9\"S"), or right before a
        // number ("S24", "S 24", "S: -24").
        func afterNumber(_ i: Int) -> Bool {
            var k = i - 1
            while k >= 0, " \t(".contains(chars[k]) { k -= 1 }
            return k >= 0 && (chars[k].isNumber || "°'\"".contains(chars[k]))
        }
        func beforeNumber(_ i: Int) -> Bool {
            var k = i + 1
            while k < chars.count, " \t:)".contains(chars[k]) { k += 1 }
            return k < chars.count && (chars[k].isNumber || (k + 1 < chars.count && "-+.".contains(chars[k]) && chars[k + 1].isNumber))
        }
        // (A direction is a word of its own, not glued to a number or mark: "03.4°S", "W24".)
        let (first, middle, last) = (letters[0], letters[1], letters[2])
        let outer = [(first, afterNumber(middle) && afterNumber(last) && " \t\n".contains(chars[first + 1]), (middle, last)),
                     (last, beforeNumber(first) && beforeNumber(middle) && !beforeNumber(last) && " \t\n(".contains(chars[last - 1]),
                      (first, middle))]
        var found: [Reading] = []
        for (i, direction, rest) in outer where direction || nameShaped(chars, i) {
            guard let t = tokens(chars, commaDecimal: commaDecimal, names: [i]),
                  let r = reading(t.tokens, words: t.words, beside: beside) else {
                continue
            }
            if "NS".contains(chars[rest.0]) && "EW".contains(chars[rest.1]) { return r }
            if nameShaped(chars, i) { found.append(r) }
        }
        guard let f = found.first, found.allSatisfy({ samePlace($0, f) }) else { return nil }
        return f
    }

    /// The numbers, letters, labels, marks and other text of a prepared text, where its N, S, E and W letters are, and
    /// whether it has other words. The letters at `names` start a name. Nil when a number has a grid zone's letter after
    /// it ("36J 342401 …", "36JUT4240123411"): `utm`, `mgrs` and `inText` read that text, and the other place is looked for.
    private static func tokens(_ chars: [Character], commaDecimal: Bool, names: Set<Int> = [])
        -> (tokens: [Token], letters: [Int], words: Bool)? {
        var tokens: [Token] = [], letters: [Int] = []
        var i = 0
        var pendingNegative = false
        // Letters that aren't a direction, label or unit: other words around the numbers.
        var words = false
        func text(word: Bool = true) {
            words = words || word
            if case let .text(before)? = tokens.last {
                tokens[tokens.count - 1] = .text(word: before || word)
            } else {
                tokens.append(.text(word: word))
            }
        }
        while i < chars.count {
            let c = chars[i]
            if c.isNumber || (c == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
                // Glued to a letter other than N/S/E/W: a name ("H4-1", "R40", a plus code), other text, not a number of
                // the position.
                if i > 0, isWordLetter(chars[i - 1]), !"NSEW".contains(chars[i - 1]) {
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                // A date ("27/10/2026", "2026/10/27", "27/10/26", "2026-10-27"): other text, not numbers of the position.
                if let end = dateEnd(chars, i) {
                    i = end
                    text(word: false)
                    pendingNegative = false
                    continue
                }
                // A road's marker after its name and "km" ("H4-1 km 3 24 59.816 S …"), not after a distance ("5 km 24 …"):
                // other text.
                if case .text? = tokens.last, !(tokens.count > 1 && isNumber(tokens[tokens.count - 2])), afterKM(chars, i) {
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                let start = i
                var digits = "", intDigits = 0, places = 0, seenPoint = false
                while i < chars.count {
                    let d = chars[i]
                    if d.isNumber {
                        digits.append(d)
                        if seenPoint { places += 1 } else { intDigits += 1 }
                    } else if !seenPoint && (d == "." || (commaDecimal && d == ",")) && i + 1 < chars.count && chars[i + 1].isNumber {
                        digits.append(".")
                        seenPoint = true
                    } else {
                        break
                    }
                    i += 1
                }
                // A second point ("27.10.2026", "2026.10.27", "1.2.3"): a date or version, other text, not two numbers.
                if seenPoint, i + 1 < chars.count, chars[i] == ".", chars[i + 1].isNumber {
                    while i < chars.count, chars[i].isNumber || (chars[i] == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
                        i += 1
                    }
                    text()
                    pendingNegative = false
                    continue
                }
                guard let v = Double(digits) else { return nil }
                if i < chars.count, isWordLetter(chars[i]), !"NSEW".contains(chars[i]) {
                    if gridZone(chars, start) { return nil }
                    // Else a time, a count or a unit written with letters ("06h45", "9:15am", "4x4", "2x", "3rd", "5L"):
                    // other text, with the hour before a colon.
                    while case .joiner(colon: true)? = tokens.last {
                        tokens.removeLast()
                        if case .number? = tokens.last { tokens.removeLast() }
                    }
                    i = afterName(chars, i)
                    text()
                    pendingNegative = false
                    continue
                }
                tokens.append(.number(value: v, intDigits: intDigits, places: places, negative: pendingNegative))
                pendingNegative = false
                continue
            }
            switch c {
            // Between whole degrees or minutes and 2 digits that can be minutes or seconds ("25-05-48.8S",
            // "031-26-13.6E") a dash joins the fields of one half, and right after a half that ends in its letter
            // ("25.0969 S - 31.4371 E") it separates the halves, unless it is written as a minus sign, after a space and
            // right before a number ("…E -25.0969, 31.4371": the same place again, in decimals). So it does with a space
            // on each side after a half with a minus sign before it, whose writer puts a minus sign right before its
            // number ("-25.0969 - 31.4371", "-24°59.815' - 31°35.539'"; not times, "-25:05 - 31:26", which may be a
            // range). Between degrees and a number it joins a range ("18°-31°", "18° - 31°"), unless it is written as a
            // minus sign ("Bearing 270° -25.0969, 31.4371"). Elsewhere it is a minus ("40-74.0445", "\"-25.0969\"").
            case "-":
                var back = i - 1, ahead = i + 1
                while back > 0, chars[back] == " " || chars[back] == "\t" { back -= 1 }
                while ahead < chars.count, chars[ahead] == " " || chars[ahead] == "\t" { ahead += 1 }
                let minus = i > 0 && chars[i - 1].isWhitespace && i + 1 < chars.count
                    && (chars[i + 1].isNumber || chars[i + 1] == ".")
                if fieldDash(chars, i) || (!minus && back > 0 && chars[back] == "°" && chars[back - 1].isNumber
                                           && ahead < chars.count && chars[ahead].isNumber) {
                    tokens.append(.joiner(colon: false))
                    break
                }
                let spaced = i > 0 && " \t".contains(chars[i - 1]) && i + 1 < chars.count && " \t".contains(chars[i + 1])
                if spaced, let last = tokens.last, isMark(last) || decimalPlaces(last) > 0, signedRun(tokens) {
                    tokens.append(.separator)
                } else if !minus, tokens.count > 1, case .hemisphere = tokens[tokens.count - 1],
                   isNumber(tokens[tokens.count - 2]) || isMark(tokens[tokens.count - 2]) {
                    tokens.append(.separator)
                } else {
                    pendingNegative = true
                }
            case "+": pendingNegative = false
            // After a whole number and before another a colon joins them too: a time ("12:30") or fields ("25:05:48.8 S"),
            // not two halves. After a number and before anything else, it ends what is before it ("km 14: 24 22.872 S").
            case ":":
                if i > 0, chars[i - 1].isNumber, i + 1 < chars.count, chars[i + 1].isNumber,
                   case .number(_, _, 0, _)? = tokens.last {
                    tokens.append(.joiner(colon: true))
                } else if i > 0, chars[i - 1].isNumber {
                    text(word: false)
                }
            case "°": tokens.append(.degree)
            case "'": tokens.append(.minute)
            case "\"": tokens.append(.second)
            case ",", ";", "|", "\n", "\t": tokens.append(.separator)
            case "/":
                tokens.append(i > 0 && chars[i - 1].isNumber && i + 1 < chars.count && chars[i + 1].isNumber ? .slash : .separator)
            // A number sign before a count ("#3"): other text.
            case "#": text(word: false)
            case latSymbol: tokens.append(.label(.lat))
            case lonSymbol: tokens.append(.label(.lon))
            case otherSymbol: tokens.append(.label(nil))
            // A word that separates two numbers as a comma does, among other words.
            case andSymbol:
                tokens.append(.separator)
                words = true
            case "N", "S", "E", "W":
                // A road named by a letter and a number ("S3", "S100", "N4"): other text.
                if names.contains(i) || isName(chars, i) {
                    i = afterName(chars, i + 1)
                    text()
                    pendingNegative = false
                    continue
                }
                if let end = tagEnd(chars, i) {
                    tokens.append(.tag(c, chars[end - 1]))
                    i = end
                    pendingNegative = false
                    continue
                }
                if isHemisphere(chars, i) {
                    tokens.append(.hemisphere(c, glued: i + 1 < chars.count && chars[i + 1].isNumber))
                    letters.append(i)
                } else {
                    text()
                }
            default:
                if isWordLetter(c) { text() }
            }
            if c != "-" && c != "+" && c != " " { pendingNegative = false }
            i += 1
        }
        return (tokens, letters, words)
    }

    /// Where a date that starts at `i` ends ("27/10/2026", "2026/10/27", "27/10/26", "2026-10-27"), if one does.
    private static func dateEnd(_ chars: [Character], _ i: Int) -> Int? {
        let s = String(chars[i..<min(chars.count, i + 14)])
        let date = #"^(?:\d{1,4}/\d{1,2}/\d{2,4}|\d{4}-\d{1,2}-\d{1,2}|\d{1,2}-\d{1,2}-\d{4})(?![\d/-]|\.\d)"#
        return matchRanges(date, in: s).first.map { i + $0[0].length }
    }

    /// Whether the number at `i` is right after "km" after a road's name ("H4-1 km 3", "S100 Km-12"): its marker.
    private static func afterKM(_ chars: [Character], _ i: Int) -> Bool {
        var k = i - 1
        while k >= 0, " \t-".contains(chars[k]) { k -= 1 }
        guard k > 0, chars[k] == "M", chars[k - 1] == "K" else { return false }
        k -= 2
        while k >= 0, chars[k] == " " || chars[k] == "\t" { k -= 1 }
        return k >= 0 && chars[k].isNumber
    }

    /// Where a latitude's and a longitude's letter written together ("S/E", "S, E") that start at `i` end, when they are
    /// before the numbers or after them, not between them ("24.99693 S/E 31.59232": each half's own letter). With a colon
    /// after them, before the numbers, they are before them whatever is before them ("06:45 GPS (S/E): …").
    private static func tagEnd(_ chars: [Character], _ i: Int) -> Int? {
        guard "NS".contains(chars[i]), !isWordLetter(i > 0 ? chars[i - 1] : nil) else { return nil }
        var k = i + 1
        while k < chars.count, chars[k] == " " || chars[k] == "\t" { k += 1 }
        guard k < chars.count, chars[k] == "/" || chars[k] == "," else { return nil }
        k += 1
        while k < chars.count, chars[k] == " " || chars[k] == "\t" { k += 1 }
        guard k < chars.count, "EW".contains(chars[k]), !isWordLetter(k + 1 < chars.count ? chars[k + 1] : nil) else { return nil }
        var next = k + 1
        while next < chars.count, " \t)".contains(chars[next]) { next += 1 }
        let before = isHemisphere(chars, k), colon = next < chars.count && chars[next] == ":"
        guard isHemisphere(chars, i) != before || (before && colon) else { return nil }
        return k + 1
    }

    /// The latitude's and the longitude's letter written together in the text (see `Token.tag`), if it has them.
    private static func tag(in t: String) -> (Character, Character)? {
        for tok in tokens(Array(prepared(t)), commaDecimal: false)?.tokens ?? [] {
            if case let .tag(a, b) = tok { return (a, b) }
        }
        return nil
    }

    /// Whether the numbers at the end of the tokens, with their marks and joiners, start with a minus sign.
    private static func signedRun(_ tokens: [Token]) -> Bool {
        var k = tokens.count
        while k > 0, isNumber(tokens[k - 1]) || isMark(tokens[k - 1]) || isJoiner(tokens[k - 1]) { k -= 1 }
        return k < tokens.count && isNegative(tokens[k])
    }

    /// Whether the number at `i` is a UTM zone with its band letter, before an easting or an MGRS square ("36J 342401",
    /// "36J UT 42401", "36JUT4240123411"), not a time or a count written with letters ("06h45", "4x4", "5L water", "12x").
    private static func gridZone(_ chars: [Character], _ i: Int) -> Bool {
        has(#"^\d{1,2}[C-HJ-NP-X](?:\s*[A-HJ-NP-Z][A-HJ-NP-V]\s*\d|[\s,:;]+\d)"#, in: String(chars[i..<min(chars.count, i + 12)]))
    }

    /// A position from the tokens of a text, with other words among them or not. `beside`: whether the text is what is
    /// left beside a place read in it before.
    private static func reading(_ all: [Token], words: Bool, beside: Bool = false) -> Reading? {
        var tokens = all, words = words
        // Two or more labels before the first number are a heading ("Lat/Long:", a spreadsheet's header row): they give
        // the order of the values after them, they don't label the first one.
        var headingFirst: Axis?
        let firstNumber = tokens.firstIndex(where: isNumber) ?? tokens.count
        let heading = tokens[..<firstNumber].compactMap { tok -> Axis?? in
            if case let .label(a) = tok { return a }
            return nil
        }
        if heading.count >= 2 {
            tokens = tokens.enumerated().filter { $0.offset >= firstNumber || !isLabel($0.element) }.map(\.element)
            let numbers = tokens.filter(isNumber)
            if heading.contains(where: { $0 == nil }) {
                // With altitude and the like as well: one number each.
                guard numbers.count == heading.count, let la = heading.firstIndex(of: .lat),
                      let lo = heading.firstIndex(of: .lon) else { return nil }
                tokens = [.label(.lat), numbers[la], .separator, .label(.lon), numbers[lo]]
            } else {
                guard heading.count == 2, heading[0] != heading[1] else { return nil }
                headingFirst = heading[0]
            }
        }
        // Altitude, a bearing and the like, with their values: not part of the position. One in degrees, or with another
        // number right after it, may be the first of a list ("Bearing 10° 20° 30°", "Heading 10 20 30"): then the rest is
        // read as among other words.
        var k = 0
        while k < tokens.count {
            guard case .label(nil) = tokens[k] else {
                k += 1
                continue
            }
            var end = k + 1
            while end < tokens.count, isMark(tokens[end]) { end += 1 } // the quotes of "altitude": 312
            if end < tokens.count, isNumber(tokens[end]) {
                end += 1
                if end < tokens.count, case .degree = tokens[end] { words = true }
                if end < tokens.count, isMark(tokens[end]) { end += 1 } // its unit, "312 m" read as minutes
                if end < tokens.count, isNumber(tokens[end]) { words = true }
            } else {
                end = k + 1
            }
            tokens.removeSubrange(k..<end)
        }
        // A latitude's and a longitude's letter written together (see `Token.tag`), for halves without letters, and whether
        // they are after the numbers.
        let lastNumber = tokens.lastIndex(where: isNumber) ?? tokens.count
        let tags = tokens.indices.compactMap { k -> (letters: [Character], after: Bool)? in
            if case let .tag(a, b) = tokens[k] { return ([a, b], k > lastNumber) }
            return nil
        }
        tokens.removeAll { if case .tag = $0 { return true } else { return false } }
        // A letter after a pair written with a minus sign is a direction, where something is going or where it is from
        // ("-24.9969, 31.5923 W of Skukuza", "… (W of the bridge)", "… W 270°", "… moving W"), not a hemisphere: it doesn't
        // change a sign. So is one before it ("Wild dogs moving W -24.9969, 31.5923", "N4 W to Nelspruit, -25.4753, …").
        // (N or E next to a number with a minus sign still says two things: "-25.0969, -31.4371 E", "N -25.0969, 31.4371".)
        // A W right after the last number, with nothing after it, may as well be the hemisphere of a half without a minus
        // sign ("-33.4489, 70.6693 W"): it isn't clear which (see below).
        var hemis = tokens.indices.filter { if case .hemisphere = tokens[$0] { return true } else { return false } }
        var lastW = false
        if hemis.count == 1, tokens.contains(where: isNegative), case let .hemisphere(h, _) = tokens[hemis[0]],
           let first = tokens.firstIndex(where: isNumber), let last = tokens.lastIndex(where: isNumber) {
            let k = hemis[0], after = k > last, before = k < first && isNegative(tokens[first])
            if (after && !(isNegative(tokens[last]) && "NE".contains(h))) || (before && "SW".contains(h)) {
                lastW = after && h == "W" && (k == last + 1 || (k == last + 2 && isMark(tokens[last + 1])))
                    && tokens[(k + 1)...].allSatisfy(isSeparator)
                tokens.remove(at: k)
                hemis = []
            }
        }

        // Split into the two halves.
        var parts: [[Token]] = [], left: [[Token]] = []
        let numberCount = tokens.filter(isNumber).count
        guard numberCount >= 2 else { return nil }
        if hemis.count == 2 {
            guard let p = byLetters(tokens, hemis) else { return nil }
            parts = p.parts
            left = p.left
        } else if hemis.count > 2 {
            return nil
        } else if let sep = splitAtSeparator(tokens) {
            parts = sep
        } else if let deg = splitAtDegrees(tokens) {
            parts = deg
        } else if numberCount % 2 == 0, numberCount <= 6 {
            var seen = 0
            var cut = 0
            for (k, tok) in tokens.enumerated() {
                if case .number = tok {
                    seen += 1
                    if seen == numberCount / 2 + 1 { cut = k; break }
                }
            }
            while cut > 0, isLabel(tokens[cut - 1]) { cut -= 1 }
            // A number right after degrees, with nothing between them, is their minutes: "24° 59.816 S" and "S 24°
            // 59.816" are one coordinate, not 24° and 59.816°. So is a number with a degree sign right after a whole number
            // ("25 05.8136 degS", the sign written after the minutes).
            if cut > 0, case .degree = tokens[cut - 1] { return nil }
            if cut + 1 < tokens.count, case .degree = tokens[cut + 1], case .number(_, _, 0, false) = tokens[cut - 1] { return nil }
            parts = [Array(tokens[..<cut]), Array(tokens[cut...])]
        } else {
            return nil
        }
        guard var halves = halves(parts) else { return nil }
        // (The W left out above, after a half without a minus sign: "-33.4489, 70.6693 W".)
        if lastW && !halves[1].signed { return nil }
        // Those letters are the halves' in their order, the latitude's first. (A minus sign and N or E say two things.) After
        // a pair written with a minus sign they are a direction, as one letter is, and don't change a sign ("Lions -24.3812,
        // 31.8456 (S/W)").
        if let tag = tags.first {
            guard tags.count == 1, hemis.isEmpty, halves[0].axis != .lon, halves[1].axis != .lat else { return nil }
            let direction = tag.after && halves.contains { $0.signed }
            for k in 0..<2 {
                if tag.letters[k] == "S" || tag.letters[k] == "W" {
                    if !halves[k].signed && !direction { halves[k].value = -halves[k].value }
                } else if halves[k].signed {
                    return nil
                }
                halves[k].axis = k == 0 ? .lat : .lon
            }
        }
        // Beside a place, two numbers without letters or labels, each named by words before it ("USD/ZAR 18.2345, EUR/ZAR
        // 19.8765. Lions at -25.0969, 31.4371"), are what the message says next to that place, not a second one. (On
        // their own they are read: the words may be labels in another language, "Широта: 55.7558 Долгота: 37.6173".)
        if beside, halves.allSatisfy({ $0.axis == nil }), let a = parts[0].firstIndex(where: isNumber),
           let b = parts[0].lastIndex(where: isNumber), let c = parts[1].firstIndex(where: isNumber) {
            func named(_ part: ArraySlice<Token>) -> Bool {
                part.contains { if case .text(true) = $0 { return true } else { return false } }
            }
            if named(parts[0][..<a]) && (named(parts[0][(b + 1)...]) || named(parts[1][..<c])) { return nil }
        }
        // Among other words, numbers that could as well be times, dates, counts or measurements ("Gates open 05:30 close
        // 18:00", "12 lions 3 cubs", "temp 25.5, wind 31.4", "Lions 5 N of Satara") aren't a position. Degrees, minutes and
        // seconds in both halves with a minus sign before the first ("We're at -25 05 48, 31 26 13") can't be a time or a
        // count.
        let signedSeconds = halves[0].signed && halves.allSatisfy { $0.numbers == 3 }
        if words && headingFirst == nil && !(halves[0].strong && halves[1].strong) && !signedSeconds { return nil }
        // Nor are times on their own ("05:30 - 18:30", "10:15:30, 11:20:40", "+02:00 -05:00"), with the same exception,
        // or with a minus sign before degrees that no hour or time zone has ("-25:05, 31:26"; not "-25:05 - 31:26", which
        // may be a range).
        let signedDegrees = halves[0].signed && abs(halves[0].value) > 14 && !halves[1].signed
        if halves.contains(where: { $0.time }) && !signedSeconds && !signedDegrees { return nil }
        // A letter right before degrees and minutes, with no letter or label on the other half ("Lions on S28 25.0969,
        // 31.4371", "N4 25.4567, 30.9876"), may as well be a road's name: it isn't clear which.
        if halves.contains(where: { $0.gluedLetter && $0.numbers > 1 }) && halves.contains(where: { $0.axis == nil }) {
            return nil
        }
        var lat = halves[0].value, lon = halves[1].value
        switch (halves[0].axis, halves[1].axis) {
        case let (a?, b?):
            guard a != b else { return nil }
            if a == .lon { swap(&lat, &lon) }
        case let (a?, nil):
            if a == .lon { swap(&lat, &lon) }
        case let (nil, b?):
            if b == .lat { swap(&lat, &lon) }
        case (nil, nil):
            let labels = tokens.compactMap { tok -> Axis? in
                if case let .label(a) = tok { return a }
                return nil
            }
            if (headingFirst ?? labels.first) == .lon || (abs(lat) > 90 && abs(lon) <= 90) { swap(&lat, &lon) }
        }
        guard let point = valid(lat, lon) else { return nil }
        let kind = max(halves[0].kind, halves[1].kind)
        let format = ["decimal degrees", "degrees and decimal minutes", "degrees, minutes and seconds", "GPS (NMEA)"][kind]
        let r: Reading = (Result(point: point, format: format), max(halves[0].resolution, halves[1].resolution))
        // What its letters left out holds a part of a position ("…E\n-25.096889, 31.437111"): the same place written
        // again, or else another place, and it isn't clear which is meant.
        for part in left {
            guard let other = reading(part, words: words), samePlace(other, r) else { return nil }
        }
        return r
    }

    /// The two halves, if each reads as one.
    private static func halves(_ parts: [[Token]]) -> [Half]? {
        guard parts.count == 2 else { return nil }
        // Numbers joined by a dash or colon stay in one half: "45-30" or "12:30" isn't 45, 30 or 12, 30.
        if case .joiner? = parts[0].last { return nil }
        let degrees = parts.contains { $0.contains { if case .degree = $0 { return true } else { return false } } }
        let halves = parts.compactMap { component($0, degrees: degrees) }
        guard halves.count == 2 else { return nil }
        // "Spot 1: 51.5007, -0.1246": a number before the position (a spot or camp number) makes the first half look like
        // 1° 51.5007'. A first half with more numbers than the second is read only when something marks where it starts
        // ("40° 41' 21" N 74° 2' W", "-25 05 48.8, 31.437121", "S25 05 48.8 E31 26.2273").
        if halves[0].numbers > halves[1].numbers && !halves[0].startMarked { return nil }
        return halves
    }

    /// The halves of a text with two letters: before the numbers ("S 25 … E 31 …"), after them ("25 … S 31 … E"), or one
    /// of each ("25 … S, E 31 …"), and the parts left out that hold a part of a position (see `reading`). What is left
    /// out (a count, a name, a time or a date before it; a phone number, height, date or count after it) isn't part of
    /// it.
    private static func byLetters(_ tokens: [Token], _ hemis: [Int]) -> (parts: [[Token]], left: [[Token]])? {
        let before = Array(tokens[..<hemis[0]]), between = tokens[(hemis[0] + 1)..<hemis[1]], after = Array(tokens[(hemis[1] + 1)...])
        var chosen: (parts: [[Token]], left: [[Token]])
        if !between.contains(where: isNumber) {
            let (second, rest, _) = ended(Array(tokens[(hemis[0] + 1)...]))
            let start = backStart(tokens, letter: hemis[0], count: second.filter(isNumber).count)
            chosen = ([Array(tokens[start...hemis[0]]), second], [Array(tokens[..<start]), rest])
        } else {
            // Letters before the numbers: the second half ends where it is complete, and what is after it is other text.
            // With numbers before the first letter, only with a number right after the second ("…E, 545.4", "…E\n-24.9961"
            // is what comes after a position with its letters after the numbers). Letters after the numbers: what is
            // after the second letter is other text ("…E 2026-10-27", "…E 3 cubs").
            let (second, rest, complete) = ended(Array(tokens[hemis[1]...]))
            let start = backStart(tokens, letter: hemis[0], count: between.filter(isNumber).count)
            let front = [Array(tokens[hemis[0]..<hemis[1]]), second]
            let back = [Array(tokens[start...hemis[0]]), Array(tokens[(hemis[0] + 1)...hemis[1]])]
            func reads(_ parts: [[Token]]) -> Bool { halves(parts).map(isPosition) ?? false }
            // Whether its halves are written the same way (as many fields), the second complete.
            func alike(_ parts: [[Token]], _ complete: Bool) -> Bool {
                complete && halves(parts).map { $0[0].numbers == $0[1].numbers } ?? false
            }
            let frontReads = after.contains(where: isNumber) && (!before.contains(where: isNumber) || after.first.map(isNumber) == true)
                && reads(front)
            let glued = hemis.allSatisfy { if case .hemisphere(_, true) = tokens[$0] { return true } else { return false } }
            let backTime = back[0].contains { if case .joiner(true) = $0 { return true } else { return false } }
                && back[0].last(where: isNumber).map { decimalPlaces($0) == 0 } == true
            switch (frontReads, before.contains(where: isNumber) && reads(back)) {
            // Each letter written right before a number ("27/10/2026 S24.99 E31.59", "38°C S24 59 48.9 E31 35 32.3",
            // "06:45 S25-05-48.8 E031-26-13.6"): the letters are in front of those numbers, whatever is before them (a date,
            // a time, a temperature, a count).
            case (true, _) where glued && complete:
                chosen = (front, [before, rest])
            // Both halves written alike rather than differently: "Leopard 06.45 S 24 59.8 E 31 35.5" is 24° 59.8' S, 31°
            // 35.5' E, not 6.45° S, 24° 59.8' E; "24 59 48.9 S 31 35 32.3 E 45° bearing" isn't 31° 35' 32.3" S, 45° E.
            case (true, true) where alike(front, complete) != alike(back, ended(back[1]).complete):
                chosen = alike(front, complete) ? (front, [before, rest]) : (back, [Array(tokens[..<start]), after])
            // A time before a letter ("27/10/2026 06:45 S 25 E 31") isn't degrees and minutes when the letters can as well
            // be in front.
            case (true, true) where backTime:
                chosen = (front, [before, rest])
            case (true, true):
                // Both can be read: the one that leaves out less of what can be a position. A count or a name (one whole
                // number: "Stop 3 S25.0969 E31.4371", "WGS 84: S24 59 48.9 E31 35 32.3") rather than other numbers
                // ("31 35 32.3", "24.99"), and those rather than decimals with 3 places or more or numbers with their
                // marks ("25.0969 S 31.4371 E 3 cubs"); whole numbers before the first letter (a time, a date, counts)
                // rather than a whole half after the second ("12:30 Lions S 24 59 48.9 E 31 35 32.3"). Leaving out as
                // much, the letters go after the numbers when the second half is complete ("24.99 S 31.59 E 12.5"); else
                // it isn't clear ("25 S 31 E 3": 3 cubs, or 3 minutes? "12.5 S 24 59 E 31 35").
                let frontLeaves = leftOut(before), backLeaves = leftOut(Array(second.dropFirst()))
                let backLeavesAHalf = isHalf(Array(second.dropFirst())) && !before.contains { decimalPlaces($0) > 0 }
                if frontLeaves < backLeaves || (backLeavesAHalf && frontLeaves < 2) {
                    chosen = (front, [before, rest])
                } else if backLeaves < frontLeaves || (backLeaves == 1 && ended(back[1]).complete) {
                    chosen = (back, [Array(tokens[..<start]), after])
                } else {
                    return nil
                }
            case (true, false): chosen = (front, [before, rest])
            case (false, true): chosen = (back, [Array(tokens[..<start]), after])
            case (false, false): return nil
            }
        }
        // What holds (a part of) another position: decimals with 3 places or more ("…E\n-24.9961, 31.5911", "S24.99 E31.59
        // -25.1234"), or more than one number with its mark (one is a bearing or a temperature: "…E, 30° bearing").
        let holding = chosen.left.filter { part in
            part.contains { decimalPlaces($0) >= 3 } || part.indices.filter { partOfAPosition(part, $0) }.count >= 2
        }
        return (chosen.parts, holding)
    }

    /// Where a half with its letter after it (at `letter`) starts, when more numbers are before the letter than the other
    /// half has (`count`): right after other text or a separator, or at a label, that has as many as that between it and
    /// the letter ("[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "12 buffalo at the dam\nLions 24 22.872 S …", "km
    /// 14: 24.3812 S 31.8456 E", "Camp 14 lat 24.99693 S lon …"), or at the last that many when there are more than one
    /// half can have ("Camp 14 24 59 48.9 S 31 35 32.3 E"); else at the start. (A number right before them could be their
    /// first field: "Camp 1 25 05.8 S, 31 26.2 E".)
    private static func backStart(_ tokens: [Token], letter: Int, count: Int) -> Int {
        let numbers = tokens[..<letter].indices.filter { isNumber(tokens[$0]) }
        guard numbers.count > count, count > 0 else { return 0 }
        let first = numbers[numbers.count - count], previous = numbers[numbers.count - count - 1]
        // (Not after the slash of a date or a fraction: "27/10 S 24.99 E 31.59" isn't 10° S.)
        if let k = tokens[..<letter].lastIndex(where: { isText($0) || isLabel($0) || (isSeparator($0) && !isSlash($0)) }),
           k < first, k > previous {
            return isLabel(tokens[k]) ? k : k + 1
        }
        // Right after a time or a short date ("06:45 24.99693 S 31.59231 E", "27/10 24.99693 S …").
        if first == previous + 1, previous > 0, isJoiner(tokens[previous - 1]) || isSlash(tokens[previous - 1]) { return first }
        // At a degree sign on its first number ("Lions 3 24° 22.872' S …"). Not at one on the number before them, with no
        // mark on theirs, when the other half has none and is written as they are, to as many decimals: that is a
        // temperature or an angle ("Lions 32° 24 22.872 S 31 50.736 E", not 32° 24' 22.872"; "24° 22 52.3 S 31 50.736 E" is
        // 24° 22' 52.3").
        let other = tokens.indices.filter { $0 > letter && isNumber(tokens[$0]) }.prefix(count)
        let lastDecimals = decimalPlaces(tokens[numbers[numbers.count - 1]])
        let otherDecimals = other.last.map { decimalPlaces(tokens[$0]) }
        func degrees(_ k: Int) -> Bool {
            if k + 1 < tokens.count, case .degree = tokens[k + 1] { return true }
            return false
        }
        if degrees(first) || (degrees(previous) && !isMark(tokens[first + 1]) && !other.contains(where: degrees)
                              && otherDecimals == lastDecimals) {
            return first
        }
        // A whole number before degrees and minutes with 3 decimals or more, when the other half is written so too ("Lions
        // 3 24 22.872 S 31 50.736 E", as a GPS shows them): a count or a name's number, not 3° 24' 22.872".
        if count == 2, other.count == 2, decimalPlaces(tokens[previous]) == 0, !isMark(tokens[previous + 1]), lastDecimals >= 3,
           (otherDecimals ?? 0) >= 3 {
            return first
        }
        // A whole number after a word, before one number with decimals, when the other half is one number with decimals
        // too ("Leopard 2 24.99692S, 31.59231E", "Camp 14 …"): a count or a name's number, as the other half says the
        // position is written in decimal degrees, not 2° 24.99692'.
        if count == 1, first == previous + 1, previous > 0, isText(tokens[previous - 1]), decimalPlaces(tokens[previous]) == 0,
           decimalPlaces(tokens[first]) > 0, tokens[(letter + 1)...].first(where: isNumber).map({ decimalPlaces($0) > 0 }) == true {
            return first
        }
        return numbers.count > 3 && count <= 3 ? numbers[numbers.count - count] : 0
    }

    /// How much of a position a reading leaves out: 0 a count or a name (one whole number), 1 other numbers, 2 what can be
    /// a part of a position (decimals with 3 places or more, a number with its unit mark).
    private static func leftOut(_ part: [Token]) -> Int {
        if part.indices.contains(where: { partOfAPosition(part, $0) }) { return 2 }
        return isCount(part) ? 0 : 1
    }

    /// Whether the part starts with degrees and minutes, or degrees, minutes and seconds, side by side and complete
    /// ("31 35 32.3", "31 35.53"): a half of a position without its letter. Not a time ("12:30") or a date ("27/10/26").
    private static func isHalf(_ part: [Token]) -> Bool {
        guard let k = part.firstIndex(where: isNumber) else { return false }
        let run = Array(part[k...].prefix(while: isNumber))
        guard (2...3).contains(run.count), component(run) != nil else { return false }
        return ended(run).complete
    }

    /// Whether the token at `k` is a number with 3 decimal places or more, or with a unit mark after it.
    private static func partOfAPosition(_ part: [Token], _ k: Int) -> Bool {
        isNumber(part[k]) && (decimalPlaces(part[k]) >= 3 || (k + 1 < part.count && isMark(part[k + 1])))
    }

    /// Whether two halves with their letters are a latitude and a longitude ("25 S 31 E", not "25 S 31 S" or "25 S 545 E").
    private static func isPosition(_ halves: [Half]) -> Bool {
        guard let a = halves[0].axis, let b = halves[1].axis else { return true }
        return a != b && valid(a == .lat ? halves[0].value : halves[1].value, a == .lat ? halves[1].value : halves[0].value) != nil
    }

    /// A half that starts with its letter, up to where it is complete: after a number with decimals, its seconds or its
    /// third number. What is after that ("S24 59.815 E31 35.539 2 lions", "… Tel: 013 735 4152") isn't part of it.
    private static func ended(_ part: [Token]) -> (half: [Token], rest: [Token], complete: Bool) {
        var numbers = 0
        for (k, tok) in part.enumerated() {
            guard case let .number(_, _, places, _) = tok else { continue }
            numbers += 1
            var end = k + 1
            let seconds: Bool
            if end < part.count, case .second = part[end] { seconds = true } else { seconds = false }
            guard places > 0 || seconds || numbers == 3 else { continue }
            if end < part.count, isMark(part[end]) { end += 1 }
            return (Array(part[..<end]), Array(part[end...]), true)
        }
        return (part, [], false)
    }

    /// One whole number on its own (a count, a camp or waypoint number, the 84 of "WGS 84").
    private static func isCount(_ part: [Token]) -> Bool {
        let numbers = part.indices.filter { isNumber(part[$0]) }
        guard numbers.count == 1, decimalPlaces(part[numbers[0]]) == 0 else { return false }
        return !(numbers[0] + 1 < part.count && isMark(part[numbers[0] + 1]))
    }

    /// Whether the N, S, E or W at `i` starts a name, as Kruger's roads are named ("S3", "S100", the "N4"): one whole
    /// number glued to it (see `nameShaped`), with no other number after it ("S24 59 48.9"), or one that no position has
    /// ("S100 24 22.872 S …", "E200").
    private static func isName(_ chars: [Character], _ i: Int) -> Bool {
        guard nameShaped(chars, i) else { return false }
        var k = i + 1
        while k < chars.count, chars[k].isNumber { k += 1 }
        if k - i <= 4, let v = Double(String(chars[(i + 1)..<k])), v > ("NS".contains(chars[i]) ? 90 : 180) { return true }
        while k < chars.count, chars[k] == " " || chars[k] == "\t" { k += 1 }
        return !(k < chars.count && chars[k].isNumber)
    }

    /// Whether the N, S, E or W at `i` has one whole number glued to it, with nothing after it that goes on with a
    /// position (decimals, a unit mark, a dash or colon and more fields: "S25.0969", "S25-05-48.8", "E31°").
    private static func nameShaped(_ chars: [Character], _ i: Int) -> Bool {
        // (Not the letter of a number before it, "5130N00007W".)
        guard i == 0 || !(isWordLetter(chars[i - 1]) || chars[i - 1].isNumber || chars[i - 1] == "."),
              i + 1 < chars.count, chars[i + 1].isNumber else { return false }
        var k = i + 1
        while k < chars.count, chars[k].isNumber { k += 1 }
        // (The mark also after a space: "S24 ° 59.816'", "S24 deg 59.816".)
        var mark = k
        while mark < chars.count, chars[mark] == " " || chars[mark] == "\t" { mark += 1 }
        if mark < chars.count, "°'\"".contains(chars[mark]) { return false }
        return !(k + 1 < chars.count && ".,-:".contains(chars[k]) && chars[k + 1].isNumber)
    }

    /// Where a name with digits in it ends: letters and digits, and a dash, point or slash between them ("H4-1", "4x4").
    private static func afterName(_ chars: [Character], _ i: Int) -> Int {
        var k = i
        func part(_ c: Character) -> Bool { c.isNumber || isWordLetter(c) }
        while k < chars.count, part(chars[k]) || ("-./".contains(chars[k]) && k + 1 < chars.count && part(chars[k + 1])) {
            k += 1
        }
        return k
    }

    /// Whether the N, S, E or W at `i` is a direction: on its own (not in a word, nor in "it's" or Afrikaans "'n") and
    /// next to a number or a unit mark ("25.0969 S", "S 25.0969", "48.8"S"), not a word in a sentence ("Heading west,
    /// lions at …", "near the east gate").
    private static func isHemisphere(_ chars: [Character], _ i: Int) -> Bool {
        guard !isWordLetter(i > 0 ? chars[i - 1] : nil), !isWordLetter(i + 1 < chars.count ? chars[i + 1] : nil) else {
            return false
        }
        // After an apostrophe only when that is a minute mark ("05'S").
        if i > 0, chars[i - 1] == "'", !(i > 1 && chars[i - 2].isNumber) { return false }
        var k = i - 1
        while k >= 0, chars[k].isWhitespace || chars[k] == "," || chars[k] == "(" { k -= 1 } // also "25.0969 (S)"
        if k >= 0, chars[k].isNumber || "°'\"".contains(chars[k]) { return true }
        k = i + 1
        while k < chars.count, chars[k].isWhitespace || chars[k] == ":" || chars[k] == ")" { k += 1 }
        if k < chars.count, chars[k].isNumber { return true }
        return k + 1 < chars.count && "-+.".contains(chars[k]) && chars[k + 1].isNumber
    }

    /// Whether the dash at `i` is between whole degrees or minutes (1 to 3 digits) and 2 digits under 60, as in
    /// "25-05-48.8" (not "40-74.0445").
    private static func fieldDash(_ chars: [Character], _ i: Int) -> Bool {
        var k = i - 1
        while k >= 0, chars[k].isNumber { k -= 1 }
        guard (1...3).contains(i - 1 - k), k < 0 || (chars[k] != "." && chars[k] != ",") else { return false }
        guard i + 2 < chars.count, let tens = chars[i + 1].wholeNumberValue, tens < 6, chars[i + 2].isNumber else { return false }
        return i + 3 == chars.count || !chars[i + 3].isNumber
    }

    private static func isLabel(_ t: Token) -> Bool {
        if case .label = t { return true }
        return false
    }

    private static func isNumber(_ t: Token) -> Bool {
        if case .number = t { return true }
        return false
    }

    /// Whether it is a number with a minus sign before it.
    private static func isNegative(_ t: Token) -> Bool {
        if case .number(_, _, _, true) = t { return true }
        return false
    }

    private static func isSeparator(_ t: Token) -> Bool {
        switch t {
        case .separator, .slash: return true
        default: return false
        }
    }

    private static func isSlash(_ t: Token) -> Bool {
        if case .slash = t { return true }
        return false
    }

    private static func isJoiner(_ t: Token) -> Bool {
        if case .joiner = t { return true }
        return false
    }

    private static func isText(_ t: Token) -> Bool {
        if case .text = t { return true }
        return false
    }

    /// A number's digits after the point (0 for anything else).
    private static func decimalPlaces(_ t: Token) -> Int {
        if case let .number(_, _, places, _) = t { return places }
        return 0
    }

    private static func isMark(_ t: Token) -> Bool {
        switch t {
        case .degree, .minute, .second: return true
        default: return false
        }
    }

    /// At a separator with numbers on both sides (a comma inside "25,5" was read as a decimal comma already).
    private static func splitAtSeparator(_ tokens: [Token]) -> [[Token]]? {
        guard let first = tokens.firstIndex(where: isNumber), let last = tokens.lastIndex(where: isNumber),
              let k = tokens[first..<last].firstIndex(where: isSeparator) else { return nil }
        return [Array(tokens[..<k]), Array(tokens[(k + 1)...])]
    }

    /// Before the second number marked with a degree sign, unless that is right after whole degrees and can be their
    /// minutes ("25° 05.8136 degS": the sign written after the minutes; see `reading`).
    private static func splitAtDegrees(_ tokens: [Token]) -> [[Token]]? {
        var marked: [Int] = []
        for k in tokens.indices.dropLast() {
            if isNumber(tokens[k]), case .degree = tokens[k + 1] { marked.append(k) }
        }
        guard marked.count == 2 else { return nil }
        if marked[1] == marked[0] + 2, case .number(_, _, 0, _) = tokens[marked[0]], case let .number(v, _, _, _) = tokens[marked[1]],
           v < 60 {
            return nil
        }
        var cut = marked[1]
        // Keep a label just before it with the second half.
        while cut > 0, isLabel(tokens[cut - 1]) { cut -= 1 }
        return [Array(tokens[..<cut]), Array(tokens[cut...])]
    }

    /// One half: its value with sign, its axis (from N/S/E/W or a label), its kind and how it is marked (see `Half`).
    /// `degrees`: whether the position has a degree sign, which makes a ' or " after a number a minute or second mark.
    private static func component(_ part: [Token], degrees: Bool = false) -> Half? {
        var nums: [(value: Double, intDigits: Int, places: Int, negative: Bool, unit: Int?)] = []
        var hemi: Character?
        var label: Axis?
        var lettered = false, glued = false // a letter or label before its first number, the letter right before it
        // A label or other words between its numbers, or a comma, semicolon or slash unless its letter comes after them
        // (CSV columns, "25,05,48.8,S,31,26,13.6,E"): they aren't the fields of one half. "-24,99 31,59." with the commas
        // read as separators isn't -24, 99° 31' 59", nor is "2026/10/27" 2026, 10° 27'. Nor is a letter after other words
        // its letter ("Warm 34°. Leeus by S24 59 48.9 …", "06.45 at S24.99 …": that S is the next half's, in front of it).
        var labelAfter = false, separatorAfter = false, separated = false
        var colon = false
        for (k, tok) in part.enumerated() {
            switch tok {
            case let .number(v, digits, places, neg):
                if labelAfter { return nil }
                if separatorAfter { separated = true }
                var unit: Int?
                if k + 1 < part.count {
                    switch part[k + 1] {
                    case .degree: unit = 0
                    case .minute: unit = 1
                    case .second: unit = 2
                    default: break
                    }
                }
                nums.append((v, digits, places, neg, unit))
            case let .hemisphere(h, next):
                if labelAfter { return nil }
                hemi = h
                if nums.isEmpty {
                    lettered = true
                    glued = next
                }
            case let .label(a?):
                if nums.isEmpty {
                    label = a
                    lettered = true
                } else {
                    labelAfter = true
                }
            case .separator, .slash:
                if !nums.isEmpty { separatorAfter = true }
            case .text:
                if !nums.isEmpty { labelAfter = true }
            case let .joiner(c):
                colon = colon || c
            default: break
            }
        }
        guard (1...3).contains(nums.count), !separated || (hemi != nil && !lettered) else { return nil }
        // Only the first number has a sign: "14 -25.1" is a stray number before a position, not 14° 25.1'.
        guard !nums.dropFirst().contains(where: { $0.negative }) else { return nil }
        // A minus sign and N or E ("-31.4371 E") say different things.
        if nums[0].negative && (hemi == "N" || hemi == "E") { return nil }
        // Without a degree sign in the position, a ' or " after a number that can't be minutes or seconds is a quote
        // ('-25.0969'). With one, they are minutes and seconds, and a half can't start with them: "S 24° 59.815'" is one
        // coordinate, not 24° S and 59.815° E.
        if !degrees && !nums.contains(where: { $0.unit == 0 }) {
            for k in nums.indices where (nums[k].unit ?? 0) > nums.count - 1 { nums[k].unit = nil }
        } else if (nums[0].unit ?? 0) > 0 {
            return nil
        }
        // Degrees, then minutes, then seconds: "25°05°48.8"" (a degree sign typed for the minute mark) or "31° 32°" (a
        // list of temperatures) isn't one half.
        var lastUnit = -1
        for (k, n) in nums.enumerated() {
            guard (n.unit ?? k) > lastUnit else { return nil }
            lastUnit = n.unit ?? k
        }
        var d = 0.0, m = 0.0, sec = 0.0
        var kind = nums.count - 1
        let resolution: Double
        if nums.count == 1, nums[0].unit == nil, nums[0].intDigits >= 4 {
            // GPS (NMEA) DDMM.mmmm or DDDMM.mmmm, and DDMMSS(.s): 4 digits or more before the point, leading zeros too
            // ("00007.4760" is 0° 07.476'), can't be whole degrees. Without decimals, only with its letter: else it is a
            // year, a time or a code ("2015-2020", "0800-1700", "1350 1242").
            guard nums[0].places > 0 || hemi != nil else { return nil }
            let v = nums[0].value
            if nums[0].intDigits <= 5 {
                d = (v / 100).rounded(.down)
                m = v - d * 100
            } else {
                d = (v / 10_000).rounded(.down)
                m = ((v - d * 10_000) / 100).rounded(.down)
                sec = v - d * 10_000 - m * 100
            }
            kind = 3
            resolution = pow(10, -Double(nums[0].places)) / (nums[0].intDigits <= 5 ? 60 : 3600)
        } else {
            for (k, n) in nums.enumerated() {
                switch n.unit ?? k {
                case 0: d = n.value
                case 1: m = n.value
                default: sec = n.value
                }
            }
            if nums.contains(where: { $0.unit == 2 }) { kind = 2 } else if nums.contains(where: { $0.unit == 1 }) { kind = max(kind, 1) }
            let last = nums[nums.count - 1]
            resolution = pow(10, -Double(last.places)) / [1, 60, 3600][min(last.unit ?? nums.count - 1, 2)]
        }
        guard m < 60, sec < 60 else { return nil }
        // Only the last number has a fraction: 25.5° 30' isn't a position, nor is "31.4371, -25.0969, 0" (lon, lat and
        // altitude, or lat, lon and altitude: it can't be told which).
        if nums.dropLast().contains(where: { $0.value != $0.value.rounded() }) { return nil }
        var value = d + m / 60 + sec / 3600
        if nums[0].negative || hemi == "S" || hemi == "W" { value = -value }
        var axis = label
        if let h = hemi { axis = (h == "N" || h == "S") ? .lat : .lon }
        return Half(value: value, axis: axis, kind: kind, numbers: nums.count,
                    time: colon && hemi == nil && label == nil && nums[nums.count - 1].places == 0,
                    startMarked: lettered || nums[0].unit == 0 || nums[0].negative,
                    strong: label != nil || (hemi != nil && (lettered || !(nums.count == 1 && nums[0].places == 0 && nums[0].unit == nil)))
                        || nums.contains { $0.unit == 1 || $0.unit == 2 || $0.places >= 3 },
                    gluedLetter: glued, signed: nums[0].negative, resolution: resolution)
    }

    // MARK: - A position inside other text

    /// A position among other words and numbers ("Camp site 14: -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: …",
    /// "Lions on H4-1 at …"), where the other numbers keep the whole text from being read: a pair that can only be a
    /// position, read on its own. That is decimals with 3 places or more, values with a degree sign and minutes or a
    /// letter, or degrees with minutes with 3 decimal places or more, or with seconds, and a letter ("S24 22.872 E31
    /// 50.736", as a GPS shows them), side by side and not part of a longer list of numbers. When the text has two
    /// different ones, it isn't clear which is meant, nor with more than 10 (a track, not a place), nor when another
    /// number like them is elsewhere in the text.
    private static func inText(_ t: String, near: GeoPoint?) -> Found? {
        let ns = t as NSString
        var found: Reading?, tried = 0
        // (Decimal commas only when a comma is between digits.)
        let decimalCommas = has(#"\d,\d"#, in: t)
        // A pair without letters in a text with both halves' letters written together ("Lions (S/E): 24.99693, 31.59232,
        // 3 males"): those are its letters, which only the whole text says. It is read on its own only with a minus sign,
        // as they are then a direction (see `reading`), and not with an N or E that says otherwise ("2 lions -24.996931,
        // 31.592312 (S/E)"). (Looked for only when two such letters are next to a number, which is quicker in a long text.)
        let together = #"[NS]\s*[/,]\s*[EW](?![A-Z])"#
        let tagged = has(#"(?i)[\d°'"][\s,(]*"# + together + #"|(?<![A-Z])"# + together + #"[\s:)]*[-+.]?\d"#, in: t)
            ? tag(in: t) : nil
        for (decimal, separator) in [(#"\."#, #"(?:\s*[,;/]\s*|\s+)"#), (",", #"(?:\s*[;/]\s*|\s+)"#)] where decimal == "," ? decimalCommas : true {
            let fraction: String = "(?:" + decimal + #"\d+)?"#
            let decimals: String = #"[-+]?\d{1,3}"# + decimal + #"\d{3,}\s?°?"#
            let degrees: String = #"[-+]?\d{1,3}"# + fraction + #"\s?°(?:\s?\d{1,2}"# + fraction + #"\s?')?(?:\s?\d{1,2}"# + fraction
                + #"\s?")?"#
            let fields: String = #"\d{1,3}\s\d{1,2}(?:"# + decimal + #"\d{3,}|\s\d{1,2}"# + fraction + ")"
            // A letter after a half, not the first of two written together ("…, 31.59232 S/E"). One before it and one after
            // it are read as the whole text would be ("W 24 59.816 S 31 35.539 E", "S24 59.816 E31 35.539 N of the dam": one
            // is a direction).
            let letter: String = #"\s?[NSEWO](?![A-Z]|\s*/\s*[EW](?![A-Z]))"#
            let half: String = #"(?:[NSEWO]\s?"# + fields + "(?:" + letter + ")?|" + fields + letter + #"|(?:[NSEWO]\s?)?(?:"#
                + decimals + "|" + degrees + ")(?:" + letter + ")?)"
            // (Not from the minutes of degrees with a minus sign: "-24 59.816, 31 35.539".)
            let pair: String = #"(?<![\w.,°'"+-])(?!(?<=(?:^|[^\w.])[-+]\d{1,3}[ \t])\d)"# + half + "(" + separator + ")" + half
                + #"(?![\w°'"]|[.,]\d)"#
            var read: [NSRange] = []
            for m in matchRanges(pair, in: t, options: [.caseInsensitive]) {
                let r = m[0]
                // A degree sign alone is also a temperature or an angle ("Skukuza 38°/22°", "Bearing 10° 20° 30° 40°").
                let halves = [NSRange(location: r.location, length: m[1].location - r.location),
                              NSRange(location: NSMaxRange(m[1]), length: NSMaxRange(r) - NSMaxRange(m[1]))]
                guard halves.allSatisfy({ has(#"\d[.,]\d{3}|['"]|(?i:[NSEWO])"#, in: ns.substring(with: $0)) }) else { continue }
                // (Not into a pair read before it: "S 25.096893 E 31.437121\n25.096893 S 31.437121 E" is the same place twice.)
                let start = max(0, r.location - 20, read.last.map(NSMaxRange) ?? 0), end = NSMaxRange(r)
                let before = ns.substring(with: NSRange(location: start, length: r.location - start))
                let after = ns.substring(with: NSRange(location: end, length: min(20, ns.length - end)))
                // Part of a longer list of numbers ("31.4371 -25.0969 545.4": longitude, latitude and altitude; "Lap times:
                // 12.345 13.456 14.567"): a number after it, or a decimal before it, joined to it by a comma or semicolon
                // or the way its own two numbers are joined. Not a number in a name ("Cheetah on H4-1, -24.9969, …") or
                // after a word ("H1-2 km 23.4, -24.9969, …"), nor a time, a date, a phone number, a temperature or a count
                // with a word, which aren't an altitude or a coordinate ("…, 06:45", "27/10/2026, …", "…, 082 555 1234",
                // "…, 32°C", "Wild dogs 12, …", "…, 3 males"). With its letters a pair says which half is which, and a
                // number after it is something else ("24.9969 S 31.5923 E 545.4") or another pair.
                let sep = ns.substring(with: m[1])
                let own = sep.contains("/") ? [#"\s*/\s*"#] : sep.contains(",") || sep.contains(";") ? [] : [#"\s+"#]
                let joiners = [#"\s*[,;]\s*"#] + own
                let number = #"(?!0\d)[-+]?\.?\d+(?:[.,]\d+)?(?![\d:/]|[.,]\d|\s*\p{L}|\s?°\s?[CF](?![A-Za-z]))"#
                let word = #"(?<![\p{L}\d.,+:/-])(?<!\p{L}[ \t]{1,3})[-+]?\d+"#
                // An "o" at its end with a number after it may as well be "or" ("…, 31.4371 o 31 grados").
                if has(#"(?i)(?<![A-Z])O$"#, in: ns.substring(with: r)) && has(#"^\s*[-+]?\d"#, in: after) { continue }
                let lettered = has(#"(?i)[NSEWO]"#, in: ns.substring(with: r))
                if !lettered, let tag = tagged {
                    let signed = halves.map { ns.substring(with: $0).contains("-") }
                    if !signed.contains(true) || (signed[0] && tag.0 == "N") || (signed[1] && tag.1 == "E") { continue }
                }
                if has(word + #"(?:[.,]\d+)?\s*[,;]\s*$"#, in: before) || joiners.contains(where: { j in
                    has(word + decimal + #"\d+"# + j + "$", in: before) || (!lettered && has("^" + j + number, in: after))
                }) { continue }
                tried += 1
                guard tried <= 10 else { return .unclear }
                // A letter after a pair written with a minus sign, with more after it on the line ("…, 31.592312 W 270°",
                // "… W of the dam"), is a direction, as in the whole text (see `reading`): the pair is read without it. (Not an
                // N or E right after a number with a minus sign, which says two things.)
                var text = ns.substring(with: r)
                if text.contains("-"), has(#"(?i)[0-9°'"][ \t]?[NSEWO]$"#, in: text), has(#"^[^\n]*[\p{L}\d]"#, in: after),
                   !has(#"(?i)-[\d.,]+[°'"]?[ \t]?[NEO]$"#, in: text) {
                    text.removeLast()
                }
                guard let p = latLon(text, near: near, language: t) else { continue }
                if let f = found, !samePlace(f, p) { return .unclear }
                found = found ?? p
                read.append(r)
            }
            if let f = found {
                // Another number like these elsewhere in the text: a list, not a place ("Price list: 12.500 31.750 and
                // 45.250"; degrees with minutes or a letter after them, "25°05'S 31°26'E\n24°59'S 31°35'E"). Not a time, a
                // measurement ("±5.000 m"), a number with thousands separators ("1.234.567"), a temperature or a bearing
                // ("32°C", "270°").
                let others = matchRanges(#"(?<![\d.,:])[-+]?\d+"# + decimal + #"\d{3,}(?![\d.,]|\s*(?i:k?m|ft|mi)\b)"#
                                         + #"|\d\s?°\s?(?:\d{1,2}(?:[.,]\d+)?\s?'|(?i:[NSEWO])(?![A-Za-z]))"#, in: t)
                if others.contains(where: { o in !read.contains { NSLocationInRange(o[0].location, $0) } }) { return .unclear }
                return .place(f.result, used: read, resolution: f.resolution)
            }
        }
        return nil
    }
}
