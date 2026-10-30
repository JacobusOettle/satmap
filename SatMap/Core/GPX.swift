import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

enum GPXError: Error, LocalizedError {
    case unreadable
    case noGeometry

    var errorDescription: String? {
        switch self {
        case .unreadable: return "The file could not be read as GPX."
        case .noGeometry: return "The file contains no waypoints, routes or tracks."
        }
    }
}

/// GPX files (1.0 and 1.1, with or without namespace prefixes), read into the same document as a KML file so the app
/// treats both alike: each waypoint is a point, each route a line, and each track one line through all its segments in
/// order (a route or track of one point is a point). A feature keeps its name, its description (desc, then cmt), and
/// its type (as data named "type") and the simple values in its extensions as data, for `FileShapes`. GPX has no
/// styles.
enum GPXParser {
    static func parse(_ data: Data) throws -> KMLDocument {
        let delegate = GPXParserDelegate()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        parser.delegate = delegate
        // What was read before an error still counts, as with KML.
        guard parser.parse() || !delegate.features.isEmpty else { throw GPXError.unreadable }
        guard !delegate.features.isEmpty else { throw GPXError.noGeometry }
        return KMLDocument(name: delegate.documentName, features: delegate.features, format: .gpx)
    }

    /// Whether the file's first element is <gpx> (with or without a prefix).
    static func isGPX(_ data: Data) -> Bool {
        guard let name = rootElement(data) else { return false }
        return name == "gpx" || name.hasSuffix(":gpx")
    }

    /// The name of the file's first element as written ("gpx", "gpx:gpx"), after any XML declaration, comments and
    /// document type; nil if there is none in its first 64 KB. Read from the bytes: UTF-8 (or another encoding that
    /// writes markup in ASCII) or UTF-16 (by its byte order mark, or a zero byte next to the first "<").
    static func rootElement(_ data: Data) -> String? {
        var b = [UInt8](data.prefix(65_536))
        func picking(from start: Int) -> [UInt8] { stride(from: start, to: b.count, by: 2).map { b[$0] } }
        if b.starts(with: [0xFE, 0xFF]) {
            b = picking(from: 3)
        } else if b.starts(with: [0xFF, 0xFE]) {
            b = picking(from: 2)
        } else if b.starts(with: [0x00, 0x3C]) {
            b = picking(from: 1)
        } else if b.starts(with: [0x3C, 0x00]) {
            b = picking(from: 0)
        } else if b.starts(with: [0xEF, 0xBB, 0xBF]) {
            b.removeFirst(3)
        }
        func at(_ i: Int, _ s: String) -> Bool { b[i...].starts(with: Array(s.utf8)) }
        func after(_ end: String, from i: Int) -> Int? {
            var j = i
            while j < b.count {
                if at(j, end) { return j + end.utf8.count }
                j += 1
            }
            return nil
        }
        var i = 0
        while i < b.count {
            switch b[i] {
            case 0x20, 0x09, 0x0A, 0x0D:
                i += 1
            case 0x3C where at(i, "<?"):
                guard let j = after("?>", from: i + 2) else { return nil }
                i = j
            case 0x3C where at(i, "<!--"):
                guard let j = after("-->", from: i + 4) else { return nil }
                i = j
            case 0x3C where at(i, "<!"):
                // A document type, to its ">" (past any declarations in [ ]).
                var depth = 0
                i += 2
                while i < b.count, b[i] != 0x3E || depth > 0 {
                    if b[i] == 0x5B { depth += 1 } else if b[i] == 0x5D { depth -= 1 }
                    i += 1
                }
                i += 1
            case 0x3C:
                var j = i + 1
                while j < b.count, ![0x20, 0x09, 0x0A, 0x0D, 0x3E, 0x2F].contains(b[j]) { j += 1 }
                return j > i + 1 ? String(decoding: b[(i + 1)..<j], as: UTF8.self) : nil
            default:
                return nil
            }
        }
        return nil
    }
}

