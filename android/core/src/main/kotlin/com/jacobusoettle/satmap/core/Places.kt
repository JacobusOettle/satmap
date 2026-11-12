package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/** A place found by name for an offline area (a park, a reserve, a town, a camp …), from OpenStreetMap. */
data class FoundPlace(
    /** OpenStreetMap's id: "R1751334" (a relation), "W…" (a way) or "N…" (a node: a spot, no boundary). */
    val id: String,
    val name: String,
    /** "Nature reserve", "Town" … */
    val kind: String,
    /** The OpenStreetMap tag value ("nature_reserve", "town" …), which decides the radius around a spot. */
    val tag: String,
    /** "Mpumalanga, South Africa". */
    val region: String,
    val centre: GeoPoint,
    /** The place's extent, for areas. */
    val extent: GeoBounds?,
) {
    val hasBoundary: Boolean get() = !id.startsWith("N")

    /** How far around a spot (a town, a camp …) to download, in metres. */
    val radiusMeters: Double
        get() = when (tag) {
            "city" -> 10_000.0
            "town" -> 4_000.0
            "village", "suburb", "borough" -> 2_000.0
            else -> 1_000.0
        }

    /** The extent's area in km², for showing how big it is. */
    val extentKm2: Double?
        get() {
            val e = extent ?: return null
            val w = Geo.distance(GeoPoint(lat = (e.minLat + e.maxLat) / 2, lon = e.minLon), GeoPoint(lat = (e.minLat + e.maxLat) / 2, lon = e.maxLon))
            val h = Geo.distance(GeoPoint(lat = e.minLat, lon = e.minLon), GeoPoint(lat = e.maxLat, lon = e.minLon))
            return w * h / 1_000_000
        }
}

/** Reading place searches (Photon, or Nominatim while Photon is down) and boundaries (Nominatim, GeoJSON). */
object Places {
    /** Results that are areas or settlements, not streets, buildings, shops … */
    private val skippedKeys: Set<String> = setOf(
        "highway", "building", "amenity", "shop", "railway", "office", "craft", "man_made", "power", "emergency",
        "healthcare", "club", "public_transport", "barrier", "entrance", "aerialway", "historic", "waterway", "route",
    )

    /** Single buildings and features, even with an outline: not places to save. */
    private val skippedValues: Set<String> = setOf(
        "hotel", "hostel", "guest_house", "motel", "apartment", "museum", "viewpoint", "attraction", "information",
        "artwork", "picnic_site", "swimming_pool", "pitch", "playground", "sports_centre", "fitness_centre", "stadium",
        "peak", "saddle", "spring", "tree", "cave_entrance", "rock", "parking", "yes",
    )

    /** Spots kept although they have no outline: settlements and camps (saved with the area around them). */
    private val spotKinds: Set<String> = setOf("camp_site", "caravan_site", "resort", "chalet")

    /** Reserves and parks: saved within their boundary; the rest that have one also get the area around it. */
    val reserveKinds: Set<String> = setOf("nature_reserve", "national_park", "protected_area", "park", "game_reserve")

    /** The places in a Photon search answer (a GeoJSON FeatureCollection), areas and settlements only, each once. */
    fun photonResults(data: ByteArray): List<FoundPlace> = photonAnswer(data) ?: emptyList()

    /** The places in a Photon answer, or null when it isn't one (not JSON, or no `features` list): Photon failed. */
    fun photonAnswer(data: ByteArray): List<FoundPlace>? {
        val root = jsonObject(data) ?: return null
        val features = objects(root["features"]) ?: return null
        val found = Found()
        for (f in features) {
            val p = f["properties"] as? JsonObject ?: continue
            val name = string(p["name"]) ?: continue
            val key = string(p["osm_key"]) ?: continue
            val value = string(p["osm_value"]) ?: continue
            val type = string(p["osm_type"]) ?: continue
            val osmID = number(p["osm_id"])?.toLong() ?: continue
            val geometry = f["geometry"] as? JsonObject ?: continue
            val c = numbers(geometry["coordinates"]) ?: continue
            if (c.size < 2) continue
            var extent: GeoBounds? = null
            val e = numbers(p["extent"])
            if (e != null && e.size == 4) {
                extent = GeoBounds(minLat = min(e[1], e[3]), minLon = min(e[0], e[2]), maxLat = max(e[1], e[3]), maxLon = max(e[0], e[2]))
            }
            found.add(name, key, value, type, osmID, GeoPoint(lat = c[1], lon = c[0]), extent, listOf(p["county"], p["state"], p["country"]))
        }
        return found.places
    }

