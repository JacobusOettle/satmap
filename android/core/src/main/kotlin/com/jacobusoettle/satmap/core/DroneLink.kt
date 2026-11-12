package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Drone link (drone mode, android/docs/spec/drone-link.md): the drones and the ADS-B aircraft a ground station forwards
 *  over MAVLink, the rules for when they go grey and go, the texts, and the distances in nautical miles. The same names
 *  and behaviour as the iPhone's core (SatMap/Core/DroneLink.swift); the decoder is `MAVLink`. Times are seconds on
 *  the caller's clock (the iPhone's `Date`). */
object DroneLink {
    /** QGroundControl's "MAVLink Forwarding" default host is localhost:14445. */
    const val defaultPort = 14445
    val portRange = 1024..65535

    /** Saved app-wide. */
    const val enabledKey = "droneLinkEnabled"
    const val portKey = "droneLinkPort"
    const val disclaimerKey = "droneLinkDisclaimerAccepted"

    /** A drone without a new position this long is greyed, and removed after `droneRemoveAfter`. */
    const val droneStaleAfter = 10.0
    const val droneRemoveAfter = 60.0

    /** Aircraft fade and go as in QGroundControl. */
    const val aircraftStaleAfter = 15.0
    const val aircraftRemoveAfter = 120.0

    /** The status says no data after this long without a datagram. */
    const val silentAfter = 10.0

    /** Aircraft below this height within this distance of you or a drone are highlighted. */
    const val lowAltitudeFeet = 2000.0
    const val closeNauticalMiles = 5.0

    const val disclaimer = "Traffic awareness only. Not for navigation, separation or collision avoidance. Many aircraft " +
        "don't broadcast. Under SACAA Part 101 the remote pilot must give way to manned aircraft."

    /** A port typed in the settings, if it can be listened on. */
    fun port(text: String): Int? {
        val t = text.trim(' ')
        if (t.isEmpty() || t.length > 5 || t.any { it !in '0'..'9' }) return null
        return t.toInt().takeIf { it in portRange }
    }

    /** A saved port, or the default for anything out of range. */
    fun savedPort(value: Int?): Int = value?.takeIf { it in portRange } ?: defaultPort

    /** What to put in QGroundControl for these addresses. */
    fun qgcInstructions(addresses: List<String>, port: Int): String {
        val host = addresses.firstOrNull()?.let { "$it:$port" } ?: "<phone IP>:$port"
        return "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name $host, restart QGC"
    }

    // Network interfaces

    /** How the settings name an interface the phone can be reached on ("Wi-Fi", "Hotspot"); null for those that can't
     *  take a ground station's datagrams (loopback, mobile data, VPN tunnels, AWDL …). */
    fun interfaceLabel(name: String): String? = when {
        name == "en0" || name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("bridge") || name.startsWith("ap") || name.startsWith("swlan") -> "Hotspot"
        name.startsWith("en") || name.startsWith("eth") -> "Network"
        else -> null
    }

    /** Whether an IPv4 address (dotted) is one a ground station on the same Wi-Fi could send to: not loopback,
     *  link-local, unspecified or multicast. */
    fun isReachableIPv4(address: String): Boolean {
        val parts = address.split('.').mapNotNull { s -> if (s.isNotEmpty() && s.all { it in '0'..'9' } && s.length <= 3) s.toInt() else null }
        if (parts.size != 4 || address.count { it == '.' } != 3 || parts.any { it !in 0..255 }) return false
        if (parts[0] == 127 || parts[0] == 0 || (parts[0] == 169 && parts[1] == 254) || parts[0] >= 224) return false
        return true
    }

    /** An address to show: the interface's label and the dotted address. */
    data class Address(val label: String, val address: String)

