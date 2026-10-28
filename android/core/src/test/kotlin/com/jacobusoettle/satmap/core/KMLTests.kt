package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

// The KML tests of the Swift DecoderTests: testKMLAndKMZ, testKMLStyles and the KMLParser line of testRejectsGarbage
// (its PNG line is in DecoderTests).
class KMLTests {
    @Test
    fun testKMLAndKMZ() {
        for (name in listOf("blocks.kml", "blocks.kmz")) {
            val doc = KMLParser.parse(fixture(name))
            assertEquals("Farm Blocks", doc.name)
            assertEquals(name, 4, doc.features.size)
            assertEquals("Block A1", doc.features[0].name)
            val polygon = doc.features[0].geometry as? KMLGeometry.Polygon ?: return fail("polygon")
            val outer = polygon.outer
            val holes = polygon.holes
            assertEquals(5, outer.size)
            assertEquals(30.975, outer[1].lon, 1e-9)
            assertEquals(-25.47, outer[1].lat, 1e-9)
            assertEquals(1, holes.size)
            assertEquals("Block A2 & co", doc.features[1].name)
            if (doc.features[1].geometry !is KMLGeometry.Polygon) return fail("multigeometry polygon")
            val l = (doc.features[2].geometry as? KMLGeometry.Line ?: return fail("line")).points
            assertEquals(2, l.size)
            val p = (doc.features[3].geometry as? KMLGeometry.Point ?: return fail("point")).point
            assertEquals(-25.46, p.lat, 1e-9)
            assertEquals(KMLColor(kml = "ff00ffff"), doc.features[0].style?.lineColor)
            assertNull(doc.features[1].style)
            val shape = doc.coverageShape(bufferMeters = 100.0)
            assertEquals(2, shape.polygons.size)
            assertEquals(1, shape.lines.size)
            assertEquals(1, shape.points.size)
        }
    }

    @Test
    fun testKMLStyles() {
        // Shared styles (also after the placemarks using them), a style map's normal style (linked or its own), a
        // placemark's own style over its shared one, and colours written aabbggrr.
        val kml = """
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
          <Placemark><name>A</name><styleUrl>#map</styleUrl>
            <Polygon><outerBoundaryIs><LinearRing><coordinates>31,-25 31.1,-25 31.1,-25.1 31,-25</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
          <Placemark><name>B</name><styleUrl>#green</styleUrl>
            <Style><LineStyle><width>4</width></LineStyle><PolyStyle><fill>0</fill></PolyStyle></Style>
            <LineString><coordinates>31,-25 31.1,-25.1</coordinates></LineString></Placemark>
          <Placemark><name>C</name><styleUrl>#inlineMap</styleUrl><Point><coordinates>31,-25</coordinates></Point></Placemark>
          <Placemark><name>D</name><styleUrl>#missing</styleUrl><Point><coordinates>31.2,-25</coordinates></Point></Placemark>
          <StyleMap id="map"><Pair><key>normal</key><styleUrl>#green</styleUrl></Pair><Pair><key>highlight</key><styleUrl>#red</styleUrl></Pair></StyleMap>
          <StyleMap id="inlineMap"><Pair><key>highlight</key><styleUrl>#red</styleUrl></Pair>
            <Pair><key>normal</key><Style><IconStyle><color>#ff0000ff</color></IconStyle></Style></Pair></StyleMap>
          <Style id="green"><LineStyle><color>ff6ed035</color><width>2</width></LineStyle><PolyStyle><color>596ed035</color></PolyStyle></Style>
          <Style id="red"><LineStyle><color>ff0000ff</color></LineStyle></Style>
        </Document></kml>
        """.trimIndent()
        val doc = KMLParser.parse(kml.encodeToByteArray())
        assertEquals(4, doc.features.size)
        val green = doc.features[0].style!!
        assertEquals(2.0, green.lineWidth)
        val line = green.lineColor!!
        assertEquals(0x35 / 255.0, line.red, 1e-9)
        assertEquals(0xd0 / 255.0, line.green, 1e-9)
        assertEquals(0x6e / 255.0, line.blue, 1e-9)
        assertEquals(1.0, line.alpha, 1e-9)
        assertEquals(0x59 / 255.0, green.fillColor!!.alpha, 1e-9)
        val own = doc.features[1].style!!
        assertEquals(4.0, own.lineWidth)
        assertEquals(green.lineColor, own.lineColor)
        assertEquals(false, own.fill)
        assertEquals(1.0, doc.features[2].style?.iconColor!!.red, 1e-9)
        assertNull(doc.features[3].style)
        // Google Earth Web and the Earth app: shared styles in gx:CascadingStyle, which holds the id.
        val earth = """
        <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2"><Document>
          <gx:CascadingStyle kml:id="__managed_style_N"><Style><LineStyle><color>ff2dc0fb</color><width>3.2</width></LineStyle>
            <PolyStyle><color>402dc0fb</color></PolyStyle></Style></gx:CascadingStyle>
          <gx:CascadingStyle kml:id="__managed_style_H"><Style><LineStyle><color>ff2dc0fb</color><width>4.8</width></LineStyle></Style></gx:CascadingStyle>
          <StyleMap id="__managed_style_M"><Pair><key>normal</key><styleUrl>#__managed_style_N</styleUrl></Pair>
            <Pair><key>highlight</key><styleUrl>#__managed_style_H</styleUrl></Pair></StyleMap>
          <Placemark><name>E</name><styleUrl>#__managed_style_M</styleUrl>
            <Polygon><outerBoundaryIs><LinearRing><coordinates>31,-25 31.1,-25 31.1,-25.1 31,-25</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        </Document></kml>
        """.trimIndent()
        val web = KMLParser.parse(earth.encodeToByteArray()).features.firstOrNull()?.style!!
        assertEquals(3.2, web.lineWidth)
        assertEquals(KMLColor(kml = "ff2dc0fb"), web.lineColor)
        assertEquals(KMLColor(kml = "402dc0fb"), web.fillColor)
        assertNull(KMLColor(kml = "red"))
        assertEquals(1.0, KMLColor(kml = "0000ff")?.red)
    }

    @Test
    fun testRejectsGarbage() {
        assertThrows(Exception::class.java) { KMLParser.parse("hello".encodeToByteArray()) }
    }
}
