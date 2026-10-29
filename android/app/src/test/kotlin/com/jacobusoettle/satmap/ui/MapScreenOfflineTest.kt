package com.jacobusoettle.satmap.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.ActiveSheet
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.DroneReport
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.app.Format
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.DownloadProgress
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.offline.AndroidSqlDriver
import com.jacobusoettle.satmap.offline.OfflineManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.math.hypot

/** The map screen's offline and drone pieces (UI spec §2.3.1, §2.3.2, §2.3.10, §2.4.5): the download banner and Offline
 *  maps while an area downloads, the drawing bar and the drone card. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w402dp-h874dp")
class MapScreenOfflineTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    private val area = OfflineArea(
        id = UUID.randomUUID(),
        name = "Demo farm",
        created = 800_000_000.0,
        shape = CoverageShape(polygons = listOf(listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.92, 18.93), GeoPoint(-33.93, 18.93)))),
        maxZoom = 22,
        allowCellular = false,
        status = OfflineArea.Status.downloading,
        failedTiles = 0,
        bytesOnDisk = 0,
    )

    @Test
    fun theBannerSaysWhatIsDownloadingAndPauses() {
        var progress by mutableStateOf<DownloadProgress?>(null)
        var pauses = 0
        var taps = 0
        compose.setContent {
            SatMapTheme {
                DownloadBanner(
                    area,
                    progress,
                    onPause = { pauses++ },
                    modifier = Modifier.clickable(interactionSource = null, indication = null) { taps++ },
                )
            }
        }
        compose.onNodeWithText("Downloading Demo farm").assertExists()
        compose.onNodeWithText("Starting…").assertExists()
        progress = DownloadProgress(layer = "Satellite", zoom = 21, maxZoom = 22, levelDone = 1615, levelTotal = 5326, fetched = 3550, bytes = 61_200_000)
        compose.onNodeWithText(
            "Satellite · zoom 21 of up to 22: 1,615/5,326 · 3,550 tiles, ${Format.bytes(61_200_000)} saved",
        ).assertExists()
        val bar = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1615f / 5326f, bar.current, 1e-6f)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, pauses)
        // The pause button's tap isn't the banner's.
        assertEquals(0, taps)
        compose.onNodeWithText("Downloading Demo farm").performClick()
        assertEquals(1, taps)
    }

    // Swift's Spacer() keeps the column 28 pt from Pause (10 + 8 + 10), whose button is 31 pt wide (screenshot 08:
    // the bar ends 70.8 pt from the banner's right edge, the glyph's centre 27.7 pt from it).
    @Test
    fun theBannerLeavesIOSsRoomBeforePause() {
        compose.setContent { SatMapTheme { DownloadBanner(area, null, onPause = {}, modifier = Modifier.testTag("banner")) } }
        val banner = compose.onNodeWithTag("banner").getUnclippedBoundsInRoot()
        val pause = compose.onNodeWithContentDescription("Pause").getUnclippedBoundsInRoot()
        val bar = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .getUnclippedBoundsInRoot()
        assertEquals(31f, pause.width.value, 0.01f)
        assertEquals(39f, pause.height.value, 0.01f)
        assertEquals(banner.right.value - 12, pause.right.value, 0.01f)
        assertEquals(pause.left.value - 28, bar.right.value, 0.01f)
    }

    private val noHeights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = null
    }

    // While an area downloads, Offline maps shows arrow.down.circle.fill: a black disc with the arrow cut out of it in
    // the button's yellow (screenshot 08: 85 % of it dark within 9 pt of its centre), not Material's ring.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun whileAnAreaDownloadsOfflineMapsShowsASolidDisc() {
        val model = AppModel(app, noHeights)
        MBTiles.driver = AndroidSqlDriver()
        // SatMap on screen, so downloads start. Told to the manager only: the model would start the map's tile store.
        AppLifecycle.resetForTests()
        AppLifecycle.init()
        process.currentState = Lifecycle.State.RESUMED
        // Its run never starts (`io` takes no work), so the area stays the one downloading.
        val never = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {}
        }
        val offline = OfflineManager(
            app,
            network = MutableStateFlow(Connectivity.State(isOnline = true, isExpensive = false)),
            io = never,
        )
        offline.add(name = "Demo farm", shape = area.shape, roads = null, preset = null, maxZoom = 22, allowCellular = false)
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            SatMapTheme { MapScreen(model, offline, model.recorder, map = null) }
        }
        compose.onNodeWithText("Downloading Demo farm").assertExists()
        val screen = Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888)
            .also { view!!.draw(android.graphics.Canvas(it)) }
        val b = compose.onNodeWithTag("offlineButton").getUnclippedBoundsInRoot()
        val cx = (b.left + b.right).value * 1.5f
        val cy = (b.top + b.bottom).value * 1.5f
        fun lum(argb: Int) =
            0.299f * android.graphics.Color.red(argb) + 0.587f * android.graphics.Color.green(argb) +
                0.114f * android.graphics.Color.blue(argb)
        var dark = 0
        var all = 0
        for (y in (cy - 27).toInt()..(cy + 27).toInt()) for (x in (cx - 27).toInt()..(cx + 27).toInt()) {
            if (hypot(x - cx, y - cy) > 27) continue
            all++
            if (lum(screen.getPixel(x, y)) < 100) dark++
        }
        assertTrue("dark: $dark of $all", dark > 0.8 * all)
        // The arrow's stem, cut out.
        assertEquals(SystemYellow.toArgb(), screen.getPixel(cx.toInt(), cy.toInt()))
    }

    @Test
    fun aTapOnTheBannerOpensOfflineMaps() {
        val model = AppModel(app, noHeights)
        compose.setContent {
            SatMapTheme {
                DownloadBanner(
                    area,
                    null,
                    onPause = {},
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        model.setSheet(ActiveSheet.offline)
                    },
                )
            }
        }
        compose.onNodeWithText("Downloading Demo farm").performClick()
        assertEquals(ActiveSheet.offline, model.sheet.value)
    }

    @Test
    fun theDrawingBarSwitchesBetweenMovingAndDrawingThenAsksAboutTheLoop() {
        var lasso by mutableStateOf(true)
        var drawn by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            SatMapTheme {
                DrawingBar(
                    drawn = drawn,
                    lassoActive = lasso,
                    onLassoActive = { lasso = it; calls.add("lasso $it") },
                    onCancel = { calls.add("cancel") },
                    onRedraw = { calls.add("redraw") },
                    onContinue = { calls.add("continue") },
                )
            }
        }
        compose.onNodeWithText("Draw a loop around the area with one finger.").assertExists()
        assertEquals(true, selected("Draw"))
        assertEquals(false, selected("Move map"))
        compose.onNodeWithText("Move map").performClick()
        compose.onNodeWithText("Pan and zoom to the area, then switch to Draw.").assertExists()
        assertEquals(true, selected("Move map"))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(listOf("lasso false", "cancel"), calls)

        drawn = true
        compose.onNodeWithText("Download this area?").assertExists()
        compose.onNodeWithText("Move map").assertDoesNotExist()
        compose.onNode(hasText("Redraw") and hasClickAction()).performClick()
        compose.onNode(hasText("Continue") and hasClickAction()).performClick()
        assertEquals(listOf("lasso false", "cancel", "redraw", "continue"), calls)
    }

    // UISegmentedControl: the chosen segment's title semibold, the other's regular (screenshot 10).
    @Test
    fun theChosenSegmentsTitleIsSemiboldAndTheOthersRegular() {
        var lasso by mutableStateOf(true)
        compose.setContent {
            SatMapTheme { DrawingBar(drawn = false, lassoActive = lasso, onLassoActive = { lasso = it }, {}, {}, {}) }
        }
        fun weight(title: String): FontWeight? {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!(layout)
            return layout.single().layoutInput.style.fontWeight
        }
        assertEquals(FontWeight.SemiBold, weight("Draw"))
        assertEquals(FontWeight.Normal, weight("Move map"))
        compose.onNodeWithText("Move map").performClick()
        assertEquals(FontWeight.SemiBold, weight("Move map"))
        assertEquals(FontWeight.Normal, weight("Draw"))
    }

    // The chosen segment is see-through, as iOS's: the map shows through it as through the track (screenshot 10, the
    // contour lines in it), only lighter.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "+xxhdpi")
    @Test
    fun theChosenSegmentLetsTheMapShowThrough() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            SatMapTheme {
                // The map: white and black stripes, 6 dp (18 px) wide, white first.
                Box(
                    Modifier.drawBehind {
                        val w = 6.dp.toPx()
                        var x = 0f
                        var white = true
                        while (x < size.width) {
                            drawRect(if (white) Color.White else Color.Black, Offset(x, 0f), Size(w, size.height))
                            x += w
                            white = !white
                        }
                    },
                ) { DrawingBar(drawn = false, lassoActive = true, onLassoActive = {}, {}, {}, {}) }
            }
        }
        compose.waitForIdle()
        val screen = Bitmap.createBitmap(view!!.width, view!!.height, Bitmap.Config.ARGB_8888)
            .also { view!!.draw(android.graphics.Canvas(it)) }
        fun lum(x: Int, y: Int) = screen.getPixel(x, y).let {
            0.299f * android.graphics.Color.red(it) + 0.587f * android.graphics.Color.green(it) +
                0.114f * android.graphics.Color.blue(it)
        }
        val draw = compose.onNode(hasText("Draw") and hasClickAction()).getUnclippedBoundsInRoot()
        val move = compose.onNode(hasText("Move map") and hasClickAction()).getUnclippedBoundsInRoot()
        // 3 dp into the segments (above their titles), on a white stripe and the black one after it, 12 dp or more in.
        val y = ((draw.top + 3.dp).value * 3).toInt()
        fun stripes(left: Float): Pair<Int, Int> {
            val k = ((left + 12) * 3 / 36).toInt() + 1
            return 36 * k + 9 to 36 * k + 27
        }
        val (thumbWhite, thumbBlack) = stripes(draw.left.value).let { (w, b) -> lum(w, y) to lum(b, y) }
        val (trackWhite, trackBlack) = stripes(move.left.value).let { (w, b) -> lum(w, y) to lum(b, y) }
        assertTrue("through the thumb: $thumbWhite, $thumbBlack", thumbWhite - thumbBlack > 0.6f * (trackWhite - trackBlack))
        assertTrue("lighter than the track", thumbBlack > trackBlack + 40)
    }

    private fun selected(title: String): Boolean? =
        compose.onNode(hasText(title) and hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun theDroneCardHasALinePerPartyAsIOS() {
        var report by mutableStateOf(DroneReport(you = "6.1 NM NE of FASH Stellenbosch"))
        compose.setContent { SatMapTheme { DroneCard(report) } }
        // One element for TalkBack, with iOS's identifier.
        compose.onNodeWithTag("droneCard").assertExists()
        compose.onAllNodesWithText("You").assertCountEquals(1)
        compose.onNodeWithText("Pin").assertDoesNotExist()
        report = DroneReport(you = "6.1 NM NE of FASH Stellenbosch", pin = "8.6 NM E of FASH Stellenbosch")
        val texts = compose.onNodeWithTag("droneCard").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf("You", "6.1 NM NE of FASH Stellenbosch", "Pin", "8.6 NM E of FASH Stellenbosch"), texts)
    }
}
