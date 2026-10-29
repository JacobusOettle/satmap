package com.jacobusoettle.satmap.core

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Read-only access to a (non-ZIP64) zip archive held in memory — enough for KMZ files.
 *  The entries are read from the archive's central directory, as on iOS, so the same archives open (and fail) the same
 *  way; DEFLATE data is inflated by `java.util.zip.Inflater`. (`ZipInputStream` reads the local headers instead, and
 *  `ZipFile` needs a file on disk.) */
class ZipArchive(data: ByteArray) {
    /** Sizes and offsets are unsigned 32-bit numbers in the archive: `Long` here (Swift's `Int` is 64-bit). */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    /** The Swift `ZipError` cases. It has no descriptions of its own, so the message is what iOS shows for it. */
    sealed class ZipError(code: Int) :
        Exception("The operation couldn’t be completed. (SatMap.ZipArchive.ZipError error $code.)") {
        class NotAZip : ZipError(0)
        class Corrupt : ZipError(1)
        class UnsupportedCompression : ZipError(2)
    }

    val entries: List<Entry>
    private val bytes: ByteArray = data.copyOf()

    init {
        // Locate the End Of Central Directory record (it may be followed by a comment of up to 64 KiB).
        if (bytes.size < 22) throw ZipError.NotAZip()
        var eocd = -1
        var i = bytes.size - 22
        val lowest = maxOf(0, bytes.size - 22 - 65_535)
        while (i >= lowest) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 0x05.toByte() &&
                bytes[i + 3] == 0x06.toByte()
            ) {
                eocd = i
                break
            }
            i -= 1
        }
        if (eocd < 0) throw ZipError.NotAZip()
        val count = u16(bytes, eocd + 10)
        var pos = u32(bytes, eocd + 16)
        val list = mutableListOf<Entry>()
        for (n in 0 until count) {
            if (!(pos + 46 <= bytes.size && u32(bytes, pos.toInt()) == 0x0201_4B50L)) throw ZipError.Corrupt()
            val p = pos.toInt()
            val method = u16(bytes, p + 10)
            val compressed = u32(bytes, p + 20)
            val uncompressed = u32(bytes, p + 24)
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val offset = u32(bytes, p + 42)
            if (p + 46 + nameLen > bytes.size) throw ZipError.Corrupt()
            // Not valid UTF-8: one U+FFFD per maximal ill-formed subpart, as Swift's `String(decoding:as:)`. (Not
            // `decodeToString()`: the desktop JVM and Android 8 decode ill-formed bytes differently.)
            val name = decodeUtf8(bytes, p + 46, p + 46 + nameLen)
            list.add(
                Entry(
                    name = name, method = method, compressedSize = compressed,
                    uncompressedSize = uncompressed, localHeaderOffset = offset,
                )
            )
            pos += 46 + nameLen + extraLen + commentLen
        }
        entries = list
    }

    fun extract(entry: Entry): ByteArray {
        val h = entry.localHeaderOffset
        if (!(h + 30 <= bytes.size && u32(bytes, h.toInt()) == 0x0403_4B50L)) throw ZipError.Corrupt()
        val nameLen = u16(bytes, h.toInt() + 26)
        val extraLen = u16(bytes, h.toInt() + 28)
        val start = h + 30 + nameLen + extraLen
        if (start + entry.compressedSize > bytes.size) throw ZipError.Corrupt()
        val payload = bytes.copyOfRange(start.toInt(), (start + entry.compressedSize).toInt())
        return when (entry.method) {
            0 -> payload
            8 -> inflateRaw(payload, decompressedSize = entry.uncompressedSize) ?: throw ZipError.Corrupt()
            else -> throw ZipError.UnsupportedCompression()
        }
    }

    private companion object {
        fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, i: Int): Long =
            (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

        /** UTF-8 bytes [from, to) as Swift's `String(decoding:as: UTF8.self)`: one U+FFFD per maximal ill-formed
         *  subpart (Unicode's best practice), with no surrogates and no overlong forms. */
        fun decodeUtf8(b: ByteArray, from: Int, to: Int): String {
            val sb = StringBuilder(to - from)
            var i = from
            while (i < to) {
                val b0 = b[i].toInt() and 0xFF
                if (b0 < 0x80) {
                    sb.append(b0.toChar())
                    i++
                    continue
                }
                val need: Int
                var cp: Int
                var lo = 0x80
                var hi = 0xBF
                when (b0) {
                    in 0xC2..0xDF -> { need = 1; cp = b0 and 0x1F }
                    in 0xE0..0xEF -> {
                        need = 2
                        cp = b0 and 0x0F
                        if (b0 == 0xE0) lo = 0xA0 else if (b0 == 0xED) hi = 0x9F // no overlong form, no surrogate
                    }
                    in 0xF0..0xF4 -> {
                        need = 3
                        cp = b0 and 0x07
                        if (b0 == 0xF0) lo = 0x90 else if (b0 == 0xF4) hi = 0x8F // no overlong form, none past U+10FFFF
                    }
                    else -> {
                        sb.append('\uFFFD')
                        i++
                        continue
                    }
                }
                var j = i + 1
                var seen = 0
                while (seen < need && j < to) {
                    val c = b[j].toInt() and 0xFF
                    if (c < lo || c > hi) break
                    cp = (cp shl 6) or (c and 0x3F)
                    lo = 0x80
                    hi = 0xBF
                    j++
                    seen++
                }
                if (seen == need) sb.appendCodePoint(cp) else sb.append('\uFFFD')
                i = j
            }
            return sb.toString()
        }

        /** Raw DEFLATE data (RFC 1951) whose decompressed size is known exactly, as the Swift `Inflate.raw`: nil unless
         *  it gives exactly that many bytes. */
        fun inflateRaw(input: ByteArray, decompressedSize: Long): ByteArray? {
            if (decompressedSize == 0L) return ByteArray(0)
            if (input.isEmpty() || decompressedSize <= 0) return null
            // A ByteArray holds less than 2 GiB (more than a phone could unpack in memory anyway).
            if (decompressedSize > Int.MAX_VALUE - 8) return null
            // DEFLATE gives at most 1032 bytes per input byte, so the stream cannot fill a larger buffer (Swift
            // allocates it lazily and then rejects it): null, without allocating it.
            if (decompressedSize > input.size.toLong() * 1032 + 1032) return null
            val size = decompressedSize.toInt()
            val out = ByteArray(size)
            // nowrap: raw DEFLATE (no zlib header or checksum). No extra "dummy" input byte, as in `Inflate.raw`: zlib
            // 1.2+ doesn't need it, and with one a stream cut short would decode the missing end from that zero byte.
            val inflater = Inflater(true)
            try {
                inflater.setInput(input)
                var written = 0
                while (written < size) {
                    val n = inflater.inflate(out, written, size - written)
                    if (n == 0) break // finished, or the input ran out
                    written += n
                }
                return if (written == size) out else null
            } catch (e: DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }
    }
}
