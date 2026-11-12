package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared examples in Tests/SatMapCoreTests/Fixtures/testbuild/cases.json, which the Swift core's tests
 *  (TestBuildSwitchTests.swift) read too, so the two cores agree. */
class TestBuildSwitchTests {
    private val cases: JsonObject by lazy { Json.parseToJsonElement(fixture("testbuild/cases.json").decodeToString()).jsonObject }

    /** A string from the fixture, null for null or a missing key. */
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A checked status as the fixture writes it (null: no information). */
    private fun status(value: JsonElement?): TestBuildSwitch.Status? {
        val d = value as? JsonObject ?: return null
        val state = assertNotNull(text(d["testBuilds"]))
        assertTrue(state == "on" || state == "off", state)
        return TestBuildSwitch.Status(
            on = state == "on", title = text(d["title"]), message = text(d["message"]),
            appStoreURL = text(d["appStoreURL"]), playStoreURL = text(d["playStoreURL"]), websiteURL = text(d["websiteURL"]),
        )
    }

    private fun lock(value: JsonElement?): TestBuildSwitch.Lock? {
        val d = value as? JsonObject ?: return null
        val buttons = d.getValue("buttons").jsonArray.map { b ->
            val o = b.jsonObject
            TestBuildSwitch.Button(
                kind = TestBuildSwitch.Button.Kind.valueOf(assertNotNull(text(o["kind"]))),
                title = assertNotNull(text(o["title"])),
                url = assertNotNull(text(o["url"])),
            )
        }
        return TestBuildSwitch.Lock(assertNotNull(text(d["title"])), assertNotNull(text(d["message"])), buttons)
    }

    private fun list(key: String): List<JsonObject> = cases.getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun defaults() {
        val d = cases.getValue("defaults").jsonObject
        assertEquals(text(d["title"]), TestBuildSwitch.defaultTitle)
        assertEquals(text(d["message"]), TestBuildSwitch.defaultMessage)
        assertEquals(d.getValue("maxTitleLength").jsonPrimitive.intOrNull, TestBuildSwitch.maxTitleLength)
        assertEquals(d.getValue("maxMessageLength").jsonPrimitive.intOrNull, TestBuildSwitch.maxMessageLength)
        assertEquals(d.getValue("maxFileBytes").jsonPrimitive.intOrNull, TestBuildSwitch.maxFileBytes)
        assertEquals(d.getValue("checkIntervalSeconds").jsonPrimitive.doubleOrNull, TestBuildSwitch.checkInterval)
        assertEquals(d.getValue("timeoutSeconds").jsonPrimitive.doubleOrNull, TestBuildSwitch.timeout)
        assertEquals(text(d["savedKey"]), TestBuildSwitch.savedKey)
        val ua = d.getValue("userAgent").jsonObject
        assertEquals(text(ua["value"]), TestBuildSwitch.userAgent(assertNotNull(text(ua["version"]))))
        val sample = assertNotNull(status(cases["sample"]))
        assertEquals(sample, TestBuildSwitch.sample)
    }

    @Test
    fun files() {
        val files = list("files")
        assertTrue(files.size > 20)
        for (c in files) {
            val name = assertNotNull(text(c["name"]))
            // 'text' as it is, or 'file' written out compactly (as JSONSerialization writes it in the Swift test).
            val data = (text(c["text"]) ?: c.getValue("file").toString()).encodeToByteArray()
            val parsed = TestBuildSwitch.parse(data)
            assertEquals(status(c["expect"]), parsed, name)
            // What is kept reads back unchanged.
            if (parsed != null) assertEquals(parsed, TestBuildSwitch.saved(TestBuildSwitch.savedText(parsed)), name)
        }
    }

    @Test
    fun links() {
        val links = list("links")
        assertTrue(links.size > 20)
        for (l in links) {
            val url = assertNotNull(text(l["url"]))
            val allowed = assertNotNull(l.getValue("allowed").jsonPrimitive.booleanOrNull)
            assertEquals(if (allowed) url else null, TestBuildSwitch.allowedLink(url), url)
            // The same in a status file, for each link.
            for (key in listOf("appStoreURL", "playStoreURL", "websiteURL")) {
                val file = buildJsonObject {
                    put("schema", 1)
                    put("testBuilds", "off")
                    put(key, url)
                }.toString().encodeToByteArray()
                val s = assertNotNull(TestBuildSwitch.parse(file), url)
                val got = when (key) {
                    "appStoreURL" -> s.appStoreURL
                    "playStoreURL" -> s.playStoreURL
                    else -> s.websiteURL
                }
                assertEquals(if (allowed) url else null, got, "$key: $url")
            }
        }
        assertNull(TestBuildSwitch.allowedLink(null))
    }