    /** The addresses to show, Wi-Fi first, then the hotspot, then others; each once. `interfaces`: (name, address). */
    fun addresses(interfaces: List<Pair<String, String>>): List<Address> {
        val order = mapOf("Wi-Fi" to 0, "Hotspot" to 1, "Network" to 2)
        val seen = HashSet<String>()
        return interfaces.mapNotNull { (name, address) ->
            val label = interfaceLabel(name) ?: return@mapNotNull null
            if (!isReachableIPv4(address) || !seen.add(address)) return@mapNotNull null
            Address(label, address)
        }.withIndex().sortedWith(compareBy({ order[it.value.label] ?: 9 }, { it.index })).map { it.value }
    }

    // Status

    sealed interface Status {
        data object Off : Status
        data class Waiting(val port: Int) : Status
        data class Receiving(val drones: Int, val aircraft: Int) : Status
        data class Silent(val seconds: Int) : Status
        data class Failed(val port: Int) : Status

        val text: String
            get() = when (this) {
                Off -> "Drone link is off"
                is Waiting -> "Waiting for MAVLink on port $port…"
                is Receiving -> {
                    var s = if (drones == 0) "Receiving MAVLink" else "Receiving from $drones drone" + if (drones == 1) "" else "s"
                    if (aircraft > 0) s += " · $aircraft aircraft"
                    s
                }
                is Silent -> "No data for $seconds s"
                is Failed -> "Can't listen on port $port: another app may be using it"
            }
    }

    // Distances

    /** "4.2 NM 225° SW": how far and which way `to` is from `from`. */
    fun rangeAndBearing(from: GeoPoint, to: GeoPoint): String {
        val d = Geo.distance(from, to)
        if (d < 0.05 * Aviation.metresPerNauticalMile) return Aviation.nauticalMiles(d)
        val b = Geo.bearing(from, to).rounded().toInt() % 360
        return "${Aviation.nauticalMiles(d)} ${b.toString().padStart(3, '0')}° ${Aviation.compass(from, to)}"
    }

    /** A place found by `nearestAerodromes`, and how far it is. */
    data class Near(val place: AviationPoint, val meters: Double)

    /** The nearest aerodromes to a spot (not helipads unless nothing else is near), nearest first, within `withinNM`. */
    fun nearestAerodromes(index: AviationIndex, to: GeoPoint, count: Int = 3, withinNM: Double = 30.0): List<Near> {
        val p = to
        val limit = withinNM * Aviation.metresPerNauticalMile
        val latPad = limit / 111_000
        val lonPad = min(180.0, latPad / max(0.05, cos(p.lat * PI / 180)))
        val b = GeoBounds(
            minLat = max(-90.0, p.lat - latPad), minLon = p.lon - lonPad,
            maxLat = min(90.0, p.lat + latPad), maxLon = p.lon + lonPad,
        )
        val found = index.points(b) { it.kind.isAerodrome }
            .map { Near(index.points[it], Geo.distance(p, index.points[it].point)) }
            .filter { it.meters <= limit }
            .sortedBy { it.meters }
        val fields = found.filter { it.place.kind != AviationPoint.Kind.heliport }
        return (fields.ifEmpty { found }).take(count)
    }

    /** A reference point for the highlight (you, a drone) and the ground's height there if known. */
    data class Reference(val point: GeoPoint, val groundM: Double?)

    /** Whether an aircraft is low and close: within `closeNauticalMiles` of one of the reference points (you, a drone)
     *  and less than `lowAltitudeFeet` above that point's ground (its elevation when known, else sea level). An
     *  aircraft whose altitude isn't valid counts as low. */
    fun isLowAndClose(a: Aircraft, references: List<Reference>): Boolean {
        for (r in references) {
            if (Geo.distance(r.point, a.position) > closeNauticalMiles * Aviation.metresPerNauticalMile) continue
            val alt = a.altitudeM ?: return true
            if (alt - (r.groundM ?: 0.0) < lowAltitudeFeet * DistanceUnit.metresPerFoot) return true
        }
        return false
    }

    // Aircraft

    /** The map's icon for an ADS-B emitter type. */
    enum class AircraftKind {
        airliner, light, helicopter, glider, balloon, uav, other;

