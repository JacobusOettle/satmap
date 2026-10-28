package com.jacobusoettle.satmap.core

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// The Swift `name` properties are `spokenName` here: a Kotlin enum's `name` is its case's name (Swift's `rawValue`).

/** How distances are shown: kilometres, miles or nautical miles. A tap on a measured distance (or the scale bar) moves
 *  on to the next. */
enum class DistanceUnit {
    metric,
    imperial,
    nautical;

    /** A scale bar: its distance, its length in points and its label. */
    data class Scale(val meters: Double, val width: Double, val label: String)

    /** The saved form ("metric", "imperial", "nautical"), as on iOS. */
    val rawValue: String get() = name

    val next: DistanceUnit
        get() = when (this) {
            metric -> imperial
            imperial -> nautical
            nautical -> metric
        }

    /** Spoken: "kilometres", "miles", "nautical miles". */
    val spokenName: String
        get() = when (this) {
            metric -> "kilometres"
            imperial -> "miles"
            nautical -> "nautical miles"
        }

    /** "850 m", "1.25 km", "12.5 km", "125 km"; "850 ft", "1.25 mi"; "0.46 NM", "12.4 NM". */
    fun format(metres: Double): String {
        val m = max(0.0, if (metres.isFinite()) metres else 0.0)
        return when (this) {
            metric -> {
                if (m < 999.5) return "${m.rounded().toLong()} m"
                decimal(m / 1000, "km")
            }
            imperial -> {
                val feet = m / metresPerFoot
                if (feet < 999.5) return "${feet.rounded().toLong()} ft"
                decimal(m / metresPerMile, "mi")
            }
            nautical -> {
                val nm = m / metresPerNauticalMile
                if (nm < 0.9995) return stringFormat("%.3f", nm) + " NM" // a thousandth is under 2 m
                decimal(nm, "NM")
            }
        }
    }

    /** The scale bar: the longest round distance (1, 2 or 5 times a power of ten, in metres or kilometres, feet or
     *  miles, or nautical miles) whose bar fits in `maxWidth` points, its length in points and its label. */
    fun scale(metersPerPoint: Double, maxWidth: Double): Scale? {
        if (!(metersPerPoint > 0 && metersPerPoint.isFinite() && maxWidth > 0)) return null
        val most = metersPerPoint * maxWidth
        val unitMetres: Double
        val symbol: String
        when (this) {
            metric -> {
                val s = ScaleBar.choose(metersPerPoint = metersPerPoint, maxWidth = maxWidth) ?: return null
                return Scale(s.meters, s.width, ScaleBar.label(s.meters))
            }
            imperial -> {
                if (most < metresPerMile) {
                    unitMetres = metresPerFoot
                    symbol = "ft"
                } else {
                    unitMetres = metresPerMile
                    symbol = "mi"
                }
            }
            nautical -> {
                unitMetres = metresPerNauticalMile
                symbol = "NM"
            }
        }
        val units = most / unitMetres
        val power = 10.0.pow(floor(log10(units)))
        val round = listOf(5.0, 2.0, 1.0).map { it * power }.firstOrNull { it <= units * (1 + 1e-9) } ?: power
        val meters = round * unitMetres
        val text = if (round >= 1) stringFormat("%.0f", round) else stringFormat("%g", (round * 1e6).rounded() / 1e6)
        return Scale(meters, meters / metersPerPoint, "$text $symbol")
    }

    companion object {
        const val metresPerFoot = 0.3048
        const val metresPerMile = 1609.344
        const val metresPerNauticalMile = 1852.0

        /** The unit saved as `rawValue`, or null (Swift's `DistanceUnit(rawValue:)`). */
        operator fun invoke(rawValue: String): DistanceUnit? = entries.firstOrNull { it.rawValue == rawValue }

        /** 2 decimals under 10, 1 under 100, none from 100. */
        private fun decimal(v: Double, symbol: String): String {
            if (v < 9.995) return stringFormat("%.2f", v) + " " + symbol
            if (v < 99.95) return stringFormat("%.1f", v) + " " + symbol
            return stringFormat("%.0f", v) + " " + symbol
        }
    }
}

/** How heights are shown: metres or feet. A tap on a height moves on to the other. */
enum class HeightUnit {
    metres,
    feet;

    /** The saved form ("metres", "feet"), as on iOS. */
    val rawValue: String get() = name

    val next: HeightUnit get() = if (this == metres) feet else metres
    val symbol: String get() = if (this == metres) "m" else "ft"
    val spokenName: String get() = if (this == metres) "metres" else "feet"

    /** A height (metres) in this unit, rounded to whole ones. */
    fun whole(metres: Double): Int =
        (if (this == HeightUnit.metres) metres else metres / DistanceUnit.metresPerFoot).rounded().toInt()

    companion object {
        /** The unit saved as `rawValue`, or null (Swift's `HeightUnit(rawValue:)`). */
        operator fun invoke(rawValue: String): HeightUnit? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
