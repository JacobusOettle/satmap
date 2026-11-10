package com.jacobusoettle.satmap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasureModeTests {
    private val spot = GeoPoint(lat = -33.9183, lon = 18.9212)
    private val other = GeoPoint(lat = -33.92, lon = 18.93)

    @Test
    fun testButtonStartsAtTheSelectedSpotOrWithTheCrosshair() {
        // Nothing selected: the crosshair, as before.
        assertEquals(MeasureButtonAction.Start(MeasureMode.Aiming), MeasureMode.Off.buttonAction(selected = null))
        // A tapped spot (or KML point) selected: A there at once, measured from you.
        assertEquals(MeasureButtonAction.Start(MeasureMode.Placed(spot)), MeasureMode.Off.buttonAction(selected = spot))
        assertEquals(listOf(spot), MeasureMode.Placed(spot).points)
    }

    @Test
    fun testButtonOnceMeasuringIgnoresTheSelectedSpot() {
        for (m in listOf(MeasureMode.Aiming, MeasureMode.Placed(spot), MeasureMode.Path(listOf(spot, other)))) {
            assertEquals("$m", MeasureButtonAction.AddPointAtCrosshair, m.buttonAction(selected = null))
            assertEquals("$m", MeasureButtonAction.AddPointAtCrosshair, m.buttonAction(selected = other))
        }
        val shape = MeasureMode.Closed(listOf(spot, other, GeoPoint(lat = -33.93, lon = 18.92)))
        assertEquals(MeasureButtonAction.Clear, shape.buttonAction(selected = null))
        assertEquals(MeasureButtonAction.Clear, shape.buttonAction(selected = spot))
    }

    @Test
    fun testLabelsAndMovingPoints() {
        assertEquals(listOf("A", "B", "C"), (0 until 3).map(MeasureMode::label))
        assertEquals("AA", MeasureMode.label(26))
        assertEquals(MeasureMode.Placed(other), MeasureMode.Placed(spot).moving(0, other))
        assertEquals(MeasureMode.Path(listOf(spot, other)), MeasureMode.Path(listOf(spot, spot)).moving(1, other))
        assertEquals(MeasureMode.Path(listOf(spot, spot)), MeasureMode.Path(listOf(spot, spot)).moving(2, other))
    }
}