        val title: String
            get() = when (this) {
                airliner -> "Airliner"
                light -> "Light aircraft"
                helicopter -> "Helicopter"
                glider -> "Glider"
                balloon -> "Balloon"
                uav -> "Drone (UAV)"
                other -> "Aircraft"
            }

        /** Balloons and the generic symbol aren't turned to the heading. */
        val rotates: Boolean get() = this != balloon && this != other

        companion object {
            operator fun invoke(emitterType: Int): AircraftKind = when (emitterType) {
                2, 3, 4, 5, 6 -> airliner // small, large, high-vortex, heavy, high-performance
                1, 12 -> light // light, ultralight
                7 -> helicopter // rotorcraft
                9 -> glider
                10 -> balloon // lighter than air
                14 -> uav
                else -> other // unknown, parachute, space, ground vehicles, obstacles
            }
        }
    }

    /** An aircraft from ADSB_VEHICLE, with what is valid of it. */
    data class Aircraft(
        val icaoAddress: Long,
        val position: GeoPoint,
        val altitudeM: Double?,
        /** 0 pressure (QNH), 1 geometric. */
        val altitudeType: Int,
        val headingDeg: Double?,
        val speedMs: Double?,
        val climbMs: Double?,
        val callsign: String?,
        val squawk: Int?,
        val emitterType: Int,
        val tslcS: Int,
        /** The drone (system id) whose receiver heard it. */
        val viaSysid: Int,
        val lastSeen: Double,
    ) {
        val icaoHex: String get() = icaoAddress.toString(16).uppercase().padStart(6, '0')
        val kind: AircraftKind get() = AircraftKind(emitterType)

        /** The callsign, or the ICAO address in hex. */
        val label: String get() = callsign ?: icaoHex

        fun isStale(now: Double): Boolean = now - lastSeen > aircraftStaleAfter

        /** "9,000 ft" (to the nearest 100 ft), null if unknown. */
        val altitudeText: String? get() = altitudeM?.let { feet(it) }

        /** "250 kt", null if unknown. */
        val speedText: String? get() = speedMs?.let { "${(it / 0.514444).rounded().toInt()} kt" }

        /** "↑", "↓" when climbing or descending faster than 100 ft/min, else null. */
        val climbArrow: String?
            get() {
                val c = climbMs ?: return null
                if (abs(c) < 0.508) return null
                return if (c > 0) "↑" else "↓"
            }
    }

    /** A speed in the app's distance unit: "35 km/h", "22 mph", "19 kt". */
    fun speed(ms: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.metric -> "${(ms * 3.6).rounded().toInt()} km/h"
        DistanceUnit.imperial -> "${(ms * 3600 / DistanceUnit.metresPerMile).rounded().toInt()} mph"
        DistanceUnit.nautical -> "${(ms * 3600 / DistanceUnit.metresPerNauticalMile).rounded().toInt()} kt"
    }

    /** A climb (up positive) in the app's height unit: "+0.5 m/s", "−1.2 m/s", "0.0 m/s"; "+100 ft/min" (to 10 ft/min). */
    fun climb(ms: Double, unit: HeightUnit): String {
        val v: Double
        val text: String
        when (unit) {
            HeightUnit.metres -> {
                v = (ms * 10).rounded() / 10
                text = stringFormat("%.1f", abs(v)) + " m/s"
            }
            HeightUnit.feet -> {
                v = (ms / DistanceUnit.metresPerFoot * 60 / 10).rounded() * 10
                text = "${grouped(abs(v).toInt())} ft/min"
            }
        }
        return (if (v > 0) "+" else if (v < 0) "−" else "") + text
    }

    /** "9,000 ft": metres in feet, to the nearest 100 ft (to the nearest 10 below 1,000 ft). */
    fun feet(metres: Double): String {
        val ft = metres / DistanceUnit.metresPerFoot
        val step = if (abs(ft) < 1000) 10.0 else 100.0
        return grouped(((ft / step).rounded() * step).toInt()) + " ft"
    }

