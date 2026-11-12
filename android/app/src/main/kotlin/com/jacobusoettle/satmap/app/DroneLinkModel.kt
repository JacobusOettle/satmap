package com.jacobusoettle.satmap.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.jacobusoettle.satmap.core.DroneLink
import com.jacobusoettle.satmap.core.Geo
import com.jacobusoettle.satmap.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/** A receive-only UDP socket on 0.0.0.0:`port` (android/docs/spec/drone-link.md §2): every datagram that arrives is
 *  handed on, from the socket's own thread, and nothing is ever sent. No SO_REUSEADDR: on Linux it would let a second
 *  socket share the port, and UDP has no TIME_WAIT to wait out after a close. The iPhone's `UDPReceiver`. Receiving
 *  unicast UDP needs no permission at targetSdk 36 (INTERNET only). */
class UdpReceiver(private val requestedPort: Int, private val onDatagram: (ByteArray) -> Unit) {
    @Volatile
    private var socket: DatagramSocket? = null

    /** The port it listens on while started (0 asks for any free one, for tests). */
    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = socket != null

    /** Opens and binds the socket and starts its thread. Throws if the port can't be had (another app has it). */
    @Throws(SocketException::class)
    fun start() {
        if (socket != null) return
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = false
            s.soTimeout = 1000 // so a closed socket's thread ends promptly
            s.bind(InetSocketAddress(requestedPort))
        } catch (e: SocketException) {
            s.close()
            throw e
        }
        socket = s
        port = s.localPort
        Thread({ drain(s) }, "DroneLink-UDP-$port").apply {
            isDaemon = true
            start()
        }
    }

    /** Closes the socket; its thread ends. */
    fun stop() {
        val s = socket ?: return
        socket = null
        port = 0
        s.close()
    }

    private fun drain(s: DatagramSocket) {
        val buffer = ByteArray(65_536)
        val packet = DatagramPacket(buffer, buffer.size)
        while (socket === s) {
            try {
                packet.setLength(buffer.size)
                s.receive(packet)
                if (socket === s) onDatagram(buffer.copyOf(packet.length))
            } catch (e: SocketTimeoutException) {
                // (a chance to see whether it was stopped)
            } catch (e: Exception) {
                if (socket === s) Log.w("DroneLink", "receive: $e")
                break
            }
        }
        s.close()
    }

    companion object {
        /** The IPv4 addresses of the interfaces that are up (name and dotted address), loopback included; which of
         *  them to show is `DroneLink.addresses`. */
        fun interfaceAddresses(): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: SocketException) {
                emptyList()
            }
            for (ni in interfaces) {
                val up = try {
                    ni.isUp
                } catch (e: SocketException) {
                    false
                }
                if (!up) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address) a.hostAddress?.let { out.add(ni.name to it) }
                }
            }
            return out
        }
    }
}

/** Drone link (android/docs/spec/drone-link.md): listens for the MAVLink a ground station (QGroundControl, Mission
 *  Planner, MAVProxy) forwards to the phone over UDP, and keeps the drones and ADS-B aircraft it hears for drone mode's
 *  map. Receive-only: nothing is ever sent. The socket is open only while the switch is on and the app is in the
 *  foreground (closed in the background, opened again on return), and never while a test build is switched off. Main
 *  thread only, except the socket's thread, which only hands datagrams over. The iPhone's `DroneLinkModel`. */
class DroneLinkModel(private val elevation: ElevationSource) {
    /** What a tap on the map selected: a drone (system id) or an aircraft (ICAO address). */
    sealed interface Selection {
        data class Drone(val sysid: Int) : Selection
        data class Aircraft(val icao: Long) : Selection
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Seconds on a monotonic clock (the drones' and aircraft's times). Tests give their own. */
    @VisibleForTesting
    internal var clock: () -> Double = { SystemClock.elapsedRealtime() / 1000.0 }

    /** The phone's interfaces and their IPv4 addresses (tests give their own). */
    @VisibleForTesting
    internal var interfaceAddresses: () -> List<Pair<String, String>> = UdpReceiver::interfaceAddresses

    /** The walkthroughs' replay (`useReplayForTests`): a recorded flight goes through the same decoder, and nothing is
     *  listened for or saved. */
    var isDemo = false
        private set

