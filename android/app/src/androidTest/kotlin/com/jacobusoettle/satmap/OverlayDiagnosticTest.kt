package com.jacobusoettle.satmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapIds
import com.jacobusoettle.satmap.map.MapImages
import com.jacobusoettle.satmap.map.MapStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Why the map's own overlays (your dot, the track, the measuring line and markers, the selected pin, the KML names and
 *  points) may not show: launches SatMap at the demo farm and writes what the style holds (every layer in order with
 *  its visibility, every source with its feature count, the images, what MapLibre has rendered in each overlay layer,
 *  and any exception from installing the overlays again), before and after a measuring point is placed, with a
 *  screenshot of the screen and of the map's own frame each time. The dump goes to the log (tag OverlayDiag) and to
 *  files/overlay-debug in the app's external files. */
@RunWith(AndroidJUnit4::class)
class OverlayDiagnosticTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val farm = GeoPoint(-33.9245, 18.9232)
    private val out = StringBuilder()

    private val folder: File by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "overlay-debug").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun dumpOverlays() {
        Logger.setVerbosity(Logger.VERBOSE)
        TestLocations.setUp()
        try {
            TestLocations.set(GeoPoint(-33.9235, 18.9234))
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                val m = waitForStyle(90)
                line("style loaded: ${m != null}")
                // A few fixes, for a track, ending at the farm.
                TestLocations.walk(
                    listOf(
                        GeoPoint(-33.9235, 18.9234),
                        GeoPoint(-33.9238, 18.9233),
                        GeoPoint(-33.9241, 18.9233),
                        GeoPoint(-33.9243, 18.9232),
                        farm,
                    ),
                )
                // The farm and the KML point 'Launch 1' on screen; no following, so the crosshair stays put.
                main {
                    AppModel.shared.setFollowing(false)
                    map()?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-33.9215, 18.9235), 15.3))
                }
                Thread.sleep(15_000) // tiles, KML labels
                phase("1-start")

                main {
                    AppModel.shared.measureButtonTapped()
                    AppModel.shared.measureButtonTapped()
                }
                Thread.sleep(6_000)
                phase("2-measure")

                // Installing the overlays (and drawing the base images) again: any exception is the evidence.
                main {
                    val map = map()
                    val style = map?.style
                    try {
                        val images = MapImages.base(instrumentation.targetContext)
                        line("MapImages.base: ${images.map { "${it.key}=${it.value.width}x${it.value.height}/${it.value.config}/dpi${it.value.density}" }}")
                        if (style != null) {
                            MapStyle.installOverlays(style, images)
                            line("installOverlays again: no exception")
                        }
                    } catch (e: Throwable) {
                        line("installOverlays/base threw: ${Log.getStackTraceString(e)}")
                    }
                }
                Thread.sleep(3_000)
                phase("3-reinstalled")

                // Probes that need no image and no dashes: is each source fed, and does MapLibre draw it?
                main {
                    val style = map()?.style ?: return@main
                    try {
                        style.addLayer(
                            CircleLayer("diag-user", MapIds.USER).withProperties(circleColor(Color.RED), circleRadius(14f)),
                        )
                        style.addLayer(
                            CircleLayer("diag-points", MapIds.MEASURE_POINTS)
                                .withProperties(circleColor(Color.MAGENTA), circleRadius(10f)),
                        )
                        style.addLayer(
                            LineLayer("diag-measure", MapIds.MEASURE).withProperties(lineColor(Color.GREEN), lineWidth(6f)),
                        )
                        style.addLayer(
                            LineLayer("diag-track-live", MapIds.TRACK_LIVE).withProperties(lineColor(Color.BLUE), lineWidth(6f)),
                        )
                        for (s in style.sources) {
                            if (s.id.startsWith("kml/") && s.id.endsWith("/labels")) {
                                style.addLayer(
                                    CircleLayer("diag-${s.id}", s.id).withProperties(circleColor(Color.CYAN), circleRadius(8f)),
                                )
                            }
                        }
                        line("probe layers added")
                    } catch (e: Throwable) {
                        line("probe layers threw: ${Log.getStackTraceString(e)}")
                    }
                }
                Thread.sleep(4_000)
                phase("4-probes")

                // A new symbol layer (a plain square image) and a new dashed line beside a plain one, on new sources.
                main {
                    val style = map()?.style ?: return@main
                    try {
                        addTestShapes(style, LatLng(-33.9215, 18.9250), "diag2")
                        line("new symbol/dash probes added")
                    } catch (e: Throwable) {
                        line("new probes threw: ${Log.getStackTraceString(e)}")
                    }
                }
                Thread.sleep(4_000)
                phase("5-new-probes")

            } finally {
                scenario.close()
            }
        } finally {
            TestLocations.tearDown()
            File(folder, "dump.txt").writeText(out.toString())
        }
    }

    /** A square symbol (red, 24 dp, a new image), a circle beside it, and a dashed line above a plain one, around
     *  `at`, on new sources (`prefix`-…). */
    private fun addTestShapes(style: Style, at: LatLng, prefix: String) {
        val d = instrumentation.targetContext.resources.displayMetrics
        val square = Bitmap.createBitmap((24 * d.density).toInt(), (24 * d.density).toInt(), Bitmap.Config.ARGB_8888)
        square.eraseColor(Color.RED)
        square.density = d.densityDpi
        style.addImage("$prefix-square", square)
        val dLon = 0.0006
        val dLat = 0.0004
        style.addSource(
            GeoJsonSource(
                "$prefix-points",
                FeatureCollection.fromFeatures(
                    listOf(
                        Feature.fromGeometry(Point.fromLngLat(at.longitude - dLon, at.latitude)).apply { addStringProperty("k", "s") },
                        Feature.fromGeometry(Point.fromLngLat(at.longitude + dLon, at.latitude)).apply { addStringProperty("k", "c") },
                    ),
                ),
            ),
        )
        style.addSource(
            GeoJsonSource(
                "$prefix-lines",
                FeatureCollection.fromFeatures(
                    listOf(
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                listOf(
                                    Point.fromLngLat(at.longitude - 2 * dLon, at.latitude + dLat),
                                    Point.fromLngLat(at.longitude + 2 * dLon, at.latitude + dLat),
                                ),
                            ),
                        ).apply { addStringProperty("k", "dash") },
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                listOf(
                                    Point.fromLngLat(at.longitude - 2 * dLon, at.latitude - dLat),
                                    Point.fromLngLat(at.longitude + 2 * dLon, at.latitude - dLat),
                                ),
                            ),
                        ).apply { addStringProperty("k", "plain") },
                    ),
                ),
            ),
        )
        style.addLayer(
            SymbolLayer("$prefix-symbol", "$prefix-points")
                .withFilter(Expression.eq(Expression.get("k"), "s"))
                .withProperties(iconImage("$prefix-square"), iconAllowOverlap(true), iconIgnorePlacement(true)),
        )
        style.addLayer(
            CircleLayer("$prefix-circle", "$prefix-points")
                .withFilter(Expression.eq(Expression.get("k"), "c"))
                .withProperties(circleColor(Color.RED), circleRadius(12f)),
        )
        style.addLayer(
            LineLayer("$prefix-dash", "$prefix-lines")
                .withFilter(Expression.eq(Expression.get("k"), "dash"))
                .withProperties(lineColor(Color.MAGENTA), lineWidth(8f), lineDasharray(arrayOf(2f, 1f))),
        )
        style.addLayer(
            LineLayer("$prefix-plain", "$prefix-lines")
                .withFilter(Expression.eq(Expression.get("k"), "plain"))
                .withProperties(lineColor(Color.MAGENTA), lineWidth(8f)),
        )
    }

    // The dump

    private fun phase(name: String) {
        line("===== $name =====")
        main { dump() }
        screenshot(name)
        snapshot(name)
    }

    private fun dump() {
        val controller = AppModel.shared.map as? MapController
        val m = map()
        if (controller == null || m == null) {
            line("no map: controller=$controller map=$m")
            return
        }
        val style = m.style
        line("camera: ${m.cameraPosition}")
        line("style: $style loaded=${style?.isFullyLoaded}")
        if (style == null) return
        val model = AppModel.shared
        line("model: location=${model.recorder.location.value} track=${model.track.size} measure=${model.measure.value} selected=${model.selectedPoint.value} following=${model.following.value}")
        line("kml files: ${model.kml.files.value.map { "${it.id} ${it.name} shown=${it.isShown}" }}")

        val mapView = controller.container.getChildAt(0)
        line("map view: ${mapView.javaClass.simpleName} ${mapView.width}x${mapView.height}")
        val screen = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())

        line("-- layers (bottom to top) --")
        for ((i, layer) in style.layers.withIndex()) {
            val source = when (layer) {
                is SymbolLayer -> layer.sourceId
                is LineLayer -> layer.sourceId
                is CircleLayer -> layer.sourceId
                is org.maplibre.android.style.layers.FillLayer -> layer.sourceId
                is org.maplibre.android.style.layers.RasterLayer -> layer.sourceId
                else -> "-"
            }
            val rendered = try {
                m.queryRenderedFeatures(screen, layer.id).size
            } catch (e: Throwable) {
                -1
            }
            val extra = when (layer) {
                is SymbolLayer -> " iconImage=${layer.iconImage} filter=${layer.filter}"
                is LineLayer -> " dash=${layer.lineDasharray} filter=${layer.filter}"
                else -> ""
            }
            line("$i ${layer.id} ${layer.javaClass.simpleName} source=$source visibility=${layer.visibility.value} " +
                "zoom=${layer.minZoom}..${layer.maxZoom} rendered=$rendered$extra")
        }

        line("-- sources --")
        for (s in style.sources) {
            val count = if (s is GeoJsonSource) {
                try {
                    s.querySourceFeatures(null).size.toString()
                } catch (e: Throwable) {
                    "threw ${e.javaClass.simpleName}: ${e.message}"
                }
            } else {
                "-"
            }
            line("${s.id} ${s.javaClass.simpleName} features=$count")
        }

        line("-- images --")
        val ids = mutableListOf(
            MapImages.USER_DOT, MapImages.USER_DOT_GREY, MapImages.USER_ARROW, MapImages.USER_ARROW_GREY,
            MapImages.MEASURE_MARKER, MapImages.SELECTED, MapImages.letterId("A"), MapImages.letterId("B"),
        )
        for (f in model.kml.files.value) for (n in 0 until 12) ids += MapImages.kmlLabelId(f.id, n)
        for (id in ids) {
            val b = try {
                style.getImage(id)
            } catch (e: Throwable) {
                line("getImage($id) threw $e")
                null
            }
            line("$id: ${if (b == null) "MISSING" else "${b.width}x${b.height}"}")
        }

        val me = model.recorder.location.value?.point
        if (me != null) {
            val p = m.projection.toScreenLocation(LatLng(me.lat, me.lon))
            line("you on the map view at $p")
            val near = RectF(p.x - 40, p.y - 40, p.x + 40, p.y + 40)
            line("rendered near you: ${m.queryRenderedFeatures(near).map { it.toJson().take(200) }}")
        }
        line("rendered user source anywhere: ${m.queryRenderedFeatures(screen, MapIds.USER_DOT, MapIds.USER_ARROW).size}")
    }

    /** The screen as the user sees it. */
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return line("no screenshot")
        save(bitmap, "$name-screen.png")
        val me = AppModel.shared.recorder.location.value?.point ?: return
        var at: PointF? = null
        main {
            val controller = AppModel.shared.map as? MapController ?: return@main
            val p = map()?.projection?.toScreenLocation(LatLng(me.lat, me.lon)) ?: return@main
            val loc = IntArray(2).also { controller.container.getLocationOnScreen(it) }
            at = PointF(p.x + loc[0], p.y + loc[1])
        }
        at?.let { p ->
            val x = p.x.toInt().coerceIn(0, bitmap.width - 1)
            val y = p.y.toInt().coerceIn(0, bitmap.height - 1)
            line("screen pixel at you ($x,$y): #${Integer.toHexString(bitmap.getPixel(x, y))}")
        }
    }

    /** The map's own GL frame. */
    private fun snapshot(name: String) {
        val latch = CountDownLatch(1)
        main {
            val m = map()
            if (m == null) {
                latch.countDown()
                return@main
            }
            m.snapshot { bitmap ->
                save(bitmap, "$name-map.png")
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) line("no map snapshot for $name")
    }

    // Helpers

    private fun map(): MapLibreMap? {
        val controller = AppModel.shared.map as? MapController ?: return null
        val field = MapController::class.java.getDeclaredField("map").apply { isAccessible = true }
        return field.get(controller) as MapLibreMap?
    }

    private fun waitForStyle(seconds: Int): MapLibreMap? {
        val end = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < end) {
            var ready: MapLibreMap? = null
            main {
                if (!isModelReady()) return@main
                val m = map()
                val style: Style? = m?.style
                if (style != null && style.isFullyLoaded && style.getLayer(MapIds.TERRAIN_TOP) != null) ready = m
            }
            if (ready != null) return ready
            Thread.sleep(500)
        }
        return null
    }

    private fun isModelReady(): Boolean = try {
        AppModel.shared
        true
    } catch (e: UninitializedPropertyAccessException) {
        false
    }

    private fun main(block: () -> Unit) {
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (e: Throwable) {
                line("on main: ${Log.getStackTraceString(e)}")
            }
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(folder, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        synchronized(out) { out.append(s).append('\n') }
    }

    private companion object {
        const val TAG = "OverlayDiag"
    }
}
