#!/usr/bin/env python3
"""Generate the shared MAVLink test fixture and demo flight for the Drone link feature.

Outputs (paths relative to the repo root):
  Tests/SatMapCoreTests/Fixtures/mavlink/cases.json  - decoder test cases (iPhone + Android)
  docs/mavlink/demo_flight.json                      - recorded demo flight for the replay hook

Every frame is encoded with the official pymavlink library (common dialect):
  v2 frames: pymavlink.dialects.v20.common (zero-trimmed payloads, as real senders do)
  v1 frames: pymavlink.dialects.v10.common (no extension fields, as real v1 senders do)
and every datagram is decoded again with pymavlink and with the small independent reference
decoder in this file; the script fails if they disagree.

Usage:
  python3 -m venv /tmp/mavvenv && /tmp/mavvenv/bin/pip install pymavlink
  /tmp/mavvenv/bin/python docs/mavlink/make_fixtures.py

The output is deterministic (fixed sequence numbers, signing key and timestamps).
"""
import json
import math
import os
import struct
import sys

import pymavlink
from pymavlink.dialects.v10 import common as v1
from pymavlink.dialects.v20 import common as v2

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CASES_PATH = os.path.join(ROOT, "Tests", "SatMapCoreTests", "Fixtures", "mavlink", "cases.json")
DEMO_PATH = os.path.join(ROOT, "docs", "mavlink", "demo_flight.json")

# Demo farm anchor (SatMap/Core/GroupDemo.swift), near Stellenbosch. HOME_ALT is the ground there in
# SatMap's elevation data (about 431 m), so the demo's "≈ above ground" reads about the height above take-off.
HOME_LAT, HOME_LON, HOME_ALT = -33.918292, 18.921204, 431.0

# Messages the apps decode: id -> (name, CRC_EXTRA, v1 payload length, v2 full payload length)
MESSAGES = {}
for _name in ["HEARTBEAT", "SYS_STATUS", "GPS_RAW_INT", "GLOBAL_POSITION_INT", "VFR_HUD",
              "BATTERY_STATUS", "HOME_POSITION", "ADSB_VEHICLE"]:
    _id = getattr(v2, "MAVLINK_MSG_ID_" + _name)
    assert v1.mavlink_map[_id].crc_extra == v2.mavlink_map[_id].crc_extra
    MESSAGES[_id] = (_name, v2.mavlink_map[_id].crc_extra,
                     v1.mavlink_map[_id].unpacker.size, v2.mavlink_map[_id].unpacker.size)

SIGNING_KEY = bytes(range(32))


# ---------------------------------------------------------------- encoding helpers

class Link:
    """One pymavlink sender per (version, sysid, compid), with its own sequence counter."""

    def __init__(self, version, sysid, compid, signed=False):
        self.mod = v2 if version == 2 else v1
        self.mav = self.mod.MAVLink(None, srcSystem=sysid, srcComponent=compid)
        if signed:
            self.mav.signing.secret_key = SIGNING_KEY
            self.mav.signing.link_id = 0
            self.mav.signing.timestamp = 0x12345678_9A  # 10 us units since 1 Jan 2015
            self.mav.signing.sign_outgoing = True

    def frame(self, name, **kw):
        cls = getattr(self.mod, "MAVLink_%s_message" % name.lower())
        args = []
        for f in cls.fieldnames:
            if f in kw:
                args.append(kw.pop(f))
                continue
            i = cls.fieldnames.index(f)
            alen = cls.array_lengths[cls.ordered_fieldnames.index(f)]
            if cls.fieldtypes[i] == "char":
                args.append(b"")
            elif alen:
                args.append([0] * alen)
            else:
                args.append(0)
        assert not kw, "unknown fields %s" % kw
        buf = cls(*args).pack(self.mav)
        self.mav.seq = (self.mav.seq + 1) % 256
        return bytes(buf)


def x25(data):
    crc = 0xFFFF
    for b in data:
        tmp = b ^ (crc & 0xFF)
        tmp = (tmp ^ (tmp << 4)) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


def raw_v2_frame(msgid, payload, sysid=1, compid=1, seq=0, crc_extra=0):
    header = bytes([0xFD, len(payload), 0, 0, seq, sysid, compid,
                    msgid & 0xFF, (msgid >> 8) & 0xFF, (msgid >> 16) & 0xFF])
    crc = x25(header[1:] + payload + bytes([crc_extra]))
    return header + payload + struct.pack("<H", crc)


