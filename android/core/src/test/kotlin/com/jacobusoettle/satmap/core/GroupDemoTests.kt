package com.jacobusoettle.satmap.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.hypot

/** Demo mode (docs/groups/SPEC.md §8.15): the seed the walkthroughs and screenshots show, as the iPhone core's
 *  GroupDemoTests. */
class GroupDemoTests {
    private val now = 1_793_613_600L
    private val a = GroupDemo.anchor
    private val stores = mutableListOf<GroupStore>()
    private lateinit var scope: CoroutineScope

    @After
    fun tearDown() {
        for (s in stores) s.close()
    }

    private fun test(body: suspend () -> Unit) = runBlocking {
        scope = this
        body()
    }

    private class Demo(val hub: GroupHub, val server: FakeGroupServer, val clock: TestClock)

    private suspend fun demo(): Demo {
        val clock = TestClock(now)
        val store = GroupStore(":memory:", JdbcSqlDriver).also { stores.add(it) }
        val (hub, server) = GroupDemo.makeHub(store = store, words = WordFilter("badword\n"), clock = { clock.now }, scope = scope)
        hub.start()
        hub.becameActive()
        return Demo(hub, server, clock)
    }

    @Test
    fun testTheGroupsList() = test {
        val hub = demo().hub
        val groups = hub.groups
        assertEquals(listOf("Kruger trip", "Drone team"), groups.map { it.name })
        assertEquals(listOf(5, 3), groups.map { it.memberCount })
        assertEquals(listOf(true, false), groups.map { it.isSharing })
        assertEquals(listOf(false, true), groups.map { it.isPaused })
        assertEquals(listOf(false, false), groups.map { it.isShown })
        assertFalse(groups.any { it.hasOfferToYou })
        // Nothing of groups on the map until a group is shown (shots 01–27).
        assertNull(hub.shownGroupId)
        assertNull(hub.mapContent(now = now))
    }

