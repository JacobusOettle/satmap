package com.jacobusoettle.satmap.app

import androidx.annotation.VisibleForTesting
import com.jacobusoettle.satmap.BuildConfig
import com.jacobusoettle.satmap.core.TestBuildSwitch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The app's side of the test-build remote switch (android/docs/spec/test-builds.md). It reads the status file
 *  (`TestBuildConfig.statusURLs`, in order), keeps the last known state in SharedPreferences so a build that has seen
 *  "off" stays locked before the next fetch answers — even offline — and turns the shared `TestBuildSwitch`'s decision
 *  into a `lock` the UI shows and `AppModel` acts on (pausing downloads, recording and sharing). The iPhone's
 *  `TestBuildGate` (Config.swift / SatMapApp.swift) does the same.
 *
 *  A store build (`channel == store`) is never locked, and a debug build (`channel == null`) never checks at all.
 *  Checks run at launch, on each return to the foreground and every five minutes while in the foreground; nothing runs
 *  in the background (`AppModel` calls `foregrounded`/`backgrounded`). No identifiers are sent: a plain GET with a
 *  `SatMap/<version>` User-Agent and normal HTTPS certificate checks, with `?t=<unix seconds>` to defeat caches.
 *
 *  Main thread only; the fetch itself suspends on `Dispatchers.IO`. */
class TestBuildGate(
    private val channel: TestBuildSwitch.Channel? = TestBuildConfig.channel,
    private val urls: List<String> = TestBuildConfig.statusURLs,
    private val userAgent: String = "SatMap/${BuildConfig.VERSION_NAME}",
    /** Reads one URL's body, or null when it can't be read. Tests give their own. */
    private val fetch: suspend (url: String) -> String? = DefaultFetch(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Where `fetch` runs, off the main thread. Tests pass an immediate dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _lock = MutableStateFlow<TestBuildSwitch.Lock?>(null)

    /** The full-screen lock to show, or null when the build is not locked. */
    val lock: StateFlow<TestBuildSwitch.Lock?> = _lock.asStateFlow()

    /** The last known state (saved between launches), used before the network answers. */
    private var saved: TestBuildSwitch.Status = TestBuildSwitch.Status.unlocked

    private var timerJob: Job? = null
    private var checkJob: Job? = null

    /** A check started at most this often from the timer/foreground, so a launch that is also a foregrounding doesn't
     *  fetch twice. In seconds. */
    private var lastCheckAt: Long? = null

    /** The walkthrough turns the real check off in the app's own process (never an intent extra). */
    private var disabledForTests = false

    /** The walkthrough forces this lock on, whatever the channel or saved state. */
    private var forcedLock: TestBuildSwitch.Lock? = null

    /** Loads the saved state and shows the lock it implies at once (before any fetch). Called by `AppModel` in its
     *  init, once `Defaults` is ready. */
    fun start() {
        if (channel == null) return // debug build: never checks, never locks
        saved = loadSaved()
        recompute(fetched = null)
    }

    /** Launch, and each return to the foreground: check now and keep checking every five minutes. */
    fun foregrounded() {
        if (channel == null || disabledForTests) return
        startTimer()
        check()
    }

    /** Gone to the background: nothing runs there. */
    fun backgrounded() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay((TestBuildConfig.foregroundInterval * 1000).toLong())
                check()
            }
        }
    }

    /** One check: tries each URL in order, uses the first file that parses, and keeps the last known state otherwise. */
    private fun check(force: Boolean = false) {
        if (channel == null || disabledForTests) return
        if (checkJob?.isActive == true) return
        val now = clock()
        if (!force) {
            val last = lastCheckAt
            if (last != null && now - last < 2) return // de-duplicate a launch that is also a foregrounding
        }
        lastCheckAt = now
        checkJob = scope.launch {
            var fetched: TestBuildSwitch.Status? = null
            for (url in urls) {
                val body = withContext(io) { fetch(url) }
                val status = TestBuildSwitch.parse(body)
                if (status != null) {
                    fetched = status
                    break
                }
            }
            recompute(fetched)
        }
    }

    /** Folds a fetch into the saved state (a readable one replaces it), saves it, and republishes the lock. */
    private fun recompute(fetched: TestBuildSwitch.Status?) {
        val channel = channel ?: run { _lock.value = forcedLock; return }
        val merged = TestBuildSwitch.merged(saved, fetched)
        if (merged != saved) {
            saved = merged
            saveStatus(merged)
        }
        _lock.value = forcedLock ?: if (disabledForTests) null else TestBuildSwitch.decide(channel, saved, null)
    }

    // Persistence (iOS UserDefaults / the app's SharedPreferences).

    private fun loadSaved(): TestBuildSwitch.Status {
        val text = Defaults.string(KEY) ?: return TestBuildSwitch.Status.unlocked
        return decodeStatus(text) ?: TestBuildSwitch.Status.unlocked
    }

    private fun saveStatus(status: TestBuildSwitch.Status) {
        Defaults.putString(KEY, encodeStatus(status))
    }

    // Test hooks (walkthrough and unit tests), in the app's own process.

    /** Turns the real check off and clears any real lock, so the walkthrough is never locked by the live switch. */
    @VisibleForTesting
    fun disableForTests() {
        disabledForTests = true
        timerJob?.cancel()
        timerJob = null
        checkJob?.cancel()
        checkJob = null
        if (forcedLock == null) _lock.value = null
    }

    /** Forces the lock on with sample texts and links (screenshot 41), or null to clear it. */
    @VisibleForTesting
    fun forceLockedForTests(lock: TestBuildSwitch.Lock?) {
        forcedLock = lock
        _lock.value = lock ?: if (channel != null && !disabledForTests) TestBuildSwitch.decide(channel, saved, null) else null
    }

    /** For unit tests: run a check and wait for it (the injected `fetch` is synchronous). */
    @VisibleForTesting
    fun checkNowForTests() = check(force = true)

    companion object {
        const val KEY = "testBuildStatus"

        private fun encodeStatus(s: TestBuildSwitch.Status): String = buildJsonObject {
            put("locked", s.locked)
            put("title", s.title)
            put("message", s.message)
            s.appStoreURL?.let { put("appStoreURL", it) }
            s.playStoreURL?.let { put("playStoreURL", it) }
            s.websiteURL?.let { put("websiteURL", it) }
        }.toString()

        private fun decodeStatus(text: String): TestBuildSwitch.Status? {
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val locked = (obj["locked"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return null
            return TestBuildSwitch.Status(
                locked = locked,
                title = str("title") ?: "",
                message = str("message") ?: "",
                appStoreURL = str("appStoreURL"),
                playStoreURL = str("playStoreURL"),
                websiteURL = str("websiteURL"),
            )
        }
    }

    /** The real fetch: a plain GET with the `SatMap/<version>` User-Agent, no cache, a 10 s timeout and normal HTTPS
     *  certificate checks, with `?t=<unix seconds>` added to defeat caches. No device or user id is ever sent. */
    private class DefaultFetch : suspend (String) -> String? {
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cache(null)
                .connectTimeout(TestBuildConfig.timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(TestBuildConfig.timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(TestBuildConfig.timeoutSeconds, TimeUnit.SECONDS)
                .build()
        }

        override suspend fun invoke(url: String): String? {
            val full = url + (if ('?' in url) "&" else "?") + "t=" + (System.currentTimeMillis() / 1000)
            val request = Request.Builder()
                .url(full)
                .header("User-Agent", "SatMap/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