# ---------------------------------------------------------------- reference decoder

def ref_decode(datagram):
    """Independent decoder with the behaviour the apps must have. Returns a list of
    (version, signed, sysid, compid, msgid, payload_zero_extended)."""
    out = []
    i, n = 0, len(datagram)
    while i < n:
        stx = datagram[i]
        if stx == 0xFE:
            if i + 8 > n:
                break
            plen = datagram[i + 1]
            total = 6 + plen + 2
            if i + total > n:
                i += 1
                continue
            sysid, compid, msgid = datagram[i + 3], datagram[i + 4], datagram[i + 5]
            payload = datagram[i + 6:i + 6 + plen]
            version, signed = 1, False
            hdr = datagram[i + 1:i + 6]
            crc_at = i + 6 + plen
        elif stx == 0xFD:
            if i + 12 > n:
                break
            plen, incompat = datagram[i + 1], datagram[i + 2]
            if incompat & ~0x01:
                i += 1
                continue
            signed = bool(incompat & 0x01)
            total = 10 + plen + 2 + (13 if signed else 0)
            if i + total > n:
                i += 1
                continue
            sysid, compid = datagram[i + 5], datagram[i + 6]
            msgid = datagram[i + 7] | (datagram[i + 8] << 8) | (datagram[i + 9] << 16)
            payload = datagram[i + 10:i + 10 + plen]
            version = 2
            hdr = datagram[i + 1:i + 10]
            crc_at = i + 10 + plen
        else:
            i += 1
            continue
        if msgid not in MESSAGES:
            i += 1  # unknown: no CRC_EXTRA, cannot validate -> resync one byte on
            continue
        name, crc_extra, v1len, v2len = MESSAGES[msgid]
        crc = struct.unpack("<H", datagram[crc_at:crc_at + 2])[0]
        if crc != x25(hdr + payload + bytes([crc_extra])) or plen > v2len:
            i += 1
            continue
        out.append((version, signed, sysid, compid, msgid, payload + bytes(v2len - plen)))
        i += total
    return out


def ref_raw_fields(msgid, payload):
    cls = v2.mavlink_map[msgid]
    vals = cls.unpacker.unpack(payload)
    fields, idx = {}, 0
    for fname, alen in zip(cls.ordered_fieldnames, cls.array_lengths):
        if alen == 0 or cls.fieldtypes[cls.fieldnames.index(fname)] == "char":
            fields[fname] = vals[idx]
            idx += 1
        else:
            fields[fname] = list(vals[idx:idx + alen])
            idx += alen
    return {f: fields[f] for f in cls.fieldnames}


# ---------------------------------------------------------------- expected decoded values

def r(x, nd=7):
    return round(x, nd)


def nz(v, invalid, f):
    return None if v == invalid else f(v)


