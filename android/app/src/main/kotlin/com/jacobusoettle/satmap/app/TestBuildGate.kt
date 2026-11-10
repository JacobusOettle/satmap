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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The app's side of the test-build switch (the rule is the shared `TestBuildSwitch`; android/docs/spec/test-builds.md),
 *  as the iPhone's `TestBuildGate`: a test build fetches the status file at launch, when it comes to the foreground and
 *  every five minutes while in the foreground (never in the background), keeps the last known state so a build that
 *  has seen "off" starts locked even offline, and publishes the lock (null when unlocked). `AppModel` acts on it
 *  (pausing downloads, recording and sharing) and the map screen shows it. Store builds and debug builds never fetch,
 *  never keep anything and never lock (`TestBuildSwitch.checks`).
 *
 *  No identifiers are sent: a plain GET with the `SatMap/<version>` User-Agent, no cookies, no cache, normal HTTPS
 *  certificate checks, with `?t=<unix seconds>` added (`TestBuildSwitch.requestURL`).
 *
 *  Main thread only; the fetch and the reading of the file run on `io`. */
class TestBuildGate(
    private val channel: TestBuildSwitch.Channel = TestBuildConfig.channel,
    private val isDebugBuild: Boolean = TestBuildConfig.isDebugBuild,
    private val urls: List<String> = TestBuildConfig.statusURLs,
    /** Reads one address's body (at most `TestBuildSwitch.maxFileBytes` + 1 bytes), or null when it can't be read
     *  (no answer, not 200). Tests give their own. */
    private val fetch: suspend (url: String) -> ByteArray? = DefaultFetch(TestBuildSwitch.userAgent(BuildConfig.VERSION_NAME)),
    /** Seconds since 1970, for `?t=`. */
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Where `fetch` and `parse` run, off the main thread. Tests pass an immediate dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _lock = MutableStateFlow<TestBuildSwitch.Lock?>(null)

    /** What to show over everything, or null when the app runs normally. */
    val lock: StateFlow<TestBuildSwitch.Lock?> = _lock.asStateFlow()

    /** Only a test build that isn't a debug build ever fetches, keeps or locks. */
    private val checks = TestBuildSwitch.checks(channel, isDebugBuild)

    /** The last state fetched or kept (null: never fetched, which counts as on). */
    private var known: TestBuildSwitch.Status? = null

    private var timerJob: Job? = null
    private var checkJob: Job? = null

    /** When the last check started (seconds), so a launch that is also a foregrounding fetches once. */
    private var lastCheckAt: Double? = null

    /** The walkthrough turns the real check off, in the app's own process (never an intent extra). */
    private var disabledForTests = false

    /** The walkthrough forces this lock, whatever the channel or the kept state (never saved). */
    private var forcedLock: TestBuildSwitch.Lock? = null

    /** Applies the lock the kept state implies at once, before any fetch. Called once by `AppModel` in its init, once
     *  `Defaults` is ready and before the recorder starts, so a build that has seen "off" never starts recording. */
    fun start() {
        if (!checks) return
        known = TestBuildSwitch.saved(Defaults.string(TestBuildSwitch.savedKey))
        publish()
    }

    /** Launch, and each return to the foreground: check now and every five minutes while in the foreground. */
    fun foregrounded() {
        if (!checks || disabledForTests) return
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
                delay((TestBuildSwitch.checkInterval * 1000).toLong())
                check()
            }
        }
    }

    /** One check at a time: tries each address in turn until one gives information, then keeps and applies it.
     *  Anything unreadable leaves the last known state as it is. */
    private fun check(force: Boolean = false) {
        if (!checks || disabledForTests || checkJob?.isActive == true) return
        val now = clock()
        val last = lastCheckAt
        if (!force && last != null && now - last in 0.0..2.0) return
        lastCheckAt = now
        checkJob = scope.launch {
            var fetched: TestBuildSwitch.Status? = null
            for (base in urls) {
                fetched = withContext(io) { fetch(TestBuildSwitch.requestURL(base, clock()))?.let(TestBuildSwitch::parse) }
                if (fetched != null) break
            }
            if (fetched == null || disabledForTests) return@launch
            known = TestBuildSwitch.known(saved = known, fetched = fetched)
            Defaults.putString(TestBuildSwitch.savedKey, TestBuildSwitch.savedText(fetched))
            publish()
        }
    }

    private fun publish() {
        _lock.value = forcedLock ?: if (disabledForTests) {
            null
        } else {
            TestBuildSwitch.lock(channel, isDebugBuild, known, TestBuildSwitch.Platform.android)
        }
    }

    // Test hooks (the walkthrough and the unit tests), in the app's own process.

    /** Turns the real check off and clears any real lock, so the walkthrough is never locked by the live switch. */
    @VisibleForTesting
    fun disableForTests() {
        disabledForTests = true
        backgrounded()
        checkJob?.cancel()
        checkJob = null
        publish()
    }

    /** Forces the lock (screenshot 41: `TestBuildSwitch.sample`'s), or null to go back to the real state. */
    @VisibleForTesting
    fun forceLockedForTests(lock: TestBuildSwitch.Lock?) {
        forcedLock = lock
        publish()
    }

    /** For the unit tests: a check now (it runs at once with their immediate dispatchers). */
    @VisibleForTesting
    fun checkNowForTests() = check(force = true)

    /** The real fetch: a plain, anonymous GET (no cookies, no cache) with the User-Agent and `Accept: application/json`,
     *  a 10 s limit, normal HTTPS certificate checks and no redirect from https to http. Only a 200 counts; at most
     *  `TestBuildSwitch.maxFileBytes` + 1 bytes are read (a longer file gives no information anyway). */
    private class DefaultFetch(private val userAgent: String) : suspend (String) -> ByteArray? {
        private val client: OkHttpClient by lazy {
            val limit = (TestBuildSwitch.timeout * 1000).toLong()
            OkHttpClient.Builder()
                .cache(null)
                .followSslRedirects(false)
                .connectTimeout(limit, TimeUnit.MILLISECONDS)
                .readTimeout(limit, TimeUnit.MILLISECONDS)
                .callTimeout(limit, TimeUnit.MILLISECONDS)
                .build()
        }

        override suspend fun invoke(url: String): ByteArray? {
            val request = try {
                Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return null
            }
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) return null
                    val source = response.body?.source() ?: return null
                    source.request(TestBuildSwitch.maxFileBytes + 1L)
                    source.buffer.readByteArray(minOf(source.buffer.size, TestBuildSwitch.maxFileBytes + 1L))
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
