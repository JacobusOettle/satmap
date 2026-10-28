package com.jacobusoettle.satmap.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    /** Seconds since 1970. */
    val time: Double,
    /** True if the dotted line should not connect this point to the previous one (e.g. after a long gap). */
    val startsSegment: Boolean,
) {
    val geo: GeoPoint get() = GeoPoint(lat = lat, lon = lon)
}

/** Append-only binary log of the recorded track (32 bytes per point), cheap enough to write from the background. */
class TrackLog(private val file: File) {
    /** One thread at a time, in order (the Swift serial queue); it ends when idle. */
    private val queue: ExecutorService = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "satmap.tracklog").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private var handle: FileChannel? = null

    /** Reads every complete record. Call once at startup. */
    fun loadAll(): List<TrackPoint> = sync {
        val data = try {
            file.readBytes()
        } catch (e: IOException) {
            return@sync emptyList()
        }
        val count = data.size / recordSize
        val out = ArrayList<TrackPoint>(count)
        val raw = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val base = i * recordSize
            val lat = Double.fromBits(raw.getLong(base))
            val lon = Double.fromBits(raw.getLong(base + 8))
            val time = Double.fromBits(raw.getLong(base + 16))
            val flags = raw.getLong(base + 24)
            if (!(lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180)) continue
            out.add(TrackPoint(lat = lat, lon = lon, time = time, startsSegment = flags and 1L == 1L))
        }
        out
    }

    fun append(p: TrackPoint) {
        val bytes = ByteBuffer.allocate(recordSize).order(ByteOrder.LITTLE_ENDIAN)
        for (v in longArrayOf(p.lat.toRawBits(), p.lon.toRawBits(), p.time.toRawBits(), if (p.startsSegment) 1L else 0L)) {
            bytes.putLong(v)
        }
        val record = bytes.array()
        queue.execute {
            val h = openHandle() ?: return@execute
            try {
                val buffer = ByteBuffer.wrap(record)
                while (buffer.hasRemaining()) h.write(buffer)
            } catch (e: IOException) {
                runCatching { h.close() }
                handle = null
            }
        }
    }

    fun clear() {
        sync {
            runCatching { handle?.close() }
            handle = null
            file.delete()
        }
    }

    /** Runs `body` on the queue after everything already on it, and waits for it (Swift's `queue.sync`). */
    private fun <T> sync(body: () -> T): T = queue.submit(Callable { body() }).get()

    /** Opens the file for appending, trimming any partial record left by an interrupted write. */
    private fun openHandle(): FileChannel? {
        handle?.let { return it }
        if (!file.exists()) {
            runCatching { file.createNewFile() }
        }
        val h = runCatching { FileChannel.open(file.toPath(), StandardOpenOption.WRITE) }.getOrNull() ?: return null
        try {
            val end = h.size()
            val aligned = end - end % recordSize
            h.position(end)
            if (aligned != end) {
                h.truncate(aligned)
                h.position(aligned)
            }
        } catch (e: IOException) {
            runCatching { h.close() }
            return null
        }
        handle = h
        return h
    }

    companion object {
        const val recordSize = 32
    }
}
