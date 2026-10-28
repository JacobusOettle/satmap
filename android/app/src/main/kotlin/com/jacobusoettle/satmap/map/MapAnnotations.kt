package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.view.View
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLColor
import com.jacobusoettle.satmap.location.GpsFix
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** Every style bitmap the map shows (the style has no glyphs, so all map text is app-drawn), at the map's density. */
object MapImages {
    /** The fixed images: your dot and arrow (blue and grey), the measuring marker, the selected pin. */
    fun base(context: Context): Map<String, Bitmap> = emptyMap() // M1: the user dot; M3: the rest.
}

/** The map's colours, exact conversions of the Swift values (dark system colours). */
object MapColours {
    const val measurePink = 0xFFFF2E66.toInt()
    const val measurePink25 = 0x40FF2E66
    const val measurePink15 = 0x26FF2E66
    const val trackCyan = 0xFF00E6FF.toInt()
    const val aviationYou = 0xFF59DBFF.toInt()
    const val aviationPin = 0xFFFFB833.toInt()
    const val systemBlue = 0xFF0A84FF.toInt()
    const val systemGray = 0xFF8E8E93.toInt()
    const val crosshairRed = 0xFFFF453A.toInt()
    const val blockStroke = 0xF2FFFFFF.toInt()
    const val blockFill = 0x12FFFFFF
    const val labelShadow = 0xE6000000.toInt()
}

/** Your position: the dot (grey while the GPS sleeps) and the compass arrow. */
class UserDotLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    fun update(location: GpsFix?, sleeping: Boolean, heading: Double?) {
        // M1: the dot; M5: the arrow and its glide.
    }
}

/** The measuring points (rings) and their letters. */
class MeasureMarkerLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    fun update(points: List<GeoPoint>) {
        // M3: one point per marker, letters from 2 points.
    }
}

/** The tapped spot's pin (while not measuring). */
class SelectedPointLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    fun update(point: GeoPoint?) {
        // M3: one point or none.
    }
}

/** The drawn area waiting to be confirmed (dashed white outline, 15 % fill). */
class DraftAreaLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    fun update(ring: List<GeoPoint>?) {
        // M4: the ring as a polygon, or empty.
    }
}

/** Drawing an area with one finger (`GONE` unless drawing): a point every 3 dp, handed over on lift. */
class LassoView(context: Context) : View(context) {
    /** The loop drawn, in view pixels. */
    var onFinish: ((List<PointF>) -> Unit)? = null

    fun reset() {
        // M4: forgets the loop.
    }
}

/** A KML colour as an Android colour int. */
fun KMLColor.argb(): Int = 0xFFFFFFFF.toInt() // M3: from red, green, blue, alpha.

/** A KML colour as a style colour string, "rgba(r,g,b,a)" with r, g, b in 0–255 and a in 0–1. */
fun KMLColor.rgba(): String = "rgba(255,255,255,1)" // M3: from red, green, blue, alpha.
