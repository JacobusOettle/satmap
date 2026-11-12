import Foundation
import os

private let placeLog = Logger(subsystem: "com.koonap.spoor", category: "places")

/// Finding a place by name for an offline area: Photon (komoot's OpenStreetMap search, forgiving of spelling) for the
/// names, or Nominatim's search (OpenStreetMap's own) while Photon fails (`PlaceSearchFallback`), then Nominatim for
/// the chosen place's boundary. Both are public services: one search per tap of Search, one boundary per place chosen,
/// and Nominatim at most once a second.
enum PlaceSearch {
    enum Failure: LocalizedError {
        case offline
        case unavailable

        var errorDescription: String? {
            switch self {
            case .offline: return "No internet connection. Places are looked up online; try again when connected."
            case .unavailable: return "The place search didn't answer. Try again in a moment."
            }
        }
    }

    /// Identifies the app to the OpenStreetMap services, as their usage policies ask.
    private static let userAgent = "Spoor/1.0 (iPhone offline map; https://github.com/JacobusOettle/satmap)"

    private static func session(timeout: TimeInterval, total: TimeInterval) -> URLSession {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = total
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        return URLSession(configuration: c)
    }

    /// Nominatim (searches and boundaries).
    private static let nominatimSession = session(timeout: 20, total: 60)
    /// Photon: an answer within `PlaceSearchFallback.photonTimeout`, or the search goes to Nominatim.
    private static let photonSession = session(timeout: PlaceSearchFallback.photonTimeout, total: PlaceSearchFallback.photonTimeout)

    /// Which service searches, and when Nominatim may next be asked, for the app's lifetime.
    private actor Choice {
        private var fallback = PlaceSearchFallback()
        func source() -> PlaceSearchFallback.Source { fallback.source(at: Date()) }
        func photon(_ outcome: PlaceRequestOutcome) -> PlaceSearchFallback.PhotonVerdict { fallback.photon(outcome, at: Date()) }
        func nominatimDelay() -> TimeInterval { fallback.nominatimDelay(at: Date()) }
    }

    private static let choice = Choice()

    /// Places matching a name, nearest `near` (the map's middle) first; reserves and parks before the rest, the whole of
    /// one before pieces of it. A name with a plural ("Sabi Sands") is also searched without it.
    static func search(_ text: String, near: GeoPoint?) async throws -> [FoundPlace] {
        let first = try await find(text, near: near)
        guard let other = Places.singular(text) else { return Places.merged([first]) }
        let second = (try? await find(other, near: near)) ?? []
        return Places.merged([first, second])
    }

    /// One text searched: with Photon, or with Nominatim while Photon fails.
    private static func find(_ text: String, near: GeoPoint?) async throws -> [FoundPlace] {
        if await choice.source() == .photon {
            let outcome = try await request(Places.photonURL(text, near: near), session: photonSession)
            switch await choice.photon(outcome) {
            case let .places(places): return places
            case .offline: throw Failure.offline
            case .useNominatim: placeLog.notice("Photon failed: searching with Nominatim for a while")
            }
        }
        let passes = Places.nominatimCountryPasses(near: near)
        for (i, countries) in passes.enumerated() {
            let outcome = try await nominatim(Places.nominatimURL(text, near: near, countries: countries))
            guard let places = PlaceSearchFallback.nominatimPlaces(outcome) else { throw failure(outcome) }
            if !places.isEmpty || i == passes.count - 1 { return places }
        }
        return []
    }

    /// The area to download for a place: its boundary (simplified to suit its size), or for a spot, the circle around
    /// it. A place that has no boundary to be had falls back to its extent; a failed request is an error (try again).
    static func area(for place: FoundPlace) async throws -> CoverageShape {
        guard place.hasBoundary else { return Places.area(for: place, rings: []) }
        var c = URLComponents(string: "https://nominatim.openstreetmap.org/lookup")!
        c.queryItems = [URLQueryItem(name: "osm_ids", value: place.id),
                        URLQueryItem(name: "format", value: "geojson"),
                        URLQueryItem(name: "polygon_geojson", value: "1"),
                        URLQueryItem(name: "polygon_threshold", value: String(format: "%.5f", Places.simplification(for: place)))]
        let outcome = try await nominatim(c.url!)
        guard case let .answer(200, data) = outcome else { throw failure(outcome) }
        let rings = Places.outerRings(geoJSON: data)
        placeLog.debug("boundary of \(place.id, privacy: .public): \(rings.count) rings, \(rings.reduce(0) { $0 + $1.count }) points")
        return Places.area(for: place, rings: rings)
    }

    /// A Nominatim request, no sooner than a second after the one before.
    private static func nominatim(_ url: URL) async throws -> PlaceRequestOutcome {
        let wait = await choice.nominatimDelay()
        if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        return try await request(url, session: nominatimSession)
    }

    private static func failure(_ outcome: PlaceRequestOutcome) -> Failure {
        if case .offline = outcome { return .offline }
        return .unavailable
    }

    /// How a request ended; throws only when the search was cancelled.
    private static func request(_ url: URL, session: URLSession) async throws -> PlaceRequestOutcome {
        do {
            let (data, response) = try await session.data(from: url)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status != 200 { placeLog.error("\(url.host ?? "", privacy: .public): HTTP \(status)") }
            return .answer(status: status, body: data)
        } catch {
            if Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled {
                throw CancellationError()
            }
            placeLog.error("\(url.host ?? "", privacy: .public): \(error.localizedDescription, privacy: .public)")
            if let e = error as? URLError { return .networkError(code: e.code.rawValue) }
            return .failed
        }
    }
}
