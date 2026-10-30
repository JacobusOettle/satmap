package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Swift FileShapesTests (Tests/SatMapCoreTests/FileShapesTests.swift), case for case.
class FileShapesTests {
    private val ring = listOf(GeoPoint(lat = -25.47, lon = 30.97), GeoPoint(lat = -25.47, lon = 30.975), GeoPoint(lat = -25.475, lon = 30.975))

    private fun line(points: List<GeoPoint>, name: String? = null, data: List<KMLData> = emptyList(), isRing: Boolean = false) =
        KMLFeature(name = name, geometry = KMLGeometry.Line(points), data = data, isRing = isRing)

    private fun kind(f: KMLFeature, format: KMLDocument.Format = KMLDocument.Format.kml) = FileShapes.kind(f, format)

    @Test
    fun testEachKindOfFeature() {
        val closed = ring + GeoPoint(lat = -25.47002, lon = 30.97002) // ends about 3 m from where it starts
        val open = ring + GeoPoint(lat = -25.4701, lon = 30.9701) // about 15 m
        assertEquals(FileShapes.Kind.dot, kind(KMLFeature(null, KMLGeometry.Point(ring[0]))))
        assertEquals(FileShapes.Kind.dot, kind(KMLFeature(null, KMLGeometry.Point(ring[0])), KMLDocument.Format.gpx))
        assertEquals(FileShapes.Kind.area, kind(KMLFeature(null, KMLGeometry.Polygon(outer = ring, holes = listOf(ring)))))
        assertEquals(FileShapes.Kind.area, kind(line(closed)))
        assertEquals(FileShapes.Kind.line, kind(line(open)))
        assertEquals(FileShapes.Kind.area, kind(line(ring, isRing = true)))
        assertEquals(FileShapes.Kind.line, kind(line(closed, name = "Ring road")))
        assertEquals(FileShapes.Kind.area, kind(line(open, name = "Block 7")))
        // GPX: a closed track stays a road, unless the file marks it as an area.
        assertEquals(FileShapes.Kind.line, kind(line(closed), KMLDocument.Format.gpx))
        assertEquals(FileShapes.Kind.area, kind(line(open, data = listOf(KMLData(name = "type", value = "Area"))), KMLDocument.Format.gpx))
        // An area needs 3 distinct points.
        val back = listOf(ring[0], ring[1], ring[0])
        assertEquals(FileShapes.Kind.line, kind(line(back, name = "Block 7")))
        assertEquals(FileShapes.Kind.line, kind(line(back, isRing = true)))
        assertEquals(FileShapes.Kind.line, kind(line(back + ring[1], data = listOf(KMLData(name = "area", value = "yes"))), KMLDocument.Format.gpx))
    }

    @Test
    fun testMarksAndWhichComesFirst() {
        fun mark(name: String? = null, description: String? = null, data: List<Pair<String, String>> = emptyList(),
                 folders: List<String> = emptyList()): FileShapes.Mark? =
            FileShapes.mark(
                KMLFeature(name = name, geometry = KMLGeometry.Line(ring), description = description, folders = folders,
                           data = data.map { KMLData(name = it.first, value = it.second) })
            )
        // Its data: keys that say what it is, and other values read as text.
        assertEquals(FileShapes.Mark.road, mark(data = listOf("highway" to "track")))
        assertEquals(FileShapes.Mark.road, mark(data = listOf(" Railway " to "rail")))
        for (key in listOf("landuse", "boundary", "building", "leisure", "natural")) {
            assertEquals(key, FileShapes.Mark.area, mark(data = listOf(key to "farmland")))
        }
        assertEquals(FileShapes.Mark.area, mark(data = listOf("area" to "YES")))
        assertNull(mark(data = listOf("area" to "no")))
        assertNull(mark(data = listOf("area" to "Block 4")))
        assertNull(mark(data = listOf("building" to "no")))
        assertNull(mark(data = listOf("highway" to " ")))
        assertEquals(FileShapes.Mark.road, mark(data = listOf("type" to "Gravel road")))
        assertEquals(FileShapes.Mark.area, mark(data = listOf("notes" to "Paddocks")))
        assertNull(mark(data = listOf("roads" to "1")))
        // Then its name and description, then its folders, innermost first, then the document.
        assertEquals(FileShapes.Mark.area, mark(name = "Ring road", data = listOf("landuse" to "farmland"), folders = listOf("Roads")))
        assertEquals(FileShapes.Mark.area, mark(name = "Paddock 4", folders = listOf("Roads")))
        assertEquals(FileShapes.Mark.area, mark(description = "Paddock 4", folders = listOf("Roads")))
        assertEquals(FileShapes.Mark.road, mark(name = "A3", folders = listOf("Far camp", "Roads", "Blocks")))
        assertEquals(FileShapes.Mark.area, mark(folders = listOf("Blocks", "Roads")))
        // A level that says both is passed over.
        assertEquals(FileShapes.Mark.area, mark(name = "Block 4", data = listOf("highway" to "track", "landuse" to "farmland")))
        assertEquals(FileShapes.Mark.road, mark(name = "Block 4", description = "Along the road", folders = listOf("Roads")))
        assertEquals(FileShapes.Mark.area, mark(name = "Roads and blocks", folders = listOf("Blocks")))
        assertNull(mark(name = "Roads and blocks", folders = listOf("Farm roads and blocks")))
        assertNull(mark(name = "Pump"))
    }

