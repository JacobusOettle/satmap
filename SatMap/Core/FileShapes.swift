import Foundation

/// What a KML or GPX file saves offline. Every feature is a dot, a line or an area:
/// - a point (KML Point, GPX waypoint) is a dot: imagery `Roads.corridorMeters` (15 m) around it;
/// - a KML Polygon is an area: imagery inside its outer ring (holes too), nothing around it;
/// - a KML LineString or LinearRing, or a GPX track (its segments joined in order) or route, is a line: imagery 15 m
///   each side of it; but it is an area (closed if it isn't) when it has at least 3 distinct points and
///   - the file marks it as an area (`mark(of:)`), or
///   - (KML only) it is closed (a LinearRing, or first and last points within `closedMeters`) and not marked as a road.
///   A closed GPX track or route stays a line: a drive that ends where it started must not save everything inside it.
/// Nothing between the parts is saved, however far apart they are. With "Only along roads" (when the file has areas),
/// roads are looked up inside the areas only and imagery is saved 15 m each side of them, and still 15 m around every
/// line and dot. Elevation is saved for all of `downloadShape` either way.
struct FileShapes: Equatable {
    var areas: [[GeoPoint]] = []
    var lines: [[GeoPoint]] = []
    var dots: [GeoPoint] = []

    init(_ document: KMLDocument) {
        for f in document.features {
            switch f.geometry {
            case .point(let p): dots.append(p)
            case .polygon(let outer, _): areas.append(outer)
            case .line(let pts):
                if Self.kind(of: f, in: document.format) == .area { areas.append(pts) } else { lines.append(pts) }
            }
        }
    }

    /// Whether "Only along roads" applies (roads are only looked up inside areas).
    var hasAreas: Bool { !areas.isEmpty }

    /// The saved area: inside every area, and `Roads.corridorMeters` around every line and dot. Elevation always covers
    /// all of it; imagery too, unless only along roads (`roadsToSave`).
    var downloadShape: CoverageShape {
        CoverageShape(polygons: areas, lines: lines, points: dots, bufferMeters: Roads.corridorMeters, polygonBufferMeters: 0)
    }

    /// Where "Only along roads" looks roads up (the shipped list, when `RoadPresets.covering` it, else the road server)
    /// and clips them to (`Roads.clip`): the areas only.
    var roadSearchShape: CoverageShape {
        CoverageShape(polygons: areas)
    }

    /// The roads an area saved "Only along roads" keeps (imagery is `Roads.corridor` of them): the roads found in
    /// `roadSearchShape` and clipped to it, then the file's lines, and each dot as a one-point line, so they keep their
    /// 15 m whatever the toggle.
    func roadsToSave(_ clippedRoads: [[GeoPoint]]) -> [[GeoPoint]] {
        clippedRoads + lines + dots.map { [$0] }
    }

    enum Kind {
        case dot
        case line
        case area
    }

    /// A closed line's first and last points are at most this far apart (KML).
    static let closedMeters = 5.0

    /// What a feature saves (see above).
    static func kind(of feature: KMLFeature, in format: KMLDocument.Format) -> Kind {
        switch feature.geometry {
        case .point: return .dot
        case .polygon: return .area
        case .line(let pts):
            guard hasThreeDistinctPoints(pts) else { return .line }
            let marked = mark(of: feature)
            if marked == .area { return .area }
            if format == .kml, marked != .road,
               feature.isRing || Geo.distance(pts[0], pts[pts.count - 1]) <= closedMeters {
                return .area
            }
            return .line
        }
    }

    private static func hasThreeDistinctPoints(_ pts: [GeoPoint]) -> Bool {
        var distinct: [GeoPoint] = []
        for p in pts where !distinct.contains(where: { $0.lat == p.lat && $0.lon == p.lon }) {
            distinct.append(p)
            if distinct.count == 3 { return true }
        }
        return false
    }

    // MARK: - Marks

    /// What a file says a feature is.
    enum Mark {
        case road
        case area
    }

