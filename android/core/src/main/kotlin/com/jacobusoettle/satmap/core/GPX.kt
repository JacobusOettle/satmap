package com.jacobusoettle.satmap.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader

/** The Swift `GPXError` cases; the message is the Swift `errorDescription`. */
sealed class GPXError(val errorDescription: String) : Exception(errorDescription) {
    class Unreadable : GPXError("The file could not be read as GPX.")
    class NoGeometry : GPXError("The file contains no waypoints, routes or tracks.")
}

/** GPX files (1.0 and 1.1, with or without namespace prefixes), read into the same document as a KML file so the app
 *  treats both alike: each waypoint is a point, each route a line, and each track one line through all its segments in
 *  order (a route or track of one point is a point). A feature keeps its name, its description (desc, then cmt), and
 *  its type (as data named "type") and the simple values in its extensions as data, for `FileShapes`. GPX has no
 *  styles. */
object GPXParser {
    fun parse(data: ByteArray): KMLDocument {
        val delegate = GPXParserDelegate()
        val parsed = parseAsXMLParser(data, delegate) ?: throw GPXError.Unreadable()
        // What was read before an error still counts, as with KML.
        if (!(parsed || delegate.features.isNotEmpty())) throw GPXError.Unreadable()
        if (delegate.features.isEmpty()) throw GPXError.NoGeometry()
        return KMLDocument(name = delegate.documentName, features = delegate.features.toList(), format = KMLDocument.Format.gpx)
    }

    /** Whether the file's first element is <gpx> (with or without a prefix). */
    fun isGPX(data: ByteArray): Boolean {
        val name = rootElement(data) ?: return false
        return name == "gpx" || name.endsWith(":gpx")
    }

    /** The name of the file's first element as written ("gpx", "gpx:gpx"), after any XML declaration, comments and
     *  document type; null if there is none in its first 64 KB. Read from the bytes: UTF-8 (or another encoding that
     *  writes markup in ASCII) or UTF-16 (by its byte order mark, or a zero byte next to the first "<"). */
    fun rootElement(data: ByteArray): String? {
        var b = data.copyOf(minOf(data.size, 65_536))
        fun startsWith(vararg prefix: Int) = b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it].toByte() }
        fun picking(from: Int): ByteArray {
            val source = b
            return ByteArray(maxOf(0, (source.size - from + 1) / 2)) { source[from + 2 * it] }
        }
        when {
            startsWith(0xFE, 0xFF) -> b = picking(3)
            startsWith(0xFF, 0xFE) -> b = picking(2)
            startsWith(0x00, 0x3C) -> b = picking(1)
            startsWith(0x3C, 0x00) -> b = picking(0)
            startsWith(0xEF, 0xBB, 0xBF) -> b = b.copyOfRange(3, b.size)
        }
        fun at(i: Int, s: String) = i + s.length <= b.size && s.indices.all { b[i + it] == s[it].code.toByte() }
        fun after(end: String, from: Int): Int? {
            var j = from
            while (j < b.size) {
                if (at(j, end)) return j + end.length
                j++
            }
            return null
        }
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            when {
                c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D -> i++
                c == 0x3C && at(i, "<?") -> i = after("?>", i + 2) ?: return null
                c == 0x3C && at(i, "<!--") -> i = after("-->", i + 4) ?: return null
                c == 0x3C && at(i, "<!") -> {
                    // A document type, to its ">" (past any declarations in [ ]).
                    var depth = 0
                    i += 2
                    while (i < b.size && (b[i] != 0x3E.toByte() || depth > 0)) {
                        if (b[i] == 0x5B.toByte()) depth++ else if (b[i] == 0x5D.toByte()) depth--
                        i++
                    }
                    i++
                }
                c == 0x3C -> {
                    var j = i + 1
                    while (j < b.size && (b[j].toInt() and 0xFF) !in nameEnds) j++
                    return if (j > i + 1) String(b, i + 1, j - i - 1, Charsets.UTF_8) else null
                }
                else -> return null
            }
        }
        return null
    }

    private val nameEnds = setOf(0x20, 0x09, 0x0A, 0x0D, 0x3E, 0x2F)
}

private class GPXParserDelegate : DefaultHandler() {
    val features = mutableListOf<KMLFeature>()
    var documentName: String? = null

