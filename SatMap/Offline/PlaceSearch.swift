import Foundation
import os

private let placeLog = Logger(subsystem: "com.jacobusoettle.satmap", category: "places")

/// Finding a place by name for an offline area: Photon (komoot's OpenStreetMap search, forgiving of spelling) for the
/// names, then Nominatim (OpenStreetMap's own) for the chosen place's boundary. Both are public services: one search
/// per tap of Search, one boundary per place chosen.
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
    private static let userAgent = "SatMap/1.0 (iPhone offline map; https://github.com/JacobusOettle/satmap)"

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        return URLSession(configuration: c)
    }()

    /// Places matching a name, nearest `near` (the map's middle) first; reserves and parks before the rest, the whole of
    /// one before pieces of it. A name with a plural ("Sabi Sands") is also searched without it.
    static func search(_ text: String, near: GeoPoint?) async throws -> [FoundPlace] {
        let first = try await photon(text, near: near)
        guard let other = Places.singular(text) else { return Places.merged([first]) }
        let second = (try? await photon(other, near: near)) ?? []
        return Places.merged([first, second])
    }

    private static func photon(_ text: String, near: GeoPoint?) async throws -> [FoundPlace] {
        var c = URLComponents(string: "https://photon.komoot.io/api/")!
        var items = [URLQueryItem(name: "q", value: text), URLQueryItem(name: "limit", value: "20")]
        if let p = near {
            items += [URLQueryItem(name: "lat", value: String(format: "%.4f", p.lat)),
                      URLQueryItem(name: "lon", value: String(format: "%.4f", p.lon))]
        }
        c.queryItems = items
        let data = try await get(c.url!)
        return Places.photonResults(data)
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
        let data = try await get(c.url!)
        let rings = Places.outerRings(geoJSON: data)
        placeLog.debug("boundary of \(place.id, privacy: .public): \(rings.count) rings, \(rings.reduce(0) { $0 + $1.count }) points")
        return Places.area(for: place, rings: rings)
    }

    private static func get(_ url: URL) async throws -> Data {
        do {
            let (data, response) = try await session.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                placeLog.error("\(url.host ?? "", privacy: .public): HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
                throw Failure.unavailable
            }
            return data
        } catch let e as URLError where [.notConnectedToInternet, .networkConnectionLost, .dataNotAllowed].contains(e.code) {
            throw Failure.offline
        } catch is Failure {
            throw Failure.unavailable
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            placeLog.error("\(url.host ?? "", privacy: .public): \(error.localizedDescription, privacy: .public)")
            throw Failure.unavailable
        }
    }
}
