import Foundation

/// Reads a position typed or pasted in almost any common form:
/// - decimal degrees, signed or with N/S/E/W before or after ("-25.096893, 31.437121", "25.0969 S 31.4371 E");
/// - degrees and decimal minutes ("25° 05.8136' S, 31° 26.2273' E") and degrees, minutes and seconds
///   ("25°05'48.8\"S 31°26'13.6\"E", "25-05-48.8S 031-26-13.6E"), with any of the usual symbols or just spaces;
/// - decimal commas ("-25,096893; 31,437121"), labels ("lat"/"lon" in either order, "X"/"Y", a "Lat/Long:" heading or a
///   spreadsheet's header row), directions in English and Afrikaans, and a lone O for east or west (see `latLon`);
///   altitude, accuracy and the like next to the position are left out;
/// - GPS (NMEA) fields or whole sentences ("2505.8136,S,03126.2273,E", "$GPGGA,…"), aviation style ("0536.22N 00011.22W")
///   and ISO 6709 ("-2505.8136+03126.2273/", "+40.6892-074.0445+545.400/");
/// - map links (Google Maps, Apple Maps, OpenStreetMap, OsmAnd, Bing), geo: links and Google navigation links;
/// - GeoJSON, KML and WKT ("POINT (31.437121 -25.096893)"), which put the longitude first, and GPX or JSON values;
/// - UTM ("36J 342401 7223411", "30 N 699568 5709428", "UTM 36 South 342402 7223412"), MGRS ("36J UT 42401 23411",
///   "36JUT42402341") and plus codes ("5G6HWC3P+6R", or a short "WC3P+6R" near `near`);
/// - a position inside other text, as in a chat message ("[12:31, 27/10/2026] Jaco: Lions on H4-1 at -25.0969, 31.4371").
/// Without letters or labels the first number is the latitude, unless only the second can be one.
/// A text that could be two different places, with nothing in it to say which, isn't read (nil). Two exceptions say in
/// the format how they were read: a lone "O" (east or west) and a directions link to a place by name (the map's centre).
enum CoordinateParser {
    struct Result: Equatable {
        let point: GeoPoint
        /// The notation it was read as, to show the user.
        let format: String
    }

    static func parse(_ text: String, near: GeoPoint? = nil) -> Result? {
        let t = normalise(text)
        guard t.contains(where: \.isNumber) else { return nil }
        if let r = fromLink(t, near: near) ?? plusCode(t, near: near) { return r }
        // The numbers in a link that couldn't be read (a short link, a place by name) don't say where.
        let rest = withoutLinks(t)
        let readers: [(String) -> Result?] = [lonFirst, nmea, iso6709, mgrs, utm, { latLon($0, near: near) },
                                              { inText($0, near: near) }]
        return readers.lazy.compactMap { $0(rest) }.first
    }

    // MARK: - Normalising

