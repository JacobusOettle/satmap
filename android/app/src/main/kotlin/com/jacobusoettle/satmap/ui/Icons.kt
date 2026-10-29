package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Android only: the SF Symbols with no Material match, drawn as vectors on a 24×24 viewport (UI spec §6.3). Icons
// tint them, so the paint colour here doesn't matter.

object SatIcons {
    /** `square.dashed` */
    val SquareDashed: ImageVector by lazy {
        icon("SquareDashed") {
            stroke("M4,8V6a2,2 0 0 1 2,-2h2 M16,4h2a2,2 0 0 1 2,2v2 M20,16v2a2,2 0 0 1 -2,2h-2 M8,20H6a2,2 0 0 1 -2,-2v-2 M11,4h2 M20,11v2 M11,20h2 M4,11v2", 2f)
        }
    }

    /** `location.magnifyingglass` */
    val LocationMagnifyingGlass: ImageVector by lazy {
        icon("LocationMagnifyingGlass") {
            stroke("M10.5,4a6.5,6.5 0 1 0 0.001,0z", 2f)
            stroke("M15.3,15.3L20.5,20.5", 2.6f)
            fill("M13.4,7.6L7.4,10.1L10.2,10.8L10.9,13.6Z")
        }
    }

    /** `mappin`: a small head on a long needle, as SF's (screenshot 15: 5.4 × 16.8 pt at body size, so 20 dp tall
     *  here). Narrow like SF's frame (16 × 24), so give it a height and its width follows. */
    val MapPin: ImageVector by lazy {
        icon("MapPin", width = 16f) {
            fill("M8,2a3.25,3.25 0 1 1 0,6.5a3.25,3.25 0 1 1 0,-6.5z M7.3,8h1.4v13.3a0.7,0.7 0 0 1 -1.4,0z")
        }
    }

    /** `mappin.circle.fill` */
    val MapPinCircleFill: ImageVector by lazy {
        icon("MapPinCircleFill") {
            fill(
                "M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z M12,6a2.9,2.9 0 1 1 0,5.8a2.9,2.9 0 1 1 0,-5.8z M11.35,11.8h1.3v5.7a0.65,0.65 0 0 1 -1.3,0z",
                PathFillType.EvenOdd,
            )
        }
    }

    /** `arrow.down.circle.fill`: a disc with the arrow cut out, so the row's own background (pressed or not) shows
     *  through it, as on iOS. The arrow is SF's: a stem and a chevron 1.8 wide with round ends, one outline that never
     *  crosses itself (the even-odd rule would fill an overlap again), fitted to screenshot 12. Material's
     *  `ArrowCircleDown` is a ring in both styles. Drawn at 26.5 dp, the disc is iOS's 22 dp. */
    val ArrowDownCircleFill: ImageVector by lazy {
        icon("ArrowDownCircleFill") {
            fill(
                "M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z " +
                    "M11.1,8.15A0.9,0.9 0 0 1 12.9,8.15L12.9,14.57L14.9,12.81A0.9,0.9 0 0 1 16.08,14.17L12.59,17.24" +
                    "A0.9,0.9 0 0 1 11.41,17.24L7.92,14.17A0.9,0.9 0 0 1 9.1,12.81L11.1,14.57Z",
                PathFillType.EvenOdd,
            )
        }
    }

    /** `lasso` */
    val Lasso: ImageVector by lazy {
        icon("Lasso") {
            stroke(
                "M12,4.5c4.7,0 8.5,2.4 8.5,5.25S16.7,15 12,15S3.5,12.6 3.5,9.75S7.3,4.5 12,4.5z " +
                    "M7.6,14.1c-1.3,0.9 -1.6,2.4 -0.6,3.3c1.1,1 2.9,0.5 3.1,-0.9c0.2,-1.3 -1.2,-1.9 -2,-1.1 " +
                    "M8.2,17.9c0.3,1.3 -0.2,2.5 -1.2,3.1",
                1.8f,
            )
        }
    }

    private fun icon(name: String, width: Float = 24f, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, defaultWidth = width.dp, defaultHeight = 24.dp, viewportWidth = width, viewportHeight = 24f)
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

    private fun ImageVector.Builder.fill(path: String, type: PathFillType = PathFillType.NonZero) {
        addPath(pathData = addPathNodes(path), pathFillType = type, fill = SolidColor(Color.Black))
    }
}

/** `arrow.clockwise.circle`: the refresh arrow (17 dp) in a 24 dp ring with a 1.75 dp border. */
@Composable
fun RetryCircle(tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).border(1.75.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp), tint = tint)
    }
}