    @Test
    fun testKrugerTrip() = test {
        val d = demo()
        val hub = d.hub
        val gid = hub.groups.first().groupId
        // S5 opens (step 32).
        hub.refreshDetail(gid)
        val detail = hub.detail(gid)!!
        assertEquals(GroupRole.owner, detail.yourRole)
        assertTrue(detail.hasConsent && detail.isSharing)
        assertEquals(TrailsMode.day, detail.trails)
        assertEquals(listOf("Sam", "Thandi", "Pieter", "Lerato", "Sipho"), detail.members.map { it.name })
        assertEquals(listOf(GroupRole.owner, GroupRole.admin, GroupRole.member, GroupRole.member, GroupRole.member), detail.members.map { it.role })
        assertEquals(listOf(0, 1, 2, 4, 9).map { GroupPolicy.palette[it] }, detail.members.map { it.colourHex })
        assertEquals(listOf("S", "T", "P", "L", "S"), detail.members.map { it.initials })
        assertEquals(MemberState.Sharing(at = now - 8), detail.members[1].state)
        assertEquals(MemberState.LastSeen(at = now - 1500), detail.members[4].state)
        // One pending invite, made by you, with its link on this phone.
        val invites = hub.invites(gid)
        assertEquals(1, invites.size)
        assertEquals("Sam", invites.first().createdByName)
        val link = invites.first().link
        assertTrue(link != null && InviteLink.parse(link, domain = GroupDemo.domain) is InviteLink.Parsed.Invite)

        // Shown (step 36): dots, trails and six pins.
        hub.setShown(gid)
        hub.tick(foreground = true)
        hub.waitForBackgroundWork()
        val map = hub.mapContent(now = d.clock.now)!!
        assertEquals(4, map.members.size)
        val thandi = map.members.first { it.initials == "T" }
        assertEquals(a.lat - 0.0012, thandi.position.lat, 1e-6)
        assertEquals(a.lon + 0.0010, thandi.position.lon, 1e-6)
        assertEquals(120, thandi.headingDeg)
        assertFalse(thandi.isStale)
        assertEquals(GroupPolicy.palette[1], thandi.colourHex)
        val sipho = map.members.first { it.a11yLabel.startsWith("Sipho") }
        assertTrue(sipho.isStale)
        assertNull(sipho.headingDeg)
        assertEquals("Sipho, Last seen 25 min ago", sipho.a11yLabel)
        assertEquals(listOf(30, 120, 270), map.members.mapNotNull { it.headingDeg }.sorted())
        assertEquals(4, map.trails.size)
        for (trail in map.trails) {
            assertEquals(1, trail.segments.size)
            assertGreaterThan(trail.segments[0].size, 5)
            assertEquals(3.0, trail.widthPt, 0.0)
            // The trail ends at the member's live position and starts about 0.004° away.
            val member = map.members.first { it.memberId == trail.memberId }
            val last = trail.segments[0].last()
            val first = trail.segments[0].first()
            assertEquals(member.position.lat, last.lat, 2e-6)
            assertEquals(member.position.lon, last.lon, 2e-6)
            assertEquals(0.004, hypot(first.lat - last.lat, first.lon - last.lon), 0.0002)
        }
        assertEquals(6, map.pins.size)
        assertEquals(PinType.entries.toSet(), map.pins.mapNotNull { it.knownType }.toSet())
        val kudu = map.pins.first { it.knownType == PinType.animal }
        assertEquals("Kudu · 2 h ago", kudu.label)
        assertEquals("Kudu, dropped by Thandi 2 h ago", kudu.a11yLabel)
        assertEquals(a.lat - 0.0006, kudu.position.lat, 1e-6)
        val kuduDetail = hub.pinDetail(gid, kudu.pinId)!!
        assertEquals("Three bulls", kuduDetail.note)
        assertEquals(kuduDetail.createdAt + 43_200, kuduDetail.expiresAt)
        val danger = map.pins.first { it.knownType == PinType.danger }
        assertEquals("Loose gravel on the bend", hub.pinDetail(gid, danger.pinId)?.note)
        assertEquals("Thandi", hub.pinDetail(gid, danger.pinId)?.dropperName)
        assertEquals(6, d.server.pinIds(gid).size)
        // Thandi's member screen (step 39).
        val t = hub.memberDetail(gid, thandi.memberId)!!
        assertEquals("Thandi", t.row.name)
        assertTrue(t.canRemoveAdmin && t.canMakeOwner && t.canRemove)
        assertEquals(a.lat - 0.0012, t.live?.lat ?: 0.0, 1e-6)
    }

    @Test
    fun testDotsStayFreshThroughALongWalkthrough() = test {
        val d = demo()
        val hub = d.hub
        val gid = hub.groups.first().groupId
        // Shots 01–27 take a while before the group is shown.
        d.clock.advance(40 * 60)
        hub.setShown(gid)
        hub.tick(foreground = true)
        hub.waitForBackgroundWork()
        val map = hub.mapContent(now = d.clock.now)!!
        assertEquals("only Sipho", 1, map.members.count { it.isStale })
        assertEquals("Thandi, Sharing · just now", map.members.first { it.initials == "T" }.a11yLabel)
        assertEquals("Sipho, Last seen 25 min ago", map.members.first { it.a11yLabel.startsWith("Sipho") }.a11yLabel)
        // Trails and pins are still there (within the last 24 hours, the animal sighting within 12).
        assertEquals(4, map.trails.size)
        assertEquals(6, map.pins.size)
    }

    @Test
    fun testDroneTeamIsPaused() = test {
        val hub = demo().hub
        val gid = hub.groups.last().groupId
        val detail = hub.detail(gid)!!
        assertTrue(detail.isPaused)
        assertEquals(PausedReason.proEnded, detail.pausedReason)
        assertEquals(now + 27 * 86_400, detail.deleteAt)
        assertEquals(GroupRole.member, detail.yourRole)
        assertEquals(listOf("Pieter", "Ayesha", "Sam"), detail.members.map { it.name })
        assertEquals(listOf(GroupRole.owner, GroupRole.admin, GroupRole.member), detail.members.map { it.role })
        assertFalse(detail.isShown)
        // Step 40: shown, it stays paused on the map.
        hub.setShown(gid)
        assertEquals(gid, hub.mapContent(now = now)?.groupId)
        assertThrows(GroupActionError::class.java) { hub.dropPin(groupId = gid, type = PinType.water, animal = null, note = null, lat = a.lat, lon = a.lon) }
    }

