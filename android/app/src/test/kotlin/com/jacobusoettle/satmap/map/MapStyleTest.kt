package com.jacobusoettle.satmap.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.style.expressions.Expression
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** The overlay stack (DESIGN.md §3.8) and the track's and measuring line's widths, on the plain JVM: MapLibre's layers
 *  are native objects, so only their ids and order, and the expressions they are given, are checked here. */
class MapStyleTest {
    @Test
    fun theOverlaysAreStackedAsMapKitDrewThem() {
        // Lines and fills first (track, measuring line, draft area), then the symbols from the lowest priority up:
        // MapLibre places symbols from the top layer down, so you and the markers win every collision.
        assertEquals(
            listOf(
                "track", "track-live",
                "measure-fill", "measure-line",
                "draft-fill", "draft-line",
                "aviation-rings", "aviation",
                "selected",
                "measure-markers", "measure-letters",
                "user-arrow", "user-dot",
            ),
            MapStyle.overlayLayerIds,
        )
        assertEquals(
            listOf("track", "track-live", "measure", "draft", "aviation", "selected", "measure-points", "user"),
            MapStyle.overlaySourceIds,
        )
    }

    @Test
    fun theRunwayRingsShowOnlyUnderShownCards() {
        assertEquals(
            """["all", ["has", "ring"], ["in", ["get", "id"], ["literal", [3, 17]]]]""",
            MapStyle.aviationRingsFilter(listOf(3, 17)).toString(),
        )
        assertEquals(
            """["all", ["has", "ring"], ["in", ["get", "id"], ["literal", []]]]""",
            MapStyle.aviationRingsFilter(emptyList()).toString(),
        )
    }

    /** A zoom curve's value at `z`, as MapLibre interpolates `["interpolate", ["exponential", base], ["zoom"], …]`. */
    private fun at(curve: Expression, z: Double): Double {
        val a = curve.toArray()
        assertEquals("interpolate", a[0])
        assertEquals("exponential", (a[1] as Array<*>)[0])
        assertEquals("zoom", (a[2] as Array<*>)[0])
        val base = ((a[1] as Array<*>)[1] as Number).toDouble()
        val stops = a.drop(3).chunked(2).map { (k, v) -> (k as Number).toDouble() to (v as Number).toDouble() }
        if (z <= stops.first().first) return stops.first().second
        if (z >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= z }
        val (z0, v0) = stops[i]
        val (z1, v1) = stops[i + 1]
        val t = (base.pow(z - z0) - 1) / (base.pow(z1 - z0) - 1)
        return v0 + (v1 - v0) * t
    }

    /** MapKit shows iOS's renderers' tiles shrunk by k = 2^(z − ⌈z⌉) (z in MapLibre's zoom; MapKit's is one more). */
    private fun k(z: Double) = 2.0.pow(z - ceil(z))

    /** Zooms between whole ones, as the app's camera always is (the walk's 14.12, 13.84 and 16.45 among them). */
    private val zooms = listOf(0.3, 5.5, 13.84, 14.12, 15.0005, 16.45, 18.9, 21.75, 25.4)

    @Test
    fun theTracksDotsAreAsBigAndAsFarApartAsTheIPhonesAtEveryZoom() {
        // iOS: 4 points wide, 9 points apart, shown k times as big. MapLibre lays the dashes out in widths at the whole
        // zoom below (its floorwidth) and grows them with the map: 2.25 of those widths apart, times 2^(z − ⌊z⌋).
        val width = MapStyle.zoomScaled(4f)
        for (z in zooms) {
            val floorWidth = at(width, floor(z))
            assertEquals("z $z", 4 * k(z), at(width, z), 1e-3)
            assertEquals("z $z", 9 * k(z), 2.25 * floorWidth * 2.0.pow(z - floor(z)), 1e-3)
            // Round: as long along the line as across it.
            assertEquals("z $z", at(width, z), floorWidth * 2.0.pow(z - floor(z)), 1e-3)
        }
    }

    @Test
    fun theMeasuringLineIsAsThickAndItsDashesAsLongAsTheIPhonesAtEveryZoom() {
        // iOS: 3 points wide, dashes 10 and 7 points, shown k times as big; the dash pattern is in line widths.
        val width = MapStyle.zoomScaled(3f)
        for (z in zooms) {
            val grown = at(width, floor(z)) * 2.0.pow(z - floor(z))
            assertEquals("z $z", 3 * k(z), at(width, z), 1e-3)
            assertEquals("z $z", 10 * k(z), 10.0 / 3 * grown, 1e-3)
            assertEquals("z $z", 7 * k(z), 7.0 / 3 * grown, 1e-3)
        }
    }
}