    /** The switch (off by default), saved app-wide (the stored value only: no intent can switch it on). */
    private val _isEnabled = MutableStateFlow(Defaults.storedBool(DroneLink.enabledKey) ?: false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /** The port listened on (default 14445), saved app-wide. */
    private val _port = MutableStateFlow(DroneLink.savedPort(Defaults.storedInt(DroneLink.portKey)))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _status = MutableStateFlow<DroneLink.Status>(DroneLink.Status.Off)
    val status: StateFlow<DroneLink.Status> = _status.asStateFlow()

    /** Bumped when the drones or aircraft change (the map and the panel redraw), and at least once a second while
     *  listening (grey and fading follow the clock). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    /** The phone's Wi-Fi and hotspot addresses, as last looked up (the settings look them up again when shown). */
    private val _addresses = MutableStateFlow<List<DroneLink.Address>>(emptyList())
    val addresses: StateFlow<List<DroneLink.Address>> = _addresses.asStateFlow()

    /** Asked once, the first time the switch is turned on. */
    private val _askingDisclaimer = MutableStateFlow(false)
    val askingDisclaimer: StateFlow<Boolean> = _askingDisclaimer.asStateFlow()

    /** The ground's height under the selected drone (SatMap's elevation data, offline where downloaded), for its
     *  "≈ above ground"; null until found. */
    private val _selectedGroundM = MutableStateFlow<Double?>(null)
    val selectedGroundM: StateFlow<Double?> = _selectedGroundM.asStateFlow()

    val traffic = DroneLink.Traffic()

    private var disclaimerAccepted = Defaults.storedBool(DroneLink.disclaimerKey) ?: false
    private var isForeground = false
    private var isLocked = false
    private var receiver: UdpReceiver? = null

    /** The port that couldn't be had, until the next try. */
    private var failedPort: Int? = null

    /** Datagrams from the socket's thread, taken on the main thread four times a second; at most 2,000 waiting (a
     *  flood is dropped). */
    private val inbox = ArrayList<ByteArray>()

    /** The replay and its clock: seconds since it began (uptime), and up to when it was fed. */
    private var replay: DroneLink.Replay? = null
    private var replayStarted: Long? = null
    private var replayFed = -1.0

    private var ticking = false
    private var ticks = 0
    private var lastRedraw = Double.NEGATIVE_INFINITY
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            tick()
            handler.postDelayed(this, 250)
        }
    }

    // Settings

    /** The switch: turning it on the first time asks the disclaimer first. */
    fun setEnabled(on: Boolean) {
        if (on && !disclaimerAccepted) {
            _askingDisclaimer.value = true
            return
        }
        if (on == _isEnabled.value) return
        _isEnabled.value = on
        if (!isDemo) Defaults.putBoolean(DroneLink.enabledKey, on)
        update()
    }

    /** "I understand": the switch comes on. */
    fun acceptDisclaimer() {
        disclaimerAccepted = true
        if (!isDemo) Defaults.putBoolean(DroneLink.disclaimerKey, true)
        _askingDisclaimer.value = false
        setEnabled(true)
    }

    /** "Cancel" on the disclaimer: the switch stays off. */
    fun declineDisclaimer() {
        _askingDisclaimer.value = false
    }

    /** A new port (checked by `DroneLink.port`): listened on at once if the link is on. */
    fun setPort(p: Int) {
        if (p !in DroneLink.portRange || p == _port.value) return
        _port.value = p
        failedPort = null
        if (!isDemo) Defaults.putInt(DroneLink.portKey, p)
        if (receiver != null) {
            stopListening()
            update()
        } else {
            refreshStatus()
        }
    }

    fun refreshAddresses() {
        val list = DroneLink.addresses(interfaceAddresses())
        if (list != _addresses.value) _addresses.value = list
    }

    val qgcInstructions: String get() = DroneLink.qgcInstructions(_addresses.value.map { it.address }, _port.value)

    /** Test builds switched off: no socket (`TestBuildGate`). */
    fun setLocked(locked: Boolean) {
        isLocked = locked
        update()
    }

    fun foregrounded() {
        isForeground = true
        refreshAddresses()
        update()
    }

    fun backgrounded() {
        isForeground = false
        update()
    }

    /** Test-only (the walkthrough, docs §5): the recorded flight in place of the network from now on, with the switch
     *  off and the disclaimer not yet accepted, and nothing saved, as the iPhone's `-droneLinkDemo YES`; null: the
     *  network again, with the saved settings. Set through the instrumentation, in the app's own process: no intent
     *  extra can switch it on. */
    @VisibleForTesting
    fun useReplayForTests(r: DroneLink.Replay?) {
        stopListening()
        stopReplay()
        isDemo = r != null
        replay = r
        _isEnabled.value = if (r != null) false else Defaults.storedBool(DroneLink.enabledKey) ?: false
        disclaimerAccepted = if (r != null) false else Defaults.storedBool(DroneLink.disclaimerKey) ?: false
        _port.value = if (r != null) DroneLink.defaultPort else DroneLink.savedPort(Defaults.storedInt(DroneLink.portKey))
        _askingDisclaimer.value = false
        update()
    }

