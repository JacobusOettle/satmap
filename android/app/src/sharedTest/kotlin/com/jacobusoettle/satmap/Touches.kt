package com.jacobusoettle.satmap

import android.graphics.PointF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/** One finger event of a gesture: `action` (`MotionEvent.ACTION_DOWN`, `ACTION_MOVE` or `ACTION_UP`) at `at`, in screen
 *  pixels, `afterMs` after the event before it. */
data class Touch(val action: Int, val at: PointF, val afterMs: Long)

/** Where and when the screenshot walk touches the screen to do what SatMapUITests/ScreenshotTests.swift does on the
 *  iPhone. `Gestures` makes the events on the emulator; the unit tests give them to MapLibre's own gesture detector. */
object Touches {
    /** The iPhone's window (iPhone 16 Pro), in points, on which the iOS walk taps and drags at shares of the width and
     *  height (`app.coordinate(withNormalizedOffset:)`). The map fills it, its centre (the crosshair) in the middle. */
    const val IPHONE_WIDTH = 402.0
    const val IPHONE_HEIGHT = 874.0

    /** How far the finger goes before MapLibre moves the map (`maplibre_minimum_move_threshold`, on either axis). The
     *  move that gets there starts the drag but isn't applied (`MoveGestureDetector`). */
    const val MOVE_THRESHOLD_DP = 4f

    /** A map drag's first move, on its main axis: a little past the threshold. */
    const val FIRST_MOVE_DP = MOVE_THRESHOLD_DP + 1

    /** The iPhone's `(x, y)` share of its window, as the point as far from `centre` in dp (`density` pixels each) as
     *  it is from the window's middle in points. Shares of a taller screen would put every tap and drag further from
     *  the crosshair than on the iPhone. */
    fun iphonePoint(x: Double, y: Double, centre: PointF, density: Float) = PointF(
        (centre.x + (x - 0.5) * IPHONE_WIDTH * density).toFloat(),
        (centre.y + (y - 0.5) * IPHONE_HEIGHT * density).toFloat(),
    )

    /** iOS's `press(forDuration:thenDragTo:)`: down at `from`, held for `holdMs`, moved to `to` in 20 steps 16 ms
     *  apart, then up. A map under it leaves out the first step (`MOVE_THRESHOLD_DP`); a point being moved doesn't. */
    fun pressAndDrag(from: PointF, to: PointF, holdMs: Long): List<Touch> =
        listOf(Touch(MotionEvent.ACTION_DOWN, from, 0)) +
            steps(from, to).mapIndexed { i, at -> Touch(MotionEvent.ACTION_MOVE, at, if (i == 0) holdMs else 16) } +
            Touch(MotionEvent.ACTION_UP, to, 16)

    /** iOS's `press(forDuration:thenDragTo:withVelocity:thenHoldForDuration:)` on the map, so that the map moves from
     *  `from` to `to`: down at `from`, held for `holdMs`; a first move `FIRST_MOVE_DP` along the drag, which starts
     *  MapLibre's drag without moving the map; on in 20 steps 16 ms apart to as far past `to`; held still there for
     *  `thenHoldMs`, so the map has no speed to glide on with; then up. */
    fun mapDrag(from: PointF, to: PointF, holdMs: Long, thenHoldMs: Long, density: Float): List<Touch> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        // MapLibre measures each axis on its own: the first move is past the threshold on the drag's main axis.
        val main = max(abs(dx), abs(dy))
        val k = if (main > 0) FIRST_MOVE_DP * density / main else 0f
        val first = PointF(from.x + dx * k, from.y + dy * k)
        val moves = steps(first, PointF(to.x + dx * k, to.y + dy * k))
        return listOf(Touch(MotionEvent.ACTION_DOWN, from, 0), Touch(MotionEvent.ACTION_MOVE, first, holdMs)) +
            moves.map { Touch(MotionEvent.ACTION_MOVE, it, 16) } +
            Touch(MotionEvent.ACTION_UP, moves.last(), thenHoldMs)
    }

    /** From `from` (left out) to `to` in 20 equal steps. */
    private fun steps(from: PointF, to: PointF): List<PointF> = (1..20).map { i ->
        val f = i / 20f
        PointF(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f)
    }
}
