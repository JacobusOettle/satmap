package com.jacobusoettle.satmap.map

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.WebMercator
import com.jacobusoettle.satmap.core.XY
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** For the zoom test (.github/workflows/android-zoomtest.yml): with the launch setting `zoomScript`, the map runs
 *  through the same zooms and pans every time (no gestures, no following you), logging each step, so recordings of
 *  different builds can be compared step by step. */
object ZoomScript {
    private const val TAG = "zoomscript"

    /** A step: the tile zoom (iOS's, of 256-point tiles), and the east-west and north-south shift in screens. */
    data class Step(val zoom: Double, val dx: Double, val dy: Double)

    /** The steps, five seconds apart. */
    val steps = listOf(
        Step(14.0, 0.0, 0.0),
        Step(15.0, 0.0, 0.0), Step(16.0, 0.0, 0.0), Step(17.0, 0.0, 0.0), Step(18.0, 0.0, 0.0), // in, to levels not seen yet
        Step(17.0, 0.0, 0.0), Step(16.0, 0.0, 0.0), Step(15.0, 0.0, 0.0), // out, to levels seen before
        Step(15.0, 1.0, 0.0), // a new place
        Step(17.0, 0.0, 0.0), // two levels in there
        Step(14.0, 0.0, 0.0), // three out
        Step(14.0, 0.0, -1.0), // and on to a new place
        Step(15.15, 0.0, 0.0), // in, to between two levels: imagery tiles show smaller than at a whole level, so more of them
    )

    val start = GeoPoint(-33.9183, 18.9212)

    /** The step under way, shown in the top left corner as four squares (white for 1: step + 1 in binary, bit 0 on
     *  the left), outside the part of the screen measured, so a recording can be matched to the steps frame by frame.
     *  In dp. */
    const val markerX = 8f
    const val markerY = 110f
    const val markerPitch = 12f
    const val markerSize = 10f

    /** The script running on a map: `stop()` when the map goes (iOS's runs as long as the app). */
    class Run internal constructor(private val handler: Handler, private val watch: Watchdog, private val squares: List<View>) {
        fun stop() {
            handler.removeCallbacksAndMessages(null)
            watch.stop()
            for (v in squares) (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    /** Runs the script on the map in `container` (the squares go there) when the launch setting asks for it. */
    fun runIfAsked(container: ViewGroup, mapView: MapView, map: MapLibreMap, model: AppModel): Run? {
        if (Defaults.bool("zoomScript") != true) return null
        var center = WebMercator.world(start)
        val d = container.resources.displayMetrics.density
        val size = (markerSize * d).roundToInt()
        val squares = (0 until 4).map { i ->
            View(container.context).apply {
                setBackgroundColor(Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                container.addView(
                    this,
                    FrameLayout.LayoutParams(size, size).apply {
                        leftMargin = ((markerX + i * markerPitch) * d).roundToInt()
                        topMargin = (markerY * d).roundToInt()
                    },
                )
            }
        }
        // Logs each time the main thread was held up for over half a second (the screen doesn't update meanwhile).
        val watch = Watchdog().also { it.start() }
        val handler = Handler(Looper.getMainLooper())
        for ((i, step) in steps.withIndex()) {
            handler.postDelayed({
                if (mapView.isDestroyed) return@postDelayed
                for ((bit, v) in squares.withIndex()) {
                    v.setBackgroundColor(if ((i + 1) shr bit and 1 == 1) Color.WHITE else Color.BLACK)
                }
                model.setFollowing(false)
                val (c, zoom) = camera(center, step, mapView.width / d.toDouble(), mapView.height / d.toDouble())
                center = c
                Log.i(TAG, "step $i zoom ${step.zoom}")
                val target = WebMercator.geo(c.x, c.y)
                val camera = CameraPosition.Builder(map.cameraPosition).target(LatLng(target.lat, target.lon)).zoom(zoom).build()
                val update = CameraUpdateFactory.newCameraPosition(camera)
                if (i > 0) map.easeCamera(update) else map.moveCamera(update)
            }, 20_000L + 5_000L * i)
        }
        return Run(handler, watch, squares)
    }

    /** A step's camera on a view `width` × `height` dp, from the centre before (world units, the world 1 × 1): the new
     *  centre, moved by the step's screens, and MapLibre's zoom. The script's zoom `z` shows the world 256·2^z dp
     *  wide (iOS's visible rect: `world / 2^z × width / 256` across); MapLibre's world is 512·2^zoom dp wide, so its
     *  zoom is z − 1. */
    fun camera(center: XY, step: Step, width: Double, height: Double): Pair<XY, Double> {
        val w = 1 / 2.0.pow(step.zoom) * width / 256
        val h = w * (height / max(1.0, width))
        return XY(center.x + step.dx * w, center.y + step.dy * h) to step.zoom - 1
    }
}

/** Logs "main thread held up N ms" when a tick, due every 0.1 s, comes more than 500 ms after the one before. */
class Watchdog {
    private val handler = Handler(Looper.getMainLooper())
    private var last = SystemClock.uptimeMillis()
    private val ticking = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 100)
        }
    }

    fun start() {
        last = SystemClock.uptimeMillis()
        handler.postDelayed(ticking, 100)
    }

    fun stop() {
        handler.removeCallbacks(ticking)
    }

    fun tick() {
        val now = SystemClock.uptimeMillis()
        val ms = now - last
        if (ms > 500) Log.i("zoomscript", "main thread held up $ms ms")
        last = now
    }
}