    /** "1,200" (thousands separated by commas, whatever the locale, as on the iPhone). */
    fun grouped(v: Int): String {
        val digits = abs(v).toString()
        val out = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return (if (v < 0) "-" else "") + out
    }

    /** The "≈ above ground" estimate: height above sea level less the elevation data's ground under it. A little
     *  below ground (GPS and elevation data disagree by up to `groundSlackM`) reads as 0; further below, the two
     *  clearly disagree (a wrong altitude datum, coarse elevation data) and nothing is shown. Null without ground. */
    fun aboveGroundM(altMslM: Double, groundM: Double?): Double? {
        val g = groundM ?: return null
        val h = altMslM - g
        if (!h.isFinite() || h < -groundSlackM) return null
        return max(0.0, h)
    }

    const val groundSlackM = 20.0

    // Drones

    data class Drone(
        val sysid: Int,
        /** The colour slot, in the order drones were first heard (0, 1, 2 …). */
        val colourIndex: Int,
        val heartbeat: MAVLink.Heartbeat? = null,
        val position: MAVLink.GlobalPositionInt? = null,
        val positionAt: Double? = null,
        val vfr: MAVLink.VfrHud? = null,
        val batteryVoltageV: Double? = null,
        val batteryRemainingPct: Int? = null,
        val gps: MAVLink.GpsRawInt? = null,
        val home: MAVLink.HomePosition? = null,
        val firstSeen: Double,
        val lastSeen: Double,
    ) {
        /** "Drone 1" (the MAVLink system id). */
        val label: String get() = "Drone $sysid"
        val point: GeoPoint? get() = position?.let { GeoPoint(it.latDeg, it.lonDeg) }
        val homePoint: GeoPoint? get() = home?.let { GeoPoint(it.latDeg, it.lonDeg) }

        /** Grey: no position, or none for `droneStaleAfter`. */
        fun isStale(now: Double): Boolean {
            val t = positionAt ?: return true
            return now - t > droneStaleAfter
        }

        /** Heading: GLOBAL_POSITION_INT's, else VFR_HUD's. */
        val headingDeg: Double? get() = position?.headingDeg ?: vfr?.headingDeg?.toDouble()

        /** Ground speed: VFR_HUD's, else from GLOBAL_POSITION_INT's velocity. */
        val groundSpeedMs: Double? get() = vfr?.groundspeedMs ?: position?.let { hypot(it.vxMs, it.vyMs) }

        /** Climb (up positive): VFR_HUD's, else GLOBAL_POSITION_INT's. */
        val climbMs: Double? get() = vfr?.climbMs ?: position?.let { -it.vzMs }

        /** The ground's height where it took off (its height above sea level less its height above take-off). */
        val takeOffGroundM: Double? get() = position?.let { it.altMslM - it.relativeAltM } ?: home?.altMslM

        /** "No fix", "2D fix", "3D fix", "DGPS", "RTK float", "RTK fixed". */
        val fixText: String?
            get() {
                val g = gps ?: return null
                return when (g.fixType) {
                    0, 1 -> "No fix"
                    2 -> "2D fix"
                    3 -> "3D fix"
                    4 -> "DGPS"
                    5 -> "RTK float"
                    6 -> "RTK fixed"
                    7 -> "Static"
                    8 -> "PPP"
                    else -> "Fix ${g.fixType}"
                }
            }
    }

    /** What one datagram did (for the status line and tests). */
    data class Received(val frames: Int = 0, val droneMessages: Int = 0, val aircraftMessages: Int = 0)

    /** The drones (by system id) and aircraft (by ICAO address) heard, kept as the rules say. Not thread-safe: use it
     *  on one thread. */
    class Traffic {
        private val _drones = HashMap<Int, Drone>()
        private val _aircraft = HashMap<Long, Aircraft>()
        val drones: Map<Int, Drone> get() = _drones
        val aircraft: Map<Long, Aircraft> get() = _aircraft

