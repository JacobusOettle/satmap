package com.jacobusoettle.satmap.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.DroneLink
import com.jacobusoettle.satmap.core.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Duration
import java.util.Collections

/** Drone link's receiver and model (android/docs/spec/drone-link.md): a real UDP loopback of every fixture datagram to
 *  the listener (the iPhone's `testUDPLoopback`), the settings, the socket only in the foreground with the switch on,
 *  a port in use, and the demo replay through the same decoder. */
@RunWith(AndroidJUnit4::class)
class DroneLinkModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val heights = object : ElevationSource {
        override suspend fun elevation(p: GeoPoint): Double? = 140.0
    }
    private var now = 1000.0
    private lateinit var model: DroneLinkModel
    private val sockets = ArrayList<DatagramSocket>()

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().clear().commit()
        model = DroneLinkModel(heights).also { it.clock = { now } }
    }

    @After
    fun tearDown() {
        model.backgrounded()
        sockets.forEach { it.close() }
        Defaults.prefs.edit().clear().commit()
    }

    private val fixtures get() = File(System.getProperty("satmap.fixtures")!!)

    private val cases by lazy {
        Json.parseToJsonElement(File(fixtures, "mavlink/cases.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    private fun datagram(name: String): ByteArray =
        DroneLink.bytes(cases.first { it.getValue("name").jsonPrimitive.content == name }.getValue("hex").jsonPrimitive.content)!!

    private fun send(bytes: ByteArray, port: Int) {
        val s = DatagramSocket().also { sockets += it }
        s.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }

    /** A port nothing listens on. */
    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    /** Runs the main thread's work (the model ticks four times a second) for `seconds` of Robolectric's clock. */
    private fun run(seconds: Double) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((seconds * 1000).toLong()))

    /** Lets the main thread run until `done` holds (the socket's thread hands datagrams over), at most 5 s. */
    private fun waitFor(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            run(0.3)
            if (done()) return true
            Thread.sleep(20)
        }
        return done()
    }

    private fun canBind(port: Int): Boolean = try {
        DatagramSocket(null).use { it.bind(InetSocketAddress(port)) }
        true
    } catch (e: SocketException) {
        false
    }

    // The receiver

    @Test
    fun udpLoopback() {
        val received = Collections.synchronizedList(ArrayList<ByteArray>())
        val all = cases.map { DroneLink.bytes(it.getValue("hex").jsonPrimitive.content)!! }
        val receiver = UdpReceiver(0) { received.add(it) }
        receiver.start()
        assertTrue(receiver.isRunning)
        val port = receiver.port
        assertTrue(port > 0)
        // A second listener can't take the same port while the first has it.
        val clash = UdpReceiver(port) {}
        try {
            clash.start()
            fail("A second listener took port $port")
        } catch (e: SocketException) {
            // as it should
        }
        assertFalse(clash.isRunning)
        for (d in all) send(d, port)
        val until = System.currentTimeMillis() + 10_000
        while (received.size < all.size && System.currentTimeMillis() < until) Thread.sleep(20)
        receiver.stop()
        assertFalse(receiver.isRunning)
        val datagrams = synchronized(received) { received.toList() }
        assertEquals(all.map { it.toList() }.toSet(), datagrams.map { it.toList() }.toSet())
        // Through the decoder, the same as the fixture says.
        val traffic = DroneLink.Traffic()
        var frames = 0
        for (d in datagrams) frames += traffic.receive(d, now).frames
        assertEquals(cases.sumOf { it.getValue("expect").jsonArray.size }, frames)
        assertEquals(setOf(1, 2), traffic.drones.keys)
        // Stopped, nothing more arrives, and the port is free again.
        send(all[0], port)
        Thread.sleep(300)
        assertEquals(datagrams.size, received.size)
        assertTrue(canBind(port))
    }

    @Test
    fun oversizedDatagramsAreDroppedAndAFailedSocketSaysSo() {
        val received = Collections.synchronizedList(ArrayList<ByteArray>())
        var stopped = 0
        val receiver = UdpReceiver(0, onStopped = { synchronized(received) { stopped++ } }) { received.add(it) }
        receiver.start()
        val port = receiver.port
        send(ByteArray(UdpReceiver.maxDatagram + 1) { 0xFD.toByte() }, port)
        send(datagram("v2-heartbeat-armed"), port)
        val until = System.currentTimeMillis() + 5_000
        while (received.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals("only the real datagram", listOf(datagram("v2-heartbeat-armed").toList()), received.map { it.toList() })
        // The socket fails under it: it says so once, and no longer counts as running.
        receiver.breakForTests()
        val until2 = System.currentTimeMillis() + 5_000
        while (receiver.isRunning && System.currentTimeMillis() < until2) Thread.sleep(20)
        Thread.sleep(100)
        assertFalse(receiver.isRunning)
        assertEquals(1, synchronized(received) { stopped })
        receiver.stop() // (harmless)
        assertEquals(1, synchronized(received) { stopped })
        // Stopped on purpose: no call.
        var stopped2 = 0
        val other = UdpReceiver(0, onStopped = { stopped2++ }) {}
        other.start()
        other.stop()
        Thread.sleep(100)
        assertEquals(0, stopped2)
    }

    // The model

    @Test
    fun offByDefaultAndTheDisclaimerComesFirst() {
        assertFalse(model.isEnabled.value)
        assertEquals(DroneLink.defaultPort, model.port.value)
        assertEquals(DroneLink.Status.Off, model.status.value)
        model.setEnabled(true)
        assertTrue(model.askingDisclaimer.value)
        assertFalse(model.isEnabled.value)
        model.declineDisclaimer()
        assertFalse(model.askingDisclaimer.value)
        assertFalse(model.isEnabled.value)
        model.setEnabled(true)
        model.acceptDisclaimer()
        assertFalse(model.askingDisclaimer.value)
        assertTrue(model.isEnabled.value)
        // Asked once.
        model.setEnabled(false)
        model.setEnabled(true)
        assertFalse(model.askingDisclaimer.value)
        assertTrue(model.isEnabled.value)
    }

    @Test
    fun settingsAreSavedAppWideAndOnlyTheStoredValuesCount() {
        model.setEnabled(true)
        model.acceptDisclaimer()
        model.setPort(14550)
        model.setPort(80) // refused: below 1024
        assertEquals(14550, model.port.value)
        val again = DroneLinkModel(heights)
        assertTrue(again.isEnabled.value)
        assertEquals(14550, again.port.value)
        again.setEnabled(false)
        again.setEnabled(true)
        assertFalse("accepted once, app-wide", again.askingDisclaimer.value)
        // Launch extras can't switch it on (any app can start SatMap with extras).
        Defaults.prefs.edit().clear().commit()
        Defaults.setLaunchArguments(android.os.Bundle().apply { putBoolean(DroneLink.enabledKey, true) })
        try {
            assertFalse(DroneLinkModel(heights).isEnabled.value)
        } finally {
            Defaults.setLaunchArguments(null)
        }
    }

    private fun switchOn(port: Int) {
        model.setPort(port)
        model.setEnabled(true)
        model.acceptDisclaimer()
        model.foregrounded()
    }

    @Test
    fun receivesOnlyInTheForegroundWithTheSwitchOn() {
        val port = freePort()
        model.setPort(port)
        model.setEnabled(true)
        model.acceptDisclaimer()
        assertFalse("not in the foreground yet", model.isListening)
        model.foregrounded()
        assertTrue(model.isListening)
        assertEquals(DroneLink.Status.Waiting(port), model.status.value)
        assertTrue(waitFor {
            send(datagram("v2-heartbeat-armed"), port)
            send(datagram("adsb-three-in-one-datagram"), port)
            model.traffic.drones.isNotEmpty() && model.traffic.aircraft.size == 3
        })
        assertEquals("Receiving from 1 drone · 3 aircraft", model.status.value.text)
        model.backgrounded()
        assertFalse(model.isListening)
        assertTrue("the socket was closed", waitFor { canBind(port) })
        model.foregrounded()
        assertTrue(model.isListening)
        model.setEnabled(false)
        assertFalse(model.isListening)
        assertEquals(DroneLink.Status.Off, model.status.value)
        assertTrue("switched off, what was heard goes", model.traffic.drones.isEmpty())
        assertTrue(waitFor { canBind(port) })
    }

    @Test
    fun silenceAndTheRules() {
        val port = freePort()
        switchOn(port)
        assertTrue(waitFor {
            send(datagram("v2-global-position-int"), port)
            model.traffic.drones.isNotEmpty()
        })
        // Datagrams still on their way (sent again while waiting) arrive before the clock moves on.
        Thread.sleep(300)
        run(0.5)
        model.select(DroneLinkModel.Selection.Drone(1))
        run(0.5)
        assertEquals(140.0, model.selectedGroundM.value!!, 0.0)
        now += 11
        run(0.5)
        assertEquals("No data for 11 s", model.status.value.text)
        assertTrue(model.traffic.drones.getValue(1).isStale(model.now()))
        now += 50
        run(0.5)
        assertTrue("gone 60 s after its last position", model.traffic.drones.isEmpty())
        assertNull("its panel closes", model.selection.value)
        assertNull(model.selectedGroundM.value)
    }

    @Test
    fun aPortInUseIsReportedAndTriedAgain() {
        // Another app's socket: ours can't share the port.
        val taken = DatagramSocket(null).apply { bind(InetSocketAddress(0)) }
        val port = taken.localPort
        switchOn(port)
        assertEquals(DroneLink.Status.Failed(port), model.status.value)
        assertEquals("Can't listen on port $port: another app may be using it", model.status.value.text)
        assertFalse(model.isListening)
        // The other app lets it go: within a few seconds it is ours.
        taken.close()
        run(4.0)
        assertTrue(model.isListening)
        assertEquals(DroneLink.Status.Waiting(port), model.status.value)
    }

    @Test
    fun aChangedPortReopensTheSocket() {
        val first = freePort()
        switchOn(first)
        assertTrue(waitFor { send(datagram("v2-heartbeat-armed"), first); model.traffic.drones.isNotEmpty() })
        val second = freePort()
        model.setPort(second)
        assertTrue(waitFor { canBind(first) })
        assertEquals(DroneLink.Status.Receiving(1, 0), model.status.value) // (what was heard stays)
        assertTrue(waitFor { send(datagram("second-drone"), second); model.traffic.drones.size == 2 })
    }

    @Test
    fun aNewPortAfterAFailedOneIsListenedOnAtOnce() {
        val taken = DatagramSocket(null).apply { bind(InetSocketAddress(0)) }
        sockets += taken
        switchOn(taken.localPort)
        assertEquals(DroneLink.Status.Failed(taken.localPort), model.status.value)
        assertFalse(model.isListening)
        val free = freePort()
        model.setPort(free)
        assertTrue("bound to the new port straight away", model.isListening)
        assertEquals(DroneLink.Status.Waiting(free), model.status.value)
        assertFalse(canBind(free))
        assertTrue(waitFor { send(datagram("v2-heartbeat-armed"), free); model.traffic.drones.isNotEmpty() })
    }

    @Test
    fun aSocketThatDiesIsReportedAndOpenedAgain() {
        val port = freePort()
        switchOn(port)
        assertTrue(model.isListening)
        model.receiverForTests!!.breakForTests()
        assertTrue(waitFor { model.status.value == DroneLink.Status.Failed(port) })
        assertFalse(model.isListening)
        // The 3 s retry opens it again.
        run(4.0)
        assertTrue(model.isListening)
        assertEquals(DroneLink.Status.Waiting(port), model.status.value)
        assertTrue(waitFor { send(datagram("v2-heartbeat-armed"), port); model.traffic.drones.isNotEmpty() })
    }

    @Test
    fun aFloodIsCappedByBytesAndDecodedOffTheMainThread() {
        val port = freePort()
        switchOn(port)
        // Garbage with a plausible MAVLink 2 header every few bytes, 8 KB a datagram: at most 1 MB waits.
        val garbage = ByteArray(UdpReceiver.maxDatagram) { k -> if (k % 10 == 0) 0xFD.toByte() else 0xFF.toByte() }
        repeat(200) { model.enqueue(garbage) }
        model.enqueue(datagram("v2-heartbeat-armed"))
        run(0.3)
        assertTrue("dropped while the inbox was full", model.traffic.drones.isEmpty())
        // The next quarter second has room again.
        model.enqueue(datagram("v2-heartbeat-armed"))
        run(0.3)
        assertEquals(setOf(1), model.traffic.drones.keys)
    }

    @Test
    fun nothingIsReceivedWhileATestBuildIsLocked() {
        switchOn(freePort())
        assertTrue(model.isListening)
        model.setLocked(true)
        assertFalse(model.isListening)
        model.setLocked(false)
        assertTrue(model.isListening)
    }

    @Test
    fun theDemoReplayGoesThroughTheDecoderAndSavesNothing() {
        val replay = DroneLink.Replay.parse(File(fixtures, "../../../docs/mavlink/demo_flight.json").readText())!!
        model.useReplayForTests(replay)
        assertTrue(model.isDemo)
        model.foregrounded()
        model.setEnabled(true)
        assertTrue("the walkthrough sees the disclaimer", model.askingDisclaimer.value)
        model.acceptDisclaimer()
        assertTrue(model.isListening)
        // Twenty seconds of the flight on Robolectric's clock (the model's own clock follows).
        repeat(80) {
            now += 0.25
            run(0.25)
        }
        assertEquals(DroneLink.Status.Receiving(drones = 1, aircraft = 3), model.status.value)
        assertEquals(setOf(0x00A1B2L, 0x00B3C4L, 0x00C5D6L), model.traffic.aircraft.keys)
        // Nothing was saved, and the network comes back with the saved settings.
        assertNull(Defaults.storedBool(DroneLink.enabledKey))
        assertNull(Defaults.storedBool(DroneLink.disclaimerKey))
        model.useReplayForTests(null)
        assertFalse(model.isDemo)
        assertFalse(model.isEnabled.value)
    }

    @Test
    fun addresses() {
        model.interfaceAddresses = { listOf("lo" to "127.0.0.1", "rmnet_data0" to "100.71.2.9", "wlan0" to "192.168.1.40", "ap0" to "192.168.43.1") }
        model.refreshAddresses()
        assertEquals(listOf("Wi-Fi", "Hotspot"), model.addresses.value.map { it.label })
        assertEquals(
            "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name 192.168.1.40:14445, restart QGC",
            model.qgcInstructions,
        )
        // The real interfaces can be read (whatever this machine has).
        UdpReceiver.interfaceAddresses()
    }
}
