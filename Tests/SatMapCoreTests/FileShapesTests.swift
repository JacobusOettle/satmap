import Foundation
import XCTest
@testable import SatMapCore

// GPX files, and what a KML or GPX file saves offline. The Android tests (GPXTests.kt, FileShapesTests.kt) have the
// same cases and read the same fixtures.

private func fixture(_ name: String) throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/" + name, withExtension: nil))
    return try Data(contentsOf: url)
}

final class GPXTests: XCTestCase {
    func testTracksRoutesAndWaypoints() throws {
        let doc = try GPXParser.parse(try fixture("farm.gpx"))
        XCTAssertEqual(doc.format, .gpx)
        XCTAssertEqual(doc.name, "Farm drive")
        // A track without points is left out.
        XCTAssertEqual(doc.features.map(\.name), ["Pump", "Gate", "To the dam", "Morning drive", "Loop", "North camp", "Camp 2"])
        XCTAssertEqual(doc.features[0].geometry, .point(GeoPoint(lat: -25.46, lon: 30.99)))
        XCTAssertEqual(doc.features[0].description, "Borehole pump\nCheck monthly")
        XCTAssertEqual(doc.features[0].data, [])
        XCTAssertEqual(doc.features[1].data, [KMLData(name: "type", value: "Gate")])
        XCTAssertNil(doc.features[1].description)
        XCTAssertEqual(doc.features[2].geometry, .line([GeoPoint(lat: -25.47, lon: 30.97), GeoPoint(lat: -25.472, lon: 30.98),
                                                        GeoPoint(lat: -25.475, lon: 30.985)]))
        // A track's segments, joined in order.
        XCTAssertEqual(doc.features[3].geometry, .line([GeoPoint(lat: -25.48, lon: 30.97), GeoPoint(lat: -25.48, lon: 30.975),
                                                        GeoPoint(lat: -25.485, lon: 30.975), GeoPoint(lat: -25.485, lon: 30.98)]))
        XCTAssertEqual(doc.features[3].data, [KMLData(name: "type", value: "Driving")])
        XCTAssertEqual(doc.features[5].data, [KMLData(name: "type", value: "Boundary")])
        // The track's simple extension values, not the track points'.
        XCTAssertEqual(doc.features[6].data, [KMLData(name: "landuse", value: "meadow"), KMLData(name: "DisplayColor", value: "Red")])
        XCTAssertTrue(doc.features.allSatisfy { $0.style == nil && $0.folders.isEmpty && !$0.isRing })
        XCTAssertEqual(doc.bounds, GeoBounds(minLat: -25.495, minLon: 30.97, maxLat: -25.44, maxLon: 30.995))
    }

    func testGPX10WithNamespacePrefixes() throws {
        let doc = try GPXParser.parse(try fixture("prefixed-1.0.gpx"))
        XCTAssertEqual(doc.name, "Old tracks")
        XCTAssertEqual(doc.features.map(\.name), ["Launch", "Fence", "Single"])
        XCTAssertEqual(doc.features[0].geometry, .point(GeoPoint(lat: -33.9175, lon: 18.924)))
        XCTAssertEqual(doc.features[0].data, [KMLData(name: "type", value: "Field")])
        // GPX 1.0 keeps extensions straight inside the track.
        XCTAssertEqual(doc.features[1].data, [KMLData(name: "color", value: "ff0000"), KMLData(name: "highway", value: "track")])
        guard case .line(let fence) = doc.features[1].geometry else { return XCTFail("track") }
        XCTAssertEqual(fence.count, 4)
        // A route of one point is a point.
        XCTAssertEqual(doc.features[2].geometry, .point(GeoPoint(lat: -33.93, lon: 18.94)))
    }

