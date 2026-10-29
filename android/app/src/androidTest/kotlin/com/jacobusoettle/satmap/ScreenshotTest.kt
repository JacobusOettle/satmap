package com.jacobusoettle.satmap

import android.Manifest
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.OfflineArea
import com.jacobusoettle.satmap.map.MapController
import com.jacobusoettle.satmap.map.MapIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.roundToInt

/** Drives the app through its main screens on an emulator and saves screenshots (run by
 *  .github/workflows/android-screenshots.yml): SatMapUITests/ScreenshotTests.swift step for step, with the same
 *  snapshot names, waits and checks (DESIGN.md §8.2). The simulated walk is on a vineyard near Stellenbosch; the
 *  matching demo blocks (DemoFarm.kml) are placed in the app's storage by the workflow.
 *
 *  As with XCTest's `continueAfterFailure`, a failed check doesn't end the walk: the failures are collected and
 *  reported together at the end, so a screen that isn't there yet only fails the steps that use it.
 *
 *  Under the Compose test rule the app's screens recompose and animate on the rule's clock, which moves only when the
 *  test moves it. Every wait here moves it on with real time (`sleep`), so the screens keep up as they do on a phone.
 *
 *  For the emulator in CI (Android only): each screenshot is written under another name and then renamed, so the
 *  workflow can pull them while the walk goes on; the waits for a download end early when no download is under way
 *  or it stopped moving; and the walk stops itself after `WALK_MINUTES`, before the workflow's own limit, so the
 *  failures found so far are still reported. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val arrival = listOf(
        GeoPoint(-33.918292, 18.921204),
        GeoPoint(-33.918426, 18.921365),
        GeoPoint(-33.918559, 18.921525),
        GeoPoint(-33.918693, 18.921686),
        GeoPoint(-33.918827, 18.921847),
        GeoPoint(-33.918960, 18.922008),
        GeoPoint(-33.919094, 18.922169),
        GeoPoint(-33.919227, 18.922330),
        GeoPoint(-33.919361, 18.922491),
        GeoPoint(-33.919539, 18.922616),
        GeoPoint(-33.919717, 18.922741),
        GeoPoint(-33.919895, 18.922867),
        GeoPoint(-33.920073, 18.922992),
        GeoPoint(-33.920251, 18.923117),
        GeoPoint(-33.920429, 18.923242),
        GeoPoint(-33.920620, 18.923319),
        GeoPoint(-33.920811, 18.923395),
        GeoPoint(-33.921001, 18.923472),
        GeoPoint(-33.921192, 18.923549),
        GeoPoint(-33.921383, 18.923625),
        GeoPoint(-33.921574, 18.923702),
        GeoPoint(-33.921765, 18.923779),
    )

    private val walkUphill = listOf(
        GeoPoint(-33.921765, 18.923779),
        GeoPoint(-33.921957, 18.923736),
        GeoPoint(-33.922150, 18.923693),
        GeoPoint(-33.922343, 18.923650),
        GeoPoint(-33.922536, 18.923607),
        GeoPoint(-33.922729, 18.923564),
        GeoPoint(-33.922922, 18.923521),
        GeoPoint(-33.923100, 18.923490),
        GeoPoint(-33.923278, 18.923459),
        GeoPoint(-33.923456, 18.923428),
        GeoPoint(-33.923634, 18.923397),
        GeoPoint(-33.923812, 18.923366),
        GeoPoint(-33.923990, 18.923335),
        GeoPoint(-33.924168, 18.923304),
        GeoPoint(-33.924346, 18.923273),
        GeoPoint(-33.924524, 18.923242),
    )

    /** Every permission SatMap asks for, so no system dialog covers the map (the iOS workflow grants location too). */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** The checks that failed, each with the screenshot it came after. */
    private val failures = mutableListOf<String>()
    private val problems = mutableSetOf<String>()
    private var step = "launch"

    /** Real time and Compose time when the test began (`syncClock`). */
    private var realStart = 0L
    private var clockStart = 0L

    /** When the walk stops itself (uptime), so the workflow's time limit never cuts it off without a report. */
    private var deadline = Long.MAX_VALUE

    /** The walk took longer than `WALK_MINUTES` (thrown from a wait, caught by the test). */
    private class OutOfTime : RuntimeException("the walk took longer than $WALK_MINUTES minutes")

    @Test
    fun screenshots() {
        // Queries for the system's own screens (the share sheet, permission prompts, the names on the map) wait at most
        // this long for the screen to settle, so a busy map doesn't stretch iOS's waits.
        Configurator.getInstance().waitForIdleTimeout = 2000
        limitIdleWaits()
        clearScreenshots()
        realStart = SystemClock.uptimeMillis()
        clockStart = compose.mainClock.currentTime
        deadline = realStart + WALK_MINUTES * 60_000L
        try {
            TestLocations.setUp()
        } catch (e: Exception) {
            fail("No mock positions (allowed with appops … android:mock_location allow?): ${short(e)}")
        }
        try {
            setLocation(arrival[0])
            val scenario = launch()
            try {
                allowPermissionPrompts()
                walkthrough()
            } catch (e: Throwable) {
                Log.e(TAG, "The walk stopped", e)
                fail("The walk stopped: ${short(e)}")
            } finally {
                try {
                    scenario?.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "Closing SatMap: ${short(e)}")
                }
            }
        } finally {
            TestLocations.tearDown()
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} failed checks:\n" + failures.joinToString("\n"))
        }
    }

    private fun walkthrough() {
        // Walk in along the farm road: the dotted track.
        walk(arrival)
        sleep(15) // satellite tiles and contours load
        snap("01-map")

        // Measure: crosshair, then marker, then walk uphill.
        setMeasure(MeasureState.aiming)
        sleep(2)
        snap("02-measure-crosshair")
        setMeasure(MeasureState.placed)
        walk(walkUphill)
        sleep(10) // elevations for the height difference
        snap("03-measure-live")

        // Blocks from the KML file.
        tap("Blocks")
        if (!waitForExistence(navigationBar("Blocks"), 5)) tap("Blocks") // a tap can be lost while the app is busy
        sleep(2)
        snap("04-blocks-list")
        check(exists(anySwitch()), "No switch to show or hide the file")
        check(exists(id("useFileColours")), "No colours checkbox")
        tapRow(startingWith = "Demo farm")
        for (i in 0 until 3) {
            sleep(2)
            if (!exists(navigationBar("Blocks"))) break
            tapRow(startingWith = "Demo farm") // a tap can be lost while the app is busy: the sheet then stays
        }
        sleep(23)
        snap("05-blocks-on-map")

        // Offline: download the area around the blocks.
        tap("Offline maps")
        sleep(2)
        snap("06-offline-maps")
        tap("Use the blocks in a KML file")
        tapRow(startingWith = "Demo farm")
        sleep(8) // size estimate
        snap("07-download-area")
        scrollTo(button("Download"))
        tap("Download")
        sleep(25)
        snap("08-downloading")

        tap("Offline maps")
        // As on iOS, up to a quarter of an hour for the download, whether or not the sheet opened; but only while it
        // is under way and moving (without a download, as before M4, the wait ends after a minute).
        waitForDownload(900.0) { exists(staticText("Ready offline")) }
        sleep(2)
        snap("09-offline-areas")

        // Choosing an area with the lasso (measuring switched off first for a clearer view).
        closeSheet("Offline maps")
        setMeasure(MeasureState.off)
        openOfflineSheet()
        tap("Draw an area on the map")
        sleep(3)
        snap("10-draw-area")

        // The terrain button: contour lines with elevation colours, the colours alone, then neither.
        for (i in 0 until 4) {
            if (waitForExistence(button("Contour lines"), 2)) break
            if (!tap("Cancel")) break // (with no Cancel, tapping again brings nothing)
        }
        setTerrain("Contour lines and elevation colours")
        sleep(12) // heights for the colours
        check(exists(id("colourKey")), "No legend for the colours")
        check(exists(id("contourKey")), "No legend for the contour lines")
        snap("22-contours-and-colours")
        setTerrain("Elevation colours")
        sleep(6)
        snap("23-elevation-colours")
        setTerrain("Contours and colours off")
        sleep(3)
        snap("11-contours-off")
        setTerrain("Contour lines")

        // Tap a spot: its coordinates at the top (your own at the bottom); tapping them copies them.
        tapScreen(0.35, 0.45)
        val tapped = id("tappedCoordinates")
        if (waitForExistence(tapped, 10)) {
            sleep(3)
            snap("15-coordinates")
            click(tapped)
            checkEqual(label(tapped), "Copied")
            // Pressed and held, the pin is moved precisely with the finger (a glass square shows 15 m around it).
            sleep(3) // "Copied" goes
            val before = label(tapped)
            Gestures.pressAndDrag(screenPoint(0.35, 0.45), screenPoint(0.43, 0.45), 900)
            sleep(2)
            check(label(tapped) != before, "Holding and dragging the pin didn't move it")
        } else {
            fail("No coordinates for the tapped spot")
        }
        // A tap on a point in the KML file selects it, with its name.
        // (MapKit may give the point's name as its own text or as the whole annotation, dot on top. The map here gives
        // accessibility services the names it shows, each as an element over the whole symbol, dot on top.)
        val launch = mapElement(Pattern.compile(Pattern.quote("Launch 1")), 5)
        if (launch != null) {
            device.click(launch.centerX(), launch.top - 1)
            check(waitForExistence(id("tappedName"), 5), "Tapping the KML point didn't select it")
            sleep(3)
            snap("24-kml-point")
        } else {
            // The map itself is asked whether it draws the point, to tell the two failures apart; when it does, the
            // point is tapped there, so the step still runs.
            val drawn = mapLabel("Launch 1", kmlPointLayers())
            if (drawn != null) {
                fail("The KML point is on the map, but accessibility services don't see it")
                device.click(drawn.x, drawn.y)
                check(waitForExistence(id("tappedName"), 5), "Tapping the KML point didn't select it")
                sleep(3)
                snap("24-kml-point")
            } else {
                fail("The KML point isn't on the map")
            }
        }
        // Drone mode: the airfields around, with how far they are from you and from a tapped spot, and where you are
        // from the nearest one. Zoomed out by going to a spot three times as far as Stellenbosch's airfield (FASH)
        // (the map then fits you and the spot), centred on you again with My location, then a spot is tapped.
        setTerrain("Drone mode: airfields and navaids")
        tap("Go to coordinates")
        val goTo = id("coordinateText")
        if (waitForExistence(goTo, 10)) {
            click(goTo)
            typeText(goTo, "-34.093892, 18.620506") // you + 3 × (FASH − you)
            sleep(1)
            tap("Show and measure from here")
            sleep(3)
            setMeasure(MeasureState.off)
            tap("My location")
            sleep(3)
        } else {
            fail("No field for coordinates")
        }
        val airfield = Pattern.compile("(Airport |Airstrip )?FASH Stellenbosch")
        tapScreen(0.7, 0.62)
        sleep(6)
        check(exists(id("droneCard")), "No reporting card in drone mode")
        check(exists(id("tappedCoordinates")), "Tapping the map in drone mode didn't place a pin")
        // On the map itself, not only in the card ("6.1 NM NE of FASH Stellenbosch").
        check(mapElement(airfield, 0) != null, "Stellenbosch airfield (FASH) isn't on the map in drone mode")
        snap("25-drone-mode")
        setTerrain("Contour lines")
        // Back in close: My location, then again for 300 m across.
        tap("My location")
        sleep(2)
        tap("My location")
        sleep(4)
        check(exists(id("myCoordinates")), "No coordinates of your position")
        // The heights beside both; a tap shows them in feet, another in metres again.
        for (tag in listOf("myHeight", "tappedHeight")) {
            val height = id(tag)
            check(exists(height), "No height beside $tag")
            check(value(height)?.contains("above sea level") == true, "$tag has no height: ${value(height)}")
        }
        val tappedHeight = id("tappedHeight")
        click(tappedHeight)
        sleep(1)
        check(value(tappedHeight)?.contains(" ft ") == true, "Height not in feet: ${value(tappedHeight)}")
        click(tappedHeight)
        sleep(1)
        check(value(tappedHeight)?.contains(" m ") == true, "Height not back in metres")

        // Measuring between two points: a tap anywhere puts a point at the crosshair; the map is moved between them.
        setMeasure(MeasureState.aiming)
        tapScreen(0.3, 0.4) // A, in the middle
        sleep(2)
        dragMap(0.25, 0.0)
        tapScreen(0.62, 0.62) // B
        sleep(8) // elevations of both points
        checkEqual(currentMeasureState(), MeasureState.pair)
        snap("16-measure-two-points")
        // A tap on the distance: miles, then nautical miles, then kilometres again.
        val distance = id("measuredDistance")
        if (exists(distance)) {
            click(distance)
            sleep(1)
            label(distance).let { check(it.endsWith(" mi") || it.endsWith(" ft"), "Not in miles: $it") }
            click(distance)
            sleep(1)
            label(distance).let { check(it.endsWith(" NM"), "Not in nautical miles: $it") }
            click(distance)
            sleep(1)
            label(distance).let { check(it.endsWith(" km") || it.endsWith(" m"), "Not back in km: $it") }
        } else {
            fail("No measured distance to tap")
        }

        // Two more points, then tap A again: the shape's area.
        dragMap(0.0, 0.2)
        tapScreen(0.75, 0.45) // C
        sleep(2)
        dragMap(-0.25, 0.0)
        tapScreen(0.3, 0.45) // D
        sleep(2)
        tap("Close shape") // (the map glides on a little after each drag, so where A ends up isn't known exactly)
        sleep(3)
        checkEqual(currentMeasureState(), MeasureState.shape)
        check(exists(staticText("Shape A–D · 4 corners")), "The shape didn't get four corners")
        snap("19-measure-area")
        setMeasure(MeasureState.off)

        // A position pasted in (any format, here degrees, minutes and seconds): measuring from it.
        tap("Go to coordinates")
        val field = id("coordinateText")
        if (waitForExistence(field, 10)) {
            click(field)
            typeText(field, "33°55'10\"S 18°55'20\"E")
            sleep(1)
            snap("21-go-to-coordinates")
            tap("Show and measure from here")
            sleep(3)
            checkEqual(currentMeasureState(), MeasureState.placed)
            setMeasure(MeasureState.off)
        } else {
            fail("No field for coordinates")
        }

        // The one-tap Kruger National Park roads (not downloaded here: several GB).
        openOfflineSheet()
        // (The long wait only once the tap was made: without the button there is nothing to wait for.)
        if (tap("Kruger National Park roads") && waitForExistence(staticText("Roads and tracks"), 120)) {
            sleep(5) // size estimate
            scrollTo(button("Download"))
            snap("17-kruger-roads")
        } else {
            fail("Kruger roads didn't load")
        }
        tapBack() // back to Offline maps
        sleep(1)

        // A place found by name: the Sabi Sand reserve, within its boundary.
        tap("Find a park, reserve or town")
        val search = id("placeSearchText")
        if (waitForExistence(search, 10)) {
            click(search)
            typeText(search, "Sabi Sand\n")
            val reserve = staticText(startingWith = "Sabi Sand")
            if (waitForExistence(reserve, 40)) {
                sleep(1)
                snap("26-find-place")
                click(reserve)
                // (The Download button is created only once the form is scrolled to it.)
                if (waitForExistence(navigationBar("Download area"), 60)) {
                    sleep(6) // boundary and size estimate
                    snap("27-place-area")
                    scrollTo(button("Download"))
                    check(exists(button("Download")), "No Download button for the reserve")
                } else {
                    fail("The reserve's download screen didn't open")
                }
                tapBack() // back to the results
                sleep(1)
            } else {
                fail("Sabi Sand wasn't found")
            }
        } else {
            fail("No place search field")
        }
        tapBack() // back to Offline maps
        sleep(1)

        // Imagery along the simulated walk (not downloaded here, so the area counts below stay the same).
        if (tap("Where I've been") && waitForExistence(staticText("Your track"), 30)) {
            sleep(3) // size estimate
            scrollTo(button("Download"))
            snap("20-where-ive-been")
        } else {
            fail("The track didn't load")
        }
        tapBack() // back to Offline maps
        sleep(1)

        // The same blocks again, but satellite imagery only along the roads.
        tap("Use the blocks in a KML file")
        tapRow(startingWith = "Demo farm")
        val name = textField()
        if (waitForExistence(name, 10)) {
            click(name)
            typeText(name, "Farm roads\n", replacing = true)
        }
        tapRow(startingWith = "Zoom 20")
        // Scroll until the Download button shows: the switch above it is then well inside the screen.
        scrollTo(button("Download"))
        // Without roads there is nothing true to show: stop rather than snap a whole-area download.
        if (!setSwitch("Only along roads", on = true)) return
        // The public road servers are sometimes busy: try again, as a user would.
        val roadsLoaded = staticText("Roads and tracks")
        for (i in 0 until 60) {
            if (exists(roadsLoaded)) break
            val again = button("Try again")
            if (exists(again) && isHittable(again)) click(again)
            sleep(4)
        }
        if (!exists(roadsLoaded)) return fail("Roads didn't load")
        sleep(3) // size estimate
        scrollTo(button("Download"))
        snap("12-download-along-roads")
        tap("Download")
        sleep(5)
        openOfflineSheet()
        val ready = staticText("Ready offline")
        val alongRoads = staticText(containing = "along roads")
        waitForDownload(900.0) { count(ready) >= 2 }
        if (count(ready) < 2 || !exists(alongRoads)) return fail("No area downloaded along roads")
        snap("13-offline-along-roads")

        // Open the roads area and add one deeper level (only zoom 21 is downloaded).
        tapRow(startingWith = "Farm roads")
        if (waitForExistence(staticText("Imagery found down to"), 20)) {
            tapRow(startingWith = "Zoom 21")
            sleep(3) // size of the extra level
            scrollTo(button("Download zoom 21"))
            snap("18-area-deeper-level")
            tap("Download zoom 21")
            sleep(2)
            tapBack() // back to Offline maps
            waitForDownload(600.0) { count(ready) >= 2 }
            checkEqual(count(ready), 2, "The roads area didn't finish its extra level")
            check(exists(staticText(containing = "up to zoom 21")), "No area up to zoom 21")
        } else {
            fail("The area screen didn't open")
            tapBack()
        }

        // Backup: export both areas through the share sheet.
        scrollTo(button("Export all offline maps"))
        tap("Export all offline maps")
        val saveToFolder = By.text("Save to a folder")
        if (!waitUntil(30.0) { device.hasObject(saveToFolder) }) return fail("No share sheet")
        sleep(2)
        snap("14-export-backup")
    }

    // Helpers

    private enum class MeasureState { off, aiming, placed, pair, shape }

    /** Taps the measure button (or its ✕ to stop) until the screen shows the wanted state (a tap can be lost while the
     *  app is busy). */
    private fun setMeasure(wanted: MeasureState) {
        for (i in 0 until 4) {
            if (currentMeasureState() == wanted) return
            if (wanted == MeasureState.off) tap("Stop measuring") else tap("Measure")
            sleep(2)
        }
        checkEqual(currentMeasureState(), wanted)
    }

    /** Taps a sheet's Done until the sheet has gone (a tap can be lost while the app is busy). */
    private fun closeSheet(title: String) {
        val bar = navigationBar(title)
        for (i in 0 until 4) {
            if (!exists(bar)) return
            tap("Done")
            sleep(2)
        }
        check(!exists(bar), "The $title sheet didn't close")
    }

    /** Moves the map by dragging it, a share of the screen each way (slowly, so it doesn't glide on). */
    private fun dragMap(dx: Double, dy: Double) {
        Gestures.pressAndDrag(screenPoint(0.5, 0.45), screenPoint(0.5 + dx, 0.45 + dy), 200)
        sleep(2)
    }

    private fun currentMeasureState(): MeasureState {
        if (exists(staticText(startingWith = "Move the crosshair onto the spot"))) return MeasureState.aiming
        if (exists(staticText(startingWith = "From A to"))) return MeasureState.pair
        if (exists(staticText(startingWith = "Shape A"))) return MeasureState.shape
        val panel = exists(staticText(containing = "than you")) || exists(staticText(startingWith = "Height:")) ||
            exists(staticText(startingWith = "Waiting for GPS"))
        return if (panel) MeasureState.placed else MeasureState.off
    }

    /** Taps the terrain button (each tap the next of four) until it shows the wanted mode (a tap can be lost while the
     *  app is busy). */
    private fun setTerrain(wanted: String) {
        val button = button("Contour lines")
        for (i in 0 until 8) {
            if (value(button) == wanted) return
            tap("Contour lines")
            sleep(2)
        }
        checkEqual(value(button), wanted)
    }

    /** Opens the Offline maps sheet, tapping again if a tap was lost. */
    private fun openOfflineSheet() {
        for (i in 0 until 4) {
            tap("Offline maps")
            if (waitForExistence(button("Draw an area on the map"), 5)) return
        }
        fail("The Offline maps sheet didn't open")
    }

    /** Toggles a switch until it has the wanted value; returns whether it has. The row must be fully on screen.
     *  (The whole row is the switch, as on recent iOS, so a tap anywhere on it toggles it.) */
    private fun setSwitch(label: String, on: Boolean): Boolean {
        val row = switch(label)
        if (!waitForExistence(row, 10)) {
            fail("No switch '$label'")
            return false
        }
        fun isOn() = node(row)?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        for (i in 0 until 3) {
            if (isOn() == on) break
            click(row)
            sleep(1)
        }
        checkEqual(isOn(), on, "Switch '$label' didn't change")
        return isOn() == on
    }

    /** Scrolls a form up, a third of the screen at a time, until the element is wholly on screen and clear of the top
     *  bar and the navigation bar (lists only create the rows near the screen, and a tap on a sliver at the bottom edge
     *  is lost). */
    private fun scrollTo(element: Query) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        for (i in 0 until 8) {
            val frame = screenFrame(element)
            if (frame != null && isHittable(element) &&
                frame.top > 100 * density && frame.bottom < device.displayHeight - 40 * density
            ) {
                return
            }
            Gestures.pressAndDrag(screenPoint(0.5, 0.75), screenPoint(0.5, 0.4), 100)
            sleep(1)
        }
    }

    private fun setLocation(p: GeoPoint) {
        TestLocations.set(p)
    }

    private fun walk(points: List<GeoPoint>) {
        TestLocations.walk(points) { ms -> sleep(ms / 1000.0) } // the app uses one fix every 2 s on screen
    }

    /** Returns whether the button was there to tap (so a long wait for what it opens can be skipped). */
    private fun tap(label: String): Boolean {
        val button = button(label)
        if (!waitForExistence(button, 10)) {
            fail("No button '$label'")
            return false
        }
        // A button under a sheet that is still closing can't be tapped yet.
        for (i in 0 until 30) {
            if (isHittable(button)) break
            sleep(1)
        }
        if (!isHittable(button)) {
            snap("not tappable - $label", debug = true)
            fail("Button '$label' can't be tapped")
            return false
        }
        click(button)
        return true
    }

    /** List rows are buttons whose label combines their texts ("Demo farm, 7 shapes · …"). */
    private fun tapRow(startingWith: String) {
        val row = Query(hasClickAction() and labelStarts(startingWith), unmerged = false, "row '$startingWith'")
        if (waitForExistence(row, 10)) {
            click(row)
            return
        }
        val label = staticText(startingWith)
        if (waitForExistence(label, 2)) {
            click(label)
        } else {
            fail("No row '$startingWith'")
        }
    }

    /** iOS `app.navigationBars.buttons.element(boundBy: 0).tap()`: the top bar's back button. */
    private fun tapBack() {
        val back = id("back")
        if (exists(back)) click(back) else fail("No back button")
    }

    /** Location (and motion) permission prompts come from the system's permission controller (none shows when the rule
     *  above has granted the permissions). As on iOS, up to 6 s for each button in turn, twice: with no prompt (as in
     *  CI) the walk starts 24 s after launch, so the first screenshot gives the imagery as long to load as on iOS. */
    private fun allowPermissionPrompts() {
        val controller = Pattern.compile(".*permissioncontroller")
        for (i in 0 until 2) {
            var handled = false
            for (label in listOf("While using the app", "Only this time", "Allow", "OK")) {
                val b = By.pkg(controller).text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
                if (waitUntil(6.0) { device.hasObject(b) }) {
                    try {
                        device.findObject(b)?.click()
                    } catch (e: Exception) {
                        Log.w(TAG, "Prompt button '$label': ${short(e)}")
                    }
                    handled = true
                    break
                }
            }
            if (!handled) break
        }
    }

    /** Saves a screenshot to the app's external files (pulled by the workflow); `debug` ones go to a folder of their
     *  own, kept with the run but not committed. Written as "<name>.png.part", then renamed: the workflow pulls the
     *  .png files while the walk goes on, and must never get one half written. */
    private fun snap(name: String, debug: Boolean = false) {
        if (!debug) step = name
        settle()
        val folder = screenshotFolder(debug) ?: return fail("No folder for screenshots")
        val part = File(folder, "$name.png.part")
        if (!device.takeScreenshot(part) || !part.renameTo(File(folder, "$name.png"))) {
            part.delete()
            fail("Screenshot '$name' wasn't saved")
        }
        Log.i(TAG, "snap $name")
    }

    // The Android side of XCTest

    /** An iOS query: a Compose matcher over the merged tree (buttons and rows, whose label combines their texts) or
     *  the unmerged one (single texts and identifiers). */
    private class Query(val matcher: SemanticsMatcher, val unmerged: Boolean, val name: String)

    /** `app.buttons[label]`: something tappable whose description or text is `label` (not a text field). */
    private fun button(label: String) = Query(
        hasClickAction() and SemanticsMatcher("labelled '$label'") {
            !isTextField(it) && (label in descriptions(it) || label in texts(it))
        },
        unmerged = false,
        "button '$label'",
    )

    /** `app.descendants(matching: .any)[identifier]`: the node with that test tag. */
    private fun id(tag: String) = Query(hasTestTag(tag), unmerged = true, "'$tag'")

    /** `app.staticTexts`: a text that is `text`, starts with `startingWith` or contains `containing`. */
    private fun staticText(text: String? = null, startingWith: String? = null, containing: String? = null): Query {
        val what = text ?: startingWith ?: containing ?: ""
        return Query(
            SemanticsMatcher("text '$what'") { n ->
                !isTextField(n) && texts(n).any {
                    (text == null || it == text) && (startingWith == null || it.startsWith(startingWith)) &&
                        (containing == null || it.contains(containing))
                }
            },
            unmerged = true,
            "text '$what'",
        )
    }

    /** `app.navigationBars[title]`: the sheet's top bar showing `title`. */
    private fun navigationBar(title: String): Query {
        val titled = staticText(title).matcher
        return Query(hasTestTag("screenTitle") and (titled or hasAnyDescendant(titled)), unmerged = true, "bar '$title'")
    }

    /** `app.switches[label]`: a switch row labelled `label`. */
    private fun switch(label: String) = Query(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            SemanticsMatcher("labelled '$label'") { label in descriptions(it) || label in texts(it) },
        unmerged = false,
        "switch '$label'",
    )

    private fun anySwitch() =
        Query(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), unmerged = false, "a switch")

    /** `app.textFields`: anything text can be typed into. */
    private fun textField() = Query(hasSetTextAction(), unmerged = false, "a text field")

    private fun labelStarts(prefix: String) = SemanticsMatcher("label starting '$prefix'") { n ->
        (descriptions(n) + texts(n)).any { it.startsWith(prefix) }
    }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun descriptions(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun isTextField(n: SemanticsNode) = SemanticsProperties.EditableText in n.config

    private fun nodes(q: Query): List<SemanticsNode> = try {
        syncClock()
        compose.onAllNodes(q.matcher, q.unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false)
    } catch (e: Throwable) {
        problem(e)
        emptyList()
    }

    private fun node(q: Query) = nodes(q).firstOrNull()

    private fun exists(q: Query) = node(q) != null

    private fun count(q: Query) = nodes(q).size

    /** `.label`. */
    private fun label(q: Query): String = node(q)?.let(::labelOf).orEmpty()

    /** The description, else the texts (else those of what is inside), joined as iOS joins a row's texts. */
    private fun labelOf(n: SemanticsNode): String =
        descriptions(n).ifEmpty { texts(n) }.joinToString(", ")
            .ifEmpty { n.children.map(::labelOf).filter { it.isNotEmpty() }.joinToString(", ") }

    /** `.value`. */
    private fun value(q: Query): String? = node(q)?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** `.isHittable`, as far as Compose knows: shown on its window. */
    private fun isHittable(q: Query): Boolean = try {
        syncClock()
        compose.onAllNodes(q.matcher, q.unmerged).onFirst().isDisplayed()
    } catch (e: Throwable) {
        false
    }

    private fun click(q: Query) {
        try {
            syncClock()
            compose.onAllNodes(q.matcher, q.unmerged).onFirst().performClick()
        } catch (e: Throwable) {
            fail("Couldn't tap ${q.name}: ${short(e)}")
        }
    }

    /** `typeText`; a final "\n" is the keyboard's return key (the field's IME action). `replacing` first deletes what
     *  the field holds. */
    private fun typeText(q: Query, text: String, replacing: Boolean = false) {
        try {
            syncClock()
            val field = compose.onAllNodes(q.matcher, q.unmerged).onFirst()
            if (replacing) field.performTextClearance()
            val typed = text.removeSuffix("\n")
            if (typed.isNotEmpty()) field.performTextInput(typed)
            if (text.endsWith("\n")) field.performImeAction()
        } catch (e: Throwable) {
            fail("Couldn't type '$text' into ${q.name}: ${short(e)}")
        }
    }

    /** `waitForExistence(timeout:)`. */
    private fun waitForExistence(q: Query, seconds: Int) = waitUntil(seconds.toDouble()) { exists(q) }

    /** Waits up to `seconds` for `done`, as iOS waits for a download to finish, but only while a download is under way
     *  or will be: it gives up after a minute with no area queued, downloading or waiting for a network (none was
     *  started, as before M4, or it stopped for good: paused or incomplete), or after three minutes in which a running
     *  download got no further. An area waiting for a network is retried by the app itself after 15 s, 30 s, 60 s …
     *  up to 10 minutes (also while the phone is online, when the tile server stops answering for a while), so that
     *  wait counts as under way, and as moving: as on iOS, the walk rides it out. Returns whether `done` came true. */
    private fun waitForDownload(seconds: Double, done: () -> Boolean): Boolean {
        val offline = AppModel.shared.offline
        var lastBusy = SystemClock.uptimeMillis()
        var lastMoved = lastBusy
        var lastMark: String? = null
        var finished = false
        var why = ""
        waitUntil(seconds) {
            if (done()) {
                finished = true
                return@waitUntil true
            }
            val now = SystemClock.uptimeMillis()
            val areas = offline.areas.value
            val active = offline.activeID.value
            val busy = active != null || areas.any {
                it.status == OfflineArea.Status.queued || it.status == OfflineArea.Status.downloading ||
                    it.status == OfflineArea.Status.waitingForNetwork
            }
            if (busy) lastBusy = now
            val p = offline.progress.value
            val mark = "$active ${p?.layer} ${p?.zoom} ${p?.levelDone} ${p?.overallDone} ${p?.failed}"
            if (mark != lastMark) {
                lastMark = mark
                lastMoved = now
            }
            // Only a running download can get stuck: between runs (waiting to retry) nothing moves.
            if (active == null) lastMoved = now
            val states = areas.joinToString { a -> "${a.name}: ${a.status}" + (a.problem?.let { ", $it" } ?: "") }
            why = when {
                now - lastBusy >= 60_000 -> "no download under way (${states.ifEmpty { "no areas" }})"
                now - lastMoved >= 180_000 -> "the download got no further for three minutes ($states)"
                else -> ""
            }
            why.isNotEmpty()
        }
        if (!finished) Log.w(TAG, "after $step: stopped waiting for the download: ${why.ifEmpty { "out of time" }}")
        return finished
    }

    private fun waitUntil(seconds: Double, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= end) return false
            sleep(0.25)
        }
    }

    private fun sleep(seconds: Int) = sleep(seconds.toDouble())

    /** Waits `seconds`, moving the Compose clock on with real time meanwhile. Past the walk's deadline it throws
     *  `OutOfTime` instead (every wait comes here). */
    private fun sleep(seconds: Double) {
        val end = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
        while (true) {
            if (SystemClock.uptimeMillis() >= deadline) throw OutOfTime()
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return
            Thread.sleep(minOf(left, 50L))
            try {
                syncClock()
            } catch (e: Throwable) {
                problem(e)
            }
        }
    }

    /** Moves the Compose clock on to real time (waiting for the screens to settle can take it further, never back). */
    private fun syncClock() {
        val behind = (SystemClock.uptimeMillis() - realStart) - (compose.mainClock.currentTime - clockStart)
        if (behind > 0) compose.mainClock.advanceTimeBy(behind, ignoreFrameDuration = true)
    }

    /** Espresso, through which the Compose rule waits for the app to settle, gives up on a busy app after 20 s instead
     *  of a minute, so a screen that never settles slows the walk down but doesn't stop the job. (Espresso is only on
     *  the run-time path of these tests, not the compile path: hence the lookup by name.) */
    private fun limitIdleWaits() {
        try {
            val policies = Class.forName("androidx.test.espresso.IdlingPolicies")
            val long = Long::class.javaPrimitiveType
            policies.getMethod("setMasterPolicyTimeout", long, TimeUnit::class.java).invoke(null, 20L, TimeUnit.SECONDS)
            policies.getMethod("setIdlingResourceTimeout", long, TimeUnit::class.java).invoke(null, 15L, TimeUnit.SECONDS)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Espresso's idle timeouts stay as they are: ${short(e)}")
        }
    }

    /** The screens up to date before a screenshot. */
    private fun settle() {
        try {
            syncClock()
            compose.waitForIdle()
        } catch (e: Throwable) {
            problem(e)
        }
    }

    // Screen and map

    /** `app.coordinate(withNormalizedOffset:)`: a share of the screen's width and height, in pixels. */
    private fun screenPoint(x: Double, y: Double) =
        PointF((x * device.displayWidth).toFloat(), (y * device.displayHeight).toFloat())

    private fun tapScreen(x: Double, y: Double) {
        val p = screenPoint(x, y)
        device.click(p.x.roundToInt(), p.y.roundToInt())
    }

    /** `.frame`, in screen pixels. */
    private fun screenFrame(q: Query): RectF? = node(q)?.let {
        val p = it.positionOnScreen
        RectF(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
    }

    /** `app.descendants(matching: .any)` with a label: a name on the map itself (a KML point, an airfield) that is
     *  `label` in full, as accessibility services see it, with its frame on the screen; null when none shows within
     *  `seconds`. The map gives them the names MapLibre has placed, each as an element over its whole symbol (dot on
     *  top for a point), as MapKit makes elements of the annotations it shows. */
    private fun mapElement(label: Pattern, seconds: Int): Rect? {
        var frame: Rect? = null
        waitUntil(seconds.toDouble()) {
            frame = try {
                device.findObject(By.desc(label))?.visibleBounds?.takeIf { !it.isEmpty }
            } catch (e: RuntimeException) {
                null // (gone while it was read: StaleObjectException)
            }
            frame != null
        }
        return frame
    }

    /** The map's own answer, when accessibility doesn't find a name (`MapController.renderedLabels`, what MapLibre has
     *  placed in `layers`): the spot just above the name `name` (the dot of a point is on top), or null when the map
     *  doesn't show it. Only to tell "not drawn" from "not given to accessibility" and to tap it then. */
    private fun mapLabel(name: String, layers: List<String>): Point? {
        var spot: Point? = null
        instrumentation.runOnMainSync {
            // (On the main thread an exception would end the app.)
            try {
                val map = AppModel.shared.map as? MapController ?: return@runOnMainSync
                val box = map.renderedLabels(layers).firstOrNull { it.first == name }?.second ?: return@runOnMainSync
                val at = IntArray(2).also { map.container.getLocationOnScreen(it) }
                spot = Point((at[0] + box.centerX()).roundToInt(), (at[1] + box.top - 1).roundToInt())
            } catch (e: Throwable) {
                Log.w(TAG, "The names on the map: ${short(e)}")
            }
        }
        return spot
    }

    /** The points layers of the shown KML files. */
    private fun kmlPointLayers() = AppModel.shared.kml.files.value.filter { it.isShown }.map { MapIds.kmlPoints(it.id) }

    // Launch, failures, files

    /** `app.launch()`. */
    private fun launch(): ActivityScenario<MainActivity>? = try {
        ActivityScenario.launch(MainActivity::class.java)
    } catch (e: Throwable) {
        fail("SatMap didn't open: ${short(e)}")
        null
    }

    private fun fail(message: String) {
        val line = "after $step: $message"
        failures += line
        Log.e(TAG, line)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    private fun <T> checkEqual(actual: T, expected: T, message: String = "") {
        if (actual != expected) fail("($actual) is not equal to ($expected)" + if (message.isEmpty()) "" else " - $message")
    }

    /** An error from the Compose test machinery (an exception in the app's screens arrives this way too), reported
     *  once. */
    private fun problem(e: Throwable) {
        val text = short(e)
        if (problems.add(text)) {
            Log.e(TAG, "Compose", e)
            fail(text)
        }
    }

    private fun short(e: Throwable) = "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"

    private fun screenshotFolder(debug: Boolean = false): File? {
        val root = instrumentation.targetContext.getExternalFilesDir("screenshots") ?: return null
        return (if (debug) File(root, "debug") else root).apply { mkdirs() }
    }

    /** Only this run's screenshots are pulled. */
    private fun clearScreenshots() {
        screenshotFolder()?.walkBottomUp()?.filter { it.isFile }?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "ScreenshotTest"

        /** How long the walk may take before it stops itself: under the 45 minutes the workflow gives the run
         *  (android-screenshots.yml), so the checks that failed are still reported. */
        const val WALK_MINUTES = 40
    }
}
