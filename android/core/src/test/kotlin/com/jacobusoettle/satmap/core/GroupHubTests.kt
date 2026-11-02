package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A clock the tests move by hand. */
class TestClock(var now: Long) {
    fun advance(seconds: Long) {
        now += seconds
    }
}

/** A WebSocket the tests drive: it records what the hub sends. */
class FakeSocket(val groupId: String) : GroupSocket {
    val sent = mutableListOf<String>()
    var closedWith: Int? = null

    override fun send(text: String) {
        sent.add(text)
    }

    override fun close(code: Int) {
        closedWith = code
    }

    /** The JSON objects sent (not "ping"). */
    val messages: List<JsonObject>
        get() = sent.mapNotNull { text -> try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } }
}

class FakeSockets : GroupSocketFactory {
    val opened = mutableListOf<FakeSocket>()

    override fun open(groupId: String, pass: String): GroupSocket = FakeSocket(groupId).also { opened.add(it) }
}

/** The fake server, with each E23 (drop a pin) held in flight while `hold` is on, until `release`: the request reaches
 *  the server at once, and its answer comes back when let go, or is lost on the way (`loseAnswers`). */
class HeldPins(private val server: FakeGroupServer) : GroupTransport {
    var hold = false
    var loseAnswers = false
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    /** The E23s waiting for their answer. */
    val held: Int get() = gates.count { !it.isCompleted }

    /** Lets every held E23 answer, and stops holding. */
    fun release() {
        hold = false
        for (g in gates) g.complete(Unit)
    }

    override suspend fun request(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse {
        if (!hold || method != "POST" || !path.endsWith("/pins")) return server.request(method, path, pass, json)
        val gate = CompletableDeferred<Unit>()
        gates.add(gate)
        val response = server.handle(method, path, pass, json)
        gate.await()
        if (loseAnswers) throw GroupTransportError.Offline()
        return response
    }
}

/** One phone: a hub with its own store and secrets, on the shared fake server (or a `transport` in front of it).
 *  `scope` is the test's (its work runs on the test's thread, as the app's runs on the main thread). */
class Phone(server: FakeGroupServer, clock: TestClock, scope: CoroutineScope, sockets: FakeSockets? = null, words: String = "badword\n",
            transport: GroupTransport = server) {
    val store = GroupStore(":memory:", JdbcSqlDriver)
    val secrets = MemoryGroupSecrets()
    val hub = GroupHub(store = store, secrets = secrets, transport = transport, sockets = sockets, words = WordFilter(words),
                       domain = "example.invalid", clock = { clock.now }, scope = scope)
    val events = mutableListOf<GroupHubEvent>()

    init {
        hub.onChange = { events.add(it) }
        hub.setLocationAccess(authorised = true, precise = true)
    }

    val alerts: List<GroupAlert> get() = events.filterIsInstance<GroupHubEvent.Alert>().map { it.alert }
}

/** The groups sync engine against the fake server (docs/groups/SPEC.md §8, §12.2), as the iPhone core's
 *  GroupHubTests. */
class GroupHubTests {
    private val t0 = 1_793_613_000L
    private val clock = TestClock(t0)
    private val server = FakeGroupServer(now = { clock.now })
    private val pro = ProProof.Apple(signedTransaction = "fake:pro.yearly:1825149600")
    private lateinit var scope: CoroutineScope
    private val phones = mutableListOf<Phone>()

    @After
    fun tearDown() {
        for (p in phones) p.store.close()
    }

    /** Runs a test on one thread, with the hubs' work in its scope. */
    private fun test(body: suspend () -> Unit) = runBlocking {
        scope = this
        body()
    }

    // Helpers

    private fun phone(sockets: FakeSockets? = null, words: String = "badword\n", transport: GroupTransport = server): Phone =
        Phone(server, clock, scope, sockets, words, transport).also { phones.add(it) }

    private fun link(made: MadeInvite): InviteLink {
        val parsed = InviteLink.parse(made.link, domain = "example.invalid")
        if (parsed !is InviteLink.Parsed.Invite) throw AssertionError("not a link: ${made.link}")
        return parsed.link
    }

    /** An owner with a group, and a second phone that joined it through an invite (over `memberTransport`). */
    private suspend fun ownerAndMember(name: String = "Kruger trip", memberTransport: GroupTransport = server): Triple<Phone, Phone, String> {
        val owner = phone()
        val gid = owner.hub.createGroup(name = name, displayName = "Sam", pro = pro)
        val made = owner.hub.createInvite(gid)
        val member = phone(transport = memberTransport)
        clock.advance(10)
        member.hub.join(link(made), displayName = "Thandi")
        member.hub.waitForBackgroundWork()
        owner.hub.becameActive()
        return Triple(owner, member, gid)
    }

    private fun requests(method: String, pathPart: String) = server.log.filter { it.method == method && it.path.contains(pathPart) }

    private fun uploads(): List<JsonObject> = requests("POST", "/upload").mapNotNull { r -> r.body?.let { jsonObject(it) } }

    private fun JsonObject.chunkCount(): Int? = (this["chunks"] as? kotlinx.serialization.json.JsonArray)?.size

    /** Starts sharing on a phone: consent, sharing on, location granted. */
    private fun share(p: Phone, gid: String) {
        p.hub.recordConsent(gid)
        p.hub.setSharing(true, groupId = gid)
        p.hub.setLocationAccess(authorised = true, precise = true)
    }

    private fun fix(p: Phone, lat: Double = -24.99619, lon: Double = 31.592512, accuracy: Double? = 5.0, at: Long? = null) {
        p.hub.fix(lat = lat, lon = lon, accuracy = accuracy, course = 87.0, speed = 3.0, heading = null, time = (at ?: clock.now).toDouble() + 0.4)
    }

    private fun point(p: Phone, lat: Double = -24.99619, lon: Double = 31.592512, at: Long? = null, segment: Boolean = false) {
        p.hub.trackPoint(TrackPoint(lat = lat, lon = lon, time = (at ?: clock.now).toDouble() + 0.7, startsSegment = segment))
    }

    /** The `GroupActionError` the action throws. */
    private suspend fun actionError(block: suspend () -> Unit): GroupActionError {
        try {
            block()
        } catch (e: GroupActionError) {
            return e
        }
        throw AssertionError("no error")
    }

    private fun memberId(p: Phone, gid: String): String = p.hub.detail(gid)!!.yourMemberId

    // Create, invite, join, see each other

    @Test
    fun testCreateInviteJoinAndBothSeeEachOther() = test {
        val owner = phone()
        val gid = owner.hub.createGroup(name = " Kruger trip ", displayName = "Sam", pro = pro)
        assertEquals(listOf("Kruger trip"), owner.hub.groups.map { it.name })
        assertEquals(listOf(gid), owner.secrets.loadAll().map { it.groupId })
        var detail = owner.hub.detail(gid)!!
        assertEquals(GroupRole.owner, detail.yourRole)
        assertEquals(listOf("Sam"), detail.members.map { it.name })
        assertEquals(GroupPolicy.palette[0], detail.members.first().colourHex)
        assertTrue(detail.members.first().isYou)
        assertTrue(detail.canInvite && detail.canRename && detail.canHandOver && detail.canDeleteGroup)
        assertFalse(detail.canLeave)
        assertFalse(detail.canReportGroupName)
        assertEquals("$gid.${detail.yourMemberId}", detail.supportCode)
        assertTrue(owner.hub.needsConsent(gid))
        // The server got only ciphertext: the name appears in no request.
        for (r in server.log) {
            assertFalse(r.path, (r.body ?: ByteArray(0)).toString(Charsets.UTF_8).contains("Kruger trip"))
        }

        val made = owner.hub.createInvite(gid)
        assertEquals("Kruger trip", made.groupName)
        assertEquals(clock.now + 604_800, made.expiresAt)
        assertTrue(made.link.startsWith("https://example.invalid/j/"))
        detail = owner.hub.detail(gid)!!
        assertEquals(listOf(made.link), detail.invites?.map { it.link })
        assertEquals("Sam", detail.invites?.first()?.createdByName)
        val parsed = InviteLink.parse("Join \"Kruger trip\" on SatMap: ${made.link}", domain = "example.invalid")
        if (parsed !is InviteLink.Parsed.Invite) return@test fail("link doesn't parse")
        val link = parsed.link
        assertEquals(gid, link.groupIdText)

        val member = phone()
        clock.advance(30)
        val preview = member.hub.preview(link)
        assertEquals(InvitePreview(groupId = gid, name = "Kruger trip", inviterName = "Sam", memberCount = 1, isPaused = false, expiresAt = made.expiresAt), preview)
        val joined = member.hub.join(link, displayName = "Thandi")
        assertEquals(gid, joined)
        assertEquals(gid, member.hub.shownGroupId)
        val mine = member.hub.detail(gid)!!
        assertEquals(GroupRole.member, mine.yourRole)
        assertEquals(listOf("Sam", "Thandi"), mine.members.map { it.name })
        assertFalse(mine.canInvite)
        assertNull(mine.invites)
        assertTrue(mine.canLeave && mine.canReportGroupName)

        owner.hub.becameActive()
        assertEquals(listOf("Sam", "Thandi"), owner.hub.detail(gid)?.members?.map { it.name })
        assertEquals(2, owner.hub.groups.first().memberCount)
        // The invite was used: E7 no longer lists it, and the phone forgets its link.
        val pending = owner.hub.invites(gid)
        assertTrue(pending.isEmpty())
        assertTrue(owner.store.madeInvites(gid).isEmpty())
        // Opening the link again on the joined phone says so.
        val again = actionError { member.hub.preview(link) }
        assertEquals(GroupActionError.Kind.AlreadyMember(groupId = gid), again.kind)
        assertEquals("You're already in Kruger trip.", again.message)
        // A used invite no longer works for anyone else.
        val third = phone()
        val used = actionError { third.hub.join(link, displayName = "Pieter") }
        assertEquals(GroupActionError.Kind.InviteNotFound, used.kind)
        assertEquals("This invite has already been used, was cancelled or has expired. Ask for a new one.", used.message)
        assertTrue(third.secrets.loadAll().isEmpty())
    }