    /** The places in a Nominatim search answer (`/search?format=jsonv2&addressdetails=1`, a JSON array), read into the
     *  same places as Photon's: the same kinds kept, the same ids ("R...", "W...", "N..." for a spot), the same region
     *  (county, state, country). A node is a spot (Photon gives it no extent; Nominatim gives every result a box).
     *  Null when it isn't a search answer (not a JSON array of results, such as an error object). */
    fun nominatimResults(data: ByteArray): List<FoundPlace>? {
        val results = objects(jsonValue(data)) ?: return null
        val found = Found()
        for (r in results) {
            val name = string(r["name"]) ?: continue
            val key = string(r["category"]) ?: continue
            val value = string(r["type"]) ?: continue
            val type = when (string(r["osm_type"])) {
                "relation" -> "R"
                "way" -> "W"
                "node" -> "N"
                else -> continue
            }
            val osmID = number(r["osm_id"])?.toLong() ?: continue
            val lat = decimal(r["lat"]) ?: continue
            val lon = decimal(r["lon"]) ?: continue
            var extent: GeoBounds? = null
            val b = (r["boundingbox"] as? JsonArray)?.map(::decimal)
            if (type != "N" && b != null && b.size == 4 && b.all { it != null }) {
                val (s, n, w, e) = b.map { it!! }
                extent = GeoBounds(minLat = min(s, n), minLon = min(w, e), maxLat = max(s, n), maxLon = max(w, e))
            }
            val address = r["address"] as? JsonObject
            found.add(
                name, key, value, type, osmID, GeoPoint(lat = lat, lon = lon), extent,
                listOf(address?.get("county"), address?.get("state"), address?.get("country")),
            )
        }
        return found.places
    }

    /** The places of one answer as they're read, areas and settlements only, each once (by id, and by name, kind and
     *  spot to 0.01 degrees): Photon's and Nominatim's alike. */
    private class Found {
        val places = mutableListOf<FoundPlace>()
        private val seen = HashSet<String>()

        fun add(
            name: String, key: String, value: String, type: String, osmID: Long, centre: GeoPoint, extent: GeoBounds?,
            region: List<JsonElement?>,
        ) {
            if (name.isEmpty()) return
            if (key in skippedKeys || value in skippedValues) return
            // A way or relation without an extent is a spot as far as the area goes; spots only for settlements and camps.
            if (!(extent != null || key == "place" || value in spotKinds)) return
            val id = "${if (extent == null) "N" else type}$osmID"
            val key2 = "$name|$value|${(centre.lat * 100).rounded().toLong()}|${(centre.lon * 100).rounded().toLong()}"
            if (id in seen || key2 in seen) return
            seen.add(id)
            seen.add(key2)
            places.add(
                FoundPlace(
                    id = id, name = name, kind = kindName(value), tag = value,
                    region = region.mapNotNull(::string).filter { it != name }.joinToString(", "), centre = centre, extent = extent,
                )
            )
        }
    }

    /** A number written as a JSON string ("-24.8213030", as Nominatim gives coordinates), or a JSON number. */
    private fun decimal(json: JsonElement?): Double? {
        val p = json as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return if (p.isString) p.content.takeIf(jsonNumber::matches)?.toDoubleOrNull()?.takeIf { it.isFinite() } else number(p)
    }

    /** The JSON in the data if it is an object (Swift's `JSONSerialization` … `as? [String: Any]`), else null. As strict
     *  as JSONSerialization: UTF-8 only, no NaN, Infinity or other bare words, no control characters or lone surrogates
     *  in strings. */
    private fun jsonObject(data: ByteArray): JsonObject? = jsonValue(data) as? JsonObject

