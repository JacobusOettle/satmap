package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.floor

/** Test builds can be switched off (README, "Test builds can be switched off"). The test builds CI publishes on GitHub
 *  (the IPA and the APK) read a small status file; while it says "off" they lock with a full-screen notice that sends
 *  people to the App Store or Google Play, and a later "on" unlocks them. Store builds and debug builds never check.
 *
 *  The rule is here, the same in both apps (SatMap/Core/TestBuildSwitch.swift, with the same names): which builds
 *  check, reading and checking the file, what is kept between launches, and whether the app locks and with what. The
 *  app fetches the file, keeps the state and locks or unlocks (`TestBuildGate`). Both cores' tests read the shared
 *  examples in Tests/SatMapCoreTests/Fixtures/testbuild/cases.json.
 *
 *  The status file (`schema` 1; unknown fields are ignored):
 *
 *      {"schema": 1, "testBuilds": "on" | "off", "title": string, "message": string,
 *       "appStoreURL": string | null, "playStoreURL": string | null, "websiteURL": string | null, "updated": string} */
object TestBuildSwitch {
    /** The build's channel: a Gradle property through `BuildConfig` on Android (android/DESIGN.md D32), the build
     *  setting `SATMAP_CHANNEL` through the Info.plist key `SatMapChannel` on the iPhone. */
    enum class Channel {
        /** A test build (the IPA and APK on GitHub): it checks the switch. */
        test,

        /** A store build (App Store, Google Play): it never checks. */
        store;

        companion object {
            /** "store" only when the setting says exactly that; anything else, an empty setting or none is "test": a
             *  build that doesn't say it is a store build can be switched off. (Swift's `Channel(setting:)`.) */
            fun from(setting: String?): Channel = if (setting == store.name) store else test
        }
    }

    /** The platform the lock is shown on: each shows its own store's button. */
    enum class Platform { iPhone, android }

    /** The only status file format understood. */
    const val schema = 1

    /** Longer titles and messages are cut, in characters (Unicode code points, the same count in both apps). */
    const val maxTitleLength = 120
    const val maxMessageLength = 500

    /** A longer file gives no information. */
    const val maxFileBytes = 64 * 1024

    /** The lock's texts when the file gives none. */
    const val defaultTitle = "This test version of Spoor has been switched off"
    const val defaultMessage = "Get Spoor from the App Store or Google Play to keep using it."

    /** The buttons' titles. */
    const val appStoreButtonTitle = "Get it on the App Store"
    const val googlePlayButtonTitle = "Get it on Google Play"
    const val websiteButtonTitle = "Website"

    /** Test builds check at launch, on coming to the foreground and this often while in the foreground, in seconds
     *  (never in the background). */
    const val checkInterval: Double = 5 * 60.0

    /** How long one request may take, in seconds. */
    const val timeout: Double = 10.0

    /** Where the last known state is kept (SharedPreferences on Android, UserDefaults on the iPhone), as the text of a
     *  status file (`savedText`), so a build that has seen "off" starts locked, also offline. */
    const val savedKey = "testBuildStatus"

    /** Links are shown only over https to these hosts, and to github.com only under `githubPathPrefix`. */
    val linkHosts: Set<String> = setOf("apps.apple.com", "play.google.com", "github.com")
    const val githubPathPrefix = "/JacobusOettle/"

    /** What a status file says, checked: whether test builds are on, and the lock's texts and links as the file gives
     *  them (plain text, cut to length; only allowed links). Null texts and links: the file gives none. */
    data class Status(
        val on: Boolean,
        val title: String? = null,
        val message: String? = null,
        val appStoreURL: String? = null,
        val playStoreURL: String? = null,
        val websiteURL: String? = null,
    )

    /** A button on the lock: what it says and the link it opens. */
    data class Button(val kind: Kind, val title: String, val url: String) {
        enum class Kind { appStore, googlePlay, website }
    }

    /** What the lock shows. */
    data class Lock(val title: String, val message: String, val buttons: List<Button>)

    /** The walkthroughs' screenshot of the lock (an instrumentation hook on Android, a test-only launch argument on the
     *  iPhone): "off" with sample texts and links. Never saved, never fetched. */
    val sample = Status(
        on = false,
        title = defaultTitle,
        message = "Thanks for testing Spoor. Get Spoor from the App Store or Google Play to keep using it.",
        appStoreURL = "https://apps.apple.com/app/satmap/id0000000000",
        playStoreURL = "https://play.google.com/store/apps/details?id=com.koonap.spoor",
        websiteURL = "https://github.com/JacobusOettle/satmap",
    )

    // Which builds check

    /** Whether a build checks the switch at all: test builds that aren't debug builds. */
    fun checks(channel: Channel, isDebugBuild: Boolean): Boolean = channel == Channel.test && !isDebugBuild

    // Fetching

    /** The address to fetch: the status file's, with `t=<unix seconds>` added to its query so that no cache on the way
     *  answers with an old copy. `now` is in seconds since 1970. */
    fun requestURL(base: String, now: Double): String {
        val seconds = floor(now).toLong()
        return base + (if ('?' in base) "&" else "?") + "t=$seconds"
    }

