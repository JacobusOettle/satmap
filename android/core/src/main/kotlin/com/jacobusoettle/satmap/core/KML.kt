package com.jacobusoettle.satmap.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

sealed interface KMLGeometry {
    data class Point(val point: GeoPoint) : KMLGeometry
    data class Line(val points: List<GeoPoint>) : KMLGeometry
    data class Polygon(val outer: List<GeoPoint>, val holes: List<List<GeoPoint>>) : KMLGeometry
}

data class KMLFeature(
    val name: String?,
    val geometry: KMLGeometry,
    /** How the file draws it (null: no style of its own). */
    val style: KMLStyle? = null,
    /** Its description (GPX: desc, then cmt on a line of its own). */
    val description: String? = null,
    /** The names of the Folders and Documents it is in, innermost first (KML). */
    val folders: List<String> = emptyList(),
    /** Its data, in file order: KML ExtendedData (Data and SimpleData), and a GPX feature's type (named "type") and
     *  the simple values in its extensions. */
    val data: List<KMLData> = emptyList(),
    /** A KML LinearRing of its own: drawn as a line, but closed (`FileShapes`). */
    val isRing: Boolean = false,
)

/** A named value a file gives a feature (`KMLFeature.data`). */
data class KMLData(val name: String, val value: String)

/** A KML colour: written aabbggrr in hex (alpha first, then blue, green, red). */
data class KMLColor(val red: Double, val green: Double, val blue: Double, val alpha: Double) {
    companion object {
        /** From "aabbggrr" (also with a leading "#", or "bbggrr" opaque); null if it isn't that. */
        operator fun invoke(kml: String): KMLColor? {
            var hex = swiftTrimmed(kml)
            if (hex.startsWith("#")) hex = hex.substring(1)
            if (hex.length == 6) hex = "ff$hex"
            if (hex.length != 8) return null
            val v = hexUInt32(hex) ?: return null
            return KMLColor(
                alpha = (v shr 24 and 0xff).toDouble() / 255,
                blue = (v shr 16 and 0xff).toDouble() / 255,
                green = (v shr 8 and 0xff).toDouble() / 255,
                red = (v and 0xff).toDouble() / 255,
            )
        }
    }
}

/** The parts of a KML style SatMap draws: outlines and lines (LineStyle), polygon fills (PolyStyle) and point
 *  icons' colour (IconStyle). Unset parts are null (the app's own look). */
data class KMLStyle(
    val lineColor: KMLColor? = null,
    val lineWidth: Double? = null,
    val fillColor: KMLColor? = null,
    val fill: Boolean? = null,
    val outline: Boolean? = null,
    val iconColor: KMLColor? = null,
) {
    val isEmpty: Boolean get() = this == KMLStyle()

    /** This style's parts, and `base`'s where this one has none (a placemark's own style over its shared one). */
    fun over(base: KMLStyle): KMLStyle =
        KMLStyle(
            lineColor = lineColor ?: base.lineColor, lineWidth = lineWidth ?: base.lineWidth,
            fillColor = fillColor ?: base.fillColor, fill = fill ?: base.fill, outline = outline ?: base.outline,
            iconColor = iconColor ?: base.iconColor,
        )
}

