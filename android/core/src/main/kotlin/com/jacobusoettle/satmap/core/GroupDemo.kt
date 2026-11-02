package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Demo mode (docs/groups/SPEC.md §8.15): walkthroughs and screenshots run against `FakeGroupServer` with this seed,
 *  relative to the anchor A = (−33.918292, 18.921204), the first point of the walkthrough's walk. Everything is the
 *  same on every run except the times, which follow `now`. The iPhone core's GroupDemo.swift makes the same seed.
 *
 *  1. "Kruger trip": yours (display name "Sam", colour 0), not shown, sharing on, consent recorded. Thandi (admin),
 *     Pieter, Lerato and Sipho (live 25 min old), each with a 2-hour trail of 60 points ending at their live
 *     position; one pin of each type; one pending invite made by you.
 *  2. "Drone team": owned by Pieter, you are a member; paused (`pro_ended`), deleted in 27 days. Ayesha is admin.
 *  3. "Weekend ride": not joined; `inviteLink` (owner Nomsa) is the link the walkthrough pastes.
 *  4. "Braai club": not joined; `inviteLink2` (owner Bongani) is used only by the link checks. */
object GroupDemo {
    const val domain = "example.invalid"
    val anchor = GeoPoint(lat = -33.918292, lon = 18.921204)
    const val displayName = "Sam"

    /** "Kruger trip" was made 45 days ago (you joined "Drone team" 20 days ago, so the list shows Kruger trip first). */
    private const val krugerAge: Long = 45 * 86_400

    /** "Weekend ride": group id 0x10…0x1f, invite secret 0x20…0x2f, key 0x30…0x4f. */
    val inviteLink: String = link(groupId = sequence(0x10, 16), secret = sequence(0x20, 16), key = sequence(0x30, 32))

    /** "Braai club": group id 0x60…0x6f, invite secret 0x70…0x7f, key 0x80…0x9f. */
    val inviteLink2: String = link(groupId = sequence(0x60, 16), secret = sequence(0x70, 16), key = sequence(0x80, 32))

    /** The proof the demo's fake Pro store hands out: yearly Pro (any proof verifies on the fake server). */
    fun proProof(now: Long): ProProof = ProProof.Apple(signedTransaction = "fake:pro.yearly:${now + 365 * 86_400}")

    /** What the seed made for you. */
    data class Seed(
        val secrets: List<GroupSecret>,
        val krugerTrip: String,
        val droneTeam: String,
        val weekendRide: String,
        val braaiClub: String,
        /** The pending invite you made in "Kruger trip". */
        val madeInvite: GroupStore.MadeInvite,
    )

    /** A hub in demo mode and its server (the Swift `(hub:, server:)` tuple). */
    data class Demo(val hub: GroupHub, val server: FakeGroupServer)