    // Listening

    private val shouldListen: Boolean get() = _isEnabled.value && isForeground && !isLocked

    val isListening: Boolean get() = receiver != null || replayStarted != null

    /** Opens or closes the socket (or the replay) for the switch, the app's state and the lock. */
    private fun update() {
        if (shouldListen) {
            if (isDemo) startReplay() else startListening()
            startTimer()
        } else {
            stopListening()
            stopReplay()
            stopTimer()
            if (!_isEnabled.value) {
                traffic.clear()
                _selection.value = null
                _revision.value++
            }
        }
        refreshStatus()
    }

    private fun startListening() {
        if (receiver != null) return
        val r = UdpReceiver(_port.value) { d ->
            synchronized(inbox) { if (inbox.size < 2000) inbox.add(d) }
        }
        try {
            r.start()
            receiver = r
            failedPort = null
        } catch (e: SocketException) {
            failedPort = _port.value
        }
    }

    private fun stopListening() {
        receiver?.stop()
        receiver = null
        synchronized(inbox) { inbox.clear() }
    }

    private fun startReplay() {
        if (replayStarted != null || replay == null) return
        replayStarted = SystemClock.uptimeMillis()
        replayFed = -1.0
        feedReplay()
    }

    private fun stopReplay() {
        replayStarted = null
    }

    private fun feedReplay() {
        val r = replay ?: return
        val start = replayStarted ?: return
        val now = (SystemClock.uptimeMillis() - start) / 1000.0
        val due = r.frames(replayFed, now)
        synchronized(inbox) { inbox.addAll(due) }
        replayFed = now
    }

    private fun startTimer() {
        if (ticking) return
        ticking = true
        tick()
        handler.postDelayed(tick, 250)
    }

    private fun stopTimer() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    /** Four times a second while listening: what arrived goes through the decoder, the old goes, the status line. */
    private fun tick() {
        // A port that was taken: try again every few seconds (the other app may have let it go).
        ticks++
        if (receiver == null && !isDemo && shouldListen && failedPort != null && ticks % 12 == 0) startListening()
        feedReplay()
        val now = clock()
        val datagrams = synchronized(inbox) { inbox.toList().also { inbox.clear() } }
        var changed = false
        for (d in datagrams) if (traffic.receive(d, now).frames > 0) changed = true
        if (traffic.prune(now)) changed = true
        if (changed || now - lastRedraw >= 1) {
            lastRedraw = now
            _revision.value++
        }
        _selection.value?.let { if (!exists(it)) _selection.value = null }
        lookUpGround()
        refreshStatus()
    }

    // The selected drone's ground

    private var groundFor: Pair<Int, GeoPoint>? = null
    private val groundLookup = HeightLookup(elevation) { p, e ->
        val g = groundFor
        if (g != null && g.second == p && e != _selectedGroundM.value) _selectedGroundM.value = e
    }

    /** Looks the ground up again once the selected drone has moved 20 m (or another is selected). */
    private fun lookUpGround() {
        val sel = _selection.value as? Selection.Drone
        val p = sel?.let { traffic.drones[it.sysid]?.point }
        if (sel == null || p == null) {
            if (groundFor != null) {
                groundFor = null
                _selectedGroundM.value = null
                groundLookup.set(null)
            }
            return
        }
        val g = groundFor
        if (g != null && g.first == sel.sysid && Geo.distance(g.second, p) < 20) return
        if (g?.first != sel.sysid) _selectedGroundM.value = null
        groundFor = sel.sysid to p
        groundLookup.set(p)
    }

    /** Selects a drone or aircraft (a tap on the map), or nothing. */
    fun select(s: Selection?) {
        _selection.value = s
        lookUpGround()
    }

    private fun exists(s: Selection): Boolean = when (s) {
        is Selection.Drone -> traffic.drones[s.sysid]?.point != null
        is Selection.Aircraft -> traffic.aircraft[s.icao] != null
    }

    private fun refreshStatus() {
        val p = failedPort
        val new = when {
            !_isEnabled.value -> DroneLink.Status.Off
            p != null && receiver == null && !isDemo -> DroneLink.Status.Failed(p)
            else -> traffic.status(_port.value, clock())
        }
        if (new != _status.value) _status.value = new
    }

    /** The time on `clock` now, for the screens' "N s ago" and grey/fade rules. */
    fun now(): Double = clock()
}