    /** The JSON in the data, an object or an array, as strictly as `jsonObject`; else null. */
    private fun jsonValue(data: ByteArray): JsonElement? = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        if (rawControlInString(text)) null
        else Json.parseToJsonElement(text).takeIf { (it is JsonObject || it is JsonArray) && strictJson(it) }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: CharacterCodingException) {
        null
    }

    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** Whether every value is one JSONSerialization reads (kotlinx takes any bare word as a value). */
    private fun strictJson(json: JsonElement): Boolean = when (json) {
        is JsonObject -> json.all { (k, v) -> wholeUtf16(k) && strictJson(v) }
        is JsonArray -> json.all(::strictJson)
        is JsonNull -> true
        is JsonPrimitive ->
            if (json.isString) wholeUtf16(json.content) else json.content == "true" || json.content == "false" || jsonNumber.matches(json.content)
    }

    /** No lone surrogates ("\\ud800"), which JSONSerialization refuses. */
    private fun wholeUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i += 2
            else if (c.isSurrogate()) return false
            else i++
        }
        return true
    }

    /** A control character written as is inside a string, which JSONSerialization refuses (kotlinx takes it). */
    private fun rawControlInString(text: String): Boolean {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString && c == '\\') i++
            else if (c == '"') inString = !inString
            else if (inString && c < ' ') return true
            i++
        }
        return false
    }

    /** An array of objects only (Swift's `as? [[String: Any]]`), else null. */
    private fun objects(json: JsonElement?): List<JsonObject>? {
        val list = json as? JsonArray ?: return null
        return if (list.all { it is JsonObject }) list.map { it as JsonObject } else null
    }

    /** A JSON string (Swift's `as? String`). */
    private fun string(json: JsonElement?): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number, however JSONSerialization gives it (it gives true and false as the numbers 1 and 0 too). */
    private fun number(json: JsonElement?): Double? {
        val p = json as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return when (p.content) {
            "true" -> 1.0
            "false" -> 0.0
            else -> p.content.toDoubleOrNull()
        }
    }

    private fun numbers(json: JsonElement?): List<Double>? {
        val list = json as? JsonArray ?: return null
        val out = list.mapNotNull(::number)
        return if (out.size == list.size) out else null
    }

    /** "nature_reserve" → "Nature reserve". */
    fun kindName(value: String): String = when (value) {
        "camp_site" -> "Camp"
        "aerodrome" -> "Airfield"
        "protected_area" -> "Protected area"
        else -> {
            val words = value.replace("_", " ")
            // The first character as Swift counts them (a grapheme cluster), upper-cased.
            val first = BreakIterator.getCharacterInstance().apply { setText(words) }.next()
            if (first == BreakIterator.DONE) words else words.substring(0, first).uppercase() + words.substring(first)
        }
    }

    /** The outer rings of a GeoJSON geometry (Polygon, MultiPolygon, or a closed LineString), or of the first feature of
     *  a FeatureCollection (Nominatim's lookup answer). Holes are left out: a download covers them too. */
    fun outerRings(data: ByteArray): List<List<GeoPoint>> {
        val root = jsonObject(data) ?: return emptyList()
        val geometry = objects(root["features"])?.firstOrNull()?.get("geometry") as? JsonObject
            ?: root["geometry"] as? JsonObject
            ?: root
        fun ring(json: JsonElement?): List<GeoPoint>? {
            val coords = json as? JsonArray ?: return null
            val pts = coords.mapNotNull { numbers(it) }.mapNotNull { if (it.size >= 2) GeoPoint(lat = it[1], lon = it[0]) else null }
            return if (pts.size >= 3) pts else null
        }
        return when (string(geometry["type"])) {
            "Polygon" -> {
                val rings = geometry["coordinates"] as? JsonArray ?: return emptyList()
                val outer = ring(rings.firstOrNull()) ?: return emptyList()
                listOf(outer)
            }
            "MultiPolygon" ->
                ((geometry["coordinates"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { polygon ->
                    (polygon as? JsonArray)?.firstOrNull()?.let(::ring)
                }
            "LineString" -> {
                val pts = ring(geometry["coordinates"]) ?: return emptyList()
                if (!(Geo.distance(pts.first(), pts.last()) < 50)) return emptyList()
                listOf(pts)
            }
            else -> emptyList()
        }
    }

    /** The download area for a place: its boundary (grown by as much as it was simplified, so nothing along its edge is
     *  cut off; a camp's or a town's also by the area around it), else its extent, else (a spot) the circle around it. */
    fun area(place: FoundPlace, rings: List<List<GeoPoint>>): CoverageShape {
        val around = if (place.tag in reserveKinds || place.tag == "administrative") 0.0 else min(place.radiusMeters, 1_000.0)
        val grow = max(simplification(place) * 111_320, around)
        if (rings.isNotEmpty()) return CoverageShape(polygons = rings, bufferMeters = grow)
        val e = place.extent
        if (e != null && place.hasBoundary) {
            return CoverageShape(
                polygons = listOf(
                    listOf(
                        GeoPoint(lat = e.minLat, lon = e.minLon), GeoPoint(lat = e.minLat, lon = e.maxLon),
                        GeoPoint(lat = e.maxLat, lon = e.maxLon), GeoPoint(lat = e.maxLat, lon = e.minLon),
                    )
                ),
                bufferMeters = around,
            )
        }
        return CoverageShape(points = listOf(place.centre), bufferMeters = place.radiusMeters)
    }

    /** Two answers in one, each place once: reserves and parks first, the largest first (the whole of a reserve before
     *  pieces of it), then the rest in the search's order. */
    fun merged(lists: List<List<FoundPlace>>): List<FoundPlace> {
        val seen = HashSet<String>()
        val all = lists.flatten().filter { seen.add(it.id) }
        val reserves = all.filter { it.hasBoundary && it.tag in reserveKinds }
            .sortedByDescending { it.extentKm2 ?: 0.0 }
        return reserves + all.filter { !(it.hasBoundary && it.tag in reserveKinds) }
    }

    /** The search again without a plural's "s" ("Sabi Sands" → "Sabi Sand"), as names are often written both ways; null
     *  when there is none. */
    fun singular(text: String): String? {
        // Whole characters (grapheme clusters), as Swift splits, counts, compares and drops them: a space followed by a
        // combining mark is part of a word, and "abc\u0600s" ends in the character "\u0600s", not "s".
        val words = splitAtSpaces(text)
        val changed = words.map { w ->
            val chars = characters(w)
            val lower = characters(w.lowercase())
            if (chars.size > 3 && lower.lastOrNull() == "s" && lower.getOrNull(lower.size - 2) != "s") chars.dropLast(1).joinToString("") else w
        }
        return if (changed == words) null else changed.joinToString(" ")
    }

    /** How much Nominatim may simplify a boundary (degrees): about a 1 500th of its size, at least 10 m. */
    fun simplification(place: FoundPlace): Double {
        val e = place.extent ?: return 0.0001
        return max(0.0001, max(e.maxLat - e.minLat, e.maxLon - e.minLon) / 1500)
    }

    /** Swift's characters of a text: as the user sees them (grapheme clusters), not UTF-16 units. */
    private fun characters(text: String): List<String> {
        val b = BreakIterator.getCharacterInstance()
        b.setText(text)
        val out = ArrayList<String>()
        var from = 0
        var to = b.next()
        while (to != BreakIterator.DONE) {
            out.add(text.substring(from, to))
            from = to
            to = b.next()
        }
        return out
    }

    /** Swift's `split(separator: " ")`: cuts only at spaces that are characters of their own (a space followed by a
     *  combining mark is one character, part of a word), leaving out empty words. */
    private fun splitAtSpaces(text: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in characters(text)) {
            if (c != " ") {
                word.append(c)
            } else if (word.isNotEmpty()) {
                words.add(word.toString())
                word.clear()
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }
}
