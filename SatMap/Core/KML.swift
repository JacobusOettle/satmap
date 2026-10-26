import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

enum KMLGeometry: Hashable {
    case point(GeoPoint)
    case line([GeoPoint])
    case polygon(outer: [GeoPoint], holes: [[GeoPoint]])
}

struct KMLFeature: Hashable {
    var name: String?
    var geometry: KMLGeometry
}

struct KMLDocument {
    var name: String?
    var features: [KMLFeature]

    var bounds: GeoBounds? {
        var all: [GeoPoint] = []
        for f in features {
            switch f.geometry {
            case .point(let p): all.append(p)
            case .line(let pts): all.append(contentsOf: pts)
            case .polygon(let outer, _): all.append(contentsOf: outer)
            }
        }
        return GeoBounds(points: all)
    }

    /// The area to download offline for this file: block polygons filled, lines and points buffered.
    func coverageShape(bufferMeters: Double) -> CoverageShape {
        var shape = CoverageShape(bufferMeters: bufferMeters)
        for f in features {
            switch f.geometry {
            case .point(let p): shape.points.append(p)
            case .line(let pts): shape.lines.append(pts)
            case .polygon(let outer, _): shape.polygons.append(outer)
            }
        }
        return shape
    }
}

enum KMLError: Error, LocalizedError {
    case unreadable
    case noKMLInArchive
    case noGeometry

    var errorDescription: String? {
        switch self {
        case .unreadable: return "The file could not be read as KML."
        case .noKMLInArchive: return "The KMZ archive does not contain a .kml file."
        case .noGeometry: return "The file contains no points, lines or polygons."
        }
    }
}

enum KMLParser {
    /// Parses .kml or .kmz data (KMZ is detected by its zip signature).
    static func parse(_ data: Data) throws -> KMLDocument {
        var kmlData = data
        if data.count >= 4, data.prefix(4) == Data([0x50, 0x4B, 0x03, 0x04]) {
            let archive = try ZipArchive(data: data)
            let kmlEntries = archive.entries.filter { $0.name.lowercased().hasSuffix(".kml") }
            // Google Earth writes the main document as doc.kml at the root.
            guard let entry = kmlEntries.first(where: { $0.name.lowercased() == "doc.kml" })
                ?? kmlEntries.min(by: { $0.name.split(separator: "/").count < $1.name.split(separator: "/").count }) else {
                throw KMLError.noKMLInArchive
            }
            kmlData = try archive.extract(entry)
        }
        let delegate = KMLParserDelegate()
        let parser = XMLParser(data: kmlData)
        parser.shouldProcessNamespaces = false
        parser.delegate = delegate
        guard parser.parse() || !delegate.features.isEmpty else { throw KMLError.unreadable }
        guard !delegate.features.isEmpty else { throw KMLError.noGeometry }
        return KMLDocument(name: delegate.documentName, features: delegate.features)
    }

    /// Parses a KML `<coordinates>` string: whitespace-separated "lon,lat[,alt]" tuples.
    static func coordinates(_ text: String) -> [GeoPoint] {
        var out: [GeoPoint] = []
        for token in text.split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "\r" }) {
            let parts = token.split(separator: ",")
            guard parts.count >= 2, let lon = Double(parts[0]), let lat = Double(parts[1]),
                  (-90...90).contains(lat), (-180...180).contains(lon) else { continue }
            out.append(GeoPoint(lat: lat, lon: lon))
        }
        return out
    }
}

private final class KMLParserDelegate: NSObject, XMLParserDelegate {
    var features: [KMLFeature] = []
    var documentName: String?

    private var stack: [String] = []
    private var text = ""
    private var placemarkName: String?
    private var inPlacemark = false
    private var pendingGeometries: [KMLGeometry] = []
    private var outer: [GeoPoint] = []
    private var holes: [[GeoPoint]] = []

    private func local(_ name: String) -> String {
        if let i = name.lastIndex(of: ":") { return String(name[name.index(after: i)...]) }
        return name
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = local(elementName)
        stack.append(name)
        text = ""
        switch name {
        case "Placemark":
            inPlacemark = true
            placemarkName = nil
            pendingGeometries = []
        case "Polygon":
            outer = []
            holes = []
        default:
            break
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
        defer {
            if !stack.isEmpty { stack.removeLast() }
            text = ""
        }
        let parent = stack.count >= 2 ? stack[stack.count - 2] : ""
        switch name {
        case "name":
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if parent == "Placemark" {
                placemarkName = value.isEmpty ? nil : value
            } else if parent == "Document" && documentName == nil && !value.isEmpty {
                documentName = value
            }
        case "coordinates":
            guard inPlacemark else { return }
            let pts = KMLParser.coordinates(text)
            // stack: ... Geometry, [outerBoundaryIs|innerBoundaryIs, LinearRing,] coordinates
            let ancestors = Array(stack.dropLast().suffix(3))
            if ancestors.contains("outerBoundaryIs") {
                outer = pts
            } else if ancestors.contains("innerBoundaryIs") {
                if pts.count >= 3 { holes.append(pts) }
            } else if parent == "LineString" || parent == "LinearRing" {
                if pts.count >= 2 { pendingGeometries.append(.line(pts)) } else if let p = pts.first { pendingGeometries.append(.point(p)) }
            } else if parent == "Point" {
                if let p = pts.first { pendingGeometries.append(.point(p)) }
            }
        case "Polygon":
            if outer.count >= 3 { pendingGeometries.append(.polygon(outer: outer, holes: holes)) }
            outer = []
            holes = []
        case "Placemark":
            for g in pendingGeometries { features.append(KMLFeature(name: placemarkName, geometry: g)) }
            pendingGeometries = []
            inPlacemark = false
        default:
            break
        }
    }
}