def decoded_fields(name, m):
    """SI-friendly decoded values (the contract both apps implement). m: raw MAVLink fields."""
    if name == "HEARTBEAT":
        return {"type": m["type"], "autopilot": m["autopilot"], "baseMode": m["base_mode"],
                "customMode": m["custom_mode"], "systemStatus": m["system_status"],
                "armed": bool(m["base_mode"] & 0x80),
                "isGcs": m["type"] == 6 or m["autopilot"] == 8}
    if name == "SYS_STATUS":
        return {"batteryVoltageV": nz(m["voltage_battery"], 0xFFFF, lambda v: r(v / 1000.0, 3)),
                "batteryCurrentA": nz(m["current_battery"], -1, lambda v: r(v / 100.0, 2)),
                "batteryRemainingPct": nz(m["battery_remaining"], -1, int)}
    if name == "GPS_RAW_INT":
        return {"fixType": m["fix_type"],
                "satellitesVisible": nz(m["satellites_visible"], 255, int),
                "latDeg": r(m["lat"] / 1e7), "lonDeg": r(m["lon"] / 1e7),
                "altMslM": r(m["alt"] / 1000.0, 3)}
    if name == "GLOBAL_POSITION_INT":
        return {"timeBootMs": m["time_boot_ms"],
                "latDeg": r(m["lat"] / 1e7), "lonDeg": r(m["lon"] / 1e7),
                "altMslM": r(m["alt"] / 1000.0, 3), "relativeAltM": r(m["relative_alt"] / 1000.0, 3),
                "vxMs": r(m["vx"] / 100.0, 2), "vyMs": r(m["vy"] / 100.0, 2),
                "vzMs": r(m["vz"] / 100.0, 2),
                "headingDeg": nz(m["hdg"], 65535, lambda v: r(v / 100.0, 2))}
    if name == "VFR_HUD":
        return {"airspeedMs": r(m["airspeed"], 4), "groundspeedMs": r(m["groundspeed"], 4),
                "headingDeg": m["heading"], "throttlePct": m["throttle"],
                "altM": r(m["alt"], 4), "climbMs": r(m["climb"], 4)}
    if name == "BATTERY_STATUS":
        return {"id": m["id"], "batteryRemainingPct": nz(m["battery_remaining"], -1, int)}
    if name == "HOME_POSITION":
        return {"latDeg": r(m["latitude"] / 1e7), "lonDeg": r(m["longitude"] / 1e7),
                "altMslM": r(m["altitude"] / 1000.0, 3)}
    if name == "ADSB_VEHICLE":
        cs = callsign_str(m["callsign"])
        fl = m["flags"]
        return {"icaoAddress": m["ICAO_address"], "icaoHex": "%06X" % m["ICAO_address"],
                "latDeg": r(m["lat"] / 1e7), "lonDeg": r(m["lon"] / 1e7),
                "altitudeType": m["altitude_type"], "altitudeM": r(m["altitude"] / 1000.0, 3),
                "headingDeg": r(m["heading"] / 100.0, 2),
                "horVelocityMs": r(m["hor_velocity"] / 100.0, 2),
                "verVelocityMs": r(m["ver_velocity"] / 100.0, 2),
                "callsign": cs or None, "emitterType": m["emitter_type"], "tslcS": m["tslc"],
                "flags": fl, "squawk": m["squawk"],
                "valid": {"coords": bool(fl & 1), "altitude": bool(fl & 2), "heading": bool(fl & 4),
                          "velocity": bool(fl & 8), "callsign": bool(fl & 16),
                          "squawk": bool(fl & 32), "verticalVelocity": bool(fl & 128)}}
    raise KeyError(name)


def callsign_str(v):
    if isinstance(v, str):
        v = v.encode("latin-1")
    return v.split(b"\x00")[0].decode("ascii", "replace").rstrip(" ")


def jsonable_raw(m):
    out = {}
    for k, v in m.items():
        if isinstance(v, (bytes, str)):
            v = callsign_str(v)
        elif isinstance(v, float):
            v = r(v, 4)
        elif isinstance(v, list):
            v = [r(x, 4) if isinstance(x, float) else x for x in v]
        out[k] = v
    return out


def pymav_decode(datagram):
    """Decode one datagram with pymavlink (robust parsing; no signing key, so signed frames
    are accepted without verification)."""
    mav = v2.MAVLink(None)
    mav.robust_parsing = True
    msgs = mav.parse_buffer(bytearray(datagram)) or []
    return [m for m in msgs if m.get_msgId() in MESSAGES and m.get_type() != "BAD_DATA"]


def expected_for(datagram, pymav_skip=0):
    """Expected decoded messages (reference decoder), cross-checked field by field
    against pymavlink. pymav_skip: leading garbage bytes pymavlink's simpler recovery
    cannot step over (it discards a whole bogus frame); pymavlink checks the rest."""
    ref = ref_decode(datagram)
    pm = pymav_decode(datagram[pymav_skip:])
    assert len(ref) == len(pm), "ref %d vs pymavlink %d messages in %s" % (
        len(ref), len(pm), datagram.hex())
    out = []
    for (version, signed, sysid, compid, msgid, payload), pmsg in zip(ref, pm):
        name = MESSAGES[msgid][0]
        assert pmsg.get_msgId() == msgid
        assert pmsg.get_srcSystem() == sysid and pmsg.get_srcComponent() == compid
        assert bool(pmsg.get_header().incompat_flags & 0x01) == signed
        if signed:  # prove the signature itself is genuine (apps skip it without verifying)
            vm = v2.MAVLink(None)
            vm.signing.secret_key = SIGNING_KEY
            assert [m.get_msgId() for m in vm.parse_buffer(bytearray(datagram)) or []] == [msgid]
            assert vm.signing.goodsig_count == 1
        raw = ref_raw_fields(msgid, payload)
        for f, a in raw.items():
            b = getattr(pmsg, f)
            if isinstance(a, (bytes, str)):
                a, b = callsign_str(a), callsign_str(b)
            assert a == b, "%s.%s ref %r pymavlink %r" % (name, f, a, b)
        out.append({"msgId": msgid, "name": name, "version": version, "signed": signed,
                    "sysid": sysid, "compid": compid,
                    "raw": jsonable_raw(raw), "decoded": decoded_fields(name, raw)})
    return out


