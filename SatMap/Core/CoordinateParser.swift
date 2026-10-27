import Foundation

/// Reads a position typed or pasted in almost any common form:
/// - decimal degrees, signed or with N/S/E/W before or after ("-25.096893, 31.437121", "25.0969 S 31.4371 E");
/// - degrees and decimal minutes ("25° 05.8136' S, 31° 26.2273' E") and degrees, minutes and seconds
///   ("25°05'48.8\"S 31°26'13.6\"E"), with any of the usual symbols or just spaces;
/// - decimal commas ("-25,096893; 31,437121"), "lat"/"lon" labels (in either order), Afrikaans N/S/O/W;
/// - GPS (NMEA) style ("2505.8136,S,03126.2273,E");
/// - map links (Google Maps, Apple Maps, OpenStreetMap, Bing) and geo: links;
/// - UTM ("36J 342401 7223411"), MGRS ("36J UT 42401 23411") and plus codes ("5G6HWC3P+6R", or a short "WC3P+6R"
///   near `near`).
/// Without letters or labels the first number is the latitude, unless only the second can be one.
enum CoordinateParser {
    struct Result: Equatable {
        let point: GeoPoint
        /// The notation it was read as, to show the user.
        let format: String
    }

    static func parse(_ text: String, near: GeoPoint? = nil) -> Result? {
        let t = normalise(text)
        guard t.contains(where: \.isNumber) else { return nil }
        return fromLink(t) ?? plusCode(t, near: near) ?? mgrs(t) ?? utm(t) ?? latLon(t)
    }

    // MARK: - Normalising

