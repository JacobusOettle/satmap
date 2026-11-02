package com.jacobusoettle.satmap.groups

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.map.GroupLayers
import com.jacobusoettle.satmap.ui.SatIcons
import com.jacobusoettle.satmap.ui.groups.Drop
import com.jacobusoettle.satmap.ui.groups.House
import com.jacobusoettle.satmap.ui.groups.Person3
import com.jacobusoettle.satmap.ui.groups.drawIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** The groups' glyphs as they are drawn (docs/groups/SPEC.md §9.4, §9.5): the map pins' white glyphs about 16 dp, as
 *  SF's 16 pt symbols; the drop solid, the house with its door and chimney; person.3 as wide for its height as the
 *  symbol. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class GroupIconsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The bounds of the white pixels within `radius` px of the bitmap's middle: left, top, right, bottom. */
    private fun whiteBounds(b: Bitmap, radius: Float): IntArray {
        val c = b.width / 2f
        var l = b.width
        var t = b.height
        var r = -1
        var bo = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if (hypot(x + 0.5f - c, y + 0.5f - c) > radius) continue
            val p = b.getPixel(x, y)
            if (minOf(Color.red(p), Color.green(p), Color.blue(p)) > 150) {
                l = minOf(l, x)
                t = minOf(t, y)
                r = maxOf(r, x)
                bo = maxOf(bo, y)
            }
        }
        return intArrayOf(l, t, r, bo)
    }

    @Test
    fun pinGlyphsShowAbout16DpOnTheMap() {
        val m = app.resources.displayMetrics
        val d = m.density
        for (type in PinType.entries + listOf<PinType?>(null)) {
            val image = GroupLayers.pinImage(m, type)
            // Inside the 2 dp white ring.
            val (l, t, r, b) = whiteBounds(image, (GroupLayers.PIN_DP / 2 - 2.5f) * d).toList()
            val size = maxOf(r - l + 1, b - t + 1) / d
            assertTrue("$type's glyph is $size dp", size in 15f..19.5f)
        }
    }

    /** A vector drawn white on black in a `box`-px square. */
    private fun draw(icon: ImageVector, box: Int): Bitmap {
        val b = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        canvas.drawColor(Color.BLACK)
        drawIcon(canvas, icon, RectF(0f, 0f, box.toFloat(), box.toFloat()), Color.WHITE)
        return b
    }

    private fun white(b: Bitmap, x: Float, y: Float): Boolean {
        val p = b.getPixel((x * b.width / 24).toInt(), (y * b.height / 24).toInt())
        return Color.red(p) > 128
    }

    @Test
    fun theDropIsSolidAndTheHouseHasItsDoorAndChimney() {
        val drop = draw(SatIcons.Drop, 240)
        // Down the middle from just under the tip to just above the bottom, and across the round part: all white.
        for (y in 4..20) assertTrue("drop at y $y", white(drop, 12f, y.toFloat()))
        for (x in 7..17) assertTrue("drop at x $x", white(drop, x.toFloat(), 15f))
        assertTrue(!white(drop, 6f, 5f) && !white(drop, 18f, 5f))

        val house = draw(SatIcons.House, 240)
        assertTrue("door", !white(house, 12f, 17f))
        assertTrue("wall beside the door", white(house, 7f, 17f) && white(house, 17f, 17f))
        assertTrue("under the door", white(house, 12f, 21.2f))
        assertTrue("chimney", white(house, 19f, 5.5f))
        assertTrue("no chimney on the left", !white(house, 5f, 5.5f))
        assertTrue("roof", white(house, 12f, 3f))
        assertTrue("gap under the roof", !white(house, 12f, 5f))
    }

    @Test
    fun person3IsAboutTwiceAsWideAsItIsTall() {
        val b = draw(SatIcons.Person3, 240)
        val (l, t, r, bo) = whiteBounds(b, 1000f).toList()
        val ratio = (r - l + 1).toFloat() / (bo - t + 1)
        assertTrue("person.3 is $ratio times as wide as tall", ratio in 2.0f..2.4f)
        // Three heads in a row, apart.
        val headRow = 9.3f
        assertTrue(white(b, 4.81f, headRow) && white(b, 12f, headRow) && white(b, 19.19f, headRow))
        assertTrue(!white(b, 8.4f, headRow) && !white(b, 15.6f, headRow))
        assertEquals(240, b.width)
    }
}
