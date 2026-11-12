# Drone link

Drone mode shows your own drone, and the aircraft an ADS-B receiver on it hears, from the MAVLink a ground station on
a laptop or tablet forwards to the phone over Wi-Fi (README, "Drone link"). The iPhone and Android do the same thing
and look the same, screen for screen; the decoder and the rules live in one shared core with the same names on both.
This file is the Android mapping (DESIGN.md D33). The iPhone's side is `SatMap/Core/MAVLink.swift`,
`SatMap/Core/DroneLink.swift`, `SatMap/Core/UDPReceiver.swift`, `SatMap/App/DroneLinkModel.swift`,
`SatMap/UI/DroneLinkViews.swift` and `SatMap/Map/DroneLinkLayer.swift`; each Android piece below names its twin.

It is **traffic awareness only** (`DroneLink.disclaimer`): "Traffic awareness only. Not for navigation, separation or
collision avoidance. Many aircraft don't broadcast. Under SACAA Part 101 the remote pilot must give way to manned
aircraft." It is asked before the first switch-on, shown in the settings and at the foot of every drone or aircraft
panel. Drones: ArduPilot and PX4 (anything that speaks MAVLink); DJI drones don't use MAVLink.

## 1. Settings (`DroneLinkSheet`, iPhone `DroneLinkSheet`)

Reached from drone mode: **Drone link** (`DroneLinkBar`, test tag `droneLinkButton`), under the drone-mode card,
opens `ActiveSheet.droneLink` in the usual inset-grouped style, titled "Drone link" with **Done**:

| Section | Rows |
| --- | --- |
| (first) | Switch **Drone link** (`droneLinkSwitch`, off by default); the status row (`droneLinkStatus`: a dot, grey off, yellow waiting, green receiving, red silent or port taken, and `DroneLink.Status.text`). Footer: what it shows, from what, "For ArduPilot and PX4 drones; DJI drones don't use MAVLink." |
| This phone | Each address (`DroneLink.addresses`: Wi-Fi first, then the hotspot, then other networks; never loopback, link-local, mobile data or a VPN) as its label over `address:port`, with **Copy** (`droneLinkCopyAddress`, "Copied" with a tick for 2 s); none: "Not on Wi-Fi or a hotspot. Join the ground station's Wi-Fi network, or turn on the phone's hotspot and join it from the ground station." Then **Listen port** with a number field (`droneLinkPort`), taken when `DroneLink.port` accepts it (1024–65535, ASCII digits). Footer: "QGroundControl forwards to port 14445 unless you change it there.", or in red "Use a port from 1024 to 65535." |
| In QGroundControl | `DroneLink.qgcInstructions` ("Application Settings > Telemetry > MAVLink Forwarding: tick Enable, Host name 192.168.1.23:14445, restart QGC", `droneLinkQGC`) and **Copy these steps** (`droneLinkCopySteps`). Footer: QGroundControl forwards MAVLink 2 only; MAVProxy's `--out=udp:<ip>:<port>`; nothing is sent back. |
| (last) | The disclaimer with a yellow warning sign (`droneLinkDisclaimer`). |

Switching on before the disclaimer was accepted shows the alert "Drone link" with the disclaimer, **Cancel** and
**I understand** (`DroneLinkDisclaimer`, `droneLinkDisclaimerAlert`, `droneLinkAccept`); the switch comes on only with
**I understand**, and it is asked once. The switch, the port and the acceptance are saved app-wide (the iPhone's
UserDefaults keys `droneLinkEnabled`, `droneLinkPort`, `droneLinkDisclaimerAccepted`), read as stored values only
(`Defaults.storedBool` / `storedInt`): no launch extra or intent can switch the link on. The addresses are read when
the sheet opens, every 5 s while it shows, and on each return to the foreground.

## 2. Receiving (`UdpReceiver`, `DroneLinkModel`)