# ---------------------------------------------------------------- message builders

def deg7(x):
    return int(round(x * 1e7))


def heartbeat(link, armed=True, mtype=2, autopilot=3, custom_mode=5, status=4):
    # MAV_MODE_FLAG custom | guided | stabilize | manual input, plus safety-armed (0x80)
    base = 0x01 | 0x08 | 0x10 | 0x40 | (0x80 if armed else 0)
    if mtype == 6:
        base = 0x80 if armed else 0
    return link.frame("HEARTBEAT", type=mtype, autopilot=autopilot, base_mode=base,
                      custom_mode=custom_mode, system_status=status, mavlink_version=3)


def sys_status(link, mv=16080, ca=1250, pct=87):
    sensors = 0x0020FC2F
    return link.frame("SYS_STATUS", onboard_control_sensors_present=sensors,
                      onboard_control_sensors_enabled=sensors,
                      onboard_control_sensors_health=sensors, load=312, voltage_battery=mv,
                      current_battery=ca, battery_remaining=pct)


def gps_raw(link, lat, lon, alt_m, fix=3, sats=17):
    kw = dict(time_usec=812_345_000, fix_type=fix, lat=deg7(lat), lon=deg7(lon),
              alt=int(round(alt_m * 1000)), eph=72, epv=110, vel=850, cog=9000,
              satellites_visible=sats)
    if link.mod is v2:
        kw.update(alt_ellipsoid=int(round((alt_m + 31.2) * 1000)), h_acc=650, v_acc=1100,
                  vel_acc=180)
    return link.frame("GPS_RAW_INT", **kw)


def global_pos(link, t_ms, lat, lon, alt_m, rel_m, vx, vy, vz, hdg):
    return link.frame("GLOBAL_POSITION_INT", time_boot_ms=t_ms, lat=deg7(lat), lon=deg7(lon),
                      alt=int(round(alt_m * 1000)), relative_alt=int(round(rel_m * 1000)),
                      vx=int(round(vx * 100)), vy=int(round(vy * 100)), vz=int(round(vz * 100)),
                      hdg=65535 if hdg is None else int(round(hdg * 100)) % 36000)


def vfr_hud(link, gs, climb, hdg, alt_m, airspeed=None, throttle=46):
    return link.frame("VFR_HUD", airspeed=gs if airspeed is None else airspeed, groundspeed=gs,
                      heading=int(round(hdg)) % 360, throttle=throttle, alt=alt_m, climb=climb)


def battery_status(link, pct=87):
    kw = dict(id=0, battery_function=1, type=3, temperature=3150,
              voltages=[4020, 4020, 4015, 4025] + [65535] * 6, current_battery=1250,
              current_consumed=742, energy_consumed=-1, battery_remaining=pct)
    if link.mod is v2:
        kw.update(time_remaining=1260, charge_state=1)
    return link.frame("BATTERY_STATUS", **kw)


def home_position(link, lat=HOME_LAT, lon=HOME_LON, alt_m=HOME_ALT):
    kw = dict(latitude=deg7(lat), longitude=deg7(lon), altitude=int(round(alt_m * 1000)),
              q=[1.0, 0.0, 0.0, 0.0])
    if link.mod is v2:
        kw.update(time_usec=812_000_000)
    return link.frame("HOME_POSITION", **kw)


