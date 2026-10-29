package com.jacobusoettle.satmap.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.jacobusoettle.satmap.app.MeasureMode
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLColor
import com.jacobusoettle.satmap.location.GpsFix
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Every style bitmap the map shows (the style has no glyphs, so all map text is app-drawn), at the map's density. */
object MapImages {
    /** Your dot, blue, and grey while the GPS sleeps. */
    const val USER_DOT = "user-dot"
    const val USER_DOT_GREY = "user-dot-grey"

    /** The compass arrow beside your dot, in the dot's colour. */
    const val USER_ARROW = "user-arrow"
    const val USER_ARROW_GREY = "user-arrow-grey"
    const val MEASURE_MARKER = "measure-marker"
    const val SELECTED = "selected"

    /** The fixed images: your dot and arrow (blue and grey), the measuring marker, the selected pin. */
    fun base(context: Context): Map<String, Bitmap> {
        val m = context.resources.displayMetrics
        return mapOf(
            USER_DOT to userDot(m, MapColours.systemBlue),
            USER_DOT_GREY to userDot(m, MapColours.systemGray),
            USER_ARROW to userArrow(m, MapColours.systemBlue),
            USER_ARROW_GREY to userArrow(m, MapColours.systemGray),
            MEASURE_MARKER to measureMarker(m),
            SELECTED to selected(m),
        )
    }

    /** A measuring point's letter ("A", "B" … "AA" …). */
    fun letterId(letter: String) = "measure-letter/$letter"

    /** A KML file's label `n` (a block's name, or a point with its name). */
    fun kmlLabelId(file: UUID, n: Int) = "kml/$file/$n"

