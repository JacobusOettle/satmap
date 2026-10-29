package com.jacobusoettle.satmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillPattern
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.linePattern
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Debug only: a bare MapView (empty style, no app content) over SatMap's screen, drawing one of each kind of layer —
 *  an icon symbol, a circle, a dashed line, a plain line, a line pattern, a fill pattern and a plain fill — and checking
 *  the map's own frame for each: does MapLibre draw it on this device? Once with a SurfaceView, once with a
 *  TextureView. Results go to the log (tag BareMap) and files/overlay-debug/bare.txt. */
@RunWith(AndroidJUnit4::class)
class BareMapTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val out = StringBuilder()
    private val folder: File by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "overlay-debug").apply { mkdirs() }
    }

    private val background = Color.rgb(0x33, 0x55, 0x77)

    /** Each probe: a name and the points (lat, lon) where it should colour the map. */
    private val probes = linkedMapOf<String, List<LatLng>>()

    @Test
    fun bareMap() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(5_000)
            run(scenario, "surface", textureMode = false)
            run(scenario, "texture", textureMode = true)
        } finally {
            scenario.close()
            File(folder, "bare.txt").writeText(out.toString())
        }
    }

    private fun run(scenario: ActivityScenario<MainActivity>, name: String, textureMode: Boolean) {
        var view: MapView? = null
        var map: MapLibreMap? = null
        scenario.onActivity { activity ->
            try {
                val v = MapView(activity, MapLibreMapOptions.createFromAttributes(activity).textureMode(textureMode))
                view = v
                activity.findViewById<ViewGroup>(android.R.id.content)
                    .addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                v.onCreate(null)
                v.onStart()
                v.onResume()
                v.getMapAsync { m ->
                    map = m
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 10.0))
                    m.setStyle(
                        Style.Builder().fromJson(
                            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#335577"}}]}""",
                        ),
                    ) { style ->
                        try {
                            shapes(style)
                        } catch (e: Throwable) {
                            line("$name: shapes threw ${Log.getStackTraceString(e)}")
                        }
                    }
                }
            } catch (e: Throwable) {
                line("$name: ${Log.getStackTraceString(e)}")
            }
        }
        Thread.sleep(8_000)
        val latch = CountDownLatch(1)
        var frame: Bitmap? = null
        instrumentation.runOnMainSync {
            val m = map
            if (m == null) {
                latch.countDown()
            } else {
                m.snapshot { b ->
                    frame = b
                    latch.countDown()
                }
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        val b = frame
        if (b == null) {
            line("$name: no frame")
        } else {
            FileOutputStream(File(folder, "bare-$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            instrumentation.runOnMainSync {
                val m = map ?: return@runOnMainSync
                for ((probe, points) in probes) {
                    var hits = 0
                    for (p in points) {
                        val s: PointF = m.projection.toScreenLocation(p)
                        val x = s.x.toInt()
                        val y = s.y.toInt()
                        if (x in 0 until b.width && y in 0 until b.height && differs(b.getPixel(x, y))) hits++
                    }
                    line("$name: $probe ${if (hits * 2 > points.size) "DRAWN" else "MISSING"} ($hits/${points.size})")
                }
            }
        }
        scenario.onActivity {
            view?.let { v ->
                v.onPause()
                v.onStop()
                v.onDestroy()
                (v.parent as? ViewGroup)?.removeView(v)
            }
        }
    }

    private fun differs(c: Int): Boolean =
        abs(Color.red(c) - Color.red(background)) + abs(Color.green(c) - Color.green(background)) +
            abs(Color.blue(c) - Color.blue(background)) > 60

    private fun shapes(style: Style) {
        val d = instrumentation.targetContext.resources.displayMetrics
        val square = Bitmap.createBitmap((24 * d.density).toInt(), (24 * d.density).toInt(), Bitmap.Config.ARGB_8888)
        square.eraseColor(Color.RED)
        square.density = d.densityDpi
        style.addImage("square", square)
        val checker = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (x in 0 until 8) for (y in 0 until 8) checker.setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.YELLOW else Color.WHITE)
        style.addImage("checker", checker)

        fun point(id: String, lat: Double, lon: Double) =
            style.addSource(GeoJsonSource(id, Feature.fromGeometry(Point.fromLngLat(lon, lat))))
        fun lineAt(id: String, lat: Double) = style.addSource(
            GeoJsonSource(id, Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(-0.15, lat), Point.fromLngLat(0.15, lat))))),
        )
        fun boxAt(id: String, lat: Double) = style.addSource(
            GeoJsonSource(
                id,
                Feature.fromGeometry(
                    Polygon.fromLngLats(
                        listOf(
                            listOf(
                                Point.fromLngLat(-0.1, lat - 0.03), Point.fromLngLat(0.1, lat - 0.03),
                                Point.fromLngLat(0.1, lat + 0.03), Point.fromLngLat(-0.1, lat + 0.03),
                                Point.fromLngLat(-0.1, lat - 0.03),
                            ),
                        ),
                    ),
                ),
            ),
        )
        fun along(lat: Double) = (0 until 12).map { LatLng(lat, -0.12 + it * 0.022) }
        fun inBox(lat: Double) = (0 until 5).map { LatLng(lat, -0.08 + it * 0.04) }

        point("symbol", 0.25, -0.08)
        style.addLayer(SymbolLayer("symbol", "symbol").withProperties(iconImage("square"), iconAllowOverlap(true), iconIgnorePlacement(true)))
        probes["icon symbol"] = listOf(LatLng(0.25, -0.08))

        point("circle", 0.25, 0.08)
        style.addLayer(CircleLayer("circle", "circle").withProperties(circleColor(Color.RED), circleRadius(12f)))
        probes["circle"] = listOf(LatLng(0.25, 0.08))

        lineAt("dash", 0.15)
        style.addLayer(LineLayer("dash", "dash").withProperties(lineColor(Color.MAGENTA), lineWidth(8f), lineDasharray(arrayOf(1f, 0.01f))))
        probes["dashed line (dashes nearly solid)"] = along(0.15)

        lineAt("plain", 0.05)
        style.addLayer(LineLayer("plain", "plain").withProperties(lineColor(Color.MAGENTA), lineWidth(8f)))
        probes["plain line"] = along(0.05)

        lineAt("pattern", -0.05)
        style.addLayer(LineLayer("pattern", "pattern").withProperties(linePattern("checker"), lineWidth(10f)))
        probes["line pattern"] = along(-0.05)

        boxAt("fillpattern", -0.17)
        style.addLayer(FillLayer("fillpattern", "fillpattern").withProperties(fillPattern("checker")))
        probes["fill pattern"] = inBox(-0.17)

        boxAt("fill", -0.3)
        style.addLayer(FillLayer("fill", "fill").withProperties(fillColor(Color.GREEN)))
        probes["plain fill"] = inBox(-0.3)
    }

    private fun line(s: String) {
        Log.i("BareMap", s)
        synchronized(out) { out.append(s).append('\n') }
    }
}
