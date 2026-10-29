package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.xml.sax.InputSource
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

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

    @Test
    fun testKMZEntryWithDeflateDataCutShortIsCorrupt() {
        // A stored DEFLATE block of 1 byte whose byte is missing: corrupt, as on iOS (not a zero byte).
        val cut = ZipArchive(zip("doc.kml", deflated = bytes(0x01, 0x01, 0x00, 0xFE, 0xFF), size = 1))
        assertThrows(ZipArchive.ZipError.Corrupt::class.java) { cut.extract(cut.entries[0]) }
        val whole = ZipArchive(zip("doc.kml", deflated = bytes(0x01, 0x01, 0x00, 0xFE, 0xFF, 0x41), size = 1))
        assertEquals("A", whole.extract(whole.entries[0]).decodeToString())
    }

    @Test
    fun testKMZEntryWithAHugeDeclaredSizeIsCorrupt() {
        // Swift allocates the declared size lazily and rejects the short stream; no buffer that DEFLATE data this short
        // could fill is allocated here (600 MB or 2 GB would run a phone out of memory).
        for (size in listOf(600_000_000, 0x7FFF_FFF0)) {
            val kmz = zip("doc.kml", deflated = bytes(0x01, 0x01, 0x00, 0xFE, 0xFF, 0x41), size = size)
            val archive = ZipArchive(kmz)
            assertThrows(ZipArchive.ZipError.Corrupt::class.java) { archive.extract(archive.entries[0]) }
            assertThrows(ZipArchive.ZipError.Corrupt::class.java) { KMLParser.parse(kmz) }
        }
    }

    @Test
    fun testKMZEntryNamesDecodedAsSwiftDoes() {
        // String(decoding:as: UTF8.self): one U+FFFD per maximal ill-formed subpart.
        fun name(vararg b: Int) = ZipArchive(zip(ZipEntry(bytes(*b), bytes(0x41)))).entries.single().name
        assertEquals("\uFFFD\uFFFD\uFFFD", name(0xED, 0xA0, 0x80)) // an encoded surrogate
        assertEquals("\uFFFD\uFFFD", name(0xC0, 0xAF)) // overlong
        assertEquals("\uFFFD\uFFFD\uFFFD", name(0xE0, 0x80, 0xAF))
        assertEquals("\uFFFD", name(0xF0, 0x9F, 0x98)) // cut short
        assertEquals("a\uD7FFb", name(0x61, 0xED, 0x9F, 0xBF, 0x62))
        assertEquals("\uD83D\uDE00", name(0xF0, 0x9F, 0x98, 0x80))
        // An overlong "." is no dot: no .kml entry.
        val overlong = "doc".encodeToByteArray() + bytes(0xC0, 0xAE) + "kml".encodeToByteArray()
        assertThrows(KMLError.NoKMLInArchive::class.java) { KMLParser.parse(zip(ZipEntry(overlong, placemarks("D")))) }
    }

    @Test
    fun testKMZEntriesBySwiftCharacters() {
        // "/" with a mark after it is no separator: "x/\u0301y.kml" is one path component, so it is the shallowest.
        val slash = zip(
            ZipEntry("a/b.kml".encodeToByteArray(), placemarks("A")),
            ZipEntry("x/\u0301y.kml".encodeToByteArray(), placemarks("X")),
        )
        assertEquals("X", KMLParser.parse(slash).features.single().name)
        // U+0600 (a Prepend character) joins the dot after it: "a\u0600.kml" doesn't end in ".kml".
        val prepend = zip(
            ZipEntry("a\u0600.kml".encodeToByteArray(), placemarks("A")),
            ZipEntry("b/c.kml".encodeToByteArray(), placemarks("C")),
        )
        assertEquals("C", KMLParser.parse(prepend).features.single().name)
    }

    @Test
    fun testKMLInTheEncodingItsDeclarationNames() {
        // XMLParser decodes in the declared encoding up to the first byte it doesn't have, also where Android's parser
        // reads any byte stream as UTF-8.
        fun kml(encoding: String, vararg names: String) =
            "<?xml version=\"1.0\" encoding=\"$encoding\"?>" + placemarks(*names).decodeToString()
        val cp1252 = charset("windows-1252")
        val undefined = kml("windows-1252", "One", "a#b").toByteArray(cp1252)
        undefined[undefined.lastIndexOf('#'.code.toByte())] = 0x81.toByte() // no windows-1252 character
        for (parse in listOf<(ByteArray) -> KMLDocument>(KMLParser::parse, { likeAndroid { KMLParser.parse(it) } })) {
            val latin1 = parse(kml("ISO-8859-1", "One", "café").toByteArray(Charsets.ISO_8859_1))
            assertEquals(listOf("One", "café"), latin1.features.map { it.name })
            assertEquals("café€“", parse(kml("windows-1252", "café€“").toByteArray(cp1252)).features.single().name)
            assertEquals("“A” – €", parse(kml("windows-1252", "“A” – €").toByteArray(cp1252)).features.single().name)
            assertEquals(listOf("One"), parse(undefined).features.map { it.name })
        }
    }

    @Test
    fun testCarriageReturnsInCDATAAsWritten() {
        // XMLParser hands a CDATA section over as written: "\r\n" stays (one Swift Character, so no separator between
        // coordinates), in UTF-8 and in UTF-16.
        val crlf = "<?xml version=\"1.0\"?>\r\n<kml><Document>\r\n<Placemark><name><![CDATA[a\r\nb]]></name>\r\n" +
            "<Point><coordinates>31,-25</coordinates></Point></Placemark>\r\n</Document></kml>\r\n"
        assertEquals("a\r\nb", KMLParser.parse(crlf.encodeToByteArray()).features.single().name)
        val utf16 = bytes(0xFF, 0xFE) + crlf.toByteArray(Charsets.UTF_16LE)
        assertEquals("a\r\nb", KMLParser.parse(utf16).features.single().name)
        val cr = crlf.replace("a\r\nb", "a\rb")
        assertEquals("a\rb", KMLParser.parse(cr.encodeToByteArray()).features.single().name)
        val coordinates = "<kml><Document><Placemark><LineString><coordinates><![CDATA[\r\n31,-25,0\r\n31.1,-25,0\r\n]]>" +
            "</coordinates></LineString></Placemark></Document></kml>"
        assertThrows(KMLError.NoGeometry::class.java) { KMLParser.parse(coordinates.encodeToByteArray()) }
    }

    @Test
    fun testZeroWidthSpaceIsTrimmed() {
        // Foundation's whitespacesAndNewlines has U+200B.
        val z = "\u200B"
        val kml = "<kml><Document><name>$z</name><name>Real</name><Style id=\"s\"><LineStyle><color>ff0000ff$z</color>" +
            "<width>${z}2</width></LineStyle><PolyStyle><fill>0$z</fill></PolyStyle></Style><Placemark>" +
            "<name>${z}Block A1$z</name><styleUrl>#s$z</styleUrl><Point><coordinates>31,-25</coordinates></Point>" +
            "</Placemark></Document></kml>"
        val doc = KMLParser.parse(kml.encodeToByteArray())
        assertEquals("Real", doc.name)
        assertEquals("Block A1", doc.features.single().name)
        assertEquals(KMLStyle(lineColor = KMLColor(kml = "ff0000ff"), lineWidth = 2.0, fill = false), doc.features[0].style)
    }

    @Test
    fun testStyleIDsAsSwiftStrings() {
        fun style(styles: String, url: String) = KMLParser.parse(
            ("<kml><Document>$styles<Placemark><styleUrl>$url</styleUrl><Point><coordinates>31,-25</coordinates>" +
                "</Point></Placemark></Document></kml>").encodeToByteArray()
        ).features.single().style?.lineColor
        val red = "<LineStyle><color>ff0000ff</color></LineStyle>"
        val green = "<LineStyle><color>ff00ff00</color></LineStyle>"
        // Canonically equivalent ids are the same id: "café" composed or decomposed, "Å" in three spellings.
        assertEquals(KMLColor(kml = "ff0000ff"), style("<Style id=\"caf\u00E9\">$red</Style>", "#cafe\u0301"))
        assertEquals(
            KMLColor(kml = "ff00ff00"),
            style("<Style id=\"caf\u00E9\">$red</Style><Style id=\"cafe\u0301\">$green</Style>", "#caf\u00E9"),
        )
        val map = "<StyleMap id=\"\u00C5\"><Pair><key>normal</key><styleUrl>#\u212B</styleUrl></Pair></StyleMap>"
        assertEquals(KMLColor(kml = "ff0000ff"), style("$map<Style id=\"A\u030A\">$red</Style>", "#\u212B"))
        // A "#" with a mark after it is no "#": the whole url is the id.
        assertEquals(
            KMLColor(kml = "ff00ff00"),
            style("<Style id=\"\u0301s\">$red</Style><Style id=\"x#\u0301s\">$green</Style>", "x#\u0301s"),
        )
    }

    @Test
    fun testDTDEntitiesAsXMLParserReportsThem() {
        // XMLParser drops the first run of text after each entity reference: an entity's own text, or the text after
        // one without any.
        fun kml(entities: String, body: String) =
            "<?xml version=\"1.0\"?><!DOCTYPE kml [$entities]><kml><Document>$body</Document></kml>".encodeToByteArray()
        val foo = "<!ENTITY foo \"bar&amp;baz\">"
        assertThrows(KMLError.NoGeometry::class.java) {
            KMLParser.parse(kml(
                "$foo<!ENTITY c \"31,-25 32,-26\">",
                "<name>&foo;</name><Placemark><name>x&foo;y</name><LineString><coordinates>&c;</coordinates></LineString></Placemark>",
            ))
        }
        val named = KMLParser.parse(kml(foo, "<Placemark><name>x&foo;y</name><Point><coordinates>31,-25</coordinates></Point></Placemark>"))
        assertEquals("x&bazy", named.features.single().name)
        val markup = KMLParser.parse(kml(
            "<!ENTITY pm \"<Placemark><name>E</name><Point><coordinates>31,-25</coordinates></Point></Placemark>\">",
            "&pm;<Placemark><name>Two</name><Point><coordinates>32,-26</coordinates></Point></Placemark>",
        ))
        assertEquals(listOf(null, "Two"), markup.features.map { it.name })
        // An external entity (not read): the character data after it is lost.
        val external = KMLParser.parse(kml(
            "<!ENTITY ext SYSTEM \"file:///etc/hostname\">",
            "<Placemark><name>One</name><Point><coordinates>31,-25</coordinates></Point></Placemark>" +
                "<Placemark><name>&ext;</name><Point><coordinates>33,-27</coordinates></Point></Placemark>",
        ))
        assertEquals(1, external.features.size)
        val empty = KMLParser.parse(kml(
            "<!ENTITY e \"\">",
            "<Placemark><LineString><coordinates>&e;\r\n31,-25\r\n32,-26\r\n33,-27</coordinates></LineString></Placemark>",
        ))
        val line = empty.features.single().geometry as? KMLGeometry.Line ?: return fail("line")
        assertEquals(listOf(GeoPoint(lat = -26.0, lon = 32.0), GeoPoint(lat = -27.0, lon = 33.0)), line.points)
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun le16(v: Int) = bytes(v and 0xFF, (v ushr 8) and 0xFF)

    private fun le32(v: Int) = le16(v and 0xFFFF) + le16(v ushr 16)

    /** A KML document of points with these names. */
    private fun placemarks(vararg names: String) = (
        "<kml><Document>" +
            names.joinToString("") { "<Placemark><name>$it</name><Point><coordinates>31,-25</coordinates></Point></Placemark>" } +
            "</Document></kml>"
        ).encodeToByteArray()

    /** A zip entry: its name's bytes, its data (stored, or DEFLATE data with its size). */
    private class ZipEntry(val name: ByteArray, val data: ByteArray, val size: Int = data.size, val method: Int = 0)

    /** A zip archive of one DEFLATE entry (CRC left at 0: not checked). */
    private fun zip(name: String, deflated: ByteArray, size: Int): ByteArray =
        zip(ZipEntry(name.encodeToByteArray(), deflated, size, method = 8))

    /** A zip archive of these entries (CRCs left at 0: not checked). */
    private fun zip(vararg entries: ZipEntry): ByteArray {
        var local = ByteArray(0)
        var central = ByteArray(0)
        for (e in entries) {
            val n = e.name
            central += bytes(0x50, 0x4B, 0x01, 0x02) + le16(20) + le16(20) + le16(0) + le16(e.method) + le16(0) +
                le16(0) + le32(0) + le32(e.data.size) + le32(e.size) + le16(n.size) + le16(0) + le16(0) + le16(0) +
                le16(0) + le32(0) + le32(local.size) + n
            local += bytes(0x50, 0x4B, 0x03, 0x04) + le16(20) + le16(0) + le16(e.method) + le16(0) + le16(0) + le32(0) +
                le32(e.data.size) + le32(e.size) + le16(n.size) + le16(0) + n + e.data
        }
        val end = bytes(0x50, 0x4B, 0x05, 0x06) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) +
            le32(central.size) + le32(local.size) + le16(0)
        return local + central + end
    }

    /** Runs `block` with Android's way of reading a byte stream (see [AndroidLikeSAXParserFactory]). */
    private fun <T> likeAndroid(block: () -> T): T {
        val key = "javax.xml.parsers.SAXParserFactory"
        val previous = System.getProperty(key)
        System.setProperty(key, AndroidLikeSAXParserFactory::class.java.name)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    /** Android's SAX parser (Expat, through ExpatReader) reads a byte stream as UTF-8 whatever its XML declaration
     *  says: Xerces does the same when the source names UTF-8. */
    class AndroidLikeSAXParserFactory : SAXParserFactory() {
        private val factory = SAXParserFactory.newDefaultInstance()

        override fun newSAXParser(): SAXParser {
            factory.isNamespaceAware = isNamespaceAware
            val parser = factory.newSAXParser()
            return object : SAXParser() {
                override fun parse(source: InputSource, handler: DefaultHandler) {
                    if (source.characterStream == null) source.encoding = "UTF-8"
                    parser.parse(source, handler)
                }

                @Suppress("DEPRECATION")
                override fun getParser(): org.xml.sax.Parser = parser.parser

                override fun getXMLReader(): XMLReader = parser.xmlReader
                override fun isNamespaceAware() = parser.isNamespaceAware
                override fun isValidating() = parser.isValidating
                override fun setProperty(name: String, value: Any) = parser.setProperty(name, value)
                override fun getProperty(name: String): Any? = parser.getProperty(name)
            }
        }

        override fun setFeature(name: String, value: Boolean) = factory.setFeature(name, value)
        override fun getFeature(name: String) = factory.getFeature(name)
    }
}