`UdpReceiver` (iPhone `UDPReceiver`) binds a `DatagramSocket` to `0.0.0.0:<port>` without SO_REUSEADDR (on Linux it
would let a second socket share the port) and hands every datagram to the model from its own thread, which waits a
second at a time so a closed socket's thread ends promptly. Datagrams over 8 KB (`UdpReceiver.maxDatagram`; real
MAVLink datagrams are a few hundred bytes) are dropped. If the socket fails under it (not stopped), it closes it,
stops counting as running and calls `onStopped`. **Nothing is ever sent.**

`DroneLinkModel` (in `AppModel.droneLink`; iPhone `DroneLinkModel`) opens it only while the switch is on, SatMap is in
the foreground (`AppModel.willEnterForeground` / `didEnterBackground`) and no test-build lock is up (`setLocked`);
otherwise it is closed. Each datagram is decoded on the socket's thread (`MAVLink.decode`), and only its frames wait
for the main thread, at most 2,000 datagrams and 1 MB between two ticks (more is dropped: a flood can't run the app out
of memory or stall the main thread). Four times a second while listening it hands what arrived to
`DroneLink.Traffic`, prunes what is too old, bumps `revision` when something changed (and at least once a
second, for the grey and fading), closes a selection whose drone or aircraft went, and updates `status`. A port that
can't be had, or a socket that failed (`onStopped`), shows `Status.Failed` and is tried again every 3 s. A new port is
bound at once whenever the link should listen, also after the old one failed. Switching off forgets everything heard.

Permissions: receiving unicast UDP needs only INTERNET at targetSdk 36. Android 17 (API 37) will need
`ACCESS_LOCAL_NETWORK` for local-network traffic once SatMap targets it: add it then, asked when the switch is turned
on. The iPhone needs no Local Network prompt to receive unicast UDP (TN3179); it declares
`NSLocalNetworkUsageDescription` for a later TCP option.

Ground stations: QGroundControl forwards every MAVLink message it receives to one address (Application Settings >
Telemetry > MAVLink Forwarding: Enable, Host name, default `localhost:14445`; restart QGC), plain one-way UDP,
signatures stripped, MAVLink 2 only (v1 is dropped except HEARTBEAT and RADIO_STATUS). Mission Planner and MAVProxy
(`--out=udp:<phone-ip>:14445`) forward v1 or v2. ADS-B receivers on the drone (uAvionix pingRX and the like) arrive as
ADSB_VEHICLE in the same stream.

### 2.1 Decoder (core `MAVLink`)

`MAVLink.decode(datagram)` returns `MAVLink.Frame`s (version, signed, sysid, compid, msgId, the payload zero-extended
to its full length, and `message`) for every frame of a known message with a good CRC, in order:

- 0xFE = MAVLink 1 (6-byte header), 0xFD = MAVLink 2 (10-byte header); several frames per datagram.
- CRC: X.25 (CRC-16/MCRF4XX) over the header without the start byte, the payload as received and the message's
  CRC_EXTRA (`MAVLink.messages`).
- MAVLink 2 incompat flag 0x01 = signed: the 13 signature bytes are skipped, not verified; any other incompat bit:
  rejected. A payload longer than the message's full length is rejected.
- An unknown message, a bad CRC or a bad flag: resync one byte on. A frame running past the datagram's end is dropped.

Messages (`MAVLink.Heartbeat`, `SysStatus`, `GpsRawInt`, `GlobalPositionInt`, `VfrHud`, `BatteryStatus`,
`HomePosition`, `AdsbVehicle`) hold the fixture's "decoded" values; sentinels are null.

### 2.2 Traffic (core `DroneLink.Traffic`)

