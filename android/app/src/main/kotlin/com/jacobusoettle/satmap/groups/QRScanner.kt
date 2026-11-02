package com.jacobusoettle.satmap.groups

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/** The camera view of S17 (docs/groups/SPEC.md §5.3): CameraX 1.6.2 shows the back camera and hands its frames to
 *  ZXing (`QRCode.decode`), one at a time, the latest only. Each code read is passed to `onText` on the main thread
 *  (the screen parses it with `InviteLink.parse`). No Play services. The camera permission is the screen's concern.
 *  Emulators have no camera: the view stays black and the walkthrough uses the paste path. */
@Composable
fun QRScannerView(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val main = Handler(Looper.getMainLooper())
        val frames = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var gone = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (gone) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(frames) { image ->
                    val text = try {
                        QRCode.decode(image)
                    } finally {
                        image.close()
                    }
                    if (text != null) main.post { if (!gone) latest(text) }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // No back camera, or it is in use: the view stays dark (pasting the link still works).
                Log.w("groups", "the camera couldn't start: ${e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            gone = true
            try {
                provider?.unbindAll()
            } catch (e: Exception) {
                Log.w("groups", "the camera couldn't stop: ${e.javaClass.simpleName}")
            }
            frames.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
