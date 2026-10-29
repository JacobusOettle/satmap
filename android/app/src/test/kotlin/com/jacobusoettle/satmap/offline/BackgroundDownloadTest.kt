package com.jacobusoettle.satmap.offline

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.AppLifecycle
import com.jacobusoettle.satmap.app.AppPaths
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.core.CoverageShape
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.MBTiles
import com.jacobusoettle.satmap.core.OfflineArea.Status
import com.jacobusoettle.satmap.core.TileFetchResult
import com.jacobusoettle.satmap.core.TileFetching
import com.jacobusoettle.satmap.core.TileID
import com.jacobusoettle.satmap.map.TileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration
import java.util.UUID

/** Downloads in the background (offline spec §2.5, DESIGN.md §5.2): `BackgroundDownload` with the dataSync service, its
 *  notification, the three status texts, Stop, the time running out, and `OfflineManager` going on and stopping in the
 *  background. */
@RunWith(AndroidJUnit4::class)
class BackgroundDownloadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    /** The services asked for. */
    private val started = mutableListOf<Intent>()
    private var services = mutableListOf<ServiceController<OfflineDownloadService>>()

    @Before
    fun setUp() {
        Defaults.init(app)
        AppPaths.init(app)
        MBTiles.driver = AndroidSqlDriver()
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
        AppLifecycle.resetForTests()
        if (!process.currentState.isAtLeast(Lifecycle.State.CREATED)) process.currentState = Lifecycle.State.CREATED
        AppLifecycle.init()
        process.currentState = Lifecycle.State.RESUMED
    }

    @After
    fun tearDown() {
        idle()
        for (s in services) s.destroy()
        BackgroundDownload.current = null
        process.currentState = Lifecycle.State.CREATED
        AppLifecycle.resetForTests()
        AppPaths.offline.deleteRecursively()
        AppPaths.offline.mkdirs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun wait(seconds: Double) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((seconds * 1000).toLong()))

    private fun background() = BackgroundDownload(app) { _, intent -> started += intent }

    /** Android starts the service asked for. */
    private fun startService(): OfflineDownloadService {
        val intent = started.last()
        assertEquals(OfflineDownloadService.ACTION_START, intent.action)
        assertEquals(OfflineDownloadService::class.java.name, intent.component!!.className)
        val c = Robolectric.buildService(OfflineDownloadService::class.java, intent).create().startCommand(0, services.size + 1)
        services += c
        return c.get()
    }

    private fun stop(service: OfflineDownloadService) =
        service.onStartCommand(Intent(app, OfflineDownloadService::class.java).setAction(OfflineDownloadService.ACTION_STOP), 0, 9)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.progress() = Pair(extras.getInt(Notification.EXTRA_PROGRESS), extras.getInt(Notification.EXTRA_PROGRESS_MAX))

    @Test
    fun theStatusSaysWhatHappensInTheBackground() {
        val b = background()
        val told = mutableListOf<String?>()
        b.onStatusChange = { told += it }
        assertNull(b.status)
        b.begin(title = "Farm", subtitle = "Starting…")
        assertEquals(1, started.size)
        assertEquals("Downloads keep going in the background, with their progress in the notification.", b.status)
        assertEquals(listOf(b.status), told)
        b.begin(title = "Farm", subtitle = "Starting…") // asked once
        assertEquals(1, started.size)

        notifications.setNotificationsEnabled(false)
        val quiet = background()
        quiet.begin(title = "Farm", subtitle = "Starting…")
        assertEquals("Downloads keep going in the background. Allow notifications for SatMap to see their progress there.",
                     quiet.status)
        notifications.setNotificationsEnabled(true)

        val refused = BackgroundDownload(app) { _, _ -> throw IllegalStateException("not allowed") }
        refused.begin(title = "Farm", subtitle = "Starting…")
        assertEquals("In the background a download stops, and continues when you open SatMap: Android didn't allow it to keep going in the background.",
                     refused.status)
        assertFalse(refused.allowsBackgroundWork)

        // Only asked with SatMap on screen.
        process.currentState = Lifecycle.State.CREATED
        val left = background()
        left.begin(title = "Farm", subtitle = "Starting…")
        assertNull(left.status)
        assertEquals(2, started.size)
    }

    @Test
    fun theServiceShowsTheProgressAndHoldsAWakeLock() {
        val b = background()
        val area = UUID.randomUUID()
        b.begin(title = "Farm", subtitle = "Starting…")
        assertFalse(b.allowsBackgroundWork) // not started yet
        val service = startService()
        assertTrue(OfflineDownloadService.running)
        assertTrue(b.allowsBackgroundWork)
        val s = shadowOf(service)
        assertEquals(BackgroundDownload.NOTIFICATION_ID, s.lastForegroundNotificationId)
        val first = s.lastForegroundNotification
        assertEquals("Farm", first.title())
        assertEquals("Starting…", first.text())
        assertEquals(BackgroundDownload.CHANNEL, first.channelId)
        assertTrue(first.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0xFFFFBD29.toInt(), first.color)
        assertEquals("Stop", first.actions.single().title.toString())
        assertNotNull(first.contentIntent)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(BackgroundDownload.CHANNEL)
        assertEquals("Offline downloads", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertEquals("SatMap:offlineDownload", shadowOf(lock).tag)

        // The progress, at most once a second.
        wait(1.0)
        b.update(area, title = "Farm", subtitle = "Elevation · zoom 3 of 15 · 2%", done = 20, total = 1000)
        val shown = notifications.getNotification(BackgroundDownload.NOTIFICATION_ID)
        assertEquals("Elevation · zoom 3 of 15 · 2%", shown.text())
        assertEquals(Pair(20, 1000), shown.progress())
        b.update(area, title = "Farm", subtitle = "Elevation · zoom 4 of 15 · 3%", done = 30, total = 1000)
        assertEquals("Elevation · zoom 3 of 15 · 2%", notifications.getNotification(BackgroundDownload.NOTIFICATION_ID).text())
        wait(1.0)
        assertEquals("Elevation · zoom 4 of 15 · 3%", notifications.getNotification(BackgroundDownload.NOTIFICATION_ID).text())

        // The next area adds on, the one before counting as done, and the bar never goes back.
        wait(1.0)
        val second = UUID.randomUUID()
        b.update(second, title = "Second farm", subtitle = "Elevation · zoom 0 of 15 · 0%", done = 10, total = 500)
        val next = notifications.getNotification(BackgroundDownload.NOTIFICATION_ID)
        assertEquals("Second farm", next.title())
        assertEquals(Pair(1010, 1500), next.progress())
        wait(1.0)
        b.update(second, title = "Second farm", subtitle = "Elevation · zoom 1 of 15 · 1%", done = 20, total = 500)
        wait(1.0)
        b.update(second, title = "Second farm", subtitle = "Elevation · zoom 1 of 15 · 0%", done = 5, total = 500)
        assertEquals(Pair(1020, 1500), notifications.getNotification(BackgroundDownload.NOTIFICATION_ID).progress())

        // Done: the service goes, with "Ready offline" for 8 s.
        b.end(success = true)
        assertTrue(s.isForegroundStopped)
        assertTrue(s.isStoppedBySelf)
        assertFalse(b.allowsBackgroundWork)
        val ready = notifications.getNotification(BackgroundDownload.READY_NOTIFICATION_ID)
        assertEquals("Ready offline", ready.text())
        assertEquals(8000L, ready.timeoutAfter)
        assertTrue(ready.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(ready.flags and Notification.FLAG_ONGOING_EVENT == 0)
        services.removeAt(0).destroy()
        assertFalse(lock.isHeld)
        assertFalse(OfflineDownloadService.running)
    }

    @Test
    fun aFailedOrEarlyEndShowsNothing() {
        val b = background()
        b.begin(title = "Farm", subtitle = "Starting…")
        b.end(success = true) // before the service started: it stops as soon as it does
        val service = startService()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(OfflineDownloadService.running)
        assertFalse(b.allowsBackgroundWork)
        assertNull(notifications.getNotification(BackgroundDownload.READY_NOTIFICATION_ID))

        b.begin(title = "Farm", subtitle = "Starting…")
        startService()
        b.end(success = false)
        assertNull(notifications.getNotification(BackgroundDownload.READY_NOTIFICATION_ID))
        // Nothing to end: nothing shown.
        b.end(success = true)
        assertNull(notifications.getNotification(BackgroundDownload.READY_NOTIFICATION_ID))
    }

    @Test
    fun withoutTheServiceTheBackgroundTimeEndsAfter25s() {
        val b = BackgroundDownload(app) { _, _ -> throw IllegalStateException("not allowed") }
        var expired = 0
        b.onExpire = { expired += 1 }
        b.begin(title = "Farm", subtitle = "Starting…")
        b.enteredBackground()
        assertTrue(b.allowsBackgroundWork) // the next area may still start meanwhile
        wait(24.9)
        assertEquals(0, expired)
        wait(0.1)
        assertEquals(1, expired)
        assertFalse(b.allowsBackgroundWork)
        // Back in time: nothing ends.
        b.enteredBackground()
        b.enteredForeground()
        wait(30.0)
        assertEquals(1, expired)
    }

    @Test
    fun theTimeOfTheServiceRunningOut() {
        val b = background()
        var expired = 0
        b.onExpire = { expired += 1 }
        b.begin(title = "Farm", subtitle = "Starting…")
        val service = startService()
        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertEquals(1, expired)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(b.allowsBackgroundWork)
        // Started again the next time a download begins on screen.
        b.begin(title = "Farm", subtitle = "Elevation · zoom 3 of 15 · 2%")
        assertEquals(2, started.size)
    }

    /** `OfflineManager` with the service (offline spec §1.6, §2.5). */
    private fun manager(answer: suspend () -> TileFetchResult): OfflineManager = OfflineManager(
        app, network = MutableStateFlow(Connectivity.State(isOnline = true, isExpensive = false)),
        freeDiskSpace = { 20L shl 30 },
        fetcher = { _, _ -> object : TileFetching {
            override suspend fun fetch(tile: TileID) = answer()
        } },
        io = Dispatchers.Main,
        readers = object : OfflineManager.AreaReaders {
            override fun setAreas(refs: List<TileRepository.AreaRef>) {}
            override fun closeArea(id: UUID) {}
        },
    )

    private val farm = CoverageShape(polygons = listOf(listOf(
        GeoPoint(lat = -25.4700, lon = 30.9700), GeoPoint(lat = -25.4700, lon = 30.9710),
        GeoPoint(lat = -25.4709, lon = 30.9710), GeoPoint(lat = -25.4709, lon = 30.9700),
    )))

    @Test
    fun aDownloadGoesOnInTheBackgroundUntilStoppedFromTheNotification() {
        val gate = CompletableDeferred<Unit>()
        var open = false
        val m = manager {
            if (!open) gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        idle()
        val a = m.add(name = "Farm", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        // The real start: Robolectric records it.
        val intent = shadowOf(app).nextStartedService
        assertEquals(OfflineDownloadService.ACTION_START, intent.action)
        started += intent
        val service = startService()
        assertEquals("Downloads keep going in the background, with their progress in the notification.",
                     m.backgroundStatus.value)
        assertTrue(m.background.allowsBackgroundWork)

        // SatMap is left: the download goes on.
        process.currentState = Lifecycle.State.CREATED
        idle()
        assertEquals(a, m.activeID.value)
        assertEquals(Status.downloading, m.areas.value.single().status)

        // Stop in the notification: it stops, to go on when SatMap is opened, and the service goes.
        stop(service)
        idle()
        assertNull(m.activeID.value)
        assertEquals(Status.queued, m.areas.value.single().status)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(m.background.allowsBackgroundWork)
        val b = m.add(name = "Next", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        assertEquals(Status.queued, m.areas.value.single { it.id == b }.status) // nothing starts in the background

        // Opened again: it goes on.
        open = true
        process.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(listOf(Status.complete, Status.complete), m.areas.value.map { it.status })
        gate.complete(Unit)
    }

    @Test
    fun stopWithSatMapOnScreenPausesTheArea() {
        val gate = CompletableDeferred<Unit>()
        val m = manager {
            gate.await()
            TileFetchResult.Data(byteArrayOf(1))
        }
        idle()
        val a = m.add(name = "Farm", shape = farm, roads = null, preset = null, maxZoom = 16, allowCellular = false).id
        idle()
        started += shadowOf(app).nextStartedService
        val service = startService()
        stop(service)
        idle()
        assertEquals(Status.paused, m.areas.value.single { it.id == a }.status)
        assertNull(m.activeID.value)
        assertTrue(shadowOf(service).isStoppedBySelf) // nothing else to download
        gate.complete(Unit)
    }

    @Test
    fun aRefusedServiceSaysSo() {
        val b = background()
        b.begin(title = "Farm", subtitle = "Starting…")
        val c = Robolectric.buildService(OfflineDownloadService::class.java, started.last()).create()
        shadowOf(c.get()).setThrowInStartForeground(IllegalStateException("time used up"))
        c.startCommand(0, 1)
        services += c
        assertEquals("In the background a download stops, and continues when you open SatMap: Android didn't allow it to keep going in the background.",
                     b.status)
        assertFalse(b.allowsBackgroundWork)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }
}
