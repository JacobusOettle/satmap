package com.jacobusoettle.satmap.app

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.system.Os
import com.jacobusoettle.satmap.core.DistanceUnit
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.HeightUnit
import com.jacobusoettle.satmap.core.rounded
import com.jacobusoettle.satmap.core.stringFormat
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** The app's folders. `filesDir` is credential-encrypted storage, readable and writable from the first unlock after
 *  boot: iOS's `completeUntilFirstUserAuthentication`, so nothing more is set. Offline areas are kept out of backups by
 *  `res/xml/backup_rules.xml` and `data_extraction_rules.xml` (iOS: `isExcludedFromBackup`). */
object AppPaths {
    private lateinit var context: Context

    // Read on any thread; working one out twice is harmless.
    @Volatile
    private var rootDir: File? = null

    @Volatile
    private var offlineDir: File? = null

    @Volatile
    private var kmlDir: File? = null

    /** The folders are worked out (and made) when first asked for, from the context given here; an `init` starts
     *  over (a test's own app has its own `filesDir`). */
    fun init(context: Context) {
        this.context = context.applicationContext
        rootDir = null
        offlineDir = null
        kmlDir = null
    }

    /** filesDir/SatMap */
    val root: File
        get() = rootDir ?: File(context.filesDir, "SatMap").apply { mkdirs() }.also { rootDir = it }

    /** Offline areas (many GB): excluded from backup. */
    val offline: File
        get() = offlineDir ?: File(root, "Offline").apply { mkdirs() }.also { offlineDir = it }

    val kml: File
        get() = kmlDir ?: File(root, "KML").apply { mkdirs() }.also { kmlDir = it }

    val track: File get() = File(root, "track.bin")

    /** Where a restore copies the chosen files first (removed at start and after each restore). */
    val restoreFolder: File get() = File(offline, "Restoring")

    /** An area's folder, named by its id in capitals as iOS's `uuidString`; created when first asked for. */
    fun offlineArea(id: UUID): File = File(offline, id.toString().uppercase()).apply { mkdirs() }

    /** Total allocated size of the files in a directory, in bytes (iOS `totalFileAllocatedSize`, else the length). */
    fun size(directory: File): Long {
        var total = 0L
        for (f in directory.walkTopDown()) {
            if (!f.isFile) continue
            total += try {
                Os.lstat(f.path).st_blocks * 512
            } catch (e: Exception) {
                f.length()
            }
        }
        return total
    }

    /** The space free for the app's files (iOS "available capacity for important usage", which counts purgeable space
     *  too: `getAllocatableBytes`), else the file system's free bytes, or null. */
    fun freeDiskSpace(): Long? {
        return try {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(root))
        } catch (e: Exception) {
            try {
                StatFs(root.path).availableBytes
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Numbers as the app shows them. Every `String(format:)` of the Swift goes through core `stringFormat` (always a
 *  decimal point, as Swift's), so a phone set to German still shows "-24.987654, 31.592134". */
object Format {
    /** "850 m", "1.25 km"; "850 ft", "1.25 mi"; "0.46 NM" (`DistanceUnit`). */
    fun distance(m: Double, unit: DistanceUnit = DistanceUnit.metric): String = unit.format(m)

    /** What iOS's `ByteCountFormatter` (`.file`) writes: decimal units, "Zero KB", "1 byte", "N bytes", KB without
     *  decimals, MB with one, GB and up with two, trailing zeros dropped ("68.4 MB", "2 MB", "15.26 GB"), with the
     *  phone's decimal separator. */
    fun bytes(b: Long): String {
        if (b == 0L) return "Zero KB"
        if (abs(b) == 1L) return "$b byte"
        if (abs(b) < 1000) return "$b bytes"
        val units = listOf("KB", "MB", "GB", "TB", "PB", "EB")
        var unit = 0
        var value = b / 1000.0
        // The unit in which the rounded value stays under 1000 ("1 MB", not "1,000 KB").
        while (unit < units.lastIndex && abs(roundTo(value, byteDecimals(unit))) >= 1000) {
            value /= 1000
            unit++
        }
        val f = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = byteDecimals(unit)
            roundingMode = RoundingMode.HALF_EVEN
        }
        return f.format(value) + " " + units[unit]
    }

    private fun byteDecimals(unit: Int): Int = when (unit) { 0 -> 0; 1 -> 1; else -> 2 }

    private fun roundTo(v: Double, decimals: Int): Double = BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toDouble()

    /** "0.35 ha", "27.5 ha", "1,250 ha". */
    fun hectares(squareMeters: Double): String {
        val ha = squareMeters / 10_000
        if (ha >= 100) return "${count(ha.rounded().toInt())} ha"
        return stringFormat(if (ha < 1) "%.2f ha" else "%.1f ha", ha)
    }

    /** Ground size of one satellite image pixel at a zoom level and latitude: "25 cm per pixel", "1.0 m per pixel". */
    fun groundResolution(zoom: Int, latitude: Double): String {
        val m = 156_543.03 * cos(latitude * PI / 180) / (1 shl zoom).toDouble()
        if (m < 0.95) return "${(m * 100).rounded().toInt()} cm per pixel"
        return stringFormat(if (m < 10) "%.1f m per pixel" else "%.0f m per pixel", m)
    }

    /** Latitude, longitude in decimal degrees (6 decimals, about 10 cm): "-24.987654, 31.592134". */
    fun coordinate(p: GeoPoint): String = stringFormat("%.6f, %.6f", p.lat, p.lon)

    /** A height in whole metres or feet ("1 250 m", "4 101 ft"). */
    fun height(m: Double, unit: HeightUnit = HeightUnit.metres): String = "${count(unit.whole(m))} ${unit.symbol}"

    /** A height difference without its sign ("12 m", "39 ft"). */
    fun heightDifference(m: Double, unit: HeightUnit): String = "${count(unit.whole(abs(m)))} ${unit.symbol}"

    /** Grouped in the phone's language, as NumberFormatter `.decimal`. */
    fun count(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

    /** The same for tile counts, which can pass Int's range (Swift's Int is 64-bit). */
    fun count(n: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)
}