    private val stack = mutableListOf<String>()
    /** Whether each open element has an element inside it (a simple one holds text only). */
    private val hasChildren = mutableListOf<Boolean>()
    private var text = StringBuilder()

    /** The waypoint, route or track being read: its place in the stack, and what it has so far. */
    private var featureDepth: Int? = null
    private var featureName: String? = null
    private var desc: String? = null
    private var cmt: String? = null
    private var data = mutableListOf<KMLData>()
    private var points = mutableListOf<GeoPoint>()

    private companion object {
        /** What a waypoint, route or track holds in GPX itself. Any other simple element in one (GPX 1.0 puts
         *  extensions there) or in its <extensions> is data. */
        val gpxElements = setOf(
            "ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link", "url", "urlname", "sym", "type",
            "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid", "number", "extensions", "rtept", "trkseg",
        )

        fun point(attributes: Attributes): GeoPoint? {
            fun number(key: String): Double? = attributes.getValue(key)?.let { swiftDouble(swiftTrimmed(it)) }
            val lat = number("lat") ?: return null
            val lon = number("lon") ?: return null
            if (!(lat in -90.0..90.0 && lon in -180.0..180.0)) return null
            return GeoPoint(lat = lat, lon = lon)
        }
    }

    private fun local(name: String): String {
        val i = name.lastIndexOf(':')
        return if (i >= 0) name.substring(i + 1) else name
    }

    /** Like XMLParser (which doesn't resolve external entities): no external DTD or entity is fetched. */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        val name = local(qName)
        if (hasChildren.isNotEmpty()) hasChildren[hasChildren.lastIndex] = true
        stack.add(name)
        hasChildren.add(false)
        text = StringBuilder()
        val depth = stack.size - 1
        val d = featureDepth
        if (d != null) {
            val parent = stack[depth - 1]
            if ((name == "rtept" && parent == "rte" && depth == d + 1) || (name == "trkpt" && parent == "trkseg" && depth == d + 2)) {
                point(attributes)?.let { points.add(it) }
            }
        } else if (name == "wpt" || name == "rte" || name == "trk") {
            featureDepth = depth
            featureName = null
            desc = null
            cmt = null
            data = mutableListOf()
            points = mutableListOf()
            if (name == "wpt") point(attributes)?.let { points.add(it) }
        }
    }

    // CDATA sections arrive here too (XMLParser hands them to foundCDATA).
    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val name = local(qName)
        val simple = hasChildren.lastOrNull() == false
        try {
            ended(name, simple)
        } finally {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            if (hasChildren.isNotEmpty()) hasChildren.removeAt(hasChildren.lastIndex)
            text = StringBuilder()
        }
    }

    private fun ended(name: String, simple: Boolean) {
        if (stack.isEmpty()) return
        val depth = stack.size - 1
        val parent = if (depth >= 1) stack[depth - 1] else ""
        val value = swiftTrimmed(text.toString())
        val d = featureDepth
        if (d == null) {
            // The file's name: in <metadata> (GPX 1.1) or in <gpx> itself (1.0).
            if (name == "name" && (parent == "metadata" || parent == "gpx") && documentName == null && value.isNotEmpty()) {
                documentName = value
            }
            return
        }
        if (depth == d) {
            finishFeature(name)
            featureDepth = null
        } else if (depth == d + 1) {
            when (name) {
                "name" -> featureName = value.ifEmpty { null }
                "desc" -> desc = value.ifEmpty { null }
                "cmt" -> cmt = value.ifEmpty { null }
                "type" -> if (value.isNotEmpty()) data.add(KMLData(name = "type", value = value))
                else -> if (simple && name !in gpxElements && value.isNotEmpty()) data.add(KMLData(name = name, value = value))
            }
        } else if (stack[d + 1] == "extensions" && simple && value.isNotEmpty()) {
            data.add(KMLData(name = name, value = value))
        }
    }

    private fun finishFeature(kind: String) {
        val first = points.firstOrNull() ?: return
        val geometry = if (kind == "wpt" || points.size == 1) KMLGeometry.Point(first) else KMLGeometry.Line(points.toList())
        val texts = listOfNotNull(desc, if (cmt == desc) null else cmt)
        features.add(
            KMLFeature(
                name = featureName, geometry = geometry,
                description = if (texts.isEmpty()) null else texts.joinToString("\n"), data = data.toList(),
            )
        )
    }
}
