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
    /// How the file draws it (nil: no style of its own).
    var style: KMLStyle?
    /// Its description (GPX: desc, then cmt on a line of its own).
    var description: String? = nil
    /// The names of the Folders and Documents it is in, innermost first (KML).
    var folders: [String] = []
    /// Its data, in file order: KML ExtendedData (Data and SimpleData), and a GPX feature's type (named "type") and
    /// the simple values in its extensions.
    var data: [KMLData] = []
    /// A KML LinearRing of its own: drawn as a line, but closed (`FileShapes`).
    var isRing = false
}

/// A named value a file gives a feature (`KMLFeature.data`).
struct KMLData: Hashable {
    var name: String
    var value: String
}

/// A KML colour: written aabbggrr in hex (alpha first, then blue, green, red).
struct KMLColor: Hashable {
    var red: Double, green: Double, blue: Double, alpha: Double

    /// From "aabbggrr" (also with a leading "#", or "bbggrr" opaque); nil if it isn't that.
    init?(kml text: String) {
        var hex = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") { hex.removeFirst() }
        if hex.count == 6 { hex = "ff" + hex }
        guard hex.count == 8, let v = UInt32(hex, radix: 16) else { return nil }
        alpha = Double(v >> 24 & 0xff) / 255
        blue = Double(v >> 16 & 0xff) / 255
        green = Double(v >> 8 & 0xff) / 255
        red = Double(v & 0xff) / 255
    }
}

/// The parts of a KML style SatMap draws: outlines and lines (LineStyle), polygon fills (PolyStyle) and point
/// icons' colour (IconStyle). Unset parts are nil (the app's own look).
struct KMLStyle: Hashable {
    var lineColor: KMLColor?
    var lineWidth: Double?
    var fillColor: KMLColor?
    var fill: Bool?
    var outline: Bool?
    var iconColor: KMLColor?

    var isEmpty: Bool { self == KMLStyle() }

    /// This style's parts, and `base`'s where this one has none (a placemark's own style over its shared one).
    func over(_ base: KMLStyle) -> KMLStyle {
        KMLStyle(lineColor: lineColor ?? base.lineColor, lineWidth: lineWidth ?? base.lineWidth,
                 fillColor: fillColor ?? base.fillColor, fill: fill ?? base.fill, outline: outline ?? base.outline,
                 iconColor: iconColor ?? base.iconColor)
    }
}

/// A KML, KMZ or GPX file (`GPXParser` reads GPX into the same document).
struct KMLDocument {
    enum Format {
        case kml
        case gpx
    }

    var name: String?
    var features: [KMLFeature]
    /// KML (or KMZ) or GPX: GPX has no styles, and its closed tracks stay lines (`FileShapes`).
    var format: Format = .kml

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

    /// The file's geometry as it is drawn: polygons filled, lines and points, all grown by `bufferMeters`. What a file
    /// saves offline is `FileShapes`.
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
    /// A file for the Blocks list: GPX when its first element is <gpx> (`GPXParser.isGPX`), else KML or KMZ.
    static func parseKMLOrGPX(_ data: Data) throws -> KMLDocument {
        try GPXParser.isGPX(data) ? GPXParser.parse(data) : parse(data)
    }

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
        return KMLDocument(name: delegate.documentName, features: delegate.styledFeatures())
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
    private var placemarkDescription: String?
    private var placemarkData: [KMLData] = []
    /// The name of the Data or SimpleData being read, and a Data's value.
    private var dataName: String?
    private var dataValue: String?
    private var inPlacemark = false
    /// Each geometry, and whether it is a LinearRing of its own.
    private var pendingGeometries: [(geometry: KMLGeometry, isRing: Bool)] = []
    /// The Folders and Documents around what is being read, outermost first, with their names (nil: none yet).
    private var containers: [String?] = []
    private var outer: [GeoPoint] = []
    private var holes: [[GeoPoint]] = []

    /// Styles: shared ones by id (anywhere in the file, as Google Earth reads them), style maps by id (the style for
    /// their "normal" key: a link, or a style of its own), and each feature's link and own style, joined up at the end
    /// (shared styles may come after the placemarks that use them).
    private var styles: [String: KMLStyle] = [:]
    private var styleMaps: [String: (url: String?, style: KMLStyle?)] = [:]
    private var featureStyles: [(url: String?, own: KMLStyle?)] = []
    private var style: KMLStyle?
    private var styleID: String?
    /// Google Earth Web and the Earth app write each shared style as an id-less Style in a gx:CascadingStyle with the id.
    private var cascadingID: String?
    private var placemarkStyleURL: String?
    private var placemarkStyle: KMLStyle?
    private var styleMapID: String?
    private var pair: (key: String?, url: String?, style: KMLStyle?)?

