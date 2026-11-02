package com.jacobusoettle.satmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Invite links (docs/groups/SPEC.md §5.2): the shared `invite` and `inviteParse` vectors, as in the iPhone core. */
class InviteLinkTests {
    private val v = GroupVectors.load()

    @Test
    fun testURLsRoundTrip() {
        val link = InviteLink(token = v.inviteToken, groupKey = unhex(v.input("groupKeyHex")))!!
        assertArrayEquals(unhex(v.input("groupIdHex")), link.groupId)
        assertEquals(v.input("groupId"), link.groupIdText)
        assertEquals(v.inviteUrl, link.url(domain = v.inviteDomain))
        assertEquals(v.inviteSchemeUrl, link.schemeURL)
        assertEquals(InviteLink.Parsed.Invite(link), InviteLink.parse(link.url(domain = v.inviteDomain), domain = v.inviteDomain))
        assertEquals(InviteLink.Parsed.Invite(link), InviteLink.parse(link.schemeURL, domain = v.inviteDomain))
        // The token is the group id and the invite secret.
        assertArrayEquals(unhex(v.input("groupIdHex") + v.input("inviteSecretHex")), Base64URL.decode(v.inviteToken))
        // The key never goes into a log line.
        assertFalse(link.toString().contains(Base64URL.encode(link.groupKey)))
    }

    @Test
    fun testEveryParseCase() {
        assertEquals(21, v.inviteParse.size)
        for (c in v.inviteParse) {
            val result = InviteLink.parse(c.text, domain = v.inviteParseDomain)
            when (c.result) {
                "invite" -> {
                    if (result !is InviteLink.Parsed.Invite) {
                        fail("${c.text}: $result")
                        continue
                    }
                    assertEquals(c.text, c.token, result.link.token)
                    assertEquals(c.text, c.groupIdHex, hex(result.link.groupId))
                    assertEquals(c.text, c.groupKeyHex, hex(result.link.groupKey))
                }
                "missingKey" -> assertEquals(c.text, InviteLink.Parsed.MissingKey, result)
                "notInvite" -> assertEquals(c.text, InviteLink.Parsed.NotInvite, result)
                else -> fail("unknown result ${c.result}")
            }
        }
    }

    @Test
    fun testOtherDomainsAndValidation() {
        val key = unhex(v.input("groupKeyHex"))
        val link = InviteLink(token = v.inviteToken, groupKey = key)!!
        assertEquals(InviteLink.Parsed.Invite(link), InviteLink.parse(link.url(domain = "groups.satmap.app"), domain = "groups.satmap.app"))
        assertEquals(InviteLink.Parsed.NotInvite, InviteLink.parse(link.url(domain = "groups.satmap.app"), domain = "example.invalid"))
        assertNull(InviteLink(token = v.inviteToken.dropLast(1), groupKey = key))
        assertNull(InviteLink(token = v.inviteToken, groupKey = key.copyOf(31)))
        assertNull(InviteLink(token = v.inviteToken.dropLast(1) + "=", groupKey = key))
        // The scheme form wins when it comes first.
        val text = "first ${link.schemeURL} then ${link.url(domain = "example.invalid")}"
        assertEquals(InviteLink.Parsed.Invite(link), InviteLink.parse(text, domain = "example.invalid"))
        // Links are values: the same fields are equal, and the bytes can't be changed from outside.
        val copy = InviteLink(token = link.token, groupId = link.groupId, groupKey = link.groupKey)
        assertEquals(link, copy)
        assertEquals(link.hashCode(), copy.hashCode())
        link.groupKey[0] = 9
        assertTrue(link.groupKey.contentEquals(key))
    }
}
