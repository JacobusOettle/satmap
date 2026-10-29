package com.jacobusoettle.satmap.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.location.LocationRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.SensorBuilder

/** The permissions asked at launch, as iOS asks: location (fine and coarse together), then motion once location is
 *  allowed and a step detector exists; each once per install, and not again while a dialog is up. */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Before
    fun setUp() {
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        Defaults.init(app)
        AppLifecycle.init()
        shadowOf(app).denyPermissions(fine, coarse, Manifest.permission.ACTIVITY_RECOGNITION)
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    /** An activity with the flow made in its onCreate, as MainActivity does, resumed. */
    private fun activity(recorder: LocationRecorder): Pair<ActivityController<ComponentActivity>, PermissionFlow> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val flow = PermissionFlow(controller.get(), recorder)
        controller.start().resume()
        return controller to flow
    }

    private fun answer(activity: ComponentActivity, granted: Boolean) {
        val asked = shadowOf(activity).lastRequestedPermission
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        if (granted) shadowOf(app).grantPermissions(*asked.requestedPermissions)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, IntArray(asked.requestedPermissions.size) { result })
    }

    @Test
    fun locationIsAskedOnceAndADenialStays() {
        val recorder = LocationRecorder(app)
        val (controller, flow) = activity(recorder)
        flow.next()
        val asked = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(listOf(fine, coarse), asked.requestedPermissions.toList())
        assertEquals(true, Defaults.bool("locationPermissionAsked"))
        // The activity resumes under the dialog: not asked twice.
        flow.next()
        assertSame(asked, shadowOf(controller.get()).lastRequestedPermission)
        answer(controller.get(), granted = false)
        assertEquals(LocationRecorder.Authorization.denied, recorder.authorization.value)
        flow.next()
        assertSame(asked, shadowOf(controller.get()).lastRequestedPermission)
    }

    @Test
    fun motionIsAskedAfterLocationWhenThereIsAStepDetector() {
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_DETECTOR).build())
        val recorder = LocationRecorder(app)
        val (controller, flow) = activity(recorder)
        flow.next()
        answer(controller.get(), granted = true)
        assertEquals(LocationRecorder.Authorization.authorized, recorder.authorization.value)
        // Asked right after the answer.
        val motion = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.ACTIVITY_RECOGNITION), motion.requestedPermissions.toList())
        assertEquals(true, Defaults.bool("motionPermissionAsked"))
        answer(controller.get(), granted = false)
        flow.next()
        assertSame(motion, shadowOf(controller.get()).lastRequestedPermission)
    }

    @Test
    fun allowingMotionStartsCountingStepsAtOnce() {
        val sensors = app.getSystemService(SensorManager::class.java)
        // This phone has only the wake-up kind.
        shadowOf(sensors).addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_DETECTOR).setWakeUpFlag(true).build())
        shadowOf(app).grantPermissions(fine, coarse)
        val recorder = LocationRecorder(app)
        process.currentState = Lifecycle.State.RESUMED // recording
        val (controller, flow) = activity(recorder)
        flow.next()
        val motion = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.ACTIVITY_RECOGNITION), motion.requestedPermissions.toList())
        assertEquals(0, shadowOf(sensors).listeners.size)
        answer(controller.get(), granted = true)
        assertEquals(1, shadowOf(sensors).listeners.size)
    }

    @Test
    fun noMotionQuestionWithoutAStepDetector() {
        shadowOf(app).grantPermissions(fine, coarse)
        val recorder = LocationRecorder(app)
        val (controller, flow) = activity(recorder)
        flow.next()
        assertNull(shadowOf(controller.get()).lastRequestedPermission)
        assertNull(Defaults.bool("motionPermissionAsked"))
    }
}
