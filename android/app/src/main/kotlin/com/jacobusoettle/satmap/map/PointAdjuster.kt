package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.core.WorldRect
import com.jacobusoettle.satmap.core.XY
import com.jacobusoettle.satmap.core.stringFormat
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point being moved precisely: the tapped spot's pin, or a measuring point (by its index). */
sealed interface MovablePoint {
    data object Selected : MovablePoint

    data class Measure(val i: Int) : MovablePoint
}

/** Moving a placed point precisely. Pressed and held, the point gets a glass square above the finger showing 15 m × 15 m
 *  of ground around it, magnified (the imagery, the blocks and the measuring line). While the finger stays down the
 *  point follows it at the square's scale, up to 5 m from the square's middle; beyond that the square and the map
 *  under it scroll that way, faster the further the finger goes, carrying the point along. Main thread only. */
class PointAdjuster(
    private val activity: ComponentActivity,
    private val mapView: MapView,
    private val map: MapLibreMap,
) {
    /** Told where the point is as it moves (`done`: the finger was lifted). */
    var onMove: ((MovablePoint, GeoPoint, Boolean) -> Unit)? = null

    private val density = activity.resources.displayMetrics.density
    private val loupe = LoupeView(activity)
    private var frameView: LoupeFrame? = null

    /** Where the square is in the content root, in pixels (for the glass's snapshot). */
    private var frameRect = RectF()
    private val choreographer = Choreographer.getInstance()
    private var ticking = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            tick(frameTimeNanos / 1e9)
            if (ticking) choreographer.postFrameCallback(this)
        }
    }

    private var which: MovablePoint? = null
    private var start = XY(0.0, 0.0)
    private var touchStart = XY(0.0, 0.0)
    private var finger = XY(0.0, 0.0)

    /** The ground at the square's middle, and the point (world units). */
    private var centre = XY(0.0, 0.0)
    private var point = XY(0.0, 0.0)

    /** World units per metre there, world units per dp of the square, and the map's directions for the screen's right
     *  and down (the map may be turned). */
    private var perMetre = 1.0
    private var perLoupePoint = 1.0
    private var right = XY(1.0, 0.0)
    private var down = XY(0.0, 1.0)
    private var lastTick = 0.0
    private var lastSnapshot = 0.0

    val isActive: Boolean get() = which != null

    /** The finger has held the point long enough: the square appears above it (or below, near the top of the screen).
     *  `touch` is the finger in the content root's pixels; `scene` is what the square draws besides the imagery. */
    fun begin(which: MovablePoint, at: GeoPoint, touch: PointF, scene: LoupeView.Scene) {
        end(cancelled = true)
        this.which = which
        start = WebMercator.world(at)
        centre = start
        point = start
        touchStart = XY(touch.x / density.toDouble(), touch.y / density.toDouble())
        finger = XY(0.0, 0.0)
        perMetre = 1 / Geo.metersPerWorldUnit(at.lat)

        // The map's directions for the screen's axes, from the map itself.
        val cx = mapView.width / 2f
        val cy = mapView.height / 2f
        val m0 = world(cx, cy)
        val mx = world(cx + 100 * density, cy)
        val my = world(cx, cy + 100 * density)
        right = unit(mx.x - m0.x, mx.y - m0.y) ?: XY(1.0, 0.0)
        down = unit(my.x - m0.x, my.y - m0.y) ?: XY(0.0, 1.0)

        val content = SIDE - 2 * BORDER
        perLoupePoint = metresAcross * perMetre / content

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val c = place(SIDE, GAP, PointF(touch.x / density, touch.y / density), area(root))
        val side = (SIDE * density).roundToInt()
        val left = (c.x * density).roundToInt() - side / 2
        val top = (c.y * density).roundToInt() - side / 2
        frameRect = RectF(left.toFloat(), top.toFloat(), (left + side).toFloat(), (top + side).toFloat())
        val frame = LoupeFrame(activity)
        (loupe.parent as? ViewGroup)?.removeView(loupe) // still in the square fading out
        val inner = (content * density).roundToInt()
        frame.addView(
            loupe,
            FrameLayout.LayoutParams(inner, inner).apply {
                leftMargin = (BORDER * density).roundToInt()
                topMargin = leftMargin
            },
        )
        loupe.scene = scene
        loupe.heading = map.cameraPosition.bearing
        loupe.marker = if (which == MovablePoint.Selected) LoupeView.Marker.pin else LoupeView.Marker.measure
        root.addView(frame, FrameLayout.LayoutParams(side, side).apply {
            leftMargin = left
            topMargin = top
        })
        frameView = frame
        update()
        val now = System.nanoTime() / 1e9 // Choreographer's frame times are on this clock
        snapshot(now)

        // In with a little spring (iOS: 0.25 s, damping 0.75).
        frame.scaleX = 0.6f
        frame.scaleY = 0.6f
        frame.alpha = 0f
        frame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).setInterpolator(OvershootInterpolator(0.6f))
        lastTick = now
        ticking = true
        choreographer.postFrameCallback(frameCallback)
    }

    /** The finger moved (the content root's pixels). */
    fun moved(touch: PointF) {
        if (which == null) return
        finger = XY(touch.x / density - touchStart.x, touch.y / density - touchStart.y)
        place()
    }

    /** The finger was lifted (or the press cancelled: the point goes back). */
    fun end(cancelled: Boolean = false) {
        ticking = false
        choreographer.removeFrameCallback(frameCallback)
        frameView?.let { frame ->
            frameView = null
            frame.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator()) // iOS's default ease in and out
                .withEndAction { (frame.parent as? ViewGroup)?.removeView(frame) }
        }
        val w = which ?: return
        which = null
        val final = if (cancelled) start else point
        onMove?.invoke(w, WebMercator.geo(final.x, final.y), true)
    }

    /** The map is going: no square, no frames, no word of the point. */
    fun stop() {
        onMove = null
        end(cancelled = true)
    }

    /** The point from the finger: as far as it went at the square's scale, up to 5 m from the square's middle. */
    private fun place() {
        val w = which ?: return
        val (dir, metres) = fingerGround(finger, right, down, perLoupePoint, perMetre)
        val along = along(metres, perMetre)
        point = XY(centre.x + dir.x * along, centre.y + dir.y * along)
        update()
        onMove?.invoke(w, WebMercator.geo(point.x, point.y), false)
    }

    /** Beyond 5 m the square and the map under it scroll toward the finger: 1.5 m/s for every metre further, up to
     *  20 m/s. Each display frame (iOS `CADisplayLink`), `now` in seconds. */
    private fun tick(now: Double) {
        val dt = min(0.1, max(0.0, now - lastTick))
        lastTick = now
        val (dir, metres) = fingerGround(finger, right, down, perLoupePoint, perMetre)
        if (metres <= freeRadius) return
        val step = scrollStep(metres, dt, perMetre)
        val dx = dir.x * step
        val dy = dir.y * step
        centre = XY(centre.x + dx, centre.y + dy)
        val t = map.cameraPosition.target ?: return
        val c = WebMercator.world(GeoPoint(t.latitude, t.longitude))
        val moved = WebMercator.geo(c.x + dx, c.y + dy)
        map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(moved.lat, moved.lon)))
        place()
        // The glass shows the map under it as it scrolls.
        if (now - lastSnapshot >= 0.2) snapshot(now)
    }

    private fun update() {
        loupe.centre = centre
        loupe.point = point
        loupe.metresAcross = metresAcross
        loupe.moved = Geo.distance(WebMercator.geo(start.x, start.y), WebMercator.geo(point.x, point.y))
        loupe.invalidate()
    }

    /** The map under the square, for its glass rim (iOS blurs what is under it live; here a snapshot of the map,
     *  taken again every 200 ms while the map scrolls). */
    private fun snapshot(now: Double) {
        lastSnapshot = now
        val frame = frameView ?: return
        val rect = RectF(frameRect)
        map.snapshot { bitmap ->
            if (frameView !== frame) return@snapshot
            crop(bitmap, rect)?.let { frame.setGlass(it) }
        }
    }

    /** The part of the map's snapshot under `rect` (the content root's pixels). */
    private fun crop(bitmap: Bitmap, rect: RectF): Bitmap? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        if (mapView.width <= 0 || mapView.height <= 0) return null
        val r = IntArray(2).also { root.getLocationOnScreen(it) }
        val m = IntArray(2).also { mapView.getLocationOnScreen(it) }
        val sx = bitmap.width.toFloat() / mapView.width
        val sy = bitmap.height.toFloat() / mapView.height
        val x0 = ((rect.left + r[0] - m[0]) * sx).roundToInt().coerceIn(0, bitmap.width - 1)
        val y0 = ((rect.top + r[1] - m[1]) * sy).roundToInt().coerceIn(0, bitmap.height - 1)
        val x1 = ((rect.right + r[0] - m[0]) * sx).roundToInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = ((rect.bottom + r[1] - m[1]) * sy).roundToInt().coerceIn(y0 + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
    }

    /** The map's world point at a spot of the map view (pixels); longitudes as the map gives them, so nearby spots stay
     *  near each other across the date line. */
    private fun world(x: Float, y: Float): XY {
        val c = map.projection.fromScreenLocation(PointF(x, y))
        return WebMercator.world(GeoPoint(c.latitude, c.longitude))
    }

    /** Where the square may go: the content root inside the system bars and the display cutout, in dp. */
    private fun area(root: View): RectF {
        val insets = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val l = insets?.left ?: 0
        val t = insets?.top ?: 0
        val r = root.width - (insets?.right ?: 0)
        val b = root.height - (insets?.bottom ?: 0)
        return RectF(l / density, t / density, r / density, b / density)
    }

    companion object {
        /** Ground across the square, and how far the point goes from its middle before the map scrolls. */
        const val metresAcross = 15.0
        const val freeRadius = 5.0

        /** The square (dp): its side, its glass rim, and its gap above the finger. */
        const val SIDE = 232f
        const val BORDER = 8f
        const val GAP = 44f

        /** Where the square goes (its centre): above the finger, else below it, else (landscape) beside it, on the side
         *  with more room; always on screen, and never under the finger when it fits anywhere. */
        fun place(side: Float, gap: Float, touch: PointF, area: RectF): PointF {
            val r = RectF(area.left + 8, area.top + 8, area.right - 8, area.bottom - 8)
            val half = side / 2
            fun clampX(x: Float) = min(max(x, r.left + half), r.right - half)
            fun clampY(y: Float) = min(max(y, r.top + half), r.bottom - half)
            if (touch.y - gap - side >= r.top) return PointF(clampX(touch.x), touch.y - gap - half)
            if (touch.y + gap + side <= r.bottom) return PointF(clampX(touch.x), touch.y + gap + half)
            val left = touch.x - r.left
            val right = r.right - touch.x
            val x = if (left >= right) touch.x - gap - half else touch.x + gap + half
            return PointF(clampX(x), clampY(touch.y))
        }

        /** The finger's way from where it pressed (`finger`, dp), on the ground: a direction in world units and a
         *  distance in metres at the square's scale. */
        fun fingerGround(finger: XY, right: XY, down: XY, perLoupePoint: Double, perMetre: Double): Pair<XY, Double> {
            val gx = finger.x * right.x + finger.y * down.x
            val gy = finger.x * right.y + finger.y * down.y
            val dir = unit(gx, gy) ?: return XY(0.0, 0.0) to 0.0
            return dir to hypot(finger.x, finger.y) * perLoupePoint / perMetre
        }

        /** How far the point is from the square's middle (world units): where the finger took it, up to 5 m. */
        fun along(metres: Double, perMetre: Double): Double = min(metres, freeRadius) * perMetre

        /** How far the square and the map scroll in `dt` seconds (world units): 1.5 m/s for every metre the finger is
         *  beyond 5 m, up to 20 m/s; nothing within 5 m. */
        fun scrollStep(metres: Double, dt: Double, perMetre: Double): Double =
            if (metres > freeRadius) min(20.0, 1.5 * (metres - freeRadius)) * dt * perMetre else 0.0

        fun unit(x: Double, y: Double): XY? {
            val l = hypot(x, y)
            return if (l > 1e-12) XY(x / l, y / l) else null
        }
    }
}

