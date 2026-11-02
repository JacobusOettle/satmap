package com.jacobusoettle.satmap.groups

import com.jacobusoettle.satmap.core.GroupTransportError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The OkHttp transport and socket against MockWebServer (docs/groups/SPEC.md §8.2, §8.7, §12.3): the request the core
 *  asked for with its Bearer pass, the status and body handed back as they are, offline and other failures, the
 *  socket's URL and header, refused upgrades as close codes, and only the latest socket's events. */
@RunWith(AndroidJUnit4::class)
class GroupHttpTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val events = CopyOnWriteArrayList<String>()
    private var online = true

    private fun http(base: String = server.url("/").toString().trimEnd('/')) =
        GroupHttp(client, baseURL = { base }, isOnline = { online }, mainThread = { it.run() }).also { h ->
            h.events = object : GroupHttp.SocketEvents {
                override fun opened(groupId: String) {
                    events += "open $groupId"
                }

                override fun received(groupId: String, text: String) {
                    events += "text $groupId $text"
                }

                override fun closed(groupId: String, code: Int) {
                    events += "close $groupId $code"
                }
            }
        }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aRequestCarriesThePassAndTheJsonAndGivesBackStatusAndBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"group_exists","message":"x"}"""))
        val answer = http().request("POST", "/v1/groups", "PASS", """{"a":1}""".toByteArray())
        assertEquals(409, answer.status)
        assertEquals("""{"error":"group_exists","message":"x"}""", String(answer.body))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/groups", r.path)
        assertEquals("Bearer PASS", r.getHeader("Authorization"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"a":1}""", r.body.readUtf8())
    }

    @Test
    fun aGetWithoutAPassHasNoAuthorization() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(byteArrayOf(1, 2, 3).let { okio.Buffer().write(it) }))
        val answer = http().request("GET", "/v1/groups/g/archive/m/2026-11", null, null)
        assertArrayEquals(byteArrayOf(1, 2, 3), answer.body)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun withoutANetworkAFailureIsOfflineAndWithOneItFails() = runBlocking {
        val port = server.port
        server.shutdown()
        online = false
        try {
            http("http://127.0.0.1:$port").request("GET", "/v1/groups/g", "p", null)
            fail("no answer")
        } catch (e: GroupTransportError.Offline) {
            // as expected
        }
        online = true
        try {
            http("http://127.0.0.1:$port").request("GET", "/v1/groups/g", "p", null)
            fail("no answer")
        } catch (e: GroupTransportError.Failed) {
            // a refused connection while online: the server's side
        }
        try {
            http("http://unknown.host.invalid").request("GET", "/v1/groups/g", "p", null)
            fail("no answer")
        } catch (e: GroupTransportError.Offline) {
            // no DNS: no network
        }
    }

    @Test
    fun theSocketGoesToTheGroupsWsPathWithThePass() {
        assertEquals("wss://example.invalid/v1/groups/G/ws", GroupHttp.socketURL("https://example.invalid", "G"))
        assertEquals("ws://10.0.2.2:8787/v1/groups/G/ws", GroupHttp.socketURL("http://10.0.2.2:8787", "G"))
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"welcome"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ping") opened.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        val h = http()
        val socket = h.open("G", "PASS")
        waitFor { events.any { it.startsWith("text G") } }
        socket.send("ping")
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val r = server.takeRequest()
        assertEquals("/v1/groups/G/ws", r.path)
        assertEquals("Bearer PASS", r.getHeader("Authorization"))
        assertEquals(listOf("open G", """text G {"type":"welcome"}"""), events.toList())
        socket.close(1000)
        Thread.sleep(200)
        assertEquals("closed by the hub: nothing more is reported", 2, events.size)
    }

    @Test
    fun aRefusedUpgradeIsACloseCode() {
        assertEquals(4401, GroupHttp.refusedUpgradeCode(401, "unauthorized"))
        assertEquals(4401, GroupHttp.refusedUpgradeCode(404, "group_not_found"))
        assertEquals(4403, GroupHttp.refusedUpgradeCode(410, "removed"))
        assertEquals(4404, GroupHttp.refusedUpgradeCode(410, "group_deleted"))
        assertEquals(1006, GroupHttp.refusedUpgradeCode(500, "internal"))
        assertEquals(1006, GroupHttp.refusedUpgradeCode(404, "not_found"))
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"removed","message":"x"}"""))
        http().open("G", "PASS")
        waitFor { events.isNotEmpty() }
        assertEquals(listOf("close G 4403"), events.toList())
    }

    @Test
    fun onlyTheLatestSocketIsReported() {
        // The first socket's upgrade answers late; by then a second one was opened.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path!!.contains("/A/")) {
                    MockResponse().setResponseCode(401).setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("{}")
                } else {
                    MockResponse().setResponseCode(410).setBody("""{"error":"group_deleted"}""")
                }
        }
        val h = http()
        h.open("A", "p")
        h.open("B", "p")
        waitFor { events.isNotEmpty() }
        Thread.sleep(800)
        assertEquals(listOf("close B 4404"), events.toList())
    }

    private fun waitFor(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!done()) {
            if (System.currentTimeMillis() > end) fail("timed out: $events")
            Thread.sleep(10)
        }
    }
}
