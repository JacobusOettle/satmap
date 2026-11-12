package com.jacobusoettle.satmap.core

/** The MAVLink messages Drone link reads (docs/mavlink, android/docs/spec/drone-link.md), as a ground station forwards
 *  them over UDP: MAVLink 1 and 2 frames, several to a datagram, checked with their CRC (with each message's
 *  CRC_EXTRA). Signed MAVLink 2 frames are read without checking the signature; unknown messages are skipped. The same
 *  names and behaviour as the iPhone's `MAVLink` (SatMap/Core/MAVLink.swift); the rules and the expected values are in
 *  Tests/SatMapCoreTests/Fixtures/mavlink/cases.json, which both cores' tests read. */
object MAVLink {
    /** A message the decoder knows: its CRC_EXTRA and its payload length with every field (MAVLink 2 extensions too). */
    data class MessageInfo(val name: String, val crcExtra: Int, val v1Length: Int, val v2Length: Int)

    const val heartbeat = 0
    const val sysStatus = 1
    const val gpsRawInt = 24
    const val globalPositionInt = 33
    const val vfrHud = 74
    const val batteryStatus = 147
    const val homePosition = 242
    const val adsbVehicle = 246

    /** Message id → what the decoder needs (common.xml). */
    val messages: Map<Int, MessageInfo> = mapOf(
        heartbeat to MessageInfo("HEARTBEAT", 50, 9, 9),
        sysStatus to MessageInfo("SYS_STATUS", 124, 31, 43),
        gpsRawInt to MessageInfo("GPS_RAW_INT", 24, 30, 52),
        globalPositionInt to MessageInfo("GLOBAL_POSITION_INT", 104, 28, 28),
        vfrHud to MessageInfo("VFR_HUD", 20, 20, 20),
        batteryStatus to MessageInfo("BATTERY_STATUS", 154, 36, 54),
        homePosition to MessageInfo("HOME_POSITION", 104, 52, 60),
        adsbVehicle to MessageInfo("ADSB_VEHICLE", 184, 38, 38),
    )

    const val v1Magic = 0xFE
    const val v2Magic = 0xFD

    /** MAVLink 2's incompat flag for a signed frame (13 signature bytes after the CRC). */
    const val incompatSigned = 0x01
    const val signatureLength = 13

    /** One frame read from a datagram: who sent it and its payload, zero-extended to the message's full length. */
    class Frame(
        val version: Int,
        val signed: Boolean,
        val sysid: Int,
        val compid: Int,
        val msgId: Int,
        val payload: ByteArray,
    ) {
        /** The payload's values, read for the message it is. */
        val message: Message? by lazy { Message(msgId, payload) }
    }

    /** X.25 CRC (CRC-16/MCRF4XX), as MAVLink uses it: `count` bytes of `data` from `from`, starting at `start`. */
    fun crc(data: ByteArray, from: Int = 0, count: Int = data.size - from, start: Int = 0xFFFF): Int {
        var c = start
        for (i in from until from + count) c = crcByte(c, data[i].toInt() and 0xFF)
        return c
    }