/** A KML, KMZ or GPX file (`GPXParser` reads GPX into the same document). */
data class KMLDocument(
    val name: String?,
    val features: List<KMLFeature>,
    /** KML (or KMZ) or GPX: GPX has no styles, and its closed tracks stay lines (`FileShapes`). */
    val format: Format = Format.kml,
) {
    enum class Format {
        kml,
        gpx,
    }

    val bounds: GeoBounds?
        get() {
            val all = mutableListOf<GeoPoint>()
            for (f in features) {
                when (val g = f.geometry) {
                    is KMLGeometry.Point -> all.add(g.point)
                    is KMLGeometry.Line -> all.addAll(g.points)
                    is KMLGeometry.Polygon -> all.addAll(g.outer)
                }
            }
            return GeoBounds(points = all)
        }

    /** The file's geometry as it is drawn: polygons filled, lines and points, all grown by `bufferMeters`. What a file
     *  saves offline is `FileShapes`. */
    fun coverageShape(bufferMeters: Double): CoverageShape {
        val polygons = mutableListOf<List<GeoPoint>>()
        val lines = mutableListOf<List<GeoPoint>>()
        val points = mutableListOf<GeoPoint>()
        for (f in features) {
            when (val g = f.geometry) {
                is KMLGeometry.Point -> points.add(g.point)
                is KMLGeometry.Line -> lines.add(g.points)
                is KMLGeometry.Polygon -> polygons.add(g.outer)
            }
        }
        return CoverageShape(polygons = polygons, lines = lines, points = points, bufferMeters = bufferMeters)
    }
}

/** The Swift `KMLError` cases; the message is the Swift `errorDescription`. */
sealed class KMLError(val errorDescription: String) : Exception(errorDescription) {
    class Unreadable : KMLError("The file could not be read as KML.")
    class NoKMLInArchive : KMLError("The KMZ archive does not contain a .kml file.")
    class NoGeometry : KMLError("The file contains no points, lines or polygons.")
}

object KMLParser {
    /** A file for the Blocks list: GPX when its first element is <gpx> (`GPXParser.isGPX`), else KML or KMZ. */
    fun parseKMLOrGPX(data: ByteArray): KMLDocument = if (GPXParser.isGPX(data)) GPXParser.parse(data) else parse(data)

    /** Parses .kml or .kmz data (KMZ is detected by its zip signature). */
    fun parse(data: ByteArray): KMLDocument {
        var kmlData = data
        if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 0x03.toByte() &&
            data[3] == 0x04.toByte()
        ) {
            val archive = ZipArchive(data = data)
            // By Swift Characters (".kml" after a character joining its dot is no suffix; a "/" with a mark is no
            // separator).
            val kmlEntries = archive.entries.filter {
                swiftCharacters(it.name.lowercase()).takeLast(4) == listOf(".", "k", "m", "l")
            }
            // Google Earth writes the main document as doc.kml at the root.
            val entry = kmlEntries.firstOrNull { it.name.lowercase() == "doc.kml" }
                ?: kmlEntries.minByOrNull { e ->
                    var n = 0
                    var inPiece = false
                    for (c in swiftCharacters(e.name)) {
                        if (c == "/") inPiece = false else if (!inPiece) { n++; inPiece = true }
                    }
                    n
                }
                ?: throw KMLError.NoKMLInArchive()
            kmlData = archive.extract(entry)
        }
        val delegate = KMLParserDelegate()
        val parsed = parseAsXMLParser(kmlData, delegate) ?: throw KMLError.Unreadable()
        // What was read before an error still counts, as with XMLParser.
        if (!(parsed || delegate.features.isNotEmpty())) throw KMLError.Unreadable()
        if (delegate.features.isEmpty()) throw KMLError.NoGeometry()
        return KMLDocument(name = delegate.documentName, features = delegate.styledFeatures())
    }

    /** Parses a KML `<coordinates>` string: whitespace-separated "lon,lat[,alt]" tuples. */
    fun coordinates(text: String): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        for (token in splitCharacters(text) { it == ' ' || it == '\n' || it == '\t' || it == '\r' }) {
            val parts = splitCharacters(token) { it == ',' }
            if (parts.size < 2) continue
            val lon = swiftDouble(parts[0]) ?: continue
            val lat = swiftDouble(parts[1]) ?: continue
            if (!(lat in -90.0..90.0 && lon in -180.0..180.0)) continue
            out.add(GeoPoint(lat = lat, lon = lon))
        }
        return out
    }
}

