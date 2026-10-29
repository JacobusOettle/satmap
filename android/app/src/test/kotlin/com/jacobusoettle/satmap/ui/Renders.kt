package com.jacobusoettle.satmap.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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
