package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Reading place searches (Photon) and boundaries (Nominatim, GeoJSON). */
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
    fun photonResults(data: ByteArray): List<FoundPlace> {
        val root = jsonObject(data) ?: return emptyList()
        val features = objects(root["features"]) ?: return emptyList()
        val out = mutableListOf<FoundPlace>()
        val seen = HashSet<String>()
        for (f in features) {
            val p = f["properties"] as? JsonObject ?: continue
            val name = string(p["name"]) ?: continue
            if (name.isEmpty()) continue
            val key = string(p["osm_key"]) ?: continue
            val value = string(p["osm_value"]) ?: continue
            if (key in skippedKeys || value in skippedValues) continue
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
            // A way or relation without an extent is a spot as far as the area goes; spots only for settlements and camps.
            if (!(extent != null || key == "place" || value in spotKinds)) continue
            val id = "${if (extent == null) "N" else type}$osmID"
            val key2 = "$name|$value|${(c[1] * 100).rounded().toLong()}|${(c[0] * 100).rounded().toLong()}"
            if (id in seen || key2 in seen) continue
            seen.add(id)
            seen.add(key2)
            val region = listOf(p["county"], p["state"], p["country"]).mapNotNull(::string).filter { it != name }
            out.add(
                FoundPlace(
                    id = id, name = name, kind = kindName(value), tag = value, region = region.joinToString(", "),
                    centre = GeoPoint(lat = c[1], lon = c[0]), extent = extent,
                )
            )
        }
        return out
    }

    /** The JSON in the data if it is an object (Swift's `JSONSerialization` … `as? [String: Any]`), else null. */
    private fun jsonObject(data: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
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
        val words = text.split(' ').filter { it.isNotEmpty() }
        val changed = words.map { w ->
            if (characterCount(w) > 3 && w.lowercase().endsWith("s") && !w.lowercase().endsWith("ss")) w.dropLast(1) else w
        }
        return if (changed == words) null else changed.joinToString(" ")
    }

    /** How much Nominatim may simplify a boundary (degrees): about a 1 500th of its size, at least 10 m. */
    fun simplification(place: FoundPlace): Double {
        val e = place.extent ?: return 0.0001
        return max(0.0001, max(e.maxLat - e.minLat, e.maxLon - e.minLon) / 1500)
    }

    /** Swift's `String.count`: characters as the user sees them (grapheme clusters), not UTF-16 units. */
    private fun characterCount(text: String): Int {
        val b = BreakIterator.getCharacterInstance()
        b.setText(text)
        var n = 0
        while (b.next() != BreakIterator.DONE) n++
        return n
    }
}
