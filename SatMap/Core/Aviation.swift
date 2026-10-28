import Foundation

/// An aerodrome, landing strip, helipad or radio navigation aid (drone mode), from OurAirports
/// (SatMap/Resources/Aviation.tsv, built by tools/build_aviation.py).
struct AviationPoint: Equatable {
    enum Kind: Character, CaseIterable {
        case largeAirport = "L"
        case mediumAirport = "M"
        case smallAirport = "S"
        case heliport = "H"
        case seaplaneBase = "W"
        case vor = "V"
        case tacan = "T"
        case dme = "D"
        case ndb = "B"

        var isAerodrome: Bool {
            switch self {
            case .largeAirport, .mediumAirport, .smallAirport, .heliport, .seaplaneBase: return true
            case .vor, .tacan, .dme, .ndb: return false
            }
        }

        /// Shown from this wide a view (km across the screen) down, so a zoomed-out map isn't crowded: airports
        /// with airline flights from far out, strips and VORs at region scale, helipads and NDBs close up.
        var shownWithinKm: Double {
            switch self {
            case .largeAirport: return 3_000
            case .mediumAirport: return 800
            case .vor, .tacan: return 400
            case .smallAirport: return 200
            case .ndb, .dme: return 120
            case .heliport, .seaplaneBase: return 60
            }
        }

        /// Kept first when labels collide (MapKit's display priority, 0–1000).
        var priority: Float {
            switch self {
            case .largeAirport: return 900
            case .mediumAirport: return 850
            case .vor, .tacan: return 700
            case .smallAirport: return 650
            case .ndb, .dme: return 500
            case .heliport, .seaplaneBase: return 450
            }
        }

        /// "Airport", "Airstrip", "Helipad", "VOR" …
        var title: String {
            switch self {
            case .largeAirport, .mediumAirport: return "Airport"
            case .smallAirport: return "Airstrip"
            case .heliport: return "Helipad"
            case .seaplaneBase: return "Seaplane base"
            case .vor: return "VOR"
            case .tacan: return "TACAN"
            case .dme: return "DME"
            case .ndb: return "NDB"
            }
        }
    }

    let kind: Kind
    /// The ICAO code (or another code pilots use: "FASZ"), or a navaid's ident ("HGV"); may be empty.
    let code: String
    let name: String
    let point: GeoPoint
    /// Aerodromes: field elevation in feet, and the longest runway's heading (degrees true, 0–179) if known.
    /// Navaids: the frequency ("114.50 MHz").
    let elevationFeet: Int?
    let runwayHeading: Int?
    let frequency: String?

    /// "FASZ Skukuza", the name without "Airport", "Aerodrome" … ; the name alone without a code.
    var shortName: String {
        var n = name
        for suffix in [" International Airport", " Airport", " Aerodrome", " Airfield", " Airstrip", " Air Force Base",
                       " Heliport", " Helipad"] where n.count > suffix.count + 2 && n.hasSuffix(suffix) {
            n = String(n.dropLast(suffix.count))
            break
        }
        return n
    }

    var label: String {
        code.isEmpty ? shortName : "\(code) \(shortName)"
    }

    /// Parses a line of Aviation.tsv (nil for comments and lines it can't read).
    init?(line: Substring) {
        let f = line.split(separator: "\t", omittingEmptySubsequences: false)
        guard f.count >= 7, let k = f[1].first, let kind = Kind(rawValue: k),
              let lat = Double(f[4]), let lon = Double(f[5]), (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
        self.kind = kind
        code = String(f[2])
        name = String(f[3])
        point = GeoPoint(lat: lat, lon: lon)
        if f[0] == "A" {
            elevationFeet = Int(f[6])
            runwayHeading = f.count > 7 ? Int(f[7]) : nil
            frequency = nil
        } else {
            elevationFeet = nil
            runwayHeading = nil
            frequency = f[6].isEmpty ? nil : String(f[6])
        }
    }

    init(kind: Kind, code: String, name: String, point: GeoPoint, elevationFeet: Int? = nil, runwayHeading: Int? = nil,
         frequency: String? = nil) {
        self.kind = kind
        self.code = code
        self.name = name
        self.point = point
        self.elevationFeet = elevationFeet
        self.runwayHeading = runwayHeading
        self.frequency = frequency
    }
}

/// All the aviation places, with a 1° grid to find those in a view or nearest a spot quickly.
final class AviationIndex {
    let points: [AviationPoint]
    private var cells: [Int: [Int32]] = [:]

