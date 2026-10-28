package com.jacobusoettle.satmap.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** The overlay stack (DESIGN.md §3.8), on the plain JVM: MapLibre's layers are native objects, so only their ids and
 *  order are checked here. */
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
}