    /** Fills the fake server with the four groups and returns your memberships. */
    fun seed(server: FakeGroupServer, now: Long): Seed {
        val a = anchor
        val day = 86_400L

        // 1. Kruger trip: the older membership, so it is listed first.
        val kruger = DemoGroup.make(name = "Kruger trip", label = "kruger")
        val sam = kruger.member("Sam")
        server.seedGroup(groupId = kruger.id, joinProof = kruger.joinProof, profile = kruger.profile, ownerId = sam.id, ownerPass = sam.pass,
                         ownerProfile = kruger.memberProfile(sam, colour = 0), createdAt = now - krugerAge,
                         pro = ProInfoDTO(platform = "apple", productId = "pro.yearly", expiresAt = now + 300 * day))
        val walkers = listOf(
            Walker(name = "Thandi", role = "admin", colour = 1, offset = -0.0012 to 0.0010, heading = 120, age = 8, bearing = 200.0, joined = 44 * day),
            Walker(name = "Pieter", role = "member", colour = 2, offset = 0.0008 to 0.0016, heading = 270, age = 15, bearing = 300.0, joined = 43 * day),
            Walker(name = "Lerato", role = "member", colour = 4, offset = -0.0004 to -0.0015, heading = 30, age = 4, bearing = 70.0, joined = 42 * day),
            Walker(name = "Sipho", role = "member", colour = 9, offset = 0.0018 to -0.0006, heading = null, age = 25 * 60, bearing = 140.0, joined = 41 * day),
        )
        val ids = HashMap<String, DemoMember>()
        ids["Sam"] = sam
        val feed = mutableListOf<Triple<DemoMember, Walker, GeoPoint>>()

        /** A walker's live record, `walker.age` seconds before `time`. */
        fun live(m: DemoMember, w: Walker, end: GeoPoint, time: Long): LiveEntry? {
            val at = time - w.age
            val p = LivePosition(time = at, lat = MicroDegrees.from(end.lat), lon = MicroDegrees.from(end.lon), accuracy = 5,
                                 heading = w.heading, speed = if (w.heading == null) null else 140)
            val rec = try {
                GroupCrypto.seal(p.encoded(), kruger.keys.live(m.id), GroupAAD.live(kruger.id, m.id, at = at))
            } catch (e: GroupCrypto.Failure) {
                return null
            }
            return LiveEntry(memberId = m.id, at = at, rec = Base64URL.encode(rec))
        }
        for (w in walkers) {
            val m = kruger.member(w.name)
            ids[w.name] = m
            server.seedMember(groupId = kruger.id, memberId = m.id, pass = m.pass, role = w.role, joinedAt = now - w.joined,
                              profile = kruger.memberProfile(m, colour = w.colour))
            val end = GeoPoint(lat = a.lat + w.offset.first, lon = a.lon + w.offset.second)
            for ((start, points) in trail(end = end, endTime = now - w.age, bearing = w.bearing)) {
                try {
                    val plain = TrailChunk.encode(points, windowStart = start, compress = true)
                    val rec = GroupCrypto.seal(plain, kruger.keys.trail(m.id), GroupAAD.trail(kruger.id, m.id, start = start))
                    server.seedChunk(groupId = kruger.id, memberId = m.id, start = start, rec = Base64URL.encode(rec))
                } catch (e: Exception) {
                    // Never with these points; a window left out only shortens the trail.
                }
            }
            val entry = live(m, w, end, now)
            val rec = entry?.rec
            if (entry != null && rec != null) server.seedLive(groupId = kruger.id, memberId = m.id, at = entry.at, rec = rec)
            feed.add(Triple(m, w, end))
        }
        // A walkthrough runs for many minutes after the seed: the members go on "sharing", so their dots keep the ages
        // above (fresh, and Sipho 25 minutes old) whenever the live positions are read.
        val krugerId = kruger.id
        val liveFeed = feed.toList()
        server.liveSource = { groupId, time ->
            if (groupId != krugerId) emptyList() else liveFeed.mapNotNull { (m, w, end) -> live(m, w, end, time) }
        }
        val pins = listOf(
            DemoPin(PinType.animal, "Kudu", "Three bulls", -0.0006 to 0.0004, "Thandi", 2 * 3600),
            DemoPin(PinType.water, null, null, 0.0010 to -0.0010, "Pieter", 5 * 3600),
            DemoPin(PinType.shelter, null, null, 0.0014 to 0.0008, "Lerato", 26 * 3600),
            DemoPin(PinType.danger, null, "Loose gravel on the bend", -0.0015 to 0.0002, "Thandi", 50 * 60),
            DemoPin(PinType.pothole, null, null, -0.0002 to 0.0018, "Sipho", 3 * 3600),
            DemoPin(PinType.police, null, null, 0.0004 to -0.0018, "Pieter", 40 * 60),
        )
        for ((i, p) in pins.withIndex()) {
            val dropper = ids[p.by] ?: continue
            val pinId = Base64URL.encode(demoBytes("kruger|pin|$i", 16))
            val createdAt = now - p.age
            val expiresAt = if (p.type == PinType.animal) createdAt + GroupPolicy.animalPinLifetime else null
            val record = PinRecord(type = p.type.rawValue, animal = p.animal, note = p.note, lat = MicroDegrees.from(a.lat + p.offset.first),
                                   lon = MicroDegrees.from(a.lon + p.offset.second))
            val rec = try {
                GroupCrypto.seal(record.json(), kruger.keys.pin, GroupAAD.pin(kruger.id, pinId = pinId, memberId = dropper.id, createdAt = createdAt, expiresAt = expiresAt))
            } catch (e: GroupCrypto.Failure) {
                continue
            }
            server.seedPin(groupId = kruger.id, pinId = pinId, memberId = dropper.id, createdAt = createdAt, expiresAt = expiresAt, rec = Base64URL.encode(rec))
        }
        val inviteId = Base64URL.encode(demoBytes("kruger|invite", 16))
        val inviteCreated = now - 3600
        val token = server.seedInvite(groupId = kruger.id, inviteId = inviteId, secret = demoBytes("kruger|invite|secret", 16), createdBy = sam.id,
                                      createdAt = inviteCreated, expiresAt = inviteCreated + 604_800)
        val madeLink = InviteLink(token = token, groupKey = kruger.groupKey)?.url(domain) ?: ""

        // 2. Drone team: paused 3 days ago, so it is deleted in 27 days.
        val drone = DemoGroup.make(name = "Drone team", label = "drone")
        val dronePieter = drone.member("Pieter")
        val droneAyesha = drone.member("Ayesha")
        val droneSam = drone.member("Sam")
        val pausedAt = now - 3 * day
        server.seedGroup(groupId = drone.id, joinProof = drone.joinProof, profile = drone.profile, ownerId = dronePieter.id, ownerPass = dronePieter.pass,
                         ownerProfile = drone.memberProfile(dronePieter, colour = 2), createdAt = now - 40 * day,
                         pro = ProInfoDTO(platform = "google", productId = "pro", expiresAt = pausedAt))
        server.seedMember(groupId = drone.id, memberId = droneAyesha.id, pass = droneAyesha.pass, role = "admin", joinedAt = now - 39 * day,
                          profile = drone.memberProfile(droneAyesha, colour = 6))
        server.seedMember(groupId = drone.id, memberId = droneSam.id, pass = droneSam.pass, role = "member", joinedAt = now - 20 * day,
                          profile = drone.memberProfile(droneSam, colour = 0))
        server.pause(groupId = drone.id, reason = "pro_ended", at = pausedAt)

        // 3. Weekend ride and 4. Braai club: not joined; fixed invite links.
        val weekend = DemoGroup.fixed(name = "Weekend ride", groupId = sequence(0x10, 16), groupKey = sequence(0x30, 32))
        seedInvited(server, weekend, owner = "Nomsa", others = listOf("Lindiwe" to 5, "Johan" to 7), secret = sequence(0x20, 16), now = now)
        val braai = DemoGroup.fixed(name = "Braai club", groupId = sequence(0x60, 16), groupKey = sequence(0x80, 32))
        seedInvited(server, braai, owner = "Bongani", others = listOf("Zanele" to 8), secret = sequence(0x70, 16), now = now)

        return Seed(secrets = listOf(kruger.secret(sam), drone.secret(droneSam)), krugerTrip = kruger.id, droneTeam = drone.id, weekendRide = weekend.id,
                    braaiClub = braai.id, madeInvite = GroupStore.MadeInvite(inviteId = inviteId, link = madeLink, expiresAt = inviteCreated + 604_800))
    }

