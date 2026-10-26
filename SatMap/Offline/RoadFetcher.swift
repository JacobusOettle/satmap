import Foundation

/// Roads and tracks in an area from OpenStreetMap, through the public Overpass API (no account needed).
enum RoadFetcher {
    /// The main server, then mirrors for when it is busy (public Overpass servers are often overloaded).
    private static let servers = [
        URL(string: "https://overpass-api.de/api/interpreter")!,
        URL(string: "https://overpass.private.coffee/api/interpreter")!,
        URL(string: "https://overpass.kumi.systems/api/interpreter")!,
    ]

    enum Failure: LocalizedError {
        case offline
        case unavailable

        var errorDescription: String? {
            switch self {
            case .offline: return "No internet connection. Roads are looked up online; try again when connected."
            case .unavailable: return "The OpenStreetMap road server is busy or unreachable. Try again in a minute."
            }
        }
    }

    /// Road centre lines inside the area (clipped to it).
    static func roads(in area: CoverageShape) async throws -> [[GeoPoint]] {
        guard let query = Roads.overpassQuery(for: area) else { return [] }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let body = "data=" + (query.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")

        for server in servers {
            try Task.checkCancellation()
            var request = URLRequest(url: server, timeoutInterval: 70)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(body.utf8)
            let data: Data
            do {
                let (d, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { continue }
                data = d
            } catch let e as URLError where e.code == .notConnectedToInternet || e.code == .dataNotAllowed {
                throw Failure.offline
            } catch let e as URLError where e.code == .cancelled {
                throw CancellationError()
            } catch {
                continue
            }
            guard let lines = try? Roads.parse(data) else { continue }
            return Roads.clip(lines, to: area)
        }
        throw Failure.unavailable
    }
}