    /** The requests' User-Agent: the app and its version, nothing else (no device or user identifier is ever sent). */
    fun userAgent(version: String): String = "Spoor/$version"

    // Reading the file

    /** Reads a status file. Null when it gives no information (the last known state is then kept): not a JSON object,
     *  longer than `maxFileBytes`, `schema` not the number 1, or `testBuilds` neither "on" nor "off". Unknown fields are
     *  ignored; a text or link that isn't a string, or a link that isn't allowed (`allowedLink`), counts as not given.
     *  Never throws. */
    fun parse(data: ByteArray): Status? {
        if (data.size > maxFileBytes) return null
        val obj = jsonObject(data) ?: return null
        // A number (`StrictJson` let only JSON's numbers, true, false and null through unquoted).
        val version = (obj["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        if (version != schema.toDouble()) return null
        val state = string(obj, "testBuilds")
        if (state != "on" && state != "off") return null
        return Status(
            on = state == "on",
            title = clean(string(obj, "title"), maxTitleLength, multiline = false),
            message = clean(string(obj, "message"), maxMessageLength, multiline = true),
            appStoreURL = allowedLink(string(obj, "appStoreURL")),
            playStoreURL = allowedLink(string(obj, "playStoreURL")),
            websiteURL = allowedLink(string(obj, "websiteURL")),
        )
    }

    /** A status as the text of a status file, to keep (`savedKey`); `parse` reads it back unchanged. The keys are
     *  sorted and null texts and links left out, as the Swift's encoder writes it. */
    fun savedText(status: Status): String = buildJsonObject {
        status.appStoreURL?.let { put("appStoreURL", it) }
        status.message?.let { put("message", it) }
        status.playStoreURL?.let { put("playStoreURL", it) }
        put("schema", schema)
        put("testBuilds", if (status.on) "on" else "off")
        status.title?.let { put("title", it) }
        status.websiteURL?.let { put("websiteURL", it) }
    }.toString()

    /** The kept state read back: null when nothing is kept, or it can't be read (as if never fetched). */
    fun saved(text: String?): Status? = text?.let { parse(it.encodeToByteArray()) }

    /** Plain text: control characters removed (line breaks and tabs become spaces in a title; in a message line breaks
     *  stay, as "\n"), spaces and line breaks at both ends trimmed, and cut to `maxLength` Unicode code points (spaces
     *  the cut leaves at the end trimmed too). Null when nothing is left. A lone surrogate, which a Swift string can't
     *  hold and a file can't bring (`parse` refuses "\ud800"), is removed too: it could be neither shown nor saved. */
    fun clean(text: String?, maxLength: Int, multiline: Boolean): String? {
        if (text == null) return null
        val plain = StringBuilder(text.length)
        var afterCR = false
        var i = 0
        while (i < text.length) {
            val s = text.codePointAt(i)
            i += Character.charCount(s)
            val second = s == LF && afterCR // the second half of "\r\n"
            afterCR = s == CR
            when {
                second -> {}
                s == CR || s == LF -> plain.append(if (multiline) '\n' else ' ')
                s == TAB -> plain.append(' ')
                Character.getType(s) == Character.CONTROL.toInt() -> {}
                Character.getType(s) == Character.SURROGATE.toInt() -> {}
                else -> plain.appendCodePoint(s)
            }
        }
        val trimmed = trimmed(plain.toString())
        val cut = trimmed(prefix(trimmed, maxLength))
        return cut.ifEmpty { null }
    }

    /** The link as given when the lock may show it, else null: only the characters a URL may hold (RFC 3986: letters,
     *  digits and `-._~:/?#[]@!$&'()*+,;=%`, so no spaces or backslashes), https (any case), no user name, password or
     *  port, the host exactly one of `linkHosts` (any case), on github.com a path starting with `githubPathPrefix`, and
     *  no "." or ".." path segment (also percent-encoded), which could lead somewhere else once resolved. */
    fun allowedLink(text: String?): String? {
        if (text == null || text.isEmpty() || !text.all(::isURLCharacter)) return null
        val c = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!"https".equals(c.scheme, ignoreCase = true) || c.rawUserInfo != null || c.port != -1) return null
        val host = c.host?.lowercase() ?: return null
        if (host !in linkHosts) return null
        if (host == "github.com" && !(c.rawPath ?: "").startsWith(githubPathPrefix)) return null
        val path = c.path ?: ""
        if (path.split('/').any { it == "." || it == ".." } || '\\' in path) return null
        return text
    }

    private fun isURLCharacter(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~:/?#[]@!$&'()*+,;=%"

    // The decision

    /** The state to keep after a check: what was fetched when it gives information, else what was known before (null:
     *  never fetched, which counts as on). */
    fun known(saved: Status?, fetched: Status?): Status? = fetched ?: saved

    /** Whether the build locks, and with what. Only a build that checks (`checks`) with "off" known locks; never
     *  fetched counts as on. The title and message are the file's or the defaults; the buttons are for the links the
     *  file gives that this platform shows, in this order: the App Store (iPhone) or Google Play (Android), then the
     *  website (both). */
    fun lock(channel: Channel, isDebugBuild: Boolean, status: Status?, platform: Platform): Lock? {
        if (!checks(channel, isDebugBuild) || status == null || status.on) return null
        val buttons = ArrayList<Button>()
        when (platform) {
            Platform.iPhone -> status.appStoreURL?.let { buttons += Button(Button.Kind.appStore, appStoreButtonTitle, it) }
            Platform.android -> status.playStoreURL?.let { buttons += Button(Button.Kind.googlePlay, googlePlayButtonTitle, it) }
        }
        status.websiteURL?.let { buttons += Button(Button.Kind.website, websiteButtonTitle, it) }
        return Lock(status.title ?: defaultTitle, status.message ?: defaultMessage, buttons)
    }

    // JSON

    private const val TAB = '\t'.code
    private const val LF = '\n'.code
    private const val CR = '\r'.code

    /** The top-level object of a strict JSON text in UTF-8 (a leading byte-order mark skipped), or null. Strict as the
     *  Swift decoder is (`StrictJson`): kotlinx's reader also takes unquoted words and raw control characters in
     *  strings, so a hand-edited file with a line break typed inside the message would lock one app and not the other. */
    private fun jsonObject(data: ByteArray): JsonObject? {
        val start = if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) 3 else 0
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, start, data.size - start))
                .toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        if (!StrictJson(text).isValid()) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Swift's `.whitespacesAndNewlines`: space separators, tab, the line breaks U+000A…U+000D, U+0085, U+2028, U+2029. */
    private fun isSpaceOrNewline(c: Char): Boolean =
        Character.getType(c) == Character.SPACE_SEPARATOR.toInt() || c in '\u0009'..'\u000D' || c == '\u0085' ||
            c == '\u2028' || c == '\u2029'