/** Parses a file with a SAX handler as Foundation's XMLParser (shouldProcessNamespaces off) reads it, for the KML and
 *  GPX parsers: element and attribute names as written ("gx:CascadingStyle", "kml:id"), in the encoding the XML
 *  declaration names, with the entities its DTD declares and carriage returns in CDATA as written. True when all of it
 *  was read, false after an error (what was read before it has reached the handler); null when the declaration names
 *  an encoding no charset has (unreadable, as with libxml2). The handler must not fetch external entities. No Swift
 *  function of its own: XMLParser does all this. */
internal fun parseAsXMLParser(data: ByteArray, handler: DefaultHandler): Boolean? {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = false
    // XMLParser (libxml2) decodes in the encoding the XML declaration names; Android's SAX parser (Expat) reads
    // any byte stream as UTF-8 whatever it says. So another declared encoding is decoded here, up to the first
    // byte it can't decode (where libxml2 stops), and parsed as text.
    val declared = try {
        declaredCharset(data)
    } catch (e: IllegalArgumentException) {
        return null
    }
    // A file whose DTD declares entities: as XMLParser reports them (see XMLParserEntities).
    val expanded = XMLParserEntities.expand(data, declared)
    var decodedAll = true
    val source = when {
        expanded != null -> InputSource(StringReader(keepingCDATACarriageReturns(expanded)))
        declared == null -> InputSource(ByteArrayInputStream(keepingCDATACarriageReturns(data)))
        else -> {
            val decoder = declared.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val out = CharBuffer.allocate((data.size * decoder.maxCharsPerByte().toDouble()).toInt() + 16)
            decodedAll = !decoder.decode(ByteBuffer.wrap(data), out, true).isError && !decoder.flush(out).isError
            InputSource(StringReader(keepingCDATACarriageReturns(out.flip().toString())))
        }
    }
    return try {
        factory.newSAXParser().parse(source, handler)
        decodedAll
    } catch (e: SAXException) {
        false
    } catch (e: IOException) {
        false
    }
}

/** The charset an XML declaration at the very start names, when it isn't UTF-8 or UTF-16 (null: none, or one of
 *  those, which the byte stream is read as). Throws `IllegalArgumentException` for a name no charset has. */
private fun declaredCharset(data: ByteArray): Charset? {
    val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
    val name = xmlDeclEncoding.find(head)?.groupValues?.get(2) ?: return null
    val charset = Charset.forName(name)
    return if (charset == Charsets.UTF_8 || charset.name().startsWith("UTF-16")) null else charset
}

private val xmlDeclEncoding = Regex("^<\\?xml\\s[^>]*?\\sencoding\\s*=\\s*([\"'])([A-Za-z][A-Za-z0-9._-]*)\\1")

private class KMLParserDelegate : DefaultHandler() {
    private data class StyleMapEntry(val url: String?, val style: KMLStyle?)
    private data class FeatureStyle(val url: String?, val own: KMLStyle?)
    private class StylePair(var key: String? = null, var url: String? = null, var style: KMLStyle? = null)
    private class PendingGeometry(val geometry: KMLGeometry, val isRing: Boolean)

    val features = mutableListOf<KMLFeature>()
    var documentName: String? = null

    private val stack = mutableListOf<String>()
    private var text = StringBuilder()
    private var placemarkName: String? = null
    private var placemarkDescription: String? = null
    private var placemarkData = mutableListOf<KMLData>()
    /** The name of the Data or SimpleData being read, and a Data's value. */
    private var dataName: String? = null
    private var dataValue: String? = null
    private var inPlacemark = false
    /** Each geometry, and whether it is a LinearRing of its own. */
    private var pendingGeometries = mutableListOf<PendingGeometry>()
    /** The Folders and Documents around what is being read, outermost first, with their names (null: none yet). */
    private val containers = mutableListOf<String?>()
    private var outer: List<GeoPoint> = emptyList()
    private var holes = mutableListOf<List<GeoPoint>>()

