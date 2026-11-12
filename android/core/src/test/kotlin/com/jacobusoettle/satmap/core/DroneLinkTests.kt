package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Drone link's core (MAVLink.kt, DroneLink.kt) against the shared fixture Tests/SatMapCoreTests/Fixtures/mavlink/
 *  cases.json and the recorded demo flight docs/mavlink/demo_flight.json: the iPhone's DroneLinkTests.swift, test for
 *  test. (The UDP loopback is the app's `DroneLinkModelTest`: the socket is the app's.) */
class DroneLinkTests {
    private val fixture: JsonObject by lazy { Json.parseToJsonElement(fixture("mavlink/cases.json").decodeToString()).jsonObject }
    private val cases get() = fixture.getValue("cases").jsonArray.map { it.jsonObject }
    private val tolerance get() = fixture.getValue("tolerance").jsonPrimitive.doubleOrNull ?: 1e-6

    private fun named(name: String) = cases.first { it.getValue("name").jsonPrimitive.content == name }
    private fun datagram(case: JsonObject) = bytesFromHex(case.getValue("hex").jsonPrimitive.content)
    private fun datagram(name: String) = datagram(named(name))

    private val t0 = 1_800_000_000.0

    // Decoder

    @Test
    fun messageTable() {
        val table = fixture.getValue("messages").jsonObject
        assertEquals(table.keys.map { it.toInt() }.toSet(), MAVLink.messages.keys)
        for ((id, info) in table) {
            val o = info.jsonObject
            val m = MAVLink.messages.getValue(id.toInt())
            assertEquals(o.getValue("name").jsonPrimitive.content, m.name)
            assertEquals(m.name, o.getValue("crcExtra").jsonPrimitive.int, m.crcExtra)
            assertEquals(m.name, o.getValue("v1Length").jsonPrimitive.int, m.v1Length)
            assertEquals(m.name, o.getValue("v2Length").jsonPrimitive.int, m.v2Length)
        }
    }

    @Test
    fun crc() {
        // CRC-16/MCRF4XX's check value.
        assertEquals(0x6F91, MAVLink.crc("123456789".toByteArray()))
    }

    /** A decoded message as the fixture's "decoded" object writes it. */
    private fun decoded(m: MAVLink.Message): Map<String, Any?> = when (m) {
        is MAVLink.Heartbeat -> mapOf(
            "type" to m.type, "autopilot" to m.autopilot, "baseMode" to m.baseMode, "customMode" to m.customMode,
            "systemStatus" to m.systemStatus, "armed" to m.armed, "isGcs" to m.isGcs,
        )
        is MAVLink.SysStatus -> mapOf(
            "batteryVoltageV" to m.batteryVoltageV, "batteryCurrentA" to m.batteryCurrentA,
            "batteryRemainingPct" to m.batteryRemainingPct,
        )
        is MAVLink.GpsRawInt -> mapOf(
            "fixType" to m.fixType, "satellitesVisible" to m.satellitesVisible, "latDeg" to m.latDeg,
            "lonDeg" to m.lonDeg, "altMslM" to m.altMslM,
        )
        is MAVLink.GlobalPositionInt -> mapOf(
            "timeBootMs" to m.timeBootMs, "latDeg" to m.latDeg, "lonDeg" to m.lonDeg, "altMslM" to m.altMslM,
            "relativeAltM" to m.relativeAltM, "vxMs" to m.vxMs, "vyMs" to m.vyMs, "vzMs" to m.vzMs,
            "headingDeg" to m.headingDeg,
        )
        is MAVLink.VfrHud -> mapOf(
            "airspeedMs" to m.airspeedMs, "groundspeedMs" to m.groundspeedMs, "headingDeg" to m.headingDeg,
            "throttlePct" to m.throttlePct, "altM" to m.altM, "climbMs" to m.climbMs,
        )
        is MAVLink.BatteryStatus -> mapOf("id" to m.id, "batteryRemainingPct" to m.batteryRemainingPct)
        is MAVLink.HomePosition -> mapOf("latDeg" to m.latDeg, "lonDeg" to m.lonDeg, "altMslM" to m.altMslM)
        is MAVLink.AdsbVehicle -> mapOf(
            "icaoAddress" to m.icaoAddress, "icaoHex" to m.icaoHex, "latDeg" to m.latDeg, "lonDeg" to m.lonDeg,
            "altitudeType" to m.altitudeType, "altitudeM" to m.altitudeM, "headingDeg" to m.headingDeg,
            "horVelocityMs" to m.horVelocityMs, "verVelocityMs" to m.verVelocityMs, "callsign" to m.callsign,
            "emitterType" to m.emitterType, "tslcS" to m.tslcS, "flags" to m.flags, "squawk" to m.squawk,
            "valid" to mapOf(
                "coords" to m.valid.coords, "altitude" to m.valid.altitude, "heading" to m.valid.heading,
                "velocity" to m.valid.velocity, "callsign" to m.valid.callsign, "squawk" to m.valid.squawk,
                "verticalVelocity" to m.valid.verticalVelocity,
            ),
        )
    }