    /// Words that mark a road or track.
    static let roadWords = ["road", "roads", "rd", "track", "tracks", "street", "streets", "highway", "route", "routes",
                            "path", "paths", "trail", "trails", "firebreak", "firebreaks", "fire break", "f/b", "drive",
                            "drivable", "driveway", "lane"]
    /// Words that mark an area.
    static let areaWords = ["area", "areas", "block", "blocks", "field", "fields", "boundary", "boundaries", "polygon",
                            "polygons", "zone", "zones", "paddock", "paddocks", "plot", "plots", "parcel", "parcels"]

    /// What the file says a feature is (nil: nothing). The first of these that says road or area, and not both, decides:
    /// 1. its data (`KMLFeature.data`, with a GPX type): the key highway or railway makes it a road; landuse, boundary,
    ///    building, leisure or natural, or area=yes, an area (a value of "no" says nothing); any other key's value is
    ///    read as text;
    /// 2. its name and description;
    /// 3. the name of each Folder it is in, innermost first, then the Document's (KML).
    /// Text says road or area when it has one of `roadWords` or `areaWords` as a whole word, in any case (see
    /// `textMark`).
    static func mark(of feature: KMLFeature) -> Mark? {
        if let m = decided(dataSays(feature.data)) { return m }
        let name = says(feature.name ?? ""), description = says(feature.description ?? "")
        if let m = decided((name.road || description.road, name.area || description.area)) { return m }
        for folder in feature.folders {
            if let m = decided(says(folder)) { return m }
        }
        return nil
    }

    /// What a text says: road, area, or nil (neither, or both). Words are runs of letters and numbers; a number may
    /// follow a word straight after ("Block12", but not "3rd"), and every other character but "/" counts as a space
    /// ("Fire-break" is "fire break").
    static func textMark(_ text: String) -> Mark? {
        decided(says(text))
    }

    private static func decided(_ s: (road: Bool, area: Bool)) -> Mark? {
        s.road == s.area ? nil : s.road ? .road : .area
    }

    private static func dataSays(_ data: [KMLData]) -> (road: Bool, area: Bool) {
        var road = false, area = false
        for d in data {
            let value = d.value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !value.isEmpty else { continue }
            let v = asciiLowercased(value)
            switch asciiLowercased(d.name.trimmingCharacters(in: .whitespacesAndNewlines)) {
            case "highway", "railway":
                if v != "no" { road = true }
            case "landuse", "boundary", "building", "leisure", "natural":
                if v != "no" { area = true }
            case "area":
                if v == "yes" { area = true }
            default:
                let s = says(value)
                road = road || s.road
                area = area || s.area
            }
        }
        return (road, area)
    }

    private static func asciiLowercased(_ s: String) -> String {
        String(String.UnicodeScalarView(s.unicodeScalars.map { ("A"..."Z").contains($0) ? Unicode.Scalar($0.value + 32)! : $0 }))
    }

    private static let roadScalars = roadWords.map { Array($0.unicodeScalars) }
    private static let areaScalars = areaWords.map { Array($0.unicodeScalars) }

    /// Whether the text has a road word and an area word.
    private static func says(_ text: String) -> (road: Bool, area: Bool) {
        var t: [Unicode.Scalar] = []
        for s in text.unicodeScalars {
            if ("A"..."Z").contains(s) {
                t.append(Unicode.Scalar(s.value + 32)!)
            } else if s == "/" || isWord(s) {
                t.append(s)
            } else if t.last != " " {
                t.append(" ")
            }
        }
        func has(_ words: [[Unicode.Scalar]]) -> Bool {
            for i in t.indices where isWord(t[i]) && (i == 0 || !isWord(t[i - 1])) {
                for w in words where i + w.count <= t.count {
                    var same = true
                    for k in w.indices where t[i + k] != w[k] {
                        same = false
                        break
                    }
                    let end = i + w.count
                    if same && (end == t.count || !isLetter(t[end])) { return true }
                }
            }
            return false
        }
        return (has(roadScalars), has(areaScalars))
    }

    /// A letter, a mark or a number.
    private static func isWord(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .decimalNumber, .letterNumber, .otherNumber: return true
        default: return isLetter(s)
        }
    }

    /// A letter or a mark.
    private static func isLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .enclosingMark:
            return true
        default:
            return false
        }
    }
}