    /// The features with their styles: a placemark's own style over the one its styleUrl names (through a style map's
    /// normal style).
    func styledFeatures() -> [KMLFeature] {
        zip(features, featureStyles).map { feature, s in
            var f = feature
            var shared: KMLStyle?
            if let id = s.url.flatMap(Self.styleID) {
                if let m = styleMaps[id] {
                    shared = m.style ?? m.url.flatMap(Self.styleID).flatMap { styles[$0] }
                } else {
                    shared = styles[id]
                }
            }
            let merged = (s.own ?? KMLStyle()).over(shared ?? KMLStyle())
            f.style = merged.isEmpty ? nil : merged
            return f
        }
    }

    /// The id a styleUrl points to: "#id", or "file.kml#id" (only styles in this file are known).
    private static func styleID(_ url: String) -> String? {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let hash = u.lastIndex(of: "#") else { return u.isEmpty ? nil : u }
        let id = String(u[u.index(after: hash)...])
        return id.isEmpty ? nil : id
    }

    private static func flag(_ text: String) -> Bool? {
        switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "1", "true": return true
        case "0", "false": return false
        default: return nil
        }
    }

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
            placemarkDescription = nil
            placemarkData = []
            pendingGeometries = []
            placemarkStyleURL = nil
            placemarkStyle = nil
        case "Folder", "Document":
            containers.append(nil)
        case "Data", "SimpleData":
            dataName = attributeDict["name"]
            dataValue = nil
        case "Polygon":
            outer = []
            holes = []
        case "Style":
            style = KMLStyle()
            let parent = stack.count >= 2 ? stack[stack.count - 2] : ""
            styleID = attributeDict["id"] ?? (parent == "CascadingStyle" ? cascadingID : nil)
        case "CascadingStyle":
            cascadingID = attributeDict["kml:id"] ?? attributeDict["id"]
        case "StyleMap":
            styleMapID = attributeDict["id"]
        case "Pair":
            pair = (key: nil, url: nil, style: nil)
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
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch name {
        case "color" where style != nil:
            let c = KMLColor(kml: value)
            switch parent {
            case "LineStyle": style?.lineColor = c
            case "PolyStyle": style?.fillColor = c
            case "IconStyle": style?.iconColor = c
            default: break
            }
        case "width" where style != nil && parent == "LineStyle":
            style?.lineWidth = Double(value)
        case "fill" where style != nil && parent == "PolyStyle":
            style?.fill = Self.flag(value)
        case "outline" where style != nil && parent == "PolyStyle":
            style?.outline = Self.flag(value)
        case "Style":
            guard let s = style else { return }
            style = nil
            if pair != nil {
                pair?.style = s
            } else if parent == "Placemark" && inPlacemark {
                placemarkStyle = s
            } else if let id = styleID {
                styles[id] = s
            }
            styleID = nil
        case "key" where parent == "Pair":
            pair?.key = value
        case "styleUrl":
            if pair != nil {
                pair?.url = value
            } else if parent == "Placemark" && inPlacemark {
                placemarkStyleURL = value
            }
        case "Pair":
            if let p = pair, let id = styleMapID, p.key == nil || p.key == "normal" {
                styleMaps[id] = (url: p.url, style: p.style)
            }
            pair = nil
        case "StyleMap":
            styleMapID = nil
        case "CascadingStyle":
            cascadingID = nil
        case "name":
            if parent == "Placemark" {
                placemarkName = value.isEmpty ? nil : value
            } else if parent == "Folder" || parent == "Document" {
                if parent == "Document" && documentName == nil && !value.isEmpty { documentName = value }
                if let last = containers.indices.last, containers[last] == nil, !value.isEmpty { containers[last] = value }
            }
        case "Folder", "Document":
            if !containers.isEmpty { containers.removeLast() }
        case "description" where parent == "Placemark":
            placemarkDescription = value.isEmpty ? nil : value
        case "value" where parent == "Data":
            dataValue = value
        case "Data", "SimpleData":
            if inPlacemark, let n = dataName?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty {
                placemarkData.append(KMLData(name: n, value: name == "Data" ? dataValue ?? "" : value))
            }
            dataName = nil
            dataValue = nil
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
                if pts.count >= 2 {
                    pendingGeometries.append((.line(pts), parent == "LinearRing"))
                } else if let p = pts.first {
                    pendingGeometries.append((.point(p), false))
                }
            } else if parent == "Point" {
                if let p = pts.first { pendingGeometries.append((.point(p), false)) }
            }
        case "Polygon":
            if outer.count >= 3 { pendingGeometries.append((.polygon(outer: outer, holes: holes), false)) }
            outer = []
            holes = []
        case "Placemark":
            let folders = containers.reversed().compactMap { $0 }
            for g in pendingGeometries {
                features.append(KMLFeature(name: placemarkName, geometry: g.geometry, description: placemarkDescription,
                                           folders: folders, data: placemarkData, isRing: g.isRing))
                featureStyles.append((url: placemarkStyleURL, own: placemarkStyle))
            }
            pendingGeometries = []
            inPlacemark = false
        default:
            break
        }
    }
}