    private fun assertMatches(expected: JsonElement, actual: Any?, where: String) {
        when {
            expected is JsonNull -> assertNull(where, actual)
            expected is JsonObject -> {
                val map = actual as? Map<*, *> ?: return fail("$where: expected an object, got $actual")
                assertEquals("$where: fields", expected.keys, map.keys)
                for ((k, v) in expected) assertMatches(v, map[k], "$where.$k")
            }
            expected is JsonPrimitive && expected.isString -> assertEquals(where, expected.content, actual)
            expected is JsonPrimitive && expected.booleanOrNull != null -> assertEquals(where, expected.booleanOrNull, actual)
            expected is JsonPrimitive -> {
                val e = expected.doubleOrNull ?: return fail("$where: unreadable $expected")
                val a = (actual as? Number)?.toDouble() ?: return fail("$where: expected $e, got $actual")
                if (abs(e - a) > max(tolerance, abs(e) * 1e-9)) fail("$where: expected $e, got $a")
            }
            else -> fail("$where: unexpected $expected")
        }
    }

    @Test
    fun fixtureCases() {
        assertTrue(cases.size >= 30)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val frames = MAVLink.decode(datagram(c))
            val expect = c.getValue("expect").jsonArray.map { it.jsonObject }
            assertEquals("$name: frames", expect.size, frames.size)
            for ((i, e) in expect.withIndex()) {
                val f = frames[i]
                val where = "$name[$i]"
                assertEquals(where, e.getValue("msgId").jsonPrimitive.int, f.msgId)
                assertEquals(where, e.getValue("name").jsonPrimitive.content, MAVLink.messages.getValue(f.msgId).name)
                assertEquals(where, e.getValue("version").jsonPrimitive.int, f.version)
                assertEquals(where, e.getValue("signed").jsonPrimitive.booleanOrNull, f.signed)
                assertEquals(where, e.getValue("sysid").jsonPrimitive.int, f.sysid)
                assertEquals(where, e.getValue("compid").jsonPrimitive.int, f.compid)
                assertEquals("$where: zero-extended", MAVLink.messages.getValue(f.msgId).v2Length, f.payload.size)
                val m = f.message ?: return fail("$where: no message")
                assertMatches(e.getValue("decoded"), decoded(m), "$where.decoded")
            }
        }
    }

    @Test
    fun truncatedPayloadIsShorterOnTheWire() {
        val c = named("v2-truncated-payload")
        val bytes = datagram(c)
        assertEquals(c.getValue("payloadLength").jsonPrimitive.int, bytes[1].toInt())
        assertTrue(bytes[1] < 28)
        assertEquals(28, MAVLink.decode(bytes).first().payload.size)
    }

    @Test
    fun everyPrefixAndRandomGarbageIsSafe() {
        // A frame cut anywhere decodes nothing past the cut and never crashes; noise never crashes either.
        val hb = datagram("two-frames-one-datagram")
        for (n in 0..hb.size) MAVLink.decode(hb.copyOf(n))
        val rng = Random(7)
        repeat(300) {
            val noise = ByteArray(rng.nextInt(0, 301)) { rng.nextInt(256).toByte() }
            if (noise.isNotEmpty()) noise[0] = if (rng.nextBoolean()) 0xFD.toByte() else 0xFE.toByte()
            MAVLink.decode(noise)
        }
    }

    @Test
    fun callsignTrimming() {
        assertEquals("ZSRVK", MAVLink.Message.callsign("ZSRVK   ".toByteArray() + byteArrayOf(0)))
        assertNull(MAVLink.Message.callsign(byteArrayOf(0x20, 0x20, 0, 0x41)))
        assertEquals("A�", MAVLink.Message.callsign(byteArrayOf(0x41, 0xC3.toByte(), 0)))
    }

    // Traffic

    @Test
    fun groundStationHeartbeatNeverMakesADrone() {
        val traffic = DroneLink.Traffic()
        val c = named("gcs-heartbeat")
        assertEquals(false, c["tracksDrone"]?.jsonPrimitive?.booleanOrNull)
        traffic.receive(datagram(c), t0)
        assertTrue(traffic.drones.isEmpty())
        assertEquals(setOf(255), traffic.groundStations)
        assertNotNull("a GCS heartbeat is still data", traffic.lastReceived)
        // A drone heartbeat does.
        traffic.receive(datagram("v2-heartbeat-armed"), t0)
        assertEquals(true, traffic.drones[1]?.heartbeat?.armed)
    }

    @Test
    fun dronesAreTrackedPerSystemId() {
        val traffic = DroneLink.Traffic()
        traffic.receive(datagram("v2-global-position-int"), t0)
        traffic.receive(datagram("second-drone"), t0)
        assertEquals(listOf(1, 2), traffic.sortedDrones.map { it.sysid })
        assertEquals(listOf(0, 1), traffic.sortedDrones.map { it.colourIndex })
        assertEquals("Drone 1", traffic.drones[1]?.label)
        assertEquals(25.0, traffic.drones[2]?.position?.relativeAltM ?: 0.0, 1e-6)
        assertEquals(DroneLink.Status.Receiving(drones = 2, aircraft = 0), traffic.status(14445, t0))
    }

    @Test
    fun droneTelemetryIsMerged() {
        val traffic = DroneLink.Traffic()
        for (name in listOf(
            "v2-heartbeat-armed", "v2-sys-status", "v2-gps-raw-int", "v2-global-position-int", "v2-vfr-hud",
            "v2-battery-status", "v2-home-position",
        )) {
            traffic.receive(datagram(name), t0)
        }
        val d = traffic.drones.getValue(1)
        assertEquals(16.08, d.batteryVoltageV ?: 0.0, 1e-9)
        assertEquals("BATTERY_STATUS came after SYS_STATUS", 86, d.batteryRemainingPct)
        assertEquals("3D fix", d.fixText)
        assertEquals(17, d.gps?.satellitesVisible)
        assertEquals(9.75, d.groundSpeedMs ?: 0.0, 1e-9)
        assertEquals(0.5, d.climbMs ?: 0.0, 1e-9)
        assertEquals(50.2, d.headingDeg ?: 0.0, 1e-9)
        assertEquals(142.0, d.takeOffGroundM ?: 0.0, 1e-6)
        assertEquals(-33.918292, d.homePoint?.lat ?: 0.0, 1e-7)
        // Unknown battery values don't wipe known ones.
        traffic.receive(datagram("v2-sys-status-unknown-battery"), t0)
        assertEquals(16.08, traffic.drones[1]?.batteryVoltageV ?: 0.0, 1e-9)
    }

    @Test
    fun staleAndRemovalRules() {
        val traffic = DroneLink.Traffic()
        traffic.receive(datagram("v2-global-position-int"), t0)
        traffic.receive(datagram("adsb-saa312"), t0)
        val d = traffic.drones.getValue(1)
        val a = traffic.aircraft.getValue(0x00A1B2)
        assertFalse(d.isStale(t0 + 10))
        assertTrue(d.isStale(t0 + 10.5))
        assertFalse(a.isStale(t0 + 15))
        assertTrue(a.isStale(t0 + 15.5))
        // A heartbeat keeps the drone in the list but doesn't make its position fresh.
        traffic.receive(datagram("v2-heartbeat-armed"), t0 + 30)
        assertTrue(traffic.drones.getValue(1).isStale(t0 + 30))
        assertFalse(traffic.prune(t0 + 60))
        assertTrue(traffic.prune(t0 + 60.5))
        assertNull("gone 60 s after its last position", traffic.drones[1])
        assertNotNull(traffic.aircraft[0x00A1B2])
        traffic.prune(t0 + 120)
        assertNotNull(traffic.aircraft[0x00A1B2])
        traffic.prune(t0 + 120.5)
        assertNull("gone after 120 s", traffic.aircraft[0x00A1B2])
        // A drone with no position at all stays while it keeps talking.
        traffic.receive(datagram("v2-heartbeat-armed"), t0 + 200)
        traffic.prune(t0 + 250)
        assertNotNull(traffic.drones[1])
        assertTrue(traffic.drones.getValue(1).isStale(t0 + 200))
    }

    @Test
    fun aircraft() {
        val traffic = DroneLink.Traffic()
        traffic.receive(datagram("adsb-three-in-one-datagram"), t0)
        assertEquals(setOf(0x00A1B2L, 0x00B3C4L, 0x00C5D6L), traffic.aircraft.keys)
        val saa = traffic.aircraft.getValue(0x00A1B2)
        assertEquals("SAA312", saa.label)
        assertEquals(DroneLink.AircraftKind.airliner, saa.kind)
        assertEquals("9,000 ft", saa.altitudeText)
        assertEquals("250 kt", saa.speedText)
        assertEquals("↓", saa.climbArrow)
        assertEquals(4521, saa.squawk)
        assertEquals(DroneLink.AircraftKind.light, traffic.aircraft[0x00B3C4]?.kind)
        assertNull(traffic.aircraft[0x00B3C4]?.climbArrow)
        assertEquals(DroneLink.AircraftKind.helicopter, traffic.aircraft[0x00C5D6]?.kind)
        // Without callsign or heading: labelled by its ICAO address, no heading.
        traffic.receive(datagram("adsb-no-callsign-no-heading"), t0)
        val anon = traffic.aircraft.getValue(0x00D7E8)
        assertEquals("00D7E8", anon.label)
        assertNull(anon.headingDeg)
        assertNull(anon.climbMs)
        assertEquals("3,500 ft", anon.altitudeText)
        assertTrue("ADS-B reports don't make a drone", traffic.drones.isEmpty())
        assertEquals(DroneLink.Status.Receiving(drones = 0, aircraft = 4), traffic.status(14445, t0))
    }

    @Test
    fun aircraftKinds() {
        assertEquals(DroneLink.AircraftKind.other, DroneLink.AircraftKind(0))
        assertEquals(DroneLink.AircraftKind.airliner, DroneLink.AircraftKind(5))
        assertEquals(DroneLink.AircraftKind.light, DroneLink.AircraftKind(12))
        assertEquals(DroneLink.AircraftKind.glider, DroneLink.AircraftKind(9))
        assertEquals(DroneLink.AircraftKind.balloon, DroneLink.AircraftKind(10))
        assertEquals(DroneLink.AircraftKind.uav, DroneLink.AircraftKind(14))
        assertEquals(DroneLink.AircraftKind.other, DroneLink.AircraftKind(19))
        assertFalse(DroneLink.AircraftKind.balloon.rotates)
    }

    @Test
    fun lowAndClose() {
        val traffic = DroneLink.Traffic()
        traffic.receive(datagram("adsb-three-in-one-datagram"), t0)
        val farm = GeoPoint(-33.918292, 18.921204)
        val refs = listOf(DroneLink.Reference(farm, 142.0))
        // The helicopter (800 ft, 2.9 NM) and the light aircraft (1,500 ft, about 4 NM) are; the airliner isn't.
        assertTrue(DroneLink.isLowAndClose(traffic.aircraft.getValue(0x00C5D6), refs))
        assertTrue(DroneLink.isLowAndClose(traffic.aircraft.getValue(0x00B3C4), refs))
        assertFalse(DroneLink.isLowAndClose(traffic.aircraft.getValue(0x00A1B2), refs))
        assertFalse(DroneLink.isLowAndClose(traffic.aircraft.getValue(0x00C5D6), emptyList()))
        // Far away, nothing is.
        val far = listOf(DroneLink.Reference(GeoPoint(-25.0, 31.0), null))
        assertFalse(DroneLink.isLowAndClose(traffic.aircraft.getValue(0x00C5D6), far))
        // Unknown altitude counts as low.
        val a = traffic.aircraft.getValue(0x00A1B2).copy(altitudeM = null, position = farm)
        assertTrue(DroneLink.isLowAndClose(a, refs))
    }

    @Test
    fun statusTexts() {
        val traffic = DroneLink.Traffic()
        assertEquals("Waiting for MAVLink on port 14445…", traffic.status(14445, t0).text)
        traffic.receive(datagram("v2-global-position-int"), t0)
        assertEquals("Receiving from 1 drone", traffic.status(14445, t0 + 5).text)
        assertEquals("No data for 10 s", traffic.status(14445, t0 + 10.5).text)
        assertEquals("No data for 42 s", traffic.status(14445, t0 + 42.2).text)
        assertEquals("Receiving from 2 drones · 3 aircraft", DroneLink.Status.Receiving(2, 3).text)
        assertEquals("Receiving MAVLink · 1 aircraft", DroneLink.Status.Receiving(0, 1).text)
        assertEquals("Drone link is off", DroneLink.Status.Off.text)
        assertEquals("Can't listen on port 14445: another app may be using it", DroneLink.Status.Failed(14445).text)
        // Garbage alone isn't data.
        val quiet = DroneLink.Traffic()
        quiet.receive(byteArrayOf(1, 2, 3, 0xFE.toByte()), t0)
        assertNull(quiet.lastReceived)
    }

    // Settings and texts

    @Test
    fun ports() {
        assertEquals(14445, DroneLink.port("14445"))
        assertEquals(1024, DroneLink.port(" 1024 "))
        assertEquals(65535, DroneLink.port("65535"))
        assertNull(DroneLink.port("1023"))
        assertNull(DroneLink.port("65536"))
        assertNull(DroneLink.port("14 445"))
        assertNull(DroneLink.port("-1"))
        assertNull(DroneLink.port(""))
        assertNull(DroneLink.port("١٤٤٤٥")) // only ASCII digits
        assertEquals(14445, DroneLink.savedPort(null))
        assertEquals(14445, DroneLink.savedPort(80))
        assertEquals(14550, DroneLink.savedPort(14550))
    }

    @Test
    fun addressesAndInstructions() {
        val list = DroneLink.addresses(
            listOf(
                "lo0" to "127.0.0.1", "pdp_ip0" to "10.12.0.4", "bridge100" to "172.20.10.1", "en0" to "192.168.1.23",
                "utun2" to "10.8.0.2", "en2" to "169.254.3.3", "en0" to "192.168.1.23",
            ),
        )
        assertEquals(listOf("Wi-Fi", "Hotspot"), list.map { it.label })
        assertEquals(listOf("192.168.1.23", "172.20.10.1"), list.map { it.address })
        // Android's names: Wi-Fi, the hotspot (ap0, swlan0), Ethernet; never mobile data (rmnet) or a VPN (tun).
        val android = DroneLink.addresses(
            listOf(
                "rmnet_data0" to "100.71.2.9", "tun0" to "10.8.0.2", "ap0" to "192.168.43.1", "wlan0" to "192.168.1.40",
                "eth0" to "10.0.2.15",
            ),
        )
        assertEquals(listOf("Wi-Fi", "Hotspot", "Network"), android.map { it.label })
        assertEquals(
            "Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name 192.168.1.23:14445, restart QGC",
            DroneLink.qgcInstructions(listOf("192.168.1.23"), 14445),
        )
        assertTrue(DroneLink.qgcInstructions(emptyList(), 14550).contains("<phone IP>:14550"))
        assertFalse(DroneLink.isReachableIPv4("0.0.0.0"))
        assertFalse(DroneLink.isReachableIPv4("224.0.0.1"))
        assertFalse(DroneLink.isReachableIPv4("1.2.3"))
        assertTrue(DroneLink.isReachableIPv4("10.0.0.5"))
    }

    @Test
    fun distancesAndNumbers() {
        val farm = GeoPoint(-33.918292, 18.921204)
        val north = GeoPoint(-33.870, 18.935)
        assertEquals("3.0 NM 013° NNE", DroneLink.rangeAndBearing(farm, north))
        assertEquals("0.0 NM", DroneLink.rangeAndBearing(farm, farm))
        assertEquals("9,000 ft", DroneLink.feet(2743.2))
        assertEquals("800 ft", DroneLink.feet(243.84))
        assertEquals("1,234,567", DroneLink.grouped(1_234_567))
        assertEquals("-1,200", DroneLink.grouped(-1200))
        assertEquals("999", DroneLink.grouped(999))
        assertEquals("35 km/h", DroneLink.speed(9.75, DistanceUnit.metric))
        assertEquals("22 mph", DroneLink.speed(9.75, DistanceUnit.imperial))
        assertEquals("19 kt", DroneLink.speed(9.75, DistanceUnit.nautical))
        assertEquals("+0.5 m/s", DroneLink.climb(0.5, HeightUnit.metres))
        assertEquals("−1.2 m/s", DroneLink.climb(-1.24, HeightUnit.metres))
        assertEquals("0.0 m/s", DroneLink.climb(0.01, HeightUnit.metres))
        assertEquals("+100 ft/min", DroneLink.climb(0.508, HeightUnit.feet))
        assertEquals("−1,200 ft/min", DroneLink.climb(-6.096, HeightUnit.feet))
    }

    @Test
    fun nearestAerodromes() {
        val tsv = listOf(
            "A\tS\tFASH\tStellenbosch Airfield\t-33.9800\t18.8200\t295\t1",
            "A\tH\t\tFarm Helipad\t-33.9190\t18.9220\t500\t",
            "A\tL\tFACT\tCape Town International Airport\t-33.9648\t18.6017\t151\t1",
            "N\tV\tCTV\tCape Town VOR\t-33.9700\t18.6000\t115.70 MHz",
        ).joinToString("\n")
        val index = AviationIndex(tsv)
        val farm = GeoPoint(-33.918292, 18.921204)
        val near = DroneLink.nearestAerodromes(index, farm)
        assertEquals("helipads only when nothing else is near", listOf("FASH", "FACT"), near.map { it.place.code })
        assertEquals(
            listOf("Farm Helipad"),
            DroneLink.nearestAerodromes(index, GeoPoint(-33.9191, 18.9221), count = 1, withinNM = 1.0).map { it.place.name },
        )
    }

    // Demo replay

    private val demoFlight: String
        get() = File(System.getProperty("satmap.mavlinkDemo") ?: error("satmap.mavlinkDemo is not set")).readText()

    @Test
    fun demoReplay() {
        val replay = DroneLink.Replay.parse(demoFlight) ?: return fail("The demo flight can't be read")
        assertEquals(855, replay.frames.size)
        assertEquals(61.0, replay.duration, 1e-9)
        val traffic = DroneLink.Traffic()
        // The first 20 s, a quarter second at a time, as the app feeds it.
        var t = -1.0
        var fed = 0
        while (t < 20) {
            val next = max(0.0, t + 0.25)
            for (f in replay.frames(t, next)) {
                fed++
                traffic.receive(f, t0 + next)
            }
            t = next
        }
        assertEquals(replay.frames.count { it.first <= 20 }, fed)
        val d = traffic.drones.getValue(1)
        assertEquals(true, d.heartbeat?.armed)
        assertEquals(50.0, d.position?.relativeAltM ?: 0.0, 0.01)
        assertNotNull(d.home)
        assertEquals(3, traffic.aircraft.size)
        assertEquals(DroneLink.Status.Receiving(drones = 1, aircraft = 3), traffic.status(14445, t0 + 20))
        // It loops: the frames at 0 come again one lap on.
        assertEquals(replay.frames.count { it.first == 0.0 }, replay.frames(60.9, 61.0).size)
        assertTrue(replay.frames(5.0, 5.0).isEmpty())
        assertNull(DroneLink.Replay.parse("{}"))
        assertNull(DroneLink.Replay.parse("not json"))
        assertNull(DroneLink.bytes("abc"))
        assertNull(DroneLink.bytes("zz"))
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xFE.toByte()), DroneLink.bytes("FdfE"))
    }
}
