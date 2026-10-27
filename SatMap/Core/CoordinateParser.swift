import Foundation

/// Reads a position typed or pasted in almost any common form:
/// - decimal degrees, signed or with N/S/E/W before or after ("-25.096893, 31.437121", "25.0969 S 31.4371 E");
/// - degrees and decimal minutes ("25° 05.8136' S, 31° 26.2273' E") and degrees, minutes and seconds
///   ("25°05'48.8\"S 31°26'13.6\"E", "25-05-48.8S 031-26-13.6E", "40d41m21sN"), with any of the usual symbols or spaces;
/// - decimal commas ("-25,096893; 31,437121"), digits in other scripts, labels ("lat"/"lon" in either order, "X"/"Y", a
///   "Lat/Long:" heading or a spreadsheet's header row), and directions and labels in English, Afrikaans, Dutch, German,
///   French, Spanish, Italian and Portuguese ("sud", "Oeste", "ZB", "Breite"), with a lone O for east or west (see
///   `latLon`); altitude, accuracy and the like next to the position are left out;
/// - GPS (NMEA) fields or whole sentences ("2505.8136,S,03126.2273,E", "$GPGGA,…"), aviation style ("0536.22N 00011.22W")
///   and ISO 6709 ("-2505.8136+03126.2273/", "+40.6892-074.0445+545.400/");
/// - map links (Google Maps, Apple Maps, OpenStreetMap, OsmAnd, Bing, Waze, Yandex, ArcGIS, GeoHack and others with the
///   position in them), geo: links and Google navigation links;
/// - GeoJSON, KML and WKT ("POINT (31.437121 -25.096893)"), which put the longitude first, as does code that names the
///   order ("ST_MakePoint(31.437121, -25.096893)", "LngLat(…)"); labelled values in GPX, JSON or XML whatever else is
///   with them (a <time>, a name, a zoom);
/// - UTM ("36J 342401 7223411", "30 N 699568 5709428", "UTM 36 South 342402 7223412"), MGRS ("36J UT 42401 23411",
///   "36JUT42402341") and plus codes ("5G6HWC3P+6R", or a short "WC3P+6R" near `near`);
/// - a position inside other text, as in a chat message ("[12:31, 27/10/2026] Jaco: Lions on H4-1 at -25.0969, 31.4371").
/// Without letters or labels the first number is the latitude, unless only the second can be one.
/// A text that could be two different places, with nothing in it to say which, isn't read (nil), nor are numbers among
/// other words that could as well be times, dates or counts. Two exceptions say in the format how they were read: a lone
/// "O" (east or west) and a directions link to a place by name (the map's centre).
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
        // The numbers in a link that couldn't be read (a short link, a place by name) don't say where. A small "o" before a
        // word in small letters is Portuguese "the" or Spanish "or", not a direction ("…, 31.4371 o ponto de encontro").
        let rest = withoutLinks(t).replacingOccurrences(of: #"(?<!\p{L})o(?=[ \t]+\p{Ll})"#, with: " ", options: .regularExpression)
        // GeoJSON, KML or WKT that doesn't say one place (a line, two placemarks) isn't read in any other way either: the
        // rest of it, such as a KML's <LookAt> (where the camera looks from), isn't the place.
        if let shaped = lonFirst(rest) { return shaped }
        let readers: [(String) -> Result?] = [nmea, iso6709, mgrs, utm, labelled, { latLon($0, near: near) },
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
            // Arabic and fullwidth decimal points, commas and signs.
            ("\u{066B}", "."), ("\u{060C}", ","), ("\u{FF0E}", "."), ("\u{FF0C}", ","), ("\u{FF0D}", "-"), ("\u{FF0B}", "+"),
            ("\u{FF0F}", "/"),
        ]
        for (from, to) in replacements {
            t = t.replacingOccurrences(of: from, with: to)
        }
        // Digits in other scripts ("٤٠", "４０") as 0-9, which is what the readers and Double() take.
        if t.unicodeScalars.contains(where: { $0.value > 127 && $0.properties.numericType == .decimal }) {
            t = String(String.UnicodeScalarView(t.unicodeScalars.map { u in
                guard u.value > 127, u.properties.numericType == .decimal, let v = u.properties.numericValue,
                      let ascii = Unicode.Scalar(48 + UInt32(v)) else { return u }
                return ascii
            }))
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

        // Directions: where they go to (Waze's to=ll.…, the last stop of OpenStreetMap's route=), not where they start.
        if let p = find(param("daddr|destination|to")) ?? find(#"[?&]route=[^&#\s]*;"# + num + "," + num + #"(?=&|#|\s|$)"#) {
            return link(p)
        }
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
        // Yandex (ll=, pt=) and ArcGIS (center=, marker=) put the longitude first. The pin (pt=, whatshere[point]=,
        // marker=) rather than the map's centre (ll=, center=), whichever comes first in the link.
        if t.range(of: #"(?i)\b(?:yandex\.[a-z.]+|arcgis\.com)/"#, options: .regularExpression) != nil,
           let p = find(param(#"pt|marker|whatshere\[point\]"#), lonFirst: true) ?? find(param("ll|center"), lonFirst: true) {
            return link(p)
        }
        // Most specific first: Google's dropped pin (!3d…!4d…) and an embedded Google map (!2d lon !3d lat).
        if let p = find("!3d" + num + "!4d" + num) { return link(p) }
        if let p = find("!2d" + num + "!3d" + num, lonFirst: true) { return link(p) }
        // The place: a pin or search (also a name with the position after an @), Bing's pushpin (sp=point.lat_lon_name),
        // OpenStreetMap's marker (mlat=, mlon=, not the map's centre in lat=, lon=) and OsmAnd's lat=…&lon=… (in either
        // order, with other parameters between them).
        let place: [String] = [param("q|query|pin|point|loc|address"), #"[?&](?:q|query)=[^&#\s@]*@"# + num + #"(?:,|\s)+"# + num,
                               #"[?&]sp=point\."# + num + "_" + num]
        if let p = place.lazy.compactMap({ find($0) }).first { return link(p) }
        func value(_ names: String) -> Double? {
            matches("[?&](?:" + names + ")=" + num, in: t, options: [.caseInsensitive]).first?[1].flatMap(Double.init)
        }
        for (lat, lon) in [("mlat", "mlon|mlng"), ("lat|latitude", "lon|lng|long|longitude")] {
            if let a = value(lat), let b = value(lon), let p = valid(a, b) { return link(p) }
        }
        // GeoHack (Wikipedia's map links): params=24_59_46_S_31_35_28_E_… or params=-24.99611_31.59111_….
        if let m = matches(#"[?&]params=([\d._]+_[NS])_([\d._]+_[EW])(?![A-Z0-9])"#, in: t, options: [.caseInsensitive]).first,
           let a = m[1], let b = m[2], let r = latLon((a + " " + b).replacingOccurrences(of: "_", with: " "), near: nil) {
            return link(r.point)
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
            return link(p)
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
        if let p = find(path, lonFirst: t.range(of: #"(?i)mapbox\.com/"#, options: .regularExpression) != nil) { return link(p) }
        return find(#"[?&][A-Za-z_]+="# + decimal + #"\s*,\s*"# + decimal + #"(?=&|#|\s|$)"#).map(link)
    }

    /// A position as part of a link's path: "-25.096893,31.437121", "-25.096893+31.437121" or "25°05'48.8"S+31°26'13.6"E".
    private static func pathPoint(_ part: String) -> GeoPoint? {
        let s = part.replacingOccurrences(of: "+", with: " ")
        guard s.range(of: #"^[-\d.,;°'"\sNSEWnsew]+$"#, options: .regularExpression) != nil else { return nil }
        return latLon(s, near: nil)?.point
    }

    /// The text without links (the ones `fromLink` couldn't read). "geo:" is a link only before a number, not in
    /// "<geo:lat>" (W3C Basic Geo) or "geo:lon=" (a photo's machine tags).
    private static func withoutLinks(_ t: String) -> String {
        // Each starts where a word starts (the look-behind), so a long run of characters isn't tried from every place in it.
        t.replacingOccurrences(of: #"(?i)(?<![a-z0-9+.-])(?:[a-z][a-z0-9+.-]*+://|www\.|geo:(?=\s*[-+\d])|google\.navigation:)\S*"#
                                   + #"|(?<![a-z0-9.-])(?:[a-z0-9-]++\.)++[a-z]{2,}/\S*"#,
                               with: " ", options: .regularExpression)
    }

    // MARK: - Plus codes (Open Location Code)

    private static let olcAlphabet = Array("23456789CFGHJMPQRVWX")

    private static func plusCode(_ t: String, near: GeoPoint?) -> Result? {
        let u = t.uppercased()
        let codes = matches(#"(?<![0-9A-Z])([23456789CFGHJMPQRVWX0]{2,8})\+([23456789CFGHJMPQRVWX]{0,7})(?![0-9A-Z])"#, in: u)
        return codes.lazy.compactMap { m in plusCode(m, in: u, near: near) }.first
    }

    private static func plusCode(_ m: [String?], in u: String, near: GeoPoint?) -> Result? {
        guard let head = m[1], let tail = m[2] else { return nil }
        var code = head.replacingOccurrences(of: "0", with: "")
        guard code.count % 2 == 0 || head.count == 8 else { return nil }
        if head.count < 8 {
            // A short code: the missing first part comes from the reference point (your position). Google writes them
            // with 4 or 6 characters before the "+" and 2 or more after it, so "25+" or "22+22" (a count, a sum) isn't one.
            guard let ref = near, !head.contains("0"), head.count >= 4, tail.count >= 2 else { return nil }
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

    /// Formats that always put the longitude first: GeoJSON ("coordinates": [lon, lat, alt], keys quoted or not, nested for
    /// a MultiPoint), KML (<coordinates>lon,lat,alt, <gx:coord>lon lat alt), WKT ("POINT (lon lat)", "SRID=4326;POINT Z
    /// (lon lat alt)", "MULTIPOINT ((lon lat))") and code that names the order ("ST_MakePoint(lon, lat)" in PostGIS,
    /// Mapbox's "LngLat(lon, lat)", OpenLayers' "fromLonLat([lon, lat])"). Two different places in it (two placemarks,
    /// a line) aren't read: then the result is nil inside (.some(nil)), and nil when the text is in none of these formats.
    private static func lonFirst(_ t: String) -> Result?? {
        let num = #"([-+]?\d+(?:\.\d+)?)"#
        // Where the positions are (the first group), and how each one is written in there. WKT's POINT in any case, but
        // not after a word, as "Meeting point (-25.0969 31.4371)" isn't WKT.
        let formats: [(area: String, position: String, name: String)] = [
            (#"["']?\bcoordinates["']?\s*:\s*(\[[\[\]\s\d.,+-]*\])"#, #"\[\s*"# + num + #"\s*,\s*"# + num + #"[^\[\]]*\]"#, "GeoJSON"),
            (#"<coordinates>([^<]*)"#, num + #"\s*,\s*"# + num + #"(?:\s*,\s*[-+]?\d+(?:\.\d+)?)?"#, "KML"),
            (#"<gx:coord>([^<]*)"#, num + #"\s+"# + num, "KML"),
            (#"(?i)(?<![A-Z0-9_])(?<![A-Z][ \t]{1,3})(?:MULTI)?POINT\s*(?:ZM|Z|M)?\s*(\([\s\d.,+()-]*\))"#,
             num + #"\s+"# + num + #"(?:\s+[-+]?\d+(?:\.\d+)?){0,2}"#, "WKT"),
            (#"(?i:\bST_(?:Make)?Point|(?:LNG|LON|LONG)LAT\w*)\s*[(\[=:]\s*[(\[]?\s*("# + num + #"\s*,\s*"# + num + ")",
             num + #"\s*,\s*"# + num, "decimal degrees"),
        ]
        for (area, position, name) in formats {
            let points: [GeoPoint?] = matches(area, in: t).compactMap { $0[1] }.flatMap { a in
                matches(position, in: a).map { m -> GeoPoint? in
                    guard let lon = m[1].flatMap(Double.init), let lat = m[2].flatMap(Double.init) else { return nil }
                    return valid(lat, lon)
                }
            }
            guard let first = points.first else { continue }
            guard let p = first, points.allSatisfy({ $0.map { Geo.distance($0, p) < 30 } ?? false }) else { return .some(nil) }
            // "coordinates: [-25.0969, 31.4371]" on its own, with the key not in quotes, no "type" and no nesting, may
            // as well be a person's or another tool's lat, lon: when both orders are positions, it can't be told which.
            if name == "GeoJSON", valid(p.lon, p.lat) != nil,
               t.range(of: #"["']coordinates["']|\btype["']?\s*:|\[\s*\["#, options: .regularExpression) == nil {
                return .some(nil)
            }
            return Result(point: p, format: name)
        }
        return nil
    }

    /// GPS (NMEA) fields, alone or in whole sentences ($GPGGA, $GPRMC, $GPGLL…): ddmm.mmmm,N/S,dddmm.mmmm,E/W. Several
    /// (a log) must all be one place.
    private static func nmea(_ t: String) -> Result? {
        let fixes = matches(#"(?<![\d.])(\d{2})(\d{2}(?:\.\d+)?),\s*([NS]),\s*(\d{3})(\d{2}(?:\.\d+)?),\s*([EW])(?![A-Z])"#,
                            in: t, options: [.caseInsensitive]).map { m -> GeoPoint? in
            guard let latD = m[1].flatMap(Double.init), let latM = m[2].flatMap(Double.init), latM < 60,
                  let lonD = m[4].flatMap(Double.init), let lonM = m[5].flatMap(Double.init), lonM < 60 else { return nil }
            let lat = (latD + latM / 60) * (m[3]?.uppercased() == "S" ? -1 : 1)
            let lon = (lonD + lonM / 60) * (m[6]?.uppercased() == "W" ? -1 : 1)
            return valid(lat, lon)
        }
        guard let first = fixes.first, let p = first,
              fixes.allSatisfy({ $0.map { Geo.distance($0, p) < 30 } ?? false }) else { return nil }
        return Result(point: p, format: "GPS (NMEA)")
    }

    /// ISO 6709, as in photo and video metadata and the time zone database: "+40.6892-074.0445+545.400/",
    /// "-2505.8136+03126.2273/", "-0117+03649". The latitude has 2 digits of degrees and the longitude 3, then 2 of
    /// minutes and 2 of seconds if there are more; an altitude and "CRS…" after them are left out.
    private static func iso6709(_ t: String) -> Result? {
        let lat = "([+-])([0-9]{2})([0-9]{2})?([0-9]{2})?([.][0-9]+)?", lon = "([+-])([0-9]{3})([0-9]{2})?([0-9]{2})?([.][0-9]+)?"
        let pattern: String = "^" + lat + lon + #"(?:[+-][0-9]+(?:\.[0-9]+)?)?(?:CRS[A-Z0-9_:]+)?/?$"#
        guard let m = matches(pattern, in: t, options: [.caseInsensitive]).first else { return nil }
        func value(_ i: Int) -> Double? {
            var fields = m[(i + 1)...(i + 3)].compactMap { $0.flatMap(Double.init) } // degrees, minutes, seconds
            guard !fields.isEmpty else { return nil }
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
        let u = t.uppercased(), ns = u as NSString
        let numbers = matchRanges(#"(?<![\d.])\d{5,8}(?:\.\d+)?(?![\d])"#, in: u)
        guard let firstNumber = numbers.first?[0].location else { return nil }
        // The zone and a band letter, or the hemisphere in words ("UTM 36 South …"), right before the easting and
        // northing (or their labels), so that "Waypoint 12 N: 36J …" or the road in "R40 N to 36J …" isn't the zone.
        // (Possessive, so that a long run of spaces after the zone isn't tried again from every place in it.)
        let zones = matchRanges(#"(?<![\d.])(\d{1,2})\s*(NORTH|SOUTH|NOORD|SUID)(?![A-Z])|(?<![\d.])(\d{1,2})\s?([C-HJ-NP-X])(?![A-Z])"#,
                                in: u)
        guard let zb = zones.last(where: { z in
            let end = NSMaxRange(z[0])
            return end <= firstNumber && ns.substring(with: NSRange(location: end, length: firstNumber - end))
                .range(of: #"^[\s,:;=]*+(?:E(?:ASTING)?|N(?:ORTHING)?)?[\s,:;=]*+$"#, options: .regularExpression) != nil
        }) else { return nil }
        let spelled = zb[1].location != NSNotFound
        guard let zone = Int(ns.substring(with: spelled ? zb[1] : zb[3])),
              let letter = ns.substring(with: spelled ? zb[2] : zb[4]).first else { return nil }
        // The rest after it, so that the "N" of "30 N 699568 5709428" isn't taken for a northing label below.
        let rest = ns.substring(from: NSMaxRange(zb[0]))
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
        if spelled {
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
        // Anywhere in the text ("MGRS: 36J UT 42401 23411", "36J UT 42401 23411 (MGRS)") that has no other numbers. The
        // zone number and band letter are written together, as every tool writes them, and the letters all in capitals or
        // all in small letters, so "Arrive 7 Dec 25" or "4 Mar 26" isn't one. The digits are the easting and northing (in
        // metres, maybe with an "m"), in two groups or one run split in half ("36JUT42402341").
        let pattern = #"(?<![A-Z0-9])(\d{1,2})([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*(\d{1,10})M?(?:\s+(\d{1,5})M?)?(?![A-Z0-9])"#
        guard let m = matches(pattern, in: t, options: [.caseInsensitive]).first, let all = m[0],
              !t.replacingOccurrences(of: all, with: " ").contains(where: \.isNumber) else { return nil }
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
                return Result(point: p, format: "MGRS")
            }
        }
        return nil
    }

    // MARK: - Labelled values

    /// Values each named by a label right before them, whatever else is in the text: GPX (<trkpt lat="…" lon="…"> with its
    /// <ele> and <time>), JSON ({"name": "Camp 14", "lat": …, "lng": …, "zoom": 12}), XML (<geo:lat>…</geo:lat>) and
    /// "Breite: 52,52 Länge: 13,405". Only plain values: one with minutes, seconds or a letter after it, or (unless in
    /// quotes) a direction or unit in words or brackets ("25 deg 5' 48.81\" S", "25.0969 South", "25.0969 (S)", "120 cm"),
    /// is read by `latLon`. Several (a track) must all be one place.
    private static func labelled(_ t: String) -> Result? {
        let u = t.uppercased()
        let value = hasDecimalPoint(u) ? #"([-+]?\d+(?:\.\d+)?)"# : #"([-+]?\d+(?:,\d+)?)"#
        let unit = (directionWords + [degreeWords, minuteWords, secondWords, lengthWords, "D"]).joined(separator: "|")
        func values(_ names: String) -> [Double] {
            matches(#"(?<![A-Z])(?:"# + names + #")(?![A-Z])[ \t]*(["']?)[ \t]*[:=>]?[ \t]*(["']?)[ \t]*"# + value
                        + #"\2(?![ \t]*(?:[\d°'"]|[NSEWOZ](?![A-Z])|[.,]\d))"#
                        + #"(?:(?<=["'])|(?![ \t]*(?:(?:"# + unit + #")(?!\p{L})|\(\s*[NSEWOZ]\s*\))))"#, in: u)
                .compactMap { $0[3].flatMap { Double($0.replacingOccurrences(of: ",", with: ".")) } }
        }
        let lats = values(latWords), lons = values(lonWords)
        guard !lats.isEmpty, lats.count == lons.count else { return nil }
        let points = zip(lats, lons).compactMap { valid($0, $1) }
        guard points.count == lats.count, points.allSatisfy({ Geo.distance($0, points[0]) < 30 }) else { return nil }
        return Result(point: points[0], format: "decimal degrees")
    }

    // MARK: - Latitude and longitude

    private enum Axis { case lat, lon }

    private enum Token {
        /// A number: its value, its digits before and after the point, and whether a minus sign was before it.
        case number(value: Double, intDigits: Int, places: Int, negative: Bool)
        case degree, minute, second
        case hemisphere(Character)
        case separator
        /// A dash or colon between the fields of one half ("25-05-48.8", "25:05:48.8"), or of a time ("12:30").
        case joiner
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
        /// Whether something says where it starts: a letter or label before it, or a degree or minus sign on its first
        /// number.
        var startMarked: Bool
        /// Whether it can only be a coordinate: it has a letter, a label or a unit mark, or 3 decimal places or more.
        var strong: Bool
        /// Whether a minus sign is before its first number.
        var signed: Bool
        /// The size, in degrees, of one unit of its last digit.
        var resolution: Double
    }

    /// A position read from text, and how precisely it is written there (in degrees, the coarser of its two halves).
    private typealias Reading = (result: Result, resolution: Double)

    /// Symbols the words are replaced with before reading: a latitude, longitude and other label.
    private static let latSymbol: Character = "\u{24D0}", lonSymbol: Character = "\u{24DE}",
                       otherSymbol: Character = "\u{24E9}"

    /// Labels in English, Afrikaans, Dutch, German, Spanish, Italian, French and Portuguese.
    private static let latWords = "LATITUDE|LATITUD|LATITUDINE|LAT|BREEDTEGRAAD|BREEDTE|BREITENGRAD|BREITE",
                       lonWords = "LONGITUDE|LONGITUD|LONGITUDINE|LONG|LON|LNG|LENGTEGRAAD|LENGTE|LÄNGENGRAD|LÄNGE|LAENGE"
    /// Directions in the same languages (Dutch ZB, OL: zuiderbreedte, oosterlengte), north, south, east and west; a lone O
    /// is left for `latLon`.
    private static let directionWords = ["NORTH|NTH|NOORD|NB|NORD|NORTE", "SOUTH|STH|SUID|ZUID|ZB|Z|SÜD|SUED|SUD|SUR|SUL",
                                         "EAST|OOS|OOST|OL|OST|EST|ESTE|LESTE", "WEST|WES|WL|OUEST|OESTE|OVEST"]
    /// Units: degrees, minutes and seconds in words, and lengths.
    private static let degreeWords = "DEGREES|DEGREE|DEG|GRADEN|GRADE|GRADOS|GRADI|GRAUS|GRAD", minuteWords = "MINUTES|MINUTE|MIN",
                       secondWords = "SECONDS|SECOND|SEC", lengthWords = "MM|CM|M|KM|METERS?|METRES?|FT|FEET"

    /// Pairs of numbers, with their letters, labels and units. `language` is the text whose words may say which way a
    /// lone O is meant, when `t` is only a part of it.
    private static func latLon(_ t: String, near: GeoPoint?, language: String? = nil) -> Result? {
        // "51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W" (Wikipedia): the same place written in more than one way.
        // The parts that read on their own must agree as closely as the least precise of them is written (Wikipedia
        // gives "31°08′E" as "31.133°E", 34 m from it), and the most precise one is taken. A part with a lone O is read
        // the way that agrees ("46°38′10″O / -46.6361" is west), the likelier way if both do. (The " / " only from the
        // start of a run of spaces, so that a long run isn't tried from every place in it.)
        let pieces = t.replacingOccurrences(of: #"(?<!\s)\s+/\s+"#, with: "\u{1}", options: .regularExpression)
            .components(separatedBy: "\u{1}")
        let language = language ?? t
        let options = (2...4).contains(pieces.count) ? pieces.map { latLonOnce($0, near: near, language: language) }
            .filter { !$0.isEmpty } : []
        guard options.count > 1 else { return latLonOnce(t, near: near, language: language).first?.result }
        // Each way of taking one reading of each part, those with fewer second choices first.
        var ways: [[Int]] = [[]]
        for o in options {
            ways = ways.flatMap { w in o.indices.map { w + [$0] } }
        }
        for way in ways.sorted(by: { $0.reduce(0, +) < $1.reduce(0, +) }) {
            let read = zip(options, way).map { $0[$1] }
            guard let best = read.min(by: { $0.resolution < $1.resolution }) else { continue }
            let tolerance = 30 + (read.map(\.resolution).max() ?? 0) * 111_320
            if read.allSatisfy({ Geo.distance($0.result.point, best.result.point) < tolerance }) { return best.result }
        }
        return nil
    }

    /// The readings of a text, the likelier first: two when it has a lone O (east or west), else one or none.
    private static func latLonOnce(_ t: String, near: GeoPoint?, language: String) -> [Reading] {
        // Decimal commas only when there are no decimal points; if that reading fails, commas separate.
        let commaDecimal = !hasDecimalPoint(t) && t.range(of: #"\d,\d"#, options: .regularExpression) != nil
        let r = latLon(t, commaDecimal: commaDecimal, near: near, language: language)
        return r.isEmpty && commaDecimal ? latLon(t, commaDecimal: false, near: near, language: language) : r
    }

    /// Whether a number in the text has a decimal point: not a full stop at the end of a sentence ("31,59."), nor the
    /// points of a date or version ("27.10.2026", "2.5.1").
    private static func hasDecimalPoint(_ t: String) -> Bool {
        t.range(of: #"(?<![\d.])\d+\.\d+(?!\d|\.\d)"#, options: .regularExpression) != nil
    }

    private static func latLon(_ text: String, commaDecimal: Bool, near: GeoPoint?, language: String) -> [Reading] {
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
        guard s.range(of: lone, options: .regularExpression) != nil else {
            return read(s, commaDecimal: commaDecimal).map { [$0] } ?? []
        }
        func readAs(_ letter: String) -> Reading? {
            read(s.replacingOccurrences(of: lone, with: " \(letter) ", options: .regularExpression), commaDecimal: commaDecimal)
        }
        let east = readAs("E"), west = readAs("W")
        // Read the same either way, it isn't a direction (Portuguese "O ponto fica em …": "the").
        if let e = east, let w = west, e.result == w.result { return [e] }
        var isWest = east == nil
        if let e = east, let w = west {
            let u = language.uppercased()
            func has(_ words: String) -> Bool { u.range(of: #"\b(?:"# + words + #")\b"#, options: .regularExpression) != nil }
            let eastWords = has("SUID|NOORD|ZUID|ZB|NB|Z|SÜD|SUED|BREEDTE(?:GRAAD)?|LENGTE(?:GRAAD)?|BREITE(?:NGRAD)?"
                                + "|LÄNGE(?:NGRAD)?|LAENGE|KOORDINATEN?|KOÖRDINATES?|STANDORT|LIGGING|POSISIE|POSITIE|LOCATIE"
                                + "|GRADEN|UND|WIR|SIND|IST|BEI|IM|DER|MIT|AUF|ONS|NAAR|WIJ|ZIJN|BIJ|HET|EEN")
            let westWords = has("SUR|SUL|SUD|NORTE|LATITUD|LONGITUD|LATITUDINE|LONGITUDINE|COORDENADAS|COORDONN[ÉE]ES"
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
        let words: [(String, String)] = [
            // Quotes around a value, as in JSON, CSV and GPX ("lat":"-25.0969", lat="-25.0969"): not minutes or seconds.
            (#"(?<=[\s=:,;\[({])(["'])\s*([-+]?\d+(?:[.,]\d+)?)\s*\1(?=[\s,;:)}\]/>])"#, " $2 "),
            // (Not from inside a number, so that a long run of digits isn't tried from every digit in it.)
            (#"(?<![\d.,])[-+]?\d+(?:[.,]\d+)?\s*M\s+(?:ABOVE|BELOW)\s+SEA\s+LEVEL"#, " "),
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
            (#"(?<![A-Z])(ALTITUDE|ALT|ELEVATION|ELEV|ELE|HEIGHT|HOOGTE|ACCURACY|ACC|HDOP|SPEED|HEADING|BEARING|COURSE|ZOOM)(?![A-Z])"#,
             " \(otherSymbol) "),
        ]
        let directions: [(String, String)] = zip(directionWords, ["N", "S", "E", "W"]).map { (#"\b("# + $0 + #")\b"#, " \($1) ") }
        // Units in words and letters.
        let units: [(String, String)] = [
            (#"\b("# + degreeWords + #")\b"#, "°"), (#"\b("# + minuteWords + #")\b"#, "'"), (#"\b("# + secondWords + #")\b"#, "\""),
            // S for seconds, right after the number and before the direction: after minutes written with an M
            // ("40d41m21sN", "25°05m48.8s S") or with the direction right after it ("40°41'21sN"). Not in "25°05'48.8 S E
            // 031°26'13.6", where it is south and the E starts the other half.
            (#"(\d\s*M\s*\d+(?:[.,]\d+)?)S(?=\s*[NSEW](?![A-Z]))"#, "$1\""), (#"('\s*\d+(?:[.,]\d+)?)S(?=[NSEW](?![A-Z]))"#, "$1\""),
            (#"(\d)\s*D(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1°"), (#"(\d)\s*M(?=\s*(?:\d|[NSEW]\b|[,;]|$))"#, "$1'"),
        ]
        for (p, r) in words + directions + units {
            s = s.replacingOccurrences(of: p, with: r, options: .regularExpression)
        }
        // A label only before a value or another label, or at the end of a line (a header row): "Long day!" isn't one.
        let symbols = "\(latSymbol)\(lonSymbol)\(otherSymbol)"
        s = s.replacingOccurrences(of: "[\(symbols)](?![ \\t\"'=:>(\\[{/|\\\\,;]*(?:[-+.\\d\(symbols)]|\\n|$)|\\s+[-+.]?\\d)",
                                   with: " ", options: .regularExpression)
        // X and Y as GIS writes them in degrees: X the longitude, Y the latitude. Only together, so a lone "x" isn't one.
        let xy = [(#"(?<![A-Z0-9_.])X(?![A-Z0-9_])"#, lonSymbol), (#"(?<![A-Z0-9_.])Y(?![A-Z0-9_])"#, latSymbol)]
        if xy.allSatisfy({ s.range(of: $0.0, options: .regularExpression) != nil }) {
            for (p, symbol) in xy {
                s = s.replacingOccurrences(of: p, with: " \(symbol) ", options: .regularExpression)
            }
        }
        return s
    }

    private static func isWordLetter(_ c: Character?) -> Bool {
        c.map { $0.isLetter && $0 != latSymbol && $0 != lonSymbol && $0 != otherSymbol } ?? false
    }

    private static func read(_ s: String, commaDecimal: Bool) -> Reading? {
        var tokens: [Token] = []
        let chars = Array(s)
        var i = 0
        var pendingNegative = false
        // Letters that aren't a direction, label or unit: other words around the numbers.
        var words = false
        while i < chars.count {
            let c = chars[i]
            if c.isNumber || (c == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
                // Glued to a letter other than N/S/E/W (as in a plus code or a word): not a coordinate.
                if i > 0, isWordLetter(chars[i - 1]), !"NSEW".contains(chars[i - 1]) { return nil }
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
                    words = true
                    pendingNegative = false
                    continue
                }
                guard let v = Double(digits) else { return nil }
                if i < chars.count, isWordLetter(chars[i]), !"NSEW".contains(chars[i]) { return nil }
                tokens.append(.number(value: v, intDigits: intDigits, places: places, negative: pendingNegative))
                pendingNegative = false
                continue
            }
            switch c {
            // Between whole degrees or minutes and 2 digits that can be minutes or seconds ("25-05-48.8S",
            // "031-26-13.6E") a dash joins the fields of one half, and right after a half that ends in its letter
            // ("25.0969 S - 31.4371 E") it separates the halves; elsewhere it is a minus ("40-74.0445").
            case "-":
                if fieldDash(chars, i) {
                    tokens.append(.joiner)
                    break
                }
                if tokens.count > 1, case .hemisphere = tokens[tokens.count - 1],
                   isNumber(tokens[tokens.count - 2]) || isMark(tokens[tokens.count - 2]) {
                    tokens.append(.separator)
                } else {
                    pendingNegative = true
                }
            case "+": pendingNegative = false
            // After a whole number and before another a colon joins them too: a time ("12:30") or fields ("25:05:48.8 S"),
            // not two halves.
            case ":":
                if i > 0, chars[i - 1].isNumber, i + 1 < chars.count, chars[i + 1].isNumber,
                   case .number(_, _, 0, _)? = tokens.last { tokens.append(.joiner) }
            case "°": tokens.append(.degree)
            case "'": tokens.append(.minute)
            case "\"": tokens.append(.second)
            case ",", ";", "/", "|", "\n", "\t": tokens.append(.separator)
            case latSymbol: tokens.append(.label(.lat))
            case lonSymbol: tokens.append(.label(.lon))
            case otherSymbol: tokens.append(.label(nil))
            case "N", "S", "E", "W":
                if isHemisphere(chars, i) { tokens.append(.hemisphere(c)) } else { words = true }
            default:
                if isWordLetter(c) { words = true }
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
            } else if tokens[(hemis[0] + 1)..<hemis[1]].contains(where: isNumber) {
                // 25 … S 31 … E: what comes after the second letter isn't part of it ("…E, 545.4 m", "…E 2026-10-27"),
                // unless a number right after it could be its minutes ("25 S 31 E 3"), which makes it unclear.
                let after = tokens[(hemis[1] + 1)...]
                let strays = after.first.map(isSeparator) == true ? [] : after.filter(isNumber)
                if !strays.isEmpty, case let .number(_, _, places, _)? = tokens[..<hemis[1]].last(where: isNumber),
                   places == 0 { return nil }
                parts = [Array(tokens[...hemis[0]]), Array(tokens[(hemis[0] + 1)...hemis[1]])]
                // Unless what is left after the last letter looks like a coordinate (3 decimal places or more): then the
                // letters go before the numbers, and a number before the first letter is a stop or camp number ("Stop 3
                // S25.0969 E31.4371", "Site 12 N 51.5007 W 0.1246"). When that one looks like a coordinate too, it
                // isn't clear.
                if strays.contains(where: { decimalPlaces($0) >= 3 }) {
                    guard !tokens[..<hemis[0]].contains(where: { decimalPlaces($0) >= 3 }) else { return nil }
                    parts = [Array(tokens[hemis[0]..<hemis[1]]), Array(tokens[hemis[1]...])]
                }
            } else {
                parts = [Array(tokens[...hemis[0]]), Array(tokens[(hemis[0] + 1)...])] // 25 … S, E 31 …
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
        // Numbers joined by a dash or colon stay in one half: "45-30" or "12:30" isn't 45, 30 or 12, 30.
        if case .joiner? = parts[0].last { return nil }

        // Each half to a signed value, and which axis it is (from its letter or label), if known.
        var halves: [Half] = []
        for part in parts {
            guard let h = component(part) else { return nil }
            halves.append(h)
        }
        guard halves.count == 2 else { return nil }
        // "Spot 1: 51.5007, -0.1246": a number before the position (a spot or camp number) makes the first half look like
        // 1° 51.5007'. A first half with more numbers than the second is read only when something marks where it starts
        // ("40° 41' 21" N 74° 2' W", "-25 05 48.8, 31.437121", "S25 05 48.8 E31 26.2273").
        if halves[0].numbers > halves[1].numbers && !halves[0].startMarked { return nil }
        // Among other words, numbers that could as well be times, dates, counts or measurements ("Gates open 05:30 close
        // 18:00", "12 lions 3 cubs", "temp 25.5, wind 31.4") aren't a position. Degrees, minutes and seconds in both
        // halves with a minus sign before the first ("We're at -25 05 48, 31 26 13") can't be a time or a count.
        let signedSeconds = halves[0].signed && halves.allSatisfy { $0.numbers == 3 }
        if words && headingFirst == nil && !(halves[0].strong && halves[1].strong) && !signedSeconds { return nil }
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
        return (Result(point: point, format: format), max(halves[0].resolution, halves[1].resolution))
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

    private static func isSeparator(_ t: Token) -> Bool {
        if case .separator = t { return true }
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

    /// One half: its value with sign, its axis (from N/S/E/W or a label), its kind and how it is marked (see `Half`).
    private static func component(_ part: [Token]) -> Half? {
        var nums: [(value: Double, intDigits: Int, places: Int, negative: Bool, unit: Int?)] = []
        var hemi: Character?
        var label: Axis?
        var lettered = false // a letter or label before its first number
        // A label between its numbers, or a comma, semicolon or slash unless its letter comes after them (CSV columns,
        // "25,05,48.8,S,31,26,13.6,E"): they aren't the fields of one half. "-24,99 31,59." with the commas read as
        // separators isn't -24, 99° 31' 59", nor is "2026/10/27" 2026, 10° 27'.
        var labelAfter = false, separatorAfter = false, separated = false
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
            case let .hemisphere(h):
                hemi = h
                if nums.isEmpty { lettered = true }
            case let .label(a?):
                if nums.isEmpty {
                    label = a
                    lettered = true
                } else {
                    labelAfter = true
                }
            case .separator:
                if !nums.isEmpty { separatorAfter = true }
            default: break
            }
        }
        guard (1...3).contains(nums.count), !separated || (hemi != nil && !lettered) else { return nil }
        // Only the first number has a sign: "14 -25.1" is a stray number before a position, not 14° 25.1'.
        guard !nums.dropFirst().contains(where: { $0.negative }) else { return nil }
        // A minus sign and N or E ("-31.4371 E") say different things.
        if nums[0].negative && (hemi == "N" || hemi == "E") { return nil }
        // Without a degree sign, a ' or " after a number that can't be minutes or seconds is a quote ('-25.0969').
        if !nums.contains(where: { $0.unit == 0 }) {
            for k in nums.indices where (nums[k].unit ?? 0) > nums.count - 1 { nums[k].unit = nil }
        }
        var d = 0.0, m = 0.0, sec = 0.0
        var kind = nums.count - 1
        let resolution: Double
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
                    startMarked: lettered || nums[0].unit == 0 || nums[0].negative,
                    strong: hemi != nil || label != nil || nums.contains { $0.unit != nil || $0.places >= 3 },
                    signed: nums[0].negative, resolution: resolution)
    }

    // MARK: - A position inside other text

    /// A position among other words and numbers ("Camp site 14: -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: …",
    /// "Lions on H4-1 at …"), where the other numbers keep the whole text from being read: a pair that can only be a
    /// position, read on its own. That is decimals with 3 places or more, or values with a degree sign, side by side and
    /// not part of a longer list of numbers. When the text has two different ones, it isn't clear which is meant, nor with
    /// more than 10 (a track, not a place), nor when another number like them is elsewhere in the text.
    private static func inText(_ t: String, near: GeoPoint?) -> Result? {
        let ns = t as NSString
        var found: Result?, tried = 0
        for (decimal, separator) in [(#"\."#, #"(?:\s*[,;/]\s*|\s+)"#), (",", #"(?:\s*[;/]\s*|\s+)"#)] {
            let fraction: String = "(?:" + decimal + #"\d+)?"#
            let decimals: String = #"[-+]?\d{1,3}"# + decimal + #"\d{3,}\s?°?"#
            let degrees: String = #"[-+]?\d{1,3}"# + fraction + #"\s?°(?:\s?\d{1,2}"# + fraction + #"\s?')?(?:\s?\d{1,2}"# + fraction
                + #"\s?")?"#
            let half: String = #"(?:[NSEWO]\s?)?(?:"# + decimals + "|" + degrees + #")(?:\s?[NSEWO](?![A-Z]))?"#
            let pair: String = #"(?<![\w.,°'"+-])"# + half + "(" + separator + ")" + half + #"(?![\w°'"]|[.,]\d)"#
            var read: [NSRange] = []
            for m in matchRanges(pair, in: t, options: [.caseInsensitive]) {
                let r = m[0]
                let start = max(0, r.location - 20), end = NSMaxRange(r)
                let before = ns.substring(with: NSRange(location: start, length: r.location - start))
                let after = ns.substring(with: NSRange(location: end, length: min(20, ns.length - end)))
                // Part of a longer list of numbers ("31.4371 -25.0969 545.4": longitude, latitude and altitude; "Lap times:
                // 12.345 13.456 14.567"): a number after it, or a decimal before it, joined to it by a comma or semicolon
                // or the way its own two numbers are joined.
                let sep = ns.substring(with: m[1])
                let own = sep.contains("/") ? [#"\s*/\s*"#] : sep.contains(",") || sep.contains(";") ? [] : [#"\s+"#]
                let joiners = [#"\s*[,;]\s*"#] + own
                if before.range(of: #"\d\s*[,;]\s*$"#, options: .regularExpression) != nil
                    || joiners.contains(where: { j in
                        before.range(of: decimal + #"\d+"# + j + "$", options: .regularExpression) != nil
                            || after.range(of: "^" + j + #"[-+]?\.?\d"#, options: .regularExpression) != nil
                    }) { continue }
                tried += 1
                guard tried <= 10 else { return nil }
                guard let p = latLon(ns.substring(with: r), near: near, language: t) else { continue }
                if let f = found, Geo.distance(p.point, f.point) >= 30 { return nil }
                found = found ?? p
                read.append(r)
            }
            if found != nil {
                // Another number like these elsewhere in the text: a list, not a place ("Price list: 12.500 31.750 and
                // 45.250"; degrees with minutes or a letter after them, "25°05'S 31°26'E\n24°59'S 31°35'E"). Not a time, a
                // measurement ("±5.000 m"), a number with thousands separators ("1.234.567"), a temperature or a bearing
                // ("32°C", "270°").
                let others = matchRanges(#"(?<![\d.,:])[-+]?\d+"# + decimal + #"\d{3,}(?![\d.,]|\s*(?i:k?m|ft|mi)\b)"#
                                         + #"|\d\s?°\s?(?:\d{1,2}(?:[.,]\d+)?\s?'|(?i:[NSEWO])(?![A-Za-z]))"#, in: t)
                if others.contains(where: { o in !read.contains { NSLocationInRange(o[0].location, $0) } }) { return nil }
                break
            }
        }
        return found
    }
}
