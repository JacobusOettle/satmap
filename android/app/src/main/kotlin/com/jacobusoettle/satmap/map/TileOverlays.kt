package com.jacobusoettle.satmap.map

import com.jacobusoettle.satmap.core.ElevationGrid
import com.jacobusoettle.satmap.core.ElevationRange
import com.jacobusoettle.satmap.core.TileID

/** Contour tiles drawn on the phone from the Terrarium heights, exactly as on iOS: marching squares into a 256×256
 *  transparent bitmap (halo then lines, labels on checkerboard tiles), encoded as PNG. Drawing uses `android.graphics`
 *  on `Work.contours`. */
class ContourRenderer {
    sealed interface Tile {
        class Lines(val png: ByteArray, val complete: Boolean) : Tile
        data object Blank : Tile
        data object Unavailable : Tile
    }

    /** The pixel ratio, used only when no scale is given. */
    @Volatile
    var screenScale: Double = 1.0

    /** The tile's lines at a drawing scale (shared per "scale/z/x/y" while in flight; complete results cached). */
    suspend fun tile(t: TileID, scale: Double): Tile = Tile.Unavailable // M2: rendering spec §1.8 and §2.8.

    fun clearCaches() {
        // M2: the PNG and blank caches.
    }

    companion object {
        val shared = ContourRenderer()
    }
}

/** The heights of a tile on a 65×65 grid, for the elevation colours. */
class HeightGrids {
    sealed interface Tile {
        class Grid(val grid: ElevationGrid, val complete: Boolean) : Tile
        data object Unavailable : Tile
    }

    suspend fun tile(t: TileID): Tile = Tile.Unavailable // M2: rendering spec §1.9.

    companion object {
        val shared = HeightGrids()
    }
}

/** A colour tile: the grid's premultiplied pixels (`ElevationColours.pixels`, alpha 0.5 included) drawn with bilinear
 *  filtering into the 256×256 tile, each node's pixel centred on the node. Run on `Work.contours`. */
fun colourPng(grid: ElevationGrid, range: ElevationRange): ByteArray = TileAnswer.BLANK_PNG // M2: rendering spec §2.8.
