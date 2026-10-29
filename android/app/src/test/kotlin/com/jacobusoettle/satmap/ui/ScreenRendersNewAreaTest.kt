package com.jacobusoettle.satmap.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.PendingArea
import com.jacobusoettle.satmap.core.FoundPlace
import com.jacobusoettle.satmap.core.GeoBounds
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLParser
import com.jacobusoettle.satmap.core.Places
import com.jacobusoettle.satmap.core.Roads
import com.jacobusoettle.satmap.core.TrackPoint
import com.jacobusoettle.satmap.offline.RoadPresets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** "Download area" drawn as the iPhone screenshots 07, 12, 17, 20 and 27 show it, into app/build/renders (see
 *  `render`), to compare with docs/screenshots side by side. The same data as there (SatMapUITests/ScreenshotTests):
 *  the demo farm's KML file, the Kruger road list, the walk's track, the Sabi Sand boundary; the free space the
 *  iPhone had. They check nothing themselves. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersNewAreaTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }
    private lateinit var model: AppModel

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        model = AppModel(app, noHeights)
    }

    @After
    fun tearDown() {
        AppLifecycle.resetForTests()
    }

    /** A sheet as SheetHost shows it: below the status bar (54 dp on the iPhone, with the 10 dp gap), rounded at the
     *  top, over the dimmed map (black here). */
    private fun sheet(content: @Composable () -> Unit) {
        compose.setContent {
            SatMapTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(
                        Modifier
                            .padding(top = 64.dp)
                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                            .background(SheetBackground)
                            .fillMaxSize(),
                    ) { content() }
                }
            }
        }
    }

    /** The free space the iPhone had ("41.72 GB"). */
    private val iPhoneFreeSpace = 41_720_000_000L

    /** The area's tile counts worked out (as the screen does when shown), with the iPhone's free space. */
    private fun counted(state: NewAreaState): NewAreaState {
        runBlocking { state.count() }
        state.freeSpace = iPhoneFreeSpace
        return state
    }

    /** As the iPhone test's `scrollTo(Download)`: the form scrolled to its end (the Download button, its last row). */
    private fun scrollToDownload() {
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.waitForIdle()
    }

    private fun back(title: String) = ScreenChrome.Leading.Back(title) {}

    /** SatMapUITests/DemoFarm.kml, as KMLAreaPicker makes its area: the blocks with 150 m around them. */
    private fun demoFarm(name: String): NewAreaState {
        val doc = KMLParser.parse(File("../../SatMapUITests/DemoFarm.kml").readBytes())
        return NewAreaState(PendingArea(name = name, shape = doc.coverageShape(bufferMeters = 150.0)))
    }

    @Test
    fun downloadArea() {
        val state = counted(demoFarm("Demo farm"))
        sheet { NewAreaView(model, state, back = back("Choose KML"), close = {}) }
        compose.render("07-download-area")
    }

    /** Roads on the farm, about 15 km as on the iPhone (OpenStreetMap's are looked up online): rows 0.00025° apart
     *  across each block. */
    private fun farmRoads(): List<List<GeoPoint>> {
        val doc = KMLParser.parse(File("../../SatMapUITests/DemoFarm.kml").readBytes())
        val lines = ArrayList<List<GeoPoint>>()
        for (ring in doc.coverageShape(bufferMeters = 0.0).polygons) {
            val minLon = ring.minOf { it.lon }
            val maxLon = ring.maxOf { it.lon }
            var lat = ring.minOf { it.lat }
            while (lat <= ring.maxOf { it.lat } + 1e-9) {
                lines.add(listOf(GeoPoint(lat, minLon), GeoPoint(lat, maxLon)))
                lat += 0.00025
            }
        }
        return lines
    }

    @Test
    fun downloadAlongRoads() {
        // Renamed, zoom 20 tapped, then only along roads.
        val state = demoFarm("Farm roads")
        state.choose(20)
        state.alongRoads = true
        val roads = farmRoads()
        runBlocking { state.loadRoads(fetch = { roads }) }
        counted(state)
        sheet { NewAreaView(model, state, back = back("Choose KML"), close = {}) }
        scrollToDownload()
        compose.render("12-download-along-roads")
    }

    @Test
    fun krugerRoads() {
        val state = OfflineRoute.Preset(RoadPresets.kruger).state!!
        runBlocking { state.loadRoads() }
        counted(state)
        sheet { PresetAreaView(model, state, back = back("Offline maps"), close = {}) }
        scrollToDownload()
        compose.render("17-kruger-roads")
    }

    /** The iPhone test's walk: in along the farm road, then uphill (a fix every 2.4 s). */
    private val walk: List<Pair<Double, Double>> = listOf(
        -33.918292 to 18.921204, -33.918426 to 18.921365, -33.918559 to 18.921525, -33.918693 to 18.921686,
        -33.918827 to 18.921847, -33.918960 to 18.922008, -33.919094 to 18.922169, -33.919227 to 18.922330,
        -33.919361 to 18.922491, -33.919539 to 18.922616, -33.919717 to 18.922741, -33.919895 to 18.922867,
        -33.920073 to 18.922992, -33.920251 to 18.923117, -33.920429 to 18.923242, -33.920620 to 18.923319,
        -33.920811 to 18.923395, -33.921001 to 18.923472, -33.921192 to 18.923549, -33.921383 to 18.923625,
        -33.921574 to 18.923702, -33.921765 to 18.923779,
        -33.921765 to 18.923779, -33.921957 to 18.923736, -33.922150 to 18.923693, -33.922343 to 18.923650,
        -33.922536 to 18.923607, -33.922729 to 18.923564, -33.922922 to 18.923521, -33.923100 to 18.923490,
        -33.923278 to 18.923459, -33.923456 to 18.923428, -33.923634 to 18.923397, -33.923812 to 18.923366,
        -33.923990 to 18.923335, -33.924168 to 18.923304, -33.924346 to 18.923273, -33.924524 to 18.923242,
    )

    @Test
    fun whereIveBeen() {
        // As TrackAreaView works it out: no track area yet, so the whole track is new.
        val track = walk.mapIndexed { i, (lat, lon) ->
            TrackPoint(lat = lat, lon = lon, time = 1_800_000_000.0 + 2.4 * i, startsSegment = i == 0)
        }
        val lines = Roads.mergeTrack(emptyList(), Roads.trackLines(track))
        val download = TrackDownload(
            lines = lines,
            newLines = lines,
            previousLines = emptyList(),
            maxZoom = null,
            allowCellular = null,
            completeTo = null,
        )
        val state = NewAreaState(PendingArea(name = "Where I've been", shape = Roads.corridor(lines)), track = download)
        runBlocking { state.loadRoads() }
        counted(state)
        val screen = TrackState().apply {
            newArea = state
            worked = true
        }
        sheet { TrackAreaView(model, screen, back = back("Offline maps"), close = {}) }
        scrollToDownload()
        compose.render("20-where-ive-been")
    }

    /** Sabi Sand Nature Reserve as the place search finds it (Photon), and its boundary as Nominatim gives it at the
     *  app's simplification for its size (OpenStreetMap relation 1751334, 99 points, lat/lon). */
    private val sabiSand = FoundPlace(
        id = "R1751334",
        name = "Sabi Sand Nature Reserve",
        kind = "Nature reserve",
        tag = "nature_reserve",
        region = "Mpumalanga, South Africa",
        centre = GeoPoint(-24.8213030, 31.4411616),
        extent = GeoBounds(minLat = -24.9771600, minLon = 31.3274500, maxLat = -24.6654308, maxLon = 31.6223272),
    )
    private val sabiSandBoundary = doubleArrayOf(
        -24.73192, 31.32745, -24.77166, 31.34011, -24.82138, 31.34117, -24.83852, 31.3429, -24.84064, 31.34661,
        -24.84325, 31.35001, -24.84518, 31.35468, -24.84519, 31.3612, -24.84781, 31.37519, -24.84663, 31.3811,
        -24.84796, 31.3854, -24.8498, 31.38736, -24.85109, 31.38735, -24.8686, 31.38512, -24.86987, 31.40392,
        -24.85959, 31.46894, -24.90174, 31.46674, -24.92852, 31.47931, -24.93427, 31.43938, -24.96445, 31.44895,
        -24.96607, 31.45096, -24.96554, 31.45127, -24.97392, 31.45911, -24.976, 31.46288, -24.97716, 31.46658,
        -24.97685, 31.46711, -24.97446, 31.46742, -24.96954, 31.47334, -24.96946, 31.47381, -24.97008, 31.47458,
        -24.96939, 31.47527, -24.96823, 31.47558, -24.96815, 31.47627, -24.96839, 31.47704, -24.96908, 31.47711,
        -24.96962, 31.47781, -24.97015, 31.47711, -24.9766, 31.48464, -24.9752263, 31.4861379, -24.9737519,
        31.491689, -24.9689721, 31.4956888, -24.9681149, 31.5004163, -24.970572, 31.5049546, -24.9686863, 31.5140629,
        -24.9693149, 31.519926, -24.9748004, 31.5291288, -24.9748086, 31.5312981, -24.9719452, 31.5327141,
        -24.9709578, 31.5370709, -24.9722414, 31.5422991, -24.9711969, 31.5454383, -24.9695153, 31.5483405,
        -24.9642719, 31.5501472, -24.9628567, 31.5518295, -24.9624355, 31.5535268, -24.9621559, 31.5580352,
        -24.958461, 31.5562186, -24.9564369, 31.5852794, -24.9528872, 31.5835931, -24.9521201, 31.5853594,
        -24.9562919, 31.5874381, -24.9538554, 31.6223272, -24.918456, 31.6164772, -24.9186335, 31.6033378, -24.91909,
        31.6008268, -24.9228255, 31.5896535, -24.9301867, 31.5726857, -24.9312453, 31.569618, -24.9319839,
        31.5597631, -24.9319593, 31.5575369, -24.9314423, 31.5566682, -24.9306053, 31.5561795, -24.9290789,
        31.5559352, -24.9261492, 31.5559895, -24.922013, 31.5569396, -24.9192674, 31.5584152, -24.9195622,
        31.5364248, -24.8783461, 31.5466188, -24.832897, 31.5470265, -24.8014626, 31.5311049, -24.7936943,
        31.5300749, -24.7909817, 31.5231656, -24.790748, 31.516342, -24.7914298, 31.5126513, -24.7791372, 31.5109776,
        -24.772557, 31.5272211, -24.7569062, 31.5273069, -24.7515047, 31.6097425, -24.708962, 31.6029224,
        -24.6718617, 31.5938526, -24.6654308, 31.5541184, -24.6770965, 31.5556709, -24.6702943, 31.5146892,
        -24.695234, 31.5151805, -24.70671, 31.49969, -24.73226, 31.46365, -24.73396, 31.39726, -24.73388, 31.38461,
        -24.73192, 31.32745,
    )

    @Test
    fun placeArea() {
        val ring = (sabiSandBoundary.indices step 2).map { GeoPoint(sabiSandBoundary[it], sabiSandBoundary[it + 1]) }
        val screen = PlaceAreaState()
        runBlocking { screen.load(sabiSand, area = { Places.area(it, listOf(ring)) }) }
        counted(screen.newArea!!)
        sheet { PlaceAreaView(model, sabiSand, screen, back = back("Find a place"), close = {}) }
        compose.render("27-place-area")
    }
}