    @Test
    fun testTextIsCheckedBeforeAnythingIsSent() = test {
        val p = phone()
        val filtered = actionError { p.hub.createGroup(name = "badword trip", displayName = "Sam", pro = pro) }
        assertEquals(GroupActionError.Kind.WordFilter(GroupTextField.groupName), filtered.kind)
        assertEquals("Please use different words for the group name.", filtered.message)
        val long = actionError { p.hub.createGroup(name = "Trip", displayName = "x".repeat(31), pro = pro) }
        assertEquals(GroupActionError.Kind.InvalidText(GroupTextField.displayName), long.kind)
        assertTrue(server.log.isEmpty())
        assertTrue(p.secrets.loadAll().isEmpty())
    }

    @Test
    fun testCreateErrors() = test {
        val p = phone()
        server.proAccepted = false
        val noPro = actionError { p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro) }
        assertEquals(GroupActionError.Kind.ProNotConfirmed, noPro.kind)
        assertEquals("SatMap Pro couldn't be confirmed. Tap Restore purchases, then try again.", noPro.message)
        // A definite refusal forgets the membership saved before the request.
        assertTrue(p.secrets.loadAll().isEmpty())
        server.proAccepted = true
        server.fail("POST", "/v1/groups", status = 503, code = "store_unavailable")
        assertEquals(GroupActionError.Kind.StoreUnavailable, actionError { p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro) }.kind)
        server.fail("POST", "/v1/groups", status = 429, code = "rate_limited")
        assertEquals(GroupActionError.Kind.RateLimited, actionError { p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro) }.kind)
        // A group id clash makes a new id and tries once more.
        server.fail("POST", "/v1/groups", status = 409, code = "group_exists")
        val gid = p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        assertEquals(5, requests("POST", "/v1/groups").size)
        assertEquals(1, p.secrets.loadAll().map { it.groupId }.count { it == gid })
        server.maxGroupsPerPurchase = 1
        val tooMany = actionError { p.hub.createGroup(name = "Trip 2", displayName = "Sam", pro = pro) }
        assertEquals(GroupActionError.Kind.TooManyGroups, tooMany.kind)
        assertEquals("You already own 20 groups. Delete one to make a new group.", tooMany.message)
    }

    @Test
    fun testLostCreateAnswerIsRestoredByStatus() = test {
        val p = phone()
        server.offline = true
        val offline = actionError { p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro) }
        assertEquals(GroupActionError.Kind.Offline, offline.kind)
        assertEquals("No connection. Try again when you're online.", offline.message)
        // Kept: the server may have said yes. It didn't, so E2 answers 404 and the secret goes.
        assertEquals(1, p.secrets.loadAll().size)
        server.offline = false
        p.hub.becameActive()
        assertTrue(p.secrets.loadAll().isEmpty())
        assertTrue(p.hub.groups.isEmpty())
        assertNull(p.hub.nextTickDelay(foreground = false))
    }

    @Test
    fun testReinstallRestoresMembershipsFromSecrets() = test {
        val (_, member, gid) = ownerAndMember()
        // A new install: same secrets, empty store.
        val fresh = phone()
        for (s in member.secrets.loadAll()) fresh.secrets.save(s)
        fresh.hub.start()
        assertEquals(listOf("Kruger trip"), fresh.hub.groups.map { it.name })
        assertEquals(listOf("Sam", "Thandi"), fresh.hub.detail(gid)?.members?.map { it.name })
        assertEquals(GroupRole.member, fresh.hub.detail(gid)?.yourRole)
        // A restarted phone keeps its store.
        val again = GroupHub(store = member.store, secrets = member.secrets, transport = server, sockets = null, words = WordFilter(""),
                             domain = "example.invalid", clock = { clock.now }, scope = scope)
        again.start()
        assertEquals(listOf(gid), again.groups.map { it.groupId })
        // A store row without a secret is useless and goes.
        val orphan = phone()
        orphan.store.putGroup(GroupStore.StoredGroup(groupId = gid, memberId = "x", role = "member", joinedAt = 1, statusJSON = null))
        orphan.hub.start()
        assertTrue(orphan.hub.groups.isEmpty())
        assertNull(orphan.store.group(gid))
        // A damaged secret is deleted.
        val damaged = phone()
        damaged.secrets.save(GroupSecret(groupId = gid, memberId = "short", pass = "x", groupKey = "y"))
        damaged.hub.start()
        assertTrue(damaged.secrets.loadAll().isEmpty())
    }

    @Test
    fun testJoinErrorsAndRetries() = test {
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val link = link(owner.hub.createInvite(gid))
        val member = phone()
        // A damaged key: the server refuses the proof.
        val bad = link.groupKey.also { it[0] = (it[0].toInt() xor 1).toByte() }
        val damaged = InviteLink(token = link.token, groupKey = bad)!!
        assertEquals(GroupActionError.Kind.InviteNotFound, actionError { member.hub.preview(damaged) }.kind)
        // A member id clash makes a new id once.
        server.fail("POST", "/invites/redeem", status = 409, code = "member_exists")
        // A lost answer: the retry uses the same pass and the server answers it again.
        server.offline = true
        assertEquals(GroupActionError.Kind.Offline, actionError { member.hub.join(link, displayName = "Thandi") }.kind)
        server.offline = false
        member.hub.join(link, displayName = "Thandi")
        assertEquals(listOf(gid), member.hub.groups.map { it.groupId })
        assertEquals(2, server.memberIds(gid).size)
    }

    @Test
    fun testDamagedGroupProfileIsADamagedLink() = test {
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val link = link(owner.hub.createInvite(gid))
        // Another admin (same pass) renames the group with a record sealed under another key.
        val pass = owner.secrets.loadAll().first().pass
        val otherKeys = GroupKeys(groupKey = GroupRandom.bytes(32), groupId = link.groupId)
        GroupAPI(server).renameGroup(gid, pass, Base64URL.encode(GroupCrypto.seal(GroupProfile("Other").json(), otherKeys.group, GroupAAD.group(gid))))
        val e = actionError { phone().hub.preview(link) }
        assertEquals(GroupActionError.Kind.DamagedLink, e.kind)
        assertEquals("This invite link is damaged. Ask for a new one.", e.message)
        // The owner's own phone shows it as unnamed.
        owner.hub.becameActive()
        assertEquals(GroupHub.unnamedGroup, owner.hub.groups.first().name)
    }

    // Sharing and the map

    @Test
    fun testSharingReachesTheOtherPhonesMap() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        point(member, segment = true)
        clock.advance(4)
        point(member, lat = -24.99615, lon = 31.59256)
        fix(member, lat = -24.99615, lon = 31.59256)
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().size)
        val body = uploads().first()
        assertEquals(1, body.chunkCount())
        assertTrue(body["live"] is JsonObject)
        val memberId = memberId(member, gid)
        assertEquals(listOf(TrailChunk.windowStart(clock.now)), server.chunkStarts(gid, memberId))

        owner.hub.setShown(gid)
        clock.advance(2)
        owner.hub.tick(foreground = true)
        owner.hub.waitForBackgroundWork()
        val map = owner.hub.mapContent(now = clock.now)!!
        assertEquals(gid, map.groupId)
        assertEquals(1, map.members.size)
        val dot = map.members.first()
        assertEquals(memberId, dot.memberId)
        assertEquals("T", dot.initials)
        assertEquals(7, dot.colourHex.length)
        assertEquals(-24.99615, dot.position.lat, 1e-6)
        assertEquals(87, dot.headingDeg)
        assertFalse(dot.isStale)
        assertEquals("Thandi, Sharing · just now", dot.a11yLabel)
        assertEquals(1, map.trails.size)
        assertEquals(2, map.trails.first().segments.first().size)
        assertEquals(3.0, map.trails.first().widthPt, 0.0)
        assertEquals(MemberState.Sharing(at = clock.now - 2), owner.hub.detail(gid)?.members?.last()?.state)
        // Your own trail is never drawn as a group trail, and you get no dot.
        assertNull(member.hub.mapContent(now = clock.now)?.members?.firstOrNull())
        assertTrue(member.hub.mapContent(now = clock.now)?.trails?.isEmpty() ?: false)
        // The dot turns stale after 10 minutes and hides after 24 hours.
        clock.advance(601)
        val stale = owner.hub.mapContent(now = clock.now)!!.members.first()
        assertTrue(stale.isStale)
        assertNull(stale.headingDeg)
        assertTrue(stale.a11yLabel.startsWith("Thandi, Last seen 10 min ago"))
        assertTrue(owner.hub.mapContent(now = clock.now + 86_400)?.members?.isEmpty() ?: false)
    }

    @Test
    fun testConsentGating() = test {
        val (_, member, gid) = ownerAndMember()
        assertTrue(member.hub.needsConsent(gid))
        member.hub.setSharing(true, groupId = gid)
        assertEquals(false, member.hub.detail(gid)?.isSharing)
        point(member)
        fix(member)
        member.hub.tick(foreground = false)
        assertTrue(member.store.ownWindows(gid).isEmpty())
        assertTrue(uploads().isEmpty())
        member.hub.recordConsent(gid)
        assertFalse(member.hub.needsConsent(gid))
        assertEquals(true, member.hub.detail(gid)?.isSharing)
        assertEquals(true, member.hub.groups.first().isSharing)
        point(member)
        assertEquals(1, member.store.ownWindows(gid).size)
        // Location not precise (reduced accuracy, coarse only): nothing is recorded or sent.
        member.hub.setLocationAccess(authorised = true, precise = false)
        clock.advance(5)
        point(member)
        assertEquals(1L, member.store.ownWindows(gid).first().version)
    }

    @Test
    fun testFirstPointAfterSharingOnStartsASegment() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        point(member)
        clock.advance(3)
        point(member)
        member.hub.setSharing(false, groupId = gid)
        member.hub.setSharing(true, groupId = gid)
        clock.advance(3)
        point(member)
        val w = member.store.ownWindows(gid).first()
        assertEquals(listOf(true, false, true), TrailChunk.decode(w.points, windowStart = w.start).map { it.startsSegment })
    }

    // Upload pace (§8.6)

    @Test
    fun testHTTPPaceWithAndWithoutWatchers() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        server.clearLog()
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals("the first live send is due at once", 1, uploads().size)
        assertEquals(60, member.hub.nextTickDelay(foreground = false))
        assertEquals(5, member.hub.nextTickDelay(foreground = true))
        repeat(5) {
            clock.advance(10)
            fix(member)
            member.hub.tick(foreground = false)
        }
        assertEquals("nobody watches: every 60 s", 1, uploads().size)
        clock.advance(10)
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals(2, uploads().size)
        // Someone starts watching: the answer says so, and the pace becomes 15 s.
        server.setWatchers(1, groupId = gid)
        clock.advance(60)
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals(3, uploads().size)
        assertEquals(15, member.hub.nextTickDelay(foreground = false))
        clock.advance(10)
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals(3, uploads().size)
        clock.advance(5)
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals(4, uploads().size)
        assertTrue(uploads().last()["live"] is JsonObject)
    }

    @Test
    fun testWindowAcknowledgedWhileOpenIsDeletedOnceClosed() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        point(member)
        fix(member)
        member.hub.tick(foreground = false)
        val w = member.store.ownWindows(gid).single()
        assertEquals("acknowledged while open", w.version, w.sentVersion)
        // A stop: no point after that. The open window stays for the points still to come, then closes.
        clock.now = w.start + 599
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals(listOf(w.start), member.store.ownWindows(gid).map { it.start })
        clock.now = w.start + 600 + 60
        fix(member)
        member.hub.tick(foreground = false)
        assertTrue("closed and acknowledged: deleted", member.store.ownWindows(gid).isEmpty())
        assertEquals(listOf(w.start), server.chunkStarts(gid, memberId(member, gid)))
        // A closed window that is not acknowledged yet stays.
        point(member)
        server.offline = true
        clock.now = TrailChunk.windowStart(clock.now) + 600 + 120
        member.hub.tick(foreground = false)
        assertEquals(1, member.store.ownWindows(gid).size)
    }

    @Test
    fun testHeartbeatWithoutANewFixHasNoLiveKey() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        server.clearLog()
        fix(member)
        member.hub.tick(foreground = false)
        assertTrue(uploads().last()["live"] is JsonObject)
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertEquals(2, uploads().size)
        assertNull("no new fix: the heartbeat has no live key", uploads().last()["live"])
        assertEquals(listOf("chunks"), uploads().last().keys.sorted())
    }

    @Test
    fun testCoarseFixesAreNeverSentAsLive() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val memberId = memberId(member, gid)
        server.clearLog()
        fix(member, accuracy = 150.0)
        member.hub.tick(foreground = false)
        assertNull(uploads().last()["live"])
        clock.advance(60)
        fix(member, accuracy = null)
        member.hub.tick(foreground = false)
        assertNull(uploads().last()["live"])
        clock.advance(60)
        member.hub.setLocationAccess(authorised = true, precise = false)
        fix(member, accuracy = 5.0)
        member.hub.tick(foreground = false)
        assertNull(uploads().lastOrNull()?.get("live"))
        assertNull(server.liveAt(gid, memberId))
        member.hub.setLocationAccess(authorised = true, precise = true)
        clock.advance(60)
        fix(member, accuracy = 100.0)
        member.hub.tick(foreground = false)
        assertTrue(uploads().last()["live"] is JsonObject)
        assertEquals(clock.now, server.liveAt(gid, memberId))
    }

    @Test
    fun testSocketPaceWhenWatchedAndNot() = test {
        val sockets = FakeSockets()
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val link = link(owner.hub.createInvite(gid))
        val member = phone(sockets = sockets)
        member.hub.join(link, displayName = "Thandi")
        share(member, gid)
        member.hub.becameActive()
        val socket = sockets.opened.last()
        assertEquals(gid, socket.groupId)
        member.hub.socketDidOpen(gid)
        val hello = socket.messages.first()
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(true, hello["watching"]!!.jsonPrimitive.boolean)
        assertEquals(member.store.group(gid)?.cursor, hello["cursor"]!!.jsonPrimitive.long)
        // Welcome with one watcher.
        val status = GroupAPI(server).status(gid, pass = member.secrets.loadAll().first().pass)
        member.hub.socketDidReceive(gid, "{\"type\":\"welcome\",\"v\":1,\"status\":${status.json()},\"live\":[]}")
        member.hub.socketDidReceive(gid, "{\"type\":\"tick\",\"serverTime\":${clock.now},\"watchers\":1}")
        server.clearLog()
        fun socketUploads() = socket.messages.filter { it["type"]?.jsonPrimitive?.content == "upload" }
        // becameActive's flush (before the socket opened) used the live slot over HTTP; 30 s later one is due.
        clock.advance(30)
        fix(member)
        member.hub.tick(foreground = true)
        assertEquals(1, socketUploads().size)
        assertTrue("over the socket, not HTTP", uploads().isEmpty())
        // 5 s later, moved 10 m: due. Moved less than 5 m: not due until 30 s.
        clock.advance(5)
        fix(member, lat = -24.99610)
        member.hub.tick(foreground = true)
        assertEquals(2, socketUploads().size)
        clock.advance(5)
        fix(member, lat = -24.99611)
        member.hub.tick(foreground = true)
        assertEquals(2, socketUploads().size)
        clock.advance(25)
        fix(member, lat = -24.99611)
        member.hub.tick(foreground = true)
        assertEquals(3, socketUploads().size)
        // The answer acknowledges the chunk.
        val id = socketUploads().last()["id"]!!.jsonPrimitive.int
        // Nobody watching: every 60 s, with the chunk upload.
        member.hub.socketDidReceive(gid, "{\"type\":\"tick\",\"serverTime\":${clock.now},\"watchers\":0}")
        member.hub.socketDidReceive(gid, "{\"type\":\"uploaded\",\"id\":$id,\"accepted\":[],\"rejected\":[],\"liveAccepted\":true}")
        clock.advance(30)
        fix(member, lat = -24.99500)
        member.hub.tick(foreground = true)
        assertEquals(3, socketUploads().size)
        clock.advance(30)
        member.hub.tick(foreground = true)
        assertEquals(4, socketUploads().size)
        // Pings every 30 s.
        assertTrue(socket.sent.contains("ping"))
        // The background closes the socket; uploads go over HTTP.
        member.hub.enteredBackground()
        assertEquals(1000, socket.closedWith)
        clock.advance(60)
        fix(member, lat = -24.99400)
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().size)
    }

    @Test
    fun testSocketClosesAndReconnects() = test {
        val sockets = FakeSockets()
        val owner = phone(sockets = sockets)
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        owner.hub.setShown(gid)
        owner.hub.becameActive()
        assertEquals(1, sockets.opened.size)
        owner.hub.socketDidOpen(gid)
        owner.hub.socketDidClose(gid, code = 1006)
        owner.hub.tick(foreground = true)
        assertEquals("waits for the backoff", 1, sockets.opened.size)
        clock.advance(2)
        owner.hub.tick(foreground = true)
        assertEquals(2, sockets.opened.size)
        // Showing another group (or none) closes it.
        owner.hub.setShown(null)
        assertEquals(1000, sockets.opened.last().closedWith)
        owner.hub.setShown(gid)
        assertEquals(3, sockets.opened.size)
        // 4404: deleted; no reconnect.
        owner.hub.socketDidClose(gid, code = 4404)
        assertEquals(listOf(GroupAlert.Kind.deleted), owner.alerts.map { it.kind })
        assertTrue(owner.hub.groups.isEmpty())
        clock.advance(100)
        owner.hub.tick(foreground = true)
        assertEquals(3, sockets.opened.size)
    }

    @Test
    fun testSocketCloseCodesForRemovalAndPass() = test {
        val sockets = FakeSockets()
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val member = phone(sockets = sockets)
        member.hub.join(link(owner.hub.createInvite(gid)), displayName = "Thandi")
        member.hub.becameActive()
        member.hub.socketDidOpen(gid)
        // 4401: the pass is checked with E2 first; it still works, so nothing goes.
        member.hub.socketDidClose(gid, code = 4401)
        member.hub.waitForBackgroundWork()
        assertEquals(listOf(gid), member.hub.groups.map { it.groupId })
        // 4403: removed.
        member.hub.tick(foreground = true)
        member.hub.socketDidOpen(gid)
        member.hub.socketDidClose(gid, code = 4403)
        assertEquals(listOf(GroupAlert.Kind.removed), member.alerts.map { it.kind })
        assertTrue(member.secrets.loadAll().isEmpty())
    }

    @Test
    fun testSocketTicksApplyChanges() = test {
        val sockets = FakeSockets()
        val owner = phone(sockets = sockets)
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        owner.hub.setShown(gid)
        owner.hub.becameActive()
        owner.hub.socketDidOpen(gid)
        val cursor = owner.store.group(gid)!!.cursor
        // Another admin renames the group (same key); the change arrives in a tick.
        val secret = owner.secrets.loadAll().first()
        val keys = GroupKeys(groupKey = Base64URL.decode(secret.groupKey)!!, groupId = Base64URL.decode(gid)!!)
        fun profile(name: String) = Base64URL.encode(GroupCrypto.seal(GroupProfile(name = name).json(), keys.group, GroupAAD.group(gid)))
        val api = GroupAPI(server)
        val status = api.status(gid, secret.pass).json()
        owner.hub.socketDidReceive(gid, "{\"type\":\"welcome\",\"v\":1,\"status\":$status,\"live\":[]}")
        val env = profile("Kruger 2027")
        val seq = api.renameGroup(gid, secret.pass, env).seq
        assertEquals(cursor + 1, seq)
        owner.hub.socketDidReceive(gid, """
            {"type":"tick","serverTime":${clock.now},"changes":{"from":$cursor,"items":[{"kind":"group","seq":$seq,"profile":"$env"}],"cursor":$seq,"more":false}}
            """)
        assertEquals("Kruger 2027", owner.hub.groups.first().name)
        assertEquals(seq, owner.store.group(gid)?.cursor)
        assertEquals(clock.now, owner.store.group(gid)?.cursorAt)
        // A tick whose `from` is ahead of the cursor (a frame was missed) is ignored and pulled over HTTP instead.
        api.renameGroup(gid, secret.pass, profile("Missed"))
        val last = api.renameGroup(gid, secret.pass, profile("Kruger 2028")).seq
        server.clearLog()
        owner.hub.socketDidReceive(gid, """
            {"type":"tick","serverTime":${clock.now},"changes":{"from":${last - 1},"items":[{"kind":"group","seq":$last,"profile":"${profile("Wrong")}"}],"cursor":$last,"more":false}}
            """)
        owner.hub.waitForBackgroundWork()
        assertEquals(1, requests("GET", "/changes").size)
        assertEquals(last, owner.store.group(gid)?.cursor)
        assertEquals("Kruger 2028", owner.hub.groups.first().name)
        // After catching up over HTTP the socket is told where the client is.
        assertEquals("ack", sockets.opened.last().messages.last()["type"]!!.jsonPrimitive.content)
        assertEquals(last, sockets.opened.last().messages.last()["cursor"]!!.jsonPrimitive.long)
        // A tick that says `more` makes the client pull too.
        server.clearLog()
        owner.hub.socketDidReceive(gid, """
            {"type":"tick","serverTime":${clock.now},"changes":{"from":$last,"items":[],"cursor":$last,"more":true}}
            """)
        owner.hub.waitForBackgroundWork()
        assertEquals(1, requests("GET", "/changes").size)
        // A frame for another group, or one that isn't understood, changes nothing.
        owner.hub.socketDidReceive("other", "{\"type\":\"tick\",\"watchers\":3}")
        owner.hub.socketDidReceive(gid, "pong")
        assertEquals("Kruger 2028", owner.hub.groups.first().name)
    }

    // Packing and answers (§4.3)

    @Test
    fun testPackingBy50ChunksAnd192KiB() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        server.offline = true
        val first = TrailChunk.windowStart(clock.now) + 600
        // 60 windows of 300 incompressible points.
        var seed = 12345L
        fun next(): Double {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (seed ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        for (w in 0 until 60) {
            for (i in 0 until 300) {
                point(member, lat = -80 + 160 * next(), lon = -170 + 340 * next(), at = first + w * 600L + i)
            }
        }
        assertEquals(60, member.store.ownWindows(gid).size)
        clock.now = first + 60 * 600 + 10
        server.offline = false
        server.clearLog()
        member.hub.connectivityChanged(online = true)
        val bodies = requests("POST", "/upload").mapNotNull { it.body }
        assertGreaterThan(bodies.size, 1)
        var total = 0
        for (b in bodies) {
            assertLessThanOrEqual(b.size, GroupPolicy.maxUploadBytes)
            val chunks = jsonObject(b).chunkCount() ?: 0
            assertLessThanOrEqual(chunks, 50)
            total += chunks
        }
        assertEquals(60, total)
        assertTrue("closed and acknowledged windows are deleted", member.store.ownWindows(gid).isEmpty())
        // Oldest first.
        val starts = jsonObject(bodies[0])["chunks"]!!.jsonArray.map { it.jsonObject["start"]!!.jsonPrimitive.long }
        assertEquals(first, starts.first())
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun testFiftyChunkLimit() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val first = TrailChunk.windowStart(clock.now) + 600
        for (w in 0 until 120) point(member, at = first + w * 600L)
        clock.now = first + 120 * 600 + 10
        server.clearLog()
        member.hub.tick(foreground = false)
        val counts = requests("POST", "/upload").mapNotNull { it.body }.map { jsonObject(it).chunkCount() ?: -1 }
        assertEquals(listOf(50, 50, 20), counts)
    }

    @Test
    fun testRejectedChunks() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val w = TrailChunk.windowStart(clock.now) + 600
        for (k in 0 until 4) point(member, at = w + k * 600L)
        server.chunkRejections = mapOf(w to "before_join", w + 600 to "window_paused", w + 1200 to "bad_record", w + 1800 to "bad_time")
        clock.now = w + 2400 + 5
        member.hub.tick(foreground = false)
        // Three can never be accepted and go; bad_time stays for one retry after the next E2.
        assertEquals(listOf(w + 1800), member.store.ownWindows(gid).map { it.start })
        assertEquals(1, member.store.ownWindows(gid).first().badTimeTries)
        server.clearLog()
        clock.advance(120)
        member.hub.tick(foreground = false)
        assertTrue("held until E2", uploads().all { (it.chunkCount() ?: 0) == 0 })
        member.hub.refreshDetail(gid)
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().last().chunkCount())
        assertTrue("rejected again: deleted", member.store.ownWindows(gid).isEmpty())
        // A bad_time that is accepted on its retry is kept as usual.
        server.chunkRejections = mapOf(w + 3000 to "bad_time")
        point(member, at = w + 3000)
        clock.now = w + 3600 + 5
        member.hub.tick(foreground = false)
        server.chunkRejections = emptyMap()
        member.hub.refreshDetail(gid)
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertTrue(member.store.ownWindows(gid).isEmpty())
        assertEquals(listOf(w + 3000), server.chunkStarts(gid, memberId(member, gid)))
    }

    @Test
    fun testOpenChunkIsResentOnlyWhenChanged() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val w = TrailChunk.windowStart(clock.now) + 600
        clock.now = w + 10
        point(member)
        server.clearLog()
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().last().chunkCount())
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertEquals("unchanged: not re-sent", 0, uploads().last().chunkCount())
        point(member)
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().last().chunkCount())
        // Each send is a new encryption.
        val recs = uploads().mapNotNull { (it["chunks"]!!.jsonArray.firstOrNull() as? JsonObject)?.get("rec")?.jsonPrimitive?.content }
        assertEquals(2, recs.size)
        assertNotEquals(recs[0], recs[1])
        assertEquals(recs[1], server.chunkRecord(gid, memberId(member, gid), start = w))
        assertEquals("the open window stays", 1, member.store.ownWindows(gid).size)
    }

    @Test
    fun testTooLargeUploadDropsTheOldestChunk() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val w = TrailChunk.windowStart(clock.now) + 600
        point(member, at = w)
        point(member, at = w + 600)
        clock.now = w + 1205
        server.fail("POST", "/upload", status = 413, code = "too_large")
        member.hub.tick(foreground = false)
        assertEquals(listOf(w + 600), member.store.ownWindows(gid).map { it.start })
        // The rest goes at the next tick (the chunk time went back).
        member.hub.tick(foreground = false)
        assertTrue(member.store.ownWindows(gid).isEmpty())
        assertEquals(listOf(w + 600), server.chunkStarts(gid, memberId(member, gid)))
    }

    @Test
    fun testServerErrorsBackOff() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        point(member)
        server.fail("POST", "/upload", status = 500, code = "internal", times = 2)
        server.clearLog()
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().size)
        // Retried after the backoff (2 s, then 4 s), not before.
        clock.advance(1)
        member.hub.tick(foreground = false)
        assertEquals(1, uploads().size)
        clock.advance(1)
        member.hub.tick(foreground = false)
        assertEquals(2, uploads().size)
        clock.advance(3)
        member.hub.tick(foreground = false)
        assertEquals(2, uploads().size)
        clock.advance(1)
        member.hub.tick(foreground = false)
        assertEquals(3, uploads().size)
        assertEquals(1, server.chunkStarts(gid, memberId(member, gid)).size)
        // Back online resets the wait.
        server.fail("POST", "/upload", status = 503, code = "internal")
        clock.advance(60)
        point(member)
        member.hub.tick(foreground = false)
        member.hub.connectivityChanged(online = true)
        assertEquals(5, uploads().size)
    }

    // Clearing the trail (§8.10)

    @Test
    fun testNoChunkUploadWhileClearTrailIsQueued() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        val memberId = memberId(member, gid)
        point(member)
        member.hub.tick(foreground = false)
        assertFalse(server.chunkStarts(gid, memberId).isEmpty())
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        assertEquals(1, owner.store.chunks(gid, memberId = memberId).size)

        server.clearLog()
        server.fail("DELETE", "/trail/me", status = 500, code = "internal", times = 2)
        member.hub.clearMyTrail(gid)
        assertTrue(member.store.ownWindows(gid).isEmpty())
        clock.advance(10)
        point(member)
        member.hub.waitForBackgroundWork()
        clock.advance(300)
        member.hub.tick(foreground = false)
        assertEquals(listOf("cleartrail"), member.store.ops(gid).map { it.kind })
        assertTrue("nothing goes up while E22 waits", uploads().isEmpty())
        assertEquals("points keep collecting", 1, member.store.ownWindows(gid).size)
        // E22 goes through; then the new chunk.
        server.clearLog()
        clock.advance(400)
        member.hub.tick(foreground = false)
        val order = server.log.filter { it.path.contains("/trail/me") || it.path.contains("/upload") }.map { it.method }
        assertEquals("DELETE", order.first())
        assertTrue(member.store.ops(gid).isEmpty())
        assertEquals(1, server.chunkStarts(gid, memberId).size)
        // The other phone hears `removed trail` and drops the old chunks.
        owner.hub.tick(foreground = true)
        assertEquals(1, owner.store.chunks(gid, memberId = memberId).size)
        assertEquals(TrailChunk.windowStart(clock.now - 700), owner.store.chunks(gid, memberId = memberId).first().start)
    }

    // Offline

    @Test
    fun testOfflineQueueThenFlush() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        server.offline = true
        member.hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = "Tap at the gate", lat = -24.99, lon = 31.6)
        clock.advance(1)
        member.hub.dropPin(groupId = gid, type = PinType.animal, animal = "Rhino", note = null, lat = -24.98, lon = 31.6)
        point(member)
        fix(member)
        member.hub.tick(foreground = false)
        member.hub.waitForBackgroundWork()
        // Shown at once; queued.
        assertEquals(2, member.hub.mapContent(now = clock.now)?.pins?.size)
        assertEquals(listOf("pin", "pin"), member.store.ops(gid).map { it.kind })
        assertTrue(server.pinIds(gid).isEmpty())
        server.offline = false
        clock.advance(30)
        member.hub.connectivityChanged(online = true)
        assertEquals(2, server.pinIds(gid).size)
        assertTrue(member.store.ops(gid).isEmpty())
        assertTrue(member.store.pins(gid).all { it.seq > 0 })
        assertEquals(1, server.chunkStarts(gid, memberId(member, gid)).size)
        // Live is never queued: the fix taken offline went with the first upload after it only because it is the newest.
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        val pins = owner.hub.mapContent(now = clock.now)!!.pins
        assertEquals(listOf(PinType.water, PinType.animal), pins.map { it.knownType })
        assertEquals("Rhino · just now", pins[1].label)
        assertEquals("Water, dropped by Thandi just now", pins[0].a11yLabel)
        assertEquals("Rhino, dropped by Thandi just now", pins[1].a11yLabel)
        assertEquals("#1E88E5", pins[0].colourHex)
        val detail = owner.hub.pinDetail(gid, pins[0].pinId)!!
        assertEquals("Tap at the gate", detail.note)
        assertEquals("Thandi", detail.dropperName)
        assertNull(detail.expiresAt)
        assertTrue("the owner may delete any pin", detail.canDelete)
        assertTrue(detail.canReport && detail.canBlock)
        assertFalse(detail.isYours)
        val animal = owner.hub.pinDetail(gid, pins[1].pinId)!!
        assertEquals(animal.createdAt + 43_200, animal.expiresAt)
    }

    // Pins

    @Test
    fun testExpiredAnimalPinHidesOffline() = test {
        val (owner, member, gid) = ownerAndMember()
        member.hub.dropPin(groupId = gid, type = PinType.animal, animal = "Kudu", note = null, lat = -24.98, lon = 31.6)
        member.hub.waitForBackgroundWork()
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        assertEquals(1, owner.hub.mapContent(now = clock.now)?.pins?.size)
        server.offline = true
        clock.advance(43_200 - 1)
        assertEquals("Kudu · 11 h ago", owner.hub.mapContent(now = clock.now)?.pins?.first()?.label)
        clock.advance(1)
        assertTrue(owner.hub.mapContent(now = clock.now)?.pins?.isEmpty() ?: false)
        owner.hub.tick(foreground = true)
        assertTrue(owner.store.pins(gid).isEmpty())
    }

    @Test
    fun testPinDeletedOnTheServerDropsTheQueuedPin() = test {
        val (_, member, gid) = ownerAndMember()
        server.fail("POST", "/pins", status = 410, code = "pin_deleted")
        member.hub.dropPin(groupId = gid, type = PinType.danger, animal = null, note = null, lat = -24.98, lon = 31.6)
        member.hub.waitForBackgroundWork()
        assertTrue(member.store.ops(gid).isEmpty())
        assertTrue(member.store.pins(gid).isEmpty())
        assertTrue(member.hub.mapContent(now = clock.now)?.pins?.isEmpty() ?: false)
    }

    @Test
    fun testPinIdClashGetsANewIdOnce() = test {
        val (_, member, gid) = ownerAndMember()
        server.fail("POST", "/pins", status = 409, code = "pin_exists")
        member.hub.dropPin(groupId = gid, type = PinType.shelter, animal = null, note = null, lat = -24.98, lon = 31.6)
        member.hub.waitForBackgroundWork()
        val posted = requests("POST", "/pins").mapNotNull { r -> r.body?.let { PinRequest.fromJson(GroupJSON.Fields(GroupJSON.parseObject(it))) } }
        assertEquals(2, posted.size)
        assertNotEquals(posted[0].pinId, posted[1].pinId)
        assertEquals(listOf(posted[1].pinId), server.pinIds(gid))
        assertEquals(listOf(posted[1].pinId), member.store.pins(gid).map { it.pinId })
    }

    @Test
    fun testDeletingPins() = test {
        val (owner, member, gid) = ownerAndMember()
        member.hub.dropPin(groupId = gid, type = PinType.pothole, animal = null, note = null, lat = -24.98, lon = 31.6)
        member.hub.waitForBackgroundWork()
        val pinId = server.pinIds(gid).first()
        // A pin deleted before it was sent never reaches the server.
        server.offline = true
        member.hub.dropPin(groupId = gid, type = PinType.police, animal = null, note = null, lat = -24.98, lon = 31.6)
        val unsent = member.store.pins(gid).first { it.seq == 0L }.pinId
        member.hub.deletePin(unsent, groupId = gid)
        member.hub.waitForBackgroundWork()
        assertTrue(member.store.ops(gid).isEmpty())
        server.offline = false
        // The owner deletes the member's pin; the member hears it.
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        owner.hub.deletePin(pinId, groupId = gid)
        owner.hub.waitForBackgroundWork()
        assertTrue(server.pinIds(gid).isEmpty())
        member.hub.becameActive()
        assertTrue(member.store.pins(gid).isEmpty())
        // Dropping needs a valid animal; the word filter applies.
        val noAnimal = assertThrows(GroupActionError::class.java) {
            member.hub.dropPin(groupId = gid, type = PinType.animal, animal = " ", note = null, lat = 0.0, lon = 0.0)
        }
        assertEquals(GroupActionError.Kind.InvalidText(GroupTextField.animal), noAnimal.kind)
        val rude = assertThrows(GroupActionError::class.java) {
            member.hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = "badword", lat = 0.0, lon = 0.0)
        }
        assertEquals("Please use different words.", rude.message)
    }

    /** Lets the hubs' background work run until an E23 is held in flight. */
    private suspend fun untilHeld(transport: HeldPins) {
        repeat(1000) {
            if (transport.held > 0) return
            yield()
        }
        fail("no E23 was sent")
    }

    @Test
    fun testPinDeletedWhileItsDropIsInFlightIsDeletedOnTheServer() = test {
        val held = HeldPins(server)
        val (owner, member, gid) = ownerAndMember(memberTransport = held)
        held.hold = true
        member.hub.dropPin(groupId = gid, type = PinType.pothole, animal = null, note = null, lat = -24.98, lon = 31.6)
        untilHeld(held)
        val pinId = member.store.pins(gid).single().pinId
        // "Oops": deleted while the server already holds it and its answer is on the way.
        member.hub.deletePin(pinId, groupId = gid)
        assertTrue(member.hub.mapContent(now = clock.now)?.pins?.isEmpty() ?: false)
        held.release()
        member.hub.waitForBackgroundWork()
        assertEquals("deleted on the server in the same flush", 1, requests("DELETE", "/pins/$pinId").size)
        assertTrue(server.pinIds(gid).isEmpty())
        assertTrue(member.store.ops(gid).isEmpty())
        assertTrue(member.store.pins(gid).isEmpty())
        // Nobody gets it back.
        member.hub.becameActive()
        owner.hub.becameActive()
        assertTrue(member.store.pins(gid).isEmpty())
        assertTrue(owner.store.pins(gid).isEmpty())
    }

    @Test
    fun testPinDeletedWhileItsDropsAnswerIsLostIsDeletedOnTheServer() = test {
        val held = HeldPins(server)
        val (_, member, gid) = ownerAndMember(memberTransport = held)
        held.hold = true
        held.loseAnswers = true
        member.hub.dropPin(groupId = gid, type = PinType.police, animal = null, note = null, lat = -24.98, lon = 31.6)
        untilHeld(held)
        val pinId = member.store.pins(gid).single().pinId
        member.hub.deletePin(pinId, groupId = gid)
        held.release()
        member.hub.waitForBackgroundWork()
        // The E23 may have reached the server (here it did): an E24 is queued, and goes after the backoff.
        assertEquals(listOf("unpin"), member.store.ops(gid).map { it.kind })
        assertEquals(listOf(pinId), server.pinIds(gid))
        held.loseAnswers = false
        clock.advance(2)
        member.hub.tick(foreground = false)
        assertTrue(server.pinIds(gid).isEmpty())
        assertTrue(member.store.ops(gid).isEmpty())
        assertTrue(member.store.pins(gid).isEmpty())
    }

    @Test
    fun testPinDeletedWhileItsIdClashesIsNotSentAgain() = test {
        val held = HeldPins(server)
        val (_, member, gid) = ownerAndMember(memberTransport = held)
        held.hold = true
        server.fail("POST", "/pins", status = 409, code = "pin_exists")
        member.hub.dropPin(groupId = gid, type = PinType.shelter, animal = null, note = null, lat = -24.98, lon = 31.6)
        untilHeld(held)
        member.hub.deletePin(member.store.pins(gid).single().pinId, groupId = gid)
        held.release()
        member.hub.waitForBackgroundWork()
        // The server stored nothing under that id (another member's), so there is nothing to delete, and no new id.
        assertEquals(1, requests("POST", "/pins").size)
        assertTrue(requests("DELETE", "/pins").isEmpty())
        assertTrue(server.pinIds(gid).isEmpty())
        assertTrue(member.store.ops(gid).isEmpty())
        assertTrue(member.store.pins(gid).isEmpty())
    }

    // Pause (§8.10)

    @Test
    fun testPausedDropsPointsAndStopsLive() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        fix(member)
        point(member)
        member.hub.tick(foreground = false)
        val memberId = memberId(member, gid)
        assertNotNull(server.liveAt(gid, memberId))
        server.pause(gid, reason = "pro_ended")
        member.hub.becameActive()
        val detail = member.hub.detail(gid)!!
        assertTrue(detail.isPaused)
        assertEquals(PausedReason.proEnded, detail.pausedReason)
        assertEquals(clock.now + 2_592_000, detail.deleteAt)
        assertTrue(member.hub.groups.first().isPaused)
        server.clearLog()
        clock.advance(100)
        point(member)
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals("no points recorded while paused", listOf(1L), member.store.ownWindows(gid).map { it.version })
        assertTrue("no live while paused", uploads().isEmpty())
        val paused = assertThrows(GroupActionError::class.java) {
            member.hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = null, lat = 0.0, lon = 0.0)
        }
        assertEquals(GroupActionError.Kind.GroupPaused, paused.kind)
        // Reporting, renaming and invites still work.
        owner.hub.renameGroup("Kruger 2027", groupId = gid)
        owner.hub.createInvite(gid)
        member.hub.report(groupId = gid, target = ReportTarget.Group, reason = ReportReason.spam, comment = "")
        // Resumed: sharing goes on, the first point starts a new segment.
        server.resume(gid)
        member.hub.becameActive()
        assertFalse(member.hub.detail(gid)?.isPaused ?: true)
        assertEquals("Kruger 2027", member.hub.groups.first().name)
        clock.advance(10)
        point(member)
        val w = member.store.ownWindows(gid).last()
        assertEquals(true, TrailChunk.decode(w.points, windowStart = w.start).last().startsSegment)
    }

    @Test
    fun testQueuedPointsInsideAPauseAreDropped() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        val memberId = memberId(member, gid)
        val secret = member.secrets.loadAll().first()
        val keys = GroupKeys(groupKey = Base64URL.decode(secret.groupKey)!!, groupId = Base64URL.decode(gid)!!)
        val w = TrailChunk.windowStart(clock.now) + 600
        // The phone didn't hear of the pause from w + 100 to w + 200 and kept recording.
        point(member, at = w + 10)
        point(member, at = w + 120)
        point(member, at = w + 300)
        server.pause(gid, at = w + 100)
        clock.now = w + 200
        server.resume(gid)
        clock.now = w + 700
        // E2 brings `pausedIntervals`; before the upload the point inside it is dropped.
        member.hub.refreshDetail(gid)
        member.hub.tick(foreground = false)
        val rec = server.chunkRecord(gid, memberId, start = w)!!
        val plain = GroupCrypto.open(Base64URL.decode(rec)!!, keys.trail(memberId), GroupAAD.trail(gid, memberId, start = w))
        assertEquals(listOf(w + 10, w + 300), TrailChunk.decode(plain, windowStart = w).map { it.time })
    }

    // Roles, removal, leaving, deletion

    @Test
    fun testRolesAndPermissions() = test {
        val (owner, member, gid) = ownerAndMember()
        val memberId = memberId(member, gid)
        val ownerId = memberId(owner, gid)
        var m = owner.hub.memberDetail(gid, memberId)!!
        assertTrue(m.canMakeAdmin && m.canMakeOwner && m.canRemove && m.canReport && m.canBlock)
        assertFalse(m.canRemoveAdmin)
        val me = owner.hub.memberDetail(gid, ownerId)!!
        assertFalse(me.canReport || me.canBlock || me.canRemove || me.canMakeOwner)
        // A member can't do owner things: the server refuses with 403.
        val forbidden = actionError { member.hub.setRole(GroupRole.admin, memberId = memberId, groupId = gid) }
        assertEquals(GroupActionError.Kind.Forbidden, forbidden.kind)
        assertEquals("You can't do that in this group.", forbidden.message)
        owner.hub.setRole(GroupRole.admin, memberId = memberId, groupId = gid)
        m = owner.hub.memberDetail(gid, memberId)!!
        assertEquals(GroupRole.admin, m.row.role)
        assertTrue(m.canRemoveAdmin)
        member.hub.becameActive()
        val asAdmin = member.hub.detail(gid)!!
        assertEquals(GroupRole.admin, asAdmin.yourRole)
        assertTrue(asAdmin.canInvite && asAdmin.canRename && asAdmin.canLeave && asAdmin.canReportGroupName)
        assertFalse(asAdmin.canHandOver || asAdmin.canDeleteGroup)
        assertEquals(emptyList<PendingInvite>(), asAdmin.invites)
        // An admin can't remove the owner.
        val ownerRow = member.hub.memberDetail(gid, ownerId)!!
        assertFalse(ownerRow.canRemove || ownerRow.canMakeAdmin)
        // Ownership offer, decline, offer, accept.
        owner.hub.offerOwnership(to = memberId, groupId = gid)
        assertEquals("Thandi", owner.hub.detail(gid)?.offerByYouTo)
        member.hub.becameActive()
        assertEquals("Sam", member.hub.detail(gid)?.offerToYouFrom)
        assertTrue(member.hub.groups.first().hasOfferToYou)
        member.hub.cancelOwnershipOffer(gid)
        assertNull(member.hub.detail(gid)?.offerToYouFrom)
        owner.hub.offerOwnership(to = memberId, groupId = gid)
        member.hub.becameActive()
        member.hub.acceptOwnership(gid, pro = ProProof.Google(productId = "pro", purchaseToken = "token-1"))
        assertEquals(GroupRole.owner, member.hub.detail(gid)?.yourRole)
        assertEquals("token-1", member.store.group(gid)?.proSent)
        owner.hub.becameActive()
        assertEquals(GroupRole.admin, owner.hub.detail(gid)?.yourRole)
        assertEquals(GroupRole.admin, owner.hub.detail(gid)?.members?.first { it.isYou }?.role)
    }

    @Test
    fun testRemovalWipesTheRemovedPhone() = test {
        val (owner, member, gid) = ownerAndMember()
        val memberId = memberId(member, gid)
        member.hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = null, lat = 0.0, lon = 0.0)
        member.hub.waitForBackgroundWork()
        owner.hub.removeMember(memberId, groupId = gid)
        assertEquals(listOf("Sam"), owner.hub.detail(gid)?.members?.map { it.name })
        assertTrue("their pins go with them", server.pinIds(gid).isEmpty())
        member.hub.becameActive()
        assertEquals(listOf(GroupAlert(kind = GroupAlert.Kind.removed, groupId = gid, groupName = "Kruger trip")), member.alerts)
        assertTrue(member.hub.groups.isEmpty())
        assertNull(member.store.group(gid))
        assertTrue(member.store.pins(gid).isEmpty())
        assertTrue(member.secrets.loadAll().isEmpty())
    }

    @Test
    fun testDeletedAndGoneAlerts() = test {
        val (owner, member, gid) = ownerAndMember()
        owner.hub.deleteGroup(gid)
        assertTrue(owner.hub.groups.isEmpty())
        assertTrue("your own deletion has no alert", owner.alerts.isEmpty())
        member.hub.becameActive()
        assertEquals(listOf(GroupAlert.Kind.deleted), member.alerts.map { it.kind })
        assertEquals("Kruger trip", member.alerts.first().groupName)
        assertTrue(member.secrets.loadAll().isEmpty())

        val (_, other, gid2) = ownerAndMember(name = "Drone team")
        server.purgeGroup(gid2)
        other.hub.becameActive()
        assertEquals(listOf(GroupAlert.Kind.gone), other.alerts.map { it.kind })
        assertTrue(other.hub.groups.isEmpty())
        // An action on a group that is gone says so.
        assertEquals(GroupActionError.gone, actionError { other.hub.renameGroup("x", groupId = gid2) })
    }

    @Test
    fun test401FromAnotherEndpointWipesNothingUnlessStatusAgrees() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        server.fail("POST", "/upload", status = 401, code = "unauthorized")
        fix(member)
        member.hub.tick(foreground = false)
        assertEquals("E2 still answers: nothing deleted", listOf(gid), member.hub.groups.map { it.groupId })
        assertEquals(1, member.secrets.loadAll().size)
        assertGreaterThanOrEqual(requests("GET", "/v1/groups/$gid").count { !it.path.contains("/changes") && !it.path.contains("/live") }, 1)
        server.fail("POST", "/upload", status = 401, code = "unauthorized")
        server.fail("GET", "/v1/groups/$gid", status = 401, code = "unauthorized")
        clock.advance(60)
        fix(member)
        member.hub.tick(foreground = false)
        assertTrue(member.hub.groups.isEmpty())
        assertEquals(listOf(GroupAlert.Kind.gone), member.alerts.map { it.kind })
        assertTrue(member.secrets.loadAll().isEmpty())
    }

    @Test
    fun testLeaving() = test {
        val (owner, member, gid) = ownerAndMember()
        assertEquals(GroupActionError.Kind.Forbidden, actionError { owner.hub.leave(gid) }.kind)
        // Only the owner deletes the group.
        assertEquals(GroupActionError.Kind.Forbidden, actionError { member.hub.deleteGroup(gid) }.kind)
        server.offline = true
        assertEquals(GroupActionError.Kind.Offline, actionError { member.hub.leave(gid) }.kind)
        assertEquals(1, member.hub.groups.size)
        server.offline = false
        member.hub.leave(gid)
        assertTrue(member.hub.groups.isEmpty())
        assertTrue(member.alerts.isEmpty())
        assertTrue(member.secrets.loadAll().isEmpty())
        owner.hub.becameActive()
        assertEquals(1, owner.hub.detail(gid)?.members?.size)
    }

    // Block list (§8.11)

    @Test
    fun testBlockedMembersAreHidden() = test {
        val (owner, member, gid) = ownerAndMember()
        val memberId = memberId(member, gid)
        share(member, gid)
        point(member, segment = true)
        clock.advance(5)
        point(member, lat = -24.9961)
        fix(member)
        member.hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = null, lat = 0.0, lon = 0.0)
        member.hub.waitForBackgroundWork()
        member.hub.tick(foreground = false)
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        var map = owner.hub.mapContent(now = clock.now)!!
        assertEquals(1, map.members.size)
        assertEquals(1, map.trails.size)
        assertEquals(1, map.pins.size)
        owner.hub.block(memberId, groupId = gid)
        map = owner.hub.mapContent(now = clock.now)!!
        assertTrue(map.members.isEmpty() && map.trails.isEmpty() && map.pins.isEmpty())
        val detail = owner.hub.detail(gid)!!
        assertEquals(listOf("Sam", "Blocked member"), detail.members.map { it.name })
        assertTrue(detail.members[1].isBlocked)
        assertEquals(GroupPolicy.grey, detail.members[1].colourHex)
        assertEquals(listOf("Thandi"), detail.blocked.map { it.name })
        assertNull(owner.hub.memberDetail(gid, memberId)?.live)
        // Not told to anyone: no request.
        assertFalse(server.log.any { it.path.contains("block") })
        // You can't block yourself.
        owner.hub.block(detail.yourMemberId, groupId = gid)
        assertEquals(setOf(memberId), owner.store.blocked(gid))
        owner.hub.unblock(memberId, groupId = gid)
        assertEquals(1, owner.hub.mapContent(now = clock.now)?.members?.size)
        assertTrue(owner.hub.detail(gid)?.blocked?.isEmpty() ?: false)
    }

    // Member state (§8.9)

    @Test
    fun testMemberState() = test {
        val sockets = FakeSockets()
        val owner = phone(sockets = sockets)
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val made = owner.hub.createInvite(gid)
        val member = phone()
        member.hub.join(link(made), displayName = "Thandi")
        val memberId = memberId(member, gid)
        owner.hub.setShown(gid)
        owner.hub.becameActive()
        owner.hub.socketDidOpen(gid)
        fun state(): MemberState? = owner.hub.detail(gid)?.members?.firstOrNull { it.memberId == memberId }?.state
        assertEquals(MemberState.NotSharing, state())
        share(member, gid)
        fix(member)
        val sharedAt = clock.now
        member.hub.tick(foreground = false)
        // The live entry arrives in a tick.
        val ownerPass = owner.secrets.loadAll().first().pass
        val live = GroupAPI(server).live(gid, ownerPass).live
        val liveJSON = LiveEntry.listJson(live).toString()
        owner.hub.socketDidReceive(gid, "{\"type\":\"tick\",\"serverTime\":${clock.now},\"live\":$liveJSON}")
        assertEquals(MemberState.Sharing(at = sharedAt), state())
        assertEquals(-24.99619, owner.hub.memberDetail(gid, memberId)?.live?.lat ?: 0.0, 1e-6)
        clock.advance(601)
        assertEquals(MemberState.LastSeen(at = sharedAt), state())
        // Stopped sharing: a tick with `rec: null` removes it; then the last E11 `lastLiveAt` decides.
        member.hub.setSharing(false, groupId = gid)
        member.hub.waitForBackgroundWork()
        assertNull(server.liveAt(gid, memberId))
        owner.hub.socketDidReceive(gid, "{\"type\":\"tick\",\"serverTime\":${clock.now},\"live\":[{\"memberId\":\"$memberId\",\"at\":${clock.now},\"rec\":null}]}")
        assertEquals(MemberState.NotSharing, state())
        assertNull(owner.hub.memberDetail(gid, memberId)?.live)
        owner.hub.refreshDetail(gid)
        assertEquals(MemberState.LastSeen(at = sharedAt), state())
        // One older than 24 h is never used.
        clock.advance(86_400)
        owner.hub.socketDidReceive(gid, "{\"type\":\"tick\",\"serverTime\":${clock.now},\"live\":$liveJSON}")
        assertNull(owner.hub.memberDetail(gid, memberId)?.live)
    }

    @Test
    fun testStopSharingSendsLiveNull() = test {
        val (_, member, gid) = ownerAndMember()
        share(member, gid)
        fix(member)
        point(member)
        member.hub.tick(foreground = false)
        server.clearLog()
        clock.advance(5)
        point(member)
        member.hub.setSharing(false, groupId = gid)
        member.hub.waitForBackgroundWork()
        val body = uploads().first()
        assertTrue("\"live\": null", body["live"] is JsonNull)
        // Points recorded while sharing still go.
        clock.advance(60)
        member.hub.tick(foreground = false)
        assertTrue(member.store.ownWindows(gid).all { it.sentVersion == it.version })
        assertEquals(false, member.hub.detail(gid)?.isSharing)
    }

    // Terms (§8.13)

    @Test
    fun testTermsOutdatedBothWays() = test {
        server.termsVersion = 2
        val p = phone()
        val outdated = actionError { p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro) }
        assertEquals(GroupActionError.Kind.TermsOutdated, outdated.kind)
        assertEquals("Please update SatMap to create or join groups.", outdated.message)
        assertTrue(p.secrets.loadAll().isEmpty())
        // The app had newer terms: S1 again, then the retry with the new version works.
        p.hub.acceptedTermsVersion = 2
        val gid = p.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val link = link(p.hub.createInvite(gid))
        val joiner = phone()
        assertEquals(GroupActionError.Kind.TermsOutdated, actionError { joiner.hub.join(link, displayName = "Thandi") }.kind)
        assertTrue(joiner.secrets.loadAll().isEmpty())
        joiner.hub.acceptedTermsVersion = 2
        joiner.hub.join(link, displayName = "Thandi")
        assertEquals(1, joiner.hub.groups.size)
        val redeem = requests("POST", "/invites/redeem").last().body!!
        assertEquals(2, RedeemRequest.fromJson(GroupJSON.Fields(GroupJSON.parseObject(redeem))).termsVersion)
    }

    // Pro proofs (§10.7)

    @Test
    fun testUpdateProDecisions() = test {
        val owner = phone()
        val expires = 1_825_149_600L
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = ProProof.Apple(signedTransaction = "fake:pro.yearly:$expires"))
        fun e5Count() = requests("PUT", "/pro").size
        // None holds: nothing sent.
        owner.hub.updatePro(ProProof.Apple("jws"), productId = "pro.yearly", expiresAt = expires)
        owner.hub.updatePro(ProProof.Apple("jws"), productId = "pro.yearly", expiresAt = expires + 60)
        owner.hub.updatePro(ProProof.Apple("jws"), productId = "pro.yearly", expiresAt = null)
        assertEquals(0, e5Count())
        // 4: a later known expiry.
        owner.hub.updatePro(ProProof.Apple("fake:pro.yearly:${expires + 61}"), productId = "pro.yearly", expiresAt = expires + 61)
        assertEquals(1, e5Count())
        // 3: another product.
        owner.hub.updatePro(ProProof.Apple("fake:pro.lifetime:-"), productId = "pro.lifetime", expiresAt = null)
        assertEquals(2, e5Count())
        val stored = GroupStatusDTO.decode(owner.store.group(gid)!!.statusJSON!!)!!
        assertEquals("pro.lifetime", stored.pro?.productId)
        assertNull(stored.pro?.expiresAt)
        owner.hub.updatePro(ProProof.Apple("x"), productId = "pro.lifetime", expiresAt = null)
        assertEquals(2, e5Count())
        // 2: a Pro pause.
        server.pause(gid, reason = "pro_unverified")
        owner.hub.becameActive()
        assertTrue(owner.hub.detail(gid)?.isPaused ?: false)
        owner.hub.updatePro(ProProof.Apple("fake:pro.lifetime:-"), productId = "pro.lifetime", expiresAt = null)
        assertEquals(3, e5Count())
        assertFalse("E5 resumed it", owner.hub.detail(gid)?.isPaused ?: true)
        // 5: a new Google purchase token (Android); the same token again sends nothing.
        owner.hub.updatePro(ProProof.Google(productId = "pro_lifetime", purchaseToken = "t1"), productId = "pro_lifetime", expiresAt = null)
        assertEquals(4, e5Count())
        assertEquals("t1", owner.store.group(gid)?.proSent)
        owner.hub.updatePro(ProProof.Google(productId = "pro_lifetime", purchaseToken = "t1"), productId = "pro_lifetime", expiresAt = null)
        assertEquals(4, e5Count())
        owner.hub.updatePro(ProProof.Google(productId = "pro_lifetime", purchaseToken = "t2"), productId = "pro_lifetime", expiresAt = null)
        assertEquals(5, e5Count())
        // A 402 changes nothing on the phone.
        server.proAccepted = false
        owner.hub.updatePro(ProProof.Google(productId = "pro_lifetime", purchaseToken = "t3"), productId = "pro_lifetime", expiresAt = null)
        assertEquals(6, e5Count())
        assertEquals("t2", owner.store.group(gid)?.proSent)
        server.proAccepted = true
        // 1: no Pro on record (a seeded group without it); and members never send.
        val seeded = phone()
        val sid = GroupRandom.bytes(16)
        val key = GroupRandom.bytes(32)
        val keys = GroupKeys(groupKey = key, groupId = sid)
        val pass = Base64URL.encode(GroupRandom.bytes(32))
        val ownerId = GroupRandom.id()
        server.seedGroup(groupId = keys.groupId, joinProof = Base64URL.encode(keys.joinProof),
                         profile = Base64URL.encode(GroupCrypto.seal(GroupProfile(name = "Old").json(), keys.group, GroupAAD.group(keys.groupId))),
                         ownerId = ownerId, ownerPass = pass,
                         ownerProfile = Base64URL.encode(GroupCrypto.seal(MemberProfile(name = "Sam", colour = 0).json(), keys.member, GroupAAD.member(keys.groupId, ownerId))),
                         createdAt = clock.now, pro = null)
        seeded.secrets.save(GroupSecret(groupId = keys.groupId, memberId = ownerId, pass = pass, groupKey = Base64URL.encode(key)))
        seeded.hub.start()
        server.clearLog()
        seeded.hub.updatePro(ProProof.Apple("jws"), productId = "pro.yearly", expiresAt = null)
        assertEquals(1, e5Count())
        val (_, member, _) = ownerAndMember(name = "Other")
        server.clearLog()
        member.hub.updatePro(ProProof.Apple("jws"), productId = "pro.monthly", expiresAt = null)
        assertEquals(0, e5Count())
    }

    // Names, colours, reports

    @Test
    fun testNamesColoursAndRename() = test {
        val (owner, member, gid) = ownerAndMember()
        member.hub.setDisplayName("Thandi M", colour = 5, groupId = gid)
        owner.hub.renameGroup("Kruger 2027", groupId = gid)
        owner.hub.becameActive()
        val row = owner.hub.detail(gid)!!.members.last()
        assertEquals("Thandi M", row.name)
        assertEquals("TM", row.initials)
        assertEquals(GroupPolicy.palette[5], row.colourHex)
        member.hub.becameActive()
        assertEquals("Kruger 2027", member.hub.groups.first().name)
        // A member can't rename.
        assertEquals(GroupActionError.Kind.Forbidden, actionError { member.hub.renameGroup("Mine", groupId = gid) }.kind)
        assertEquals("Please use different words for your name.", actionError { member.hub.setDisplayName("badword", colour = 1, groupId = gid) }.message)
    }

    @Test
    fun testJoinerWithAClashingColourTakesTheLowestFree() = test {
        // The owner has colour 0; joiners pick at random. Force a clash by retrying until one happens.
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        for (attempt in 0 until 40) {
            val link = link(owner.hub.createInvite(gid))
            val p = phone()
            p.hub.join(link, displayName = "M$attempt")
            p.hub.waitForBackgroundWork()
            p.hub.becameActive()
            val mine = p.hub.detail(gid)!!.members.first { it.isYou }
            val others = p.hub.detail(gid)!!.members.filter { !it.isYou }.map { it.colourHex }.toSet()
            if (others.size < GroupPolicy.palette.size) {
                assertFalse("attempt $attempt: ${mine.colourHex} clashes", others.contains(mine.colourHex))
            }
        }
    }

    @Test
    fun testReportsCarryTheDecryptedCopyWithoutPositions() = test {
        val (owner, member, gid) = ownerAndMember()
        member.hub.dropPin(groupId = gid, type = PinType.animal, animal = "Rhino", note = "Two adults", lat = -24.98, lon = 31.6)
        member.hub.waitForBackgroundWork()
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        val pinId = server.pinIds(gid).first()
        val memberId = memberId(member, gid)
        owner.hub.report(groupId = gid, target = ReportTarget.Pin(pinId = pinId), reason = ReportReason.offensive, comment = " Not nice ")
        owner.hub.report(groupId = gid, target = ReportTarget.Member(memberId = memberId), reason = ReportReason.harassment, comment = "")
        member.hub.report(groupId = gid, target = ReportTarget.Group, reason = ReportReason.other, comment = "line one\nline two")
        val reports = server.reports
        assertEquals(3, reports.size)
        assertEquals("pin", reports[0].targetKind)
        assertEquals(mapOf("type" to "animal", "animal" to "Rhino", "note" to "Two adults", "name" to "Thandi"), reports[0].content)
        assertEquals("Not nice", reports[0].comment)
        assertEquals(mapOf("name" to "Thandi"), reports[1].content)
        assertEquals(gid, reports[2].targetId)
        assertEquals(mapOf("name" to "Kruger trip"), reports[2].content)
        // Never a position (SD10): only these keys travel.
        for (r in requests("POST", "/reports")) {
            val body = jsonObject(r.body!!)
            assertEquals(setOf("targetKind", "targetId", "reason", "comment", "content"), body.keys)
            assertTrue(setOf("type", "animal", "note", "name").containsAll(body["content"]!!.jsonObject.keys))
        }
        val filtered = actionError { owner.hub.report(groupId = gid, target = ReportTarget.Member(memberId = memberId), reason = ReportReason.spam, comment = "badword") }
        assertEquals(GroupActionError.Kind.WordFilter(GroupTextField.reportComment), filtered.kind)
    }

    @Test
    fun testReceivedNamesAreMasked() = test {
        val owner = phone(words = "")
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val link = link(owner.hub.createInvite(gid))
        // This phone's list is empty, so it can type the word; the owner's phone masks it.
        val rude = phone(words = "")
        clock.advance(1)
        rude.hub.join(link, displayName = "Sipho badword")
        val strict = GroupHub(store = owner.store, secrets = owner.secrets, transport = server, sockets = null, words = WordFilter("badword\n"),
                              domain = "example.invalid", clock = { clock.now }, scope = scope)
        strict.start()
        strict.becameActive()
        assertEquals(listOf("Sam", "Sipho •••"), strict.detail(gid)?.members?.map { it.name })
    }

    // Invites

    @Test
    fun testInvitesListAndCancel() = test {
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val a = owner.hub.createInvite(gid)
        clock.advance(5)
        val b = owner.hub.createInvite(gid)
        val list = owner.hub.invites(gid)
        assertEquals(listOf(b.inviteId, a.inviteId), list.map { it.inviteId })
        assertEquals(listOf(b.link, a.link), list.map { it.link })
        assertEquals(2, server.pendingInviteCount(gid))
        owner.hub.cancelInvite(a.inviteId, groupId = gid)
        owner.hub.cancelInvite(a.inviteId, groupId = gid) // 404: done
        assertEquals(1, server.pendingInviteCount(gid))
        assertEquals(listOf(b.inviteId), owner.hub.detail(gid)?.invites?.map { it.inviteId })
        val p = phone()
        assertEquals(GroupActionError.Kind.InviteNotFound, actionError { p.hub.preview(link(a)) }.kind)
        // Expired invites leave the list.
        clock.advance(604_800)
        assertEquals(emptyList<PendingInvite>(), owner.hub.detail(gid)?.invites)
    }

    // Archives (§6.3, §8.8)

    @Test
    fun testArchivedMonthsAreDownloadedOrRelabelled() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        val memberId = memberId(member, gid)
        val w = TrailChunk.windowStart(clock.now) + 600
        point(member, at = w + 1)
        point(member, at = w + 601)
        clock.now = w + 1300
        member.hub.tick(foreground = false)
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        assertEquals(2, owner.store.chunks(gid, memberId = memberId).size)
        // The archive pass moves them; the owner had seen them before the pass: relabel, no download.
        clock.advance(10)
        server.archive(gid, before = w + 1200)
        server.clearLog()
        clock.advance(10)
        owner.hub.tick(foreground = true)
        assertTrue(requests("GET", "/archive/").isEmpty())
        assertEquals(2, owner.store.chunks(gid, memberId = memberId).size)
        // A phone that never saw them downloads the month.
        val fresh = phone()
        for (s in owner.secrets.loadAll()) fresh.secrets.save(s)
        fresh.hub.start()
        assertEquals(1, requests("GET", "/archive/").size)
        assertEquals(listOf(w, w + 600), fresh.store.chunks(gid, memberId = memberId).map { it.start })
        fresh.hub.setShown(gid)
        assertEquals(2, fresh.hub.mapContent(now = clock.now)?.trails?.first()?.segments?.first()?.size)
        assertTrue(fresh.store.ops(gid).isEmpty())
    }

    // Trails setting and thinning (§9.5)

    @Test
    fun testTrailsSettingAndSegments() = test {
        val (owner, member, gid) = ownerAndMember()
        share(member, gid)
        val w = TrailChunk.windowStart(clock.now) + 600
        point(member, at = w + 1, segment = true)
        point(member, lat = -24.9960, at = w + 61)
        // A gap of more than 600 s starts a new segment.
        point(member, lat = -24.9950, at = w + 1300)
        point(member, lat = -24.9940, at = w + 1360)
        clock.now = w + 1900
        member.hub.tick(foreground = false)
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        assertEquals(listOf(2, 2), owner.hub.mapContent(now = clock.now)?.trails?.first()?.segments?.map { it.size })
        owner.hub.setTrails(TrailsMode.off, groupId = gid)
        assertEquals(TrailsMode.off, owner.hub.detail(gid)?.trails)
        assertTrue(owner.hub.mapContent(now = clock.now)?.trails?.isEmpty() ?: false)
        owner.hub.setTrails(TrailsMode.day, groupId = gid)
        assertTrue("older than 24 h", owner.hub.mapContent(now = clock.now + 86_400 + 2000)?.trails?.isEmpty() ?: false)
        owner.hub.setTrails(TrailsMode.all, groupId = gid)
        assertEquals(2, owner.hub.mapContent(now = clock.now + 86_400 + 2000)?.trails?.first()?.segments?.size)
    }

    @Test
    fun testBigGroupsGetThinnerSimplerTrails() = test {
        val owner = phone()
        val gid = owner.hub.createGroup(name = "Trip", displayName = "Sam", pro = pro)
        val w = TrailChunk.windowStart(clock.now) + 600
        // Eleven members, each with a straight trail of 50 points: thinner lines, and simplified to their ends.
        repeat(11) { i ->
            val p = phone()
            p.hub.join(link(owner.hub.createInvite(gid)), displayName = "M$i")
            share(p, gid)
            for (k in 0 until 50) point(p, lat = -24.99 + k * 0.0001, lon = 31.6 + i * 0.01, at = w + k * 10L)
        }
        clock.now = w + 1205
        for (p in phones.drop(1)) p.hub.tick(foreground = false)
        owner.hub.setShown(gid)
        owner.hub.tick(foreground = true)
        val trails = owner.hub.mapContent(now = clock.now)!!.trails
        assertEquals(11, trails.size)
        assertTrue(trails.all { it.widthPt == 2.0 })
        assertTrue(trails.all { it.segments.single().size == 2 })
    }
}