    func testTellsGPXFromKML() throws {
        XCTAssertTrue(GPXParser.isGPX(try fixture("farm.gpx")))
        XCTAssertTrue(GPXParser.isGPX(try fixture("prefixed-1.0.gpx")))
        XCTAssertFalse(GPXParser.isGPX(try fixture("blocks.kml")))
        XCTAssertFalse(GPXParser.isGPX(try fixture("blocks.kmz")))
        XCTAssertFalse(GPXParser.isGPX(Data("hello".utf8)))
        XCTAssertFalse(GPXParser.isGPX(Data()))
        XCTAssertFalse(GPXParser.isGPX(Data("<gpxx/>".utf8)))
        let gpx = "<gpx version=\"1.1\"><wpt lat=\"1\" lon=\"2\"/></gpx>"
        let declared = "\u{FEFF}<?xml version=\"1.0\"?>\n<!-- a > b -->\n<!DOCTYPE gpx [ <!ENTITY a \"b\"> ]>\n" + gpx
        XCTAssertTrue(GPXParser.isGPX(Data(declared.utf8)))
        XCTAssertEqual(GPXParser.rootElement(Data(declared.utf8)), "gpx")
        // UTF-16 in either byte order, with or without a byte order mark.
        let big = try XCTUnwrap(gpx.data(using: .utf16BigEndian)), little = try XCTUnwrap(gpx.data(using: .utf16LittleEndian))
        for data in [big, little, Data([0xFE, 0xFF]) + big, Data([0xFF, 0xFE]) + little] {
            XCTAssertTrue(GPXParser.isGPX(data))
        }
        XCTAssertEqual(try KMLParser.parseKMLOrGPX(try fixture("farm.gpx")).format, .gpx)
        XCTAssertEqual(try KMLParser.parseKMLOrGPX(try fixture("blocks.kmz")).format, .kml)
        XCTAssertEqual(try KMLParser.parseKMLOrGPX(try fixture("blocks.kml")).name, "Farm Blocks")
    }

    func testErrors() {
        XCTAssertThrowsError(try GPXParser.parse(Data("hello".utf8))) {
            XCTAssertEqual($0.localizedDescription, "The file could not be read as GPX.")
        }
        XCTAssertThrowsError(try GPXParser.parse(Data("<gpx version=\"1.1\"><trk><trkseg/></trk></gpx>".utf8))) {
            XCTAssertEqual($0.localizedDescription, "The file contains no waypoints, routes or tracks.")
        }
        // Points off the world are left out; spaces around a number are not.
        let odd = "<gpx><wpt lat=\"91\" lon=\"0\"/><wpt lat=\" -25.5 \" lon=\"31\"/><wpt lat=\"x\" lon=\"31\"/></gpx>"
        XCTAssertEqual(try GPXParser.parse(Data(odd.utf8)).features.map(\.geometry), [.point(GeoPoint(lat: -25.5, lon: 31))])
    }
}

final class FileShapesTests: XCTestCase {
    private let ring = [GeoPoint(lat: -25.47, lon: 30.97), GeoPoint(lat: -25.47, lon: 30.975), GeoPoint(lat: -25.475, lon: 30.975)]

    private func line(_ points: [GeoPoint], name: String? = nil, data: [KMLData] = [], isRing: Bool = false) -> KMLFeature {
        KMLFeature(name: name, geometry: .line(points), data: data, isRing: isRing)
    }

    private func kind(_ f: KMLFeature, _ format: KMLDocument.Format = .kml) -> FileShapes.Kind {
        FileShapes.kind(of: f, in: format)
    }

    func testEachKindOfFeature() {
        let closed = ring + [GeoPoint(lat: -25.47002, lon: 30.97002)] // ends about 3 m from where it starts
        let open = ring + [GeoPoint(lat: -25.4701, lon: 30.9701)] // about 15 m
        XCTAssertEqual(kind(KMLFeature(name: nil, geometry: .point(ring[0]))), .dot)
        XCTAssertEqual(kind(KMLFeature(name: nil, geometry: .point(ring[0])), .gpx), .dot)
        XCTAssertEqual(kind(KMLFeature(name: nil, geometry: .polygon(outer: ring, holes: [ring]))), .area)
        XCTAssertEqual(kind(line(closed)), .area)
        XCTAssertEqual(kind(line(open)), .line)
        XCTAssertEqual(kind(line(ring, isRing: true)), .area)
        XCTAssertEqual(kind(line(closed, name: "Ring road")), .line)
        XCTAssertEqual(kind(line(open, name: "Block 7")), .area)
        // GPX: a closed track stays a road, unless the file marks it as an area.
        XCTAssertEqual(kind(line(closed), .gpx), .line)
        XCTAssertEqual(kind(line(open, data: [KMLData(name: "type", value: "Area")]), .gpx), .area)
        // An area needs 3 distinct points.
        let back = [ring[0], ring[1], ring[0]]
        XCTAssertEqual(kind(line(back, name: "Block 7")), .line)
        XCTAssertEqual(kind(line(back, isRing: true)), .line)
        XCTAssertEqual(kind(line(back + [ring[1]], data: [KMLData(name: "area", value: "yes")]), .gpx), .line)
    }