    @Test
    fun testWordsInText() {
        for (text in listOf("Main road", "MAIN RD", "rd.", "4x4 track", "Track2", "fire break", "Fire-break", "fire_break", "F/B 3",
                            "roads/tracks", "Driveway", "Drivable", "Lane", "Streets", "Highway", "Route", "Paths", "Trail é", "firebreaks")) {
            assertEquals(text, FileShapes.Mark.road, FileShapes.textMark(text))
        }
        for (text in listOf("Block12", "Block A1", "paddocks", "zone_A", "Area 51", "boundary", "Boundaries", "Parcel-9", "Plots",
                            "Polygon", "Fields", "Zones")) {
            assertEquals(text, FileShapes.Mark.area, FileShapes.textMark(text))
        }
        for (text in listOf("", "3rd camp", "of/by", "Fieldwork", "Roadside", "Lanes", "Boulevard", "road́", "Pump", "fire/break",
                            "Track and field", "Blocks/roads")) {
            assertNull(text, FileShapes.textMark(text))
        }
    }

    @Test
    fun testWhatAKMLFileSaves() {
        val doc = KMLParser.parse(fixture("farm-folders.kml"))
        val kinds = doc.features.associate { (it.name ?: "") to FileShapes.kind(it, doc.format) }
        assertEquals(
            mapOf(
                "A1" to FileShapes.Kind.area, "A2" to FileShapes.Kind.area, "A3 outline" to FileShapes.Kind.area,
                "North firebreak" to FileShapes.Kind.line, "B1" to FileShapes.Kind.area, "Ring road" to FileShapes.Kind.line,
                "Main" to FileShapes.Kind.line, "Dam wall" to FileShapes.Kind.area, "Paddock 4" to FileShapes.Kind.area,
                "Pump" to FileShapes.Kind.dot, "Loop" to FileShapes.Kind.area, "To the dam" to FileShapes.Kind.line,
            ),
            kinds,
        )
        val shapes = FileShapes(doc)
        assertEquals(7, shapes.areas.size)
        assertEquals(4, shapes.lines.size)
        assertEquals(listOf(GeoPoint(lat = -25.46, lon = 30.99)), shapes.dots)
        assertTrue(shapes.hasAreas)
        assertEquals(
            CoverageShape(polygons = shapes.areas, lines = shapes.lines, points = shapes.dots, bufferMeters = 15.0, polygonBufferMeters = 0.0),
            shapes.downloadShape,
        )
        assertEquals(CoverageShape(polygons = shapes.areas), shapes.roadSearchShape)
    }

