package com.jacobusoettle.satmap.app

import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** `Format` and `MeasureMode` on the plain JVM: the Swift strings, with a decimal point whatever the phone's
 *  language, and grouping in the phone's language. */
class FormatTest {
    private lateinit var saved: Locale

    @Before
    fun setUp() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(saved)
    }

    @Test
    fun coordinatesAlwaysHaveADecimalPoint() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("-24.987654, 31.592134", Format.coordinate(GeoPoint(-24.987654, 31.592134)))
    }

    @Test
    fun hectares() {
        assertEquals("0.35 ha", Format.hectares(3_500.0))
        assertEquals("27.5 ha", Format.hectares(275_000.0))
        assertEquals("1,250 ha", Format.hectares(12_500_000.0))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("27.5 ha", Format.hectares(275_000.0))
        assertEquals("1.250 ha", Format.hectares(12_500_000.0))
    }

    @Test
    fun groundResolution() {
        assertEquals("30 cm per pixel", Format.groundResolution(19, 0.0))
        assertEquals("1.2 m per pixel", Format.groundResolution(17, 0.0))
        assertEquals("153 m per pixel", Format.groundResolution(10, 0.0))
    }

    @Test
    fun heights() {
        assertEquals("1,250 m", Format.height(1250.0))
        assertEquals("4,101 ft", Format.height(1250.0, HeightUnit.feet))
        assertEquals("12 m", Format.heightDifference(-12.0, HeightUnit.metres))
    }

    @Test
    fun distances() {
        assertEquals(DistanceUnit.metric.format(850.0), Format.distance(850.0))
    }

    @Test
    fun bytesAsByteCountFormatterFile() {
        assertEquals("Zero KB", Format.bytes(0))
        assertEquals("1 byte", Format.bytes(1))
        assertEquals("999 bytes", Format.bytes(999))
        assertEquals("2 MB", Format.bytes(2_000_000))
        assertEquals("68.4 MB", Format.bytes(68_400_000))
        assertEquals("42 GB", Format.bytes(42_000_000_000))
        assertEquals("15.26 GB", Format.bytes(15_260_000_000))
        assertEquals("1 MB", Format.bytes(999_999))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("68,4 MB", Format.bytes(68_400_000))
    }

    @Test
    fun measureLabelsAreBijectiveBase26() {
        assertEquals("A", MeasureMode.label(0))
        assertEquals("Z", MeasureMode.label(25))
        assertEquals("AA", MeasureMode.label(26))
        assertEquals("ZZ", MeasureMode.label(701))
        assertEquals("AAA", MeasureMode.label(702))
    }

    @Test
    fun movingAMeasuringPoint() {
        val a = GeoPoint(-25.0, 31.0)
        val b = GeoPoint(-25.1, 31.1)
        val c = GeoPoint(-25.2, 31.2)
        assertEquals(MeasureMode.Placed(c), MeasureMode.Placed(a).moving(0, c))
        assertEquals(MeasureMode.Placed(a), MeasureMode.Placed(a).moving(1, c))
        assertEquals(MeasureMode.Path(listOf(a, c)), MeasureMode.Path(listOf(a, b)).moving(1, c))
        assertEquals(MeasureMode.Closed(listOf(a, b)), MeasureMode.Closed(listOf(a, b)).moving(2, c))
        assertEquals(MeasureMode.Aiming, MeasureMode.Aiming.moving(0, c))
        assertEquals(listOf(a, b), MeasureMode.Path(listOf(a, b)).points)
        assertEquals(emptyList<GeoPoint>(), MeasureMode.Off.points)
    }
}
