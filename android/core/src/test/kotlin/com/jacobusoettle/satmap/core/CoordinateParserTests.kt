package com.jacobusoettle.satmap.core

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoordinateParserTests {
    /** A spot in the Kruger: -25.096893, 31.437121 (25° 5' 48.815" S, 31° 26' 13.636" E). */
    private val lat = -25.096893
    private val lon = 31.437121

    /** Elsewhere: Big Ben, the Statue of Liberty. */
    private val london = GeoPoint(lat = 51.5007, lon = -0.1246)
    private val newYork = GeoPoint(lat = 40.6892, lon = -74.0445)

    /** Where you might be: in the Kruger, Cape Town, Madrid, Rio de Janeiro. */
    private val kruger = GeoPoint(lat = -25.0, lon = 31.5)
    private val capeTown = GeoPoint(lat = -33.9249, lon = 18.4241)
    private val madrid = GeoPoint(lat = 40.4168, lon = -3.7038)
    private val rio = GeoPoint(lat = -22.9068, lon = -43.1729)

    /** Read as `at` (the Kruger spot if not given), within `within` metres, with the format if given. */
    private fun check(text: String, format: String? = null, within: Double = 1.0, near: GeoPoint? = null, at: GeoPoint? = null) {
        val r = CoordinateParser.parse(text, near = near) ?: return fail("Not read: $text")
        val d = Geo.distance(r.point, at ?: GeoPoint(lat = lat, lon = lon))
        assertLessThan(d, within, "$text read as ${r.point} (${r.format}), ${d.toInt()} m off")
        if (format != null) assertEquals(text, format, r.format)
    }

    /** Seconds since `start` (from `System.nanoTime()`). */
    private fun secondsSince(start: Long): Double = (System.nanoTime() - start) / 1e9

    @Test
    fun testDecimalDegrees() {
        for (t in listOf("-25.096893, 31.437121", "-25.096893,31.437121", "-25.096893 31.437121", "-25.096893;31.437121",
                  "25.096893 S, 31.437121 E", "25.096893S 31.437121E", "S25.096893 E31.437121", "S 25.096893° E 31.437121°",
                  "25.096893°S 31.437121°E", "-25.096893° 31.437121°", "(-25.096893, 31.437121)", "−25.096893, +31.437121",
                  "lat -25.096893 lon 31.437121", "Latitude: -25.096893, Longitude: 31.437121",
                  "lon 31.437121 lat -25.096893", "31.437121 E, 25.096893 S", "E 31.437121 S 25.096893",
                  "-25,096893; 31,437121", "-25,096893 31,437121", "25.096893 South 31.437121 East", "25.096893 Suid 31.437121 Oos",
                  "📍 -25.096893, 31.437121", "  -25.096893 ,\n 31.437121  ")) {
            check(t, "decimal degrees")
        }
        // Longitude first, where only the second number can be a latitude.
        assertEquals(GeoPoint(lat = -25.1, lon = 131.5), CoordinateParser.parse("131.5, -25.1")?.point)
    }

    @Test
    fun testDegreesAndMinutes() {
        for (t in listOf("25° 05.81358' S, 31° 26.22726' E", "S 25° 5.81358′ E 31° 26.22726′", "25 05.81358 S 31 26.22726 E",
                  "-25 5.81358, 31 26.22726", "-25° 5.81358' 31° 26.22726'", "S25 05.81358 E031 26.22726",
                  "25°05.81358'S 031°26.22726'E", "25d 5.81358m S 31d 26.22726m E")) {
            check(t, "degrees and decimal minutes")
        }
    }

    @Test
    fun testDegreesMinutesSeconds() {
        for (t in listOf("25°05'48.815\"S 31°26'13.636\"E", "25°05′48.815″S 31°26′13.636″E", "25º 5' 48.815'' S, 31º 26' 13.636'' E",
                  "S 25° 5' 48.815\" E 31° 26' 13.636\"", "25 05 48.815 S 31 26 13.636 E", "-25 05 48.815 31 26 13.636",
                  "-25° 05' 48.815\", 31° 26' 13.636\"", "25°5’48.815”S 31°26’13.636”E", "Lat: 25°05'48.815\"S Long: 31°26'13.636\"E",
                  "25 deg 5 min 48.815 sec S, 31 deg 26 min 13.636 sec E")) {
            check(t, "degrees, minutes and seconds")
        }
    }

    @Test
    fun testGPSAndLinks() {
        check("2505.81358,S,03126.22726,E", "GPS (NMEA)")
        check("\$GPGGA,123519,2505.81358,S,03126.22726,E,1,08,0.9,545.4,M,46.9,M,,*47".split(",").subList(2, 6).joinToString(","), "GPS (NMEA)")
        check("https://www.google.com/maps/place/-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/@-25.096893,31.437121,15z", "map link")
        check("https://www.google.com/maps/place/Somewhere/@-25.1,31.4,17z/data=!3m1!4b1!4m6!3m5!3d-25.096893!4d31.437121", "map link")
        check("https://maps.google.com/?q=-25.096893,31.437121", "map link")
        check("https://maps.apple.com/?ll=-25.096893,31.437121&q=Pin", "map link")
        check("https://www.openstreetmap.org/?mlat=-25.096893&mlon=31.437121#map=16/-25.1/31.4", "map link")
        check("https://www.openstreetmap.org/#map=18/-25.096893/31.437121", "map link")
        check("https://www.bing.com/maps?cp=-25.096893~31.437121&lvl=16", "map link")
        check("geo:-25.096893,31.437121?z=17", "geo: link")
    }

    @Test
    fun testUTMAndMGRS() {
        // Reference values from pyproj and the mgrs library.
        check("36J 342401.64 7223411.52", "UTM")
        check("36 J 342401 7223411", "UTM", within = 2.0)
        check("UTM 36J 342401mE 7223411mN", "UTM", within = 2.0)
        check("Zone 36J, E 342401, N 7223411", "UTM", within = 2.0)
        check("36J N 7223411 E 342401", "UTM", within = 2.0)
        check("36S 342401 7223411", "UTM", within = 2.0) // S as "south", as some tools write it
        check("36JUT4240123411", "MGRS", within = 2.0)
        check("36J UT 42401 23411", "MGRS", within = 2.0)
        check("MGRS 36J UT 4240 2341", "MGRS", within = 15.0)
        // Elsewhere: Stellenbosch, London, New York, Tromsø, near the equator.
        val others: List<Triple<String, Double, Double>> = listOf(
            Triple("34H 307828.05 6244957.75", -33.918292, 18.921204), Triple("30U 699567.54 5709427.56", 51.5007, -0.1246),
            Triple("18T 580735.87 4504695.17", 40.6892, -74.0445), Triple("34W 420682.24 7728124.9", 69.6496, 18.956),
            Triple("17N 811683.01 55331.54", 0.5, -78.2), Triple("34HCH0782844957", -33.918292, 18.921204),
            Triple("30UXC9956709427", 51.5007, -0.1246), Triple("18TWL8073504695", 40.6892, -74.0445),
            Triple("34WDC2068228124", 69.6496, 18.956), Triple("17NRA1168355331", 0.5, -78.2),
        )
        for ((text, la, lo) in others) {
            val r = CoordinateParser.parse(text)
            assertNotNull("Not read: $text", r)
            assertLessThan(Geo.distance(r!!.point, GeoPoint(lat = la, lon = lo)), 2.0, text)
        }
    }

    @Test
    fun testPlusCodes() {
        // Reference values from Google's openlocationcode library (the centre of the code's area, within ~7 m).
        check("5G6HWC3P+6R", "plus code", within = 8.0)
        check("5G6HWC3P+6RX", "plus code", within = 3.0)
        check("WC3P+6R", "plus code (near you)", within = 8.0, near = GeoPoint(lat = -25.0, lon = 31.54))
        assertNull(CoordinateParser.parse("WC3P+6R")) // a short code needs your position
        val s = CoordinateParser.parse("4FRW3WJC+MF") ?: return fail("Not read")
        assertLessThan(Geo.distance(s.point, GeoPoint(lat = -33.9183125, lon = 18.9211875)), 1.0)
    }

    @Test
    fun testNotPositions() {
        for (t in listOf("", "hello", "25", "123.4, 456.7", "25° 75' S, 31° 26' E", "91, 181", "25 S 31 S", "1 2 3", "https://example.com/x")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    // Inputs found misread or not read by a sweep of real-world sources, by family.

    @Test
    fun testMoreLinks() {
        // Apple Maps (iOS 18 and its documented examples): coordinate=, after an address that has numbers in it.
        check("https://maps.apple.com/place?address=Apple%20Inc.,%201%20Apple%20Park%20Way,%20Cupertino,%20CA%2095014,"
              + "%20United%20States&coordinate=37.334859,-122.009040&name=Apple%20Park&place-id=I7C250D2CDCB364A&map=explore",
              "map link", at = GeoPoint(lat = 37.334859, lon = -122.009040))
        check("https://maps.apple.com/place?coordinate=-25.096893,31.437121&name=Marked%20Location", "map link")
        check("https://maps.apple.com/?coordinate=-25.096893,31.437121", "map link")
        // Android geo: links: the q= pin, not the map centre or the geo:0,0 placeholder.
        check("geo:0,0?q=-25.096893,31.437121(Dropped pin)", "geo: link")
        check("geo:0,0?q=-25.096893%2C31.437121%28Camp%29", "geo: link")
        check("geo:50.123456,-120.123456?q=40.7127400,-74.0059965", "geo: link", at = GeoPoint(lat = 40.71274, lon = -74.0059965))
        assertNull(CoordinateParser.parse("geo:0,0?q=Skukuza")) // a place by name, not 0,0
        check("google.navigation:q=-25.096893,31.437121&mode=w", "map link")
        // Google Maps: the place or search in the path, not the map centre after /@; a DMS place; where directions go.
        check("https://www.google.com/maps/place/-25.096893,31.437121/@-25.0968930,31.4345295,17z", "map link")
        check("https://www.google.com/maps/search/-25.096893,+31.437121/@-25.0968930,31.4345295,17z?entry=ttu", "map link")
        check("https://www.google.com/maps/place/25%C2%B005'48.8%22S+31%C2%B026'13.6%22E/@-25.0968930,31.4345295,17z/data=!3m1!1e3",
              "map link", within = 2.0)
        check("https://www.google.com/maps/dir//-25.096893,31.437121/@-25.1105930,31.3998210,13z?entry=ttu", "map link")
        check("https://www.google.com/maps?saddr=-24.976893,31.517121&daddr=-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/dir/Hermannstra%C3%9Fe+1,+12049+Berlin/Reuterstra%C3%9Fe+1,+Berlin/@52.4844406,13.4217121,16z"
              + "/data=!4m14!4m13!1m5!1m1!1s0x0:0x1!2m2!1d13.4236883!2d52.4858222!1m5!1m1!1s0x0:0x2!2m2!1d13.4300356!2d52.4807739!3e2",
              "map link", at = GeoPoint(lat = 52.4807739, lon = 13.4300356)) // the last stop's position in the data
        check("https://www.google.com/maps/dir//The+Station,+1+Mends+St,+South+Perth+WA+6151/@-31.9614112,115.8523381,14z",
              "map link (centre of the route)", at = GeoPoint(lat = -31.9614112, lon = 115.8523381))
        check("https://www.google.com/maps/place/5G6HWC3P%2B6R", "plus code", within = 8.0)
        check("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=-25.096893,31.437121", "map link")
        check("https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3600.5!2d31.437121!3d-25.096893!2m3!1f0!2f0!3f0!3m2!1i1024"
              + "!2i768!4f13.1!3m3!1m2!1s0x0%3A0x0!2z!5e1!3m2!1sen!2sza!4v1730000000000!5m2!1sen!2sza", "map link")
        // OsmAnd.
        check("https://osmand.net/map?pin=-25.09689,31.43712#15/-25.09689/31.43712", "map link")
        check("https://osmand.net/go?lat=-25.096893&lon=31.437121&z=15", "map link")
        // A short link needs the internet to follow.
        assertNull(CoordinateParser.parse("https://maps.app.goo.gl/q8w1k9mH3qFz7Dxq8"))
    }

    @Test
    fun testMoreFieldNMEAAndISO6709() {
        // Military and aviation degrees, minutes and seconds with dashes (not minus signs).
        check("25-05-48.8S 031-26-13.6E", "degrees, minutes and seconds", within = 2.0)
        check("S25-05-48.8 E031-26-13.6", "degrees, minutes and seconds", within = 2.0)
        check("40-41-21.1200N 074-02-40.2000W", "degrees, minutes and seconds", at = newYork)
        // NMEA and aviation within 1° or 2° of the equator or Greenwich (leading zeros).
        check("5130.0420,N,00007.4760,W", "GPS (NMEA)", at = london)
        check("0010.8392,S,07828.0700,W", "GPS (NMEA)", at = GeoPoint(lat = -0.180653, lon = -78.467834))
        check("0536.22N 00011.22W", "GPS (NMEA)", at = GeoPoint(lat = 5.603667, lon = -0.187))
        // Whole NMEA sentences.
        check("\$GPGGA,123519,2505.8136,S,03126.2273,E,1,08,0.9,545.4,M,46.9,M,,*47", "GPS (NMEA)")
        check("\$GPRMC,123519,A,2505.8136,S,03126.2273,E,022.4,084.4,230394,003.1,W*6A", "GPS (NMEA)")
        // ISO 6709: degrees and minutes, from the time zone database, and with an altitude (iPhone video).
        check("-0117.5240+03649.3168/", "ISO 6709", at = GeoPoint(lat = -1.292066, lon = 36.821946))
        check("-0117+03649", "ISO 6709", at = GeoPoint(lat = -1.283333, lon = 36.816667))
        check("+40.6892-074.0445+545.400/", "ISO 6709", at = newYork)
        // exiftool's video GPS Coordinates, with the altitude after them.
        check("25 deg 5' 48.81\" S, 31 deg 26' 13.64\" E, 545.4 m Above Sea Level", "degrees, minutes and seconds")
    }

    @Test
    fun testMoreGrids() {
        // UTM with a space between the zone and N (band or hemisphere), or the hemisphere in words.
        check("30 N 699568 5709428", "UTM", within = 2.0, at = london)
        check("48 N 372280 142646", "UTM", within = 2.0, at = GeoPoint(lat = 1.2903, lon = 103.852001))
        check("UTM 36 South 342402 7223412", "UTM", within = 2.0)
        // MGRS as one run of digits (10 m), and with a label or a note.
        check("36JUT42402341", "MGRS", within = 15.0)
        check("MGRS: 36J UT 42401 23411", "MGRS", within = 2.0)
        check("36J UT 42401 23411 (MGRS)", "MGRS", within = 2.0)
    }

    @Test
    fun testDataSnippets() {
        // A heading gives the order of the values: "Lat/Long:", "Long/Lat:", a spreadsheet's header row.
        for (t in listOf("Lat/Long: -25.096893, 31.437121", "Lat Long (-25.096893, 31.437121)", "lat/lng: (-25.096893,31.437121)",
                  "Latitude\tLongitude\n-25.096893\t31.437121\n", "Long/Lat: 31.437121, -25.096893",
                  "X: 31.437121, Y: -25.096893")) {
            check(t, "decimal degrees")
        }
        // Quoted values (CSV, JSON, GPX), not seconds; altitude, accuracy and the like left out.
        for (t in listOf("\"-25.096893\",\"31.437121\"", "\"-25,096893\",\"31,437121\"", "\"lat\":\"-25.0968930\",\"lon\":\"31.4371210\"",
                  "{\"lat\":\"-25.0968930\",\"lon\":\"31.4371210\"}", "<wpt lat=\"-25.096893\" lon=\"31.437121\">",
                  "{\"location\": {\"lat\": -25.096893, \"lng\": 31.437121}, \"accuracy\": 20}",
                  "Latitude: -25.096893\nLongitude: 31.437121\nAltitude: 545.4 m",
                  "Latitude: -25.096893\nLongitude: 31.437121\nAltitude: 312 m\nAccuracy: 5 m",
                  "CLLocationCoordinate2D(latitude: -25.096893, longitude: 31.437121)")) {
            check(t, "decimal degrees")
        }
        // Longitude first: GeoJSON, KML, WKT.
        check("{\"type\":\"Point\",\"coordinates\":[31.437121,-25.096893]}", "GeoJSON")
        check("{\"type\": \"Point\", \"coordinates\": [31.437121, -25.096893, 312.0]}", "GeoJSON")
        check("<coordinates>31.437121,-25.096893,0</coordinates>", "KML")
        check("<Point><coordinates>31.437121,-25.096893,312</coordinates></Point>", "KML")
        check(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n" +
                "<Placemark>\n" +
                "\t<name>Untitled Placemark</name>\n" +
                "\t<LookAt>\n" +
                "\t\t<longitude>31.43712100</longitude>\n" +
                "\t\t<latitude>-25.09689300</latitude>\n" +
                "\t\t<altitude>0</altitude>\n" +
                "\t\t<range>1000</range>\n" +
                "\t</LookAt>\n" +
                "\t<Point>\n" +
                "\t\t<gx:drawOrder>1</gx:drawOrder>\n" +
                "\t\t<coordinates>31.437121,-25.096893,0</coordinates>\n" +
                "\t</Point>\n" +
                "</Placemark>\n" +
                "</kml>",
            "KML",
        )
        check("Point (31.437121 -25.096893)", "WKT")
        check("POINT (31.437121 -25.096893)", "WKT")
        // lon, lat, alt or lat, lon, alt: it can't be told which.
        assertNull(CoordinateParser.parse("31.437121,-25.096893,0"))
    }

    @Test
    fun testPositionInText() {
        check("25°05'48.8\"S 31°26'13.6\"E\nhttps://maps.app.goo.gl/q8w1k9mH3qFz7Dxq8", "degrees, minutes and seconds", within = 2.0)
        for (t in listOf("Camp site 14: -25.096893, 31.437121", "Saw leopard at 06:45 -25.096893, 31.437121",
                  "Lions on H4-1 at -25.096893, 31.437121", "[12:31, 27/10/2026] Jaco: -25.096893, 31.437121",
                  "27/10/2026, 12:31 - Jaco: -25.096893, 31.437121")) {
            check(t, "decimal degrees")
        }
        check("Spot 1: 51.5007, -0.1246", "decimal degrees", at = london) // not 1° 51.5007'
        // Wikipedia: the same place twice (the more precise of the two is taken).
        check("51°30′02.5″N 0°07′28.6″W / 51.500694°N 0.124611°W", "decimal degrees",
              at = GeoPoint(lat = 51.500694, lon = -0.124611))
        // Two different places: it isn't clear which is meant.
        assertNull(CoordinateParser.parse("Camp at -25.096893, 31.437121; sighting at -25.196893, 31.537121"))
    }

    @Test
    fun testTheEvidenceDecides() {
        // A lone O: east (Afrikaans Oos, German Ost) unless your position says west (French Ouest, Spanish Oeste).
        check("25,096893° S, 31,437121° O", "decimal degrees (O = east)")
        check("40,68920° N, 74,04450° O", "decimal degrees (O = west)", near = GeoPoint(lat = 45.5017, lon = -73.5673), at = newYork)
        check("51,50070° N, 0,12460° O", "decimal degrees (O = west)", near = london, at = london)
        // Without your position or a word that says which language, east, as the format says.
        check("40,68920° N, 74,04450° O", "decimal degrees (O = east)", at = GeoPoint(lat = 40.6892, lon = 74.0445))
        // Another word in the text says which language it is: Dutch Z (zuid) and German Süd with Oost/Ost, Spanish Sur
        // and French sud with Oeste/Ouest.
        check("25,096893 Z 31,437121 O", "decimal degrees (O = east)")
        check("33° 51′ Süd, 151° 12′ O", "degrees and decimal minutes (O = east)", at = GeoPoint(lat = -33.85, lon = 151.2))
        check("34° 36' Sur, 58° 22' O", "degrees and decimal minutes (O = west)", at = GeoPoint(lat = -34.6, lon = -58.366667))
        check("22° 54′ 30″ sud, 43° 11′ 47″ O", "degrees, minutes and seconds (O = west)",
              at = GeoPoint(lat = -22.908333, lon = -43.196389))
        // A minus sign can't be east.
        check("-74,0445 O, 40,6892 N", "decimal degrees (O = west)", at = newYork)
        // A short plus code with a place name was shortened against that place, which can't be looked up offline: near
        // you it could be a square up to 1° from it (110 km north of it at Letaba, 1,600 km away near Cape Town).
        for (near in listOf(GeoPoint(lat = -33.9249, lon = 18.4241), GeoPoint(lat = -23.854, lon = 31.575), GeoPoint(lat = -24.99, lon = 31.59))) {
            assertNull(CoordinateParser.parse("WC3P+6R Skukuza, South Africa", near = near))
        }
    }

    // Inputs checked again after that sweep, by family.

    @Test
    fun testHalvesWithDifferentFields() {
        // Seconds in one half and not the other, marked by a degree sign, a letter in front or a minus sign.
        check("40° 41' 21\" N 74° 2 W", "degrees, minutes and seconds", at = GeoPoint(lat = 40.689167, lon = -74.033333))
        check("51° 30' 26\" N 0° 7 W", "degrees, minutes and seconds", at = GeoPoint(lat = 51.507222, lon = -0.116667))
        check("25.0969° S 31° 26 E", "degrees and decimal minutes", at = GeoPoint(lat = -25.0969, lon = 31.433333))
        check("S 25° E 31° 26", "degrees and decimal minutes", at = GeoPoint(lat = -25.0, lon = 31.433333))
        check("25°05'48.8\"S 31°26 E", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.433333))
        check("25.0969 S 31 26 E", "degrees and decimal minutes", at = GeoPoint(lat = -25.0969, lon = 31.433333))
        check("S25 05 48.8 E31 26.2273", "degrees, minutes and seconds", within = 2.0)
        check("-25 05 48.8, 31.437121", "degrees, minutes and seconds", within = 2.0)
        check("51.5007, -0 07.476", "degrees and decimal minutes", at = london)
        // Without such a mark a camp number before it could be the first field: 1° 25' 05.8" S.
        assertNull(CoordinateParser.parse("Camp 1 25 05.8 S, 31 26.2 E"))
        // A number right after the last letter could be minutes, but not after a decimal.
        assertNull(CoordinateParser.parse("25 S 31 E 3"))
        check("25.096893 S 31.437121 E 2026-10-27", "decimal degrees")
    }

    @Test
    fun testListsOfNumbers() {
        // Longitude, latitude and altitude (or latitude, longitude and altitude): it can't be told which.
        for (t in listOf("31.437121 -25.096893 545.4", "31.437121 / -25.096893 / 545", "-74.0445 40.6892 10", "31.437121\t-25.096893\t312",
                  "1730025900 31.437121 -25.096893 545.4", "Lap times: 12.345 13.456 14.567", "Price list: 12.500 31.750 and 45.250")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        // A number after it that isn't joined the same way, a whole number before it, or a measurement isn't part of a list.
        check("-25.096893, 31.437121 3 cubs", "decimal degrees")
        check("Camp 14 -25.096893 31.437121", "decimal degrees")
        check("Lions at -25.096893, 31.437121 (±5.000 m)", "decimal degrees")
    }

    @Test
    fun testMessages() {
        // Apostrophes: "it's", "Jaco's" and Afrikaans "'n" aren't directions.
        check("40.6892, -74.0445 is where it's at", "decimal degrees", at = newYork)
        check("Jaco's sighting: 51.5007, -0.1246", "decimal degrees", at = london)
        check("It's here: 40.6892, -74.0445", "decimal degrees", at = newYork)
        check("-25.096893, 31.437121 at Jaco's camp", "decimal degrees")
        check("-25.096893, 31.437121 'n groot mannetjie", "decimal degrees")
        // Words in a sentence aren't labels or directions unless a number is next to them.
        check("Long day! Lions at -25.096893, 31.437121", "decimal degrees")
        check("Heading west, lions at -25.096893, 31.437121", "decimal degrees")
        check("Lions near the east gate -25.096893, 31.437121", "decimal degrees")
        check("West side of the river -25.096893 31.437121", "decimal degrees")
        check("Leeus by die oos hek -25.096893, 31.437121", "decimal degrees")
        check("Lions at 25.096893 South 31.437121 East near the camp", "decimal degrees")
        // Times, dates, counts and measurements among words aren't positions; "GPS:" and the like only say one follows.
        for (t in listOf("Gates open 05:30 close 18:00", "Departed 08:15, arrived 09:40", "25 Oct 2026", "12 lions 3 cubs", "Version 2.5.1",
                  "Alt 1450 m, temp 25.5, wind 31.4", "Arrive 7 Dec 25", "4 Mar 26", "25 Dec 31", "abc 25 def 31")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        check("GPS: -25.1, 31.4", "decimal degrees", within = 20_000.0)
        // "#27/10/26" in a message isn't a map link.
        check("Ref #27/10/26: lions at -25.096893, 31.437121", "decimal degrees")
    }

    @Test
    fun testOtherLanguages() {
        for (t in listOf("25.096893 Sur 31.437121 Este", "25.096893 Süd 31.437121 Ost", "25.096893 Sud 31.437121 Est", "25.096893 Sth 31.437121 E",
                  "25,096893 Zuid 31,437121 Oost")) {
            check(t, "decimal degrees")
        }
        check("40,6892 N 74,0445 Oeste", "decimal degrees", at = newYork)
        check("33° 51′ 35″ sud, 151° 12′ 40″ est", "degrees, minutes and seconds", at = GeoPoint(lat = -33.859722, lon = 151.211111))
        check("22° 54′ 30″ sud, 43° 11′ 47″ ouest", "degrees, minutes and seconds", at = GeoPoint(lat = -22.908333, lon = -43.196389))
        check("34° 36' Sur, 58° 22' Oeste", "degrees and decimal minutes", at = GeoPoint(lat = -34.6, lon = -58.366667))
        check("33° 51′ ZB, 151° 12′ OL", "degrees and decimal minutes", at = GeoPoint(lat = -33.85, lon = 151.2))
        check("Länge: 13,405 Breite: 52,52", "decimal degrees", at = GeoPoint(lat = 52.52, lon = 13.405))
        check("Longitud: -3,7038 Latitud: 40,4168", "decimal degrees", at = GeoPoint(lat = 40.4168, lon = -3.7038))
    }

    @Test
    fun testDashesAndUnitLetters() {
        // A dash after a half that ends in its letter separates the halves.
        check("25.096893 S - 31.437121 E", "decimal degrees")
        check("25°05'48.8\"S - 31°26'13.6\"E", "degrees, minutes and seconds", within = 2.0)
        check("25.096893° S \u2013 31.437121° E", "decimal degrees")
        // A minus sign with N or E says two things.
        assertNull(CoordinateParser.parse("-25.096893, -31.437121 E"))
        // d, m and s for degrees, minutes and seconds.
        check("40d41m21sN 74d02m40sW", "degrees, minutes and seconds", at = GeoPoint(lat = 40.689167, lon = -74.044444))
        check("25d05m48.815sS 31d26m13.636sE", "degrees, minutes and seconds")
        check("25°05m48.815sS 31°26m13.636sE", "degrees, minutes and seconds")
    }

    @Test
    fun testLongitudeFirstAndSeveralPlaces() {
        check("{ type: 'Point', coordinates: [-77.032, 38.913] }", "GeoJSON", at = GeoPoint(lat = 38.913, lon = -77.032))
        check("\"coordinates\": [31.437121, -25.096893]", "GeoJSON")
        check("{\"type\":\"MultiPoint\",\"coordinates\":[[31.437121,-25.096893]]}", "GeoJSON")
        check("MULTIPOINT((31.437121 -25.096893))", "WKT")
        check("point(31.437121 -25.096893)", "WKT")
        check("point z (31.437121 -25.096893 312)", "WKT")
        check("ST_SetSRID(ST_MakePoint(31.437121, -25.096893), 4326)", "decimal degrees")
        check("mapboxgl.LngLat(31.437121, -25.096893)", "decimal degrees")
        check("<gx:coord>31.437121 -25.096893 545.4</gx:coord>", "KML")
        // "point" in a sentence isn't WKT.
        check("Meeting point (-25.096893 31.437121)", "decimal degrees")
        // Two different places, or a line: it isn't clear which is meant.
        for (t in listOf("<Placemark><name>Skukuza</name><Point><coordinates>31.5969,-24.9947,0</coordinates></Point></Placemark>"
                    + "<Placemark><name>Lower Sabie</name><Point><coordinates>31.9150,-25.1197,0</coordinates></Point></Placemark>",
                  "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[31.5969,-24.9947]}},"
                    + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[31.9150,-25.1197]}}]}",
                  "{\"type\":\"LineString\",\"coordinates\":[[31.5969,-24.9947],[31.9150,-25.1197]]}",
                  "POINT (31.5969 -24.9947), POINT (31.9150 -25.1197)", "2459.682,S,03135.814,E 2507.182,S,03154.900,E")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    @Test
    fun testLabelledValuesWithOthers() {
        for (t in listOf("<wpt lat=\"-25.096893\" lon=\"31.437121\"><ele>545.2</ele><time>2026-10-27T10:45:00Z</time><name>Camp</name></wpt>",
                  "<trkpt lat=\"-25.096893\" lon=\"31.437121\"><ele>312.4</ele><time>2026-10-27T12:31:00Z</time></trkpt>",
                  "<wpt lat=\"-25.096893\" lon=\"31.437121\"><name>Camp 14</name></wpt>",
                  "{\"lat\": -25.096893, \"lng\": 31.437121, \"zoom\": 12}", "{\"name\":\"Camp 14\",\"lat\":-25.096893,\"lon\":31.437121}",
                  "<geo:lat>-25.096893</geo:lat> <geo:long>31.437121</geo:long>", "geo:lat=-25.096893 geo:lon=31.437121")) {
            check(t, "decimal degrees")
        }
        // A track: different places.
        assertNull(CoordinateParser.parse("<trkpt lat=\"-25.096893\" lon=\"31.437121\"/><trkpt lat=\"-25.196893\" lon=\"31.537121\"/>"))
    }

    @Test
    fun testEvenMoreLinks() {
        for (t in listOf("https://www.mapquest.com/latlng/-25.096893,31.437121", "https://share.here.com/l/-25.096893,31.437121,Lions",
                  "https://maps.google.com/?q=Lions@-25.096893,31.437121", "https://example.com/map?lon=31.437121&lat=-25.096893",
                  "https://www.waze.com/live-map/directions?to=ll.-25.096893%2C31.437121",
                  "https://yandex.com/maps/?ll=31.437121%2C-25.096893&z=15",
                  "https://www.arcgis.com/home/webmap/viewer.html?center=31.437121,-25.096893&level=12")) {
            check(t, "map link")
        }
        check("geo:-25.096893 31.437121", "geo: link")
        check("https://geohack.toolforge.org/geohack.php?pagename=Skukuza&params=24_59_46_S_31_35_28_E_region:ZA_type:city",
              "map link", at = GeoPoint(lat = -24.996111, lon = 31.591111))
        assertNull(CoordinateParser.parse("https://example.com/?bbox=31.4,-25.2,31.5,-25.0"))
    }

    @Test
    fun testGridReferencesInText() {
        // The zone right before the easting and northing, not a number and letter elsewhere.
        check("Waypoint 12 N: 36J 342401 7223411", "UTM", within = 2.0)
        check("R40 N to 36J 342401 7223411", "UTM", within = 2.0)
    }

    @Test
    fun testWikipediaAtMinutePrecision() {
        // The decimal form is rounded to 3 places: up to about 55 m from the minutes.
        check("25°03′S 31°08′E / 25.050°S 31.133°E", "decimal degrees", at = GeoPoint(lat = -25.05, lon = 31.133))
        check("Coordinates: 25°03′S 31°08′E\uFEFF / \uFEFF25.050°S 31.133°E", "decimal degrees", at = GeoPoint(lat = -25.05, lon = 31.133))
        check("24°59′S 31°35′E / 24.983°S 31.583°E / -24.983; 31.583", "decimal degrees", at = GeoPoint(lat = -24.983, lon = 31.583))
        // Different places (more than a minute apart) still aren't read.
        assertNull(CoordinateParser.parse("25°03′S 31°08′E / 25.150°S 31.133°E"))
    }

    @Test
    fun testDigitsInOtherScriptsAndLongText() {
        check("+٤٠٫٦٨٩٢-٠٧٤٫٠٤٤٥/", "ISO 6709", at = newYork)
        check("+４０.６８９２-０７４.０４４５/", "ISO 6709", at = newYork)
        check("-٢٥٫٠٩٦٨٩٣، ٣١٫٤٣٧١٢١", "decimal degrees")
        check("+٤٠-٠٧٤", "ISO 6709", at = GeoPoint(lat = 40.0, lon = -74.0))
        // A long paste is read quickly (it is read as you type).
        val start = System.nanoTime()
        assertNull(CoordinateParser.parse("1".repeat(20_000)))
        check("Ref " + "7".repeat(5_000) + " at -25.096893, 31.437121", "decimal degrees")
        assertLessThan(secondsSince(start), 2.0)
    }

    // Inputs checked a third time, by family.

    @Test
    fun testLabelsJoinedByAnd() {
        // Two labels joined by "and" are a heading, as "Lat/Long:" is: they don't label the first value.
        for (t in listOf("Lat & Long: -25.096893, 31.437121", "Lat and Long: -25.096893, 31.437121",
                  "Latitude and longitude: -25.096893, 31.437121", "lat & lng -25.096893, 31.437121",
                  "Breite und Länge: -25,096893; 31,437121", "Latitud y longitud: -25.096893, 31.437121",
                  "Breedte en lengte: -25,096893; 31,437121", "Longitude and latitude: 31.437121, -25.096893")) {
            check(t, "decimal degrees")
        }
        check("Lat & Long: -24.996931, 31.592312", "decimal degrees", near = kruger, at = GeoPoint(lat = -24.996931, lon = 31.592312))
    }

    @Test
    fun testFieldsOfOneHalf() {
        // A full stop at the end of a sentence isn't a decimal point: the commas are.
        check("-24,99 31,59.", "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
        check("Breite 52,52, Länge 13,41.", "decimal degrees", at = GeoPoint(lat = 52.52, lon = 13.41))
        // Numbers with a comma, semicolon or slash between them, or a second point, aren't the fields of one half; a dash
        // or colon keeps them in one (a time, a score).
        for (t in listOf("2026/10/27", "27/10/26", "10/27/26", "2026/1/5", "-24, 31, 45", "27.10.2026", "2026.10.27", "27.10.26", "45-30",
                  "12:30", "23:59", "00:00")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        // Unless the half's letter comes after them (CSV columns); fields with colons.
        check("25,05,48.815,S,31,26,13.636,E", "degrees, minutes and seconds")
        check("25:05:48.815 S 31:26:13.636 E", "degrees, minutes and seconds")
        // A dash before a number that can't be minutes is a minus sign.
        check("40-74.0445", "decimal degrees", at = GeoPoint(lat = 40.0, lon = -74.0445))
        check("-33-70", "decimal degrees", at = GeoPoint(lat = -33.0, lon = -70.0))
        // A date after the position is other text.
        check("25.096893 S 31.437121 E 27.10.2026", "decimal degrees")
    }

    @Test
    fun testSouthOrSeconds() {
        // An S after minutes is south when the other half starts with its letter; "s" for seconds is written right after
        // the number (see testDashesAndUnitLetters).
        check("25°05'48.8 S E 031°26'13.6", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
        check("34°36'12 S W 58°22'54", "degrees, minutes and seconds", at = GeoPoint(lat = -34.603333, lon = -58.381667))
        check("25 05' 48 S E 31 26' 13", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096667, lon = 31.436944))
        check("40°41'21sN 74°02'40sW", "degrees, minutes and seconds", at = GeoPoint(lat = 40.689167, lon = -74.044444))
        // Degrees, minutes and seconds among words, with a minus sign: not a time or a count.
        check("We're at -25 05 48, 31 26 13", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096667, lon = 31.436944))
    }

    @Test
    fun testLoneOWithMoreEvidence() {
        // A Spanish, Portuguese or French word says west, a German or Afrikaans one east, wherever you are.
        check("Coordenadas: 23° 32′ 51″ S, 46° 38′ 10″ O", "degrees, minutes and seconds (O = west)", near = capeTown,
              at = GeoPoint(lat = -23.5475, lon = -46.636111))
        check("Coordenadas: 22,9068 S 43,1729 O", "decimal degrees (O = west)", at = rio)
        check("Estamos en 40,4168 N 3,7038 O. Ven pronto.", "decimal degrees (O = west)", at = madrid)
        check("Coordonnées : 47,2184 N 1,5536 O", "decimal degrees (O = west)", at = GeoPoint(lat = 47.2184, lon = -1.5536))
        check("Koordinaten: 25,096893 S 31,437121 O", "decimal degrees (O = east)", near = rio)
        // Without one, the reading nearer to you: from Madrid, New York rather than 74° east.
        check("40,68920° N, 74,04450° O", "decimal degrees (O = west)", near = madrid, at = newYork)
        // The place written twice: the reading of the O that agrees with the other.
        check("23°32′51″S 46°38′10″O / -23.5475, -46.6361", "decimal degrees", at = GeoPoint(lat = -23.5475, lon = -46.6361))
        check("40°25′08″N 3°41′31″O / 40.418888888889, -3.6919444444444", "decimal degrees",
              at = GeoPoint(lat = 40.418889, lon = -3.691944))
        // The O of a word ("Posição", "Estação", "año"), or one that reads the same either way, isn't a direction.
        for (near in listOf(null, kruger)) {
            check("Posição: -25.096893, 31.437121", "decimal degrees", near = near)
        }
        check("Localização: -22.9068, -43.1729", "decimal degrees", near = rio, at = rio)
        check("Estação -23.5505, -46.6333", "decimal degrees", at = GeoPoint(lat = -23.5505, lon = -46.6333))
        check("Sitio del año -25.096893, 31.437121", "decimal degrees")
        check("O ponto fica em -25.096893, 31.437121", "decimal degrees")
        check("-25.096893, 31.437121 o ponto de encontro", "decimal degrees", near = madrid)
        // The words of the whole text count, also when the position is read from a part of it.
        check("Sichtung um 12:30 bei 25,096893 S 31,437121 O", "decimal degrees (O = east)", near = madrid)
    }

    @Test
    fun testLabelledValuesWithWordsAfterThem() {
        // A direction or unit in words or brackets after a labelled value: read with it, not as a plain value.
        for (t in listOf("Latitude: 25.096893 South, Longitude: 31.437121 East", "Lat 25.096893 Suid, Lon 31.437121 Oos",
                  "lat 25.096893 south lon 31.437121 east", "Latitude: 25.096893 (S)\nLongitude: 31.437121 (E)",
                  "Latitude: 25.096893 deg S, Longitude: 31.437121 deg E")) {
            check(t, "decimal degrees")
        }
        // exiftool's default output, and the like.
        for (t in listOf("GPS Latitude                    : 25 deg 5' 48.81\" S\nGPS Longitude                   : 31 deg 26' 13.64\" E",
                  "Latitude: 25 deg 5 min 48.81 sec S\nLongitude: 31 deg 26 min 13.64 sec E",
                  "Latitude: 25 degrees 5 minutes 48.81 seconds South, Longitude: 31 degrees 26 minutes 13.64 seconds East",
                  "Breite: 25 Grad 5' 48.81\" S, Länge: 31 Grad 26' 13.64\" O")) {
            check(t, within = 2.0)
        }
        check("Lat: 25 deg 5.8135 min S\nLon: 31 deg 26.2273 min E", "degrees and decimal minutes", within = 2.0)
        // Other words after them are still read as before.
        for (t in listOf("Lat: -25.096893 Lon: 31.437121 at 12:30", "Camp 14: Lat: -25.096893 Lon: 31.437121 (25+)")) {
            check(t, "decimal degrees")
        }
        // Length and width in German and Afrikaans.
        assertNull(CoordinateParser.parse("Länge: 120 cm, Breite: 60 cm"))
        assertNull(CoordinateParser.parse("Lengte 2,10 m breedte 0,90 m"))
    }

    @Test
    fun testNumbersAroundAPosition() {
        // A stop or camp number before letters that go before the numbers.
        check("Stop 3 S25.0969 E31.4371", "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        check("Gate 4 S 25.096893 E 31.437121", "decimal degrees")
        check("Site 12 N 51.5007 W 0.1246", "decimal degrees", at = london)
        // A list of places in degrees and minutes or seconds: none of them is meant.
        val a = "25°05'48.8\"S 31°26'13.6\"E"
        val b = "24°59'46.0\"S 31°35'28.0\"E"
        for (t in listOf(a + "\n" + b, a + " " + b, "Waypoints:\n" + a + "\n" + b + "\n25°10'00.0\"S 31°30'00.0\"E", "1. " + a + "\n2. " + b,
                  "25°05'S 31°26'E\n24°59'S 31°35'E", "25°S 31°E\n24°S 32°E")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        // "25+" or "22+22" is a count or a sum, not a short plus code near you.
        for (t in listOf("Elephants at -25.096893, 31.437121 (25+)", "-25.096893, 31.437121\n\nCount: 25+")) {
            check(t, "decimal degrees", near = kruger)
        }
        for (t in listOf("Temp 35+", "Age 25+", "22+22=44")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testLongitudeFirstOnlyWhereTheFormatSaysSo() {
        // "coordinates: listOf(a, b)" on its own may be latitude first as well: it can't be told which.
        assertNull(CoordinateParser.parse("coordinates: [-25.096893, 31.437121]"))
        assertNull(CoordinateParser.parse("coordinates: [31.437121, -25.096893]"))
        // A Google Earth path, or two placemarks: the camera's <LookAt> isn't the place either.
        val lookAt = "<LookAt><longitude>31.50412</longitude><latitude>-25.04501</latitude><range>21345.6</range></LookAt>"
        for (t in listOf("<kml><Document><Placemark><name>Untitled Path</name>" + lookAt
                    + "<LineString><coordinates>31.437121,-25.096893,0 31.591110,-24.996110,0</coordinates></LineString>"
                    + "</Placemark></Document></kml>",
                  "<kml><Document>" + lookAt + "<Placemark><Point><coordinates>31.437121,-25.096893,0</coordinates></Point>"
                    + "</Placemark><Placemark><Point><coordinates>31.591110,-24.996110,0</coordinates></Point></Placemark></Document></kml>")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    @Test
    fun testThePinInAMapLink() {
        // Not the map's centre, wherever it is in the link.
        for (t in listOf("https://yandex.ru/maps/?ll=31.500000%2C-25.050000&z=12&pt=31.437121%2C-25.096893",
                  "https://yandex.ru/maps/?ll=31.500000%2C-25.050000&mode=whatshere&whatshere%5Bpoint%5D=31.437121%2C-25.096893",
                  "https://www.arcgis.com/home/webmap/viewer.html?center=31.5,-25.05&level=12&marker=31.437121,-25.096893",
                  "https://www.openstreetmap.org/?lat=-25.05&lon=31.5&zoom=12&layers=M&mlat=-25.096893&mlon=31.437121",
                  "https://www.bing.com/maps?cp=-25.05~31.5&lvl=11&sp=point.-25.096893_31.437121_Camp")) {
            check(t, "map link")
        }
    }

    @Test
    fun testLongRunsOfSpaces() {
        // A spreadsheet's empty cells, a message with many empty lines: read quickly (it is read as you type).
        val start = System.nanoTime()
        check("Latitude\tLongitude" + "\t".repeat(25) + "\n-25.096893\t31.437121" + "\t".repeat(25)
              + ("\n" + "\t".repeat(25)).repeat(400) + "\nSource: GPS", "decimal degrees")
        check("Lions at -25.096893, 31.437121" + "\n".repeat(20_000) + "ok", "decimal degrees")
        assertNull(CoordinateParser.parse("1" + " ".repeat(20_000) + "N"))
        assertNull(CoordinateParser.parse("36J" + " ".repeat(20_000) + "X 342401 7223411"))
        assertLessThan(secondsSince(start), 2.0)
    }

    // Inputs checked a fourth time, by family. Near Skukuza: S 24° 59' 48.9" E 31° 35' 32.3", or in decimal minutes
    // S 24° 59.815' E 31° 35.539'.
    private val dms = GeoPoint(lat = -24.9969167, lon = 31.5923056)
    private val ddm = GeoPoint(lat = -24.9969167, lon = 31.5923167)

    @Test
    fun testACountOrNameBeforeTheLetters() {
        // A sighting's count, a camp or waypoint number, a datum or a year before a position with its letters in front:
        // not its latitude.
        for (t in listOf("3 rhino S 24° 59' 48.9\" E 31° 35' 32.3\"", "Leopard 2 S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "2 lions S24 59 48.9 E31 35 32.3", "Camp 14 S24 59 48.9 E31 35 32.3", "Site 14: S24 59 48.9 E31 35 32.3",
                  "Sighting 2:\nS24°59'48.9\"\nE31°35'32.3\"", "2. S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "WP 02 S 24° 59' 48.9\" E 31° 35' 32.3\"", "Pride of 6 S24°59'48.9\" E31°35'32.3\"",
                  "WGS 84: S 24° 59' 48.9\" E 31° 35' 32.3\"", "GPS (WGS 84): S24 59 48.9 E31 35 32.3", "WGS-84: S24 59 48.9 E31 35 32.3",
                  "Datum: WGS 84\nS24 59 48.9 E31 35 32.3", "Kruger 2026: S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "EPSG:4326: S24 59 48.9 E31 35 32.3", "Lions 2 E 31° 35' 32.3\" S 24° 59' 48.9\"")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        for (t in listOf("Camp 14 S24.99 E31.59", "2 lions S 24.99 E 31.59", "Leopard 2 S24.99, E31.59")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
        }
        check("Site 3 S 25.0 E 31.5", "decimal degrees", at = GeoPoint(lat = -25.0, lon = 31.5))
        for (t in listOf("3 rhino S 24 59.8 E 31 35.5", "3 rhino S24°59.8' E31°35.5'")) {
            check(t, "degrees and decimal minutes", at = GeoPoint(lat = -24.9966667, lon = 31.5916667))
        }
        // A chat message's time and date before it.
        check("[05:31, 27/10/2026] Jaco: S24 59.815 E31 35.539 2 lions", "degrees and decimal minutes", at = ddm)
        // With the letters after the numbers, what follows the last letter is other text; unless the last half could go on
        // with it ("24 59 E 31 35"), which makes it unclear.
        check("24.99 S 31.59 E 12.5", "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
        check("25.096893 S 31.437121 E 3 cubs", "decimal degrees")
        check("Lions 25.0969 S 31.4371 E, 30° bearing", "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        assertNull(CoordinateParser.parse("12.5 S 24 59 E 31 35"))
    }

    @Test
    fun testRoadNames() {
        // Kruger's roads (S3, S100, H4-1) next to a position aren't a third letter or a number of it.
        for (t in listOf("S3 S24 59.815 E31 35.539", "S24 59.815 E31 35.539 on S3", "S24 59.815 E31 35.539 (S3)",
                  "Lions on the S3 at S24 59.815 E31 35.539", "Leopard on H4-1 at S24 59.815 E31 35.539")) {
            check(t, "degrees and decimal minutes", at = ddm)
        }
        for (t in listOf("Lions on S3: S24 59 48.9 E31 35 32.3", "S100: S24 59 48.9 E31 35 32.3")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        for (t in listOf("Cheetah on H4-1, -24.99693, 31.59231", "H4-1, -24.99693, 31.59231",
                  "Cheetah! 2km past Nkuhlu picnic spot on H4-1, -24.99693, 31.59231 at 07:45")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99693, lon = 31.59231))
        }
        // A letter right after a number is its direction.
        check("5130N00007W", "GPS (NMEA)", at = GeoPoint(lat = 51.5, lon = -0.1166667))
    }

    @Test
    fun testNumbersAfterAPosition() {
        // A phone number, a count or a height after a position with its letters in front.
        check("Skukuza Rest Camp GPS: S24 59 48.9 E31 35 32.3 Tel: 013 735 4152", "degrees, minutes and seconds", at = dms)
        check("S 24° 59' 48.9\" E 31° 35' 32.3\" 3 rhino", "degrees, minutes and seconds", at = dms)
        check("S24 59.815 E31 35.539 2 lions", "degrees and decimal minutes", at = ddm)
        check("S 24° 59' 48.9\", E 31° 35' 32.3\", 270.5 m", "degrees, minutes and seconds", at = dms)
        check("S24 59.815 E31 35.539 270m", "degrees and decimal minutes", at = ddm)
        check("N 51° 30' 2.5\" W 0° 7' 28.6\" 12 m", "degrees, minutes and seconds", at = GeoPoint(lat = 51.5006944, lon = -0.1246111))
        // A height with its unit is written for people, who put the latitude first.
        for (t in listOf("-24.996931, 31.592312, 270 m", "Lat -24.996931 Lon 31.592312 270 m")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.996931, lon = 31.592312))
        }
        // Without one, it can't be told from KML's longitude, latitude and altitude.
        assertNull(CoordinateParser.parse("-24.996931,31.592312,270.5"))
    }

    @Test
    fun testMoreWaysOfWritingIt() {
        // The location iMessage sends (a vCard, with its commas escaped).
        check("item1.URL;type=pref:http://maps.apple.com/?ll=-24.996931\\,31.592312&q=-24.996931\\,31.592312", "map link",
              at = GeoPoint(lat = -24.996931, lon = 31.592312))
        check("BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Current Location;;;\r\nFN:Current Location\r\nitem1.URL;type=pref:http://maps.apple.com/"
              + "?ll=-24.996931\\,31.592312&q=-24.996931\\,31.592312\r\nitem1.X-ABLabel:map url\r\nEND:VCARD\r\n", "map link",
              at = GeoPoint(lat = -24.996931, lon = 31.592312))
        // Degrees, minutes and seconds with points, the letter right next to them.
        for (t in listOf("S24.59.48.9 E31.35.32.3", "24.59.48.9S 31.35.32.3E")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        // UTM within 10 km north of the equator, where the northing has fewer digits.
        check("31N 166021 0", "UTM", within = 2.0, at = GeoPoint(lat = 0.0, lon = 0.0))
        check("31 N 500000 0", "UTM", within = 2.0, at = GeoPoint(lat = 0.0, lon = 3.0))
        check("37N 285000 1500", "UTM", within = 2.0, at = GeoPoint(lat = 0.0135632, lon = 37.0682178))
        // GeoJSON copied without its closing bracket is still longitude first.
        check("\"coordinates\": [\n  31.4371,\n  -25.0969", "GeoJSON", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        check("{\"type\": \"Point\", \"coordinates\": [31.4371, -25.0969", "GeoJSON", at = GeoPoint(lat = -25.0969, lon = 31.4371))
    }

    @Test
    fun testTemperaturesAndAngles() {
        // A degree sign, or degrees in words, on whole or one-decimal numbers among words: temperatures and angles.
        for (t in listOf("Dis 25 grade in die oggend en 31 grade in die middag", "Dit was 38 grade by Satara en 41 grade by Letaba",
                  "Min 12 grade, maks 31 grade", "Draai 90 grade links, dan 45 grade regs", "Heute 25 Grad, morgen 31 Grad",
                  "It was 25 degrees this morning and 31 degrees by noon", "Temp 31 deg, humidity 25 deg",
                  "Satara 38°C, Letaba 41°C", "Max 31°C min 18°C", "Skukuza: 34°C / 21°C", "Skukuza 38°/22°", "Min -5° max 31°",
                  "77°F / 88°F", "Water temp 18.5°, air 25.3°", "Pool 26°, sea 19°", "Slope 25°, aspect 31°",
                  "Wind veering from 20° to 45°", "Pitch 12.5°, roll 3.2°", "Field of view 63.4° x 44.1°")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // Lists of degrees, and a degree sign typed for the minute mark: the second one isn't minutes.
        for (t in listOf("Max temps: 31° 32° 33° 34°", "25° 25° 25° 25°", "Day 1: 25°, day 2: 31°", "Bearing 10° 20° 30° 40°",
                  "Skukuza Mon 34° 21°, Tue 33° 20°", "25°05°48.8\"S 31°26°13.6\"E", "25° 05° S, 31° 26° E")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testDistancesTimesAndYears() {
        // A distance after a direction, and a surveyor's bearing.
        for (t in listOf("Drive S 25 km, then E 31 km", "Drive S 25 km then E 31 km to the hide", "Drive N 2 km, E 3 km",
                  "From the gate go N 3 km, then W 2 km", "S 30° W, distance 120 m", "Course S 30° E 12 nm")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // Times, durations and time zones; years, opening hours, postal codes and a screen size.
        for (t in listOf("05:30 - 18:30", "05:30 - 08:45", "05:30, 18:15", "05:31 / 18:12", "12:30 - 14:45", "12:30:45 - 13:15:20",
                  "10:15:30, 11:20:40", "02:15:30, 01:45:20", "+02:00 -05:00", "3:2, 16:9", "2015-2020", "2010-2020", "2000-2024",
                  "2023/24", "2019/20", "2001/2002", "0800-1700", "0800 - 1700", "0530-1830", "0600-1800", "1200 1430",
                  "1350 1242", "1350, 1242", "0800 1234", "1920×1200")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // Fields with colons and a minus sign, with seconds in both halves, can't be times.
        check("-24:59:41, 31:35:32", "degrees, minutes and seconds", at = GeoPoint(lat = -24.9947222, lon = 31.5922222))
    }

    @Test
    fun testTwoDifferentPlaces() {
        // Two places 19 km apart in any two forms: links, a link and a position, plus codes, a grid reference, labelled
        // values, WKT or degrees and a position elsewhere in the text.
        for (t in listOf("https://www.google.com/maps?q=-25.0969,31.4371 https://www.google.com/maps?q=-24.9961,31.5911",
                  "Location: https://www.google.com/maps?q=-25.0969,31.4371\nLocation: https://www.google.com/maps?q=-24.9961,31.5911",
                  "https://maps.apple.com/?ll=-25.0969,31.4371 https://maps.apple.com/?ll=-24.9961,31.5911",
                  "geo:-25.0969,31.4371 geo:-24.9961,31.5911", "https://www.google.com/maps?q=-25.0969,31.4371 or -24.9961, 31.5911",
                  "-25.0969, 31.4371\nhttps://maps.google.com/?q=-24.9961,31.5911",
                  "5G6HWC3P+6R\n5G6HXC5P+2Q", "5G6HWC3P+6R or 5G6HWC4Q+2X", "5G6HWC3P+6R and -24.9961, 31.5911",
                  "Lions WC3P+6R, leopard XC5P+2Q",
                  "36J 342401 7223411\n-24.9961 31.5911", "36J 342401 7223411 and -24.9961, 31.5911",
                  "From -24.9961, 31.5911 to 36J 352401 7233411", "36J UT 42401 23411 and -24.9961 31.5911",
                  "Camp at 36JUT4240123411; sighting at -24.9961 31.5911",
                  "lat: -25.0969, lon: 31.4371 (Skukuza: -24.9961, 31.5911)", "<trkpt lat=\"-25.0969\" lon=\"31.4371\"/> -24.9961, 31.5911",
                  "lat=-25.0969 lon=31.4371 then go to -24.9961, 31.5911", "POINT (31.4371 -25.0969) and -24.9961, 31.5911",
                  "25°05'48.8\"S 31°26'13.6\"E\n-24.9961, 31.5911", "25°05'48.8\"S 31°26'13.6\"E or -24.9961, 31.5911",
                  "Camp: -25.0969, 31.4371\nS24 59 48.9 E31 35 32.3")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // The same place twice is read.
        check("https://www.google.com/maps?q=-25.096893,31.437121 https://www.google.com/maps?q=-25.096893,31.437121", "map link")
        check("-25.096893, 31.437121\nhttps://maps.google.com/?q=-25.096893,31.437121", "map link")
        check("5G6HWC3P+6R, 5G6HWC3P+6R", "plus code", within = 8.0)
        check("36J UT 42401 23411 / -25.096893, 31.437121", "decimal degrees")
    }

    @Test
    fun testLongPastesAreReadQuickly() {
        // A label with a long run of spaces after it, many zone-like words before a grid reference, a long list of degrees
        // and a 20 KB chat log: each read in a time that grows with the length, not faster (it is read as you type).
        val lines = listOf("Only 4x4 on the S100 after the rain, 50 m of mud near the bridge", "Leaving at 5 p.m., 2 x 2 litres of water each",
                     "Saw 12 elephants and 3 calves at the dam, about 400 m from the H4-1", "Gate opens 05:30, closes 18:30",
                     "Booked site 14 for 27/10/2026, R 1234.50 paid", "Temp 34°C at noon, 21°C tonight")
        val log = mutableListOf<String>()
        while (log.joinToString("\n").length < 20_000) {
            log.add("[12:${10 + log.size % 50}, 27/10/2026] Jaco: " + lines[log.size % lines.size])
        }
        log.add("[13:01, 27/10/2026] Jaco: Booking ref 1234567, lions at -25.0969, 31.4371")
        val start = System.nanoTime()
        assertNull(CoordinateParser.parse("lat" + " ".repeat(20_000) + "x 1"))
        assertNull(CoordinateParser.parse("latitude" + " ".repeat(200) + "S 25"))
        assertNull(CoordinateParser.parse("1C ".repeat(10_000) + "x 342401 7223411"))
        assertNull(CoordinateParser.parse("25° ".repeat(5_000)))
        check(log.joinToString("\n"), "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        assertLessThan(secondsSince(start), 3.0)
    }

    // Inputs checked a fifth time, by family.

    @Test
    fun testADirectionAfterADistance() {
        // A distance or a time with its direction after a position ("3 km N of Skukuza") isn't that position's letter.
        val typed = GeoPoint(lat = -24.99692, lon = 31.59231)
        for (t in listOf("-24.99692, 31.59231 (3 km N of Skukuza)", "-24.99692, 31.59231 3 km N of Skukuza", "-24.99692, 31.59231\n3 km N of Skukuza",
                  "Lions -24.99692, 31.59231 3km N of Skukuza", "Leopard: -24.99692, 31.59231 (3 km N of Skukuza on H1-2)",
                  "-24.99692, 31.59231 (2 km S of Satara)", "-24.99692, 31.59231 (1 km W of Skukuza)",
                  "-24.99692, 31.59231 (500 m W of the bridge)", "-24.99692, 31.59231, 3 km N of Skukuza", "-24.99692 31.59231 3 km N",
                  "Leeus by -24.99692, 31.59231, 2 km suid van Skukuza", "-24.99692, 31.59231 (5 min N of Skukuza)")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = typed)
            }
        }
        check("51.5007, -0.1246 (3 km S of Camden)", "decimal degrees", at = london)
    }

    @Test
    fun testDrivingDirectionsAndTimes() {
        // Distances in words or "kms", driving times, and a distance with its direction but no unit ("5 N of Satara") aren't
        // positions; nor are times and distances in a sports result.
        for (t in listOf("Drive south 25 kilometres then east 31 kilometres", "Drive S 25 kilometre, then E 31 kilometre",
                  "Drive north 2 kilometers, then east 3 kilometers", "Drive S 25 kms, then E 31 kms", "S 25 kms E 31 kms",
                  "Lions S 2 kms, W 1 kms of Satara", "From Satara drive 10 min S on the H1-3, then 5 min E on the S100",
                  "Head 10 min north then 2 min west on the S100", "Ry 10 min suid van Satara en dan 2 min oos op die S100",
                  "Leeus 10 min S van Skukuza, 5 min O van die brug", "Lions 10 minutes S of Satara and 5 minutes E of the H1-3",
                  "Leopard in tree, 5 min N of Skukuza on H1-2, 1 min W of the Sabie bridge",
                  "Lions 5 N of Satara, leopard 3 E of Satara", "Wild dogs 3 S of Lower Sabie, cheetah 2 W of Crocodile Bridge",
                  "Lions 25 S of Satara on the H1-3, 31 E of the river", "Pride of 15 S of Tshokwane, 3 E of the H1-2",
                  "Lions S 2, W 1 of Satara", "100m 10.123 s, long jump 8.952 m", "Ran 100 m in 12.345 s, then 8.952 m")) {
            for (near in listOf(null, kruger)) {
                assertNull(t, CoordinateParser.parse(t, near = near))
            }
        }
        // Whole degrees with the letter in front are still read.
        check("Elephants S 25 E 31", "decimal degrees", at = GeoPoint(lat = -25.0, lon = 31.0))
    }

    @Test
    fun testMinutesWrittenWithAnM() {
        // "m" for minutes after whole degrees without a degree sign too, and "s" for the seconds after it.
        for (t in listOf("25 05 m S, 31 26 m E", "25 5m S, 31 26m E", "S 25 05m E 31 26m", "25 05m S 31 26m E", "-25 5m, 31 26m")) {
            check(t, "degrees and decimal minutes", at = GeoPoint(lat = -25.083333, lon = 31.433333))
        }
        check("25 05.8m S, 31 26.2m E", "degrees and decimal minutes", at = GeoPoint(lat = -25.096667, lon = 31.436667))
        check("25 05.8136m S 31 26.2273m E", "degrees and decimal minutes", within = 2.0)
        for (t in listOf("25 5m 48.8s S, 31 26m 13.6s E", "25 05m 48.8s S 31 26m 13.6s E")) {
            check(t, "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
        }
        check("25 5m 48s S 31 26m 13s E", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096667, lon = 31.436944))
        // Minutes and seconds in words in the other languages, or after numbers alone.
        for (t in listOf("25 grados 5 minutos 48 segundos S, 31 grados 26 minutos 13 segundos E", "25 05 48 sec S 31 26 13 sec E",
                  "25 5 min 48 sec S, 31 26 min 13 sec E")) {
            check(t, "degrees, minutes and seconds", at = GeoPoint(lat = -25.096667, lon = 31.436944))
        }
    }

    @Test
    fun testAPositionFollowedByItsOwnDecimals() {
        // A position and the same place in signed decimals after it (a map's title, a GPS app showing both).
        val p = GeoPoint(lat = -25.096889, lon = 31.437111)
        for (t in listOf("25°05'48.8\"S 31°26'13.6\"E\n-25.096889, 31.437111", "25°05'48.8\"S 31°26'13.6\"E, -25.096889, 31.437111",
                  "25°05'48.8\"S 31°26'13.6\"E -25.096889, 31.437111", "25°05'48.8\"S 31°26'13.6\"E\n\n-25.096889, 31.437111",
                  "25°05'48.8\"S 31°26'13.6\"E\n-25.096889 31.437111", "25 05 48.8 S 31 26 13.6 E\n-25.096889, 31.437111",
                  "25°05.8133'S 31°26.2267'E\n-25.096889, 31.437111", "25.096889 S 31.437111 E\n-25.096889, 31.437111")) {
            check(t, at = p)
        }
        check("51°30′02.5″N 0°07′28.6″W\n51.500694, -0.124611", at = GeoPoint(lat = 51.500694, lon = -0.124611))
        check("Leopard 24°59'48.9\"S 31°35'32.3\"E\n-24.996917, 31.592306", at = dms)
        // Another place after it still isn't read.
        assertNull(CoordinateParser.parse("25°05'48.8\"S 31°26'13.6\"E -24.9961, 31.5911"))
    }

    @Test
    fun testMoreWaysWithPointsAndColons() {
        // Degrees, minutes and seconds with points after a degree sign, or with a space next to the letter.
        for (t in listOf("S24°59.48.9 E31°35.32.3", "S 24°59.48.9 E 31°35.32.3", "24°59.48.9S 31°35.32.3E", "24°59.48.9 S, 31°35.32.3 E",
                  "S 24.59.48.9 E 31.35.32.3", "24.59.48.9 S 31.35.32.3 E", "S 24.59.48.9, E 31.35.32.3")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        check("S25°05.48.8 E31°26.13.6", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
        // Decimal minutes after a degree sign stay decimal minutes.
        check("S24°59.815 E31°35.539", "degrees and decimal minutes", at = ddm)
        // Degrees and minutes with colons and a minus sign that no hour or time zone has.
        check("-25:05, 31:26", "degrees and decimal minutes", at = GeoPoint(lat = -25.083333, lon = 31.433333))
        check("-24:59, 31:35", "degrees and decimal minutes", at = GeoPoint(lat = -24.983333, lon = 31.583333))
        for (t in listOf("-05:00, 02:00", "-25:05 - 31:26")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        // UTM near the equator with the northing labelled first, or "E" and "N" after the numbers.
        for (t in listOf("37N N 1500 E 285000", "37N 285000 E 1500 N")) {
            check(t, "UTM", within = 2.0, at = GeoPoint(lat = 0.0135632, lon = 37.0682178))
        }
    }

    @Test
    fun testListsOfBearings() {
        for (t in listOf("Bearing 10° 20° 30°", "Heading 10° 20° 30°", "Bearing: 10° 20° 30°", "Bearing 10°, 20°, 30°", "Bearing 10 20 30")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // A bearing or an altitude next to a position is still left out.
        check("Bearing 270°, -25.096893, 31.437121", "decimal degrees")
        check("-24.99, 31.59, alt 312", "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
    }

    @Test
    fun testRoadsAndMarkersNextToAPosition() {
        // A kilometre marker on a road ("H1-3 km 14"), or a road right before the position or a count, isn't part of it.
        val h13 = GeoPoint(lat = -24.3812, lon = 31.8456)
        for (t in listOf("Lions on H1-3 km 14: 24.3812 S 31.8456 E", "Lions on H1-3 km 14: 24 22.872 S 31 50.736 E",
                  "H1-3 km 14 - lions 24 22.872 S 31 50.736 E", "Lions H1-3 km 14, 24.3812 S, 31.8456 E",
                  "Lions on S100 km 7: 24 22.872 S 31 50.736 E", "Lions on S100 24 22.872 S 31 50.736 E",
                  "Leopard on S100 km 7, -24.3812, 31.8456")) {
            check(t, within = 2.0, at = h13)
        }
        check("S100 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds", at = GeoPoint(lat = -24.381194, lon = 31.845611))
        check("S3 24 59.816 S 31 35.539 E", "degrees and decimal minutes", at = GeoPoint(lat = -24.996933, lon = 31.592317))
        check("S100 2 lions S24 23.580 E31 46.680", "degrees and decimal minutes", at = GeoPoint(lat = -24.393, lon = 31.778))
        check("Lions S25 07.178 E31 55.057 S28 3 males", "degrees and decimal minutes", at = GeoPoint(lat = -25.119633, lon = 31.917617))
        check("Lions on S 3 at S24 59.815 E31 35.539", "degrees and decimal minutes", at = ddm)
        // A road's marker after its name and "km" isn't the first field of the degrees after it; a number after a word could
        // be ("Camp 1 25 05.8 S, …"), unless both halves are degrees and minutes to 3 decimals, as a GPS writes them (see
        // testCountsAndTemperaturesBeforeAGarminPosition), and one after a distance is a distance ("5 km 24 59.816 S …").
        for (t in listOf("Leopard H4-1 km 3 24 59.816 S 31 35.539 E", "Leopard H4-1 km 14 24 59.816 S 31 35.539 E",
                  "Lions 5 km 24 59.816 S 31 35.539 E", "Leopard km 3 24 59.816 S 31 35.539 E")) {
            check(t, "degrees and decimal minutes", at = GeoPoint(lat = -24.996933, lon = 31.592317))
        }
        assertNull(CoordinateParser.parse("Leopard km 3 24 59.8 S 31 35.5 E"))
        // A road written next to degrees and minutes, with no letter on the other half, could as well be a direction.
        for (t in listOf("Lions on S28 25.0969, 31.4371", "Accident on N4 25.4567, 30.9876")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testTimesAndCountsWrittenWithLetters() {
        // "06h45", "4x4", "10am", "3rd", "5L", "12x" or "2x" next to a position: other text, not a grid zone.
        for (t in listOf("Leeus by S24 59.816 O31 35.539 om 06h45", "4x4 only S24 59.816 E31 35.539", "Lions S24 59.816 E31 35.539 at 10am",
                  "Bring 5L water. S24 59.816 E31 35.539", "Wild dogs 12x S24 59.816 E31 35.539")) {
            check(t, at = GeoPoint(lat = -24.996933, lon = 31.592317))
        }
        for (t in listOf("Skukuza Rest Camp\nGPS: S24 59 48.9 E31 35 32.3\nGate hours: 05h30 - 18h30\nTel: 013 735 4152",
                  "Lions S24 59 48.9 E31 35 32.3 3rd time", "Lions 24°59'48.9\"S 31°35'32.3\"E 4x4 only")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        for (t in listOf("Lions S24.99693 E31.59231 12h00", "Lions 24.99693 S 31.59231 E 2x leopard")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99693, lon = 31.59231))
        }
    }

    @Test
    fun testTimesDatesAndCountsAroundAPosition() {
        // A time, a date, a phone number or a count with a word next to a position isn't part of a list of numbers.
        val p = GeoPoint(lat = -24.3812, lon = 31.8456)
        for (t in listOf("Lions at -24.3812, 31.8456, 06:45 this morning", "Lions -24.3812, 31.8456, 3 males and 2 cubs",
                  "Lions at -24.3812, 31.8456, 27/10/2026", "Lions at -24.3812, 31.8456, 082 555 1234", "Lions -24.3812, 31.8456; 06:45",
                  "Lions\n-24.3812, 31.8456,\n3 males", "Lions, 06:45, -24.3812, 31.8456", "27/10/2026, -24.3812, 31.8456",
                  "Wild dogs 12, -24.3812, 31.8456")) {
            check(t, "decimal degrees", at = p)
        }
        for (t in listOf("-24.4567 31.6789 06:45", "Lions!! 4 females + 2 cubs, S36 about 5km from H1-3 junction -24.4567 31.6789 9:15am")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.4567, lon = 31.6789))
        }
        for (t in listOf("Leopard 07:45, -24.99693, 31.59231", "Leopard -24.99693, 31.59231, 3 cubs", "Leopard -24.99693, 31.59231, 3 cubs, 05:45")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99693, lon = 31.59231))
        }
        // Before letters after the numbers, the numbers right before them, as many as the other half has.
        for (t in listOf("[07:15, 27/10/2026] Jaco: 24 22.872 S 31 50.736 E", "07:15 Lions 24 22.872 S 31 50.736 E",
                  "Lions at 07:15, 24 22.872 S 31 50.736 E", "27/10 Lions 24 22.872 S 31 50.736 E",
                  "12 buffalo at Mazithi dam\nLions 24 22.872 S 31 50.736 E")) {
            check(t, "degrees and decimal minutes", at = p)
        }
        check("27/10/2026, 07:15 - Jaco: 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds",
              at = GeoPoint(lat = -24.381194, lon = 31.845611))
        // Before letters in front, a time, a date or two counts rather than a whole half after the second letter.
        for (t in listOf("12:30 Lions S 24 59 48.9 E 31 35 32.3", "[07:45] Jaco: S24 59 48.9 E31 35 32.3", "27/10: S24 59 48.9 E31 35 32.3",
                  "27/10/26 S24 59 48.9 E31 35 32.3", "2 lions 3 cubs S 24 59 48.9 E 31 35 32.3", "Camp 14 site 7 S24 59 48.9 E31 35 32.3",
                  "12:30 E 31 35 32.3 S 24 59 48.9", "Camp 14 24 59 48.9 S 31 35 32.3 E")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
        check("Gate 05:30 S25 07 09.6 E31 54 56.4", "degrees, minutes and seconds", at = GeoPoint(lat = -25.119333, lon = 31.915667))
        check("Lions 07:45 S 24 59.81 E 31 35.53", "degrees and decimal minutes", at = GeoPoint(lat = -24.996833, lon = 31.592167))
        // Not when what is before them could be a half as well: then as before.
        check("24.99 S 31.59 E 12 30.5", "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
    }

    @Test
    fun testTheLastOfManyChatMessages() {
        // More messages than the whole text is read for: a GPS's degrees and minutes with letters can only be a position.
        val messages = listOf("[06:12, 27/10/2026] Pieter: Morning all, gates opened 05:30",
                        "[06:20, 27/10/2026] Marié: Ellies on the H1-3 near Tshokwane",
                        "[06:31, 27/10/2026] Jaco: Leopard at Sweni hide, 2 cubs", "[06:40, 27/10/2026] Pieter: Thanks!",
                        "[06:52, 27/10/2026] Anel: Road closed S41 after the rain", "[07:03, 27/10/2026] Marié: 12 buffalo at Mazithi dam")
        check((messages + listOf("[07:15, 27/10/2026] Jaco: Lions on S100: S24 22.872 E31 50.736")).joinToString("\n"),
              "degrees and decimal minutes", within = 2.0, at = GeoPoint(lat = -24.3812, lon = 31.8456))
        check((messages + listOf("[07:15, 27/10/2026] Jaco: Lions on S100: S24 22 52.3 E31 50 44.2")).joinToString("\n"),
              "degrees, minutes and seconds", at = GeoPoint(lat = -24.381194, lon = 31.845611))
    }

    @Test
    fun testTwoPlacesAroundLinksAndOtherLetters() {
        // "ß" or a ligature in the text, a link with a space in it or a part of a link: the other place is still looked for.
        for (t in listOf("Löwen bei lat: -25.0969, lon: 31.4371 (Skukuza: -24.9961, 31.5911). Grüße",
                  "Löwen an der Straße H4-1: 36J 342401 7223411, Skukuza -24.9961 31.5911", "5G6HWC3P+6R and -24.9961, 31.5911 Grüße",
                  "Camp: lat -25.0969 lon 31.4371; gate at S 24° 59' 48.9\" E 31° 35' 32.3\" (\uFB01rst left)",
                  "https://maps.google.com/?q=-25.0969, 31.4371 and -24.9961, 31.5911",
                  "https://maps.google.com/?q=-25.0969, 31.4371\n-24.9961, 31.5911", "geo:-25.0969 31.4371 and -24.9961, 31.5911",
                  "maps.google.com/?q=-25.0969, 31.4371 and S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "maps?q=-25.0969,31.4371 and -24.9961, 31.5911", "&ll=-25.0969,31.4371 and -24.9961, 31.5911",
                  "/@-25.0969,31.4371,15z and -24.9961, 31.5911")) {
            for (near in listOf(null, kruger)) {
                assertNull(t, CoordinateParser.parse(t, near = near))
            }
        }
        check("https://maps.google.com/?q=-25.096893, 31.437121", "map link")
        check("Löwen bei lat: -25.096893, lon: 31.437121. Grüße", "decimal degrees")
        // The same place written less precisely: the same as closely as that is written.
        for (t in listOf("lat: -24.99, lon: 31.59 (S 24° 59' 48.9\" E 31° 35' 32.3\")",
                  "https://maps.google.com/?q=-24.99,31.59 S 24° 59' 48.9\" E 31° 35' 32.3\"",
                  "S 24° 59' 48.9\" E 31° 35' 32.3\" https://maps.google.com/?q=-24.99,31.59")) {
            check(t, at = GeoPoint(lat = -24.99, lon = 31.59))
        }
        check("Lions at -24.996931, 31.592312 (-24.997, 31.592)", "decimal degrees", at = GeoPoint(lat = -24.996931, lon = 31.592312))
    }

    // Inputs checked a sixth time, by family. Near Skukuza with five decimals, or in decimal minutes as "S24 59.816".
    private val typed = GeoPoint(lat = -24.99693, lon = 31.59231)
    private val gps = GeoPoint(lat = -24.9969333, lon = 31.5923167)

    @Test
    fun testWhatIsBeforeLettersInFront() {
        // A date, a time, a temperature or a number with decimals before letters written right before their numbers:
        // the letters are in front, whatever is before them.
        val p = GeoPoint(lat = -24.99, lon = 31.59)
        for (t in listOf("27/10/2026 S24.99 E31.59", "2026/10/27 S24.99 E31.59", "27/10/26 S24.99 E31.59", "27/10/2026 06:45 S24.99 E31.59",
                  "3/4 S24.99 E31.59", "27/10/2026 E31.59 S24.99", "27/10 S24.99 E31.59", "25° S24.99 E31.59", "12.5 S24.99 E31.59",
                  "Lions at 06.45, S24.99 E31.59", "Km 14.5, then S24.99 E31.59", "Temp 31.5, lions at S24.99 E31.59",
                  "Lions 3.5 E31.59 S24.99", "Lions, 31°C, S 24.99 E 31.59")) {
            check(t, "decimal degrees", at = p)
        }
        // With a space after the letters too: a date's last number or a time isn't a half.
        check("27/10/2026 S 25.09 E 31.43", "decimal degrees", at = GeoPoint(lat = -25.09, lon = 31.43))
        check("27/10/2026 N 51.50 W 0.12", "decimal degrees", at = GeoPoint(lat = 51.5, lon = -0.12))
        check("27/10/2026 06:45 S 25 E 31", "decimal degrees", at = GeoPoint(lat = -25.0, lon = 31.0))
        // Fields joined by dashes or colons, and the SANParks form after a temperature or a degree sign.
        for (t in listOf("06:45 S24:59:48.9 E31:35:32.3", "[06:45] S24-59-48.9 E031-35-32.3", "Gate 2/3 S24-59-48.9 E031-35-32.3",
                  "27/10/26 S24:59:48.9 E31:35:32.3", "2026-10-27 06:45 S24:59:48.9 E31:35:32.3", "06:45 E31:35:32.3 S24:59:48.9",
                  "38°C S24 59 48.9 E31 35 32.3", "Hot! 38°C. Lions S24 59 48.9 E31 35 32.3", "Temp 32°C, S 24 59 48.9 E 31 35 32.3",
                  "38° in the shade, lions at S24 59 48.9 E31 35 32.3", "Dis 34 grade! Leeus by S24 59 48.9 O31 35 32.3",
                  "Warm 34°. Leeus by S24 59 48.9 O31 35 32.3", "Lions 06.45 S24 59 48.9 E31 35 32.3")) {
            check(t, at = dms)
        }
        check("06:45 S25-05-48.8 E031-26-13.6", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
        // Both halves written alike: 24° 59.8' S, 31° 35.5' E, not 6.45° S, 24° 59.8' E.
        check("Leopard 06.45 S 24 59.8 E 31 35.5", "degrees and decimal minutes", at = GeoPoint(lat = -24.996667, lon = 31.591667))
    }

    @Test
    fun testWhatIsBeforeLettersAfter() {
        // A count or a name's number before a position in decimals with its letters after them, or a time or a short date.
        for (t in listOf("Leopard 2 24.99692S, 31.59231E", "Camp 14 24.99692S, 31.59231E", "Stop 3 24.99692S, 31.59231E",
                  "#3 24.99692S, 31.59231E", "06:45 24.99692S, 31.59231E", "Leopard 2 24.99692 S, 31.59231 E")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99692, lon = 31.59231))
        }
        for (t in listOf("06:45 24.99693 S 31.59231 E", "Lions at 06:45 24.99693 S 31.59231 E", "06:45 24.99693S 31.59231E",
                  "06:45 24.99693 S, 31.59231 E", "06:45 24.99693° S, 31.59231° E 3 lions", "27/10 24.99693 S 31.59231 E (3 lions)",
                  "27/10 24.99693 S 31.59231 E x2")) {
            check(t, "decimal degrees", at = typed)
        }
        // Not a number that could be the degrees of decimal minutes.
        assertNull(CoordinateParser.parse("25 5.8136 S, 31.4371 E"))
    }

    @Test
    fun testTemperaturesAndBearingsNextToAPosition() {
        // A temperature or a bearing after a position isn't its longitude, nor the next number of a list.
        for (t in listOf("24 59 48.9 S 31 35 32.3 E 38°C", "Lions 24 59 48.9 S 31 35 32.3 E (32°C)", "Lions 24 59 48.9 S 31 35 32.3 E 45° bearing",
                  "Leeus 24 59 48.9 S 31 35 32.3 O 34°", "24 59 48.9 S 31 35 32.3 E 32 grade", "24 59 48.9 S 31 35 32.3 E 1° C cooler",
                  "24°59'48.9\"S 31°35'32.3\"E 32°C")) {
            check(t, at = dms)
        }
        for (t in listOf("-24.996931, 31.592312, 32°C", "Lions at -24.996931, 31.592312, 32°C", "Sun 27 Oct, 5 lions, -24.996931 31.592312, 32°C",
                  "Lions -24.996931, 31.592312, 32℃", "-24.996931, 31.592312; 32°C")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.996931, lon = 31.592312))
        }
        for (t in listOf("24.99693° S, 31.59231° E 32°C", "Lions 24.99693° S, 31.59231° E 32°C", "-24.99693, 31.59231, 32°C, 06:45")) {
            check(t, "decimal degrees", at = typed)
        }
        check("Lions 24 59.816 S 31 35.539 E 32°C", "degrees and decimal minutes", at = gps)
        // Ranges of temperatures.
        for (t in listOf("18°-31°", "18° - 31°", "25° and 31°")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testOneCoordinateIsNotAPosition() {
        // A latitude or a longitude on its own, with its minutes.
        for (t in listOf("S 24° 59.815'", "25° 05.8136' S", "E 31° 35.539'", "24° 59'", "Lat: S 24° 59.815'", "24° 59.816 S", "S 24° 59.816")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // A degree sign read in the wrong encoding ("Â°"), or written with a small o.
        check("25Â° 05.8136' S, 31Â° 26.2273' E", "degrees and decimal minutes", within = 2.0)
        check("25ᵒ05'48.8\"S 31ᵒ26'13.6\"E", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
    }

    @Test
    fun testOtherDashesAndNumbers() {
        // A dash of any kind before a number is a minus sign; one with spaces round it, after a half written with a minus
        // sign, separates the halves.
        for (t in listOf("\u201025.096893, 31.437121", "\u201125.096893 31.437121", "\u201225.096893, 31.437121",
                  "\uFE6325.096893, 31.437121", "Lat: \u201025.096893 Lon: 31.437121", "-25.096893 - 31.437121",
                  "-25.096893 – 31.437121")) {
            check(t, "decimal degrees")
        }
        check("-24°59.815' - 31°35.539'", "degrees and decimal minutes", at = ddm)
        // A fraction, a superscript or a circled number next to a position isn't one of its digits.
        for (t in listOf("Lions S 24.99 E 31.59, ½ km from gate", "Burn area S 24.99 E 31.59, 2 km²", "Lions 24.99 S 31.59 E (½ km N of camp)",
                  "Lions S24.99 E31.59 ①")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
        }
    }

    @Test
    fun testListsOfPlacesInMixedForms() {
        // Two or three places in grid references, links, degrees and decimals, also after the same place written four or
        // five ways: none of them is meant.
        for (t in listOf("Lions 36J 342401 7223411\nLeopard 36J 352401 7233411\nCheetah -24.8961, 31.6911",
                  "Lions 36J UT 42401 23411\nLeopard 36J UT 52401 33411\nCheetah -24.8961, 31.6911",
                  "Lions 36J 342401 7223411\nLeopard 36J 352401 7233411\nCheetah https://maps.google.com/?q=-24.8961,31.6911",
                  "25°05'48.8\"S 31°26'13.6\"E\n24°59'46\"S 31°35'28\"E\nhttps://maps.google.com/?q=-24.8961,31.6911",
                  "S 25.0969 E 31.4371\nS 24.9961 E 31.5911\n36J 362401 7243411",
                  "36J 342401 7223411\n36J 342401 7223411\n-24.9961, 31.5911",
                  "36J 342401 7223411\n36J UT 62401 43411\n36J 352401 7233411",
                  "https://maps.google.com/?q=-25.096893,31.437121\n5G6HWC3P+6R\n2505.8136,S,03126.2273,E\n-2505.8136+03126.2273/\n"
                    + "36J 342401 7223411\n-24.9961, 31.5911",
                  "https://maps.google.com/?q=-25.0969,31.4371 \n5G6HWC3P+6R \n2505.8136,S,03126.2273,E \n36J 342401 7223411 \n"
                    + "lat=-25.0969 lon=31.4371 \nLeopard: -24.9961, 31.5911")) {
            for (near in listOf(null, kruger)) {
                assertNull(t, CoordinateParser.parse(t, near = near))
            }
        }
    }

    @Test
    fun testTheSamePlaceInMoreForms() {
        for (t in listOf("S 25.096893 E 31.437121\n25°05'48.8\"S 31°26'13.6\"E", "S 25.096893 E 31.437121\n25.096893 S 31.437121 E",
                  "36J 342401 7223411\n36J 342401 7223411", "36J 342401 7223411\n36J UT 42401 23411",
                  "25°05'48.8\"S 31°26'13.6\"E\n25°05'48.8\"S 31°26'13.6\"E\n25°05'48.8\"S 31°26'13.6\"E",
                  "https://maps.google.com/?q=-25.096893,31.437121\n5G6HWC3P+6R\n2505.8136,S,03126.2273,E\n36J 342401 7223411\n"
                    + "lat=-25.096893 lon=31.437121\n-25.096893, 31.437121")) {
            check(t, within = 10.0)
        }
        check("N 51.500694 W 0.124611\n51°30'02.5\"N 0°07'28.6\"W", within = 10.0, at = GeoPoint(lat = 51.500694, lon = -0.124611))
    }

    @Test
    fun testRoadMarkersAndNumbersNextToAPair() {
        // A road's marker after the position ("km 12"), a number after labelled values, and a distance or an odometer with
        // decimals before a pair.
        for (t in listOf("-24.99693, 31.59231 km 12", "S 24.99693 E 31.59231 km 12 H4-1", "Lions S 24.99693 E 31.59231 km 12",
                  "Leeus S 24,99693 O 31,59231 km 12", "-24.99693; 31.59231 km 12", "-24.99693, 31.59231 km 120",
                  "-24.99693 31.59231 Km-12", "27/10 S 24.99693 E 31.59231 km 12", "Lat -24.99693 Lon 31.59231 06:45",
                  "Lat -24.99693 Lon 31.59231 3 lions", "Lat -24.99693 Lon 31.59231 27/10/2026", "Lat -24.99693 Lon 31.59231 km 12",
                  "Breedte -24,99693 Lengte 31,59231 3 leeus")) {
            check(t, at = typed)
        }
        check("06:45 S 24 59.816 E 31 35.539 km 12", "degrees and decimal minutes", at = gps)
        for (t in listOf("H1-2 km 23.4, -24.9969, 31.5923", "Lions on H4-1 km 12.5 -24.9969 31.5923", "Odometer 12345.6, -24.9969, 31.5923")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.9969, lon = 31.5923))
        }
        check("Lions on H4-1 at km 12.5, -24.996931, 31.592312", "decimal degrees", at = GeoPoint(lat = -24.996931, lon = 31.592312))
        // A list is still a list.
        assertNull(CoordinateParser.parse("12.345, -24.9969, 31.5923"))
    }

    @Test
    fun testDegreesInWordsAndMinutesWithAnMNextToTheLetter() {
        for (t in listOf("S24 deg 59.816 E31 deg 35.539", "Lions at S24 deg 59.816 E31 deg 35.539",
                  "S24 degrees 59.816 minutes E31 degrees 35.539 minutes", "S24 deg 59.816 min, E31 deg 35.539 min",
                  "S24 grad 59.816 E31 grad 35.539", "S24 graad 59.816 E31 graad 35.539", "S24 ° 59.816' E31 ° 35.539'",
                  "S24 °59.816' E31 °35.539'", "S24 59.816m E31 35.539m", "S24 59.816 m E31 35.539 m", "S24d 59.816m E31d 35.539m",
                  "S24d59.816m E31d35.539m")) {
            check(t, "degrees and decimal minutes", at = gps)
        }
        check("S25d 05.8136m E31d 26.2273m", "degrees and decimal minutes", within = 2.0)
        check("S25 deg 07.182 E31 deg 54.942", "degrees and decimal minutes", at = GeoPoint(lat = -25.1197, lon = 31.9157))
        check("N40 deg 41.352 W74 deg 2.670", "degrees and decimal minutes", at = newYork)
        for (t in listOf("S24 deg 59' 48.9\" E31 deg 35' 32.3\"", "S24deg 59' 48.9\" E31deg 35' 32.3\"",
                  "S24 graad 59' 48.9\" E31 graad 35' 32.3\"")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
    }

    @Test
    fun testSearchLinksTypedInOtherForms() {
        // A search, a pin or a destination in degrees and minutes, with letters or in DMS: the whole value is read, not its
        // first two numbers.
        for (t in listOf("https://maps.google.com/?q=24+59.816+S+31+35.539+E", "https://maps.apple.com/?q=24+59.816+S+31+35.539+E",
                  "https://www.google.com/maps/search/?api=1&query=24%2059.816%20S%2031%2035.539%20E",
                  "https://www.google.com/maps/dir/?api=1&destination=24+59.816+S+31+35.539+E",
                  "https://maps.apple.com/?daddr=24+59.816+S+31+35.539+E", "https://maps.google.com/?q=-24+59.816,+31+35.539",
                  "https://www.google.com/maps/search/?api=1&query=S24%2059.816%20E31%2035.539",
                  "https://maps.apple.com/?q=S24%2059.816%20E31%2035.539", "https://maps.google.com/?q=S24+59.816+E31+35.539")) {
            check(t, "map link", at = gps)
        }
        check("geo:0,0?q=24 59.816 S 31 35.539 E", "geo: link", at = gps)
        check("https://maps.google.com/?q=24+59+48.9+S+31+35+32.3+E", "map link", at = dms)
        check("https://www.google.com/maps?q=24%C2%B059'48.9%22S+31%C2%B035'32.3%22E", "map link", at = dms)
        for (t in listOf("https://maps.google.com/?q=24.996931+S+31.592312+E", "https://maps.google.com/?q=S24.996931,E31.592312")) {
            check(t, "map link", at = GeoPoint(lat = -24.996931, lon = 31.592312))
        }
        // A "%" elsewhere in the message doesn't keep the link encoded.
        check("100% sure https://www.google.com/maps/place/24%C2%B059'48.9%22S+31%C2%B035'32.3%22E/@-24.85,31.2,9z", "map link", at = dms)
        check("Battery 15% https://www.google.com/maps/place/5G6HWC3P%2B6R", "plus code", within = 8.0)
    }

    @Test
    fun testDottedSecondsBeforeAFullStop() {
        for (t in listOf("Leopard S24.59.48.9 E31.35.32.3. 2 cubs", "Leopard S24.59.48.9, E31.35.32.3. 2 cubs",
                  "06:45 Lions at S24.59.48.9 E31.35.32.3.", "27/10 S24.59.48.9 E31.35.32.3.", "S24.59.48.9 E31.35.32.3.",
                  "Lions at S24.59.48.9 E31.35.32.3.")) {
            check(t, "degrees, minutes and seconds", at = dms)
        }
    }

    @Test
    fun testShortDatesAndNamedNumbersNextToAPosition() {
        // A day and a month next to a position aren't another place.
        val p = GeoPoint(lat = -24.996931, lon = 31.592312)
        for (t in listOf("27/10 -24.996931, 31.592312", "-24.996931 31.592312\n27/10", "-24.996931, 31.592312 (27/10)",
                  "-24.996931, 31.592312 12/3")) {
            check(t, "decimal degrees", at = p)
        }
        check("https://maps.google.com/?q=-24.996931,31.592312 27/10", "map link", at = p)
        check("-24.9969, 31.5923, 27/10", "decimal degrees", at = GeoPoint(lat = -24.9969, lon = 31.5923))
        // Beside a position, numbers each named by words before them are what the message says, not another place; on their
        // own the words may be labels in another language.
        for (t in listOf("USD/ZAR 18.2345, EUR/ZAR 19.8765. Lions at -25.0969, 31.4371", "Temp 25.123, wind 31.456. Lions at -25.0969, 31.4371")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        }
        check("Широта: 55.7558 Долгота: 37.6173", "decimal degrees", at = GeoPoint(lat = 55.7558, lon = 37.6173))
        check("Lions at -25.096893 and 31.437121", "decimal degrees")
        assertNull(CoordinateParser.parse("25° and 31°"))
    }

    @Test
    fun testTablesWithAHeaderRow() {
        // Rows under a header that names the latitude and longitude columns, whatever the other columns hold.
        val p = GeoPoint(lat = -24.996931, lon = 31.592312)
        for (t in listOf("id,lat,lon\n1,-24.996931,31.592312", "Name,Count,Lat,Lon\nLeopard,2,-24.996931,31.592312",
                  "id;name;lat;lon\n1;Camp;-24,996931;31,592312", "| lat | lon |\n|---|---|\n| -24.996931 | 31.592312 |",
                  "id,lat,lon\n1,-24.996931,31.592312\n2,-24.996932,31.592313")) {
            check(t, "decimal degrees", at = p)
        }
        assertNull(CoordinateParser.parse("id,lat,lon\n1,-24.9969,31.5923\n2,-25.0969,31.4371"))
    }

    @Test
    fun testCountsAfterLettersInAChatLog() {
        // More chat messages than the whole text is read for, the last a position with its letters after the numbers and
        // a count after it.
        val lines = listOf("[06:00, 27/10/2026] Jaco: Only 4x4 on the S100 after the rain, 50 m of mud near the bridge",
                     "[07:01, 27/10/2026] Jaco: Leaving at 5 tomorrow, gates open 05:30",
                     "[08:02, 27/10/2026] Jaco: Saw 12 elephants and 3 calves at Sunset Dam")
        val log = (lines + lines + lines).joinToString("\n") + "\n[18:01, 27/10/2026] Jaco: "
        for (t in listOf("Lions 24.9969 S 31.5923 E 2 males", "24.9969S 31.5923E 2 males")) {
            check(log + t, "decimal degrees", at = GeoPoint(lat = -24.9969, lon = 31.5923))
        }
        check(log + "Lions 24 59 48.9 S 31 35 32.3 E 3 rhino", "degrees, minutes and seconds", at = dms)
        check(log + "24°59.815' S 31°35.539' E 2 males", "degrees and decimal minutes", at = ddm)
    }

    @Test
    fun testNumbersWithAPlusAndLongRuns() {
        // A number with a plus isn't a plus code, nor is a padded code (an area 5 km across or more).
        for (t in listOf("25000000+ visitors", "22000000+", "Over 25000000+ views", "5G6HWC00+")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        // Long runs after a grid reference's labels, or of digits in KML: read in a time that grows with the length.
        val start = System.nanoTime()
        assertNull(CoordinateParser.parse("N" + " ".repeat(20_000) + "x E 342401 7223411"))
        check("36J 342401 7223411 E" + " ".repeat(20_000) + "x", "UTM", within = 2.0)
        assertNull(CoordinateParser.parse("<coordinates>" + "1".repeat(20_000)))
        assertLessThan(secondsSince(start), 3.0)
    }

    // Inputs checked a seventh time, by family.

    @Test
    fun testMinutesAndSecondsInWordsAfterLetterMarks() {
        // "min" and "sec" after degrees written with a "d", or after minutes written with an "m": the position's minutes
        // and seconds, not a time left out.
        for (t in listOf("S25d 05.8136 min E31d 26.2273 min", "-25d 05.8136 min, 31d 26.2273 min", "S 25 d 05.8136 min E 31 d 26.2273 min",
                  "Lat: -25d 05.8136 min Lon: 31d 26.2273 min", "25d 05.8136 min S, 31d 26.2273 min E")) {
            check(t, "degrees and decimal minutes", within = 2.0)
        }
        for (t in listOf("S 25d 05 min 48.8 sec E 31d 26 min 13.6 sec", "-25d 05 min 48.8 sec, 31d 26 min 13.6 sec",
                  "25d 05 min 48.8\" S, 31d 26 min 13.6\" E", "Lat: 25d 05 min 48.8 sec S Long: 31d 26 min 13.6 sec E",
                  "25d 05 min 48.8 sec S, 31d 26 min 13.6 sec E", "25°05m 48.8 sec S, 31°26m 13.6 sec E",
                  "25d 05m 48.8 sec S, 31d 26m 13.6 sec E", "25 05 m 48.8 sec S, 31 26 m 13.6 sec E",
                  "25°05m48.8 sec S 31°26m13.6 sec E", "25° 05m 48.8sec S 31° 26m 13.6sec E", "25 05m 48.8 sec S 31 26m 13.6 sec E",
                  "25°05m 48.8 secs S, 31°26m 13.6 secs E")) {
            for (near in listOf(null, kruger)) {
                check(t, "degrees, minutes and seconds", near = near, at = GeoPoint(lat = -25.096889, lon = 31.437111))
            }
        }
        check("25d 5 min S, 31d 26 min E", "degrees and decimal minutes", at = GeoPoint(lat = -25.083333, lon = 31.433333))
        check("N 40d 41 min W 74d 2 min", "degrees and decimal minutes", at = GeoPoint(lat = 40.683333, lon = -74.033333))
        for (t in listOf("N40d 41.352min W74d 2.670min", "40d 41.352 min N, 74d 2.670 min W")) {
            check(t, "degrees and decimal minutes", at = newYork)
        }
        for (t in listOf("40d 41 min 21.1 sec N, 74d 2 min 40.2 sec W", "40d 41m 21.1 sec N 74d 2m 40.2 sec W")) {
            check(t, "degrees, minutes and seconds", at = GeoPoint(lat = 40.689194, lon = -74.0445))
        }
        // Elsewhere still a time.
        assertNull(CoordinateParser.parse("Lions 10 min S of Satara"))
        check("-25.0969, 31.4371 seen 5 min ago", "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
    }

    @Test
    fun testSecondsMarkedWithAnS() {
        // An "s" after the seconds, with the letters in front, a minus sign, or a space before the letter after it.
        for (t in listOf("S 25°05'48.815s E 31°26'13.636s", "-25°05'48.815s, 31°26'13.636s", "25°05'48.815s S, 31°26'13.636s E",
                  "25°05'48.815s S 31°26'13.636s E", "S 25°05′48.815s E 31°26′13.636s", "S 25º05'48.815s E 31º26'13.636s",
                  "S 25d05m48.815s E 31d26m13.636s", "S 25 deg 05 min 48.815s E 31 deg 26 min 13.636s",
                  "S 25°05min48.815s E 31°26min13.636s", "-25d05m48.815s, 31d26m13.636s", "25°05'48.815sS 31°26'13.636sE")) {
            check(t, "degrees, minutes and seconds")
        }
        for (t in listOf("S25°05'48.8s, E31°26'13.6s", "S25d05m48.8s E31d26m13.6s", "25 05 48.8s S 31 26 13.6s E",
                  "25°05'48.8S E 031°26'13.6")) {
            check(t, "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = 31.437111))
        }
        // Where nothing else says the hemispheres, an "s" may as well be south.
        for (t in listOf("25 05 48S 31 26 13S", "25°05'48.815s, 31°26'13.636s")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    @Test
    fun testDegreesWithTheirLetterRightAfterThem() {
        for (t in listOf("25.096893 degS 31.437121 degE", "25.096893 DegS, 31.437121 DegE", "25.096893 degreesS 31.437121 degreesE",
                  "Lat 25.096893 degS Lon 31.437121 degE", "25.096893degS 31.437121degE")) {
            check(t, "decimal degrees")
        }
        check("33.9249 degS 18.4241 degW", "decimal degrees", at = GeoPoint(lat = -33.9249, lon = -18.4241))
    }

    @Test
    fun testDegreesAndMinutesOnEachSideOfASlash() {
        // A letter on each side: the two halves of one position, not two read on their own.
        for (t in listOf("S25 05.8136 / E31 26.2273", "S 25 05.8136 / E 31 26.2273", "25 05.8136 S / 31 26.2273 E",
                  "25 05.8136S / 31 26.2273E", "S25 05.8136 / E31 26.2273 (Satara)")) {
            check(t, "degrees and decimal minutes", within = 2.0)
        }
        check("S24 59.400 / E31 35.400", "degrees and decimal minutes", at = GeoPoint(lat = -24.99, lon = 31.59))
    }

    @Test
    fun testADirectionAfterAPairWithSigns() {
        // Where an animal is heading, or a direction from a place, after a pair written with a minus sign or after a heading:
        // not the position's letter.
        val p = GeoPoint(lat = -24.996931, lon = 31.592312)
        for (t in listOf("Wild dogs at -24.996931, 31.592312 heading W towards Skukuza", "-24.996931, 31.592312 heading W",
                  "-24.996931, 31.592312, Heading 270° W", "-24.996931 31.592312 W 270°", "-24.996931, 31.592312 W of Skukuza",
                  "-24.996931, 31.592312 (W of the bridge)", "-24.996931, 31.592312 heading west", "-24.996931, 31.592312 Bearing W",
                  "-24.996931, 31.592312 heading N", "Leopard at -24.996931, 31.592312, heading S on H1-2",
                  "-24.996931, 31.592312, Heading 180° S", "-24.996931, 31.592312 heading south", "-24.996931, 31.592312 moving W")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = p)
            }
        }
        check("24.996931, 31.592312, Heading 180° S", "decimal degrees", at = GeoPoint(lat = 24.996931, lon = 31.592312))
        check("S24 59.816 E31 35.539 heading S", "degrees and decimal minutes", at = gps)
        check("40.6892, -74.0445 heading S", "decimal degrees", at = newYork)
    }

    @Test
    fun testPositionsBeforeOfOrFrom() {
        // A position with its letters, then "from Pieter" or "van Jaco": not a distance with its direction.
        for (t in listOf("Lions 24.99 S 31.59 E from Pieter", "Leeus 24.99 S 31.59 O van Skukuza", "S 24.99 E 31.59 of Satara",
                  "Lions at S24.99 E31.59 of the road", "Lions 24.99 S 31.59 E from camp 3 km", "Lions 24.99S 31.59E from Pieter")) {
            check(t, at = GeoPoint(lat = -24.99, lon = 31.59))
        }
        check("Wild dogs 25.10 S 31.44 E van Jaco", "decimal degrees", at = GeoPoint(lat = -25.1, lon = 31.44))
        for (t in listOf("Leeus 24 59.82 S 31 35.54 O van Jaco", "Lions 24 59.82 S 31 35.54 E from Skukuza")) {
            check(t, at = GeoPoint(lat = -24.997, lon = 31.592333))
        }
        check("Lions 24 59.8 S 31 35.5 E from Pieter", "degrees and decimal minutes", at = GeoPoint(lat = -24.996667, lon = 31.591667))
        // Distances with their direction still aren't positions.
        for (t in listOf("Lions 2.5 S of Satara, leopard 1.5 E of Satara", "Lions 2.25 N of Satara, leopard 3.75 E of Satara",
                  "Lions 2.5 N 3.5 E of Satara")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testAPhotosDetails() {
        // exiftool's lines, with the hemisphere on a line of its own before or after the value.
        val p = GeoPoint(lat = -24.996931, lon = 31.592311)
        for (t in listOf("GPS Version ID                  : 2.2.0.0\nGPS Latitude Ref                : South\n"
                    + "GPS Latitude                    : 24 deg 59' 48.95\"\nGPS Longitude Ref               : East\n"
                    + "GPS Longitude                   : 31 deg 35' 32.32\"",
                  "Latitude Ref: South\nLatitude: 24° 59' 48.95\"\nLongitude Ref: East\nLongitude: 31° 35' 32.32\"",
                  "[GPS] GPS Latitude Ref : South\n[GPS] GPS Latitude : 24 deg 59' 48.95\"\n[GPS] GPS Longitude Ref : East\n"
                    + "[GPS] GPS Longitude : 31 deg 35' 32.32\"",
                  "GPSLatitudeRef: S\nGPSLatitude: 24 deg 59' 48.95\"\nGPSLongitudeRef: E\nGPSLongitude: 31 deg 35' 32.32\"",
                  "GPS Latitude : 24 deg 59' 48.95\"\nGPS Latitude Ref : South\nGPS Longitude : 31 deg 35' 32.32\"\nGPS Longitude Ref : East",
                  "GPS Latitude Ref: S\nGPS Latitude: 24; 59; 48.95\nGPS Longitude Ref: E\nGPS Longitude: 31; 35; 32.32")) {
            check(t, "degrees, minutes and seconds", at = p)
        }
        check("GPS Latitude Ref : South\nGPS Latitude : 24.9969305555556\nGPS Longitude Ref : East\nGPS Longitude : 31.5923111111111",
              "decimal degrees", at = p)
        check("GPS Latitude Ref : North\nGPS Latitude : 40 deg 41' 21.12\"\nGPS Longitude Ref : West\nGPS Longitude : 74 deg 2' 40.20\"",
              "degrees, minutes and seconds", at = newYork)
        // Windows: degrees, minutes and seconds with semicolons, and no hemisphere.
        for (t in listOf("Latitude\t24; 59; 48.95\nLongitude\t31; 35; 32.32",
                  "Latitude 24; 59; 48.9500000000006\nLongitude 31; 35; 32.3200000000006\nAltitude 285.4")) {
            check(t, "degrees, minutes and seconds", at = GeoPoint(lat = 24.996931, lon = 31.592311))
        }
        assertNull(CoordinateParser.parse("Latitude\t24; 59\nLongitude\t31; 35"))
    }

    @Test
    fun testBothLettersTogether() {
        // The halves' letters written together before or after the numbers, in the halves' order.
        val p = GeoPoint(lat = -24.99693, lon = 31.59232)
        for (t in listOf("GPS (S/E): 24.99693, 31.59232", "Coords (S, E): 24.99693 / 31.59232", "GPS: S/E 24.99693 31.59232",
                  "24.99693 / 31.59232 (S/E)", "24.99693, 31.59232 (S, E)", "Sighting at 24.99693, 31.59232 S/E",
                  "Lat/Lon (S/E): 24.99693, 31.59232", "24.99693 S/E 31.59232", "S/E: -24.99693, 31.59232")) {
            check(t, "decimal degrees", at = p)
        }
        check("N/W: 40.6892, 74.0445", "decimal degrees", at = newYork)
        check("S/E 24 59.816 31 35.539", "degrees and decimal minutes", at = gps)
        // Not with a minus sign that says otherwise, nor a pair read on its own from a text that only as a whole says them.
        for (t in listOf("S/E: 24.99693, -31.59232", "Lions (S/E): 24.99693, 31.59232, 3 males")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    @Test
    fun testWindsAndSpeeds() {
        // A wind's direction and speed (in knots, Beaufort, mph or none) isn't a position, nor is it beside one.
        for (t in listOf("Wind S 10 kt, later E 15 kt", "Wind S 15-20 kts, becoming E 25 kts", "Wind: S 15 kt, E 10 kt later",
                  "Breeze from S 12 knots then E 18 knots", "Wind S 4 Bft, E 5 Bft later", "Today: Wind S 24, tomorrow E 31",
                  "Windrigting S 24 knope, later O 31 knope", "Durban: wind S 25 kt; Richards Bay: wind E 30 kt",
                  "Wind S 10 mph, later E 15 mph")) {
            for (near in listOf(null, kruger)) {
                assertNull(t, CoordinateParser.parse(t, near = near))
            }
        }
        check("Lions at S24 59.815 E31 35.539. Wind S 10 kt, later E 15 kt", "degrees and decimal minutes", at = ddm)
    }

    // Inputs checked an eighth time, by family. Near Satara: 24° 22.872' S, 31° 50.736' E.
    private val satara = GeoPoint(lat = -24.3812, lon = 31.8456)
    private val signed = GeoPoint(lat = -24.996931, lon = 31.592312)

    @Test
    fun testADirectionAsAThirdLetter() {
        // A direction from a place after a position with its letters in front, or of travel before one with its letters
        // after its numbers: not one of its letters, whatever else is around it.
        for (t in listOf("Lions S24 59 48.9 E31 35 32.3 N of camp", "Lions S24 59 48.9 E31 35 32.3 N of camp, 3 males",
                  "S24 59 48.9 E31 35 32.3 N of Skukuza 06:45", "Lions S24 59 48.9 E31 35 32.3 S of Satara",
                  "Leeus S24 59 48.9 E31 35 32.3 noord van Skukuza", "Lions S24 59 48.9 E31 35 32.3 north of camp",
                  "S24 59 48.9 E31 35 32.3\nN of camp\nwind E 15 kts", "Lions heading W 24 59 48.9 S 31 35 32.3 E",
                  "Lions walking W 24°59'48.9\"S 31°35'32.3\"E", "Lions walking N 24°59'48.9\"S 31°35'32.3\"E")) {
            for (near in listOf(null, kruger)) {
                check(t, "degrees, minutes and seconds", near = near, at = dms)
            }
        }
        check("S25 07 10.7 E31 55 03.4 S of the dam", "degrees, minutes and seconds", at = GeoPoint(lat = -25.1196389, lon = 31.9176111))
        check("Lions walking W 24 22 52.3 S 31 50 44.2 E", "degrees, minutes and seconds", at = GeoPoint(lat = -24.3811944, lon = 31.8456111))
        for (t in listOf("Leopard on H4-1 km 3: S24 59.816 E31 35.539 N side", "Wind S 10 kt. Lions S24 59.816 E31 35.539 N of the dam",
                  "Lions S24 59.816 E31 35.539 N of the dam", "Lions walking W 24 59.816 S 31 35.539 E",
                  "Wild dogs moving E 24 59.816 S 31 35.539 E", "Leeus stap W 24 59.816 S 31 35.539 O",
                  "Leeus loop O 24 59.816 S 31 35.539 O", "Lions walking W 24°59.816'S 31°35.539'E",
                  "Wild dogs moving N 24 59.816 S 31 35.539 E", "Leopard going S 24 59.816 S 31 35.539 E")) {
            for (near in listOf(null, kruger)) {
                check(t, near = near, at = gps)
            }
        }
        check("Lions on H1-3 km 14 S25 07.178 E31 55.057 N of camp", "degrees and decimal minutes",
              at = GeoPoint(lat = -25.1196333, lon = 31.9176167))
        check("Lions S24 59.8 E31 35.5 N of camp", "degrees and decimal minutes", at = GeoPoint(lat = -24.9966667, lon = 31.5916667))
        for (t in listOf("Ellies crossing, moving E 24 22.872 S 31 50.736 E", "Lions 2 km W 24 22.872 S 31 50.736 E",
                  "Lions W 24 22.872 S 31 50.736 E", "Lions walking W\n24 22.872 S 31 50.736 E", "Leeus beweeg W 24 22.872 S 31 50.736 O")) {
            check(t, at = satara)
        }
        for (t in listOf("Lions walking W 24.99693 S 31.59231 E", "Wild dogs moving E 24.99693 S, 31.59231 E", "Heading E 24.99693 S, 31.59231 E")) {
            check(t, "decimal degrees", at = typed)
        }
        // So too in a long chat log, where only the pairs in it are read.
        val log = "[12:31, 27/10/2026] Jaco: Leaving at 5 p.m., 2 x 2 litres of water, 3 x 1 kg of ice\n".repeat(20)
        check(log + "Lions walking W 24 59.816 S 31 35.539 E", "degrees and decimal minutes", at = gps)
        check(log + "Lions S24 59.816 E31 35.539 N of the dam", "degrees and decimal minutes", at = gps)
        // A letter glued to the seconds is their mark, not a direction after the position.
        check("S 25°05'48.815\" E 31°26'13.636s", "degrees, minutes and seconds")
    }

    @Test
    fun testAHeadingOrAWindBeforeAPosition() {
        // A heading that is only a direction, with a stop or a colon after it, doesn't label the position's first number;
        // with its angle after it, that is its value.
        for (t in listOf("Lions heading W: 24 59.816 S 31 35.539 E", "Heading W. 24 59.816 S 31 35.539 E", "Lions heading W. -24 59.816, 31 35.539",
                  "Bearing W: 24 59.816 S 31 35.539 E", "Course W. 24 59.816 S 31 35.539 E")) {
            check(t, "degrees and decimal minutes", at = gps)
        }
        check("Wild dogs heading W. 24 59 48.9 S 31 35 32.3 E", "degrees, minutes and seconds", at = dms)
        check("Heading W. 24 22.872 S 31 50.736 E", "degrees and decimal minutes", at = satara)
        check("Heading W. -25.096893, 31.437121. 45° bearing", "decimal degrees")
        check("Lions -24 22.872, 31 50.736 heading W: 45° bearing", "degrees and decimal minutes", at = satara)
        check("Lions -24.3812, 31.8456, course S: 3 males", "decimal degrees", at = satara)
        // A minus sign right after a bearing's degrees is the position's.
        for (t in listOf("Bearing 270° -25.096893, 31.437121", "Bearing 270° -25.096893, 31.437121, wind E 15 kts")) {
            check(t, "decimal degrees")
        }
        // A letter glued to a number with minutes, or followed by a number only a coordinate has, is a position's, not
        // the wind's.
        for (t in listOf("Lions out of the wind S24 59.816 E31 35.539", "Strong wind S24 59.816 E31 35.539", "Lots of wind S24 59.816 E31 35.539",
                  "Lions out of the wind S24°59.816' E31°35.539'", "Leeus uit die wind S24 59.816 O31 35.539",
                  "Breeze S24 59.816 E31 35.539")) {
            for (near in listOf(null, kruger)) {
                check(t, near = near, at = gps)
            }
        }
        for (t in listOf("Lions out of the wind S 24.99693 E 31.59231", "Wind: S 24.99693 E 31.59231")) {
            check(t, "decimal degrees", at = typed)
        }
    }

    @Test
    fun testDirectionsNextToAPairWithSigns() {
        // A direction from a place or of travel, in one letter or two, before or after a pair written with a minus sign,
        // doesn't change a sign; a distance with two letters and "of" isn't their letters either.
        for (t in listOf("Lions 3 S/W of Satara -24.99693, 31.59231", "Lions 3 N/E of Satara -24.99693, 31.59231",
                  "Lions 3 S/E of Satara -24.99693, 31.59231", "Lions 2 S/W of the bridge at -24.99693 31.59231",
                  "Leopard 5 S, W of camp -24.99693, 31.59231", "Lions -24.99693, 31.59231, 3 S/W of Satara",
                  "Lions 1 S/W of Skukuza: -24.99693, 31.59231, 06:45", "Leeus 3 S/W van Satara -24.99693, 31.59231",
                  "-24.99693, 31.59231 S/W", "-24.99693, 31.59231 (S/W of camp)", "Lions -24.99693, 31.59231 S/W of Satara")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = typed)
            }
        }
        for (t in listOf("2 lions -24.996931, 31.592312 (S/E)", "06:45 -24.996931, 31.592312 (S/E)",
                  "[07:15, 27/10/2026] Jaco: -24.996931, 31.592312 (S/E)", "Wild dogs moving W -24.996931, 31.592312",
                  "Lions heading W -24.996931, 31.592312", "Heading W -24.996931, 31.592312", "Leeus stap W -24.996931, 31.592312",
                  "Lions walking W\n-24.996931, 31.592312", "Lions (moving W) -24.996931, 31.592312", "Rhino 2 km W -24.996931, 31.592312",
                  "Lions 500 m W -24.996931, 31.592312", "Lions moving W -24,996931; 31,592312", "Leeus beweeg O -24.996931, 31.592312",
                  "Leeus loop O -24.996931 31.592312")) {
            for (near in listOf(null, kruger)) {
                check(t, near = near, at = signed)
            }
        }
        check("Lions moving W -24°59.816', 31°35.539'", "degrees and decimal minutes", at = gps)
        check("N4 W to Nelspruit, -25.4753, 30.9694", "decimal degrees", at = GeoPoint(lat = -25.4753, lon = 30.9694))
        check("R536 W to Hazyview -25.0476 31.1295", "decimal degrees", at = GeoPoint(lat = -25.0476, lon = 31.1295))
        for (t in listOf("S1 W -25.0012, 31.5409", "Gate 3 W -25.0012, 31.5409")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -25.0012, lon = 31.5409))
        }
        // So too in a long chat log, where only the pairs in it are read.
        val log = "[12:31, 27/10/2026] Jaco: Leaving at 5 p.m., 2 x 2 litres of water, 3 x 1 kg of ice\n".repeat(20)
        for (t in listOf("Wild dogs moving W -24.996931, 31.592312", "-24.996931, 31.592312 W of the dam", "-24.996931, 31.592312 (S/W)")) {
            check(log + t, "decimal degrees", at = signed)
        }
        // A W after the last number of a half without a minus sign, with a space before it, may as well be its hemisphere;
        // with a minus sign it says the same. Written right after the number it is the hemisphere.
        for (t in listOf("-33.4489, 70.6693 W", "-34.6037, 58.3816 W", "-0.180653, 78.467834 W", "Lat -33.4489 Lon 70.6693 W",
                  "-24 59.816, 31 35.539 W")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        check("40 41.352, -74 2.670 W", "degrees and decimal minutes", at = newYork)
        check("-33.4489 70.6693W", "decimal degrees", near = kruger, at = GeoPoint(lat = -33.4489, lon = -70.6693))
        check("-25°05'48.8\", 31°26'13.6\"W", "degrees, minutes and seconds", at = GeoPoint(lat = -25.096889, lon = -31.437111))
        // Both letters after a pair without a minus sign, read only with the whole text: not when other numbers are before it.
        for (t in listOf("3 lions at 24.99693, 31.59232 S/E", "06:45 sighting at 24.99693, 31.59232 S/E", "06:45 GPS (S/E): 24.99693, 31.59232")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
    }

    @Test
    fun testAPhotosDetailsWithMoreLines() {
        // exiftool's default output: the same place as latitude and longitude lines and as a position line, with its
        // altitude and time before them.
        val pad = { label: String -> label + " ".repeat(32 - label.length) + ": " }
        val lines = pad("GPS Latitude") + "24 deg 59' 48.95\" S\n" + pad("GPS Longitude") + "31 deg 35' 32.32\" E\n" + pad("GPS Position") +
            "24 deg 59' 48.95\" S, 31 deg 35' 32.32\" E"
        val photo = GeoPoint(lat = -24.996931, lon = 31.592311)
        for (t in listOf(lines, pad("GPS Altitude") + "285.4 m Above Sea Level\n" + pad("GPS Date/Time") + "2026:10:28 04:45:12Z\n" + lines,
                  "Latitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E\nPosition: 24°59'48.95\" S, 31°35'32.32\" E",
                  "GPS Altitude : 285.4 m Above Sea Level\nGPS Latitude : 24 deg 59' 48.95\" S\nGPS Longitude : 31 deg 35' 32.32\" E",
                  "Altitude: 285 m\nLatitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E",
                  "Elevation 285 m\nLat 24°59'48.95\"S\nLon 31°35'32.32\"E", "Accuracy: 5 m\nLat: 24°59'48.95\" S\nLong: 31°35'32.32\" E",
                  "Altitude 285 m, Latitude 24°59'48.95\" S, Longitude 31°35'32.32\" E")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("GPS Latitude : 24.996931 S\nGPS Longitude : 31.592311 E\nGPS Position : 24.996931 S, 31.592311 E", "decimal degrees",
              at = photo)
        check("Altitude: 285.4 m\nLatitude: 24° 59.816' S\nLongitude: 31° 35.539' E", "degrees and decimal minutes", at = gps)
        // Two different places so written aren't read.
        assertNull(CoordinateParser.parse("Latitude: 24°59'48.95\" S\nLongitude: 31°35'32.32\" E\n"
                                            + "Position: 25°05'48.8\" S, 31°26'13.6\" E"))
        // A waypoint's or camp's number before labels with the letters after the values.
        check("Camp 14 lat 24.99693 S lon 31.59231 E", "decimal degrees", at = typed)
        for (t in listOf("WP 12 Lat 24°59.816'S Lon 31°35.539'E", "Waypoint 12 Lat 24 59.816 S Lon 31 35.539 E")) {
            check(t, "degrees and decimal minutes", at = gps)
        }
        // A long run of spaces or tabs after a "Ref" label, and a chat log with two letters together in it, are read
        // quickly.
        val start = System.nanoTime()
        for (t in listOf("Latitude Ref" + " ".repeat(20_000) + "x 1", "Lat ref" + "\t".repeat(2_000) + "x 1",
                  "GPS Latitude Ref: S\nGPS Latitude" + " ".repeat(2_000) + "x")) {
            assertNull(CoordinateParser.parse(t))
        }
        val log = ("[12:31, 27/10/2026] Jaco: Only 4x4 on the S100 after the rain\n[12:33, 27/10/2026] Jaco: "
                         + "Leopard S/E of Satara\n").repeat(150)
        check(log + "[13:01, 27/10/2026] Jaco: lions at -25.0969, 31.4371", "decimal degrees", at = GeoPoint(lat = -25.0969, lon = 31.4371))
        assertLessThan(secondsSince(start), 3.0)
    }

    @Test
    fun testCountsAndTemperaturesBeforeAGarminPosition() {
        // A temperature, or a count or waypoint's number, before degrees and minutes written as the other half is: not
        // their first field.
        for (t in listOf("Hot today 32° 24 22.872 S 31 50.736 E", "Lions 32° 24 22.872 S 31 50.736 E", "Lions (32°) 24 22.872 S 31 50.736 E",
                  "Lions 18° 24 22.872 S 31 50.736 E", "Lions 32 deg 24 22.872 S 31 50.736 E", "Leeus 32 grade 24 22.872 S 31 50.736 O",
                  "Lions 3 24 22.872 S 31 50.736 E", "Pride of 6 24 22.872 S 31 50.736 E", "017 24 22.872 S 31 50.736 E",
                  "WP 017 24 22.872 S 31 50.736 E 285 m", "#3 24 22.872 S 31 50.736 E", "No. 3 24 22.872 S 31 50.736 E",
                  "Temp 32 24 22.872 S 31 50.736 E")) {
            for (near in listOf(null, kruger)) {
                check(t, near = near, at = satara)
            }
        }
        check("Lions 32° 24.3812 S 31.8456 E", "decimal degrees", at = satara)
        // Written otherwise, they are.
        check("24° 22 52.3 S 31 50.736 E", "degrees, minutes and seconds", at = GeoPoint(lat = -24.3811944, lon = 31.8456))
        assertNull(CoordinateParser.parse("Lions 3 24 22.8 S 31 50.7 E"))
    }

    @Test
    fun testUnitsWithALetterRightAfterThem() {
        // Seconds or minutes in words with the direction right after them; "secS" is south when another unit has its own.
        val photo = GeoPoint(lat = -24.9969306, lon = 31.5923111)
        for (t in listOf("24°59min48.95secS, 31°35min32.32secE", "24d59min48.95 secS, 31d35min32.32 secE",
                  "24°59min48.95secondsS 31°35min32.32secondsE", "24°59min48.95 sS 31°35min32.32 sE", "24°59'48.95secS, 31°35'32.32secE")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("24d59.816 minS, 31d35.539 minE", "degrees and decimal minutes", at = gps)
        check("40°41min21.1secN 74°02min40.2secW", "degrees, minutes and seconds", at = GeoPoint(lat = 40.6891944, lon = -74.0445))
        check("25°05min48.8secS 31°26min13.6secE", "degrees, minutes and seconds", at = GeoPoint(lat = -25.0968889, lon = 31.4371111))
        // "de" isn't a unit with its letter.
        for (t in listOf("Lat: -33.4489 Lon: -70.6693 de Santiago", "Latitud: -33.4489 Longitud: -70.6693 de Santiago 06:45")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -33.4489, lon = -70.6693))
        }
        // The degree sign written after the minutes: one coordinate, not a position.
        for (t in listOf("25 05.8136degS", "25° 05.8136 degS", "25 05.8136 deg S")) {
            assertNull(t, CoordinateParser.parse(t))
        }
    }

    // Inputs checked a ninth time, by family. A photo's position: 24° 59' 48.95" S, 31° 35' 32.32" E.
    private val photo = GeoPoint(lat = -24.9969306, lon = 31.5923111)

    @Test
    fun testUnitsWithAFullStopOrInThePlural() {
        // The full stop of "min." or "sec." is part of the word, not between the seconds and their letter.
        for (t in listOf("24°59min48.95 sec. S, 31°35min32.32 sec. E", "24d 59 min 48.95 sec. S, 31d 35 min 32.32 sec. E",
                  "24d59 min.48.95 secs S, 31d35 min.32.32 secs E", "-24d59 min.48.95\", 31d35 min.32.32\"",
                  "24°59'48.95 sec. S, 31°35'32.32 sec. E")) {
            for (near in listOf(null, kruger)) {
                check(t, "degrees, minutes and seconds", near = near, at = photo)
            }
        }
        for (t in listOf("24d59.816 min. S, 31d35.539 min. E", "24 d 59.816 min., S; 31 d 35.539 min., E",
                  "24 deg 59.816 min. S, 31 deg 35.539 min. E", "24 deg. 59.816 min. S, 31 deg. 35.539 min. E")) {
            check(t, "degrees and decimal minutes", at = gps)
        }
        // "Mins" and "secs" are plurals where the text has its N or S, or a number of seconds follows.
        check("25°05 mins 48.8 seconds S, 31°26 mins 13.6 secondsE", "degrees, minutes and seconds",
              at = GeoPoint(lat = -25.0968889, lon = 31.4371111))
        check("S 25 deg 05 mins 48.8 secs, E 31 deg 26 mins 13.6 secsE", "degrees, minutes and seconds",
              at = GeoPoint(lat = -25.0968889, lon = 31.4371111))
        for (t in listOf("-24°59 mins 48.95 secs, 31°35 mins 32.32 secE", "S 24°59 mins 48.95 secs, E 31°35 mins 32.32 secE")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("33°26 mins56 secondsS, 70°40 mins9.5 secondsW", "degrees, minutes and seconds",
              at = GeoPoint(lat = -33.4488889, lon = -70.6693056))
    }

    @Test
    fun testSecondsOrMinutesMarkedWithLettersNextToOtherText() {
        // Seconds marked with an s before "from Pieter": not a distance with its direction.
        for (t in listOf("S 24°59'48.95s E 31°35'32.32s from Pieter", "S 24°59'48.95s E 31°35'32.32s van Jaco")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("24°59'48.9S 31°35'32.3E from Pieter", "degrees, minutes and seconds", at = dms)
        check("S24-59-49.0 E031-35-32.3 N of camp", "degrees, minutes and seconds", at = GeoPoint(lat = -24.9969444, lon = 31.5923056))
        // A unit glued to the next field.
        for (t in listOf("S24°59 mins48.95″, E31°35 mins32.32″", "24 deg59 min 48.95″ S, 31 deg35 min 32.32″ E")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("40 deg41.352m -74 deg02.670m", "degrees and decimal minutes", at = newYork)
        // Minutes marked with an m, or degrees in words glued to them, before or after a slash.
        for (t in listOf("S24 59.816m / E31 35.539m", "S 24 59.816M / E 31 35.539M", "24 deg59.816' S / 31 deg35.539' E",
                  "24 deg59.816' S, 31 deg35.539' E")) {
            check(t, "degrees and decimal minutes", at = gps)
        }
        check("S25 05.8136m / E31 26.2273m", "degrees and decimal minutes", within = 2.0)
        check("N40 41.352m / W74 02.670m", "degrees and decimal minutes", at = newYork)
    }

    @Test
    fun testTemperaturesBeforeAndBesideAPosition() {
        // A temperature right before a letter in front of a number with decimals isn't that letter's degrees, nor a
        // wind after it a third letter.
        for (t in listOf("Hot 32° S 24.996931 E 31.592312, wind S 10 kt", "Leopard 32° S 24.996931 E 31.592312, wind S 10 kt",
                  "Leopard 32° S 24.996931 E 31.592312, wind 10 kt", "Hot 32° S 24.996931 E 31.592312")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = signed)
            }
        }
        // Nor the degrees of a position written in decimals on the other half.
        assertNull(CoordinateParser.parse("Leopard 32° 40.6892, -74.0445 Wind E 15 kt"))
        // Highs and lows on their own aren't a position, with a minus sign or a letter they are.
        for (t in listOf("34°/24°", "34 grade, 24 grade", "34 deg\n24 deg", "10 grade, 31 degrees", "#31, 24 deg", "34.5°/24.5°", "25° 31°")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        for (t in listOf("-25° 31°", "-25°, 31°", "25° S 31° E")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -25.0, lon = 31.0))
        }
        check("-25° 31.5°", "decimal degrees", at = GeoPoint(lat = -25.0, lon = 31.5))
        assertNull(CoordinateParser.parse("25° 05.8136 degS"))
    }

    @Test
    fun testADirectionOfTravelBeforeAPairWithSigns() {
        // A direction of travel before a pair with a minus sign, after other words or a heading, whichever it is.
        for (t in listOf("Lions heading N -24.9969 31.5923", "Lions heading E -24.9969 31.5923", "Lions going N -24.9969 31.5923",
                  "Heading N -24.9969 31.5923")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = GeoPoint(lat = -24.9969, lon = 31.5923))
            }
        }
        check("Lions on S25 heading E -25.3601 31.8932", "decimal degrees", at = GeoPoint(lat = -25.3601, lon = 31.8932))
        assertNull(CoordinateParser.parse("N -25.0969, 31.4371"))
    }

    @Test
    fun testAHeadingOrAWindWithoutAPositionsNumber() {
        // A heading's label with only a direction, or with nothing, before the position's labels.
        for (t in listOf("Heading: W\nLatitude: 24.99693 S\nLongitude: 31.59231 E", "Course: S\nLat: 24.99693 S\nLon: 31.59231 E",
                  "Lions heading W Lat 24.99693 S Lon 31.59231 E", "Heading:\nLatitude: 24.99693 S\nLongitude: 31.59231 E")) {
            check(t, "decimal degrees", at = typed)
        }
        check("Bearing: SW\nLat 24 59.816 S\nLon 31 35.539 E", "degrees and decimal minutes", at = gps)
        check("Heading: W\nLatitude: 24°59'48.95\"S\nLongitude: 31°35'32.32\"E", "degrees, minutes and seconds", at = photo)
        // After "wind", a letter before a number with decimals is a position's: a wind's speed is a whole number.
        for (t in listOf("Lions out of the wind S 24.99 E 31.59", "Wind: S 24.99 E 31.59", "Wind S 24.99, E 31.59")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.99, lon = 31.59))
        }
        assertNull(CoordinateParser.parse("Wind S 24.9, E 31.5 (km/h)"))
    }

    @Test
    fun testHemispheresInAHeadingOrAColumn() {
        // The hemispheres once in a heading, or in a header row's names, for the values under it.
        for (t in listOf("Lat (S) Long (E): 24.99693 31.59231", "Lat (S)\tLon (E)\n24.99693\t31.59231", "Lat (S), Long (E)\n24.99693, 31.59231",
                  "Lat (S) / Long (E)\n24.99693 / 31.59231", "Lat(S)\tLong(E)\n24.99693\t31.59231", "Lat S\tLong E\n24.99693\t31.59231",
                  "S\tE\n24.99693\t31.59231", "Site,Lat (S),Long (E)\nSkukuza,24.99693,31.59231",
                  "Name;Latitude (South);Longitude (East)\nLeopard;24,99693;31,59231", "Site\tS\tE\nSkukuza\t24.99693\t31.59231",
                  "Camp | Lat (S) | Long (E)\n---|---|---\nSkukuza | 24.99693 | 31.59231", "Lon (E) Lat (S): 31.59231 24.99693",
                  "Site S E 24.99693 31.59231", "24.99693, 31.59231 S E")) {
            for (near in listOf(null, kruger)) {
                check(t, "decimal degrees", near = near, at = typed)
            }
        }
        check("Camp\tLatitude (S)\tLongitude (E)\nSkukuza\t24°59'47.3\"\t31°35'27.6\"", "degrees, minutes and seconds",
              at = GeoPoint(lat = -24.9964722, lon = 31.591))
        check("Site\tLat (S)\tLong (E)\nSkukuza\t24 59.816\t31 35.539", "degrees and decimal minutes", at = gps)
        assertNull(CoordinateParser.parse("Site,Lat (S),Long (E)\nSkukuza,24.99693,31.59231\nSatara,24.3812,31.8456"))
        // Or in columns or fields of their own, as a GPS logger writes them.
        for (t in listOf("INDEX,RCR,DATE,TIME,VALID,LATITUDE,N/S,LONGITUDE,E/W,HEIGHT,SPEED\n"
                    + "1,T,2026/10/28,06:45:12,SPS,24.996931,S,31.592312,E,268.4 M,0.0 km/h",
                  "LATITUDE,N/S,LONGITUDE,E/W\n24.996931,S,31.592312,E", "lat\tns\tlon\tew\n24.996931\tS\t31.592312\tE",
                  "24.996931;S;31.592312;E")) {
            check(t, "decimal degrees", at = signed)
        }
        for (t in listOf("N/S: S\nLat: 24.9969\nE/W: E\nLon: 31.5923", "Hemisphere: South\nLatitude: 24.9969\nLongitude: 31.5923")) {
            check(t, "decimal degrees", at = GeoPoint(lat = -24.9969, lon = 31.5923))
        }
    }

    @Test
    fun testPhotoDetailsFromOtherTools() {
        // libexif: the hemispheres titled "North or South Latitude", and degrees, minutes and seconds with commas.
        for (t in listOf("North or South Latitude\tS\nLatitude\t24, 59, 48.95\nEast or West Longitude\tE\nLongitude\t31, 35, 32.32\n"
                    + "Altitude Reference\tSea level\nAltitude\t268.40",
                  "North or South Latitude: S\nLatitude: 24, 59, 48.95\nEast or West Longitude: E\nLongitude: 31, 35, 32.32",
                  "North or South Latitude: South\nLatitude: 24 59 48.95\nEast or West Longitude: East\nLongitude: 31 35 32.32")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        check("Latitude: 24, 59, 48.95\nLongitude: 31, 35, 32.32", "degrees, minutes and seconds",
              at = GeoPoint(lat = 24.9969306, lon = 31.5923111))
        // "Tag - Value": the dash is a separator when a value has its own sign or letter, or a Ref line gives it; else it
        // may as well be a minus sign.
        for (t in listOf("GPSLatitudeRef - S\nGPSLatitude - 24 59 48.95\nGPSLongitudeRef - E\nGPSLongitude - 31 35 32.32",
                  "GPS Latitude Ref - South\nGPS Latitude - 24 deg 59' 48.95\"\nGPS Longitude Ref - East\nGPS Longitude - 31 deg 35' 32.32\"")) {
            check(t, "degrees, minutes and seconds", at = photo)
        }
        for (t in listOf("GPSLatitudeRef - S\nGPSLatitude - 24.996931\nGPSLongitudeRef - E\nGPSLongitude - 31.592312",
                  "Lat - -24.996931\nLon - 31.592312", "Latitude - -24.996931 | Longitude - 31.592312")) {
            check(t, "decimal degrees", at = signed)
        }
        for (t in listOf("Y - -24.99693, X - 31.59231", "Latitude - 24.99693 S\nLongitude - 31.59231 E", "Latitude – 24.99693 S\nLongitude – 31.59231 E")) {
            check(t, "decimal degrees", at = typed)
        }
        for (t in listOf("Lat - 24.9969, Long - 31.5923", "Lat/Long - 24.9969 / 31.5923")) {
            assertNull(t, CoordinateParser.parse(t))
        }
        check("Lat: - 24.9969 Lon: 31.5923", "decimal degrees", at = GeoPoint(lat = -24.9969, lon = 31.5923))
        // exiftool -n with its Composite lines: the same place unsigned with its Ref lines, and signed.
        val pad = { label: String -> label + " ".repeat(32 - label.length) + ": " }
        val exif = pad("GPS Latitude Ref") + "S\n" + pad("GPS Latitude") + "24.9969305555556\n" + pad("GPS Longitude Ref") + "E\n" +
            pad("GPS Longitude") + "31.5923111111111\n"
        for (t in listOf(exif + pad("GPS Latitude") + "-24.9969305555556\n" + pad("GPS Longitude") + "31.5923111111111",
                  exif + pad("GPS Position") + "-24.9969305555556 31.5923111111111")) {
            check(t, "decimal degrees", at = photo)
        }
    }

    @Test
    fun testRoadsCountsDatesAndWaypointNumbers() {
        // A road's name and a count, a direction and a temperature: no position.
        for (t in listOf("Lions on S28 3 females, heading E 31 deg", "On S25 2 lions, wind E 31 deg", "S25 3 rhino moving W 31 degrees",
                  "op die S28 2 leeus loop wes 31 grade", "S1 3 lions walking W 30 deg", "Wild dogs heading E 31 deg on the S28 25 of them",
                  "11 females op die N4 27 hyenas 11h28 moving W 41 deg")) {
            assertNull(t, CoordinateParser.parse(t, near = kruger))
        }
        check("Lions S25 05 E31 26", "degrees and decimal minutes", at = GeoPoint(lat = -25.083333, lon = 31.433333))
        // Labelled values with degree signs, and a date, a count or a fraction elsewhere.
        for (t in listOf("Lat: -24.99693° Long: 31.59231° on 28/10", "Latitude -24.99693°, Longitude 31.59231° (2/3 cubs)",
                  "Lat: -24.99693°\nLong: 31.59231°\nAlt: 268 m\nAccuracy: 3 m\nSats: 12/24", "Breedte: -24.99693° Lengte: 31.59231° op 28/10",
                  "Lat: -24.99693° Long: 31.59231° rating 5/5", "Lat: -24.99693° Long: 31.59231° 24/7 open")) {
            check(t, "decimal degrees", at = typed)
        }
        // An OziExplorer waypoint file, and its rows: the waypoint's number before the pair, its date after it.
        for (t in listOf("OziExplorer Waypoint File Version 1.1\nWGS 84\nReserved 2\nReserved 3\n   1,001             , -24.996931,  31.592312,"
                    + "45958.28125, 0, 1, 3,         0,     65535,Leopard                                 , 0, 0, 0,     879",
                  "1,001, -24.996931,  31.592312", "001, -24.996931, 31.592312")) {
            check(t, "decimal degrees", at = signed)
        }
    }

    @Test
    fun testUnitsBeforeTheirValueAndLettersAfterAPair() {
        // A unit right after a coordinate with its own value after it is that value's ("…, 31.592312 min 5"); after a
        // whole number it is that number's ("Wind 15 kt 24 59.816 S …", "Lions 500 m -24.996931, …").
        for (t in listOf("-24.996931, 31.592312 min 5", "-24.996931, 31.592312 hrs 2", "-24.996931, 31.592312 miles 3",
                  "Lions 500 m -24.996931, 31.592312")) {
            check(t, "decimal degrees", at = signed)
        }
        check("Wind 15 kt 24 59.816 S 31 35.539 E", "degrees and decimal minutes", at = gps)
        // A bearing's or zoom's value before a heading of both labels, and a heading's direction before a pair read on its own.
        for (t in listOf("Bearing 270° Lat/Long: -24.996931, 31.592312", "Zoom 15 Lat/Long: -24.996931, 31.592312")) {
            check(t, "decimal degrees", at = signed)
        }
        check("Heading W 40.6892, -74.0445 2 males", "decimal degrees", at = newYork)
        // One N or S after a pair with a minus sign may as well be the second half's ("31.4371, -25.0969 S"): not read,
        // unless a label or a heading names the halves.
        for (t in listOf("-24.996931, 31.592312 N", "40.6892, -74.0445 S", "-33.4489, -70.6693 S")) {
            for (near in listOf(null, kruger)) {
                assertNull(t, CoordinateParser.parse(t, near = near))
            }
        }
        for (t in listOf("Lat/Long: -24.996931, 31.592312 N", "Lat: -24.996931 Lon: 31.592312 S")) {
            check(t, "decimal degrees", at = signed)
        }
        // The only letter, with a place after it, is a direction; one after a half when the other has its own isn't.
        check("24.996931, 31.592312 (W of the bridge)", "decimal degrees", at = GeoPoint(lat = 24.996931, lon = 31.592312))
        check("24.99 N 31.59 W from Pieter", "decimal degrees", at = GeoPoint(lat = 24.99, lon = -31.59))
    }

    /** Every input the checks so far have found (Fixtures/coordinate-corpus.json), each variant on its own, with what it
     *  must read as: a point within a distance, null, or either. A case that names no position of yours is read both without
     *  one and near the Kruger. Lists every case that gives a wrong point or refuses a point it must give. */
    @Test
    fun testKnownInputsCorpus() {
        class Point(val lat: Double, val lon: Double, val within: Double)
        class Case(val input: String, val near: List<Double>?, val expect: Point?, val nilAllowed: Boolean, val source: String)
        val cases = Json.parseToJsonElement(fixture("coordinate-corpus.json").decodeToString()).jsonArray.map { element ->
            val o = element.jsonObject
            val near = o["near"]?.takeIf { it != JsonNull }?.jsonArray?.map { it.jsonPrimitive.double }
            val expect = o["expect"]?.takeIf { it != JsonNull }?.jsonObject?.let {
                Point(it.getValue("lat").jsonPrimitive.double, it.getValue("lon").jsonPrimitive.double, it.getValue("within").jsonPrimitive.double)
            }
            Case(
                input = o.getValue("input").jsonPrimitive.content, near = near, expect = expect,
                nilAllowed = o.getValue("nilAllowed").jsonPrimitive.boolean, source = o.getValue("source").jsonPrimitive.content,
            )
        }
        val misread = mutableListOf<String>()
        for (c in cases) {
            val nears = c.near?.let { listOf(GeoPoint(lat = it[0], lon = it[1])) } ?: listOf(null, GeoPoint(lat = -24.99, lon = 31.59))
            for (near in nears) {
                var wrong: String? = null
                val r = CoordinateParser.parse(c.input, near = near)
                val e = c.expect
                if (r != null) {
                    val read = "read as ${r.point.lat}, ${r.point.lon} (${r.format})"
                    if (e != null) {
                        val d = Geo.distance(r.point, GeoPoint(lat = e.lat, lon = e.lon))
                        if (d >= e.within) wrong = read + ", ${d.toInt()} m from ${e.lat}, ${e.lon}"
                    } else {
                        wrong = "$read, not nil"
                    }
                } else if (e != null && !c.nilAllowed) {
                    wrong = "not read, must be ${e.lat}, ${e.lon}"
                }
                if (wrong != null) {
                    val shown = if (c.input.length > 120) c.input.take(120) + "… (${c.input.length} characters)" else c.input
                    misread.add("[${c.source}] ${Json.encodeToString(String.serializer(), shown)} near ${near?.let { "${it.lat}, ${it.lon}" } ?: "nil"}: $wrong")
                }
            }
        }
        assertTrue("${misread.size} misread of ${cases.size} cases:\n" + misread.joinToString("\n"), misread.isEmpty())
    }
}