    /** Styles: shared ones by id (anywhere in the file, as Google Earth reads them), style maps by id (the style for
     *  their "normal" key: a link, or a style of its own), and each feature's link and own style, joined up at the end
     *  (shared styles may come after the placemarks that use them). */
    private val styles = mutableMapOf<String, KMLStyle>()
    private val styleMaps = mutableMapOf<String, StyleMapEntry>()
    private val featureStyles = mutableListOf<FeatureStyle>()
    private var style: KMLStyle? = null
    private var styleID: String? = null
    /** Google Earth Web and the Earth app write each shared style as an id-less Style in a gx:CascadingStyle with the id. */
    private var cascadingID: String? = null
    private var placemarkStyleURL: String? = null
    private var placemarkStyle: KMLStyle? = null
    private var styleMapID: String? = null
    private var pair: StylePair? = null

    /** The features with their styles: a placemark's own style over the one its styleUrl names (through a style map's
     *  normal style). */
    fun styledFeatures(): List<KMLFeature> =
        features.zip(featureStyles).map { (feature, s) ->
            var shared: KMLStyle? = null
            val id = s.url?.let { styleID(it) }
            if (id != null) {
                val m = styleMaps[id]
                shared = if (m != null) {
                    m.style ?: m.url?.let { styleID(it) }?.let { styles[it] }
                } else {
                    styles[id]
                }
            }
            val merged = (s.own ?: KMLStyle()).over(shared ?: KMLStyle())
            feature.copy(style = if (merged.isEmpty) null else merged)
        }

    /** The id a styleUrl points to: "#id", or "file.kml#id" (only styles in this file are known). */
    private fun styleID(url: String): String? {
        val u = swiftTrimmed(url)
        val chars = swiftCharacters(u) // a '#' with a mark after it is no '#'
        val hash = chars.lastIndexOf("#")
        if (hash < 0) return if (u.isEmpty()) null else key(u)
        val id = chars.drop(hash + 1).joinToString("")
        return if (id.isEmpty()) null else key(id)
    }

    /** Swift's [String: ...] matches canonically equivalent ids ("café" composed or decomposed): key by NFC. */
    private fun key(id: String): String = Normalizer.normalize(id, Normalizer.Form.NFC)

    private fun flag(text: String): Boolean? =
        when (swiftTrimmed(text).lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }

    private fun local(name: String): String {
        val i = name.lastIndexOf(':')
        return if (i >= 0) name.substring(i + 1) else name
    }

