package com.jacobusoettle.satmap.core

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** DEFLATE decompression for PNG image data. On iOS it is Apple's Compression framework (zlib on Linux); here
 *  `java.util.zip.Inflater`, which is zlib on the JVM and on Android. (Zip archives use `java.util.zip` directly.) */
object Inflate {
    /** Decompresses raw DEFLATE data (RFC 1951) whose decompressed size is known exactly. */
    fun raw(input: ByteArray, decompressedSize: Int): ByteArray? {
        if (decompressedSize == 0) return ByteArray(0)
        if (input.isEmpty() || decompressedSize <= 0) return null
        val out = ByteArray(decompressedSize)
        // nowrap: raw DEFLATE (no zlib header or checksum). Inflater's documentation asks for one extra "dummy" input
        // byte with nowrap, but zlib 1.2+ (on the JVM and on Android) doesn't need it; and with one, a stream cut short
        // decodes the missing end from that invented zero byte, which Apple's compression_decode_buffer cannot do.
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            var written = 0
            while (written < decompressedSize) {
                val n = inflater.inflate(out, written, decompressedSize - written)
                if (n == 0) break // finished, or the input ran out
                written += n
            }
            // As with Apple's compression_decode_buffer: exactly the expected size (any output beyond it is not read).
            return if (written == decompressedSize) out else null
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /** Decompresses a zlib-wrapped stream (RFC 1950), e.g. concatenated PNG IDAT chunks. */
    fun zlib(input: ByteArray, decompressedSize: Int): ByteArray? {
        val bytes = input
        if (bytes.size <= 6) return null
        val cmf = bytes[0].toInt() and 0xFF
        val flg = bytes[1].toInt() and 0xFF
        if (!(cmf and 0x0F == 8 && ((cmf shl 8) or flg) % 31 == 0)) return null
        val header = if ((flg and 0x20) != 0) 6 else 2
        // Drop the trailing Adler-32 checksum; the DEFLATE stream ends before it.
        val end = bytes.size - 4
        if (end <= header) return null
        return raw(bytes.copyOfRange(header, end), decompressedSize = decompressedSize)
    }
}
