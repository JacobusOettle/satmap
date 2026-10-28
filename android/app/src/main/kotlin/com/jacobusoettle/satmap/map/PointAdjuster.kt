package com.jacobusoettle.satmap.map

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jacobusoettle.satmap.core.GeoPoint

/** A point that can be pressed and held to move it. */
sealed interface MovablePoint {
    /** The tapped spot's pin. */
    data object Selected : MovablePoint

    /** Measuring point `i`. */
    data class Measure(val i: Int) : MovablePoint
}

/** Moving a placed point precisely: pressed and held, the point gets a glass square above the finger showing
 *  15 m × 15 m around it; the finger moves it up to 5 m, beyond that the map scrolls. */
class PointAdjuster(
    private val activity: ComponentActivity,
    private val onMove: (MovablePoint, GeoPoint, Boolean) -> Unit,
) {
    var isActive: Boolean = false
        private set

    /** `touch` is the finger in content-root coordinates. */
    fun begin(which: MovablePoint, point: GeoPoint, touch: PointF) {
        // M3: shows the loupe (DESIGN.md §3.11).
    }

    fun moved(touch: PointF) {
        // M3: the 5 m clamp, then onMove(which, point, false).
    }

    fun end(cancelled: Boolean = false) {
        // M3: hides the loupe; onMove(which, final, true).
    }

    companion object {
        /** Where the square goes: 44 dp above the finger, else below, else beside it, inside `area`. */
        fun place(side: Float, gap: Float, touch: PointF, area: RectF): PointF = PointF(touch.x, touch.y) // M3.
    }
}

/** The glass square: a blurred snapshot rim, the `LoupeView` inside, a thin border and a shadow. */
class LoupeFrame(context: Context) : FrameLayout(context) {
    // M3: DESIGN.md §3.11.
}

/** The 15 m view: imagery (`SatelliteRenderer.drawImagery`), blocks, points, the measuring line and the moving point. */
class LoupeView(context: Context) : View(context) {
    // M3: interaction spec §1.2 drawing order.
}