    private fun crcByte(crc: Int, b: Int): Int {
        var tmp = b xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    /** Every frame of a known message with a good CRC in one UDP datagram, in order. Garbage, unknown messages and
     *  frames with a bad CRC or an unknown incompat flag are stepped over one byte at a time (the next start byte may be
     *  inside them); a frame that runs past the end is dropped. Shorter MAVLink 2 payloads (trailing zeros trimmed)
     *  are zero-extended. */
    fun decode(datagram: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        val d = datagram
        val n = d.size
        var i = 0
        fun u8(at: Int) = d[at].toInt() and 0xFF
        while (i < n) {
            val version: Int
            val signed: Boolean
            val plen: Int
            val msgId: Int
            val headerEnd: Int
            val total: Int
            when (u8(i)) {
                v1Magic -> {
                    if (i + 8 > n) break
                    plen = u8(i + 1)
                    total = 6 + plen + 2
                    if (i + total > n) {
                        i++
                        continue
                    }
                    msgId = u8(i + 5)
                    version = 1
                    signed = false
                    headerEnd = i + 6
                }
                v2Magic -> {
                    if (i + 12 > n) break
                    plen = u8(i + 1)
                    val incompat = u8(i + 2)
                    if (incompat and incompatSigned.inv() != 0) {
                        i++
                        continue
                    }
                    signed = incompat and incompatSigned != 0
                    total = 10 + plen + 2 + if (signed) signatureLength else 0
                    if (i + total > n) {
                        i++
                        continue
                    }
                    msgId = u8(i + 7) or (u8(i + 8) shl 8) or (u8(i + 9) shl 16)
                    version = 2
                    headerEnd = i + 10
                }
                else -> {
                    i++
                    continue
                }
            }
            // Unknown: no CRC_EXTRA to check it with, so resync one byte on.
            val info = messages[msgId]
            if (info == null) {
                i++
                continue
            }
            val payloadEnd = headerEnd + plen
            val c = crcByte(crc(d, i + 1, payloadEnd - i - 1), info.crcExtra)
            if (c != (u8(payloadEnd) or (u8(payloadEnd + 1) shl 8)) || plen > info.v2Length) {
                i++
                continue
            }
            val payload = ByteArray(info.v2Length)
            System.arraycopy(d, headerEnd, payload, 0, plen)
            // (sysid and compid sit just before the message id in both versions)
            val sysid = if (version == 1) u8(i + 3) else u8(i + 5)
            val compid = if (version == 1) u8(i + 4) else u8(i + 6)
            out.add(Frame(version, signed, sysid, compid, msgId, payload))
            i += total
        }
        return out
    }

    // Messages

    sealed interface Message {
        companion object {
            /** A payload's values for its message (null for one the decoder doesn't read). */
            operator fun invoke(msgId: Int, payload: ByteArray): Message? {
                if ((messages[msgId]?.v2Length ?: return null) > payload.size) return null
                val p = Reader(payload)
                return when (msgId) {
                    heartbeat -> Heartbeat(
                        type = p.u8(4), autopilot = p.u8(5), baseMode = p.u8(6), customMode = p.u32(0), systemStatus = p.u8(7),
                    )
                    sysStatus -> SysStatus(
                        batteryVoltageV = p.u16(14).takeIf { it != 0xFFFF }?.let { it / 1000.0 },
                        batteryCurrentA = p.i16(16).takeIf { it != -1 }?.let { it / 100.0 },
                        batteryRemainingPct = p.i8(30).takeIf { it != -1 },
                    )
                    gpsRawInt -> GpsRawInt(
                        fixType = p.u8(28), satellitesVisible = p.u8(29).takeIf { it != 255 },
                        latDeg = p.i32(8) / 1e7, lonDeg = p.i32(12) / 1e7, altMslM = p.i32(16) / 1000.0,
                    )
                    globalPositionInt -> GlobalPositionInt(
                        timeBootMs = p.u32(0), latDeg = p.i32(4) / 1e7, lonDeg = p.i32(8) / 1e7, altMslM = p.i32(12) / 1000.0,
                        relativeAltM = p.i32(16) / 1000.0, vxMs = p.i16(20) / 100.0, vyMs = p.i16(22) / 100.0,
                        vzMs = p.i16(24) / 100.0, headingDeg = p.u16(26).takeIf { it != 65535 }?.let { it / 100.0 },
                    )
                    vfrHud -> VfrHud(
                        airspeedMs = p.f32(0), groundspeedMs = p.f32(4), headingDeg = p.i16(16), throttlePct = p.u16(18),
                        altM = p.f32(8), climbMs = p.f32(12),
                    )
                    batteryStatus -> BatteryStatus(id = p.u8(32), batteryRemainingPct = p.i8(35).takeIf { it != -1 })
                    homePosition -> HomePosition(latDeg = p.i32(0) / 1e7, lonDeg = p.i32(4) / 1e7, altMslM = p.i32(8) / 1000.0)
                    adsbVehicle -> AdsbVehicle(
                        icaoAddress = p.u32(0), latDeg = p.i32(4) / 1e7, lonDeg = p.i32(8) / 1e7, altitudeType = p.u8(26),
                        altitudeM = p.i32(12) / 1000.0, headingDeg = p.u16(16) / 100.0, horVelocityMs = p.u16(18) / 100.0,
                        verVelocityMs = p.i16(20) / 100.0, callsign = callsign(payload.copyOfRange(27, 36)),
                        emitterType = p.u8(36), tslcS = p.u8(37), flags = p.u16(22), squawk = p.u16(24),
                    )
                    else -> null
                }
            }

            /** ADSB_VEHICLE's callsign: up to the first NUL, trailing spaces trimmed (non-ASCII bytes as U+FFFD); empty
             *  is null. */
            fun callsign(bytes: ByteArray): String? {
                val used = bytes.takeWhile { it.toInt() != 0 }
                val s = String(CharArray(used.size) { k -> (used[k].toInt() and 0xFF).let { if (it < 0x80) it.toChar() else '�' } })
                return s.trimEnd(' ').ifEmpty { null }
            }
        }
    }

    data class Heartbeat(
        val type: Int,
        val autopilot: Int,
        val baseMode: Int,
        val customMode: Long,
        val systemStatus: Int,
    ) : Message {
        /** MAV_MODE_FLAG_SAFETY_ARMED. */
        val armed: Boolean get() = baseMode and 0x80 != 0

        /** A ground station's own heartbeat (MAV_TYPE_GCS, or MAV_AUTOPILOT_INVALID): never a drone. */
        val isGcs: Boolean get() = type == 6 || autopilot == 8

        /** The vehicle's own flight controller: an autopilot (not MAV_AUTOPILOT_INVALID) on a vehicle type, not a
         *  peripheral (camera, gimbal, ADS-B receiver, onboard computer …) that shares the drone's system id. Only
         *  its heartbeat is the drone's (armed state, type). */
        val isAutopilot: Boolean get() = autopilot != 8 && type !in peripheralTypes

        companion object {
            /** MAV_TYPEs that are never a vehicle's flight controller: antenna tracker (5), GCS (6), onboard
             *  controller (18), gimbal (26), ADS-B (27), camera (30), charging station (31), FLARM (32), servo (33),
             *  Open Drone ID (34), battery (36), parachute (37), log (38), OSD (39), IMU (40), GPS (41), winch (42),
             *  illuminator (44). */
            val peripheralTypes: Set<Int> = setOf(5, 6, 18, 26, 27, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 41, 42, 44)
        }
    }

    data class SysStatus(val batteryVoltageV: Double?, val batteryCurrentA: Double?, val batteryRemainingPct: Int?) : Message

    data class GpsRawInt(
        /** GPS_FIX_TYPE: 0–1 no fix, 2 2D, 3 3D, 4 DGPS, 5 RTK float, 6 RTK fixed … */
        val fixType: Int,
        val satellitesVisible: Int?,
        val latDeg: Double,
        val lonDeg: Double,
        val altMslM: Double,
    ) : Message

    data class GlobalPositionInt(
        val timeBootMs: Long,
        val latDeg: Double,
        val lonDeg: Double,
        val altMslM: Double,
        /** Height above home (take-off). */
        val relativeAltM: Double,
        /** North, east and down, m/s. */
        val vxMs: Double,
        val vyMs: Double,
        val vzMs: Double,
        val headingDeg: Double?,
    ) : Message

    data class VfrHud(
        val airspeedMs: Double,
        val groundspeedMs: Double,
        val headingDeg: Int,
        val throttlePct: Int,
        val altM: Double,
        val climbMs: Double,
    ) : Message

    data class BatteryStatus(val id: Int, val batteryRemainingPct: Int?) : Message

    data class HomePosition(val latDeg: Double, val lonDeg: Double, val altMslM: Double) : Message

    data class AdsbVehicle(
        val icaoAddress: Long,
        val latDeg: Double,
        val lonDeg: Double,
        /** ADSB_ALTITUDE_TYPE: 0 pressure (QNH), 1 geometric (GNSS). */
        val altitudeType: Int,
        val altitudeM: Double,
        val headingDeg: Double,
        val horVelocityMs: Double,
        val verVelocityMs: Double,
        val callsign: String?,
        /** ADSB_EMITTER_TYPE (`DroneLink.AircraftKind` groups them for the map). */
        val emitterType: Int,
        /** Seconds since the last communication. */
        val tslcS: Int,
        val flags: Int,
        val squawk: Int,
    ) : Message {
        data class Valid(
            val coords: Boolean,
            val altitude: Boolean,
            val heading: Boolean,
            val velocity: Boolean,
            val callsign: Boolean,
            val squawk: Boolean,
            val verticalVelocity: Boolean,
        )

        /** "00A1B2". */
        val icaoHex: String get() = icaoAddress.toString(16).uppercase().padStart(6, '0')

        val valid: Valid
            get() = Valid(
                coords = flags and 1 != 0, altitude = flags and 2 != 0, heading = flags and 4 != 0,
                velocity = flags and 8 != 0, callsign = flags and 16 != 0, squawk = flags and 32 != 0,
                verticalVelocity = flags and 128 != 0,
            )
    }

    /** Little-endian fields of a zero-extended payload. */
    private class Reader(val b: ByteArray) {
        fun u8(at: Int): Int = b[at].toInt() and 0xFF
        fun i8(at: Int): Int = b[at].toInt()
        fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
        fun i16(at: Int): Int = u16(at).toShort().toInt()
        fun i32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
        fun u32(at: Int): Long = i32(at).toLong() and 0xFFFFFFFFL
        fun f32(at: Int): Double = Float.fromBits(i32(at)).toDouble()
    }
}