        /** When the last datagram with a known frame came. */
        var lastReceived: Double? = null
            private set

        /** System ids that sent a ground station's heartbeat: never drones. */
        private val _groundStations = HashSet<Int>()
        val groundStations: Set<Int> get() = _groundStations
        private var nextColour = 0

        /** Every frame in a datagram. */
        fun receive(datagram: ByteArray, now: Double): Received = receive(MAVLink.decode(datagram), now)

        /** The frames of one datagram, already decoded (`MAVLink.decode`, which may run on another thread). */
        fun receive(frames: List<MAVLink.Frame>, now: Double): Received {
            var r = Received()
            for (f in frames) {
                r = r.copy(frames = r.frames + 1)
                when (apply(f, now)) {
                    Applied.drone -> r = r.copy(droneMessages = r.droneMessages + 1)
                    Applied.aircraft -> r = r.copy(aircraftMessages = r.aircraftMessages + 1)
                    Applied.ignored -> {}
                }
            }
            if (r.frames > 0) lastReceived = now
            return r
        }

        enum class Applied { drone, aircraft, ignored }

        /** One frame. */
        fun apply(f: MAVLink.Frame, now: Double): Applied {
            val message = f.message ?: return Applied.ignored
            if (message is MAVLink.Heartbeat && !message.isAutopilot) {
                // A ground station's own heartbeat keeps its system id out; any other component's (a camera, gimbal,
                // ADS-B receiver or companion computer, often on the drone's own system id) is ignored: it neither
                // makes a drone nor changes one's armed state.
                if (isGroundStation(f, message) && _drones[f.sysid] == null) _groundStations.add(f.sysid)
                return Applied.ignored
            }
            if (message is MAVLink.AdsbVehicle) {
                return if (applyAircraft(message, f.sysid, now)) Applied.aircraft else Applied.ignored
            }
            if (f.sysid in _groundStations && _drones[f.sysid] == null) return Applied.ignored
            var d = _drones[f.sysid] ?: Drone(sysid = f.sysid, colourIndex = nextColour++, firstSeen = now, lastSeen = now)
            d = d.copy(lastSeen = now)
            d = when (message) {
                is MAVLink.Heartbeat -> d.copy(heartbeat = message)
                is MAVLink.GlobalPositionInt -> d.copy(position = message, positionAt = now)
                is MAVLink.VfrHud -> d.copy(vfr = message)
                is MAVLink.SysStatus -> d.copy(
                    batteryVoltageV = message.batteryVoltageV ?: d.batteryVoltageV,
                    batteryRemainingPct = message.batteryRemainingPct ?: d.batteryRemainingPct,
                )
                is MAVLink.BatteryStatus -> d.copy(batteryRemainingPct = message.batteryRemainingPct ?: d.batteryRemainingPct)
                is MAVLink.GpsRawInt -> d.copy(gps = message)
                is MAVLink.HomePosition -> d.copy(home = message)
                is MAVLink.AdsbVehicle -> d
            }
            _drones[f.sysid] = d
            return Applied.drone
        }

        /** A ground station's heartbeat (MAV_TYPE_GCS, or MAV_AUTOPILOT_INVALID from Mission Planner's component
         *  190 or the usual GCS system id 255), as opposed to a peripheral's on the drone. */
        private fun isGroundStation(f: MAVLink.Frame, h: MAVLink.Heartbeat): Boolean =
            h.type == 6 || (h.autopilot == 8 && (f.compid == 190 || f.sysid == 255))

