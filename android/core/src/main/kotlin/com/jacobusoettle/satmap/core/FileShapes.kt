package com.jacobusoettle.satmap.core

/** What a KML or GPX file saves offline. Every feature is a dot, a line or an area:
 *  - a point (KML Point, GPX waypoint) is a dot: imagery `Roads.corridorMeters` (15 m) around it;
 *  - a KML Polygon is an area: imagery inside its outer ring (holes too), nothing around it;
 *  - a KML LineString or LinearRing, or a GPX track (its segments joined in order) or route, is a line: imagery 15 m
 *    each side of it; but it is an area (closed if it isn't) when it has at least 3 distinct points and
 *    - the file marks it as an area (`mark`), or
 *    - (KML only) it is closed (a LinearRing, or first and last points within `closedMeters`) and not marked as a road.
 *    A closed GPX track or route stays a line: a drive that ends where it started must not save everything inside it.
 *  Nothing between the parts is saved, however far apart they are. With "Only along roads" (when the file has areas),
 *  roads are looked up inside the areas only and imagery is saved 15 m each side of them, and still 15 m around every
 *  line and dot. Elevation is saved for all of `downloadShape` either way. */
data class FileShapes(
    val areas: List<List<GeoPoint>> = emptyList(),
    val lines: List<List<GeoPoint>> = emptyList(),
    val dots: List<GeoPoint> = emptyList(),
) {
    /** Whether "Only along roads" applies (roads are only looked up inside areas). */
    val hasAreas: Boolean
        get() = areas.isNotEmpty()

    /** The saved area: inside every area, and `Roads.corridorMeters` around every line and dot. Elevation always covers
     *  all of it; imagery too, unless only along roads (`roadsToSave`). */
    val downloadShape: CoverageShape
        get() = CoverageShape(
            polygons = areas, lines = lines, points = dots, bufferMeters = Roads.corridorMeters, polygonBufferMeters = 0.0,
        )

    /** Where "Only along roads" looks roads up (the shipped list, when `RoadPresets.covering` it, else the road server)
     *  and clips them to (`Roads.clip`): the areas only. */
    val roadSearchShape: CoverageShape
        get() = CoverageShape(polygons = areas)

    /** The roads an area saved "Only along roads" keeps (imagery is `Roads.corridor` of them): the roads found in
     *  `roadSearchShape` and clipped to it, then the file's lines, and each dot as a one-point line, so they keep their
     *  15 m whatever the toggle. */
    fun roadsToSave(clippedRoads: List<List<GeoPoint>>): List<List<GeoPoint>> = clippedRoads + lines + dots.map { listOf(it) }

    enum class Kind {
        dot,
        line,
        area,
    }

    /** What a file says a feature is. */
    enum class Mark {
        road,
        area,
    }

    /** Whether a text has a road word and an area word. */
    private class Says(val road: Boolean, val area: Boolean)

    companion object {
        /** The file's dots, lines and areas (Swift's `init(_ document:)`). */
        operator fun invoke(document: KMLDocument): FileShapes {
            val areas = mutableListOf<List<GeoPoint>>()
            val lines = mutableListOf<List<GeoPoint>>()
            val dots = mutableListOf<GeoPoint>()
            for (f in document.features) {
                when (val g = f.geometry) {
                    is KMLGeometry.Point -> dots.add(g.point)
                    is KMLGeometry.Polygon -> areas.add(g.outer)
                    is KMLGeometry.Line -> if (kind(f, document.format) == Kind.area) areas.add(g.points) else lines.add(g.points)
                }
            }
            return FileShapes(areas = areas, lines = lines, dots = dots)
        }

        /** A closed line's first and last points are at most this far apart (KML). */
        const val closedMeters = 5.0

        /** What a feature saves (see above). */
        fun kind(feature: KMLFeature, format: KMLDocument.Format): Kind =
            when (val g = feature.geometry) {
                is KMLGeometry.Point -> Kind.dot
                is KMLGeometry.Polygon -> Kind.area
                is KMLGeometry.Line -> {
                    val pts = g.points
                    if (!hasThreeDistinctPoints(pts)) {
                        Kind.line
                    } else {
                        val marked = mark(feature)
                        if (marked == Mark.area) {
                            Kind.area
                        } else if (format == KMLDocument.Format.kml && marked != Mark.road &&
                            (feature.isRing || Geo.distance(pts[0], pts[pts.size - 1]) <= closedMeters)
                        ) {
                            Kind.area
                        } else {
                            Kind.line
                        }
                    }
                }
            }

        private fun hasThreeDistinctPoints(pts: List<GeoPoint>): Boolean {
            val distinct = mutableListOf<GeoPoint>()
            for (p in pts) {
                if (distinct.any { it.lat == p.lat && it.lon == p.lon }) continue
                distinct.add(p)
                if (distinct.size == 3) return true
            }
            return false
        }

        /** Words that mark a road or track. */
        val roadWords = listOf(
            "road", "roads", "rd", "track", "tracks", "street", "streets", "highway", "route", "routes",
            "path", "paths", "trail", "trails", "firebreak", "firebreaks", "fire break", "f/b", "drive",
            "drivable", "driveway", "lane",
        )
        /** Words that mark an area. */
        val areaWords = listOf(
            "area", "areas", "block", "blocks", "field", "fields", "boundary", "boundaries", "polygon",
            "polygons", "zone", "zones", "paddock", "paddocks", "plot", "plots", "parcel", "parcels",
        )

        /** What the file says a feature is (null: nothing). The first of these that says road or area, and not both,
         *  decides:
         *  1. its data (`KMLFeature.data`, with a GPX type): the key highway or railway makes it a road; landuse,
         *     boundary, building, leisure or natural, or area=yes, an area (a value of "no" says nothing); any other
         *     key's value is read as text;
         *  2. its name and description;
         *  3. the name of each Folder it is in, innermost first, then the Document's (KML).
         *  Text says road or area when it has one of `roadWords` or `areaWords` as a whole word, in any case (see
         *  `textMark`). */
        fun mark(feature: KMLFeature): Mark? {
            decided(dataSays(feature.data))?.let { return it }
            val name = says(feature.name ?: "")
            val description = says(feature.description ?: "")
            decided(Says(name.road || description.road, name.area || description.area))?.let { return it }
            for (folder in feature.folders) {
                decided(says(folder))?.let { return it }
            }
            return null
        }

        /** What a text says: road, area, or null (neither, or both). Words are runs of letters and numbers; a number may
         *  follow a word straight after ("Block12", but not "3rd"), and every other character but "/" counts as a space
         *  ("Fire-break" is "fire break"). */
        fun textMark(text: String): Mark? = decided(says(text))

        private fun decided(s: Says): Mark? = if (s.road == s.area) null else if (s.road) Mark.road else Mark.area

        private fun dataSays(data: List<KMLData>): Says {
            var road = false
            var area = false
            for (d in data) {
                val value = swiftTrimmed(d.value)
                if (value.isEmpty()) continue
                val v = asciiLowercased(value)
                when (asciiLowercased(swiftTrimmed(d.name))) {
                    "highway", "railway" -> if (v != "no") road = true
                    "landuse", "boundary", "building", "leisure", "natural" -> if (v != "no") area = true
                    "area" -> if (v == "yes") area = true
                    else -> {
                        val s = says(value)
                        road = road || s.road
                        area = area || s.area
                    }
                }
            }
            return Says(road, area)
        }

        private fun asciiLowercased(s: String): String = buildString { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

        private val roadCodePoints = roadWords.map { it.codePoints().toArray() }
        private val areaCodePoints = areaWords.map { it.codePoints().toArray() }

        /** Whether the text has a road word and an area word (by code points, as Swift by Unicode scalars). */
        private fun says(text: String): Says {
            val t = IntArray(text.length)
            var n = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in 'A'.code..'Z'.code) {
                    t[n++] = cp + 32
                } else if (cp == '/'.code || isWord(cp)) {
                    t[n++] = cp
                } else if (n == 0 || t[n - 1] != ' '.code) {
                    t[n++] = ' '.code
                }
            }
            fun has(words: List<IntArray>): Boolean {
                for (s in 0 until n) {
                    if (!isWord(t[s]) || (s > 0 && isWord(t[s - 1]))) continue
                    for (w in words) {
                        if (s + w.size > n) continue
                        var same = true
                        for (k in w.indices) {
                            if (t[s + k] != w[k]) {
                                same = false
                                break
                            }
                        }
                        val end = s + w.size
                        if (same && (end == n || !isLetter(t[end]))) return true
                    }
                }
                return false
            }
            return Says(has(roadCodePoints), has(areaCodePoints))
        }

        /** A letter, a mark or a number. */
        private fun isWord(cp: Int): Boolean =
            when (Character.getType(cp).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> isLetter(cp)
            }

        /** A letter or a mark. */
        private fun isLetter(cp: Int): Boolean =
            when (Character.getType(cp).toByte()) {
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
                -> true
                else -> false
            }
    }
}
