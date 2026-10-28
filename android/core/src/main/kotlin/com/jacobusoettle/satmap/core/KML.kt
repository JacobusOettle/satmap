package com.jacobusoettle.satmap.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
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
)

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

data class KMLDocument(val name: String?, val features: List<KMLFeature>) {
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

    /** The area to download offline for this file: block polygons filled, lines and points buffered. */
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
    /** Parses .kml or .kmz data (KMZ is detected by its zip signature). */
    fun parse(data: ByteArray): KMLDocument {
        var kmlData = data
        if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 0x03.toByte() &&
            data[3] == 0x04.toByte()
        ) {
            val archive = ZipArchive(data = data)
            val kmlEntries = archive.entries.filter { it.name.lowercase().endsWith(".kml") }
            // Google Earth writes the main document as doc.kml at the root.
            val entry = kmlEntries.firstOrNull { it.name.lowercase() == "doc.kml" }
                ?: kmlEntries.minByOrNull { e -> e.name.split('/').count { it.isNotEmpty() } }
                ?: throw KMLError.NoKMLInArchive()
            kmlData = archive.extract(entry)
        }
        val delegate = KMLParserDelegate()
        // As Foundation's XMLParser with shouldProcessNamespaces off: element and attribute names as written
        // ("gx:CascadingStyle", "kml:id").
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parsed = try {
            factory.newSAXParser().parse(ByteArrayInputStream(kmlData), delegate)
            true
        } catch (e: SAXException) {
            false
        } catch (e: IOException) {
            false
        }
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

private class KMLParserDelegate : DefaultHandler() {
    private data class StyleMapEntry(val url: String?, val style: KMLStyle?)
    private data class FeatureStyle(val url: String?, val own: KMLStyle?)
    private class StylePair(var key: String? = null, var url: String? = null, var style: KMLStyle? = null)

    val features = mutableListOf<KMLFeature>()
    var documentName: String? = null

    private val stack = mutableListOf<String>()
    private var text = StringBuilder()
    private var placemarkName: String? = null
    private var inPlacemark = false
    private var pendingGeometries = mutableListOf<KMLGeometry>()
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
        val hash = u.lastIndexOf('#')
        if (hash < 0) return if (u.isEmpty()) null else u
        val id = u.substring(hash + 1)
        return if (id.isEmpty()) null else id
    }

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
                pendingGeometries = mutableListOf()
                placemarkStyleURL = null
                placemarkStyle = null
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
                    styles[id] = s
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
                    styleMaps[id] = StyleMapEntry(url = p.url, style = p.style)
                }
                pair = null
            }
            name == "StyleMap" -> styleMapID = null
            name == "CascadingStyle" -> cascadingID = null
            name == "name" -> {
                if (parent == "Placemark") {
                    placemarkName = if (value.isEmpty()) null else value
                } else if (parent == "Document" && documentName == null && value.isNotEmpty()) {
                    documentName = value
                }
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
                        pendingGeometries.add(KMLGeometry.Line(pts))
                    } else if (pts.isNotEmpty()) {
                        pendingGeometries.add(KMLGeometry.Point(pts.first()))
                    }
                } else if (parent == "Point") {
                    if (pts.isNotEmpty()) pendingGeometries.add(KMLGeometry.Point(pts.first()))
                }
            }
            name == "Polygon" -> {
                if (outer.size >= 3) pendingGeometries.add(KMLGeometry.Polygon(outer = outer, holes = holes.toList()))
                outer = emptyList()
                holes = mutableListOf()
            }
            name == "Placemark" -> {
                for (g in pendingGeometries) {
                    features.add(KMLFeature(name = placemarkName, geometry = g))
                    featureStyles.add(FeatureStyle(url = placemarkStyleURL, own = placemarkStyle))
                }
                pendingGeometries = mutableListOf()
                inPlacemark = false
            }
        }
    }
}

// Swift's String handling where Kotlin's differs, so a file reads exactly as on iOS.

/** Swift's `trimmingCharacters(in: .whitespacesAndNewlines)`: Unicode spaces (Zs), line and paragraph separators,
 *  tab, line feed to carriage return, and NEL. Kotlin's `trim()` also takes U+001C–U+001F and leaves NEL. */
private fun swiftTrimmed(s: String): String =
    s.trim { c ->
        c == '\t' || c in '\n'..'\r' || c == '\u0085' || Character.getType(c).toByte().let {
            it == Character.SPACE_SEPARATOR || it == Character.LINE_SEPARATOR || it == Character.PARAGRAPH_SEPARATOR
        }
    }

/** Swift's `Double(String)`: a decimal or hexadecimal number ("2.5", "-1e3", "0x1.8p1"), or "inf", "infinity" or
 *  "nan" in any case, with an optional sign and nothing around it. Kotlin's `toDoubleOrNull` also takes surrounding
 *  spaces and a "d" or "f" after the number, but not "inf" or a hexadecimal number without a "p" exponent. */
private fun swiftDouble(text: String): Double? {
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

/** Unicode's grapheme Extend, ZWJ and SpacingMark (near enough): marks that belong to the character before them. */
private fun joinsPrevious(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> codePoint == 0x200C || codePoint == 0x200D || codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F
    }