    init(tsv: String) {
        var points: [AviationPoint] = []
        points.reserveCapacity(90_000)
        for line in tsv.split(separator: "\n") where !line.hasPrefix("#") {
            if let p = AviationPoint(line: line) { points.append(p) }
        }
        self.points = points
        for (i, p) in points.enumerated() {
            cells[Self.cell(p.point.lat, p.point.lon), default: []].append(Int32(i))
        }
    }

    private static func cell(_ lat: Double, _ lon: Double) -> Int {
        let y = min(179, max(0, Int(floor(lat + 90))))
        let x = ((Int(floor(lon + 180)) % 360) + 360) % 360
        return y * 360 + x
    }

    /// The places inside the bounds (longitude may run past ±180 for a view across the date line).
    func points(in b: GeoBounds, where keep: (AviationPoint) -> Bool = { _ in true }) -> [Int] {
        var out: [Int] = []
        let y0 = max(0, Int(floor(b.minLat + 90))), y1 = min(179, Int(floor(b.maxLat + 90)))
        let x0 = Int(floor(b.minLon + 180)), x1 = Int(floor(b.maxLon + 180))
        guard y0 <= y1, x0 <= x1, x1 - x0 <= 360 else { return [] }
        for y in y0...y1 {
            for x in x0...x1 {
                guard let list = cells[y * 360 + ((x % 360) + 360) % 360] else { continue }
                for i in list {
                    let p = points[Int(i)]
                    guard p.point.lat >= b.minLat, p.point.lat <= b.maxLat else { continue }
                    var lon = p.point.lon
                    if lon < b.minLon { lon += 360 }
                    if lon > b.maxLon { lon -= 360 }
                    guard lon >= b.minLon, lon <= b.maxLon, keep(p) else { continue }
                    out.append(Int(i))
                }
            }
        }
        return out
    }

    /// The nearest place that `keep` accepts, within `maxMeters`, and how far it is.
    func nearest(to p: GeoPoint, within maxMeters: Double = 400_000,
                 where keep: (AviationPoint) -> Bool = { $0.kind.isAerodrome }) -> (index: Int, meters: Double)? {
        // Grow the search a degree at a time; a degree of latitude is 111 km.
        let maxRing = Int(ceil(maxMeters / 111_000)) + 1
        var best: (index: Int, meters: Double)?
        for ring in 0...maxRing {
            let latPad = Double(ring) + 0.5
            let lonPad = min(180, latPad / max(0.05, cos(p.lat * .pi / 180)))
            let b = GeoBounds(minLat: max(-90, p.lat - latPad), minLon: p.lon - lonPad,
                              maxLat: min(90, p.lat + latPad), maxLon: p.lon + lonPad)
            for i in points(in: b, where: keep) {
                let d = Geo.distance(p, points[i].point)
                if d <= maxMeters, d < (best?.meters ?? .infinity) { best = (i, d) }
            }
            // Anything nearer would have been within this ring.
            if let found = best, found.meters < (Double(ring) + 0.5) * 111_000 * 0.9 { return found }
        }
        return best
    }
}

enum Aviation {
    static let metresPerNauticalMile = 1852.0

    /// "0.4 NM", "3.2 NM", "12.4 NM", "86 NM", "140 NM".
    static func nauticalMiles(_ metres: Double) -> String {
        let nm = metres / metresPerNauticalMile
        if nm < 99.95 { return String(format: "%.1f NM", nm) }
        return String(format: "%.0f NM", nm)
    }

    /// The direction from `a` to `b` on the 16-point compass: "N", "NNE", "NE" …
    static func compass(from a: GeoPoint, to b: GeoPoint) -> String {
        let names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
        let k = Int((Geo.bearing(from: a, to: b) / 22.5).rounded()) % 16
        return names[(k + 16) % 16]
    }

    /// Where a spot is from a place, as a pilot reports it: "3.2 NM NE of FASZ Skukuza" (close by: "over …").
    static func report(_ spot: GeoPoint, from place: AviationPoint) -> String {
        let d = Geo.distance(place.point, spot)
        if d < 0.1 * metresPerNauticalMile { return "Over \(place.label)" }
        return "\(nauticalMiles(d)) \(compass(from: place.point, to: spot)) of \(place.label)"
    }
}
