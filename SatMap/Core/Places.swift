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

/// Reading place searches (Photon, or Nominatim when Photon fails) and boundaries (Nominatim, GeoJSON).
enum Places {
    /// Results that are areas or settlements, not streets, buildings, shops …
    private static let skippedKeys: Set<String> = [
        "highway", "building", "amenity", "shop", "railway", "office", "craft", "man_made", "power", "emergency",
        "healthcare", "club", "public_transport", "barrier", "entrance", "aerialway", "historic", "waterway", "route",
    ]
    /// Single buildings and features, even with an outline: not places to save.
    private static let skippedValues: Set<String> = [
        "hotel", "hostel", "guest_house", "motel", "apartment", "museum", "viewpoint", "attraction", "information",
        "artwork", "picnic_site", "swimming_pool", "pitch", "playground", "sports_centre", "fitness_centre", "stadium",
        "peak", "saddle", "spring", "tree", "cave_entrance", "rock", "parking", "yes",
    ]
    /// Spots kept although they have no outline: settlements and camps (saved with the area around them).
    private static let spotKinds: Set<String> = ["camp_site", "caravan_site", "resort", "chalet"]
    /// Reserves and parks: saved within their boundary; the rest that have one also get the area around it.
    static let reserveKinds: Set<String> = ["nature_reserve", "national_park", "protected_area", "park", "game_reserve"]

    /// The places in a Photon search answer (a GeoJSON FeatureCollection), areas and settlements only, each once.
    static func photonResults(_ data: Data) -> [FoundPlace] { photonPlaces(data) ?? [] }

