package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `TestBuildSwitch` against the shared examples both apps' tests read
 *  (Tests/SatMapCoreTests/Fixtures/testbuild/cases.json), plus a few checks of the pieces on their own. The Swift
 *  tests (SatMapCoreTests) read the same file, so the two cores agree. */
class TestBuildSwitchTests {
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(fixture("testbuild/cases.json").decodeToString()).jsonObject
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun statusOf(obj: JsonObject): TestBuildSwitch.Status = TestBuildSwitch.Status(
        locked = obj.getValue("locked").jsonPrimitive.content.toBoolean(),
        title = obj.string("title") ?: "",
        message = obj.string("message") ?: "",
        appStoreURL = obj.string("appStoreURL"),
        playStoreURL = obj.string("playStoreURL"),
        websiteURL = obj.string("websiteURL"),
    )

    @Test
    fun parseCasesFromTheSharedFixture() {
        val parse = cases.getValue("parse") as JsonArray
        assertTrue(parse.size >= 8, "the fixture should carry a good spread of parse cases")
        for (element in parse) {
            val c = element.jsonObject
            val name = c.string("name") ?: "?"
            val input = c.string("input")
            val result = TestBuildSwitch.parse(input)
            val expect = c["expect"]
            if (expect == null || expect is JsonNull) {
                assertNull(result, "parse: $name should be no information")
            } else {
                val e = statusOf(expect.jsonObject)
                assertEquals(e, result, "parse: $name")
            }
        }
    }

    @Test
    fun decideCasesFromTheSharedFixture() {
        val decide = cases.getValue("decide").let { it as kotlinx.serialization.json.JsonArray }
        assertTrue(decide.size >= 6, "the fixture should carry a good spread of decide cases")
        for (element in decide) {
            val c = element.jsonObject
            val name = c.string("name") ?: "?"
            val channel = TestBuildSwitch.Channel.from(c.string("channel"))
            val saved = statusOf(c.getValue("saved").jsonObject)
            val fetched = c["fetched"]?.takeIf { it !is JsonNull }?.let { statusOf(it.jsonObject) }
            val result = TestBuildSwitch.decide(channel, saved, fetched)
            val lock = c["lock"]
            if (lock == null || lock is JsonNull) {
                assertNull(result, "decide: $name should not lock")
            } else {
                val l = lock.jsonObject
                assertEquals(
                    TestBuildSwitch.Lock(
                        title = l.string("title") ?: "",
                        message = l.string("message") ?: "",
                        appStoreURL = l.string("appStoreURL"),
                        playStoreURL = l.string("playStoreURL"),
                        websiteURL = l.string("websiteURL"),
                    ),
                    result,
                    "decide: $name",
                )
            }
        }
    }

    @Test
    fun channelFromStringTreatsOnlyStoreAsStore() {
        assertEquals(TestBuildSwitch.Channel.store, TestBuildSwitch.Channel.from("store"))
        assertEquals(TestBuildSwitch.Channel.store, TestBuildSwitch.Channel.from("  Store "))
        assertEquals(TestBuildSwitch.Channel.store, TestBuildSwitch.Channel.from("STORE"))
        assertEquals(TestBuildSwitch.Channel.test, TestBuildSwitch.Channel.from("test"))
        assertEquals(TestBuildSwitch.Channel.test, TestBuildSwitch.Channel.from(""))
        assertEquals(TestBuildSwitch.Channel.test, TestBuildSwitch.Channel.from(null))
        assertEquals(TestBuildSwitch.Channel.test, TestBuildSwitch.Channel.from("stores"))
    }

    @Test
    fun allowedLinkRules() {
        assertEquals("https://apps.apple.com/app/id1", TestBuildSwitch.allowedLink("https://apps.apple.com/app/id1"))
        assertEquals("https://play.google.com/store/apps/details?id=x", TestBuildSwitch.allowedLink("https://play.google.com/store/apps/details?id=x"))
        assertEquals("https://github.com/JacobusOettle/satmap", TestBuildSwitch.allowedLink("https://github.com/JacobusOettle/satmap"))
        // Host compared without case.
        assertEquals("https://APPS.Apple.com/app", TestBuildSwitch.allowedLink("https://APPS.Apple.com/app"))
        // Rejected.
        assertNull(TestBuildSwitch.allowedLink("http://apps.apple.com/app"))
        assertNull(TestBuildSwitch.allowedLink("https://example.com"))
        assertNull(TestBuildSwitch.allowedLink("https://github.com/someoneelse/satmap"))
        assertNull(TestBuildSwitch.allowedLink("https://notgithub.com/JacobusOettle/x"))
        assertNull(TestBuildSwitch.allowedLink("javascript:alert(1)"))
        assertNull(TestBuildSwitch.allowedLink(null))
        assertNull(TestBuildSwitch.allowedLink(""))
        assertNull(TestBuildSwitch.allowedLink("   "))
    }

    @Test
    fun debugBuildsNeverMakeAChannelButDefaultIsTest() {
        // A Release build with no explicit channel is the safer "test".
        assertEquals(TestBuildSwitch.Channel.test, TestBuildSwitch.Channel.from(null))
    }

    @Test
    fun mergedKeepsSavedWhenFetchFailed() {
        val saved = TestBuildSwitch.Status(locked = true, title = "Off", message = "m", appStoreURL = null, playStoreURL = null, websiteURL = null)
        assertEquals(saved, TestBuildSwitch.merged(saved, null))
        val fresh = TestBuildSwitch.Status.unlocked
        assertEquals(fresh, TestBuildSwitch.merged(saved, fresh))
    }
}
