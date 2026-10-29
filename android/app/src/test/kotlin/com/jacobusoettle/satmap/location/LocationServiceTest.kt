package com.jacobusoettle.satmap.location

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.location.LocationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.ElevationSource
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.map.TileRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The foreground service that keeps recording in the background: its notification, and its Stop. */
@RunWith(AndroidJUnit4::class)
class LocationServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    @Before
    fun setUp() {
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        Defaults.init(app)
        AppPaths.init(app)
        Connectivity.init(app)
        AppLifecycle.init()
        TileRepository.init(app)
    }

    @After
    fun tearDown() {
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
    }

    @Test
    fun itRunsInTheForegroundSayingRecordingYourTrack() {
        val service = Robolectric.buildService(LocationService::class.java, Intent(app, LocationService::class.java))
            .create().startCommand(0, 1).get()
        val shadow = shadowOf(service)
        assertEquals(LocationService.ID, shadow.lastForegroundNotificationId)
        val n = shadow.lastForegroundNotification
        assertEquals("SatMap", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Recording your track", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(LocationService.CHANNEL, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(LocationService.CHANNEL)
        assertEquals("Track recording", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun stopEndsRecordingAndTheService() {
        val model = AppModel(app, object : ElevationSource {
            override suspend fun elevation(p: GeoPoint): Double? = null
        })
        AppModel.shared = model
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        process.currentState = Lifecycle.State.RESUMED
        val lm = app.getSystemService(LocationManager::class.java)
        assertEquals(1, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)
        val service = Robolectric.buildService(LocationService::class.java, Intent(app, LocationService::class.java))
            .create().startCommand(0, 1).get()
        // The notification's Stop, delivered to the running service.
        service.onStartCommand(Intent(app, LocationService::class.java).setAction(LocationService.ACTION_STOP), 0, 2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)
        assertEquals(LocationRecorder.Power.active, model.recorder.power.value)
    }
}