private final class GPXParserDelegate: NSObject, XMLParserDelegate {
    var features: [KMLFeature] = []
    var documentName: String?

    /// What a waypoint, route or track holds in GPX itself. Any other simple element in one (GPX 1.0 puts extensions
    /// there) or in its <extensions> is data.
    private static let gpxElements: Set<String> = [
        "ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link", "url", "urlname", "sym", "type",
        "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid", "number", "extensions", "rtept", "trkseg",
    ]

    private var stack: [String] = []
    /// Whether each open element has an element inside it (a simple one holds text only).
    private var hasChildren: [Bool] = []
    private var text = ""

    /// The waypoint, route or track being read: its place in the stack, and what it has so far.
    private var featureDepth: Int?
    private var featureName: String?
    private var desc: String?
    private var cmt: String?
    private var data: [KMLData] = []
    private var points: [GeoPoint] = []

    private func local(_ name: String) -> String {
        if let i = name.lastIndex(of: ":") { return String(name[name.index(after: i)...]) }
        return name
    }

    private static func point(_ attributes: [String: String]) -> GeoPoint? {
        func number(_ key: String) -> Double? {
            attributes[key].flatMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        }
        guard let lat = number("lat"), let lon = number("lon"), (-90...90).contains(lat), (-180...180).contains(lon) else {
            return nil
        }
        return GeoPoint(lat: lat, lon: lon)
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = local(elementName)
        if !hasChildren.isEmpty { hasChildren[hasChildren.count - 1] = true }
        stack.append(name)
        hasChildren.append(false)
        text = ""
        let depth = stack.count - 1
        if let d = featureDepth {
            let parent = stack[depth - 1]
            if (name == "rtept" && parent == "rte" && depth == d + 1) || (name == "trkpt" && parent == "trkseg" && depth == d + 2),
               let p = Self.point(attributeDict) {
                points.append(p)
            }
        } else if name == "wpt" || name == "rte" || name == "trk" {
            featureDepth = depth
            featureName = nil
            desc = nil
            cmt = nil
            data = []
            points = []
            if name == "wpt", let p = Self.point(attributeDict) { points.append(p) }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text += string
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if let s = String(data: CDATABlock, encoding: .utf8) { text += s }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let name = local(elementName)
        let simple = hasChildren.last == false
        defer {
            if !stack.isEmpty { stack.removeLast() }
            if !hasChildren.isEmpty { hasChildren.removeLast() }
            text = ""
        }
        guard !stack.isEmpty else { return }
        let depth = stack.count - 1
        let parent = depth >= 1 ? stack[depth - 1] : ""
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let d = featureDepth else {
            // The file's name: in <metadata> (GPX 1.1) or in <gpx> itself (1.0).
            if name == "name", parent == "metadata" || parent == "gpx", documentName == nil, !value.isEmpty {
                documentName = value
            }
            return
        }
        if depth == d {
            finishFeature(name)
            featureDepth = nil
        } else if depth == d + 1 {
            switch name {
            case "name": featureName = value.isEmpty ? nil : value
            case "desc": desc = value.isEmpty ? nil : value
            case "cmt": cmt = value.isEmpty ? nil : value
            case "type": if !value.isEmpty { data.append(KMLData(name: "type", value: value)) }
            default: if simple, !Self.gpxElements.contains(name), !value.isEmpty { data.append(KMLData(name: name, value: value)) }
            }
        } else if stack[d + 1] == "extensions", simple, !value.isEmpty {
            data.append(KMLData(name: name, value: value))
        }
    }

    private func finishFeature(_ kind: String) {
        guard let first = points.first else { return }
        let geometry: KMLGeometry = kind == "wpt" || points.count == 1 ? .point(first) : .line(points)
        let texts = [desc, cmt == desc ? nil : cmt].compactMap { $0 }
        features.append(KMLFeature(name: featureName, geometry: geometry,
                                   description: texts.isEmpty ? nil : texts.joined(separator: "\n"), data: data))
    }
}
