package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.text.BreakIterator
import java.util.UUID

/** An offline area's settings: the app's list of areas, and the JSON inside each exported file. That JSON is the
 *  backup format on the iPhone and on Android alike (backups move between them), so its shape — Swift's own
 *  encoding of this struct: the id in capitals, dates as seconds since 1 January 2001, values not set left out —
 *  must stay as it is (`toJson`, `fromJson`). */
data class OfflineArea(
    val id: UUID,
    var name: String,
    /** Seconds since 1970. */
    var created: Double,
    var shape: CoverageShape,
    /** Satellite imagery only along these roads (with `Roads.corridorMeters` on each side); null = the whole
     *  area. Elevation is always downloaded for the whole area. */
    var roads: List<List<GeoPoint>>? = null,
    /** Or the roads of a set shipped with the app (`RoadPresets`), so they aren't stored again here. */
    var preset: String? = null,
    /** The area along your recorded track ("Where I've been"), updated from the latest track each time. */
    var isTrack: Boolean? = null,
    var maxZoom: Int,
    var allowCellular: Boolean,
    var status: Status,
    var failedTiles: Int,
    var bytesOnDisk: Long,
    /** Why the last run stopped early (e.g. storage full), shown to the user. */
    var problem: String? = null,
) {
    enum class Status {
        queued,
        downloading,
        paused,
        waitingForNetwork,
        complete,
        incomplete;

        /** The saved form, as on iOS. */
        val rawValue: String get() = name
    }

    val alongRoads: Boolean get() = roads != null || preset != null

    /** Swift's `JSONEncoder` form: the keys are the property names, `id` in capitals, `created` in seconds since
     *  1 January 2001, whole numbers without a decimal point, properties not set left out. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id.toString().uppercase())
        put("name", name)
        put("created", swiftNumber(created - referenceDate))
        put("shape", shape.toJson())
        roads?.let { r -> put("roads", buildJsonArray { r.forEach { line -> add(buildJsonArray { line.forEach { add(it.toJson()) } }) } }) }
        preset?.let { put("preset", it) }
        isTrack?.let { put("isTrack", it) }
        put("maxZoom", maxZoom)
        put("allowCellular", allowCellular)
        put("status", status.rawValue)
        put("failedTiles", failedTiles)
        put("bytesOnDisk", bytesOnDisk)
        problem?.let { put("problem", it) }
    }

    companion object {
        /** 1 January 2001 in seconds since 1970: Swift's reference date, which its dates are encoded from. */
        private const val referenceDate = 978_307_200.0

        /** Reads `toJson()`'s form, from either app: keys in any order, the id in either case, numbers with or
         *  without a decimal point, and null for a value not set. Throws if it isn't one. */
        fun fromJson(json: JsonElement): OfflineArea {
            val o = json.jsonObject
            fun value(key: String): JsonElement = o[key]?.takeIf { it !is JsonNull } ?: throw IllegalArgumentException("No $key")
            fun optional(key: String): JsonElement? = o[key]?.takeIf { it !is JsonNull }
            return OfflineArea(
                id = uuid(value("id").jsonPrimitive.content),
                name = string(value("name")),
                created = value("created").jsonPrimitive.double + referenceDate,
                shape = CoverageShape.fromJson(value("shape")),
                roads = optional("roads")?.jsonArray?.map { line -> line.jsonArray.map(GeoPoint::fromJson) },
                preset = optional("preset")?.let(::string),
                isTrack = optional("isTrack")?.jsonPrimitive?.boolean,
                maxZoom = wholeNumber(value("maxZoom")).toInt(),
                allowCellular = value("allowCellular").jsonPrimitive.boolean,
                status = Status.valueOf(string(value("status"))),
                failedTiles = wholeNumber(value("failedTiles")).toInt(),
                bytesOnDisk = wholeNumber(value("bytesOnDisk")),
                problem = optional("problem")?.let(::string),
            )
        }

        private fun string(json: JsonElement): String {
            val p = json.jsonPrimitive
            require(p.isString) { "Not a string: $p" }
            return p.content
        }

        /** A UUID as Swift writes and reads it: 8-4-4-4-12 hex digits. */
        private fun uuid(s: String): UUID {
            require(Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}").matches(s)) { "Not a UUID: $s" }
            return UUID.fromString(s)
        }

        /** A whole number, written with or without a decimal point ("18" or "18.0"). */
        private fun wholeNumber(json: JsonElement): Long {
            val p = json.jsonPrimitive
            require(!p.isString) { "Not a number: $p" }
            p.content.toLongOrNull()?.let { return it }
            val d = BigDecimal(p.content)
            return d.toBigIntegerExact().longValueExact()
        }

        /** A `Double` as Swift's `JSONEncoder` writes it: in full, a whole number without a decimal point
         *  ("783388800", "783388800.25"). */
        private fun swiftNumber(v: Double): JsonPrimitive {
            if (!v.isFinite()) return JsonPrimitive(v)
            var d = BigDecimal(v.toString()).stripTrailingZeros()
            if (d.scale() < 0) d = d.setScale(0)
            return JsonPrimitive(d)
        }
    }
}