    /// As `photonResults`, but nil when the answer isn't a Photon answer at all (an error page, a cut-off body): that
    /// counts as Photon failing (`PlaceSearchFallback`), unlike an answer with nothing in it.
    static func photonPlaces(_ data: Data) -> [FoundPlace]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return nil }
        var found = Found()
        for f in features {
            guard let p = f["properties"] as? [String: Any], let geometry = f["geometry"] as? [String: Any],
                  let c = numbers(geometry["coordinates"]), c.count >= 2 else { continue }
            var extent: GeoBounds?
            if let e = numbers(p["extent"]), e.count == 4 {
                extent = GeoBounds(minLat: min(e[1], e[3]), minLon: min(e[0], e[2]), maxLat: max(e[1], e[3]), maxLon: max(e[0], e[2]))
            }
            found.add(name: p["name"] as? String, key: p["osm_key"] as? String, value: p["osm_value"] as? String,
                      type: p["osm_type"] as? String, osmID: number(p["osm_id"]), lat: c[1], lon: c[0], extent: extent,
                      region: [p["county"], p["state"], p["country"]])
        }
        return found.places
    }

    /// The places in a Nominatim search answer (`format=jsonv2&addressdetails=1`), chosen and described exactly as
    /// Photon's are, so a list from either looks the same and opens the same boundary (the same "R…"/"W…"/"N…" id).
    /// Nil when the answer isn't a Nominatim answer at all.
    static func nominatimPlaces(_ data: Data) -> [FoundPlace]? {
        guard let results = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        var found = Found()
        for r in results {
            guard let lat = coordinate(r["lat"]), let lon = coordinate(r["lon"]) else { continue }
            let type: String?
            switch r["osm_type"] as? String {
            case "relation": type = "R"
            case "way": type = "W"
            case "node": type = "N"
            default: type = nil
            }
            // Nominatim gives every result a box (a spot a small one around it); Photon gives only ways and relations theirs.
            var extent: GeoBounds?
            if type != "N", let list = r["boundingbox"] as? [Any] {
                let b = list.compactMap(coordinate)
                if b.count == 4 {
                    extent = GeoBounds(minLat: min(b[0], b[1]), minLon: min(b[2], b[3]), maxLat: max(b[0], b[1]), maxLon: max(b[2], b[3]))
                }
            }
            let address = r["address"] as? [String: Any] ?? [:]
            found.add(name: r["name"] as? String, key: r["category"] as? String, value: r["type"] as? String, type: type,
                      osmID: number(r["osm_id"]), lat: lat, lon: lon, extent: extent,
                      region: [address["county"], address["state"], address["country"]])
        }
        return found.places
    }

    /// Choosing the places to keep from a search answer, the same for both services: areas and settlements, each once.
    private struct Found {
        var places: [FoundPlace] = []
        private var seen = Set<String>()

        mutating func add(name: String?, key: String?, value: String?, type: String?, osmID: Double?, lat: Double, lon: Double,
                          extent: GeoBounds?, region: [Any?]) {
            guard let name = name, !name.isEmpty, let key = key, let value = value,
                  !skippedKeys.contains(key), !skippedValues.contains(value), let type = type, let osmID = osmID.map(Int64.init)
            else { return }
            // A way or relation without an extent is a spot as far as the area goes; spots only for settlements and camps.
            guard extent != nil || key == "place" || spotKinds.contains(value) else { return }
            let id = "\(extent == nil ? "N" : type)\(osmID)"
            let key2 = "\(name)|\(value)|\(Int((lat * 100).rounded()))|\(Int((lon * 100).rounded()))"
            guard !seen.contains(id), !seen.contains(key2) else { return }
            seen.insert(id)
            seen.insert(key2)
            let region = region.compactMap { $0 as? String }.filter { $0 != name }
            places.append(FoundPlace(id: id, name: name, kind: kindName(value), tag: value, region: region.joined(separator: ", "),
                                     centre: GeoPoint(lat: lat, lon: lon), extent: extent))
        }
    }

    /// Nominatim writes coordinates as strings ("-24.8213030").
    private static func coordinate(_ any: Any?) -> Double? {
        if let s = any as? String { return Double(s) }
        return number(any)
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

    /// The download area for a place: its boundary (grown by as much as it was simplified, so nothing along its edge is
    /// cut off; a camp's or a town's also by the area around it), else its extent, else (a spot) the circle around it.
    static func area(for place: FoundPlace, rings: [[GeoPoint]]) -> CoverageShape {
        let around = reserveKinds.contains(place.tag) || place.tag == "administrative" ? 0 : min(place.radiusMeters, 1_000)
        let grow = max(simplification(for: place) * 111_320, around)
        if !rings.isEmpty { return CoverageShape(polygons: rings, bufferMeters: grow) }
        if let e = place.extent, place.hasBoundary {
            return CoverageShape(polygons: [[GeoPoint(lat: e.minLat, lon: e.minLon), GeoPoint(lat: e.minLat, lon: e.maxLon),
                                             GeoPoint(lat: e.maxLat, lon: e.maxLon), GeoPoint(lat: e.maxLat, lon: e.minLon)]],
                                 bufferMeters: around)
        }
        return CoverageShape(points: [place.centre], bufferMeters: place.radiusMeters)
    }

    /// Two answers in one, each place once: reserves and parks first, the largest first (the whole of a reserve before
    /// pieces of it), then the rest in the search's order.
    static func merged(_ lists: [[FoundPlace]]) -> [FoundPlace] {
        var seen = Set<String>()
        let all = lists.joined().filter { seen.insert($0.id).inserted }
        let reserves = all.filter { $0.hasBoundary && reserveKinds.contains($0.tag) }
            .sorted { ($0.extentKm2 ?? 0) > ($1.extentKm2 ?? 0) }
        return reserves + all.filter { !($0.hasBoundary && reserveKinds.contains($0.tag)) }
    }

    /// The search again without a plural's "s" ("Sabi Sands" → "Sabi Sand"), as names are often written both ways; nil
    /// when there is none.
    static func singular(_ text: String) -> String? {
        let words = text.split(separator: " ").map(String.init)
        let changed = words.map { w -> String in
            w.count > 3 && w.lowercased().hasSuffix("s") && !w.lowercased().hasSuffix("ss") ? String(w.dropLast()) : w
        }
        return changed == words ? nil : changed.joined(separator: " ")
    }

    /// How much Nominatim may simplify a boundary (degrees): about a 1 500th of its size, at least 10 m.
    static func simplification(for place: FoundPlace) -> Double {
        guard let e = place.extent else { return 0.0001 }
        return max(0.0001, max(e.maxLat - e.minLat, e.maxLon - e.minLon) / 1500)
    }
}

// MARK: - Which service searches

/// The place search's requests: Photon first (forgiving of spelling, biased to the map's middle), and Nominatim's own
/// search when Photon fails. Both apps build the same requests.
extension Places {
    /// At most this many results per search, from either service.
    static let searchLimit = 20

    static func photonURL(_ text: String, near: GeoPoint?) -> URL {
        var c = URLComponents(string: "https://photon.komoot.io/api/")!
        var items = [URLQueryItem(name: "q", value: text), URLQueryItem(name: "limit", value: String(searchLimit))]
        if let p = near {
            items += [URLQueryItem(name: "lat", value: String(format: "%.4f", p.lat)),
                      URLQueryItem(name: "lon", value: String(format: "%.4f", p.lon))]
        }
        c.queryItems = items
        return c.url!
    }

