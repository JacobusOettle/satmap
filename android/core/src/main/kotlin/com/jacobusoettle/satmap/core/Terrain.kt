package com.jacobusoettle.satmap.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A decoded elevation tile: `size`×`size` samples in metres, row-major from the north edge.
 *  Sample (i, j) is the value at the centre of pixel (i, j). */
class DEMTile(
    val id: TileID,
    val size: Int,
    val values: FloatArray,
)

object Terrarium {
    /** Highest zoom published by the AWS Terrain Tiles dataset. */
    const val maxZoom = 15

    /** Decodes a Terrarium-encoded PNG: elevation = R·256 + G + B/256 − 32768. */
    fun decode(data: ByteArray, id: TileID): DEMTile? {
        val img = PNGDecoder.decode(data) ?: return null
        if (img.width != img.height) return null
        val count = img.width * img.height
        val values = FloatArray(count)
        val p = img.pixels
        for (i in 0 until count) {
            val r = (p[i * 4].toInt() and 0xFF).toFloat()
            val g = (p[i * 4 + 1].toInt() and 0xFF).toFloat()
            val b = (p[i * 4 + 2].toInt() and 0xFF).toFloat()
            values[i] = r * 256 + g + b / 256 - 32768
        }
        return DEMTile(id = id, size = img.width, values = values)
    }
}