/** Backups of offline areas as plain MBTiles files, one per layer ("<area> - satellite.mbtiles" and
 *  "<area> - elevation.mbtiles"). Each file carries the area's settings in its metadata, so the files can be
 *  restored into the app later, and other map apps can open them meanwhile. */
object OfflineBackup {
    data class Layer(
        /** File name inside the area's folder. */
        val fileName: String,
        /** Stored in the file's metadata to tell the layers apart on restore. */
        val key: String,
        /** Part of the exported file's name. */
        val label: String,
    )

    /** What an exported file holds: its layer's key and the area's settings (`OfflineArea` JSON). */
    class Contents(val layerKey: String, val areaJSON: ByteArray)

    const val areaKey = "satmap_area"
    const val layerKey = "satmap_layer"

    /** The Swift `Failure` cases; the message is the Swift `errorDescription`. */
    sealed class Failure(val errorDescription: String) : Exception(errorDescription) {
        class Busy(val name: String) : Failure("Couldn't prepare $name for export. Try again in a moment.")
        class NotABackup(val name: String) : Failure("$name isn't a SatMap backup.")
    }

    /** Writes the area's settings into its tile files, folds their write-ahead logs into them, and copies them into
     *  `into` under readable names. (On the iPhone a copy is an APFS clone, taking almost no space; here it is a
     *  plain copy. Either way a download that resumes in the area can't change a copy the system may still be
     *  sending.) Layers not downloaded yet are skipped. `usedNames` keeps file names unique. */
    fun export(areaFolder: File, layers: List<Layer>, name: String, areaJSON: String, into: File,
               usedNames: MutableSet<String>): List<File> {
        if (!into.isDirectory && !into.mkdirs()) throw IOException("Couldn't create $into")
        var base = fileSafe(name)
        var n = 2
        while (base.lowercase() in usedNames) {
            base = "${fileSafe(name)} ($n)"
            n += 1
        }
        usedNames.add(base.lowercase())

        val out = mutableListOf<File>()
        for (layer in layers) {
            val source = File(areaFolder, layer.fileName)
            if (!source.exists()) continue
            val store = MBTiles(source, create = false)
            store.setMetadata(mapOf(areaKey to areaJSON, layerKey to layer.key))
            val ready = store.checkpoint()
            store.close()
            if (!ready) throw Failure.Busy(name)
            val target = File(into, "$base - ${layer.label}.mbtiles")
            target.delete()
            source.copyTo(target)
            out.add(target)
        }
        return out
    }

    /** The layer key and area settings stored in an exported file (opened from the app's own storage). */
    fun inspect(file: File): Contents {
        val store = MBTiles(file, create = false)
        try {
            val layer = store.metadata(layerKey)
            val json = store.metadata(areaKey)
            if (layer == null || json == null) throw Failure.NotABackup(file.name)
            return Contents(layerKey = layer, areaJSON = json.toByteArray(Charsets.UTF_8))
        } finally {
            store.close()
        }
    }

    /** The name without characters that file systems reject. */
    fun fileSafe(name: String): String {
        // Each of these (and each control or format character) becomes a "-".
        val cleaned = StringBuilder()
        name.codePoints().forEach { c ->
            val type = Character.getType(c)
            if (c < 0x80 && "/\\:?*\"<>|".indexOf(c.toChar()) >= 0 ||
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            ) {
                cleaned.append('-')
            } else {
                cleaned.appendCodePoint(c)
            }
        }
        val trimmed = trimSpacesAndDots(cleaned.toString())
        return if (trimmed.isEmpty()) "Offline area" else prefixCharacters(trimmed, 80)
    }

    /** Without white space, line breaks and dots at either end (Foundation's `whitespacesAndNewlines` and "."). */
    private fun trimSpacesAndDots(s: String): String {
        fun trimmable(c: Int): Boolean {
            val type = Character.getType(c)
            return c == '.'.code || c in 0x09..0x0D || c == 0x85 || type == Character.SPACE_SEPARATOR.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        }
        var start = 0
        var end = s.length
        while (start < end && trimmable(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start))
        while (end > start && trimmable(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end))
        return s.substring(start, end)
    }

    /** The first `count` characters as Swift counts them (each letter with its accents, each emoji, as one). */
    private fun prefixCharacters(s: String, count: Int): String {
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(s)
        var end = 0
        for (i in 0 until count) {
            val next = characters.next()
            if (next == BreakIterator.DONE) return s
            end = next
        }
        return s.substring(0, end)
    }
}