    func testMarksAndWhichComesFirst() {
        func mark(name: String? = nil, description: String? = nil, data: [(String, String)] = [],
                  folders: [String] = []) -> FileShapes.Mark? {
            FileShapes.mark(of: KMLFeature(name: name, geometry: .line(ring), description: description, folders: folders,
                                           data: data.map { KMLData(name: $0.0, value: $0.1) }))
        }
        // Its data: keys that say what it is, and other values read as text.
        XCTAssertEqual(mark(data: [("highway", "track")]), .road)
        XCTAssertEqual(mark(data: [(" Railway ", "rail")]), .road)
        for key in ["landuse", "boundary", "building", "leisure", "natural"] {
            XCTAssertEqual(mark(data: [(key, "farmland")]), .area, key)
        }
        XCTAssertEqual(mark(data: [("area", "YES")]), .area)
        XCTAssertNil(mark(data: [("area", "no")]))
        XCTAssertNil(mark(data: [("area", "Block 4")]))
        XCTAssertNil(mark(data: [("building", "no")]))
        XCTAssertNil(mark(data: [("highway", " ")]))
        XCTAssertEqual(mark(data: [("type", "Gravel road")]), .road)
        XCTAssertEqual(mark(data: [("notes", "Paddocks")]), .area)
        XCTAssertNil(mark(data: [("roads", "1")]))
        // Then its name and description, then its folders, innermost first, then the document.
        XCTAssertEqual(mark(name: "Ring road", data: [("landuse", "farmland")], folders: ["Roads"]), .area)
        XCTAssertEqual(mark(name: "Paddock 4", folders: ["Roads"]), .area)
        XCTAssertEqual(mark(description: "Paddock 4", folders: ["Roads"]), .area)
        XCTAssertEqual(mark(name: "A3", folders: ["Far camp", "Roads", "Blocks"]), .road)
        XCTAssertEqual(mark(folders: ["Blocks", "Roads"]), .area)
        // A level that says both is passed over.
        XCTAssertEqual(mark(name: "Block 4", data: [("highway", "track"), ("landuse", "farmland")]), .area)
        XCTAssertEqual(mark(name: "Block 4", description: "Along the road", folders: ["Roads"]), .road)
        XCTAssertEqual(mark(name: "Roads and blocks", folders: ["Blocks"]), .area)
        XCTAssertNil(mark(name: "Roads and blocks", folders: ["Farm roads and blocks"]))
        XCTAssertNil(mark(name: "Pump"))
    }

    func testWordsInText() {
        for text in ["Main road", "MAIN RD", "rd.", "4x4 track", "Track2", "fire break", "Fire-break", "fire_break", "F/B 3",
                     "roads/tracks", "Driveway", "Drivable", "Lane", "Streets", "Highway", "Route", "Paths", "Trail é", "firebreaks"] {
            XCTAssertEqual(FileShapes.textMark(text), .road, text)
        }
        for text in ["Block12", "Block A1", "paddocks", "zone_A", "Area 51", "boundary", "Boundaries", "Parcel-9", "Plots",
                     "Polygon", "Fields", "Zones"] {
            XCTAssertEqual(FileShapes.textMark(text), .area, text)
        }
        for text in ["", "3rd camp", "of/by", "Fieldwork", "Roadside", "Lanes", "Boulevard", "road\u{301}", "Pump", "fire/break",
                     "Track and field", "Blocks/roads"] {
            XCTAssertNil(FileShapes.textMark(text), text)
        }
    }

    func testWhatAKMLFileSaves() throws {
        let doc = try KMLParser.parse(try fixture("farm-folders.kml"))
        var kinds: [String: FileShapes.Kind] = [:]
        for f in doc.features { kinds[f.name ?? ""] = FileShapes.kind(of: f, in: doc.format) }
        XCTAssertEqual(kinds, ["A1": .area, "A2": .area, "A3 outline": .area, "North firebreak": .line, "B1": .area,
                               "Ring road": .line, "Main": .line, "Dam wall": .area, "Paddock 4": .area, "Pump": .dot,
                               "Loop": .area, "To the dam": .line])
        let shapes = FileShapes(doc)
        XCTAssertEqual(shapes.areas.count, 7)
        XCTAssertEqual(shapes.lines.count, 4)
        XCTAssertEqual(shapes.dots, [GeoPoint(lat: -25.46, lon: 30.99)])
        XCTAssertTrue(shapes.hasAreas)
        XCTAssertEqual(shapes.downloadShape, CoverageShape(polygons: shapes.areas, lines: shapes.lines, points: shapes.dots,
                                                           bufferMeters: 15, polygonBufferMeters: 0))
        XCTAssertEqual(shapes.roadSearchShape, CoverageShape(polygons: shapes.areas))
    }

