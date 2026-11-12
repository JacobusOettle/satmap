package com.jacobusoettle.satmap.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.DroneLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/** Drone link's map images (`DroneLinkLayer`, `DroneLinkShapes`): every icon drawn, in the 24-unit box, the colours
 *  and the label cards. The layer itself needs MapLibre and is exercised by the walkthrough (shots 42-43). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DroneLinkLayerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val metrics get() = app.resources.displayMetrics

    private fun drawn(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) if (Color.alpha(b.getPixel(x, y)) > 0) n++
        return n
    }

    @Test
    fun shapesStayInTheirBox() {
        // (The quadcopter's rotor rings reach a tenth of a unit past it, as on the iPhone; the image has room round it.)
        val box = RectF(-0.5f, -0.5f, 24.5f, 24.5f)
        for (k in DroneLink.AircraftKind.entries) {
            val b = RectF()
            DroneLinkShapes.path(k).computeBounds(b, true)
            assertTrue("$k: $b", b.width() > 10 && box.contains(b))
        }
    }

    @Test
    fun coloursAreTheIPhones() {
        assertEquals(5, DroneLinkLayer.droneColours.size)
        assertEquals(Color.rgb(255, 79, 217), DroneLinkLayer.colour(0)) // magenta
        assertEquals(DroneLinkLayer.colour(0), DroneLinkLayer.colour(5)) // round again
        assertEquals(Color.rgb(158, 158, 158), DroneLinkLayer.staleColour)
        assertEquals(Color.rgb(255, 69, 59), DroneLinkLayer.highlightColour)
        assertEquals(
            listOf(DroneLinkLayer.HOMES, DroneLinkLayer.RINGS, DroneLinkLayer.ICONS, DroneLinkLayer.UPRIGHT_ICONS, DroneLinkLayer.LABELS),
            DroneLinkLayer.layerIds,
        )
        assertNotEquals(
            DroneLinkLayer.labelImageId(listOf("ZSHMB"), Color.WHITE, null),
            DroneLinkLayer.labelImageId(listOf("ZSHMB"), Color.WHITE, DroneLinkLayer.highlightColour),
        )
    }

    @Test
    fun everyIconIsDrawn() {
        val d = metrics.density
        val drone = DroneLinkLayer.iconImage(metrics, DroneLinkShapes.quad, DroneLinkLayer.colour(0), DroneLinkLayer.DRONE_SIZE)
        assertEquals(ceil((DroneLinkLayer.DRONE_SIZE + 6) * d).toInt(), drone.width)
        assertTrue(drawn(drone) > drone.width * drone.height / 5)
        for (k in DroneLink.AircraftKind.entries) {
            val b = DroneLinkLayer.iconImage(metrics, DroneLinkShapes.path(k), Color.WHITE, DroneLinkLayer.AIRCRAFT_SIZE)
            assertTrue("$k", drawn(b) > b.width * b.height / 12)
        }
        assertTrue(drawn(DroneLinkLayer.homeImage(metrics, DroneLinkLayer.colour(1))) > 0)
        assertTrue(drawn(DroneLinkLayer.ringImage(metrics, DroneLinkLayer.DRONE_SIZE)) > 0)
        val label = DroneLinkLayer.labelImage(metrics, listOf("SAA312", "9,000 ft · 250 kt · ↓"), Color.WHITE, null)
        assertTrue(label.width > label.height)
        assertTrue(drawn(label) > 0)
    }
}