    /** iOS `MeasureMarkerView`: 30 points, a pink ring (inset 3, so radius 12, 3 wide, filled pink 25 %) with a white
     *  centre of radius 3. */
    private fun measureMarker(m: DisplayMetrics): Bitmap = image(m, 30f, 30f) { d ->
        val c = 15 * d
        drawCircle(c, c, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapColours.measurePink25 })
        drawCircle(c, c, 12 * d, stroke(MapColours.measurePink, 3 * d))
        drawCircle(c, c, 3 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    /** A measuring point's letter, as iOS's label beside the marker: 34 × 18 points, 15 point heavy white text with a
     *  black 90 % shadow 1 point down, left aligned and centred vertically. It goes at (3, −29) from the marker's
     *  centre (the label's frame at (18, −14) in the 30 point marker). */
    fun measureLetter(m: DisplayMetrics, letter: String): Bitmap = image(m, 34f, 18f) { d ->
        val paint = textPaint(15 * d, HEAVY)
        val fm = paint.fontMetrics
        val baseline = 9 * d - (fm.ascent + fm.descent) / 2
        shadowedText(letter, 0f, baseline, paint, d)
    }

    /** iOS `SelectedPointView`: 22 points, a white ring (inset 2, so radius 9, 3 wide) filled black 25 %, with a shadow
     *  (black 50 %, radius 2), and a white centre of radius 2. The image is 28 dp, so the shadow fits around it. */
    private fun selected(m: DisplayMetrics): Bitmap = image(m, 28f, 28f) { d ->
        val c = 14 * d
        // The shape layer's shadow follows its content: faint under the 25 % fill, full under the ring.
        drawCircle(c, c, 9 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            setShadowLayer(2 * d, 0f, 0f, 0x20000000)
        })
        drawCircle(c, c, 9 * d, stroke(Color.WHITE, 3 * d).apply { setShadowLayer(2 * d, 0f, 0f, 0x80000000.toInt()) })
        drawCircle(c, c, 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    /** A KML label as iOS's `BlockLabelView` draws it, with its size in dp. A block's name: 13 point semibold white
     *  text with a black 90 % shadow 1 point down, its frame max(text width, 10) wide, centred on the spot. A point: a
     *  10 point dot (its colour, or white, with a 1 point black border) on the spot and its name under it (15 point
     *  bold, the same shadow), the name's top 12 points below the dot's top; an empty name shows just the dot. The
     *  image is 1 dp taller than the frame, for the shadow under the text. */
    fun kmlLabel(m: DisplayMetrics, label: BlockLayers.Label): LabelImage {
        val d = m.density
        val paint = if (label.isPoint) textPaint(15 * d, BOLD) else textPaint(13 * d, SEMIBOLD)
        val fm = paint.fontMetrics
        // UILabel.sizeToFit: whole points; nothing for no text.
        val textWidth = if (label.title.isEmpty()) 0f else ceil(paint.measureText(label.title) / d)
        val textHeight = if (label.title.isEmpty()) 0f else ceil((fm.descent - fm.ascent) / d)
        val w = max(textWidth, 10f)
        val h = textHeight + if (label.isPoint) 12f else 0f
        val bitmap = image(m, w, h + 1) { _ ->
            val top = if (label.isPoint) 12 * d else 0f
            if (label.isPoint) {
                val cx = w / 2 * d
                val cy = 5 * d
                drawCircle(cx, cy, 5 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = label.colour ?: Color.WHITE })
                drawCircle(cx, cy, 4.5f * d, stroke(Color.BLACK, d)) // the border, inside the dot
            }
            if (label.title.isNotEmpty()) shadowedText(label.title, 0f, top - fm.ascent, paint, d)
        }
        return LabelImage(bitmap, w, h + 1)
    }

    /** A label's image and its size in dp. */
    class LabelImage(val bitmap: Bitmap, val width: Float, val height: Float)

    /** UILabel's shadow: the text in black 90 % 1 point down, then the text in white. */
    private fun Canvas.shadowedText(text: String, x: Float, baseline: Float, paint: Paint, d: Float) {
        paint.color = MapColours.labelShadow
        drawText(text, x, baseline + d, paint)
        paint.color = Color.WHITE
        drawText(text, x, baseline, paint)
    }

    /** Font weights as iOS names them. */
    const val SEMIBOLD = 600
    const val BOLD = 700
    const val HEAVY = 900

    /** The system font at `size` pixels and a weight (on Android 8.x, the nearest of Roboto's own faces). */
    fun textPaint(size: Float, weight: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = typeface(weight)
        color = Color.WHITE
    }

    fun typeface(weight: Int): Typeface = when {
        Build.VERSION.SDK_INT >= 28 -> Typeface.create(Typeface.DEFAULT, weight, false)
        weight >= HEAVY -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        weight >= BOLD -> Typeface.DEFAULT_BOLD
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colour
        strokeWidth = width
    }

    /** iOS `UserDotView`'s dot: 22 points across, the colour inside a white 3 point border, with a soft shadow (black
     *  40 %, radius 2, no offset). The image is 28 dp, so the shadow fits around it. */
    private fun userDot(m: DisplayMetrics, colour: Int): Bitmap = image(m, 28f, 28f) { d ->
        val cx = width / 2f
        val cy = height / 2f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(2 * d, 0f, 0f, SHADOW)
        }
        drawCircle(cx, cy, 11 * d, ring)
        drawCircle(cx, cy, 8 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
    }

    /** iOS `UserDotView`'s arrow, pointing north: a triangle just outside the dot, its base 16 points wide and 2.5
     *  points beyond the dot's edge, its tip 12 points further out, filled in the dot's colour with a white 2 point
     *  outline (round joins) and the dot's shadow. The image is 64 dp, centred on the dot's centre, so it turns about
     *  it. */
    private fun userArrow(m: DisplayMetrics, colour: Int): Bitmap = image(m, 64f, 64f) { d ->
        val cx = width / 2f
        val cy = height / 2f
        val base = cy - (11 + 2.5f) * d
        val path = Path().apply {
            moveTo(cx, base - 12 * d)
            lineTo(cx + 8 * d, base)
            lineTo(cx - 8 * d, base)
            close()
        }
        // The fill with the outline's outer half, casting the shadow of both (as the shape layer's shadow), then the
        // white outline over it.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            color = colour
            strokeWidth = 2 * d
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(2 * d, 0f, 0f, SHADOW)
        }
        drawPath(path, fill)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2 * d
            strokeJoin = Paint.Join.ROUND
        }
        drawPath(path, outline)
    }

    /** iOS shadows: black at 40 %. */
    private const val SHADOW = 0x66000000

    /** A transparent bitmap `w` × `h` dp at the map's density, drawn with the density (pixels per dp) given. MapLibre
     *  shows it `w` × `h` dp: `Style.toImage` takes the pixel ratio from the bitmap's density. */
    private fun image(m: DisplayMetrics, w: Float, h: Float, draw: Canvas.(Float) -> Unit): Bitmap {
        val d = m.density
        val bitmap = Bitmap.createBitmap(ceil(w * d).toInt(), ceil(h * d).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.density = m.densityDpi
        Canvas(bitmap).draw(d)
        return bitmap
    }
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

/** Your position: the blue dot (grey while the GPS sleeps), with a small arrow beside it pointing where the phone
 *  points (the compass). One point in the `user` source; its `dot` and `arrow` say which images, and `heading` (none:
 *  no arrow) how the arrow is turned from north. The arrow is turned against the map's north, so MapLibre keeps it
 *  pointing the same way as the map turns, which iOS did by hand (`updateArrow`). */
class UserDotLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    private var location: GeoPoint? = null
    private var sleeping = false

    /** The heading asked for, and the one shown (they differ while the arrow glides). */
    private var heading: Double? = null
    private var shownHeading: Double? = null
    private var glide: ValueAnimator? = null

    /** Shows you at `location` (none: no dot). */
    fun update(location: GpsFix?, sleeping: Boolean, heading: Double?) {
        this.location = location?.point
        this.sleeping = sleeping
        if (heading != this.heading) turnArrow(heading)
        show()
    }

    /** The map is going: no more frames. */
    fun stop() {
        stopGlide()
    }

    /** Small turns glide (the compass reports every 2°); larger ones, and the arrow appearing, jump. */
    private fun turnArrow(new: Double?) {
        val old = heading
        heading = new
        stopGlide()
        val from = shownHeading
        if (new == null || from == null || !glides(old, new, showing = true)) {
            shownHeading = new
            return
        }
        // The shortest way round, from where the arrow is now.
        val delta = turn(from, new)
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator() // Core Animation's default curve
            addUpdateListener {
                shownHeading = from + delta * (it.animatedValue as Float)
                show()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    shownHeading = new
                    show()
                }
            })
            start()
        }
    }

    private fun stopGlide() {
        val g = glide ?: return
        glide = null
        g.removeAllUpdateListeners()
        g.removeAllListeners()
        g.cancel()
    }

    private fun show() {
        val source = style.getSourceAs<GeoJsonSource>(MapIds.USER) ?: return
        val p = location
        if (p == null) {
            source.setGeoJson(MapStyle.empty())
        } else {
            source.setGeoJson(FeatureCollection.fromFeature(feature(p, sleeping, shownHeading)))
        }
    }

    companion object {
        /** The `user` source's point: the images for the GPS awake or asleep, and the arrow's heading if there is one
         *  (0–360°, clockwise from north). */
        fun feature(p: GeoPoint, sleeping: Boolean, heading: Double?): Feature =
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("dot", if (sleeping) MapImages.USER_DOT_GREY else MapImages.USER_DOT)
                addStringProperty("arrow", if (sleeping) MapImages.USER_ARROW_GREY else MapImages.USER_ARROW)
                if (heading != null) addNumberProperty("heading", (heading % 360 + 360) % 360)
            }

        /** Whether a turn from `old` to `new` glides: only when the arrow shows already and the turn is 45° or less
         *  (no `old`: as iOS, a half turn, so it jumps). */
        fun glides(old: Double?, new: Double, showing: Boolean): Boolean {
            var turn = abs((old ?: (new + 180)) - new) % 360
            if (turn > 180) turn = 360 - turn
            return showing && turn <= 45
        }

        /** The turn from `from` to `to` the shortest way round, in degrees (−180 to 180, clockwise positive). */
        fun turn(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180
    }
}

