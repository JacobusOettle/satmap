package com.jacobusoettle.satmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.jacobusoettle.satmap.app.TileSources
import com.jacobusoettle.satmap.core.RecentCache
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.core.rounded
import java.lang.ref.WeakReference
import kotlin.math.log2

/** The encoded imagery tiles in memory (JPEG, 15–60 KB each), shared by every map and the loupe. */
object ImageryMemory {
    val tiles = RecentCache<TileID, ByteArray>(900)

    fun enteredBackground() {
        tiles.trim(150)
        SatelliteRenderer.trim(0)
    }

    fun trimMemory() {
        tiles.trim(100)
        SatelliteRenderer.trim(32)
    }
}

/** One map's place in the tile world: where it loads (the focus, read by the interceptor on any thread), and what it
 *  is told of arrivals (on the main thread). The phone's map, the car's map and the loupe each have one. */
class DrawnMap {
    /** The rect (world units, 0…1) and level a map loads at; level −1 when unknown. */
    data class Focus(val rect: WorldRect?, val level: Int)

    /** The imagery focus, and the contour (and colour) level over the same rect. Immutable, swapped whole. */
    data class Focuses(val imagery: Focus, val contours: Focus)

    @Volatile
    var focuses: Focuses = Focuses(Focus(null, -1), Focus(null, -1))
        private set

    /** Called on the main thread when a tile arrives. */
    var onArrival: ((TileID, SatelliteRenderer.Content) -> Unit)? = null

    fun setFocus(rect: WorldRect, level: Int, contourLevel: Int) {
        focuses = Focuses(Focus(rect, level), Focus(rect, contourLevel))
    }

    companion object {
        private val all = mutableListOf<WeakReference<DrawnMap>>()

        /** Tells every live map that a tile arrived (posted to the main thread). */
        fun announce(t: TileID, content: SatelliteRenderer.Content) {
            // M1: posts onArrival(t, content) to every live map on the main thread.
        }
    }
}

/** Decides what each tile request is answered with (DESIGN.md §3.2 answer table): imagery from memory, the repository
 *  or a stand-in crop; contour and colour bitmaps; and draws imagery for the loupe. Thread-safe. */
object SatelliteRenderer {
    enum class Content { imagery, contours, colours }

    /** The land heights on screen (`heightExtremes`), and whether every tile under it has its heights. */
    data class Extremes(val low: Double, val high: Double, val complete: Boolean)

    /** Stand-in crops and the loupe only (MapLibre decodes what it shows). */
    val decoded = RecentCache<TileID, Bitmap>(64)

    suspend fun answer(route: TileRoute, layer: TileLayer): TileAnswer =
        TileAnswer.empty(maxAge = 30) // M1: imagery; M2: contours and colours (rendering spec §2.6).

    /** The imagery level for tiles about 256 pixels across on screen (the Swift `level(pixelsPerMapPoint:)`, with the
     *  world, 0…1, as the unit). */
    fun level(pixelsPerWorld: Double): Int =
        log2(pixelsPerWorld / 256).rounded().toInt().coerceIn(0, TileSources.imageryMaxZoom)

    fun heightExtremes(r: WorldRect, level: Int, onScreen: List<XY>): Extremes? = null // M2: as Swift.

    /** The loupe: each spot's own tile, else an ancestor in memory up to 12 levels up; what is missing is loaded
     *  urgently. */
    fun drawImagery(r: WorldRect, level: Int, canvas: Canvas, target: RectF) {
        // M3: as Swift (DESIGN.md D7).
    }

    /** Forgets the retry-after of failed tiles (not of those beyond the imagery's depth) and the contour and height
     *  retry gates. */
    fun reload() {
        // M2: clears the gates.
    }

    fun trim(count: Int) {
        decoded.trim(count)
        // M2: contourImages.trim(count); heightGrids.trim(count).
    }
}

/** When the map stops: loads the tiles 3 and 6 levels up around the screen in the background, so the next pan or zoom
 *  out is answered from memory. */
object ImageryPrefetch {
    fun mapStopped(visible: WorldRect, level: Int) {
        // M1: rendering spec §1.7 and §2.10.
    }
}