    @Test
    fun testInviteLinks() = test {
        val hub = demo().hub
        val weekend = InviteLink(token = Base64URL.encode(GroupDemo.sequence(0x10, 16) + GroupDemo.sequence(0x20, 16)), groupKey = GroupDemo.sequence(0x30, 32))!!
        assertEquals(weekend.url("example.invalid"), GroupDemo.inviteLink)
        assertEquals("https://example.invalid/j/EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8#MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8", GroupDemo.inviteLink)
        val link = (InviteLink.parse(GroupDemo.inviteLink, domain = GroupDemo.domain) as? InviteLink.Parsed.Invite)?.link
        val link2 = (InviteLink.parse(GroupDemo.inviteLink2, domain = GroupDemo.domain) as? InviteLink.Parsed.Invite)?.link
        if (link == null || link2 == null) return@test fail("demo links don't parse")
        assertArrayEquals(GroupDemo.sequence(0x60, 16), link2.groupId)
        assertArrayEquals(GroupDemo.sequence(0x80, 32), link2.groupKey)
        assertEquals(InviteLink.Parsed.Invite(link2), InviteLink.parse(link2.schemeURL, domain = GroupDemo.domain))
        val preview2 = hub.preview(link2)
        assertEquals("Braai club", preview2.name)
        assertEquals("Bongani", preview2.inviterName)
        // Steps 34–35: paste the link, preview, join; the joined group becomes the shown group.
        val preview = hub.preview(link)
        assertEquals("Weekend ride", preview.name)
        assertEquals("Nomsa", preview.inviterName)
        assertEquals(3, preview.memberCount)
        assertFalse(preview.isPaused)
        assertEquals(now - 7200 + 604_800, preview.expiresAt)
        val gid = hub.join(link, displayName = GroupDemo.displayName)
        assertEquals(gid, hub.shownGroupId)
        assertTrue(hub.needsConsent(gid))
        assertEquals(listOf("Kruger trip", "Drone team", "Weekend ride"), hub.groups.map { it.name })
        assertEquals(listOf("Nomsa", "Lindiwe", "Johan", "Sam"), hub.detail(gid)?.members?.map { it.name })
        hub.recordConsent(gid)
        hub.setSharing(true, groupId = gid)
        assertTrue(hub.detail(gid)?.isSharing ?: false)
    }

    @Test
    fun testCreatingAGroupWithTheFakePro() = test {
        val hub = demo().hub
        val gid = hub.createGroup(name = "Weekend braai", displayName = "Sam", pro = GroupDemo.proProof(now = now))
        assertEquals("Weekend braai", hub.detail(gid)?.name)
        val made = hub.createInvite(gid)
        assertTrue(made.link.startsWith("https://example.invalid/j/"))
    }

    @Test
    fun testTheSeedIsTheSameEveryRun() {
        val s1 = GroupDemo.seed(server = FakeGroupServer(now = { 1 }), now = now)
        val s2 = GroupDemo.seed(server = FakeGroupServer(now = { 2 }), now = now + 999)
        assertEquals(s1.secrets, s2.secrets)
        assertEquals(s1.krugerTrip, s2.krugerTrip)
        assertEquals(Base64URL.encode(GroupDemo.sequence(0x10, 16)), s1.weekendRide)
        assertEquals(Base64URL.encode(GroupDemo.sequence(0x60, 16)), s1.braaiClub)
        assertEquals(s1.madeInvite.link, s2.madeInvite.link)
        assertEquals(setOf(s1.krugerTrip, s1.droneTeam), s1.secrets.map { it.groupId }.toSet())
        assertNotEquals(s1.krugerTrip, s1.droneTeam)
    }
}
