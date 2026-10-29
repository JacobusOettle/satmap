package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TileID
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile URL scheme (DESIGN.md §3.2): formatted and parsed back. */
class TileUrlsTest {
    private val t = TileID(z = 15, x = 18_900, y = 18_501)

    private fun url(template: String, t: TileID) = template
        .replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())
        .toHttpUrl()

    @Test
    fun theTemplates() {
        assertEquals("https://satmap.local/imagery/1/{z}/{x}/{y}", TileUrls.imagery(1))
        assertEquals("https://satmap.local/contours/3/263/{z}/{x}/{y}", TileUrls.contours(3, 2.625))
        assertEquals("https://satmap.local/contours/3/200/{z}/{x}/{y}", TileUrls.contours(3, 2.0))
        assertEquals("https://satmap.local/colours/2/none/{z}/{x}/{y}", TileUrls.colours(2, null))
        assertEquals("https://satmap.local/colours/2/300.0_900.0/{z}/{x}/{y}", TileUrls.colours(2, ElevationRange(300.0, 900.0)))
    }

    @Test
    fun roundTrip() {
        assertEquals(TileRoute.Imagery(7, t), TileUrls.parse(url(TileUrls.imagery(7), t)))
        assertEquals(TileRoute.Imagery(7, TileID(0, 0, 0)), TileUrls.parse(url(TileUrls.imagery(7), TileID(0, 0, 0))))
        val deepest = TileID(22, (1 shl 22) - 1, (1 shl 22) - 1)
        assertEquals(TileRoute.Imagery(7, deepest), TileUrls.parse(url(TileUrls.imagery(7), deepest)))
        for (s in listOf(1.0, 2.0, 2.63, 3.0)) {
            assertEquals(TileRoute.Contours(8, t, s), TileUrls.parse(url(TileUrls.contours(8, s), t)))
        }
        val ranges = listOf(
            null,
            ElevationRange(300.0, 900.0),
            ElevationRange(-12.5, 40.25),
            ElevationRange(-0.5, 0.125),
            ElevationRange(1234.5678, 1.0E7),
            ElevationRange(-430.0, -400.0),
        )
        for (r in ranges) {
            assertEquals(TileRoute.Colours(9, t, r), TileUrls.parse(url(TileUrls.colours(9, r), t)))
        }
    }

    @Test
    fun malformedURLsAreNoRoute() {
        val bad = listOf(
            "https://example.com/imagery/1/15/18900/18501",
            "https://satmap.local/",
            "https://satmap.local/roads/1/15/18900/18501",
            "https://satmap.local/imagery/1/15/18900",
            "https://satmap.local/imagery/1/15/18900/18501/7",
            "https://satmap.local/imagery/0/15/18900/18501",
            "https://satmap.local/imagery/x/15/18900/18501",
            "https://satmap.local/imagery/1/15/18900/y",
            "https://satmap.local/imagery/1/23/0/0",
            "https://satmap.local/imagery/1/-1/0/0",
            "https://satmap.local/imagery/1/2/4/0",
            "https://satmap.local/imagery/1/2/0/-1",
            "https://satmap.local/contours/1/15/18900/18501",
            "https://satmap.local/contours/1/0/15/18900/18501",
            "https://satmap.local/contours/1/2.5/15/18900/18501",
            "https://satmap.local/colours/1/15/18900/18501",
            "https://satmap.local/colours/1/300.0/15/18900/18501",
            "https://satmap.local/colours/1/300.0_/15/18900/18501",
            "https://satmap.local/colours/1/a_b/15/18900/18501",
            "https://satmap.local/colours/1/NaN_1.0/15/18900/18501",
            "https://satmap.local/colours/1/1.0_2.0_3.0/15/18900/18501",
        )
        for (u in bad) assertNull(u, TileUrls.parse(u.toHttpUrl()))
    }

    @Test
    fun generationsCountUpFromOne() {
        val a = TileUrls.nextGeneration()
        val b = TileUrls.nextGeneration()
        assertTrue(a >= 1)
        assertEquals(a + 1, b)
    }
}