/** The square's frame: a glass rim (a blurred, darkened snapshot of the map under it: Liquid Glass and iOS's frosted
 *  materials have no Android counterpart, and the rim is only 8 dp wide), a thin white border, and a shadow. The
 *  `LoupeView` goes inside. It takes no touches. */
class LoupeFrame(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density

    /** What the rim shows: the map under the square, blurred (`RenderEffect` from Android 12; before, shrunk to an
     *  eighth and stretched back, with a dark tint), until then a dark grey. */
    private val glass = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(0xB31C1C1E.toInt())
        outlineProvider = rounded(32 * density)
        clipToOutline = true
        if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(PorterDuffColorFilter(TINT, PorterDuff.Mode.SRC_OVER)),
                    RenderEffect.createBlurEffect(20 * density, 20 * density, Shader.TileMode.CLAMP),
                ),
            )
        } else {
            foreground = ColorDrawable(TINT)
        }
    }

    /** iOS's material border: 1 point, white 45 %, inside the 32 point corners. */
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x73FFFFFF
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWillNotDraw(false)
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // iOS: a shadow of opacity 0.35, radius 14, 6 points down, the shape of the rounded square.
        outlineProvider = rounded(32 * density)
        elevation = 14 * density
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = Color.BLACK
            outlineAmbientShadowColor = Color.BLACK
        }
    }

    /** The map under the square, for the rim. */
    fun setGlass(map: Bitmap) {
        if (Build.VERSION.SDK_INT >= 31) {
            glass.setImageBitmap(map)
        } else {
            val small = Bitmap.createScaledBitmap(map, max(1, map.width / 8), max(1, map.height / 8), true)
            glass.setImageBitmap(small)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = density / 2
        val r = 32 * density - inset
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, border)
    }

    companion object {
        /** The rim's dark tint (black 35 %), as the dark material. */
        private const val TINT = 0x59000000

        fun rounded(radius: Float) = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }
}

