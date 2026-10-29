package com.jacobusoettle.satmap.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

// Android only: SwiftUI gives iOS its colours, text styles and the dark appearance for free. The app is always dark
// (iOS `.preferredColorScheme(.dark)`); pt become dp and SwiftUI font sizes sp.

val Accent = Color(0xFFFFBD29) // the AccentColor asset (1.0, 0.74, 0.16)
val SystemYellow = Color(0xFFFFD60A)
val SystemGreen = Color(0xFF30D158)
val SystemOrange = Color(0xFFFF9F0A)
val SystemRed = Color(0xFFFF453A)
val SheetBackground = Color(0xFF1C1C1E)
val CellBackground = Color(0xFF2C2C2E)
val CellPressed = Color(0xFF3A3A3C)
val Separator = Color(0xFF38383A)
val LabelPrimary = Color.White
val LabelSecondary = Color(0x99EBEBF5)
val LabelTertiary = Color(0x4DEBEBF5)
val PasteFill = Color(0xFFBD8B1A)
val YouColour = Color(0xFF59DBFF) // AviationView.youColour
val PinColour = Color(0xFFFFB833) // AviationView.pinColour
val ContourBold = Color(red = 1.0f, green = 0.74f, blue = 0.16f)
val ContourThin = Color(red = 1.0f, green = 0.84f, blue = 0.32f) // drawn at alpha 0.9

/** `.headline` */
val Headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

/** `.body` */
val Body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)

/** `.subheadline` */
val Subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)

/** `.footnote` */
val Footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** `.caption` */
val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

/** `.caption2` */
val Caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)

/** `.system(size: 30, weight: .bold)` */
val BigNumber = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)

/** `.monospacedDigit()`: digits all one width, so a changing number doesn't jiggle. */
const val TabularDigits = "tnum"

/** The iOS text styles in Material's slots, so stock components look right. */
val SatTypography = Typography(
    bodyLarge = Body,
    bodyMedium = Subheadline,
    bodySmall = Footnote,
    labelSmall = Caption2,
    titleMedium = Headline,
)

@Composable
fun SatMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.Black, secondary = Accent, onSecondary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = SheetBackground, onSurface = Color.White, onSurfaceVariant = LabelSecondary,
            surfaceContainerLowest = SheetBackground, surfaceContainerLow = SheetBackground,
            surfaceContainer = CellBackground, surfaceContainerHigh = CellBackground, surfaceContainerHighest = CellPressed,
            outline = Separator, outlineVariant = Separator, error = SystemRed, scrim = Color.Black,
        ),
        typography = SatTypography,
        content = content,
    )
}

/** The blur of SwiftUI's `.shadow(color: .black, radius: 1.5)` on text and lines over the map, as an Android
 *  shadow-layer radius (DESIGN.md §4.3). */
val ShadowBlur: Dp = 3.dp

/** The text shadow that keeps white text readable on bright imagery. */
val mapTextShadow: Shadow
    @Composable @ReadOnlyComposable
    get() = Shadow(Color.Black, Offset.Zero, with(LocalDensity.current) { ShadowBlur.toPx() })

/** The same shadow on lines and shapes over the map (the crosshair, the scale bar): what `draw` paints on a canvas the
 *  node's size (px), with the black shadow of the whole of it under it. Drawn in software into a bitmap, again only
 *  when the size changes: a hardware canvas draws a shadow layer under text only before Android 9, so on 8.0 and 8.1
 *  the lines would have no dark edge on bright imagery. The shadow of the whole, as SwiftUI's on a group, also keeps
 *  one piece's shadow off another (the red dot's off the white cross). */
fun Modifier.drawShadowed(draw: Density.(android.graphics.Canvas, Size) -> Unit): Modifier = drawWithCache {
    val blur = ShadowBlur.toPx()
    val room = shadowRoom(blur).toFloat()
    val image = shadowedBitmap(size, blur) { draw(this, it, size) }.asImageBitmap()
    onDrawBehind { drawImage(image, Offset(-room, -room)) }
}

/** What `draw` paints on a `size` canvas (px), with the black shadow of the whole of it (a shadow layer of `blur` px)
 *  under it: a software bitmap with `shadowRoom(blur)` px round the canvas for the blur. */
internal fun shadowedBitmap(size: Size, blur: Float, draw: (android.graphics.Canvas) -> Unit): Bitmap {
    val room = shadowRoom(blur)
    val bitmap = Bitmap.createBitmap(
        size.width.roundToInt() + 2 * room,
        size.height.roundToInt() + 2 * room,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bitmap)
    canvas.save()
    canvas.translate(room.toFloat(), room.toFloat())
    draw(canvas)
    canvas.restore()
    // The alpha of all of it, blurred as a shadow layer is, drawn black under what is there.
    val offset = IntArray(2)
    val blurred = Paint().apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
    val shadow = bitmap.extractAlpha(blurred, offset)
    val under = Paint().apply {
        color = android.graphics.Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
    }
    canvas.drawBitmap(shadow, offset[0].toFloat(), offset[1].toFloat(), under)
    shadow.recycle()
    return bitmap
}

/** How far the blur of a shadow layer of `blur` px reaches past what casts it: three of Skia's sigmas
 *  (0.57735 × radius + 0.5 px). */
internal fun shadowRoom(blur: Float): Int = ceil(3 * (0.57735f * blur + 0.5f)).toInt()

/** Panels and pills over the map take every touch, so a tap on them never reaches the map (it would place a
 *  measuring point); their own clickable children handle their taps first. Being hit is enough: Compose then offers
 *  the touch to nothing under the panel, the map's view included. It must not consume: a parent that consumes a
 *  move cancels its children's taps, so a finger that moves a little would no longer tap a button in the panel. */
fun Modifier.blocksMap(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/** iOS's plain button: what it shows dims while pressed (no ripple). */
@Composable
internal fun Modifier.pressable(onClickLabel: String? = null, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .clickable(
            interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
        )
        .graphicsLayer { alpha = if (pressed) 0.35f else 1f }
}

/** A capsule over the map: black 65 %, padding 12 / 8 (the height pill 10 / 8, a point's name 12 / 5). */
fun Modifier.pill(horizontal: Dp = 12.dp, vertical: Dp = 8.dp): Modifier =
    background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(horizontal = horizontal, vertical = vertical)

/** A panel over the map: black at `alpha`, 12 dp corners (10 for the hint; padding per panel). */
fun Modifier.panel(alpha: Float, cornerRadius: Dp = 12.dp): Modifier =
    background(Color.Black.copy(alpha = alpha), RoundedCornerShape(cornerRadius))