/** The measuring points: pink rings with a white centre, which always show and hide labels under them, each
 *  labelled A, B, C … once there are two or more (their letters hide nothing). One point per marker in the
 *  `measure-points` source; a letter's image is drawn the first time it is needed. Main thread only. */
class MeasureMarkerLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** The letters whose images the style has. */
    private val letters = HashSet<String>()

    /** Shows the points (also each time one is moved: the source is sent again and MapLibre moves it in the frame). */
    fun update(points: List<GeoPoint>) {
        val source = style.getSourceAs<GeoJsonSource>(MapIds.MEASURE_POINTS) ?: return
        if (points.size >= 2) {
            val m = context.resources.displayMetrics
            for (i in points.indices) {
                val letter = MeasureMode.label(i)
                if (letters.add(letter)) style.addImage(MapImages.letterId(letter), MapImages.measureLetter(m, letter))
            }
        }
        source.setGeoJson(features(points))
    }

    companion object {
        /** A point per marker; with two or more, each has its letter's image ("A", "B" … from `MeasureMode.label`). */
        fun features(points: List<GeoPoint>): FeatureCollection = FeatureCollection.fromFeatures(
            points.mapIndexed { i, p ->
                Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    if (points.size >= 2) addStringProperty("letter", MapImages.letterId(MeasureMode.label(i)))
                }
            },
        )
    }
}

/** A spot tapped on the map, or a KML point (its coordinates are shown at the bottom): a small white ring with a dark
 *  centre, drawn over whatever is there and hiding nothing (a selected KML point keeps its own dot and name under the
 *  ring). It can be moved (press and hold). Main thread only. */
class SelectedPointLayer(private val map: MapLibreMap, private val style: Style, private val context: Context) {
    /** Shows the pin at `point` (none: no pin). */
    fun update(point: GeoPoint?) {
        val source = style.getSourceAs<GeoJsonSource>(MapIds.SELECTED) ?: return
        source.setGeoJson(features(point))
    }

    companion object {
        fun features(point: GeoPoint?): FeatureCollection =
            if (point == null) MapStyle.empty()
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))
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

/** A KML colour as an Android colour int (iOS `UIColor(kml:)`). */
fun KMLColor.argb(): Int = Color.argb(byte(alpha), byte(red), byte(green), byte(blue))

/** A KML colour as a style colour string, "rgba(r,g,b,a)" with r, g, b in 0–255 and a in 0–1. */
fun KMLColor.rgba(): String = "rgba(${byte(red)},${byte(green)},${byte(blue)},${alpha.coerceIn(0.0, 1.0)})"

/** A colour component (0–1) as a byte (0–255). */
private fun byte(c: Double): Int = (c.coerceIn(0.0, 1.0) * 255).roundToInt()