/** The magnified ground inside the square: the imagery (`SatelliteRenderer.drawImagery`) turned as the map is, the
 *  blocks, the measuring line and points, you, the point being moved, a ring 5 m from the middle (beyond it the map
 *  scrolls) and how far the point has moved. */
class LoupeView(context: Context) : View(context) {
    /** A block's outline (or line) in world units, with its colour and its box. */
    data class Line(val points: List<XY>, val colour: Int, val closed: Boolean, val bounds: WorldRect?)

    /** A KML point, with its colour. */
    data class Dot(val point: XY, val colour: Int)

    /** What is drawn over the imagery, in world units: lines (with their colour), other points, and you. */
    class Scene(
        val lines: List<Line> = emptyList(),
        val dots: List<Dot> = emptyList(),
        /** The measuring line through the points (the one being moved at `moving`), and from you for a single point. */
        val measure: List<XY> = emptyList(),
        val measureClosed: Boolean = false,
        val moving: Int? = null,
        val user: XY? = null,
    )

    enum class Marker { pin, measure }

    var scene = Scene()
    var centre = XY(0.0, 0.0)
    var point = XY(0.0, 0.0)
    var heading = 0.0
    var metresAcross = 15.0
    var moved = 0.0
    var marker = Marker.measure

    /** The imagery level drawn, so tiles that arrive for it show (`DrawnMap.announce`). */
    private val map = DrawnMap()
    private val density = resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val measureDash = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    private val ringDash = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    private val captionText = MapImages.textPaint(12 * density, MapImages.SEMIBOLD).apply {
        fontFeatureSettings = "tnum" // iOS monospacedDigitSystemFont
    }
    private val captionBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }

    init {
        setBackgroundColor(Color.BLACK)
        outlineProvider = LoupeFrame.rounded(24 * density)
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        map.onArrival = { _, content ->
            if (content == SatelliteRenderer.Content.imagery) invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        val d = density
        val side = width / d.toDouble() // dp: the drawing is in dp, the canvas scaled
        val perMetre = 1 / Geo.metersPerWorldUnit(WebMercator.geo(centre.x, centre.y).lat)
        val k = side / (metresAcross * perMetre) // dp per world unit
        fun local(m: XY) = PointF(((m.x - centre.x) * k).toFloat(), ((m.y - centre.y) * k).toFloat())

        canvas.save()
        canvas.scale(d, d)
        canvas.translate((side / 2).toFloat(), (side / 2).toFloat())
        canvas.rotate(-heading.toFloat()) // as the map is turned
        // The ground under the square, turned: wide enough to fill its corners.
        val half = side * 0.75 / k
        val ground = WorldRect(centre.x - half, centre.y - half, centre.x + half, centre.y + half)
        val level = SatelliteRenderer.level(k * d)
        map.setFocus(ground, level, -1)
        val h = (half * k).toFloat()
        SatelliteRenderer.drawImagery(ground, level, canvas, RectF(-h, -h, h, h))

        val margin = 4 / k
        val near = WorldRect(ground.minX - margin, ground.minY - margin, ground.maxX + margin, ground.maxY + margin)
        for (line in scene.lines) {
            if (line.points.size < 2 || !overlap(line.bounds, near)) continue
            val path = path(line.points.map(::local), line.closed)
            stroke(canvas, path, 0x66000000, 4f)
            stroke(canvas, path, line.colour, 2f)
        }
        for (dot in scene.dots) {
            val p = local(dot.point)
            fill.color = dot.colour
            canvas.drawCircle(p.x, p.y, 5f, fill)
            stroke.color = Color.BLACK
            stroke.strokeWidth = 1f
            canvas.drawCircle(p.x, p.y, 5f, stroke)
        }
        // The measuring line, with the point being moved where it is now.
        val measure = scene.measure.toMutableList()
        scene.moving?.let { if (it in measure.indices) measure[it] = point }
        val user = scene.user
        if (user != null && measure.size == 1) measure.add(0, user)
        if (measure.size >= 2) {
            stroke.pathEffect = measureDash
            stroke(canvas, path(measure.map(::local), scene.measureClosed), MapColours.measurePink, 3f)
            stroke.pathEffect = null
        }
        for ((i, m) in scene.measure.withIndex()) {
            if (i == scene.moving) continue
            val p = local(m)
            stroke.color = MapColours.measurePink
            stroke.strokeWidth = 3f
            canvas.drawCircle(p.x, p.y, 9f, stroke)
        }
        if (user != null) {
            val p = local(user)
            fill.color = MapColours.systemBlue // blue here, whether or not the GPS sleeps
            canvas.drawCircle(p.x, p.y, 8f, fill)
            stroke.color = Color.WHITE
            stroke.strokeWidth = 2.5f
            canvas.drawCircle(p.x, p.y, 8f, stroke)
        }

        // The point being moved: a ring with a fine cross, so its exact spot shows.
        val p = local(point)
        stroke.color = 0x8C000000.toInt()
        stroke.strokeWidth = 5f
        canvas.drawCircle(p.x, p.y, 13f, stroke)
        stroke.color = if (marker == Marker.pin) Color.WHITE else MapColours.measurePink
        stroke.strokeWidth = 3f
        canvas.drawCircle(p.x, p.y, 13f, stroke)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 1f
        canvas.drawLine(p.x - 20, p.y, p.x - 4, p.y, stroke)
        canvas.drawLine(p.x + 4, p.y, p.x + 20, p.y, stroke)
        canvas.drawLine(p.x, p.y - 20, p.x, p.y - 4, stroke)
        canvas.drawLine(p.x, p.y + 4, p.x, p.y + 20, stroke)
        fill.color = Color.WHITE
        canvas.drawCircle(p.x, p.y, 1.5f, fill)
        canvas.restore()

        // Where the map starts to scroll: 5 m from the middle (in pixels from here on).
        val r = (PointAdjuster.freeRadius / metresAcross * width).toFloat()
        stroke.color = 0x8CFFFFFF.toInt()
        stroke.strokeWidth = d
        stroke.pathEffect = ringDash
        stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawCircle(width / 2f, height / 2f, r, stroke)
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND

        caption(canvas, "${metresAcross.toInt()} m", 8 * d, 6 * d, alignRight = false)
        caption(canvas, stringFormat("moved %.1f m", moved), width - 8 * d, height - 24 * d, alignRight = true)
    }

    private fun path(points: List<PointF>, closed: Boolean) = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (q in points.drop(1)) lineTo(q.x, q.y)
        if (closed) close()
    }

    private fun stroke(canvas: Canvas, path: Path, colour: Int, width: Float) {
        stroke.color = colour
        stroke.strokeWidth = width
        canvas.drawPath(path, stroke)
    }

    /** White 12 point semibold figures on a black 55 % capsule (6 × 2 points round the text), at `x`, `y` (pixels):
     *  the text's top left, or top right. */
    private fun caption(canvas: Canvas, text: String, x: Float, y: Float, alignRight: Boolean) {
        val fm = captionText.fontMetrics
        val w = captionText.measureText(text)
        val h = fm.descent - fm.ascent
        val left = if (alignRight) x - w else x
        val box = RectF(left - 6 * density, y - 2 * density, left + w + 6 * density, y + h + 2 * density)
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, captionBox)
        canvas.drawText(text, left, y - fm.ascent, captionText)
    }

    companion object {
        /** Whether two world rects meet (also for a line's flat box). */
        fun overlap(a: WorldRect?, b: WorldRect?): Boolean =
            a != null && b != null && a.minX <= b.maxX && a.maxX >= b.minX && a.minY <= b.maxY && a.maxY >= b.minY

        /** The box around points, from their own extremes (null for none). */
        fun box(pts: List<XY>): WorldRect? {
            if (pts.isEmpty()) return null
            return WorldRect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
    }
}