    private static func normalise(_ s: String) -> String {
        var t = s
        for (from, to) in [("\u{2212}", "-"), ("\u{2013}", "-"), ("\u{2014}", "-"), ("\u{00A0}", " "), ("\u{2009}", " "),
                           ("\u{202F}", " "), ("º", "°"), ("˚", "°"), ("⁰", "°"), ("′", "'"), ("’", "'"), ("‘", "'"),
                           ("`", "'"), ("´", "'"), ("″", "\""), ("”", "\""), ("“", "\""), ("''", "\"")] {
            t = t.replacingOccurrences(of: from, with: to)
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matches(_ pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [[String?]] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        let ns = text as NSString
        return re.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { i in
                let r = m.range(at: i)
                return r.location == NSNotFound ? nil : ns.substring(with: r)
            }
        }
    }

    private static func valid(_ lat: Double, _ lon: Double) -> GeoPoint? {
        guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        return GeoPoint(lat: lat, lon: lon)
    }

    // MARK: - Map links

    private static func fromLink(_ t: String) -> Result? {
        let num = #"(-?\d{1,3}(?:\.\d+)?)"#
        // Most specific first: Google's dropped pin (!3d…!4d…), then the usual parameters and paths.
        let patterns = [
            #"!3d"# + num + #"!4d"# + num,
            #"geo:\s*"# + num + #"\s*,\s*"# + num,
            #"[?&](?:q|ll|sll|center|query|destination|daddr|saddr|near|loc|coordinates|point|address)=(?:loc:)?\s*"#
                + num + #"(?:,|%2C|\+|\s|%20)+"# + num,
            #"cp="# + num + #"~"# + num,
            #"mlat="# + num + #"&mlon="# + num,
            #"#map=\d+(?:\.\d+)?/"# + num + "/" + num,
            #"/@"# + num + "," + num,
            #"/maps/(?:place|search|dir)/"# + num + #"(?:,|%2C)\+?"# + num,
        ]
        for p in patterns {
            if let m = matches(p, in: t, options: [.caseInsensitive]).first, let a = m[1].flatMap(Double.init),
               let b = m[2].flatMap(Double.init), let point = valid(a, b) {
                return Result(point: point, format: t.lowercased().hasPrefix("geo:") ? "geo: link" : "map link")
            }
        }
        return nil
    }

    // MARK: - Plus codes (Open Location Code)

    private static let olcAlphabet = Array("23456789CFGHJMPQRVWX")

    private static func plusCode(_ t: String, near: GeoPoint?) -> Result? {
        let u = t.uppercased()
        guard let m = matches(#"(?<![0-9A-Z])([23456789CFGHJMPQRVWX0]{2,8})\+([23456789CFGHJMPQRVWX]{0,7})(?![0-9A-Z])"#,
                              in: u).first,
              let head = m[1], let tail = m[2] else { return nil }
        var code = head.replacingOccurrences(of: "0", with: "")
        guard code.count % 2 == 0 || head.count == 8 else { return nil }
        if head.count < 8 {
            // A short code: the missing first part comes from the reference point (your position).
            guard let ref = near, !head.contains("0") else { return nil }
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
        code += tail
        guard let p = olcDecode(code) else { return nil }
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

    private static func utm(_ t: String) -> Result? {
        let u = t.uppercased()
        guard let zb = matches(#"(?<![\d.])(\d{1,2})\s?([C-HJ-NP-X])(?![A-Z])"#, in: u).first,
              let zone = zb[1].flatMap(Int.init), let letter = zb[2]?.first else { return nil }
        let big = matches(#"(?<![\d.])(\d{5,8}(?:\.\d+)?)(?![\d])"#, in: u).compactMap { $0[1].flatMap(Double.init) }
        guard big.count == 2 else { return nil }
        // Easting first, unless labelled the other way round or only the first can be a northing.
        var e = big[0], n = big[1]
        // "N 7223411 E 342401": labels, not the band letter right after the zone number.
        let northLabel = u.range(of: #"(?<![\dA-Z])N(?:ORTHING)?\s*[:=]?\s*\d"#, options: .regularExpression)
        let eastLabel = u.range(of: #"(?<![\dA-Z])E(?:ASTING)?\s*[:=]?\s*\d"#, options: .regularExpression)
        let northingFirst = northLabel.map { n in eastLabel.map { n.lowerBound < $0.lowerBound } ?? true } ?? false
        if northingFirst || (e >= 1_000_000 && n < 1_000_000) { swap(&e, &n) }
        guard (100_000...900_000).contains(e), (0...10_000_000).contains(n) else { return nil }
        // The letter is a latitude band; "N"/"S" can also mean the hemisphere (used by some tools).
        if let range = bandRange(letter), let p = utmToLatLon(zone: zone, north: letter >= "N", easting: e, northing: n),
           (range.lowerBound - 1...range.upperBound + 1).contains(p.lat) {
            return Result(point: p, format: "UTM")
        }
        if letter == "N" || letter == "S", let p = utmToLatLon(zone: zone, north: letter == "N", easting: e, northing: n) {
            return Result(point: p, format: "UTM")
        }
        return nil
    }

    private static func mgrs(_ t: String) -> Result? {
        let u = t.uppercased().replacingOccurrences(of: "MGRS", with: "")
        guard let m = matches(#"^\s*(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])\s*([A-HJ-NP-V])\s*(\d{1,5})\s*(\d{0,5})\s*$"#,
                              in: u).first ?? matches(#"^\s*(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])\s*([A-HJ-NP-V])\s*(\d{2,10})()\s*$"#, in: u).first,
              let zone = m[1].flatMap(Int.init), (1...60).contains(zone), let band = m[2]?.first,
              let col = m[3]?.first, let row = m[4]?.first, var es = m[5], var ns = m[6] else { return nil }
        if ns.isEmpty {
            guard es.count % 2 == 0 else { return nil }
            ns = String(es.suffix(es.count / 2))
            es = String(es.prefix(es.count / 2))
        }
        guard es.count == ns.count, let ev = Double(es), let nv = Double(ns) else { return nil }
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
                return Result(point: p, format: "MGRS")
            }
        }
        return nil
    }

    // MARK: - Latitude and longitude

    private enum Axis { case lat, lon }

    private enum Token {
        case number(value: Double, intDigits: Int, negative: Bool)
        case degree, minute, second
        case hemisphere(Character)
        case separator
        case latLabel, lonLabel
    }

    private static func latLon(_ t: String) -> Result? {
        // Decimal commas only when there are no decimal points; if that reading fails, commas separate.
        let commaDecimal = !t.contains(".") && !matches(#"\d,\d"#, in: t).isEmpty
        return latLon(t, commaDecimal: commaDecimal) ?? (commaDecimal ? latLon(t, commaDecimal: false) : nil)
    }

    private static func latLon(_ text: String, commaDecimal: Bool) -> Result? {
        var s = " " + text.uppercased() + " "
        // Words for labels and directions (English and Afrikaans), and letter units.
        let words: [(String, String)] = [
            (#"\b(LATITUDE|LAT|BREEDTEGRAAD|BREEDTE)\b"#, " \u{24D0} "), (#"\b(LONGITUDE|LONG|LON|LNG|LENGTEGRAAD|LENGTE)\b"#, " \u{24DE} "),
            (#"\b(NORTH|NOORD)\b"#, " N "), (#"\b(SOUTH|SUID)\b"#, " S "), (#"\b(EAST|OOS|O)\b"#, " E "), (#"\b(WEST|WES)\b"#, " W "),
            (#"\b(DEGREES|DEGREE|DEG|GRADE)\b"#, "°"), (#"\b(MINUTES|MINUTE|MIN)\b"#, "'"), (#"\b(SECONDS|SECOND|SEC)\b"#, "\""),
            (#"(\d)\s*D(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1°"), (#"(\d)\s*M(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1'"),
        ]
        for (p, r) in words {
            s = s.replacingOccurrences(of: p, with: r, options: .regularExpression)
        }

        var tokens: [Token] = []
        let chars = Array(s)
        var i = 0
        var pendingNegative = false
        func isLetter(_ c: Character?) -> Bool { c.map { $0.isLetter && $0 != "\u{24D0}" && $0 != "\u{24DE}" } ?? false }
        while i < chars.count {
            let c = chars[i]
            if c.isNumber || (c == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
                // Glued to a letter other than N/S/E/W (as in a plus code or a word): not a coordinate.
                if i > 0, isLetter(chars[i - 1]), !"NSEW".contains(chars[i - 1]) { return nil }
                var digits = "", intDigits = 0, seenPoint = false
                while i < chars.count {
                    let d = chars[i]
                    if d.isNumber {
                        digits.append(d)
                        if !seenPoint { intDigits += 1 }
                    } else if !seenPoint && (d == "." || (commaDecimal && d == ",")) && i + 1 < chars.count && chars[i + 1].isNumber {
                        digits.append(".")
                        seenPoint = true
                    } else {
                        break
                    }
                    i += 1
                }
                guard let v = Double(digits) else { return nil }
                if i < chars.count, isLetter(chars[i]), !"NSEW".contains(chars[i]) { return nil }
                tokens.append(.number(value: v, intDigits: intDigits, negative: pendingNegative))
                pendingNegative = false
                continue
            }
            switch c {
            case "-": pendingNegative = true
            case "+": pendingNegative = false
            case "°": tokens.append(.degree)
            case "'": tokens.append(.minute)
            case "\"": tokens.append(.second)
            case ",", ";", "/", "|", "\n", "\t": tokens.append(.separator)
            case "\u{24D0}": tokens.append(.latLabel)
            case "\u{24DE}": tokens.append(.lonLabel)
            case "N", "S", "E", "W":
                if !isLetter(i > 0 ? chars[i - 1] : nil) && !isLetter(i + 1 < chars.count ? chars[i + 1] : nil) {
                    tokens.append(.hemisphere(c))
                }
            default: break
            }
            if c != "-" && c != "+" && c != " " { pendingNegative = false }
            i += 1
        }

        // Split into the two halves.
        var parts: [[Token]] = []
        let hemis = tokens.indices.filter { if case .hemisphere = tokens[$0] { return true } else { return false } }
        let numberCount = tokens.filter { if case .number = $0 { return true } else { return false } }.count
        guard numberCount >= 2 else { return nil }
        if hemis.count == 2 {
            let firstNumber = tokens.firstIndex { if case .number = $0 { return true } else { return false } } ?? 0
            if hemis[0] < firstNumber {
                parts = [Array(tokens[hemis[0]..<hemis[1]]), Array(tokens[hemis[1]...])] // N 25 … E 31 …
            } else {
                parts = [Array(tokens[...hemis[0]]), Array(tokens[(hemis[0] + 1)...])] // 25 … S 31 … E
            }
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
            parts = [Array(tokens[..<cut]), Array(tokens[cut...])]
        } else {
            return nil
        }

        // Each half to a signed value, and which axis it is (from its letter or label), if known.
        var values: [(value: Double, axis: Axis?, kind: Int)] = []
        for part in parts {
            guard let v = component(part) else { return nil }
            values.append(v)
        }
        guard values.count == 2 else { return nil }
        var lat = values[0].value, lon = values[1].value
        switch (values[0].axis, values[1].axis) {
        case let (a?, b?):
            guard a != b else { return nil }
            if a == .lon { swap(&lat, &lon) }
        case let (a?, nil):
            if a == .lon { swap(&lat, &lon) }
        case let (nil, b?):
            if b == .lat { swap(&lat, &lon) }
        case (nil, nil):
            let labels = tokens.compactMap { tok -> Axis? in
                if case .latLabel = tok { return .lat }
                if case .lonLabel = tok { return .lon }
                return nil
            }
            if labels.first == .lon || (abs(lat) > 90 && abs(lon) <= 90) { swap(&lat, &lon) }
        }
        guard let point = valid(lat, lon) else { return nil }
        let kind = max(values[0].kind, values[1].kind)
        return Result(point: point, format: ["decimal degrees", "degrees and decimal minutes",
                                             "degrees, minutes and seconds", "GPS (NMEA)"][kind])
    }

    private static func isLabel(_ t: Token) -> Bool {
        switch t {
        case .latLabel, .lonLabel: return true
        default: return false
        }
    }

    private static func isNumber(_ t: Token) -> Bool {
        if case .number = t { return true }
        return false
    }

    /// At a separator with numbers on both sides (a comma inside "25,5" was read as a decimal comma already).
    private static func splitAtSeparator(_ tokens: [Token]) -> [[Token]]? {
        for (k, tok) in tokens.enumerated() {
            guard case .separator = tok else { continue }
            if tokens[..<k].contains(where: isNumber) && tokens[(k + 1)...].contains(where: isNumber) {
                return [Array(tokens[..<k]), Array(tokens[(k + 1)...])]
            }
        }
        return nil
    }

    /// Before the second number marked with a degree sign.
    private static func splitAtDegrees(_ tokens: [Token]) -> [[Token]]? {
        var marked: [Int] = []
        for k in tokens.indices.dropLast() {
            if isNumber(tokens[k]), case .degree = tokens[k + 1] { marked.append(k) }
        }
        guard marked.count == 2 else { return nil }
        var cut = marked[1]
        // Keep a label just before it with the second half.
        while cut > 0, isLabel(tokens[cut - 1]) { cut -= 1 }
        return [Array(tokens[..<cut]), Array(tokens[cut...])]
    }

    /// One half: its value with sign, its axis ("LAT"/"LON" from N/S/E/W or a label), and its kind (0 decimal degrees,
    /// 1 decimal minutes, 2 seconds, 3 NMEA).
    private static func component(_ part: [Token]) -> (value: Double, axis: Axis?, kind: Int)? {
        var nums: [(value: Double, intDigits: Int, negative: Bool, unit: Int?)] = []
        var hemi: Character?
        var label: Axis?
        for (k, tok) in part.enumerated() {
            switch tok {
            case let .number(v, digits, neg):
                var unit: Int?
                if k + 1 < part.count {
                    switch part[k + 1] {
                    case .degree: unit = 0
                    case .minute: unit = 1
                    case .second: unit = 2
                    default: break
                    }
                }
                nums.append((v, digits, neg, unit))
            case let .hemisphere(h): hemi = h
            case .latLabel: if nums.isEmpty { label = .lat }
            case .lonLabel: if nums.isEmpty { label = .lon }
            default: break
            }
        }
        guard (1...3).contains(nums.count) else { return nil }
        var d = 0.0, m = 0.0, sec = 0.0
        var kind = nums.count - 1
        if nums.count == 1, nums[0].unit == nil, nums[0].value > 180, nums[0].intDigits >= 4 {
            // GPS (NMEA) DDMM.mmmm or DDDMM.mmmm, and DDMMSS(.s).
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
        } else {
            for (k, n) in nums.enumerated() {
                switch n.unit ?? k {
                case 0: d = n.value
                case 1: m = n.value
                default: sec = n.value
                }
            }
            if nums.contains(where: { $0.unit == 2 }) { kind = 2 } else if nums.contains(where: { $0.unit == 1 }) { kind = max(kind, 1) }
        }
        guard m < 60, sec < 60 else { return nil }
        if (m > 0 || sec > 0) && d != d.rounded() { return nil } // 25.5° 30' isn't a position
        var value = d + m / 60 + sec / 3600
        let negative = nums.contains { $0.negative } || hemi == "S" || hemi == "W"
        if negative { value = -value }
        var axis = label
        if let h = hemi { axis = (h == "N" || h == "S") ? .lat : .lon }
        return (value, axis, kind)
    }
}