    func testWhatAGPXFileSaves() throws {
        let doc = try GPXParser.parse(try fixture("farm.gpx"))
        XCTAssertEqual(doc.features.map { FileShapes.kind(of: $0, in: doc.format) }, [.dot, .dot, .line, .line, .line, .area, .area])
        XCTAssertEqual(FileShapes.mark(of: doc.features[3]), .road) // "Morning drive"
        XCTAssertNil(FileShapes.mark(of: doc.features[4])) // "Loop": closed, but a GPX track
        XCTAssertEqual(FileShapes.mark(of: doc.features[5]), .area) // typed "Boundary"
        XCTAssertEqual(FileShapes.mark(of: doc.features[6]), .area) // landuse in its extensions
        let shapes = FileShapes(doc)
        XCTAssertEqual(shapes.areas.count, 2)
        XCTAssertEqual(shapes.lines.count, 3)
        XCTAssertEqual(shapes.dots.count, 2)
        let old = try GPXParser.parse(try fixture("prefixed-1.0.gpx"))
        XCTAssertEqual(FileShapes.mark(of: old.features[1]), .road)
        XCTAssertFalse(FileShapes(old).hasAreas)
        XCTAssertEqual(FileShapes(old).dots.count, 2)
    }

    func testOnlyAlongRoadsKeepsTheFilesLinesAndDots() throws {
        // Two blocks about 4 km apart, and a line and a dot away from them.
        let a = [GeoPoint(lat: -33.910, lon: 18.910), GeoPoint(lat: -33.910, lon: 18.922),
                 GeoPoint(lat: -33.920, lon: 18.922), GeoPoint(lat: -33.920, lon: 18.910)]
        let b = a.map { GeoPoint(lat: $0.lat, lon: $0.lon + 0.06) }
        let road = [GeoPoint(lat: -33.930, lon: 18.930), GeoPoint(lat: -33.930, lon: 18.940)]
        let dot = GeoPoint(lat: -33.935, lon: 18.950)
        let doc = KMLDocument(name: "Farm", features: [
            KMLFeature(name: "A", geometry: .polygon(outer: a, holes: [])), KMLFeature(name: "B", geometry: .polygon(outer: b, holes: [])),
            KMLFeature(name: "Track", geometry: .line(road)), KMLFeature(name: "Pump", geometry: .point(dot)),
        ])
        let shapes = FileShapes(doc)
        XCTAssertEqual(shapes.areas, [a, b])
        // Roads are looked up around the blocks only (one box each, the corridor's width around it) …
        let query = try XCTUnwrap(Roads.overpassQuery(for: shapes.roadSearchShape))
        XCTAssertEqual(query.components(separatedBy: "way[").count - 1, 2)
        XCTAssertTrue(query.contains("(-33.920135,"))
        // … and a road through both keeps only its parts inside them.
        let through = [GeoPoint(lat: -33.915, lon: 18.900), GeoPoint(lat: -33.915, lon: 18.990)]
        let clipped = Roads.clip([through], to: shapes.roadSearchShape)
        XCTAssertEqual(clipped.count, 2)
        let width = Geo.distance(GeoPoint(lat: -33.915, lon: 18.910), GeoPoint(lat: -33.915, lon: 18.922))
        XCTAssertEqual(Roads.length(clipped), 2 * width, accuracy: 90)
        let roads = shapes.roadsToSave(clipped)
        XCTAssertEqual(roads, clipped + [road, [dot]])
        // Imagery 15 m each side of those roads, and 15 m around the file's line and dot as without "Only along roads".
        for z in [17, 19] {
            let imagery = TileCoverage(shape: Roads.corridor(roads), zoom: z)
            TileCoverage(shape: CoverageShape(lines: [road], points: [dot], bufferMeters: 15), zoom: z).forEachTile {
                XCTAssertTrue(imagery.contains($0), "zoom \(z): \($0)")
            }
            XCTAssertLessThan(imagery.tileCount, TileCoverage(shape: shapes.downloadShape, zoom: z).tileCount)
            XCTAssertFalse(imagery.contains(TileID.containing(GeoPoint(lat: -33.915, lon: 18.946), z: z)))
        }
        // No areas: no "Only along roads".
        XCTAssertFalse(FileShapes(KMLDocument(name: nil, features: Array(doc.features.dropFirst(2)))).hasAreas)
    }
}
