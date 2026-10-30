package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The Swift GPXTests (Tests/SatMapCoreTests/FileShapesTests.swift), case for case.
class GPXTests {
    @Test
    fun testTracksRoutesAndWaypoints() {
        val doc = GPXParser.parse(fixture("farm.gpx"))
        assertEquals(KMLDocument.Format.gpx, doc.format)
        assertEquals("Farm drive", doc.name)
        // A track without points is left out.
        assertEquals(listOf("Pump", "Gate", "To the dam", "Morning drive", "Loop", "North camp", "Camp 2"), doc.features.map { it.name })
        assertEquals(KMLGeometry.Point(GeoPoint(lat = -25.46, lon = 30.99)), doc.features[0].geometry)
        assertEquals("Borehole pump\nCheck monthly", doc.features[0].description)
        assertEquals(emptyList<KMLData>(), doc.features[0].data)
        assertEquals(listOf(KMLData(name = "type", value = "Gate")), doc.features[1].data)
        assertNull(doc.features[1].description)
        assertEquals(
            KMLGeometry.Line(listOf(GeoPoint(lat = -25.47, lon = 30.97), GeoPoint(lat = -25.472, lon = 30.98), GeoPoint(lat = -25.475, lon = 30.985))),
            doc.features[2].geometry,
        )
        // A track's segments, joined in order.
        assertEquals(
            KMLGeometry.Line(listOf(GeoPoint(lat = -25.48, lon = 30.97), GeoPoint(lat = -25.48, lon = 30.975),
                                    GeoPoint(lat = -25.485, lon = 30.975), GeoPoint(lat = -25.485, lon = 30.98))),
            doc.features[3].geometry,
        )
        assertEquals(listOf(KMLData(name = "type", value = "Driving")), doc.features[3].data)
        assertEquals(listOf(KMLData(name = "type", value = "Boundary")), doc.features[5].data)
        // The track's simple extension values, not the track points'.
        assertEquals(listOf(KMLData(name = "landuse", value = "meadow"), KMLData(name = "DisplayColor", value = "Red")), doc.features[6].data)
        assertTrue(doc.features.all { it.style == null && it.folders.isEmpty() && !it.isRing })
        assertEquals(GeoBounds(minLat = -25.495, minLon = 30.97, maxLat = -25.44, maxLon = 30.995), doc.bounds)
    }

    @Test
    fun testGPX10WithNamespacePrefixes() {
        val doc = GPXParser.parse(fixture("prefixed-1.0.gpx"))
        assertEquals("Old tracks", doc.name)
        assertEquals(listOf("Launch", "Fence", "Single"), doc.features.map { it.name })
        assertEquals(KMLGeometry.Point(GeoPoint(lat = -33.9175, lon = 18.924)), doc.features[0].geometry)
        assertEquals(listOf(KMLData(name = "type", value = "Field")), doc.features[0].data)
        // GPX 1.0 keeps extensions straight inside the track.
        assertEquals(listOf(KMLData(name = "color", value = "ff0000"), KMLData(name = "highway", value = "track")), doc.features[1].data)
        val fence = (doc.features[1].geometry as? KMLGeometry.Line ?: return fail("track")).points
        assertEquals(4, fence.size)
        // A route of one point is a point.
        assertEquals(KMLGeometry.Point(GeoPoint(lat = -33.93, lon = 18.94)), doc.features[2].geometry)
    }

    @Test
    fun testTellsGPXFromKML() {
        assertTrue(GPXParser.isGPX(fixture("farm.gpx")))
        assertTrue(GPXParser.isGPX(fixture("prefixed-1.0.gpx")))
        assertFalse(GPXParser.isGPX(fixture("blocks.kml")))
        assertFalse(GPXParser.isGPX(fixture("blocks.kmz")))
        assertFalse(GPXParser.isGPX("hello".toByteArray()))
        assertFalse(GPXParser.isGPX(ByteArray(0)))
        assertFalse(GPXParser.isGPX("<gpxx/>".toByteArray()))
        val gpx = "<gpx version=\"1.1\"><wpt lat=\"1\" lon=\"2\"/></gpx>"
        val declared = "﻿<?xml version=\"1.0\"?>\n<!-- a > b -->\n<!DOCTYPE gpx [ <!ENTITY a \"b\"> ]>\n" + gpx
        assertTrue(GPXParser.isGPX(declared.toByteArray()))
        assertEquals("gpx", GPXParser.rootElement(declared.toByteArray()))
        // UTF-16 in either byte order, with or without a byte order mark.
        val big = gpx.toByteArray(Charsets.UTF_16BE)
        val little = gpx.toByteArray(Charsets.UTF_16LE)
        for (data in listOf(big, little, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + big, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + little)) {
            assertTrue(GPXParser.isGPX(data))
        }
        assertEquals(KMLDocument.Format.gpx, KMLParser.parseKMLOrGPX(fixture("farm.gpx")).format)
        assertEquals(KMLDocument.Format.kml, KMLParser.parseKMLOrGPX(fixture("blocks.kmz")).format)
        assertEquals("Farm Blocks", KMLParser.parseKMLOrGPX(fixture("blocks.kml")).name)
    }

    @Test
    fun testErrors() {
        val unreadable = assertThrows(GPXError::class.java) { GPXParser.parse("hello".toByteArray()) }
        assertEquals("The file could not be read as GPX.", unreadable.errorDescription)
        val empty = assertThrows(GPXError::class.java) { GPXParser.parse("<gpx version=\"1.1\"><trk><trkseg/></trk></gpx>".toByteArray()) }
        assertEquals("The file contains no waypoints, routes or tracks.", empty.errorDescription)
        // Points off the world are left out; spaces around a number are not.
        val odd = "<gpx><wpt lat=\"91\" lon=\"0\"/><wpt lat=\" -25.5 \" lon=\"31\"/><wpt lat=\"x\" lon=\"31\"/></gpx>"
        assertEquals(listOf(KMLGeometry.Point(GeoPoint(lat = -25.5, lon = 31.0))), GPXParser.parse(odd.toByteArray()).features.map { it.geometry })
    }
}