    /** Like XMLParser (which doesn't resolve external entities): no external DTD or entity is fetched. */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        val name = local(qName)
        stack.add(name)
        text = StringBuilder()
        when (name) {
            "Placemark" -> {
                inPlacemark = true
                placemarkName = null
                placemarkDescription = null
                placemarkData = mutableListOf()
                pendingGeometries = mutableListOf()
                placemarkStyleURL = null
                placemarkStyle = null
            }
            "Folder", "Document" -> containers.add(null)
            "Data", "SimpleData" -> {
                dataName = attributes.getValue("name")
                dataValue = null
            }
            "Polygon" -> {
                outer = emptyList()
                holes = mutableListOf()
            }
            "Style" -> {
                style = KMLStyle()
                val parent = if (stack.size >= 2) stack[stack.size - 2] else ""
                styleID = attributes.getValue("id") ?: (if (parent == "CascadingStyle") cascadingID else null)
            }
            "CascadingStyle" -> cascadingID = attributes.getValue("kml:id") ?: attributes.getValue("id")
            "StyleMap" -> styleMapID = attributes.getValue("id")
            "Pair" -> pair = StylePair()
        }
    }

    // CDATA sections arrive here too (XMLParser hands them to foundCDATA).
    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val name = local(qName)
        try {
            ended(name)
        } finally {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            text = StringBuilder()
        }
    }

    private fun ended(name: String) {
        val parent = if (stack.size >= 2) stack[stack.size - 2] else ""
        val value = swiftTrimmed(text.toString())
        when {
            name == "color" && style != null -> {
                val c = KMLColor(kml = value)
                when (parent) {
                    "LineStyle" -> style = style?.copy(lineColor = c)
                    "PolyStyle" -> style = style?.copy(fillColor = c)
                    "IconStyle" -> style = style?.copy(iconColor = c)
                }
            }
            name == "width" && style != null && parent == "LineStyle" -> style = style?.copy(lineWidth = swiftDouble(value))
            name == "fill" && style != null && parent == "PolyStyle" -> style = style?.copy(fill = flag(value))
            name == "outline" && style != null && parent == "PolyStyle" -> style = style?.copy(outline = flag(value))
            name == "Style" -> {
                val s = style ?: return
                style = null
                val p = pair
                val id = styleID
                if (p != null) {
                    p.style = s
                } else if (parent == "Placemark" && inPlacemark) {
                    placemarkStyle = s
                } else if (id != null) {
                    styles[key(id)] = s
                }
                styleID = null
            }
            name == "key" && parent == "Pair" -> pair?.key = value
            name == "styleUrl" -> {
                val p = pair
                if (p != null) {
                    p.url = value
                } else if (parent == "Placemark" && inPlacemark) {
                    placemarkStyleURL = value
                }
            }
            name == "Pair" -> {
                val p = pair
                val id = styleMapID
                if (p != null && id != null && (p.key == null || p.key == "normal")) {
                    styleMaps[key(id)] = StyleMapEntry(url = p.url, style = p.style)
                }
                pair = null
            }
            name == "StyleMap" -> styleMapID = null
            name == "CascadingStyle" -> cascadingID = null
            name == "name" -> {
                if (parent == "Placemark") {
                    placemarkName = if (value.isEmpty()) null else value
                } else if (parent == "Folder" || parent == "Document") {
                    if (parent == "Document" && documentName == null && value.isNotEmpty()) documentName = value
                    if (containers.isNotEmpty() && containers.last() == null && value.isNotEmpty()) {
                        containers[containers.lastIndex] = value
                    }
                }
            }
            name == "Folder" || name == "Document" -> if (containers.isNotEmpty()) containers.removeAt(containers.lastIndex)
            name == "description" && parent == "Placemark" -> placemarkDescription = if (value.isEmpty()) null else value
            name == "value" && parent == "Data" -> dataValue = value
            name == "Data" || name == "SimpleData" -> {
                val n = dataName?.let(::swiftTrimmed)
                if (inPlacemark && !n.isNullOrEmpty()) {
                    placemarkData.add(KMLData(name = n, value = if (name == "Data") dataValue ?: "" else value))
                }
                dataName = null
                dataValue = null
            }
            name == "coordinates" -> {
                if (!inPlacemark) return
                val pts = KMLParser.coordinates(text.toString())
                // stack: ... Geometry, [outerBoundaryIs|innerBoundaryIs, LinearRing,] coordinates
                val ancestors = stack.dropLast(1).takeLast(3)
                if (ancestors.contains("outerBoundaryIs")) {
                    outer = pts
                } else if (ancestors.contains("innerBoundaryIs")) {
                    if (pts.size >= 3) holes.add(pts)
                } else if (parent == "LineString" || parent == "LinearRing") {
                    if (pts.size >= 2) {
                        pendingGeometries.add(PendingGeometry(KMLGeometry.Line(pts), isRing = parent == "LinearRing"))
                    } else if (pts.isNotEmpty()) {
                        pendingGeometries.add(PendingGeometry(KMLGeometry.Point(pts.first()), isRing = false))
                    }
                } else if (parent == "Point") {
                    if (pts.isNotEmpty()) pendingGeometries.add(PendingGeometry(KMLGeometry.Point(pts.first()), isRing = false))
                }
            }
            name == "Polygon" -> {
                if (outer.size >= 3) {
                    pendingGeometries.add(PendingGeometry(KMLGeometry.Polygon(outer = outer, holes = holes.toList()), isRing = false))
                }
                outer = emptyList()
                holes = mutableListOf()
            }
            name == "Placemark" -> {
                val folders = containers.asReversed().filterNotNull()
                for (g in pendingGeometries) {
                    features.add(
                        KMLFeature(
                            name = placemarkName, geometry = g.geometry, description = placemarkDescription,
                            folders = folders, data = placemarkData.toList(), isRing = g.isRing,
                        )
                    )
                    featureStyles.add(FeatureStyle(url = placemarkStyleURL, own = placemarkStyle))
                }
                pendingGeometries = mutableListOf()
                inPlacemark = false
            }
        }
    }
}

