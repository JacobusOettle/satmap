package com.jacobusoettle.satmap.map

import android.app.Activity
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** `MapContainerView` tells `MapController` when it was laid out, and whether its frame changed: a turned screen
 *  resizes the same map (the manifest handles the change), and MapLibre sends no camera callbacks for a resize. It
 *  runs iOS's press and hold that moves a point (0.35 s; not after moving 10 dp, with a second finger, or as the press
 *  of a tap then press), keeps the gesture from the map while a point moves, records taps, and gives accessibility
 *  services the names on the map (told of changes once the map settles, found when asked). */
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

    // Press and hold to move a point, and taps

    /** Stands in for the `MapView`: takes every touch, and keeps their actions. */
    private class TouchMap(context: Context) : View(context) {
        val actions = mutableListOf<Int>()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            actions += event.actionMasked
            return true
        }
    }

    /** What the controller hears. */
    private class Heard : MapContainerView.Touches {
        var pressable = true
        val events = mutableListOf<String>()
        val taps = mutableListOf<Pair<PointF, PointF>>()

        override fun pressable(x: Float, y: Float) = pressable

        override fun pressBegan(x: Float, y: Float): Boolean {
            events += "began ${x.toInt()},${y.toInt()}"
            return true
        }

        override fun pressMoved(x: Float, y: Float) {
            events += "moved ${x.toInt()},${y.toInt()}"
        }

        override fun pressEnded(cancelled: Boolean) {
            events += if (cancelled) "cancelled" else "ended"
        }

        override fun touchEnded(down: PointF, up: PointF) {
            taps += down to up
        }
    }

    /** The container's parent: keeps the accessibility events it sends. */
    private class Recorder(context: Context) : FrameLayout(context) {
        val events = mutableListOf<AccessibilityEvent>()

        override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            events += event
            return true
        }
    }

    private val density = context.resources.displayMetrics.density
    private lateinit var activity: Activity
    private lateinit var recorder: Recorder
    private lateinit var container: MapContainerView
    private lateinit var map: TouchMap
    private val heard = Heard()
    private var downTime = 0L

    /** The container on the screen (its delayed press needs a window), with the stand-in map filling it. */
    private fun show() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        recorder = Recorder(activity)
        container = MapContainerView(activity)
        map = TouchMap(activity)
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        container.addView(map, FrameLayout.LayoutParams(fill, fill))
        container.touches = heard
        recorder.addView(container, FrameLayout.LayoutParams(fill, fill))
        activity.setContentView(recorder, ViewGroup.LayoutParams(fill, fill))
        shadowOf(Looper.getMainLooper()).idle() // laid out in the window
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        container.dispatchTouchEvent(e)
        e.recycle()
    }

    /** A second finger landing at (x2, y2) while the first is at (x, y). */
    private fun secondFinger(x: Float, y: Float, x2: Float, y2: Float) {
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y },
            MotionEvent.PointerCoords().apply { this.x = x2; this.y = y2 },
        )
        val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        container.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun heldForAThirdOfASecondAPointMovesAndTheMapSeesNoMore() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(349)
        assertTrue(heard.events.isEmpty())
        idle(1)
        assertEquals(listOf("began 100,200"), heard.events)
        // The map's own gesture is cancelled at once, and it sees nothing after.
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), map.actions)
        touch(MotionEvent.ACTION_MOVE, 160f, 200f)
        touch(MotionEvent.ACTION_MOVE, 200f, 150f)
        touch(MotionEvent.ACTION_UP, 200f, 150f)
        assertEquals(listOf("began 100,200", "moved 160,200", "moved 200,150", "ended"), heard.events)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), map.actions)
        assertTrue(heard.taps.isEmpty()) // lifting the finger isn't a tap
        // The next touch is the map's again.
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        assertEquals(MotionEvent.ACTION_DOWN, map.actions.last())
    }

    @Test
    fun aCancelledMovePutsThePointBack() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(400)
        touch(MotionEvent.ACTION_CANCEL, 100f, 200f)
        assertEquals(listOf("began 100,200", "cancelled"), heard.events)
    }

    @Test
    fun theFingerMayWanderUpTo10dpBeforeThePressStarts() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_MOVE, 100f + 9.9f * density, 200f)
        idle(400)
        assertEquals(1, heard.events.size)
        assertTrue(heard.events[0].startsWith("began"))
    }

    @Test
    fun movingMoreThan10dpIsAPanNotAPress() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_MOVE, 100f, 200f + 10.5f * density)
        idle(1000)
        assertTrue(heard.events.isEmpty())
        // The map had the whole gesture.
        touch(MotionEvent.ACTION_UP, 100f, 200f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), map.actions)
    }

    @Test
    fun aSecondFingerIsAPinchNotAPress() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        secondFinger(100f, 200f, 150f, 250f)
        idle(1000)
        assertTrue(heard.events.isEmpty())
    }

    @Test
    fun aPressAwayFromAnyPointIsTheMaps() {
        show()
        heard.pressable = false
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(1000)
        assertTrue(heard.events.isEmpty())
        touch(MotionEvent.ACTION_UP, 100f, 200f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), map.actions)
    }

    @Test
    fun aTapThenAPressIsAOneFingerZoomNotAPress() {
        show()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_UP, 100f, 200f)
        idle(100)
        touch(MotionEvent.ACTION_DOWN, 105f, 203f)
        idle(1000)
        assertTrue(heard.events.isEmpty())
        touch(MotionEvent.ACTION_UP, 105f, 203f)
        // Later, a press is a press again.
        idle(1000)
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(400)
        assertEquals(1, heard.events.size)
    }

    @Test
    fun aTapIsRecordedWhereItsFingerWentDownAndCameUp() {
        show()
        heard.pressable = false
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_UP, 102f, 201f)
        assertEquals(listOf(PointF(100f, 200f) to PointF(102f, 201f)), heard.taps)
        // A drag isn't a tap.
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_MOVE, 100f, 300f)
        touch(MotionEvent.ACTION_UP, 100f, 300f)
        assertEquals(1, heard.taps.size)
    }

    // The names on the map, for accessibility services

    @Test
    fun theNamesOnTheMapAreAccessibilityNodesWithTheirBoxes() {
        show()
        container.labels = { listOf(launch, other) }
        var clicked: MapContainerView.Label? = null
        container.onLabelClicked = { clicked = it }
        val provider = container.accessibilityNodeProvider
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertEquals(3, host.childCount) // the map, and the two names
        val node = provider.createAccessibilityNodeInfo(0)!!
        assertEquals("Launch 1", node.contentDescription)
        val r = Rect()
        @Suppress("DEPRECATION")
        node.getBoundsInParent(r)
        assertEquals(Rect(100, 200, 180, 262), r)
        assertTrue(node.isClickable)
        assertTrue(node.isVisibleToUser)
        assertEquals("Launch 2", provider.createAccessibilityNodeInfo(1)!!.contentDescription)
        assertNull(provider.createAccessibilityNodeInfo(2))
        // Activated, it is tapped where its dot is.
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(other, clicked)
    }

    private val launch = MapContainerView.Label("Launch 1", RectF(100f, 200f, 180f, 262f), PointF(140f, 210f))
    private val other = MapContainerView.Label("Launch 2", RectF(200f, 300f, 280f, 362f), PointF(240f, 310f))

    /** An accessibility service is on (a screen reader explores by touch). */
    private fun accessibilityOn(touchExploration: Boolean = false) {
        val manager = activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(touchExploration)
    }

    private val changes
        get() = recorder.events.filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }

    @Test
    fun changedNamesAreToldOnceTheMapSettlesAndFoundOnlyWhenAsked() {
        show()
        var found = 0
        container.labels = {
            found++
            listOf(launch)
        }
        // No service on: nothing to tell.
        container.labelsChanged()
        idle(1000)
        assertTrue(recorder.events.isEmpty())

        accessibilityOn()
        // The map comes to rest every frame while the loupe scrolls it: one event, 100 ms after the last.
        repeat(10) {
            container.labelsChanged()
            idle(16)
        }
        idle(83)
        assertTrue(changes.isEmpty())
        idle(1)
        assertEquals(1, changes.size)
        assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, changes[0].contentChangeTypes)
        // The names aren't asked of the map (a query that waits for its renderer) until a service asks for them.
        assertEquals(0, found)
        val provider = container.accessibilityNodeProvider
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertEquals(1, found)
        assertEquals(2, host.childCount) // the map and the name
        idle(1000)
        assertEquals(1, changes.size)
    }

    @Test
    fun namesChangedWhileAPointMovesAreToldWhenItIsLetGo() {
        show()
        accessibilityOn()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(300)
        container.labelsChanged() // due 50 ms after the press began
        idle(50)
        assertEquals(listOf("began 100,200"), heard.events)
        idle(1000)
        touch(MotionEvent.ACTION_MOVE, 160f, 200f)
        container.labelsChanged()
        idle(1000)
        assertTrue(changes.isEmpty())
        touch(MotionEvent.ACTION_UP, 160f, 200f)
        idle(99)
        assertTrue(changes.isEmpty())
        idle(1)
        assertEquals(1, changes.size)
        // Nothing held back: nothing more when the next point is let go.
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        idle(400)
        touch(MotionEvent.ACTION_UP, 100f, 200f)
        idle(1000)
        assertEquals(1, changes.size)
    }

    private fun hover(action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN // a pointer's hover goes to dispatchHoverEvent
        val handled = container.dispatchGenericMotionEvent(e)
        e.recycle()
        return handled
    }

    @Test
    fun exploringByTouchFindsTheNamesAgainWhenTheyMayHaveChanged() {
        show()
        accessibilityOn(touchExploration = true)
        var shown = listOf(launch)
        var found = 0
        container.labels = {
            found++
            shown
        }
        assertTrue(hover(MotionEvent.ACTION_HOVER_ENTER, 140f, 230f))
        assertEquals(1, found)
        assertTrue(hover(MotionEvent.ACTION_HOVER_MOVE, 141f, 231f))
        assertEquals(1, found)
        // The map moved: the next touch finds the names where they are now.
        shown = listOf(other)
        container.labelsChanged()
        assertTrue(hover(MotionEvent.ACTION_HOVER_MOVE, 240f, 330f))
        assertEquals(2, found)
        // Another name comes before it in the list: the one under the finger is still it, not entered again.
        shown = listOf(launch, other)
        container.labelsChanged()
        assertTrue(hover(MotionEvent.ACTION_HOVER_MOVE, 241f, 331f))
        assertEquals(3, found)
        val entered = recorder.events.filter { it.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER }
        assertEquals(listOf("Launch 1", "Launch 2"), entered.map { it.contentDescription.toString() })
        assertTrue(recorder.events.none { it.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT })
    }
}