def adsb(link, icao, lat, lon, alt_ft, hdg, kt, fpm, callsign, emitter, squawk,
         flags=1 | 2 | 4 | 8 | 16 | 32 | 128, tslc=1, altitude_type=0):
    return link.frame("ADSB_VEHICLE", ICAO_address=icao, lat=deg7(lat), lon=deg7(lon),
                      altitude_type=altitude_type, altitude=int(round(alt_ft * 304.8)),
                      heading=int(round(hdg * 100)) % 36000,
                      hor_velocity=int(round(kt * 51.4444)),
                      ver_velocity=int(round(fpm * 0.508)),
                      callsign=callsign.encode("ascii"), emitter_type=emitter, tslc=tslc,
                      flags=flags, squawk=squawk)


# Three aircraft near the farm at t = 0 of the demo flight (fictional registrations).
AIRCRAFT = [
    # airliner descending towards Cape Town International, about 9 NM north-east
    dict(icao=0x00A1B2, callsign="SAA312", emitter=3, squawk=4521,
         lat=-33.835, lon=19.045, alt_ft=9000, hdg=245, kt=250, fpm=-1200),
    # light aircraft out of Stellenbosch airfield, low, about 4 NM south-west
    dict(icao=0x00B3C4, callsign="ZSRVK", emitter=1, squawk=7000,
         lat=-33.965, lon=18.860, alt_ft=1500, hdg=45, kt=95, fpm=0),
    # helicopter about 2.9 NM north, heading south past the farm
    dict(icao=0x00C5D6, callsign="ZSHMB", emitter=7, squawk=7000,
         lat=-33.870, lon=18.935, alt_ft=800, hdg=190, kt=70, fpm=-100),
]


def aircraft_at(a, t):
    d = a["kt"] * 0.514444 * t  # metres travelled
    lat = a["lat"] + d * math.cos(math.radians(a["hdg"])) / 111_320.0
    lon = a["lon"] + d * math.sin(math.radians(a["hdg"])) / (
        111_320.0 * math.cos(math.radians(a["lat"])))
    return lat, lon, a["alt_ft"] + a["fpm"] * t / 60.0


def adsb_for(link, a, t, **kw):
    lat, lon, alt = aircraft_at(a, t)
    return adsb(link, a["icao"], lat, lon, alt, a["hdg"], a["kt"], a["fpm"], a["callsign"],
                a["emitter"], a["squawk"], **kw)


# ---------------------------------------------------------------- cases