/** XMLParser (libxml2 reading the file in pieces) hands a CDATA section over as written, carriage returns and all, so
 *  "\r\n" in one stays "\r\n" (one Character: no separator between coordinates). Java's parsers make every line end
 *  "\n", so each carriage return in a CDATA section goes in as a character reference (which they leave as it is):
 *  "]]>&#13;<![CDATA[". Comments and processing instructions are passed over; the file's code units are bytes
 *  (UTF-8 and other ASCII-based encodings) or UTF-16 ones (by its byte order mark or "<?"). */
private fun keepingCDATACarriageReturns(data: ByteArray): ByteArray {
    if (!data.contains(0x0D)) return data
    val b = { i: Int -> if (i < data.size) data[i].toInt() and 0xff else -1 }
    val bigEndian = b(0) == 0xFE && b(1) == 0xFF || b(0) == 0 && b(1) == 0x3C && b(2) == 0 && b(3) == 0x3F
    val littleEndian = b(0) == 0xFF && b(1) == 0xFE || b(0) == 0x3C && b(1) == 0 && b(2) == 0x3F && b(3) == 0
    val width = if (bigEndian || littleEndian) 2 else 1
    val (high, low) = if (littleEndian) 1 to 0 else 0 to 1 // a UTF-16 code unit's bytes
    // The code units as chars, rewritten, and back.
    val text = String(CharArray(data.size / width) { i ->
        (if (width == 1) b(i) else b(2 * i + high) shl 8 or b(2 * i + low)).toChar()
    })
    val kept = keepingCDATACarriageReturns(text)
    if (kept.length == text.length) return data
    val out = ByteArray(kept.length * width + data.size % width)
    for (i in kept.indices) {
        val c = kept[i].code
        if (width == 1) {
            out[i] = c.toByte()
        } else {
            out[2 * i + high] = (c shr 8).toByte()
            out[2 * i + low] = c.toByte()
        }
    }
    if (data.size % width != 0) out[out.size - 1] = data[data.size - 1]
    return out
}

/** The same for text (a file decoded here). */
private fun keepingCDATACarriageReturns(text: String): String {
    if (!text.contains('\r')) return text
    val out = StringBuilder(text.length + 64)
    var i = 0
    while (i < text.length) {
        val lt = text.indexOf('<', i)
        if (lt < 0) {
            out.append(text, i, text.length)
            break
        }
        out.append(text, i, lt)
        i = lt
        val end = when {
            text.startsWith("<![CDATA[", i) -> "]]>"
            text.startsWith("<!--", i) -> "-->"
            text.startsWith("<?", i) -> "?>"
            else -> null
        }
        if (end == null) {
            out.append('<')
            i++
            continue
        }
        val close = text.indexOf(end, i).let { if (it < 0) text.length else it + end.length }
        if (end == "]]>") {
            for (j in i until close) if (text[j] == '\r') out.append("]]>&#13;<![CDATA[") else out.append(text[j])
        } else {
            out.append(text, i, close)
        }
        i = close
    }
    return out.toString()
}

// Swift's String handling where Kotlin's differs, so a file reads exactly as on iOS.

