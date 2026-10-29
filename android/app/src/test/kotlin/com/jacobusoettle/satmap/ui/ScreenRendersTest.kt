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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheets drawn as the iPhone screenshots show them, into app/build/renders (see `render`), to compare with
 *  docs/screenshots side by side. They check nothing themselves. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPHONE_SCREEN)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRendersTest {
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

    @Test
    fun offlineMaps() {
        sheet { OfflineSheet(model, model.offline, model.kml, close = {}) }
        compose.render("06-offline-maps")
    }
}