- A `DroneLink.Drone` per MAVLink system id, with a colour slot in the order drones were first heard. Only the
  flight controller's heartbeat (`MAVLink.Heartbeat.isAutopilot`: autopilot not 8, type not a peripheral such as a
  camera, gimbal, ADS-B receiver or onboard computer) makes a drone or sets its armed state; any other heartbeat
  (type 6 or autopilot 8 included) never makes a drone. A ground station's (type 6, or autopilot 8 from component 190
  or system id 255) also keeps its system id out unless it was already a drone; a peripheral's on the drone's system
  id (a camera at component 100 with autopilot 8) doesn't. ADSB_VEHICLE makes no drone either. Of several
  autopilot heartbeats on one system id, the flight controller's (`DroneLink.autopilotCompid`, component 1) wins: a
  new one is kept only if it comes from component 1 or the kept one (`Drone.heartbeatCompid`) doesn't. Positions
  (GLOBAL_POSITION_INT, HOME_POSITION) count only when `DroneLink.isPosition` (in range and not exactly 0,0, what
  autopilots send before a fix): until then the drone is heard but not drawn, and stays grey (as on the iPhone). Heading: GLOBAL_POSITION_INT's, else VFR_HUD's; ground speed and climb:
  VFR_HUD's, else from GLOBAL_POSITION_INT; the take-off ground: height above sea level less height above take-off,
  else the home's.
- A drone is **stale** (grey) without a position for 10 s, and removed 60 s after its last position (one that never
  had one, 60 s after its last message).
- A `DroneLink.Aircraft` per ICAO address, only once it has a position; heading, velocities, callsign and squawk only
  when their valid flag is set. It **fades** after 15 s and goes after 120 s (as QGroundControl). Its kind
  (`DroneLink.AircraftKind`) from emitter_type: airliner (2–6), light (1, 12), helicopter (7), glider (9), balloon
  (10), UAV (14), other; balloons and "other" aren't turned.
- **Low and close** (`DroneLink.isLowAndClose`): within 5 NM of you or a drone and less than 2,000 ft above that
  point's ground (yours from the elevation data, a drone's take-off ground; sea level when unknown); an aircraft with
  no altitude counts as low.
- Status (`DroneLink.Status`): "Drone link is off", "Waiting for MAVLink on port 14445…", "Receiving from 1 drone"
  (" · 3 aircraft"; "Receiving MAVLink" with none), "No data for 12 s" (after 10 s without a datagram holding a known
  frame), "Can't listen on port 14445: another app may be using it".

## 3. Map (`DroneLinkLayer`, `DroneLinkOverlay`; iPhone `DroneLinkLayer`, `DroneLinkOverlay`)

Only in drone mode, on the phone's map (not Android Auto), above the airfields: one GeoJSON source `drone-link` and its
symbol layers just above `aviation`, bottom to top `drone-link-homes`, `drone-link-rings` (the selected item's ring),
`drone-link-icons` (map-aligned, turned with `icon-rotate`), `drone-link-upright-icons` (no heading, or a kind that
isn't turned) and `drone-link-labels` (upright cards 3 dp under the icons). Every image is app-drawn (the style has no
glyphs); the shapes are the iPhone's `DroneLinkShapes` in their 24-unit box (`DroneLinkShapes`), filled, with a dark
1.2 dp outline and a soft shadow.

- Drone: a 32 dp quadcopter in its colour (magenta, lime, violet, yellow, teal in the order heard; grey 62 % when
  stale), turned to its heading; card "Drone 1" (bold, its colour) and its height above take-off. Home: a 22 dp dark
  disc with an "H" and a ring in the drone's colour.
- Aircraft: a 28 dp icon by kind, white (red when low and close, with a red card border), turned to its heading, 45 %
  opaque once stale; card: callsign (or ICAO hex), then altitude in feet (to 100 ft; 10 below 1,000), speed in knots
  and ↑/↓ beyond 100 ft/min.

A tap within 26 dp of a drone (first) or an aircraft selects it (`DroneLinkModel.select`); any other tap closes the
panel. The panel (`DroneLinkPanel`, `droneLinkPanel`) shows under the button, with ✕ (`droneLinkPanelClose`):

- Drone: header "Drone 1" with Armed/Disarmed; Position "No position for 12 s" (orange) when stale; Height "50 m above
  take-off · 192 m above sea level"; "≈ above ground" (`DroneLink.aboveGroundM`: above sea level less SatMap's
  elevation under it, looked up again once it has moved 20 m; up to 10 m (`DroneLink.groundSlackM`) below that ground
  reads 0 m, further below it reads "– (heights don't match the map)", as on the iPhone; without the ground the row is
  left out); Speed "35 km/h · climb +0.5 m/s"; Battery "86 % · 16.1 V"; GPS "3D fix · 17
  satellites"; From you "0.1 NM 078° ENE" (cyan); Airfields: the nearest as drone mode reports it, then the next two
  with their distance (`DroneLink.nearestAerodromes`: within 30 NM, helipads only when nothing else is near).
