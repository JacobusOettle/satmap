package com.jacobusoettle.satmap.core

/** The measuring tool. A point is placed at the crosshair, with the measure button or a tap anywhere on the map; with a
 *  spot selected on the map (tapped, or a point in a KML file), the measure button puts A on it instead.
 *  off → aiming → placed (distance from you to A) → path (A → B → C …, total distance) → closed (back to A: area). */
sealed interface MeasureMode {
    data object Off : MeasureMode

    /** Crosshair shown, waiting for the first point. */
    data object Aiming : MeasureMode

    /** One point: the live distance and height from you to it. */
    data class Placed(val a: GeoPoint) : MeasureMode

    /** Two or more points: the line through them, its total length and the height from first to last. */
    data class Path(val pts: List<GeoPoint>) : MeasureMode

    /** Three or more points joined back to the first: the shape's area and perimeter. */
    data class Closed(val pts: List<GeoPoint>) : MeasureMode

    val points: List<GeoPoint>
        get() = when (this) {
            Off, Aiming -> emptyList()
            is Placed -> listOf(a)
            is Path -> pts
            is Closed -> pts
        }

    /** The same with point `i` moved (`PointAdjuster`). */
    fun moving(i: Int, p: GeoPoint): MeasureMode = when (this) {
        is Placed -> if (i == 0) Placed(p) else this
        is Path -> if (i in pts.indices) Path(pts.toMutableList().also { it[i] = p }) else this
        is Closed -> if (i in pts.indices) Closed(pts.toMutableList().also { it[i] = p }) else this
        Off, Aiming -> this
    }

    /** What the measure button does. With measuring off it starts: from the selected spot if there is one (A there at
     *  once, with the live distance and height from you to it; the map stays where it is), else with the crosshair.
     *  While aiming or with points it places one at the crosshair; after a shape is closed it clears. */
    fun buttonAction(selected: GeoPoint?): MeasureButtonAction = when (this) {
        Off -> MeasureButtonAction.Start(selected?.let { Placed(it) } ?: Aiming)
        Aiming, is Placed, is Path -> MeasureButtonAction.AddPointAtCrosshair
        is Closed -> MeasureButtonAction.Clear
    }

    companion object {
        /** "A", "B", … "Z", "AA", "AB", … "ZZ", "AAA", … */
        fun label(i: Int): String {
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var n = i + 1
            var s = ""
            while (n > 0) {
                n -= 1
                s = letters[n % 26] + s
                n /= 26
            }
            return s
        }
    }
}

/** The measure button's effect (`MeasureMode.buttonAction`). */
sealed interface MeasureButtonAction {
    /** Measuring starts in this state: aiming, or A at the selected spot. */
    data class Start(val mode: MeasureMode) : MeasureButtonAction

    /** A point at the crosshair (the next one, or A after aiming). */
    data object AddPointAtCrosshair : MeasureButtonAction

    /** The closed shape goes. */
    data object Clear : MeasureButtonAction
}