    @Test
    fun testWhatAGPXFileSaves() {
        val doc = GPXParser.parse(fixture("farm.gpx"))
        assertEquals(
            listOf(FileShapes.Kind.dot, FileShapes.Kind.dot, FileShapes.Kind.line, FileShapes.Kind.line, FileShapes.Kind.line,
                   FileShapes.Kind.area, FileShapes.Kind.area),
            doc.features.map { FileShapes.kind(it, doc.format) },
        )
        assertEquals(FileShapes.Mark.road, FileShapes.mark(doc.features[3])) // "Morning drive"
        assertNull(FileShapes.mark(doc.features[4])) // "Loop": closed, but a GPX track
        assertEquals(FileShapes.Mark.area, FileShapes.mark(doc.features[5])) // typed "Boundary"
        assertEquals(FileShapes.Mark.area, FileShapes.mark(doc.features[6])) // landuse in its extensions
        val shapes = FileShapes(doc)
        assertEquals(2, shapes.areas.size)
        assertEquals(3, shapes.lines.size)
        assertEquals(2, shapes.dots.size)
        val old = GPXParser.parse(fixture("prefixed-1.0.gpx"))
        assertEquals(FileShapes.Mark.road, FileShapes.mark(old.features[1]))
        assertFalse(FileShapes(old).hasAreas)
        assertEquals(2, FileShapes(old).dots.size)
    }

    @Test
    fun testOnlyAlongRoadsKeepsTheFilesLinesAndDots() {
        // Two blocks about 4 km apart, and a line and a dot away from them.
        val a = listOf(GeoPoint(lat = -33.910, lon = 18.910), GeoPoint(lat = -33.910, lon = 18.922),
                       GeoPoint(lat = -33.920, lon = 18.922), GeoPoint(lat = -33.920, lon = 18.910))
        val b = a.map { GeoPoint(lat = it.lat, lon = it.lon + 0.06) }
        val road = listOf(GeoPoint(lat = -33.930, lon = 18.930), GeoPoint(lat = -33.930, lon = 18.940))
        val dot = GeoPoint(lat = -33.935, lon = 18.950)
        val doc = KMLDocument(
            name = "Farm",
            features = listOf(
                KMLFeature("A", KMLGeometry.Polygon(outer = a, holes = emptyList())), KMLFeature("B", KMLGeometry.Polygon(outer = b, holes = emptyList())),
                KMLFeature("Track", KMLGeometry.Line(road)), KMLFeature("Pump", KMLGeometry.Point(dot)),
            ),
        )
        val shapes = FileShapes(doc)
        assertEquals(listOf(a, b), shapes.areas)
        // Roads are looked up around the blocks only (one box each, the corridor's width around it) …
        val query = Roads.overpassQuery(shapes.roadSearchShape)!!
        assertEquals(2, query.split("way[").size - 1)
        assertTrue(query.contains("(-33.920135,"))
        // … and a road through both keeps only its parts inside them.
        val through = listOf(GeoPoint(lat = -33.915, lon = 18.900), GeoPoint(lat = -33.915, lon = 18.990))
        val clipped = Roads.clip(listOf(through), shapes.roadSearchShape)
        assertEquals(2, clipped.size)
        val width = Geo.distance(GeoPoint(lat = -33.915, lon = 18.910), GeoPoint(lat = -33.915, lon = 18.922))
        assertEquals(2 * width, Roads.length(clipped), 90.0)
        val roads = shapes.roadsToSave(clipped)
        assertEquals(clipped + listOf(road, listOf(dot)), roads)
        // Imagery 15 m each side of those roads, and 15 m around the file's line and dot as without "Only along roads".
        for (z in listOf(17, 19)) {
            val imagery = TileCoverage(shape = Roads.corridor(roads), zoom = z)
            TileCoverage(shape = CoverageShape(lines = listOf(road), points = listOf(dot), bufferMeters = 15.0), zoom = z).forEachTile {
                assertTrue("zoom $z: $it", imagery.contains(it))
            }
            assertLessThan(imagery.tileCount, TileCoverage(shape = shapes.downloadShape, zoom = z).tileCount)
            assertFalse(imagery.contains(TileID.containing(GeoPoint(lat = -33.915, lon = 18.946), z = z)))
        }
        // No areas: no "Only along roads".
        assertFalse(FileShapes(KMLDocument(name = null, features = doc.features.drop(2))).hasAreas)
    }
}