def build_cases():
    cases = []

    def add(name, description, datagram, pymav_skip=0, **extra):
        case = {"name": name, "description": description, "hex": datagram.hex(),
                "expect": expected_for(datagram, pymav_skip)}
        case.update(extra)
        cases.append(case)

    # in-flight sample drone state, about 200 m east of the farm, 50 m up
    lat, lon = HOME_LAT + 0.00035, HOME_LON + 0.0021

    for ver in (2, 1):
        L = Link(ver, 1, 1)
        L.mav.seq = 10 if ver == 2 else 110
        tag = "v%d" % ver
        add(tag + "-heartbeat-armed", "ArduCopter quad heartbeat, armed", heartbeat(L, armed=True))
        add(tag + "-heartbeat-disarmed", "ArduCopter quad heartbeat, disarmed",
            heartbeat(L, armed=False, custom_mode=0, status=3))
        add(tag + "-sys-status", "Battery 16.08 V, 12.5 A, 87 %", sys_status(L))
        add(tag + "-gps-raw-int", "3D fix, 17 satellites", gps_raw(L, lat, lon, HOME_ALT + 50.3))
        add(tag + "-global-position-int", "50.3 m above take-off, flying north-east",
            global_pos(L, 123_456, lat, lon, HOME_ALT + 50.3, 50.3, 6.25, 7.5, -0.5, 50.2))
        add(tag + "-vfr-hud", "Groundspeed 9.75 m/s, climb 0.5 m/s (float32-exact values)",
            vfr_hud(L, 9.75, 0.5, 50, HOME_ALT + 50.25, airspeed=10.5))
        add(tag + "-battery-status", "Battery 86 %", battery_status(L, pct=86))
        add(tag + "-home-position", "Home at the demo farm", home_position(L))
        add(tag + "-adsb-vehicle", "Airliner from a drone-borne ADS-B receiver",
            adsb_for(L, AIRCRAFT[0], 0))

    L = Link(2, 1, 1)
    L.mav.seq = 40
    add("v2-global-position-int-no-heading", "Heading unknown (hdg = 65535 -> null), on the ground",
        global_pos(L, 2_000, HOME_LAT, HOME_LON, HOME_ALT, 0.0, 0.0, 0.0, 0.0, None))
    add("v2-sys-status-unknown-battery", "Voltage 65535, current -1, remaining -1 -> null",
        sys_status(L, mv=0xFFFF, ca=-1, pct=-1))

    tr = global_pos(L, 200_000, lat, lon, HOME_ALT + 30.0, 30.0, 3.0, -4.0, 0.0, 0.0)
    plen = tr[1]
    assert plen < MESSAGES[33][3], "expected a trimmed payload"
    add("v2-truncated-payload",
        "GLOBAL_POSITION_INT with vz = 0 and hdg = 0: the sender trims trailing zero bytes "
        "(payload %d of 28 bytes); the decoder must zero-extend" % plen, tr, payloadLength=plen)

    S = Link(2, 1, 1, signed=True)
    S.mav.seq = 77
    add("v2-signed-heartbeat",
        "Signed MAVLink 2 frame (incompat flag 0x01, 13-byte signature). Accept without verifying.",
        heartbeat(S, armed=True))
    add("v2-signed-global-position-int", "Signed position frame",
        global_pos(S, 300_000, lat, lon, HOME_ALT + 42.0, 42.0, 1.0, 2.0, 0.0, 63.4))

    L = Link(2, 1, 1)
    L.mav.seq = 50
    add("two-frames-one-datagram", "HEARTBEAT then GLOBAL_POSITION_INT in one UDP datagram",
        heartbeat(L) + global_pos(L, 400_000, lat, lon, HOME_ALT + 50.0, 50.0, 0.0, 8.0, 0.0, 90.0))
    add("v1-and-v2-one-datagram", "A v1 HEARTBEAT followed by a v2 VFR_HUD",
        heartbeat(Link(1, 1, 1)) + vfr_hud(L, 8.0, -0.25, 90, HOME_ALT + 50.0))

    add("garbage-before-frame", "Non-MAVLink bytes before a valid frame are skipped",
        bytes.fromhex("0011223344556677") + heartbeat(L))
    add("garbage-false-magic",
        "Garbage with a fake v1 start byte (0xFE, length 3) whose frame would overlap the real "
        "one; its CRC fails, so the decoder must resync one byte on and still find the frame",
        bytes([0x55, 0xFE, 0x03, 0x00, 0x01, 0x01]) + global_pos(
            L, 500_000, lat, lon, HOME_ALT + 50.0, 50.0, 0.0, 0.0, 0.0, 180.0), pymav_skip=6)

    bad = bytearray(heartbeat(L))
    bad[-1] ^= 0x5A
    add("bad-crc", "HEARTBEAT with a corrupted CRC byte: must be rejected", bytes(bad))
    bad2 = bytearray(global_pos(L, 600_000, lat, lon, HOME_ALT + 50.0, 50.0, 0, 0, 0, 10.0))
    bad2[14] ^= 0x01  # flip one payload bit
    add("bad-crc-then-good", "Corrupted payload (rejected), then a valid HEARTBEAT",
        bytes(bad2) + heartbeat(L))

    unknown = raw_v2_frame(42000, bytes([0x10, 0x20, 0x30, 0x40, 0x50, 0x60]), seq=60,
                           crc_extra=17)
    add("unknown-message", "Unknown message id 42000 is ignored", unknown)
    add("unknown-then-known", "Unknown message id 42000 ignored, then a valid HEARTBEAT decoded",
        unknown + heartbeat(L))

    G = Link(2, 255, 190)
    add("gcs-heartbeat", "QGroundControl's own heartbeat (type 6 GCS, autopilot 8 INVALID): "
        "decoded, but must not create a drone",
        heartbeat(G, armed=False, mtype=6, autopilot=8, custom_mode=0, status=4),
        tracksDrone=False)

    hb = heartbeat(L)
    add("incomplete-frame-at-end", "Valid HEARTBEAT, then the first 8 bytes of another frame: "
        "the partial frame is dropped", hb + hb[:8])

    D2 = Link(2, 2, 1)
    add("second-drone", "Position from a second drone (system id 2), tracked separately",
        global_pos(D2, 50_000, HOME_LAT - 0.0012, HOME_LON + 0.0009, HOME_ALT + 25.0, 25.0,
                   -1.5, 0.0, 0.0, 180.0))

    A = Link(2, 1, 1)
    A.mav.seq = 90
    for a in AIRCRAFT:
        kind = {3: "airliner", 1: "light aircraft", 7: "helicopter"}[a["emitter"]]
        add("adsb-%s" % a["callsign"].lower(), "ADS-B %s %s" % (kind, a["callsign"]),
            adsb_for(A, a, 0))
    add("adsb-three-in-one-datagram", "All three aircraft in one datagram (t = 1 s)",
        b"".join(adsb_for(A, a, 1) for a in AIRCRAFT))
    add("adsb-no-callsign-no-heading",
        "Aircraft without callsign or heading (flags = coords|altitude|velocity): label by ICAO hex",
        adsb(A, 0x00D7E8, -33.90, 18.95, 3500, 0, 110, 0, "", 2, 0, flags=1 | 2 | 8, tslc=3))

    return cases