    @Test
    fun decisions() {
        val decisions = list("decisions")
        assertTrue(decisions.size > 8)
        for (d in decisions) {
            val name = assertNotNull(text(d["name"]))
            val channel = TestBuildSwitch.Channel.valueOf(assertNotNull(text(d["channel"])))
            val debug = assertNotNull(d.getValue("debugBuild").jsonPrimitive.booleanOrNull)
            val expect = d.getValue("expect").jsonObject
            val known = TestBuildSwitch.known(saved = status(d["saved"]), fetched = status(d["fetched"]))
            assertEquals(status(expect["known"]), known, name)
            assertEquals(
                lock(expect["iPhone"]),
                TestBuildSwitch.lock(channel, isDebugBuild = debug, status = known, platform = TestBuildSwitch.Platform.iPhone),
                "$name (iPhone)",
            )
            assertEquals(
                lock(expect["android"]),
                TestBuildSwitch.lock(channel, isDebugBuild = debug, status = known, platform = TestBuildSwitch.Platform.android),
                "$name (Android)",
            )
        }
    }

    @Test
    fun channels() {
        for (c in list("channels")) {
            val setting = text(c["setting"])
            val channel = TestBuildSwitch.Channel.from(setting)
            assertEquals(text(c["channel"]), channel.name, setting.toString())
            assertEquals(c.getValue("checks").jsonPrimitive.booleanOrNull, TestBuildSwitch.checks(channel, isDebugBuild = false), setting.toString())
            assertFalse(TestBuildSwitch.checks(channel, isDebugBuild = true), "A debug build never checks")
        }
    }

    @Test
    fun requestURLs() {
        for (u in list("requestUrls")) {
            val now = assertNotNull(u.getValue("now").jsonPrimitive.doubleOrNull)
            assertEquals(text(u["url"]), TestBuildSwitch.requestURL(assertNotNull(text(u["base"])), now))
        }
    }

    @Test
    fun tooLongAFileGivesNoInformation() {
        val padding = " ".repeat(TestBuildSwitch.maxFileBytes)
        val file = "{\"schema\": 1, \"testBuilds\": \"off\"}$padding".encodeToByteArray()
        assertNull(TestBuildSwitch.parse(file))
        assertNotNull(TestBuildSwitch.parse(file.copyOf(TestBuildSwitch.maxFileBytes)))
    }

    @Test
    fun nothingKeptOrUnreadableCountsAsNeverFetched() {
        assertNull(TestBuildSwitch.saved(null))
        assertNull(TestBuildSwitch.saved(""))
        assertNull(TestBuildSwitch.saved("off"))
        assertNull(
            TestBuildSwitch.lock(TestBuildSwitch.Channel.test, isDebugBuild = false, status = TestBuildSwitch.saved("{"),
                                 platform = TestBuildSwitch.Platform.iPhone),
        )
        // The kept text is a status file.
        val kept = TestBuildSwitch.savedText(TestBuildSwitch.Status(on = false, title = "Off"))
        assertEquals("""{"schema":1,"testBuilds":"off","title":"Off"}""", kept)
    }