/** Swift's `trimmingCharacters(in: .whitespacesAndNewlines)`: Unicode spaces (Zs) and U+200B ZERO WIDTH SPACE (as
 *  Core Foundation's whitespace set has U+2000–U+200B), line and paragraph separators, tab, line feed to carriage
 *  return, and NEL. Kotlin's `trim()` also takes U+001C–U+001F and leaves NEL and U+200B. */
internal fun swiftTrimmed(s: String): String =
    s.trim { c ->
        c == '\t' || c in '\n'..'\r' || c == '\u0085' || c == '\u200B' || Character.getType(c).toByte().let {
            it == Character.SPACE_SEPARATOR || it == Character.LINE_SEPARATOR || it == Character.PARAGRAPH_SEPARATOR
        }
    }

/** Swift's `Double(String)`: a decimal or hexadecimal number ("2.5", "-1e3", "0x1.8p1"), or "inf", "infinity" or
 *  "nan" in any case, with an optional sign and nothing around it. Kotlin's `toDoubleOrNull` also takes surrounding
 *  spaces and a "d" or "f" after the number, but not "inf" or a hexadecimal number without a "p" exponent. */
internal fun swiftDouble(text: String): Double? {
    if (!swiftNumber.matches(text)) return null
    val negative = text.startsWith("-")
    val number = text.removePrefix("-").removePrefix("+")
    val magnitude = when {
        number.startsWith("inf", ignoreCase = true) -> Double.POSITIVE_INFINITY
        number.contains("nan", ignoreCase = true) -> Double.NaN
        number.startsWith("0x", ignoreCase = true) && !number.contains('p', ignoreCase = true) -> "${number}p0".toDouble()
        else -> number.toDouble()
    }
    return if (negative) -magnitude else magnitude
}

private val swiftNumber = Regex(
    "[+-]?(?:(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?" +
        "|0[xX](?:[0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?" +
        "|(?i:inf|infinity|s?nan(?:\\([0-9a-z_]*\\))?))"
)

/** Swift's `UInt32(text, radix: 16)` for up to 8 characters: hex digits after an optional sign ("-0" is 0). */
private fun hexUInt32(text: String): Long? {
    val digits = if (text.startsWith("+") || text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val v = digits.toLong(16)
    return if (text.startsWith("-") && v != 0L) null else v
}

/** Swift's `split` of a String into pieces between separator Characters, leaving out empty pieces. A Swift Character is
 *  a whole grapheme cluster, so a separator joined to its neighbour doesn't count: "\r\n" is one Character (neither
 *  "\r" nor "\n"), as is a space or comma with a combining mark after it. */
private fun splitCharacters(text: String, isSeparator: (Char) -> Boolean): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    for (i in text.indices) {
        val c = text[i]
        if (!isSeparator(c)) continue
        val alone = when (c) {
            '\r' -> i + 1 >= text.length || text[i + 1] != '\n'
            '\n' -> i == 0 || text[i - 1] != '\r'
            '\t' -> true // a control character is always a Character of its own
            else -> i + 1 >= text.length || !joinsPrevious(text.codePointAt(i + 1))
        }
        if (!alone) continue
        if (i > start) out.add(text.substring(start, i))
        start = i + 1
    }
    if (text.length > start) out.add(text.substring(start))
    return out
}

/** Swift's Characters of a String: its grapheme clusters (a '/', '#' or '.' joined to a mark after it, or to a
 *  Prepend character before it, is not that character). */
private fun swiftCharacters(s: String): List<String> {
    val b = BreakIterator.getCharacterInstance(Locale.ROOT)
    b.setText(s)
    val out = ArrayList<String>()
    var start = b.first()
    var end = b.next()
    while (end != BreakIterator.DONE) {
        out.add(s.substring(start, end))
        start = end
        end = b.next()
    }
    return out
}

/** Unicode's grapheme Extend, ZWJ and SpacingMark (near enough): marks that belong to the character before them. */
private fun joinsPrevious(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> codePoint == 0x200C || codePoint == 0x200D || codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F
    }
