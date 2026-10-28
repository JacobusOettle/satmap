package com.jacobusoettle.satmap.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** A decoded 8-bit RGBA image. (A class rather than a data class: a data class compares arrays by reference, and the
 *  Swift struct isn't compared at all.) */
class RGBAImage(
    val width: Int,
    val height: Int,
    /** Row-major from the top, 4 bytes per pixel. */
    val pixels: ByteArray,
)

/** Minimal, exact PNG decoder (8-bit, non-interlaced; grey, RGB, palette, grey+alpha, RGBA).
 *  Used for Terrarium elevation tiles where every bit of the RGB value matters, so we avoid
 *  any colour-managed decode path (such as `BitmapFactory`) that could alter pixel values. */
object PNGDecoder {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun decode(data: ByteArray): RGBAImage? {
        val bytes = data
        if (!(bytes.size > 33 && bytes.copyOfRange(0, 8).contentEquals(signature))) return null

        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = -1
        var interlace = 0
        var palette = ByteArray(0)
        var transparency = ByteArray(0)
        val idat = ByteArrayOutputStream()

        chunks@ while (pos + 12 <= bytes.size) {
            val length = be32(bytes, pos)
            val typeStart = pos + 4
            val start = pos + 8
            if (!(length >= 0 && start + length + 4 <= bytes.size)) return null
            val count = length.toInt()
            val type = (u8(bytes, typeStart) shl 24) or (u8(bytes, typeStart + 1) shl 16) or
                (u8(bytes, typeStart + 2) shl 8) or u8(bytes, typeStart + 3)
            when (type) {
                0x4948_4452 -> { // IHDR
                    if (count < 13) return null
                    // Swift's Int holds any UInt32; a size beyond Int's range is refused below either way.
                    width = be32(bytes, start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    height = be32(bytes, start + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    bitDepth = u8(bytes, start + 8)
                    colorType = u8(bytes, start + 9)
                    interlace = u8(bytes, start + 12)
                }
                0x504C_5445 -> // PLTE
                    palette = bytes.copyOfRange(start, start + count)
                0x7452_4E53 -> // tRNS
                    transparency = bytes.copyOfRange(start, start + count)
                0x4944_4154 -> // IDAT
                    idat.write(bytes, start, count)
                0x4945_4E44 -> // IEND
                    break@chunks
                else -> {}
            }
            pos = start + count + 4
        }

        if (!(width > 0 && height > 0 && width <= 8192 && height <= 8192 && bitDepth == 8 && interlace == 0)) return null
        val channels: Int = when (colorType) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> return null
        }
        if (colorType == 3 && palette.isEmpty()) return null

        val stride = width * channels
        val src = Inflate.zlib(idat.toByteArray(), decompressedSize = height * (stride + 1)) ?: return null

        val out = ByteArray(width * height * 4) { 255.toByte() }
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        val bpp = channels

        for (row in 0 until height) {
            val rowStart = row * (stride + 1)
            val filter = u8(src, rowStart)
            val line = rowStart + 1
            // The sums wrap to a byte, as Swift's &+ does.
            when (filter) {
                0 ->
                    for (i in 0 until stride) cur[i] = src[line + i]
                1 ->
                    for (i in 0 until stride) {
                        cur[i] = (u8(src, line + i) + (if (i >= bpp) u8(cur, i - bpp) else 0)).toByte()
                    }
                2 ->
                    for (i in 0 until stride) cur[i] = (u8(src, line + i) + u8(prev, i)).toByte()
                3 ->
                    for (i in 0 until stride) {
                        val left = if (i >= bpp) u8(cur, i - bpp) else 0
                        cur[i] = (u8(src, line + i) + (left + u8(prev, i)) / 2).toByte()
                    }
                4 ->
                    for (i in 0 until stride) {
                        val a = if (i >= bpp) u8(cur, i - bpp) else 0
                        val b = u8(prev, i)
                        val c = if (i >= bpp) u8(prev, i - bpp) else 0
                        cur[i] = (u8(src, line + i) + paeth(a, b, c)).toByte()
                    }
                else -> return null
            }

            var o = row * width * 4
            when (colorType) {
                0 ->
                    for (x in 0 until width) {
                        val g = cur[x]
                        out[o] = g; out[o + 1] = g; out[o + 2] = g
                        o += 4
                    }
                2 ->
                    for (x in 0 until width) {
                        out[o] = cur[x * 3]; out[o + 1] = cur[x * 3 + 1]; out[o + 2] = cur[x * 3 + 2]
                        o += 4
                    }
                3 ->
                    for (x in 0 until width) {
                        val idx = u8(cur, x)
                        if (!(idx * 3 + 2 < palette.size)) return null
                        out[o] = palette[idx * 3]; out[o + 1] = palette[idx * 3 + 1]; out[o + 2] = palette[idx * 3 + 2]
                        out[o + 3] = if (idx < transparency.size) transparency[idx] else 255.toByte()
                        o += 4
                    }
                4 ->
                    for (x in 0 until width) {
                        val g = cur[x * 2]
                        out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = cur[x * 2 + 1]
                        o += 4
                    }
                else -> // 6
                    for (x in 0 until width) {
                        out[o] = cur[x * 4]; out[o + 1] = cur[x * 4 + 1]
                        out[o + 2] = cur[x * 4 + 2]; out[o + 3] = cur[x * 4 + 3]
                        o += 4
                    }
            }
            val t = prev
            prev = cur
            cur = t
        }
        return RGBAImage(width = width, height = height, pixels = out)
    }

    /** A byte as Swift's `UInt8`, 0…255. */
    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /** A big-endian `UInt32` (as a `Long`, so it is never negative). */
    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or
            u8(b, i + 3).toLong()

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        if (pa <= pb && pa <= pc) return a
        if (pb <= pc) return b
        return c
    }
}