    private fun trimmed(s: String): String = s.trim(::isSpaceOrNewline)

    /** The first `n` code points of `s`. */
    private fun prefix(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
}

/** Whether a text is one JSON value exactly as RFC 8259 writes it, as Swift's JSONDecoder reads it (`TestBuildSwitch`
 *  reads the file with kotlinx only after this): only `true`, `false`, `null` and numbers unquoted, no control
 *  characters (a typed tab or line break) inside a string, only the escapes JSON has, no lone surrogate escape, nothing
 *  after the value, and at most 512 arrays and objects deep, the Swift parser's limit. */
private class StrictJson(private val s: String) {
    private var i = 0

    fun isValid(): Boolean {
        space()
        if (!value(depth = 0)) return false
        space()
        return i == s.length
    }

    private fun space() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun value(depth: Int): Boolean {
        if (i >= s.length) return false
        return when (s[i]) {
            '{' -> container(depth + 1, close = '}', members = true)
            '[' -> container(depth + 1, close = ']', members = false)
            '"' -> string()
            't' -> word("true")
            'f' -> word("false")
            'n' -> word("null")
            else -> number()
        }
    }

    /** An object (`members`: its entries are "key": value) or an array, from its opening bracket. */
    private fun container(depth: Int, close: Char, members: Boolean): Boolean {
        if (depth > MAX_DEPTH) return false
        i++
        space()
        if (i < s.length && s[i] == close) {
            i++
            return true
        }
        while (true) {
            if (members) {
                if (i >= s.length || s[i] != '"' || !string()) return false
                space()
                if (i >= s.length || s[i] != ':') return false
                i++
                space()
            }
            if (!value(depth)) return false
            space()
            if (i >= s.length) return false
            when (s[i++]) {
                ',' -> space()
                close -> return true
                else -> return false
            }
        }
    }

    /** A string, from its opening quote. Raw text came from strict UTF-8, so its surrogates are always pairs. */
    private fun string(): Boolean {
        i++
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return true
                c == '\\' -> if (!escape()) return false
                c < ' ' -> return false
            }
        }
        return false
    }

    private fun escape(): Boolean {
        if (i >= s.length) return false
        return when (s[i++]) {
            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
            'u' -> when (hex4()) {
                null -> false
                in 0xD800..0xDBFF -> s.startsWith("\\u", i) && run {
                    i += 2
                    hex4() in 0xDC00..0xDFFF
                }
                in 0xDC00..0xDFFF -> false
                else -> true
            }
            else -> false
        }
    }

    private fun hex4(): Int? {
        if (i + 4 > s.length) return null
        var v = 0
        repeat(4) {
            val c = s[i++]
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            v = v * 16 + d
        }
        return v
    }

    private fun word(w: String): Boolean {
        if (!s.startsWith(w, i)) return false
        i += w.length
        return true
    }

    private fun number(): Boolean {
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) return false
        when (s[i]) {
            '0' -> i++
            in '1'..'9' -> digits()
            else -> return false
        }
        if (i < s.length && s[i] == '.') {
            i++
            if (!digits()) return false
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (!digits()) return false
        }
        return true
    }

    private fun digits(): Boolean {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        return i > start
    }

    private companion object {
        const val MAX_DEPTH = 512
    }
}
