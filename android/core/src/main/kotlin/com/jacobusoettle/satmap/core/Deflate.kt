package com.jacobusoettle.satmap.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Raw DEFLATE (RFC 1951, no zlib header) for group trail chunks (docs/groups/SPEC.md §4.2, encoding 1):
 *  `java.util.zip`'s `Deflater` and `Inflater` with `nowrap`, which are zlib on the JVM and on Android.
 *
 *  `inflateExact` accepts a stream only if it ends (its final block) exactly after producing the declared size and no
 *  input follows it, the same rule as the iPhone core's on Apple platforms and on Linux. `Inflate.raw` isn't used for
 *  chunks: it decodes into an exact-size buffer, so it would accept a longer stream. */
object Deflate {
    /** The data compressed as raw DEFLATE (level 6, as the iPhone core's Linux build), or null if compressing failed. */
    fun raw(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflater = Deflater(6, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n == 0 && deflater.needsInput()) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** The decompressed bytes, only if the stream ends exactly after `size` bytes and no input byte follows its end. */
    fun inflateExact(data: ByteArray, size: Int): ByteArray? {
        if (size < 0 || data.isEmpty()) return null
        // One byte more than declared, so a stream that goes on past `size` is seen.
        val out = ByteArray(size + 1)
        // nowrap: raw DEFLATE. No extra "dummy" input byte (zlib 1.2+ doesn't need it): with one, a stream cut short
        // would decode its missing end from the invented byte.
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, written, out.size - written)
                if (n == 0) break // the input ran out, or a dictionary is asked for
                written += n
            }
            if (!inflater.finished() || written != size || inflater.remaining != 0) return null
            return out.copyOf(size)
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