# ---------------------------------------------------------------- demo flight

def demo_flight():
    """60 s flight at the demo farm: GLOBAL_POSITION_INT + VFR_HUD at 4 Hz; HEARTBEAT,
    SYS_STATUS, GPS_RAW_INT and three ADSB_VEHICLE every second; HOME_POSITION every 10 s.
    Disarmed 0-2 s, armed on the ground 2-3 s, climb to 50 m 3-13 s, one clockwise loop
    of radius 70 m (centred 70 m east of home) 13-55 s, descend over home 55-60 s."""
    L = Link(2, 1, 1)
    frames = []
    radius = 70.0
    m_lat = 111_320.0
    m_lon = 111_320.0 * math.cos(math.radians(HOME_LAT))
    t_climb0, t_climb1, t_loop1, t_end = 3.0, 13.0, 55.0, 60.0
    omega = 2 * math.pi / (t_loop1 - t_climb1)
    speed = omega * radius

    def state(t):
        """(north m, east m, relative alt m, vn, ve, vz (down +), heading deg)"""
        if t < t_climb0:
            return 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        if t < t_climb1:
            return 0.0, 0.0, 5.0 * (t - t_climb0), 0.0, 0.0, -5.0, 0.0
        if t < t_loop1:
            # angle of the drone seen from the centre, from east, decreasing = clockwise;
            # starts at pi (home, west of the centre), so the drone sets off north
            a = math.pi - omega * (t - t_climb1)
            e = radius + radius * math.cos(a)
            n = radius * math.sin(a)
            ve = speed * math.sin(a)
            vn = -speed * math.cos(a)
            return n, e, 50.0, vn, ve, 0.0, math.degrees(math.atan2(ve, vn)) % 360
        return 0.0, 0.0, 50.0 - 4.0 * (t - t_loop1), 0.0, 0.0, 4.0, 0.0

    for k in range(int(t_end * 4) + 1):
        t = k / 4.0
        n, e, rel, vn, ve, vz, hdg = state(t)
        lat, lon, alt = HOME_LAT + n / m_lat, HOME_LON + e / m_lon, HOME_ALT + rel
        flying = t >= t_climb0
        if k % 4 == 0:
            sec = k // 4
            frames.append((t, heartbeat(L, armed=t >= 2.0, custom_mode=5 if flying else 0,
                                        status=4 if t >= 2.0 else 3)))
            mv = 16600 - sec * 9 - (650 if flying else 0)
            frames.append((t, sys_status(L, mv=mv, ca=1850 if flying else 120,
                                         pct=100 - sec // 5)))
            frames.append((t, gps_raw(L, lat, lon, alt, fix=3, sats=17 if sec % 20 < 15 else 16)))
            if sec % 10 == 0:
                frames.append((t, home_position(L)))
            for a in AIRCRAFT:
                frames.append((t, adsb_for(L, a, t)))
        frames.append((t, global_pos(L, 812_000 + int(t * 1000), lat, lon, alt, rel,
                                     vn, ve, vz, hdg)))
        frames.append((t, vfr_hud(L, round(math.hypot(vn, ve) * 4) / 4, round(-vz * 4) / 4,
                                  hdg, round(alt * 4) / 4, throttle=46 if flying else 0)))
    for t, f in frames:
        assert len(expected_for(f)) == 1, (t, f.hex())
    return {
        "description": "60 s demo flight at the SatMap demo farm (Stellenbosch) with three ADS-B "
                       "aircraft, for the Drone link demo replay on both apps. Feed each frame "
                       "through the MAVLink decoder at its time offset t (seconds).",
        "generator": "docs/mavlink/make_fixtures.py (pymavlink %s, common dialect, MAVLink 2)"
                     % pymavlink.__version__,
        "home": {"latDeg": HOME_LAT, "lonDeg": HOME_LON, "altMslM": HOME_ALT},
        "durationS": t_end,
        "drone": {"sysid": 1, "compid": 1, "type": 2, "autopilot": 3},
        "aircraft": [{"icaoHex": "%06X" % a["icao"], "callsign": a["callsign"],
                      "emitterType": a["emitter"]} for a in AIRCRAFT],
        "frames": [{"t": t, "hex": f.hex()} for t, f in frames],
    }


def main():
    cases = build_cases()
    fixture = {
        "description": "Shared MAVLink decoder fixture for SatMap's Drone link (iPhone SatMap/Core "
                       "and android/core). Each case is ONE UDP datagram (hex). 'expect' lists the "
                       "messages the decoder must emit, in order; [] means nothing is emitted. "
                       "'raw' holds MAVLink field values as on the wire (names as in common.xml); "
                       "'decoded' holds the SI-friendly values the apps use (degrees, metres, m/s, "
                       "volts, amperes, percent; null = not available).",
        "generator": "docs/mavlink/make_fixtures.py (pymavlink %s, common dialect)"
                     % pymavlink.__version__,
        "tolerance": 1e-6,
        "rules": [
            "Start bytes: 0xFE = MAVLink 1 (6-byte header), 0xFD = MAVLink 2 (10-byte header).",
            "CRC is X.25 (CRC-16/MCRF4XX, init 0xFFFF) over the header without the start byte, "
            "the payload as received, then the message's CRC_EXTRA byte; stored little-endian.",
            "MAVLink 2 incompat flag 0x01 = signed: 13 signature bytes follow the CRC; skip them, "
            "do not verify. Any other incompat bit: reject the frame.",
            "MAVLink 2 payloads may be shorter than the full length (trailing zeros trimmed): "
            "zero-extend before reading fields. MAVLink 1 payloads have no extension fields.",
            "Payload fields are little-endian, in wire order (sorted by type size, extensions last).",
            "Unknown message id or CRC mismatch: drop the start byte and resync one byte on.",
            "A frame that runs past the end of the datagram is dropped.",
            "HEARTBEAT with type 6 (GCS) or autopilot 8 (INVALID) never creates a drone "
            "(tracksDrone = false).",
            "Sentinels -> null: GLOBAL_POSITION_INT.hdg 65535; SYS_STATUS voltage 65535, current "
            "-1, remaining -1; BATTERY_STATUS remaining -1; GPS_RAW_INT satellites 255.",
            "ADSB_VEHICLE callsign: up to the first NUL, trailing spaces trimmed; empty -> null. "
            "icaoHex is 6 upper-case hex digits. altitude_type 0 = pressure (QNH), 1 = geometric. "
            "Use heading/velocity/callsign only when the matching 'valid' flag is set.",
        ],
        "messages": {str(k): {"name": v[0], "crcExtra": v[1], "v1Length": v[2], "v2Length": v[3]}
                     for k, v in sorted(MESSAGES.items())},
        "cases": cases,
    }
    os.makedirs(os.path.dirname(CASES_PATH), exist_ok=True)
    with open(CASES_PATH, "w") as fh:
        json.dump(fixture, fh, indent=1)
        fh.write("\n")
    demo = demo_flight()
    with open(DEMO_PATH, "w") as fh:
        json.dump(demo, fh, indent=1)
        fh.write("\n")
    print("wrote %d cases to %s" % (len(cases), os.path.relpath(CASES_PATH, ROOT)))
    print("wrote %d demo frames to %s" % (len(demo["frames"]), os.path.relpath(DEMO_PATH, ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
