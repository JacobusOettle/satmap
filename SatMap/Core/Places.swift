import Foundation

/// A place found by name for an offline area (a park, a reserve, a town, a camp …), from OpenStreetMap.
struct FoundPlace: Identifiable, Equatable {
    /// OpenStreetMap's id: "R1751334" (a relation), "W…" (a way) or "N…" (a node: a spot, no boundary).
    let id: String
    let name: String
    /// "Nature reserve", "Town" …
    let kind: String
    /// The OpenStreetMap tag value ("nature_reserve", "town" …), which decides the radius around a spot.
    let tag: String
    /// "Mpumalanga, South Africa".
    let region: String
    let centre: GeoPoint
    /// The place's extent, for areas.
    let extent: GeoBounds?

    var hasBoundary: Bool { !id.hasPrefix("N") }

    /// How far around a spot (a town, a camp …) to download, in metres.
    var radiusMeters: Double {
        switch tag {
        case "city": return 10_000
        case "town": return 4_000
        case "village", "suburb", "borough": return 2_000
        default: return 1_000
        }
    }

    /// The extent's area in km², for showing how big it is.
    var extentKm2: Double? {
        guard let e = extent else { return nil }
        let w = Geo.distance(GeoPoint(lat: (e.minLat + e.maxLat) / 2, lon: e.minLon), GeoPoint(lat: (e.minLat + e.maxLat) / 2, lon: e.maxLon))
        let h = Geo.distance(GeoPoint(lat: e.minLat, lon: e.minLon), GeoPoint(lat: e.maxLat, lon: e.minLon))
        return w * h / 1_000_000
    }
}

/// Reading place searches (Photon) and boundaries (Nominatim, GeoJSON).
enum Places {
    /// Results that are areas or settlements, not streets, buildings, shops …
    private static let skippedKeys: Set<String> = [
        "highway", "building", "amenity", "shop", "railway", "office", "craft", "man_made", "power", "emergency",
        "healthcare", "club", "public_transport", "barrier", "entrance", "aerialway", "historic", "waterway", "route",
    ]

    /// The places in a Photon search answer (a GeoJSON FeatureCollection), areas and settlements only, each once.
    static func photonResults(_ data: Data) -> [FoundPlace] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return [] }
        var out: [FoundPlace] = []
        var seen = Set<String>()
        for f in features {
            guard let p = f["properties"] as? [String: Any], let name = p["name"] as? String, !name.isEmpty,
                  let key = p["osm_key"] as? String, let value = p["osm_value"] as? String,
                  !skippedKeys.contains(key), value != "yes",
                  let type = p["osm_type"] as? String, let osmID = number(p["osm_id"]).map(Int64.init),
                  let geometry = f["geometry"] as? [String: Any], let c = numbers(geometry["coordinates"]), c.count >= 2
            else { continue }
            var extent: GeoBounds?
            if let e = numbers(p["extent"]), e.count == 4 {
                extent = GeoBounds(minLat: min(e[1], e[3]), minLon: min(e[0], e[2]), maxLat: max(e[1], e[3]), maxLon: max(e[0], e[2]))
            }
            // A way or relation without an extent is a spot as far as the area goes.
            let id = "\(extent == nil ? "N" : type)\(osmID)"
            let key2 = "\(name)|\(value)|\(Int((c[1] * 100).rounded()))|\(Int((c[0] * 100).rounded()))"
            guard !seen.contains(id), !seen.contains(key2) else { continue }
            seen.insert(id)
            seen.insert(key2)
            let region = [p["county"], p["state"], p["country"]].compactMap { $0 as? String }.filter { $0 != name }
            out.append(FoundPlace(id: id, name: name, kind: kindName(value), tag: value, region: region.joined(separator: ", "),
                                  centre: GeoPoint(lat: c[1], lon: c[0]), extent: extent))
        }
        return out
    }

    /// A JSON number, however JSONSerialization gives it.
    private static func number(_ any: Any?) -> Double? {
        if let n = any as? NSNumber { return n.doubleValue }
        if let d = any as? Double { return d }
        if let i = any as? Int { return Double(i) }
        return nil
    }

    private static func numbers(_ any: Any?) -> [Double]? {
        guard let list = any as? [Any] else { return nil }
        let out = list.compactMap(number)
        return out.count == list.count ? out : nil
    }

    /// "nature_reserve" → "Nature reserve".
    static func kindName(_ value: String) -> String {
        switch value {
        case "camp_site": return "Camp"
        case "aerodrome": return "Airfield"
        case "protected_area": return "Protected area"
        default:
            let words = value.replacingOccurrences(of: "_", with: " ")
            return words.prefix(1).uppercased() + words.dropFirst()
        }
    }

    /// The outer rings of a GeoJSON geometry (Polygon, MultiPolygon, or a closed LineString), or of the first feature of
    /// a FeatureCollection (Nominatim's lookup answer). Holes are left out: a download covers them too.
    static func outerRings(geoJSON data: Data) -> [[GeoPoint]] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        var geometry = root
        if let features = root["features"] as? [[String: Any]], let g = features.first?["geometry"] as? [String: Any] {
            geometry = g
        } else if let g = root["geometry"] as? [String: Any] {
            geometry = g
        }
        func ring(_ any: Any?) -> [GeoPoint]? {
            guard let coords = any as? [Any] else { return nil }
            let pts = coords.compactMap { numbers($0) }.compactMap { $0.count >= 2 ? GeoPoint(lat: $0[1], lon: $0[0]) : nil }
            return pts.count >= 3 ? pts : nil
        }
        switch geometry["type"] as? String {
        case "Polygon":
            guard let rings = geometry["coordinates"] as? [Any], let outer = ring(rings.first) else { return [] }
            return [outer]
        case "MultiPolygon":
            return ((geometry["coordinates"] as? [Any]) ?? []).compactMap { ($0 as? [Any])?.first.flatMap(ring) }
        case "LineString":
            guard let pts = ring(geometry["coordinates"]), let a = pts.first, let b = pts.last,
                  Geo.distance(a, b) < 50 else { return [] }
            return [pts]
        default:
            return []
        }
    }

    /// The download area for a place: its boundary, else (a spot) the circle around it, else its extent.
    static func area(for place: FoundPlace, rings: [[GeoPoint]]) -> CoverageShape {
        if !rings.isEmpty { return CoverageShape(polygons: rings) }
        if let e = place.extent, place.hasBoundary {
            return CoverageShape(polygons: [[GeoPoint(lat: e.minLat, lon: e.minLon), GeoPoint(lat: e.minLat, lon: e.maxLon),
                                             GeoPoint(lat: e.maxLat, lon: e.maxLon), GeoPoint(lat: e.maxLat, lon: e.minLon)]])
        }
        return CoverageShape(points: [place.centre], bufferMeters: place.radiusMeters)
    }

    /// How much Nominatim may simplify a boundary (degrees): about a 1 500th of its size, at least 10 m.
    static func simplification(for place: FoundPlace) -> Double {
        guard let e = place.extent else { return 0.0001 }
        return max(0.0001, max(e.maxLat - e.minLat, e.maxLon - e.minLon) / 1500)
    }
}