        private fun applyAircraft(m: MAVLink.AdsbVehicle, sysid: Int, now: Double): Boolean {
            val v = m.valid
            var a = _aircraft[m.icaoAddress]
            if (v.coords) {
                val p = GeoPoint(m.latDeg, m.lonDeg)
                if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return false
                a = (a ?: Aircraft(
                    icaoAddress = m.icaoAddress, position = p, altitudeM = null, altitudeType = m.altitudeType,
                    headingDeg = null, speedMs = null, climbMs = null, callsign = null, squawk = null,
                    emitterType = m.emitterType, tslcS = m.tslcS, viaSysid = sysid, lastSeen = now,
                )).copy(position = p, lastSeen = now)
            }
            // Without a position yet there is nothing to show.
            var b = a ?: return false
            if (v.altitude) b = b.copy(altitudeM = m.altitudeM, altitudeType = m.altitudeType)
            if (v.heading) b = b.copy(headingDeg = m.headingDeg)
            if (v.velocity) b = b.copy(speedMs = m.horVelocityMs)
            if (v.verticalVelocity) b = b.copy(climbMs = m.verVelocityMs)
            if (v.callsign && m.callsign != null) b = b.copy(callsign = m.callsign)
            if (v.squawk) b = b.copy(squawk = m.squawk)
            b = b.copy(emitterType = m.emitterType, tslcS = m.tslcS, viaSysid = sysid)
            _aircraft[m.icaoAddress] = b
            return true
        }

        /** Removes drones and aircraft gone too long; true if any went. */
        fun prune(now: Double): Boolean {
            val d0 = _drones.size
            val a0 = _aircraft.size
            _drones.values.removeAll { now - (it.positionAt ?: it.lastSeen) > droneRemoveAfter }
            _aircraft.values.removeAll { now - it.lastSeen > aircraftRemoveAfter }
            return _drones.size != d0 || _aircraft.size != a0
        }

        /** Forgets everything (the link was switched off). */
        fun clear() {
            _drones.clear()
            _aircraft.clear()
            lastReceived = null
            _groundStations.clear()
            nextColour = 0
        }

        /** The status line while listening on `port`. */
        fun status(port: Int, now: Double): Status {
            val last = lastReceived ?: return Status.Waiting(port)
            val quiet = now - last
            if (quiet > silentAfter) return Status.Silent(quiet.toInt())
            return Status.Receiving(_drones.size, _aircraft.size)
        }

        /** Drones in system-id order. */
        val sortedDrones: List<Drone> get() = _drones.values.sortedBy { it.sysid }
    }

    // Demo replay

    /** A recorded flight (docs/mavlink/demo_flight.json) for the walkthroughs: frames at time offsets, fed through
     *  the same decoder as the network's datagrams, round and round. */
    class Replay private constructor(val duration: Double, val frames: List<Pair<Double, ByteArray>>) {
        /** The frames due after `from` up to and including `to` (seconds since the replay began), looping. Start
         *  with `from` below 0 to have those at 0. */
        fun frames(from: Double, to: Double): List<ByteArray> {
            if (!(to > from) || duration <= 0) return emptyList()
            val out = ArrayList<ByteArray>()
            var lap = max(0.0, floor(from / duration))
            while (lap * duration <= to) {
                for ((ft, bytes) in frames) {
                    val t = lap * duration + ft
                    if (t > from && t <= to) out.add(bytes)
                }
                lap += 1
            }
            return out
        }

        companion object {
            /** The recording, or null when it can't be read. */
            fun parse(json: String): Replay? {
                val root = try {
                    Json.parseToJsonElement(json) as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: return null
                val list = try {
                    root["frames"]?.jsonArray
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (list.isNullOrEmpty()) return null
                val frames = ArrayList<Pair<Double, ByteArray>>()
                for (f in list) {
                    val o = f as? JsonObject ?: return null
                    val t = (o["t"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
                    val hex = (o["hex"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    frames.add(t to (bytes(hex) ?: return null))
                }
                frames.sortBy { it.first }
                val last = frames.last().first
                val stated = (root["durationS"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: last
                return Replay(max(stated, last) + 1, frames)
            }
        }
    }

    /** Bytes from hex ("fd09…"), null if it isn't hex. */
    fun bytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        fun nibble(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }
        val out = ByteArray(hex.length / 2)
        for (k in out.indices) {
            val hi = nibble(hex[2 * k]) ?: return null
            val lo = nibble(hex[2 * k + 1]) ?: return null
            out[k] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
