package com.jacobusoettle.satmap.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.location.GpsFix
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Go to coordinates" drawn as the iPhone screenshot 21 shows it, into app/build/renders (see `render`), to compare
 *  with docs/screenshots side by side. It checks nothing itself. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersCoordinatesTest {
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

    /** Screenshot 21: your position known (the coordinate bar at the top of the iPhone screenshot,
     *  -33.924524, 18.923242, 573 m from the typed spot), text on the clipboard (Paste enabled), and
     *  33°55'10"S 18°55'20"E typed into the focused field. */
    @Test
    fun goToCoordinates() {
        model.recorder.setForTests(
            GpsFix(-33.924524, 18.923242, 5.0, 431.0, 3.0, 1_800_000_000.0, SystemClock.elapsedRealtime() / 1000.0),
        )
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", "-33.924524, 18.923242"))
        sheet { CoordinateEntryView(model, close = {}) }
        compose.onNodeWithTag("coordinateText").performTextInput("33°55'10\"S 18°55'20\"E")
        compose.render("21-go-to-coordinates")
    }
}
