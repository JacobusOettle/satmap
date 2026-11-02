package com.jacobusoettle.satmap.groups

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Invite QR codes (docs/groups/SPEC.md §5.3) with ZXing core 3.5.4: drawn from the https link exactly (byte mode,
 *  error correction M, a quiet zone of 4 modules, black on white), and read from camera frames. iOS draws them with
 *  CoreImage and reads them with VisionKit or AVFoundation, an allowed difference (§9.9). */
object QRCode {
    /** The link's code, one pixel per module, the quiet zone included: the screen scales it up with nearest-neighbour
     *  filtering (no blur between modules). Null when the text can't be encoded (it always can, for a link). */
    fun bitmap(text: String): Bitmap? {
        val matrix = try {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
            )
        } catch (e: WriterException) {
            return null
        }
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    /** The text of a QR code in the picture, or null. */
    fun decode(source: LuminanceSource): String? = synchronized(reader) {
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    /** A bitmap's code (the tests read the code `bitmap` drew). */
    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luminance = ByteArray(w * h) { i ->
            val c = pixels[i]
            ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000).toByte()
        }
        return decode(PlanarYUVLuminanceSource(luminance, w, h, 0, 0, w, h, false))
    }

    /** A camera frame's code: its luminance plane (YUV_420_888's first plane), as ZXing reads it. */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val width = image.width
        val height = image.height
        if (rowStride < width || bytes.size < rowStride * (height - 1) + width) return null
        return decode(PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, width, height, false))
    }
}
