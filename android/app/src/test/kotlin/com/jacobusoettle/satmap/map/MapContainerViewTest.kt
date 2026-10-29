package com.jacobusoettle.satmap.map

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** `MapContainerView` tells `MapController` when it was laid out, and whether its frame changed: a turned screen
 *  resizes the same map (the manifest handles the change), and MapLibre sends no camera callbacks for a resize. */
@RunWith(AndroidJUnit4::class)
class MapContainerViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Stands in for the `MapView`, which resizes the native map in `onSizeChanged`. */
    private class Map(context: Context) : View(context) {
        var size = 0 to 0

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            size = w to h
        }
    }

    private fun lay(v: View, width: Int, height: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, width, height)
    }

    @Test
    fun aTurnedScreenIsAChangedLayoutWithTheMapAlreadyResized() {
        val container = MapContainerView(context)
        val map = Map(context)
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        container.addView(map, FrameLayout.LayoutParams(fill, fill))
        val heard = mutableListOf<Pair<Boolean, Pair<Int, Int>>>()
        container.onLaidOut = { changed -> heard += changed to map.size }

        lay(container, 1080, 2400)
        container.requestLayout() // laid out again in the same frame
        lay(container, 1080, 2400)
        lay(container, 2400, 1080) // turned

        assertEquals(
            listOf(true to (1080 to 2400), false to (1080 to 2400), true to (2400 to 1080)),
            heard,
        )
    }
}
