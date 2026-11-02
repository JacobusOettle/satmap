package com.jacobusoettle.satmap.ui.groups

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.ui.SatIcons

// Android only: the groups' SF Symbols (docs/groups/SPEC.md §9.1, §9.3, §9.4) as Material icons where one matches
// (DESIGN.md §4.4), and as vectors drawn to match where none does, on a 24 × 24 viewport.

/** `person.3.fill`: three people, the middle one in front and larger (the Groups button). */
val SatIcons.Person3: ImageVector by lazy {
    vector("Person3") {
        fill(
            "M12,4.2a3.1,3.1 0 1 1 0,6.2a3.1,3.1 0 1 1 0,-6.2z M6.3,12.6c0,-0.7 2.6,-1.6 5.7,-1.6s5.7,0.9 5.7,1.6v5.2H6.3z " +
                "M5.1,6.6a2.3,2.3 0 1 1 0,4.6a2.3,2.3 0 1 1 0,-4.6z M18.9,6.6a2.3,2.3 0 1 1 0,4.6a2.3,2.3 0 1 1 0,-4.6z " +
                "M0.6,13.6c0,-0.6 2,-1.4 4.5,-1.4c0.4,0 0.7,0 0.9,0.05c-0.5,0.4 -0.7,0.9 -0.7,1.3v3.3H0.6z " +
                "M23.4,13.6c0,-0.6 -2,-1.4 -4.5,-1.4c-0.4,0 -0.7,0 -0.9,0.05c0.5,0.4 0.7,0.9 0.7,1.3v3.3h4.7z",
        )
    }
}

/** `mappin.and.ellipse`: the pin over an ellipse on the ground. */
val SatIcons.MapPinAndEllipse: ImageVector by lazy {
    vector("MapPinAndEllipse") {
        fill("M12,2a3.6,3.6 0 1 1 0,7.2a3.6,3.6 0 1 1 0,-7.2z M11.25,8.8h1.5v8.6a0.75,0.75 0 0 1 -1.5,0z")
        stroke("M7.2,16.8c-2.3,0.4 -3.7,1.1 -3.7,1.9c0,1.3 3.8,2.3 8.5,2.3s8.5,-1 8.5,-2.3c0,-0.8 -1.4,-1.5 -3.7,-1.9", 1.5f)
    }
}

/** `smallcircle.filled.circle`: a ring with a dot in it (the pothole pin). */
val SatIcons.Pothole: ImageVector by lazy {
    vector("Pothole") {
        stroke("M12,3.5a8.5,8.5 0 1 1 0,17a8.5,8.5 0 1 1 0,-17z", 2.2f)
        fill("M12,8a4,4 0 1 1 0,8a4,4 0 1 1 0,-8z")
    }
}

/** `shield.lefthalf.filled`: a shield outline with its left half filled (the police pin). */
val SatIcons.Shield: ImageVector by lazy {
    vector("Shield") {
        stroke("M12,2.6l7.6,2.9v5.6c0,5 -3.3,8.7 -7.6,10.3c-4.3,-1.6 -7.6,-5.3 -7.6,-10.3V5.5z", 1.9f)
        fill("M12,2.6v18.8c-4.3,-1.6 -7.6,-5.3 -7.6,-10.3V5.5z")
    }
}

val SatIcons.Paw: ImageVector get() = Icons.Filled.Pets
val SatIcons.House: ImageVector get() = Icons.Filled.Home
val SatIcons.Drop: ImageVector get() = Icons.Filled.WaterDrop
val SatIcons.Warning: ImageVector get() = Icons.Filled.Warning
val SatIcons.Pin: ImageVector get() = SatIcons.MapPin

/** The other symbols the screens use. */
object GroupSymbols {
    val link: ImageVector get() = Icons.Filled.Link
    val lock: ImageVector get() = Icons.Filled.Lock
    val flag: ImageVector get() = Icons.Filled.Flag
    val star: ImageVector get() = Icons.Filled.Star
    val gift: ImageVector get() = Icons.Filled.Redeem
    val map: ImageVector get() = Icons.Filled.Map
    val plusCircle: ImageVector get() = Icons.Outlined.AddCircleOutline
    val personBadgePlus: ImageVector get() = Icons.Outlined.PersonAddAlt
    val qrViewfinder: ImageVector get() = Icons.Filled.QrCodeScanner
}

/** §9.4: a pin type's glyph. */
fun pinIcon(type: PinType?): ImageVector = when (type) {
    PinType.animal -> SatIcons.Paw
    PinType.shelter -> SatIcons.House
    PinType.water -> SatIcons.Drop
    PinType.danger -> SatIcons.Warning
    PinType.pothole -> SatIcons.Pothole
    PinType.police -> SatIcons.Shield
    null -> SatIcons.Pin
}

/** Draws an icon's paths into `bounds` of a plain canvas (the map's pin bitmaps), in one colour, as Compose would
 *  draw it tinted. */
fun drawIcon(canvas: Canvas, icon: ImageVector, bounds: RectF, colour: Int) {
    val scale = minOf(bounds.width() / icon.viewportWidth, bounds.height() / icon.viewportHeight)
    val dx = bounds.left + (bounds.width() - icon.viewportWidth * scale) / 2
    val dy = bounds.top + (bounds.height() - icon.viewportHeight * scale) / 2
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(dx, dy)
    }
    fun draw(group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorGroup -> draw(node)
                is VectorPath -> {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    path.transform(matrix)
                    if (node.fill != null) {
                        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; style = Paint.Style.FILL })
                    }
                    if (node.stroke != null && node.strokeLineWidth > 0) {
                        canvas.drawPath(
                            path,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = colour
                                style = Paint.Style.STROKE
                                strokeWidth = node.strokeLineWidth * scale
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            },
                        )
                    }
                }
            }
        }
    }
    draw(icon.root)
}

private fun vector(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply(paths)
        .build()

private fun ImageVector.Builder.stroke(path: String, width: Float) {
    addPath(
        pathData = addPathNodes(path),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(path: String) {
    addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
}