    /// Nominatim's search for the same text: the same limit, each result's county, state and country (`addressdetails`)
    /// for the same line under its name, and Photon's nearness to the map's middle as a preferred box around it plus,
    /// where the map is, the countries around it (`nominatimCountries`). The language is the one the request's
    /// Accept-Language header asks for, as with Photon.
    static func nominatimURL(_ text: String, near: GeoPoint?, countries: [String]?) -> URL {
        var c = URLComponents(string: "https://nominatim.openstreetmap.org/search")!
        var items = [URLQueryItem(name: "q", value: text), URLQueryItem(name: "format", value: "jsonv2"),
                     URLQueryItem(name: "addressdetails", value: "1"), URLQueryItem(name: "limit", value: String(searchLimit))]
        if let p = near {
            let box = [p.lon - 1, p.lat + 1, p.lon + 1, p.lat - 1].map { String(format: "%.4f", $0) }.joined(separator: ",")
            items.append(URLQueryItem(name: "viewbox", value: box))
        }
        if let countries = countries, !countries.isEmpty {
            items.append(URLQueryItem(name: "countrycodes", value: countries.joined(separator: ",")))
        }
        c.queryItems = items
        return c.url!
    }

    /// The countries Nominatim searches first for a map in southern Africa (South Africa and its neighbours, as far as
    /// Mozambique's north): Nominatim, unlike Photon, doesn't rank by nearness, and misses "Sabi Sand" among the whole
    /// world's names. Nil elsewhere, or with no map.
    static func nominatimCountries(near: GeoPoint?) -> [String]? {
        guard let p = near, (-35.5 ... -10.3).contains(p.lat), (11.5 ... 41.0).contains(p.lon) else { return nil }
        return ["za", "ls", "sz", "na", "bw", "zw", "mz"]
    }

    /// The Nominatim searches for one text, each only if the one before found no place: within the countries around
    /// the map, then the whole world.
    static func nominatimCountryPasses(near: GeoPoint?) -> [[String]?] {
        guard let countries = nominatimCountries(near: near) else { return [nil] }
        return [countries, nil]
    }
}

/// How a request to a search service ended.
enum PlaceRequestOutcome {
    /// An HTTP answer, any status.
    case answer(status: Int, body: Data)
    /// The phone has no connection: no service would answer.
    case offline
    /// Timed out (`PlaceSearchFallback.photonTimeout` for Photon), refused, or any other network error.
    case failed
}

/// Choosing the service for a place search: Photon until it fails (a network error, no answer within `photonTimeout`,
/// an HTTP error, an answer that isn't Photon's), then Nominatim for `photonCoolDown` before Photon is tried again.
/// Nominatim is asked at most once a second (its usage policy), boundaries included, and only per search, never per
/// keystroke. A working Photon that finds nothing has found nothing: that doesn't go to Nominatim.
struct PlaceSearchFallback {
    /// How long Photon may take to answer, in seconds (the same in both apps).
    static let photonTimeout: TimeInterval = 5
    /// How long a Photon failure is remembered, in seconds: searches go straight to Nominatim meanwhile.
    static let photonCoolDown: TimeInterval = 10 * 60
    /// The least time between two Nominatim requests, in seconds.
    static let nominatimInterval: TimeInterval = 1

    enum Source: Equatable { case photon, nominatim }

    /// What a Photon request means for the search.
    enum PhotonVerdict: Equatable {
        /// Photon answered: these are the places (none is a real "nothing found").
        case places([FoundPlace])
        /// Photon failed: search with Nominatim.
        case useNominatim
        /// No connection: neither service can answer.
        case offline
    }

    private(set) var photonFailedAt: Date?
    private var nextNominatim: Date?

    func source(at now: Date) -> Source {
        guard let failed = photonFailedAt else { return .photon }
        let since = now.timeIntervalSince(failed)
        return since >= 0 && since < Self.photonCoolDown ? .nominatim : .photon
    }

    /// Takes in how a Photon request ended, remembering a failure (and forgetting it once Photon answers again).
    mutating func photon(_ outcome: PlaceRequestOutcome, at now: Date) -> PhotonVerdict {
        switch outcome {
        case .offline:
            return .offline
        case .failed:
            photonFailedAt = now
            return .useNominatim
        case let .answer(status, body):
            guard status == 200, let places = Places.photonPlaces(body) else {
                photonFailedAt = now
                return .useNominatim
            }
            photonFailedAt = nil
            return .places(places)
        }
    }

    /// Books the next Nominatim request and says how long to wait before sending it (0: now).
    mutating func nominatimDelay(at now: Date) -> TimeInterval {
        let slot = max(now, nextNominatim ?? now)
        nextNominatim = slot.addingTimeInterval(Self.nominatimInterval)
        return slot.timeIntervalSince(now)
    }

    /// The places in a Nominatim search answer; nil when it didn't answer (the search failed).
    static func nominatimPlaces(_ outcome: PlaceRequestOutcome) -> [FoundPlace]? {
        guard case let .answer(status, body) = outcome, status == 200 else { return nil }
        return Places.nominatimPlaces(body)
    }
}
