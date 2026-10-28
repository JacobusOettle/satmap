package com.jacobusoettle.satmap.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

// Swift's Foundation behaviour where Kotlin's own differs, so ported code rounds and formats numbers exactly as the
// iPhone app does. (No Swift file of its own: Swift has these built in.)

/** Swift's `rounded()`: to the nearest whole number, halves away from zero. Kotlin's `round` takes halves to the even
 *  neighbour and `Math.round` takes them up, so neither matches. */
fun Double.rounded(): Double {
    val t = truncate(this)
    return if (abs(this - t) >= 0.5) t + sign(this) else t
}

/** Swift's `rounded()` for a `Float`. */
fun Float.rounded(): Float {
    val t = truncate(this)
    return if (abs(this - t) >= 0.5f) t + sign(this) else t
}

/** Swift's `String(format:)` for the conversions the app uses: `%f`, `%e` and `%g` with an optional precision
 *  (`%.2f`), and `%%`. Rounds as C's printf does, from the exact binary value with halves to even. Java's
 *  `String.format` rounds the shortest decimal form with halves up instead ("%.2f" of 0.125 is "0.13" there, "0.12"
 *  on iOS) and writes `%g` differently ("0.0500000" for "0.05"). */
fun stringFormat(format: String, vararg args: Double): String {
    val out = StringBuilder()
    var i = 0
    var next = 0
    while (i < format.length) {
        val c = format[i++]
        if (c != '%') {
            out.append(c)
            continue
        }
        require(i < format.length) { "Unfinished conversion in \"$format\"" }
        if (format[i] == '%') {
            out.append('%')
            i++
            continue
        }
        var precision = 6
        if (format[i] == '.') {
            i++
            val start = i
            while (i < format.length && format[i] in '0'..'9') i++
            precision = if (i > start) format.substring(start, i).toInt() else 0
        }
        require(i < format.length) { "Unfinished conversion in \"$format\"" }
        require(next < args.size) { "Too few arguments for \"$format\"" }
        val v = args[next++]
        out.append(
            when (val conversion = format[i++]) {
                'f' -> fixed(v, precision)
                'e' -> exponential(v, precision)
                'g' -> general(v, precision)
                else -> throw IllegalArgumentException("Unsupported conversion %$conversion in \"$format\"")
            }
        )
    }
    return out.toString()
}

/** "-" for a negative number (and negative zero), as printf writes it. */
private fun signOf(v: Double) = if (v.toRawBits() < 0) "-" else ""

private fun special(v: Double) = when {
    v.isNaN() -> "nan"
    v > 0 -> "inf"
    else -> "-inf"
}

/** `%.{precision}f`. */
private fun fixed(v: Double, precision: Int): String {
    if (!v.isFinite()) return special(v)
    return signOf(v) + BigDecimal(abs(v)).setScale(precision, RoundingMode.HALF_EVEN).toPlainString()
}

/** `%.{precision}e`: one digit before the point, the exponent with a sign and at least two digits. */
private fun exponential(v: Double, precision: Int): String {
    if (!v.isFinite()) return special(v)
    val a = abs(v)
    val digits: String
    val exponent: Int
    if (a == 0.0) {
        digits = "0".repeat(precision + 1)
        exponent = 0
    } else {
        val r = BigDecimal(a).round(MathContext(precision + 1, RoundingMode.HALF_EVEN))
        digits = r.unscaledValue().toString().padEnd(precision + 1, '0').substring(0, precision + 1)
        exponent = r.precision() - r.scale() - 1
    }
    val mantissa = if (precision > 0) digits.substring(0, 1) + "." + digits.substring(1) else digits
    return signOf(v) + mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
}

/** `%.{precision}g`: `%f` or `%e` by the exponent, without trailing zeros. */
private fun general(v: Double, precision: Int): String {
    if (!v.isFinite()) return special(v)
    val p = if (precision == 0) 1 else precision
    val exponent = if (v == 0.0) 0 else {
        val r = BigDecimal(abs(v)).round(MathContext(p, RoundingMode.HALF_EVEN))
        r.precision() - r.scale() - 1
    }
    val s = if (exponent < p && exponent >= -4) fixed(v, p - 1 - exponent) else exponential(v, p - 1)
    val e = s.indexOf('e')
    val mantissa = if (e >= 0) s.substring(0, e) else s
    if ('.' !in mantissa) return s
    return mantissa.trimEnd('0').trimEnd('.') + (if (e >= 0) s.substring(e) else "")
}