    /** A hub in demo mode: the fake server with the seed, memberships in memory, no sockets, and your local settings
     *  for "Kruger trip" (sharing on, consent recorded, not shown) and its invite link. Call `start()` on it as usual.
     *  `scope` is the hub's (the main thread in the app). */
    fun makeHub(store: GroupStore, words: WordFilter, clock: () -> Long,
                scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)): Demo {
        val server = FakeGroupServer(now = clock)
        val now = clock()
        val seed = seed(server, now)
        seed.secrets.firstOrNull { it.groupId == seed.krugerTrip }?.let { you ->
            store.putGroup(GroupStore.StoredGroup(groupId = seed.krugerTrip, memberId = you.memberId, role = "owner", joinedAt = now - krugerAge,
                                                  statusJSON = null, sharing = true, consentAt = now - krugerAge, trails = TrailsMode.day.rawValue,
                                                  shown = false))
            store.putMadeInvite(seed.krugerTrip, seed.madeInvite)
        }
        val hub = GroupHub(store = store, secrets = MemoryGroupSecrets(seed.secrets), transport = server, sockets = null, words = words,
                           domain = domain, clock = clock, scope = scope)
        return Demo(hub, server)
    }

    // Building the seed

    /** Bytes `start`, `start + 1`, … (`count` of them). */
    fun sequence(start: Int, count: Int): ByteArray = ByteArray(count) { (start + it).toByte() }

    private fun link(groupId: ByteArray, secret: ByteArray, key: ByteArray): String =
        InviteLink(token = Base64URL.encode(groupId + secret), groupKey = key)?.url(domain) ?: ""

    /** Fixed bytes for a label, so the demo's ids, keys and passes are the same on every run. */
    private fun demoBytes(label: String, count: Int): ByteArray =
        GroupCrypto.subkey(groupKey = ByteArray(32) { 0x5A }, groupId = "satmap-demo".toByteArray(Charsets.UTF_8), purpose = label).copyOf(count)

    private class Walker(val name: String, val role: String, val colour: Int, val offset: Pair<Double, Double>, val heading: Int?, val age: Long,
                         val bearing: Double, val joined: Long)

    private class DemoPin(val type: PinType, val animal: String?, val note: String?, val offset: Pair<Double, Double>, val by: String, val age: Long)

    private class DemoMember(val id: String, val pass: String, val name: String)

    private class DemoGroup(val name: String, val label: String, val groupKey: ByteArray, groupId: ByteArray) {
        val keys = GroupKeys(groupKey = groupKey, groupId = groupId)
        val id: String get() = keys.groupId
        val joinProof: String get() = Base64URL.encode(keys.joinProof)

        val profile: String get() = Base64URL.encode(GroupCrypto.seal(GroupProfile(name = name).json(), keys.group, GroupAAD.group(id)))

        fun member(name: String): DemoMember =
            DemoMember(id = Base64URL.encode(demoBytes("$label|member|$name", 16)), pass = Base64URL.encode(demoBytes("$label|pass|$name", 32)), name = name)

        fun memberProfile(m: DemoMember, colour: Int): String =
            Base64URL.encode(GroupCrypto.seal(MemberProfile(name = m.name, colour = colour).json(), keys.member, GroupAAD.member(id, m.id)))

        fun secret(m: DemoMember): GroupSecret = GroupSecret(groupId = id, memberId = m.id, pass = m.pass, groupKey = Base64URL.encode(groupKey))

        companion object {
            fun make(name: String, label: String) = DemoGroup(name, label, groupKey = demoBytes("$label|key", 32), groupId = demoBytes("$label|group", 16))

            fun fixed(name: String, groupId: ByteArray, groupKey: ByteArray) = DemoGroup(name, Base64URL.encode(groupId), groupKey, groupId)
        }
    }

    private fun seedInvited(server: FakeGroupServer, g: DemoGroup, owner: String, others: List<Pair<String, Int>>, secret: ByteArray, now: Long) {
        val o = g.member(owner)
        server.seedGroup(groupId = g.id, joinProof = g.joinProof, profile = g.profile, ownerId = o.id, ownerPass = o.pass,
                         ownerProfile = g.memberProfile(o, colour = 0), createdAt = now - 10 * 86_400,
                         pro = ProInfoDTO(platform = "apple", productId = "pro.yearly", expiresAt = now + 200 * 86_400))
        for ((i, other) in others.withIndex()) {
            val m = g.member(other.first)
            server.seedMember(groupId = g.id, memberId = m.id, pass = m.pass, role = "member", joinedAt = now - (9 - i) * 86_400L,
                              profile = g.memberProfile(m, colour = other.second))
        }
        val created = now - 2 * 3600
        server.seedInvite(groupId = g.id, inviteId = Base64URL.encode(demoBytes("${g.label}|invite", 16)), secret = secret, createdBy = o.id,
                          createdAt = created, expiresAt = created + 604_800)
    }

    /** A 2-hour trail of 60 points (one every 2 minutes) ending at `end` at `endTime`: a straight line from 0.004°
     *  away (towards `bearing` degrees from the end), plus a sine wiggle of 0.0003° across it. Grouped by window. */
    private fun trail(end: GeoPoint, endTime: Long, bearing: Double): List<Pair<Long, List<TrailPoint>>> {
        val rad = bearing * PI / 180
        val alongLat = cos(rad)
        val alongLon = sin(rad)
        val acrossLat = -sin(rad)
        val acrossLon = cos(rad)
        val windows = mutableListOf<Pair<Long, MutableList<TrailPoint>>>()
        for (i in 0 until 60) {
            val t = i.toDouble() / 59
            val wiggle = 0.0003 * sin(3 * PI * t)
            val lat = end.lat + (1 - t) * 0.004 * alongLat + wiggle * acrossLat
            val lon = end.lon + (1 - t) * 0.004 * alongLon + wiggle * acrossLon
            val time = endTime - (59 - i) * 120L
            val p = TrailPoint(time = time, lat = MicroDegrees.from(lat), lon = MicroDegrees.from(lon), startsSegment = i == 0)
            val start = TrailChunk.windowStart(time)
            val last = windows.lastOrNull()
            if (last != null && last.first == start) {
                last.second.add(p)
            } else {
                windows.add(start to mutableListOf(p))
            }
        }
        return windows
    }
}
