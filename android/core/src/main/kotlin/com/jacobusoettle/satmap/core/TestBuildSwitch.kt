package com.jacobusoettle.satmap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** The remote on/off switch for SatMap's test builds (the IPA and APK handed to testers from GitHub releases; never
 *  the App Store / Google Play builds, nor local debug builds). The owner flips it by rewriting a small status file
 *  (`test-builds.json` on the `app-status` branch, or a Cloudflare mirror). When it reads `"off"`, every test build
 *  that is online shows a full-screen notice pointing people to the stores and stops its background work; `"on"`
 *  unlocks them again. Store builds set the `store` channel and never check.
 *
 *  This type is the whole decision, with no app or platform in it, so the iPhone (`TestBuildSwitch.swift`) and Android
 *  do the same thing: it parses and validates a fetched status file, and turns a build's channel, its saved last-known
 *  state and a fresh fetch (which may have failed) into "locked or not", with the texts and links to show. The app
 *  around it only fetches, saves and draws. The shared examples both apps' tests read are
 *  Tests/SatMapCoreTests/Fixtures/testbuild/cases.json. */
object TestBuildSwitch {
    /** Which kind of build this is, set at build time (DESIGN.md row "Test build switch"). A Release build with no
     *  explicit channel is `test` (the safer default: a store build that forgot to set it would then lock, which is
     *  noticed at once, rather than a test build that never locks). Debug builds never make a `Channel` at all: they
     *  never check and never lock. */
    enum class Channel {
        test,
        store;

        companion object {
            /** The channel a build-time string names (`SATMAP_CHANNEL` / the Info.plist key): `store` only for an
             *  exact, case-insensitive "store"; anything else, empty or missing, is `test`. */
            fun from(raw: String?): Channel = if (raw?.trim()?.lowercase() == "store") store else test
        }
    }

    /** This parser understands schema 1 only. A file with any other schema counts as no information. */
    const val schema = 1

    /** The longest a title and a message may be; longer is cut (the switch workflow caps them too). */
    const val maxTitleLength = 120
    const val maxMessageLength = 500

    /** Shown when the file gave no title or message of its own. */
    const val defaultTitle = "This test version of SatMap has been switched off"
    const val defaultMessage = "Get SatMap from the App Store or Google Play to keep using it."

    /** The validated contents of a status file, and the state a build saves between launches (UserDefaults /
     *  SharedPreferences) so a build that has seen "off" stays locked before the next fetch answers, even offline.
     *  `title` and `message` are already trimmed and cut; a link is kept only when it passed [allowedLink], else null. */
    data class Status(
        /** `testBuilds` was "off". */
        val locked: Boolean,
        /** The file's title, cut to [maxTitleLength]; "" when it gave none (the default is filled in at [decide]). */
        val title: String,
        /** The file's message, cut to [maxMessageLength]; "" when it gave none. */
        val message: String,
        val appStoreURL: String?,
        val playStoreURL: String?,
        val websiteURL: String?,
    ) {
        companion object {
            /** A build that has never fetched anything acts as "on" (unlocked). */
            val unlocked = Status(locked = false, title = "", message = "", appStoreURL = null, playStoreURL = null, websiteURL = null)
        }
    }

    /** The full-screen lock the app shows while a test build is off: the title and message with the defaults filled
     *  in, and the links the file allowed (the app shows a store button for its own platform, and a website button on
     *  both). `null` from [decide] means the build is not locked. */
    data class Lock(
        val title: String,
        val message: String,
        val appStoreURL: String?,
        val playStoreURL: String?,
        val websiteURL: String?,
    )

    /** Parses and validates one fetched status file. Returns `null` for "no information" — anything unreadable, not an
     *  object, a `schema` other than [schema], or a `testBuilds` other than "on"/"off" — so the caller keeps its last
     *  known state. Unknown fields are ignored; a missing title or message is "" and a missing or disallowed link is
     *  null. Never throws. */
    fun parse(text: String?): Status? {
        val body = text ?: return null
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return null
        }
        val obj = root as? JsonObject ?: return null
        if (intField(obj, "schema") != schema) return null
        val locked = when (stringField(obj, "testBuilds")) {
            "on" -> false
            "off" -> true
            else -> return null
        }
        return Status(
            locked = locked,
            title = (stringField(obj, "title") ?: "").take(maxTitleLength),
            message = (stringField(obj, "message") ?: "").take(maxMessageLength),
            appStoreURL = allowedLink(stringField(obj, "appStoreURL")),
            playStoreURL = allowedLink(stringField(obj, "playStoreURL")),
            websiteURL = allowedLink(stringField(obj, "websiteURL")),
        )
    }

    /** Folds a fresh fetch into the saved state: a readable file ([fetched] not null) replaces it; an unreadable one
     *  (null) keeps what was saved. What the app saves after every fetch. */
    fun merged(saved: Status, fetched: Status?): Status = fetched ?: saved

    /** channel + saved state + fresh fetch -> locked or not. A `store` build is never locked, whatever the file says.
     *  Otherwise the fresh fetch wins when it is readable, else the saved state stands; a locked state becomes a
     *  [Lock] with the defaults filled in. [fetched] null means the fetch gave no new information. */
    fun decide(channel: Channel, saved: Status, fetched: Status?): Lock? {
        if (channel == Channel.store) return null
        val effective = merged(saved, fetched)
        if (!effective.locked) return null
        return Lock(
            title = effective.title.ifEmpty { defaultTitle },
            message = effective.message.ifEmpty { defaultMessage },
            appStoreURL = effective.appStoreURL,
            playStoreURL = effective.playStoreURL,
            websiteURL = effective.websiteURL,
        )
    }

    /** A link is kept only when it is `https` and on `apps.apple.com`, `play.google.com`, or `github.com` with a path
     *  under `/JacobusOettle/`; anything else (including `http`) is ignored. The host is compared ignoring case. */
    fun allowedLink(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: ""
        val ok = when (host) {
            "apps.apple.com", "play.google.com" -> true
            "github.com" -> path.startsWith("/JacobusOettle/")
            else -> false
        }
        return if (ok) raw else null
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intField(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}