    @Test
    fun theSampleLocksWithEveryButtonOfItsPlatform() {
        val iPhone = TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, TestBuildSwitch.sample, TestBuildSwitch.Platform.iPhone)
        assertEquals(TestBuildSwitch.defaultTitle, iPhone?.title)
        assertEquals(listOf(TestBuildSwitch.Button.Kind.appStore, TestBuildSwitch.Button.Kind.website), iPhone?.buttons?.map { it.kind })
        assertEquals(listOf("Get it on the App Store", "Website"), iPhone?.buttons?.map { it.title })
        val android = TestBuildSwitch.lock(TestBuildSwitch.Channel.test, false, TestBuildSwitch.sample, TestBuildSwitch.Platform.android)
        assertEquals(listOf(TestBuildSwitch.Button.Kind.googlePlay, TestBuildSwitch.Button.Kind.website), android?.buttons?.map { it.kind })
        assertEquals(listOf("Get it on Google Play", "Website"), android?.buttons?.map { it.title })
        // Every sample link passes the link check.
        for (url in listOf(TestBuildSwitch.sample.appStoreURL, TestBuildSwitch.sample.playStoreURL, TestBuildSwitch.sample.websiteURL)) {
            assertNotNull(TestBuildSwitch.allowedLink(url))
        }
    }

    // Android only: what kotlinx's reader and Kotlin's number parsing would take that the Swift decoder doesn't. Each is
    // no information, as on the iPhone, so the two apps lock and unlock on the same files.

    private fun file(text: String) = TestBuildSwitch.parse(text.encodeToByteArray())

    @Test
    fun onlyStrictJsonIsRead() {
        assertEquals(TestBuildSwitch.Status(on = false), file("""{"schema": 1, "testBuilds": "off"}"""))
        assertNotNull(file("""{"schema": 1.0, "testBuilds": "off"}"""))
        assertNotNull(file("""{"schema": 1e0, "testBuilds": "off", "x": [-0.5e+2, true, false, null, {}, []]}"""))
        assertNotNull(file(" \r\n\t{\"schema\": 1, \"testBuilds\": \"off\", \"title\": \"\\u00e9\\ud83d\\ude00\\/\"}\n "))
        val notJson = listOf(
            // Numbers only as JSON writes them (Kotlin's toDouble takes 1d, 0x1 and Infinity).
            """{"schema": 1d, "testBuilds": "off"}""",
            """{"schema": 0x1, "testBuilds": "off"}""",
            """{"schema": 01, "testBuilds": "off"}""",
            """{"schema": 1., "testBuilds": "off"}""",
            """{"schema": +1, "testBuilds": "off"}""",
            """{"schema": Infinity, "testBuilds": "off"}""",
            // Unquoted words, anywhere (kotlinx reads them as strings or literals).
            """{"schema": 1, "testBuilds": off}""",
            """{"schema": 1, "testBuilds": "off", "note": maybe}""",
            """{schema: 1, "testBuilds": "off"}""",
            """{'schema': 1, "testBuilds": "off"}""",
            // A tab or a line break typed inside a string (as editing the file on GitHub could leave).
            "{\"schema\": 1, \"testBuilds\": \"off\", \"message\": \"Line one\nLine two\"}",
            "{\"schema\": 1, \"testBuilds\": \"off\", \"title\": \"a\tb\"}",
            // Escapes JSON doesn't have, and lone surrogates.
            """{"schema": 1, "testBuilds": "off", "title": "\x41"}""",
            """{"schema": 1, "testBuilds": "off", "title": "\u00g1"}""",
            """{"schema": 1, "testBuilds": "off", "title": "Off\ud800 now"}""",
            """{"schema": 1, "testBuilds": "off", "title": "Off\udc00"}""",
            """{"schema": 1, "testBuilds": "off", "title": "\ud83dA"}""",
            // Trailing commas, something after the object, two objects.
            """{"schema": 1, "testBuilds": "off",}""",
            """{"schema": 1, "testBuilds": "off", "x": [1,]}""",
            """{"schema": 1, "testBuilds": "off"} x""",
            """{"schema": 1, "testBuilds": "off"}{}""",
        )
        for (text in notJson) assertNull(file(text), text)
    }

    @Test
    fun nestingIsLimitedAsInSwift() {
        fun nested(levels: Int) = """{"schema": 1, "testBuilds": "off", "x": ${"[".repeat(levels)}${"]".repeat(levels)}}"""
        assertNotNull(file(nested(511)), "512 levels with the file's own object")
        assertNull(file(nested(512)))
        assertNull(file(nested(20_000)), "deep nesting is refused, never a stack overflow")
    }

    @Test
    fun onlyUtf8IsRead() {
        val latin1 = byteArrayOf(*"""{"schema": 1, "testBuilds": "off", "title": """".encodeToByteArray(), 0xE9.toByte(), *"\"}".encodeToByteArray())
        assertNull(TestBuildSwitch.parse(latin1))
        // A byte-order mark is skipped.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), *"""{"schema": 1, "testBuilds": "off"}""".encodeToByteArray())
        assertEquals(TestBuildSwitch.Status(on = false), TestBuildSwitch.parse(bom))
    }

    @Test
    fun cleanRemovesALoneSurrogate() {
        // JSON can't bring one (above), but `clean` is public: such text could be neither shown nor saved.
        assertEquals("Off now", TestBuildSwitch.clean("Off\uD800 now", maxLength = 120, multiline = false))
        assertEquals("😀", TestBuildSwitch.clean("😀", maxLength = 1, multiline = false))
    }
}