/** Bilinear sampling of elevation tiles at one zoom level, across tile edges. */
data class DEMSampler(
    val zoom: Int,
    /** Returns the decoded tile or null if unavailable. May block (disk / network). */
    val tile: (TileID) -> DEMTile?,
) {
    /** A rectangular block of pixel samples assembled from one or more tiles. */
    private class Mosaic(
        val px0: Int,
        val py0: Int,
        val w: Int,
        val h: Int,
        val values: FloatArray,
    ) {
        fun at(px: Int, py: Int): Float {
            val x = min(max(px - px0, 0), w - 1)
            val y = min(max(py - py0, 0), h - 1)
            return values[y * w + x]
        }
    }

    /** Builds a mosaic covering global pixels [px0...px1] × [py0...py1].
     *  Missing neighbour tiles fall back to the edge of the tile containing `anchor`; returns null if that tile is
     *  missing. */
    private fun mosaic(px0: Int, py0: Int, px1: Int, py1: Int, anchor: Pair<Int, Int>): Mosaic? {
        val n = 1 shl zoom
        val anchorTile = TileID(z = zoom, x = min(max(anchor.first, 0), n - 1), y = min(max(anchor.second, 0), n - 1))
        val primary = tile(anchorTile) ?: return null
        val size = primary.size
        val worldPixels = n * size
        val cx0 = max(0, px0)
        val cy0 = max(0, py0)
        val cx1 = min(worldPixels - 1, px1)
        val cy1 = min(worldPixels - 1, py1)
        if (!(cx0 <= cx1 && cy0 <= cy1)) return null

        val tiles = hashMapOf(anchorTile to primary)
        val missing = HashSet<TileID>()
        fun fetch(id: TileID): DEMTile? {
            tiles[id]?.let { return it }
            if (id in missing) return null
            val t = tile(id)
            if (t != null && t.size == size) {
                tiles[id] = t
                return t
            }
            missing.add(id)
            return null
        }

        val w = cx1 - cx0 + 1
        val h = cy1 - cy0 + 1
        val values = FloatArray(w * h)
        val primaryMinX = anchorTile.x * size
        val primaryMinY = anchorTile.y * size
        for (ty in (cy0 / size)..(cy1 / size)) {
            for (tx in (cx0 / size)..(cx1 / size)) {
                val id = TileID(z = zoom, x = tx, y = ty)
                val t = fetch(id)
                val yStart = max(cy0, ty * size)
                val yEnd = min(cy1, ty * size + size - 1)
                val xStart = max(cx0, tx * size)
                val xEnd = min(cx1, tx * size + size - 1)
                // For a missing tile, borrow the nearest row/column of the neighbour that shares the anchor's
                // row or column, so adjacent map tiles fall back to identical values along their common edge.
                val sameRow = if (t == null && ty != anchorTile.y) fetch(TileID(z = zoom, x = tx, y = anchorTile.y)) else null
                val sameColumn =
                    if (t == null && sameRow == null && tx != anchorTile.x) fetch(TileID(z = zoom, x = anchorTile.x, y = ty))
                    else null
                for (gy in yStart..yEnd) {
                    for (gx in xStart..xEnd) {
                        val v: Float
                        if (t != null) {
                            v = t.values[(gy - ty * size) * size + (gx - tx * size)]
                        } else if (sameRow != null) {
                            val ly = min(max(gy - primaryMinY, 0), size - 1)
                            v = sameRow.values[ly * size + (gx - tx * size)]
                        } else if (sameColumn != null) {
                            val lx = min(max(gx - primaryMinX, 0), size - 1)
                            v = sameColumn.values[(gy - ty * size) * size + lx]
                        } else {
                            val lx = min(max(gx - primaryMinX, 0), size - 1)
                            val ly = min(max(gy - primaryMinY, 0), size - 1)
                            v = primary.values[ly * size + lx]
                        }
                        values[(gy - cy0) * w + (gx - cx0)] = v
                    }
                }
            }
        }
        return Mosaic(px0 = cx0, py0 = cy0, w = w, h = h, values = values)
    }

    /** Elevation in metres at a world coordinate, or null if the covering tile is unavailable. */
    fun elevation(worldX: Double, worldY: Double, tileSize: Int = 256): Double? {
        val scale = (tileSize shl zoom).toDouble()
        val gx = worldX * scale - 0.5
        val gy = worldY * scale - 0.5
        val i0 = floor(gx).toInt()
        val j0 = floor(gy).toInt()
        val fx = (gx - i0.toDouble()).toFloat()
        val fy = (gy - j0.toDouble()).toFloat()
        val anchor = Pair(floor(worldX * (1 shl zoom).toDouble()).toInt(), floor(worldY * (1 shl zoom).toDouble()).toInt())
        val m = mosaic(px0 = i0, py0 = j0, px1 = i0 + 1, py1 = j0 + 1, anchor = anchor) ?: return null
        val v00 = m.at(i0, j0)
        val v10 = m.at(i0 + 1, j0)
        val v01 = m.at(i0, j0 + 1)
        val v11 = m.at(i0 + 1, j0 + 1)
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return (top + (bottom - top) * fy).toDouble()
    }

    fun elevation(at: GeoPoint, tileSize: Int = 256): Double? {
        val w = WebMercator.world(at)
        return elevation(worldX = w.x, worldY = w.y, tileSize = tileSize)
    }

    /** A grid of `nodesX`×`nodesY` bilinear samples spanning the world rectangle edge to edge.
     *  Node (0,0) is at (minX, minY); node (nodesX-1, nodesY-1) is at (maxX, maxY). */
    fun grid(
        minX: Double, minY: Double, maxX: Double, maxY: Double, nodesX: Int, nodesY: Int, tileSize: Int = 256,
    ): FloatArray? {
        if (!(nodesX >= 2 && nodesY >= 2)) return null
        val scale = (tileSize shl zoom).toDouble()
        val px0 = floor(minX * scale - 0.5).toInt()
        val py0 = floor(minY * scale - 0.5).toInt()
        val px1 = floor(maxX * scale - 0.5).toInt() + 1
        val py1 = floor(maxY * scale - 0.5).toInt() + 1
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val anchor = Pair(floor(cx * (1 shl zoom).toDouble()).toInt(), floor(cy * (1 shl zoom).toDouble()).toInt())
        val m = mosaic(px0 = px0, py0 = py0, px1 = px1, py1 = py1, anchor = anchor) ?: return null

        val out = FloatArray(nodesX * nodesY)
        val dx = (maxX - minX) / (nodesX - 1).toDouble()
        val dy = (maxY - minY) / (nodesY - 1).toDouble()
        for (j in 0 until nodesY) {
            val gy = (minY + j.toDouble() * dy) * scale - 0.5
            val j0 = floor(gy).toInt()
            val fy = (gy - j0.toDouble()).toFloat()
            for (i in 0 until nodesX) {
                val gx = (minX + i.toDouble() * dx) * scale - 0.5
                val i0 = floor(gx).toInt()
                val fx = (gx - i0.toDouble()).toFloat()
                val v00 = m.at(i0, j0)
                val v10 = m.at(i0 + 1, j0)
                val v01 = m.at(i0, j0 + 1)
                val v11 = m.at(i0 + 1, j0 + 1)
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[j * nodesX + i] = top + (bottom - top) * fy
            }
        }
        return out
    }
}
