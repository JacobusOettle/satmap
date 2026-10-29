package com.jacobusoettle.satmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
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
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Whether MapLibre's renderer on this device draws every kind of layer the map uses (DESIGN.md D1): a bare `MapView`
 *  (an empty style, nothing of SatMap's) with an icon symbol (your dot, the pins, every map text), a dashed line (the
 *  track and the measuring line), a plain line, a circle and a fill, checked in the map's own frame. With a SurfaceView
 *  (the phone) and a TextureView (the car). On the CI emulator the OpenGL-only MapLibre build drew no icon symbols and
 *  no dashed lines. */
@RunWith(AndroidJUnit4::class)
class MapRendererTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val background = Color.rgb(0x33, 0x55, 0x77)

    /** Each kind of layer, and the spots (lat, lon) it should colour. */
    private val probes = linkedMapOf<String, List<LatLng>>()

    @Test
    fun everyKindOfLayerIsDrawn() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val missing = draw(scenario, textureMode = false).map { "SurfaceView: $it" } +
                draw(scenario, textureMode = true).map { "TextureView: $it" }
            assertTrue("Not drawn: $missing", missing.isEmpty())
        } finally {
            scenario.close()
        }
    }

    /** The probes the map's frame doesn't show. */
    private fun draw(scenario: ActivityScenario<MainActivity>, textureMode: Boolean): List<String> {
        var view: MapView? = null
        var map: MapLibreMap? = null
        scenario.onActivity { activity ->
            val v = MapView(activity, MapLibreMapOptions.createFromAttributes(activity).textureMode(textureMode))
            view = v
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                v,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            v.onCreate(null)
            v.onStart()
            v.onResume()
            v.getMapAsync { m ->
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 10.0))
                m.setStyle(
                    Style.Builder().fromJson(
                        """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#335577"}}]}""",
                    ),
                ) { style ->
                    addProbes(style)
                    map = m
                }
            }
        }
        try {
            Thread.sleep(8_000)
            val frame = snapshot { map }
            if (frame == null) return listOf("no frame")
            val missing = ArrayList<String>()
            instrumentation.runOnMainSync {
                val m = map ?: return@runOnMainSync
                for ((probe, spots) in probes) {
                    val hits = spots.count { spot ->
                        val s = m.projection.toScreenLocation(spot)
                        val x = s.x.toInt()
                        val y = s.y.toInt()
                        x in 0 until frame.width && y in 0 until frame.height && coloured(frame.getPixel(x, y))
                    }
                    Log.i(TAG, "${if (textureMode) "TextureView" else "SurfaceView"}: $probe $hits/${spots.size}")
                    if (hits * 2 <= spots.size) missing += probe
                }
            }
            return missing
        } finally {
            scenario.onActivity {
                view?.let { v ->
                    v.onPause()
                    v.onStop()
                    v.onDestroy()
                    (v.parent as? ViewGroup)?.removeView(v)
                }
            }
        }
    }

    private fun snapshot(map: () -> MapLibreMap?): Bitmap? {
        val latch = CountDownLatch(1)
        var frame: Bitmap? = null
        instrumentation.runOnMainSync {
            val m = map()
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
        return frame
    }

    private fun coloured(c: Int): Boolean =
        abs(Color.red(c) - Color.red(background)) + abs(Color.green(c) - Color.green(background)) +
            abs(Color.blue(c) - Color.blue(background)) > 60

    private fun addProbes(style: Style) {
        val d = instrumentation.targetContext.resources.displayMetrics
        val square = Bitmap.createBitmap((24 * d.density).toInt(), (24 * d.density).toInt(), Bitmap.Config.ARGB_8888)
        square.eraseColor(Color.RED)
        square.density = d.densityDpi
        style.addImage("square", square)

        fun point(id: String, lat: Double, lon: Double) =
            style.addSource(GeoJsonSource(id, Feature.fromGeometry(Point.fromLngLat(lon, lat))))
        fun line(id: String, lat: Double) = style.addSource(
            GeoJsonSource(
                id,
                Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(-0.15, lat), Point.fromLngLat(0.15, lat)))),
            ),
        )
        fun along(lat: Double) = (0 until 12).map { LatLng(lat, -0.12 + it * 0.022) }

        point("symbol", 0.2, -0.08)
        style.addLayer(
            SymbolLayer("symbol", "symbol").withProperties(iconImage("square"), iconAllowOverlap(true), iconIgnorePlacement(true)),
        )
        probes["icon symbol"] = listOf(LatLng(0.2, -0.08))

        point("circle", 0.2, 0.08)
        style.addLayer(CircleLayer("circle", "circle").withProperties(circleColor(Color.RED), circleRadius(12f)))
        probes["circle"] = listOf(LatLng(0.2, 0.08))

        // Dashes 1 line width long, 0.01 apart: nearly solid, so every spot along it is coloured.
        line("dashed", 0.1)
        style.addLayer(
            LineLayer("dashed", "dashed").withProperties(lineColor(Color.MAGENTA), lineWidth(8f), lineDasharray(arrayOf(1f, 0.01f))),
        )
        probes["dashed line"] = along(0.1)

        line("plain", 0.0)
        style.addLayer(LineLayer("plain", "plain").withProperties(lineColor(Color.MAGENTA), lineWidth(8f)))
        probes["plain line"] = along(0.0)

        style.addSource(
            GeoJsonSource(
                "fill",
                Feature.fromGeometry(
                    Polygon.fromLngLats(
                        listOf(
                            listOf(
                                Point.fromLngLat(-0.1, -0.13), Point.fromLngLat(0.1, -0.13), Point.fromLngLat(0.1, -0.07),
                                Point.fromLngLat(-0.1, -0.07), Point.fromLngLat(-0.1, -0.13),
                            ),
                        ),
                    ),
                ),
            ),
        )
        style.addLayer(FillLayer("fill", "fill").withProperties(fillColor(Color.GREEN)))
        probes["fill"] = (0 until 5).map { LatLng(-0.1, -0.08 + it * 0.04) }
    }

    private companion object {
        const val TAG = "MapRendererTest"
    }
}
