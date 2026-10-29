package com.jacobusoettle.satmap.ui

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import java.io.File

/** The iPhone the iOS screenshots come from (iPhone 16 Pro: 402 × 874 pt at 3×), as Robolectric qualifiers: a render
 *  at this size has the screenshot's pixel size, so the two can be laid side by side. */
const val IPHONE_SCREEN = "w402dp-h874dp-xxhdpi"

/** Saves what the test's activity shows as `<name>.png` in `app/build/renders`, to compare with the iPhone screenshot
 *  in docs/screenshots. Needs `@GraphicsMode(GraphicsMode.Mode.NATIVE)` (real text and pixels). The window is drawn
 *  into a bitmap in software (Compose's `captureToImage` waits for a frame Robolectric never draws); MapLibre's map
 *  can't be drawn in a unit test, so a screen over the map shows over nothing. */
fun AndroidComposeTestRule<*, out ComponentActivity>.render(name: String): File {
    waitForIdle()
    val bitmap = runOnIdle {
        val view = activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    val dir = File("build/renders").apply { mkdirs() }
    return File(dir, "$name.png").also { file ->
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Shows `content` as SheetHost shows a sheet on that iPhone: below the status bar, rounded at the top, over the dimmed
 *  map (black here). The iPhone 16 Pro's status bar is 62 pt, so its sheets start 72 pt down (the screenshots' sheet
 *  edge is at y 108 of 1304). The window here has no navigation bar, so the sheet's content stops 34 dp above the
 *  bottom, as the iPhone's lists end above its 34 pt home indicator. */
fun AndroidComposeTestRule<*, out ComponentActivity>.setSheet(content: @Composable () -> Unit) {
    setContent {
        SatMapTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    Modifier
                        .padding(top = 72.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .background(SheetBackground)
                        .fillMaxSize()
                        .padding(bottom = 34.dp),
                ) { content() }
            }
        }
    }
}
