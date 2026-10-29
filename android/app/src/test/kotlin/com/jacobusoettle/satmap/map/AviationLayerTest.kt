package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.AviationIndex
import com.jacobusoettle.satmap.core.AviationPoint
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Point
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drone mode: the places chosen for a view (the kinds shown at its width, the view and 15 % around it, at most 140 by
 *  priority then nearness), the cards' text as iOS's `AviationView` writes it (runs, colours, sizes, the name cut at
 *  24 characters, no distance to the place itself), the card images and runway rings, the source's points, and the
 *  store that reads the places once and calls back on the main thread. */
@RunWith(AndroidJUnit4::class)
class AviationLayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun aerodrome(kind: Char, code: String, name: String, lat: Double, lon: Double, runway: String = "") =
        "A\t$kind\t$code\t$name\t$lat\t$lon\t100\t$runway"

    private fun navaid(kind: Char, code: String, name: String, lat: Double, lon: Double) =
        "N\t$kind\t$code\t$name\t$lat\t$lon\t114.50 MHz"

    private fun index(vararg lines: String) = AviationIndex(lines.joinToString("\n"))

    /** The codes of the places chosen, in order. */
    private fun codes(index: AviationIndex, ids: List<Int>) = ids.map { index.points[it].code }

    /** A point `metres` north of `p`. */
    private fun north(p: GeoPoint, metres: Double) = GeoPoint(p.lat + metres / Geo.earthRadius * 180 / PI, p.lon)

    // Which places

    @Test
    fun aViewShowsTheKindsShownAtItsWidth() {
        val index = index(
            aerodrome('L', "LLLL", "Large", 0.1, 0.1),
            aerodrome('M', "MMMM", "Medium", 0.1, 0.2),
            navaid('V', "VOR", "Vor", 0.1, 0.3),
            navaid('T', "TAC", "Tacan", 0.1, 0.35),
            aerodrome('S', "SSSS", "Small", 0.1, 0.4),
            navaid('B', "NDB", "Ndb", 0.2, 0.1),
            navaid('D', "DME", "Dme", 0.2, 0.2),
            aerodrome('H', "HHHH", "Heli", 0.2, 0.3),
            aerodrome('W', "WWWW", "Water", 0.2, 0.4),
        )
        val c = GeoPoint(0.0, 0.0)
        fun shown(km: Double) = codes(index, AviationLayer.select(index, c, 1.0, 1.0, km)).toSet()
        assertEquals(setOf("LLLL", "MMMM", "VOR", "TAC", "SSSS", "NDB", "DME", "HHHH", "WWWW"), shown(60.0))
        assertEquals(setOf("LLLL", "MMMM", "VOR", "TAC", "SSSS", "NDB", "DME"), shown(60.1))
        assertEquals(setOf("LLLL", "MMMM", "VOR", "TAC", "SSSS", "NDB", "DME"), shown(120.0))
        assertEquals(setOf("LLLL", "MMMM", "VOR", "TAC", "SSSS"), shown(200.0))
        assertEquals(setOf("LLLL", "MMMM", "VOR", "TAC"), shown(400.0))
        assertEquals(setOf("LLLL", "MMMM"), shown(800.0))
        assertEquals(setOf("LLLL"), shown(3000.0))
        assertEquals(emptySet<String>(), shown(3000.1))
    }

    @Test
    fun placesAreLookedForInTheViewAnd15PercentMoreOnEachSide() {
        // A view 1° × 2° around (−30, 20): from −30.65 to −29.35 and from 18.7 to 21.3.
        val index = index(
            aerodrome('S', "IN1", "North edge", -29.36, 20.0),
            aerodrome('S', "OUT1", "North", -29.34, 20.0),
            aerodrome('S', "IN2", "South edge", -30.64, 20.0),
            aerodrome('S', "OUT2", "South", -30.66, 20.0),
            aerodrome('S', "IN3", "East edge", -30.0, 21.29),
            aerodrome('S', "OUT3", "East", -30.0, 21.31),
            aerodrome('S', "IN4", "West edge", -30.0, 18.71),
            aerodrome('S', "OUT4", "West", -30.0, 18.69),
        )
        val ids = AviationLayer.select(index, GeoPoint(-30.0, 20.0), 1.0, 2.0, 50.0)
        assertEquals(setOf("IN1", "IN2", "IN3", "IN4"), codes(index, ids).toSet())
        val b = AviationLayer.bounds(GeoPoint(-30.0, 20.0), 1.0, 2.0)
        assertEquals(-30.65, b.minLat, 1e-12)
        assertEquals(-29.35, b.maxLat, 1e-12)
        assertEquals(18.7, b.minLon, 1e-12)
        assertEquals(21.3, b.maxLon, 1e-12)
    }

    @Test
    fun latitudesStayOnTheGlobeAndLongitudesRunPastTheDateLine() {
        val b = AviationLayer.bounds(GeoPoint(89.5, 179.5), 2.0, 2.0)
        assertEquals(90.0, b.maxLat, 0.0)
        assertEquals(88.2, b.minLat, 1e-12)
        assertEquals(180.8, b.maxLon, 1e-12)
        assertEquals(-90.0, AviationLayer.bounds(GeoPoint(-89.5, 0.0), 2.0, 2.0).minLat, 0.0)
        // A place just across the date line is in a view reaching to 180.8°.
        val index = index(aerodrome('S', "FIJI", "Across", -17.0, -179.6), aerodrome('S', "FAR", "Far", -17.0, -178.0))
        assertEquals(listOf("FIJI"), codes(index, AviationLayer.select(index, GeoPoint(-17.0, 179.5), 1.0, 2.0, 50.0)))
    }

    @Test
    fun atMost140PlacesTheMostImportantThenTheNearest() {
        // 150 strips on a line east of the middle, 1 km apart, and 10 airports further out.
        val c = GeoPoint(-30.0, 20.0)
        val lines = ArrayList<String>()
        for (i in 0 until 150) lines += aerodrome('S', "S%03d".format(i), "Strip $i", -30.0, 20.0 + (150 - i) * 0.01)
        for (i in 0 until 10) lines += aerodrome('M', "M%03d".format(i), "Airport $i", -30.4, 20.0 + i * 0.05)
        val index = AviationIndex(lines.joinToString("\n"))
        val ids = AviationLayer.select(index, c, 2.0, 4.0, 100.0)
        assertEquals(AviationLayer.maxShown, ids.size)
        val codes = codes(index, ids)
        // The airports first (nearest first), then the 130 strips nearest the middle, nearest first.
        assertEquals((0 until 10).map { "M%03d".format(it) }, codes.take(10))
        assertEquals((149 downTo 20).map { "S%03d".format(it) }, codes.drop(10))
        // Up to 140, all are kept as the index finds them.
        val few = AviationIndex(lines.take(140).joinToString("\n"))
        assertEquals(few.points(AviationLayer.bounds(c, 2.0, 4.0)), AviationLayer.select(few, c, 2.0, 4.0, 100.0))
    }

    // The cards' text

    private val fash = AviationPoint(
        kind = AviationPoint.Kind.smallAirport, code = "FASH", name = "Stellenbosch Airport",
        point = GeoPoint(-33.98098, 18.82233),
    )
    private val hgv = AviationPoint(
        kind = AviationPoint.Kind.vor, code = "HGV", name = "Hoedspruit", point = GeoPoint(-24.35, 31.05),
        frequency = "114.50 MHz",
    )

    /** Each run of the text: what it says, its font (size, weight, tabular digits), its colour, and its icon. */
    private data class Run(val text: String, val size: Float?, val weight: Int?, val digits: Boolean?, val colour: Int?, val icon: String?)

    private fun runs(text: Spanned): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < text.length) {
            val end = text.nextSpanTransition(i, text.length, Any::class.java)
            val font = text.getSpans(i, end, AviationCard.Font::class.java).firstOrNull()
            val colour = text.getSpans(i, end, ForegroundColorSpan::class.java).firstOrNull()?.foregroundColor
            val icon = text.getSpans(i, end, AviationCard.Icon::class.java).firstOrNull()
            val lineBreak = text.getSpans(i, end, AviationCard.LineBreak::class.java).isNotEmpty()
            out += Run(
                text.substring(i, end), font?.size, font?.weight, font?.digits, colour,
                icon?.let { "${it.symbol} ${Integer.toHexString(it.colour)}" } ?: if (lineBreak) "break" else null,
            )
            i = end
        }
        return out
    }

    @Test
    fun anAerodromeWithACodeShowsItBoldThenItsName() {
        val text = AviationCard.text(fash, me = null, pin = null)
        assertEquals("FASH  Stellenbosch", text.toString())
        assertEquals(
            listOf(
                Run("FASH", 13f, MapImages.BOLD, false, Color.WHITE, null),
                Run("  Stellenbosch", 11.5f, MapImages.SEMIBOLD, false, 0xD9FFFFFF.toInt(), null),
            ),
            runs(text),
        )
    }

    @Test
    fun aNavaidAddsItsKind() {
        val text = AviationCard.text(hgv, me = null, pin = null)
        assertEquals("HGV VOR  Hoedspruit", text.toString())
        assertEquals(
            listOf(
                Run("HGV", 13f, MapImages.BOLD, false, Color.WHITE, null),
                Run(" VOR", 11.5f, MapImages.SEMIBOLD, false, 0xBFFFFFFF.toInt(), null),
                Run("  Hoedspruit", 11.5f, MapImages.SEMIBOLD, false, 0xD9FFFFFF.toInt(), null),
            ),
            runs(text),
        )
    }

    @Test
    fun withoutACodeTheNameIsWhite() {
        val strip = AviationPoint(AviationPoint.Kind.smallAirport, "", "Mala Mala Airstrip", GeoPoint(-24.8, 31.5))
        val text = AviationCard.text(strip, null, null)
        assertEquals("Mala Mala", text.toString())
        assertEquals(listOf(Run("Mala Mala", 11.5f, MapImages.SEMIBOLD, false, Color.WHITE, null)), runs(text))
    }

    @Test
    fun aLongNameIsCutAt24Characters() {
        fun named(name: String) = AviationPoint(AviationPoint.Kind.heliport, "ZA-0001", name, GeoPoint(0.0, 0.0))
        assertEquals("ZA-0001  " + "A".repeat(24), AviationCard.text(named("A".repeat(24)), null, null).toString())
        assertEquals("ZA-0001  " + "A".repeat(23) + "…", AviationCard.text(named("A".repeat(25)), null, null).toString())
        // Characters as the user sees them: an accented letter written as two code points is one.
        val accented = "é".repeat(25)
        assertEquals("ZA-0001  " + "é".repeat(23) + "…", AviationCard.text(named(accented), null, null).toString())
    }

    @Test
    fun theDistancesFromYouAndThePinFollowInCyanAndAmber() {
        val me = north(fash.point, 10 * 1852.0)
        val pin = north(fash.point, -86.2 * 1852)
        val text = AviationCard.text(fash, me, pin, density = 2f)
        assertEquals("FASH  Stellenbosch\n￼ 10.0 NM\n￼ 86.2 NM", text.toString())
        val cyan = MapColours.aviationYou
        val amber = MapColours.aviationPin
        assertEquals(0xFF59DBFF.toInt(), cyan)
        assertEquals(0xFFFFB833.toInt(), amber)
        assertEquals(
            listOf(
                Run("FASH", 13f, MapImages.BOLD, false, Color.WHITE, null),
                Run("  Stellenbosch", 11.5f, MapImages.SEMIBOLD, false, 0xD9FFFFFF.toInt(), null),
                Run("\n", null, null, null, null, "break"),
                Run("￼", null, null, null, null, "location ff59dbff"),
                Run(" ", null, null, null, null, null),
                Run("10.0 NM", 13f, MapImages.BOLD, true, cyan, null),
                Run("\n", null, null, null, null, "break"),
                Run("￼", null, null, null, null, "mappin ffffb833"),
                Run(" ", null, null, null, null, null),
                Run("86.2 NM", 13f, MapImages.BOLD, true, amber, null),
            ),
            runs(text),
        )
        // Only the pin.
        assertEquals("FASH  Stellenbosch\n￼ 86.2 NM", AviationCard.text(fash, null, pin).toString())
    }

    @Test
    fun noDistanceIsShownToThePlaceItself() {
        // Under 0.05 NM (92.6 m): the place itself (a selected place is the pin).
        assertEquals("FASH  Stellenbosch", AviationCard.text(fash, fash.point, fash.point).toString())
        assertEquals("FASH  Stellenbosch", AviationCard.text(fash, north(fash.point, 92.0), null).toString())
        assertEquals("FASH  Stellenbosch\n￼ 0.1 NM", AviationCard.text(fash, north(fash.point, 93.0), null).toString())
        assertEquals("FASH  Stellenbosch\n￼ 0.1 NM", AviationCard.text(fash, fash.point, north(fash.point, 93.0)).toString())
    }

    // The images

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xhdpi")
    fun aCardIsItsSymbolAboveADarkCardWithItsText() {
        val m = context.resources.displayMetrics
        val bare = AviationCard.draw(fash, AviationCard.text(fash, null, null, 2f), m)
        val one = AviationCard.draw(fash, AviationCard.text(fash, north(fash.point, 11_300.0), null, 2f), m)
        val two = AviationCard.draw(fash, AviationCard.text(fash, north(fash.point, 11_300.0), north(fash.point, 16_000.0), 2f), m)
        for (card in listOf(bare, one, two)) {
            assertEquals(320, card.bitmap.density)
            assertEquals((card.width * 2).roundToInt(), card.bitmap.width)
            assertEquals((card.height * 2).roundToInt(), card.bitmap.height)
            assertEquals(card.width, card.width.roundToInt().toFloat()) // whole dp, as iOS's frame
        }
        // One line of 13 and 11.5 point text, padded 3 dp: about 15 dp, under the 22 dp symbol and a 3 dp gap.
        assertTrue("${bare.height}", bare.height in 45f..50f)
        // Each distance line as high as the label's 17 point font (about 20 dp; iOS 20.3).
        assertTrue("${one.height - bare.height}", one.height - bare.height in 18f..22f)
        assertEquals(one.height - bare.height, two.height - one.height, 1f)
        // "FASH  Stellenbosch" sets the width (padded 6 dp each side).
        assertEquals(bare.width, one.width)
        assertTrue("${bare.width}", bare.width in 90f..160f)
        // The card: dark (white 5 % at 74 %) under the symbol's square, with its 0.5 dp white 18 % border.
        val inside = bare.bitmap.getPixel(bare.bitmap.width / 2, 2 * 26)
        assertEquals(0xBD, Color.alpha(inside))
        assertTrue(abs(Color.red(inside) - 0x0D) <= 1)
        // Above the card, the symbol's square: an airstrip's ring is drawn apart (turned by the map), so it's empty.
        assertEquals(0, Color.alpha(bare.bitmap.getPixel(bare.bitmap.width / 2 + 2 * 6, 2 * 11)))
        assertEquals(0, Color.alpha(bare.bitmap.getPixel(bare.bitmap.width / 2, 2 * 23)))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xhdpi")
    fun theSymbolsOfPlacesWithoutARunwayAreOnTheCard() {
        val m = context.resources.displayMetrics
        fun symbolPixel(kind: AviationPoint.Kind, dx: Float, dy: Float): Int {
            val place = AviationPoint(kind, "X", "Somewhere", GeoPoint(0.0, 0.0))
            val card = AviationCard.draw(place, AviationCard.text(place, null, null, 2f), m)
            return card.bitmap.getPixel((card.bitmap.width / 2f + 2 * dx).roundToInt(), (2 * (11 + dy)).roundToInt())
        }
        // A helipad: a white ring of radius 8 (with "H" inside).
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.heliport, 8f, 0f))
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.heliport, 0f, -8f))
        // A VOR: a hexagon with a corner on the right and a dot of radius 1.5 in the middle.
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.vor, 7.5f, 0f))
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.vor, 1.5f, 0f))
        // A DME: a 13 dp square, filled with the halo's black 35 %.
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.dme, 6.5f, 3f))
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.dme, -3f, -6.5f))
        val shaded = symbolPixel(AviationPoint.Kind.dme, 0f, 0f)
        assertEquals(0x59, Color.alpha(shaded))
        assertEquals(0, Color.red(shaded))
        // An NDB: dots of radius 1.2 on a ring of 7.5, and one of radius 2 in the middle.
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.ndb, 8.5f, 0f))
        assertEquals(Color.WHITE, symbolPixel(AviationPoint.Kind.ndb, 2f, 0f))
        // Outside the halo, nothing.
        assertEquals(0, Color.alpha(symbolPixel(AviationPoint.Kind.dme, 10f, 0f)))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xhdpi")
    fun theRingsHaveTheRunwayNorthUp() {
        val rings = AviationCard.rings(context.resources.displayMetrics)
        assertEquals(setOf(AviationCard.RING_8, AviationCard.RING_6_5), rings.keys)
        val big = rings.getValue(AviationCard.RING_8)
        assertEquals(48, big.width) // 24 dp
        assertEquals(320, big.density)
        fun pixel(dx: Float, dy: Float) = big.getPixel((24 + 2 * dx).roundToInt(), (24 + 2 * dy).roundToInt())
        assertEquals(Color.WHITE, pixel(8f, 0f)) // the ring
        assertEquals(Color.WHITE, pixel(-8f, 0f))
        assertEquals(Color.WHITE, pixel(0f, -10.5f)) // the runway, 3 dp beyond the ring
        assertEquals(Color.WHITE, pixel(0f, 10.5f))
        assertEquals(0x59, Color.alpha(pixel(3f, 2f))) // inside: the halo's fill
        assertEquals(0, Color.alpha(pixel(10.5f, 10.5f)))
        val small = rings.getValue(AviationCard.RING_6_5)
        assertEquals(Color.WHITE, small.getPixel(24 + 13, 24))
        assertEquals(Color.WHITE, small.getPixel(24, 24 - 19))
    }

    @Test
    fun aRunwayKindHasAMapAlignedRing() {
        assertEquals(AviationCard.RING_8, AviationCard.ring(AviationPoint.Kind.largeAirport))
        assertEquals(AviationCard.RING_8, AviationCard.ring(AviationPoint.Kind.mediumAirport))
        assertEquals(AviationCard.RING_8, AviationCard.ring(AviationPoint.Kind.seaplaneBase))
        assertEquals(AviationCard.RING_6_5, AviationCard.ring(AviationPoint.Kind.smallAirport))
        for (k in listOf(AviationPoint.Kind.heliport, AviationPoint.Kind.vor, AviationPoint.Kind.tacan, AviationPoint.Kind.dme, AviationPoint.Kind.ndb)) {
            assertNull(AviationCard.ring(k))
        }
    }

    // The source

    @Test
    fun aPlacesPointSaysItsCardRingPriorityAndName() {
        val card = AviationLayer.Shown("av/7/3", "FASH  Stellenbosch", 120f, 47f)
        val f = AviationLayer.feature(7, fash, card)
        val p = f.geometry() as Point
        assertEquals(fash.point.lon, p.longitude(), 0.0)
        assertEquals(fash.point.lat, p.latitude(), 0.0)
        assertEquals(7, f.getNumberProperty("id").toInt())
        assertEquals("av/7/3", f.getStringProperty("img"))
        assertEquals(400.0, f.getNumberProperty("key").toDouble(), 0.0) // 1000 − 600
        assertEquals(AviationCard.RING_6_5, f.getStringProperty("ring"))
        assertEquals(45, f.getNumberProperty("rwy").toInt()) // no runway known: as iOS draws it
        assertEquals("aviation", f.getStringProperty("kind"))
        assertEquals("Airstrip FASH Stellenbosch", f.getStringProperty("name"))
        assertEquals(120.0, f.getNumberProperty("w").toDouble(), 0.0)
        assertEquals(47.0, f.getNumberProperty("h").toDouble(), 0.0)
        // A runway's heading, and no ring for a navaid.
        val fasz = fash.copy(kind = AviationPoint.Kind.mediumAirport, code = "FASZ", name = "Skukuza Airport", runwayHeading = 172)
        assertEquals(172, AviationLayer.feature(1, fasz, card).getNumberProperty("rwy").toInt())
        assertEquals(280.0, AviationLayer.feature(1, fasz, card).getNumberProperty("key").toDouble(), 0.0)
        val vor = AviationLayer.feature(2, hgv, card)
        assertFalse(vor.hasProperty("ring"))
        assertFalse(vor.hasProperty("rwy"))
        assertEquals("VOR HGV Hoedspruit", vor.getStringProperty("name"))
    }

    @Test
    fun aCardsBoxHasItsSymbolsMiddleOnThePlace() {
        // 2.625 px per dp; a card 120 × 70 dp on the place at (500, 800).
        val d = 2.625f
        val box = MapController.labelBox(AviationLayer.KIND, PointF(500f, 800f), 120 * d, 70 * d, d)
        assertEquals(500 - 60 * d, box.left, 1e-3f)
        assertEquals(800 - 11 * d, box.top, 1e-3f)
        assertEquals(500 + 60 * d, box.right, 1e-3f)
        assertEquals(800 + 59 * d, box.bottom, 1e-3f)
        // As before for KML points (dot on top) and block names (centred).
        assertEquals(800 - 5 * d, MapController.labelBox("point", PointF(500f, 800f), 50f, 40f, d).top, 1e-3f)
        assertEquals(800 + d / 2 - 20, MapController.labelBox("name", PointF(500f, 800f), 50f, 40f, d).top, 1e-3f)
    }

    // The store

    private fun idleUntil(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < until) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun theStoreReadsThePlacesOnceAndCallsEveryoneBackOnTheMainThread() {
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        val store = AviationStore { _ ->
            reads.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            aerodrome('S', "FASH", "Stellenbosch Airport", -33.98098, 18.82233)
        }
        val heard = ArrayList<Pair<AviationIndex, Boolean>>()
        store.load(context) { heard += it to (Looper.myLooper() == Looper.getMainLooper()) }
        store.load(context) { heard += it to (Looper.myLooper() == Looper.getMainLooper()) }
        assertNull(store.index) // still reading
        release.countDown()
        idleUntil { heard.size == 2 }
        assertEquals(2, heard.size)
        assertTrue(heard.all { it.second })
        assertTrue(heard[0].first === heard[1].first)
        assertTrue(store.index === heard[0].first)
        assertEquals("FASH", store.index!!.points.single().code)
        // Once read, a caller is still called back later, on the main thread, and nothing is read again.
        var later: AviationIndex? = null
        store.load(context) { later = it }
        assertNull(later)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(later === store.index)
        assertEquals(1, reads.get())
    }

    @Test
    fun theStoreReadsTheShippedPlaces() {
        val store = AviationStore()
        var index: AviationIndex? = null
        store.load(context) { index = it }
        idleUntil { index != null }
        assertNotNull(index)
        assertTrue(index!!.points.size > 80_000)
        val fash = index!!.points.first { it.code == "FASH" && it.kind.isAerodrome }
        assertEquals("Stellenbosch Airport", fash.name)
        assertEquals(AviationPoint.Kind.smallAirport, fash.kind)
        assertNull(fash.runwayHeading) // the ring's runway then lies at 45°, as on the iPhone's screenshot
    }
}