    private static func normalise(_ s: String) -> String {
        var t = s
        // Links come percent-encoded ("%2C", "%C2%B0", "%2B" in a plus code).
        if t.range(of: "%[0-9A-Fa-f]{2}", options: .regularExpression) != nil, let decoded = t.removingPercentEncoding {
            t = decoded
        }
        let replacements: [(String, String)] = [
            ("\u{2212}", "-"), ("\u{2013}", "-"), ("\u{2014}", "-"), ("\u{00A0}", " "), ("\u{2009}", " "), ("\u{202F}", " "),
            ("\u{FEFF}", ""), ("\u{200B}", ""), ("\u{200E}", ""), ("\u{200F}", ""), ("\r\n", "\n"), ("\r", "\n"), ("º", "°"),
            ("˚", "°"), ("⁰", "°"), ("′", "'"), ("’", "'"), ("‘", "'"), ("`", "'"), ("´", "'"), ("″", "\""), ("”", "\""),
            ("“", "\""), ("''", "\""),
        ]
        for (from, to) in replacements {
            t = t.replacingOccurrences(of: from, with: to)
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matches(_ pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [[String?]] {
        let ns = text as NSString
        return matchRanges(pattern, in: text, options: options).map { m in
            m.map { r in r.location == NSNotFound ? nil : ns.substring(with: r) }
        }
    }

    private static func matchRanges(_ pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [[NSRange]] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        return re.matches(in: text, range: NSRange(location: 0, length: (text as NSString).length)).map { m in
            (0..<m.numberOfRanges).map { m.range(at: $0) }
        }
    }

    private static func valid(_ lat: Double, _ lon: Double) -> GeoPoint? {
        guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        return GeoPoint(lat: lat, lon: lon)
    }

    // MARK: - Map links

    private static func fromLink(_ t: String, near: GeoPoint?) -> Result? {
        let num = #"([-+]?\d{1,3}(?:\.\d+)?)(?!\d)"#
        // A parameter whose whole value is "lat,lon" ("lat+lon", "lat lon" once decoded), maybe after "loc:" (Google) or
        // "ll." (Waze); "google.navigation:q=" too.
        func param(_ names: String) -> String {
            #"(?:[?&]|navigation:)(?:"# + names + #")=(?:loc:|ll\.)?\s*"# + num + #"(?:,|%2C|\+|\s|%20)+"# + num
                + #"(?!\w|\.\d)"#
        }
        func find(_ pattern: String, lonFirst: Bool = false) -> GeoPoint? {
            for m in matches(pattern, in: t, options: [.caseInsensitive]) {
                if let a = m[1].flatMap(Double.init), let b = m[2].flatMap(Double.init),
                   let p = lonFirst ? valid(b, a) : valid(a, b) { return p }
            }
            return nil
        }
        let link = { (p: GeoPoint) in Result(point: p, format: t.lowercased().hasPrefix("geo:") ? "geo: link" : "map link") }

        // Directions: where they go to, not where they start.
        if let p = find(param("daddr|destination")) { return link(p) }
        if let m = matches(#"/maps/dir/([^?#]*)"#, in: t, options: [.caseInsensitive]).first, let path = m[1] {
            // "/maps/dir/<from>/<to>/@<map centre>/data=…": the last stop, from the path or else from the stops' positions
            // in the data (!1d lon !2d lat or !3d lat !4d lon each). Without either, the map's centre is somewhere along
            // the way, and the format says so.
            let stops = path.components(separatedBy: "/").prefix { !$0.hasPrefix("@") && !$0.hasPrefix("data=") }
            if let last = stops.last(where: { !$0.isEmpty }), let p = pathPoint(last) { return link(p) }
            let positions: String = "!1d" + num + "!2d" + num + "|!3d" + num + "!4d" + num
            let data = matches(positions, in: t).compactMap { m -> GeoPoint? in
                if let lon = m[1].flatMap(Double.init), let lat = m[2].flatMap(Double.init) { return valid(lat, lon) }
                if let lat = m[3].flatMap(Double.init), let lon = m[4].flatMap(Double.init) { return valid(lat, lon) }
                return nil
            }
            if let p = data.last { return link(p) }
            return find("/@" + num + "," + num).map { Result(point: $0, format: "map link (centre of the route)") }
        }
        // Most specific first: Google's dropped pin (!3d…!4d…) and an embedded Google map (!2d lon !3d lat).
        if let p = find("!3d" + num + "!4d" + num) { return link(p) }
        if let p = find("!2d" + num + "!3d" + num, lonFirst: true) { return link(p) }
        // The place: a pin or search, OpenStreetMap's marker, OsmAnd's lat=…&lon=….
        let place: [String] = [param("q|query|pin|point|loc|address"), "[?&]m?lat=" + num + "&m?(?:lon|lng)=" + num]
        if let p = place.lazy.compactMap({ find($0) }).first { return link(p) }
        // "/maps/place/<lat,lon, DMS or plus code>/@<map centre>": the place, not where the map was centred.
        if let m = matches(#"/maps/(?:place|search)/([^/?#@]+)"#, in: t, options: [.caseInsensitive]).first, let part = m[1] {
            if let p = pathPoint(part) { return link(p) }
            if let r = plusCode(part, near: near) { return r }
        }
        // geo:lat,lon, unless it is geo:0,0, which Android writes when only q= says where (and q= had no position).
        let geo: String = #"geo:\s*"# + num + #"\s*,\s*"# + num
        if let m = matches(geo, in: t, options: [.caseInsensitive]).first, let a = m[1].flatMap(Double.init),
           let b = m[2].flatMap(Double.init), a != 0 || b != 0, let p = valid(a, b) {
            return link(p)
        }
        // ll= (Apple's pin, Google's map centre), Apple's coordinate= and Street View's viewpoint=, then the map's centre,
        // and last where directions start from.
        let rest: [String] = [param("ll"), param("coordinates?|viewpoint"), param("sll|center|near"), "cp=" + num + "~" + num,
                              #"#(?:map=)?\d{1,2}(?:\.\d+)?/"# + num + "/" + num, "/@" + num + "," + num, param("saddr|source")]
        return rest.lazy.compactMap({ find($0) }).first.map(link)
    }

    /// A position as part of a link's path: "-25.096893,31.437121", "-25.096893+31.437121" or "25°05'48.8"S+31°26'13.6"E".
    private static func pathPoint(_ part: String) -> GeoPoint? {
        let s = part.replacingOccurrences(of: "+", with: " ")
        guard s.range(of: #"^[-\d.,;°'"\sNSEWnsew]+$"#, options: .regularExpression) != nil else { return nil }
        return latLon(s, near: nil)?.point
    }

    /// The text without links (the ones `fromLink` couldn't read).
    private static func withoutLinks(_ t: String) -> String {
        // Each starts where a word starts (the look-behind), so a long run of characters isn't tried from every place in it.
        t.replacingOccurrences(of: #"(?i)(?<![a-z0-9+.-])(?:[a-z][a-z0-9+.-]*+://|www\.|geo:|google\.navigation:)\S*"#
                                   + #"|(?<![a-z0-9.-])(?:[a-z0-9-]++\.)++[a-z]{2,}/\S*"#,
                               with: " ", options: .regularExpression)
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
            // A place name after it ("WC3P+6R Skukuza") means the code was shortened against that place, which can't be
            // looked up offline. Google shortens a code like this only within 0.3 of a grid square of the place (0.3° when
            // 4 characters are left out), so it is read near you only when you are that close to it too; further away the
            // same short code could be meant for a square next to yours.
            if let r = u.range(of: m[0] ?? ""), u[r.upperBound...].contains(where: \.isLetter),
               max(abs(p.lat - ref.lat), abs((p.lon - ref.lon).remainder(dividingBy: 360))) >= 0.3 * resolution {
                return nil
            }
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

    // MARK: - GeoJSON, KML and WKT, NMEA sentences, ISO 6709

    /// Formats that always put the longitude first: GeoJSON ("coordinates": [lon, lat, alt]), KML (<coordinates>lon,lat,alt)
    /// and WKT ("POINT (lon lat)", "SRID=4326;POINT Z (lon lat alt)").
    private static func lonFirst(_ t: String) -> Result? {
        let num = #"([-+]?\d+(?:\.\d+)?)"#
        let wkt: String = #"\b(?:POINT|Point)\s*(?:ZM|Z|M)?\s*\(\s*"# + num + #"\s+"# + num
            + #"(?:\s+[-+]?\d+(?:\.\d+)?){0,2}\s*\)"#
        let formats: [(String, String)] = [
            (#""coordinates"\s*:\s*\[\s*"# + num + #"\s*,\s*"# + num, "GeoJSON"),
            (#"<coordinates>\s*"# + num + #"\s*,\s*"# + num, "KML"), (#"<gx:coord>\s*"# + num + #"\s+"# + num, "KML"),
            (wkt, "WKT"),
        ]
        for (p, format) in formats {
            if let m = matches(p, in: t).first, let lon = m[1].flatMap(Double.init),
               let lat = m[2].flatMap(Double.init), let point = valid(lat, lon) {
                return Result(point: point, format: format)
            }
        }
        return nil
    }

    /// GPS (NMEA) fields, alone or in a whole sentence ($GPGGA, $GPRMC, $GPGLL…): ddmm.mmmm,N/S,dddmm.mmmm,E/W.
    private static func nmea(_ t: String) -> Result? {
        guard let m = matches(#"(?<![\d.])(\d{2})(\d{2}(?:\.\d+)?),\s*([NS]),\s*(\d{3})(\d{2}(?:\.\d+)?),\s*([EW])(?![A-Z])"#,
                              in: t, options: [.caseInsensitive]).first,
              let latD = m[1].flatMap(Double.init), let latM = m[2].flatMap(Double.init), latM < 60,
              let lonD = m[4].flatMap(Double.init), let lonM = m[5].flatMap(Double.init), lonM < 60 else { return nil }
        let lat = (latD + latM / 60) * (m[3]?.uppercased() == "S" ? -1 : 1)
        let lon = (lonD + lonM / 60) * (m[6]?.uppercased() == "W" ? -1 : 1)
        return valid(lat, lon).map { Result(point: $0, format: "GPS (NMEA)") }
    }

    /// ISO 6709, as in photo and video metadata and the time zone database: "+40.6892-074.0445+545.400/",
    /// "-2505.8136+03126.2273/", "-0117+03649". The latitude has 2 digits of degrees and the longitude 3, then 2 of
    /// minutes and 2 of seconds if there are more; an altitude and "CRS…" after them are left out.
    private static func iso6709(_ t: String) -> Result? {
        let lat = #"([+-])(\d{2})(\d{2})?(\d{2})?(\.\d+)?"#, lon = #"([+-])(\d{3})(\d{2})?(\d{2})?(\.\d+)?"#
        let pattern: String = "^" + lat + lon + #"(?:[+-]\d+(?:\.\d+)?)?(?:CRS[A-Z0-9_:]+)?/?$"#
        guard let m = matches(pattern, in: t, options: [.caseInsensitive]).first else { return nil }
        func value(_ i: Int) -> Double? {
            var fields = m[(i + 1)...(i + 3)].compactMap { $0.flatMap(Double.init) } // degrees, minutes, seconds
            fields[fields.count - 1] += m[i + 4].flatMap { Double("0" + $0) } ?? 0
            guard fields.dropFirst().allSatisfy({ $0 < 60 }) else { return nil }
            let v = fields.enumerated().reduce(0) { $0 + $1.element / pow(60, Double($1.offset)) }
            return m[i] == "-" ? -v : v
        }
        guard let la = value(1), let lo = value(6), let p = valid(la, lo) else { return nil }
        return Result(point: p, format: "ISO 6709")
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
        // The zone and a band letter, or the hemisphere in words ("UTM 36 South …").
        let spelled = matches(#"(?<![\d.])(\d{1,2})\s*(NORTH|SOUTH|NOORD|SUID)(?![A-Z])"#, in: u).first
        guard let zb = spelled ?? matches(#"(?<![\d.])(\d{1,2})\s?([C-HJ-NP-X])(?![A-Z])"#, in: u).first,
              let zone = zb[1].flatMap(Int.init), let letter = zb[2]?.first, let zoneText = zb[0],
              let zoneRange = u.range(of: zoneText) else { return nil }
        // The rest, so that the "N" of "30 N 699568 5709428" isn't taken for a northing label below.
        let rest = u.replacingCharacters(in: zoneRange, with: " ")
        let big = matches(#"(?<![\d.])(\d{5,8}(?:\.\d+)?)(?![\d])"#, in: rest).compactMap { $0[1].flatMap(Double.init) }
        guard big.count == 2 else { return nil }
        // Easting first, unless labelled the other way round or only the first can be a northing.
        var e = big[0], n = big[1]
        // "N 7223411 E 342401": labels, not the band letter right after the zone number.
        let northLabel = rest.range(of: #"(?<![\dA-Z])N(?:ORTHING)?\s*[:=]?\s*\d"#, options: .regularExpression)
        let eastLabel = rest.range(of: #"(?<![\dA-Z])E(?:ASTING)?\s*[:=]?\s*\d"#, options: .regularExpression)
        let northingFirst = northLabel.map { n in eastLabel.map { n.lowerBound < $0.lowerBound } ?? true } ?? false
        if northingFirst || (e >= 1_000_000 && n < 1_000_000) { swap(&e, &n) }
        guard (100_000...900_000).contains(e), (0...10_000_000).contains(n) else { return nil }
        if spelled != nil {
            return utmToLatLon(zone: zone, north: letter == "N", easting: e, northing: n)
                .map { Result(point: $0, format: "UTM") }
        }
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
        let u = t.uppercased()
        // Anywhere in the text ("MGRS: 36J UT 42401 23411", "36J UT 42401 23411 (MGRS)") that has no other numbers. The
        // digits are the easting and northing (in metres, maybe with an "m"), in two groups or one run split in half
        // ("36JUT42402341").
        let pattern = #"(?<![A-Z0-9])(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])\s*([A-HJ-NP-V])\s*(\d{1,10})M?(?:\s+(\d{1,5})M?)?(?![A-Z0-9])"#
        guard let m = matches(pattern, in: u).first, let all = m[0],
              !u.replacingOccurrences(of: all, with: " ").contains(where: \.isNumber),
              let zone = m[1].flatMap(Int.init), (1...60).contains(zone), let band = m[2]?.first,
              let col = m[3]?.first, let row = m[4]?.first, var es = m[5] else { return nil }
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
        /// "lat", "lon", or (nil) altitude, accuracy and the like.
        case label(Axis?)
    }

    /// Symbols the words are replaced with before reading: a latitude, longitude and other label.
    private static let latSymbol: Character = "\u{24D0}", lonSymbol: Character = "\u{24DE}",
                       otherSymbol: Character = "\u{24E9}"

    private static func latLon(_ t: String, near: GeoPoint?) -> Result? {
        // "51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W" (Wikipedia): the same place written in more than one way.
        // The parts that read on their own must all agree.
        let pieces = t.replacingOccurrences(of: #"\s+/\s+"#, with: "\u{1}", options: .regularExpression)
            .components(separatedBy: "\u{1}")
        let read = (2...4).contains(pieces.count) ? pieces.compactMap { latLonOnce($0, near: near) } : []
        if read.count > 1 {
            return read.allSatisfy({ Geo.distance($0.point, read[0].point) < 30 }) ? read[0] : nil
        }
        return latLonOnce(t, near: near)
    }

    private static func latLonOnce(_ t: String, near: GeoPoint?) -> Result? {
        // Decimal commas only when there are no decimal points; if that reading fails, commas separate.
        let commaDecimal = !t.contains(".") && !matches(#"\d,\d"#, in: t).isEmpty
        return latLon(t, commaDecimal: commaDecimal, near: near)
            ?? (commaDecimal ? latLon(t, commaDecimal: false, near: near) : nil)
    }

    private static func latLon(_ text: String, commaDecimal: Bool, near: GeoPoint?) -> Result? {
        let s = prepared(text)
        // A lone O is east in Afrikaans, Dutch and German (Oos, Oost, Ost) but west in French, Spanish, Italian and
        // Portuguese (Ouest, Oeste, Ovest), and all of them write decimal commas, so the text can't tell. It is read as
        // east, as this app's users write it in Afrikaans, unless the west reading is under half as far from you; the
        // format says which way it was read.
        let lone = #"(?<![A-Z])O(?![A-Z'])"# // not the O of "O'Brien"
        guard s.range(of: lone, options: .regularExpression) != nil else { return read(s, commaDecimal: commaDecimal) }
        func readAs(_ letter: String) -> Result? {
            read(s.replacingOccurrences(of: lone, with: " \(letter) ", options: .regularExpression), commaDecimal: commaDecimal)
        }
        let east = readAs("E"), west = readAs("W")
        var isWest = east == nil
        if let e = east, let w = west, let ref = near {
            isWest = Geo.distance(ref, w.point) < Geo.distance(ref, e.point) / 2
        }
        return (isWest ? west : east).map { Result(point: $0.point, format: $0.format + (isWest ? " (O = west)" : " (O = east)")) }
    }

    /// Upper case, with labels, directions and units as single symbols, and without the words that aren't part of the
    /// position.
    private static func prepared(_ text: String) -> String {
        var s = " " + text.uppercased() + " "
        let words: [(String, String)] = [
            // Quotes around a value, as in JSON, CSV and GPX ("lat":"-25.0969", lat="-25.0969"): not minutes or seconds.
            (#"(?<=[\s=:,;\[({])(["'])\s*([-+]?\d+(?:[.,]\d+)?)\s*\1(?=[\s,;:)}\]/>])"#, " $2 "),
            (#"[-+]?\d+(?:[.,]\d+)?\s*M\s+(?:ABOVE|BELOW)\s+SEA\s+LEVEL"#, " "),
            // Names with digits in them ("CLLocationCoordinate2D"): not numbers.
            (#"(?<![A-Z0-9])[A-Z]{2,}\d[A-Z0-9_]*"#, " "),
            (#"\b(LATITUDE|LAT|BREEDTEGRAAD|BREEDTE)\b"#, " \(latSymbol) "),
            (#"\b(LONGITUDE|LONG|LON|LNG|LENGTEGRAAD|LENGTE)\b"#, " \(lonSymbol) "),
            (#"(?<![A-Z])(ALTITUDE|ALT|ELEVATION|ELEV|ELE|HEIGHT|HOOGTE|ACCURACY|ACC|HDOP|SPEED|HEADING|BEARING|COURSE)(?![A-Z])"#,
             " \(otherSymbol) "),
            (#"\b(NORTH|NOORD)\b"#, " N "), (#"\b(SOUTH|SUID)\b"#, " S "), (#"\b(EAST|OOS)\b"#, " E "), (#"\b(WEST|WES)\b"#, " W "),
            (#"\b(DEGREES|DEGREE|DEG|GRADE)\b"#, "°"), (#"\b(MINUTES|MINUTE|MIN)\b"#, "'"), (#"\b(SECONDS|SECOND|SEC)\b"#, "\""),
            (#"(\d)\s*D(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1°"), (#"(\d)\s*M(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1'"),
        ]
        for (p, r) in words {
            s = s.replacingOccurrences(of: p, with: r, options: .regularExpression)
        }
        // X and Y as GIS writes them in degrees: X the longitude, Y the latitude. Only together, so a lone "x" isn't one.
        let xy = [(#"(?<![A-Z0-9_.])X(?![A-Z0-9_])"#, lonSymbol), (#"(?<![A-Z0-9_.])Y(?![A-Z0-9_])"#, latSymbol)]
        if xy.allSatisfy({ s.range(of: $0.0, options: .regularExpression) != nil }) {
            for (p, symbol) in xy {
                s = s.replacingOccurrences(of: p, with: " \(symbol) ", options: .regularExpression)
            }
        }
        return s
    }

    private static func read(_ s: String, commaDecimal: Bool) -> Result? {
        var tokens: [Token] = []
        let chars = Array(s)
        var i = 0
        var pendingNegative = false
        func isLetter(_ c: Character?) -> Bool {
            c.map { $0.isLetter && $0 != latSymbol && $0 != lonSymbol && $0 != otherSymbol } ?? false
        }
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
            // Between whole degrees or minutes and 2 digits ("25-05-48.8S", "031-26-13.6E") a dash separates the fields;
            // elsewhere it is a minus.
            case "-": if !fieldDash(chars, i) { pendingNegative = true }
            case "+": pendingNegative = false
            case "°": tokens.append(.degree)
            case "'": tokens.append(.minute)
            case "\"": tokens.append(.second)
            case ",", ";", "/", "|", "\n", "\t": tokens.append(.separator)
            case latSymbol: tokens.append(.label(.lat))
            case lonSymbol: tokens.append(.label(.lon))
            case otherSymbol: tokens.append(.label(nil))
            case "N", "S", "E", "W":
                if !isLetter(i > 0 ? chars[i - 1] : nil) && !isLetter(i + 1 < chars.count ? chars[i + 1] : nil) {
                    tokens.append(.hemisphere(c))
                }
            default: break
            }
            if c != "-" && c != "+" && c != " " { pendingNegative = false }
            i += 1
        }

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
        // Altitude, accuracy and the like, with their values: not part of the position.
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
                if end < tokens.count, isMark(tokens[end]) { end += 1 } // its unit, "312 m" read as minutes
            } else {
                end = k + 1
            }
            tokens.removeSubrange(k..<end)
        }

        // Split into the two halves.
        var parts: [[Token]] = []
        let hemis = tokens.indices.filter { if case .hemisphere = tokens[$0] { return true } else { return false } }
        let numberCount = tokens.filter(isNumber).count
        guard numberCount >= 2 else { return nil }
        if hemis.count == 2 {
            let firstNumber = tokens.firstIndex(where: isNumber) ?? 0
            if hemis[0] < firstNumber {
                parts = [Array(tokens[hemis[0]..<hemis[1]]), Array(tokens[hemis[1]...])] // N 25 … E 31 …
            } else {
                // 25 … S 31 … E; after a separator past the second letter ("…E, 545.4 m") nothing belongs to it.
                let cut = hemis[1] + 1 < tokens.count && isSeparator(tokens[hemis[1] + 1])
                parts = [Array(tokens[...hemis[0]]), Array(tokens[(hemis[0] + 1)...(cut ? hemis[1] : tokens.count - 1)])]
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
        var values: [(value: Double, axis: Axis?, kind: Int, numbers: Int, marked: Bool)] = []
        for part in parts {
            guard let v = component(part) else { return nil }
            values.append(v)
        }
        guard values.count == 2 else { return nil }
        // "Spot 1: 51.5007, -0.1246": halves with different numbers of values, without unit marks to say which is which,
        // have a stray number in them (it isn't 1° 51.5007').
        if values[0].numbers != values[1].numbers && !(values[0].marked && values[1].marked) { return nil }
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
                if case let .label(a) = tok { return a }
                return nil
            }
            if (headingFirst ?? labels.first) == .lon || (abs(lat) > 90 && abs(lon) <= 90) { swap(&lat, &lon) }
        }
        guard let point = valid(lat, lon) else { return nil }
        let kind = max(values[0].kind, values[1].kind)
        return Result(point: point, format: ["decimal degrees", "degrees and decimal minutes",
                                             "degrees, minutes and seconds", "GPS (NMEA)"][kind])
    }

    /// Whether the dash at `i` is between whole degrees or minutes (1 to 3 digits) and 2 digits, as in "25-05-48.8".
    private static func fieldDash(_ chars: [Character], _ i: Int) -> Bool {
        var k = i - 1
        while k >= 0, chars[k].isNumber { k -= 1 }
        guard (1...3).contains(i - 1 - k), k < 0 || (chars[k] != "." && chars[k] != ",") else { return false }
        guard i + 2 < chars.count, chars[i + 1].isNumber, chars[i + 2].isNumber else { return false }
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

    private static func isSeparator(_ t: Token) -> Bool {
        if case .separator = t { return true }
        return false
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

    /// One half: its value with sign, its axis ("LAT"/"LON" from N/S/E/W or a label), its kind (0 decimal degrees,
    /// 1 decimal minutes, 2 seconds, 3 NMEA), how many numbers it has and whether they all have a unit mark.
    private static func component(_ part: [Token]) -> (value: Double, axis: Axis?, kind: Int, numbers: Int, marked: Bool)? {
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
            case let .label(a?): if nums.isEmpty { label = a }
            default: break
            }
        }
        guard (1...3).contains(nums.count) else { return nil }
        // Only the first number has a sign: "14 -25.1" is a stray number before a position, not 14° 25.1'.
        guard !nums.dropFirst().contains(where: { $0.negative }) else { return nil }
        // Without a degree sign, a ' or " after a number that can't be minutes or seconds is a quote ('-25.0969').
        if !nums.contains(where: { $0.unit == 0 }) {
            for k in nums.indices where (nums[k].unit ?? 0) > nums.count - 1 { nums[k].unit = nil }
        }
        var d = 0.0, m = 0.0, sec = 0.0
        var kind = nums.count - 1
        if nums.count == 1, nums[0].unit == nil, nums[0].intDigits >= 4 {
            // GPS (NMEA) DDMM.mmmm or DDDMM.mmmm, and DDMMSS(.s): 4 digits or more before the point, leading zeros too
            // ("00007.4760" is 0° 07.476'), can't be whole degrees.
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
        // Only the last number has a fraction: 25.5° 30' isn't a position, nor is "31.4371, -25.0969, 0" (lon, lat and
        // altitude, or lat, lon and altitude: it can't be told which).
        if nums.dropLast().contains(where: { $0.value != $0.value.rounded() }) { return nil }
        var value = d + m / 60 + sec / 3600
        let negative = nums[0].negative || hemi == "S" || hemi == "W"
        if negative { value = -value }
        var axis = label
        if let h = hemi { axis = (h == "N" || h == "S") ? .lat : .lon }
        return (value, axis, kind, nums.count, nums.allSatisfy { $0.unit != nil })
    }

    // MARK: - A position inside other text

    /// A position among other words and numbers ("Camp site 14: -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: …",
    /// "Lions on H4-1 at …"), where the other numbers keep the whole text from being read: a pair that can only be a
    /// position, read on its own. That is decimals with 3 places or more, or values with a degree sign, side by side and
    /// not part of a longer list of numbers. When the text has two different ones, it isn't clear which is meant, nor with
    /// more than 10 (a track, not a place).
    private static func inText(_ t: String, near: GeoPoint?) -> Result? {
        let ns = t as NSString
        var found: Result?, tried = 0
        for (decimal, separator) in [(#"\."#, #"(?:\s*[,;/]\s*|\s+)"#), (",", #"(?:\s*[;/]\s*|\s+)"#)] {
            let fraction: String = "(?:" + decimal + #"\d+)?"#
            let decimals: String = #"[-+]?\d{1,3}"# + decimal + #"\d{3,}\s?°?"#
            let degrees: String = #"[-+]?\d{1,3}"# + fraction + #"\s?°(?:\s?\d{1,2}"# + fraction + #"\s?')?(?:\s?\d{1,2}"# + fraction
                + #"\s?")?"#
            let half: String = #"(?:[NSEWO]\s?)?(?:"# + decimals + "|" + degrees + #")(?:\s?[NSEWO](?![A-Z]))?"#
            let pair: String = #"(?<![\w.,°'"+-])"# + half + separator + half + #"(?![\w°'"]|[.,]\d)"#
            for m in matchRanges(pair, in: t, options: [.caseInsensitive]) {
                let r = m[0]
                let start = max(0, r.location - 20), end = NSMaxRange(r)
                let before = ns.substring(with: NSRange(location: start, length: r.location - start))
                let after = ns.substring(with: NSRange(location: end, length: min(20, ns.length - end)))
                if before.range(of: #"\d\s*[,;]\s*$"#, options: .regularExpression) != nil
                    || after.range(of: #"^\s*[,;]\s*[-+]?\d"#, options: .regularExpression) != nil { continue }
                tried += 1
                guard tried <= 10 else { return nil }
                guard let p = latLon(ns.substring(with: r), near: near) else { continue }
                if let f = found, Geo.distance(p.point, f.point) >= 30 { return nil }
                found = found ?? p
            }
            if found != nil { break }
        }
        return found
    }
}