- Aircraft: header callsign with kind and ICAO; Warning "Below 2,000 ft within 5 NM" (red) when low and close;
  Altitude "800 ft (244 m) QNH" (metres too when heights are in metres; GPS for geometric); Speed "70 kt · −100
  ft/min"; Heading; Squawk; From you; From each drone (in its colour); Last heard "3 s ago" (orange once stale).
- The disclaimer at the foot (`droneLinkPanelDisclaimer`).

Units: the drone's heights in the app's height unit, speeds in its distance unit (km/h, mph, kt), climbs in m/s or
ft/min; aircraft altitudes in feet and speeds in knots as aviation gives them (metres added in metres mode); distances
in nautical miles as the rest of drone mode.

## 4. Core

`core/.../MAVLink.kt` and `core/.../DroneLink.kt`, with the iPhone's names: `MAVLink` (`Frame`, `Message`, the
message types, `crc`, `decode`) and `DroneLink` (constants, `port`, `savedPort`, `qgcInstructions`, `interfaceLabel`,
`isReachableIPv4`, `addresses`, `Status`, `rangeAndBearing`, `nearestAerodromes`, `isLowAndClose`, `AircraftKind`,
`Aircraft`, `speed`, `climb`, `feet`, `grouped`, `Drone`, `Received`, `Traffic`, `Replay`, `bytes`). `DroneLinkTests`
is the iPhone's `DroneLinkTests.swift` test for test, against the shared fixture
`Tests/SatMapCoreTests/Fixtures/mavlink/cases.json` (written with pymavlink by `docs/mavlink/make_fixtures.py`) and
`docs/mavlink/demo_flight.json`.

## 5. Demo and tests

- **Replay**: `DroneLinkModel.useReplayForTests(replay)` feeds the recorded flight (60 s at the demo farm with three
  ADS-B aircraft, looping) through the same decoder instead of the socket, starting with the switch off and the
  disclaimer not accepted, and saving nothing: the iPhone's `-droneLinkDemo YES`. It is set through the
  instrumentation in the app's own process, never by an intent extra, and the flight is in the **test** APK's assets
  only (`androidTest` assets from `docs/mavlink`).
- **Walkthrough** (`ScreenshotTest.droneLinkScreenshots`, right after step 25 in drone mode, as on the iPhone): the
  button, the switch with its disclaimer, "Receiving from 1 drone" and QGroundControl's steps → `42-drone-link-settings`;
  then the drone, its home and the aircraft on the map, the drone tapped and its panel with the disclaimer, the map
  moved (`MapController.scrollForTests`) so the drone is in the clear a third of the way down below the panel →
  `43-drone-on-map`. The recorded flight takes off at 380 m above sea level (the farm's ground in the elevation data)
  so its "≈ above ground" reads sensibly; the decoder cases keep 142 m. A failed check fails the job.
- **Unit tests**: `DroneLinkTests` (core); `DroneLinkModelTest` (a UDP loopback of every fixture datagram to
  `UdpReceiver`, a second listener refused, the disclaimer flow, saved settings, foreground and switch-off close the
  socket, silence and removal, a port in use tried again, a changed port, a new port after a failed one, a socket that
dies, oversized datagrams, a flood capped by bytes, the lock, the replay); `MapScreenDroneLinkTest`
  (button, disclaimer, addresses, port, steps, panels); `DroneLinkLayerTest` (shapes, colours, images).

## 6. Later

- Online ADS-B (adsb.lol and the like) for aircraft when the drone has no receiver.
- A TCP or MAVLink-router connection; Android 17's `ACCESS_LOCAL_NETWORK`.
