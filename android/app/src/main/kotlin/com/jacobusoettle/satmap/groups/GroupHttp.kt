package com.jacobusoettle.satmap.groups

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jacobusoettle.satmap.core.GroupResponse
import com.jacobusoettle.satmap.core.GroupSocket
import com.jacobusoettle.satmap.core.GroupSocketFactory
import com.jacobusoettle.satmap.core.GroupTransport
import com.jacobusoettle.satmap.core.GroupTransportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The groups transport on OkHttp (docs/groups/SPEC.md §8.1, §8.2): one HTTP request to "/v1/…" under the base URL
 *  (`GroupConfig.baseURL`, read at each request), and the shown group's WebSocket (§6.6, §8.7). iOS's GroupHTTP.swift
 *  and GroupSocket.swift do the same on URLSession.
 *
 *  `isOnline` says whether the phone has a network (`Connectivity`): a request that fails without one throws
 *  `GroupTransportError.Offline` (the "No connection" text), any other failure `.Failed`. No headers reach the core:
 *  the status and the body only. The socket's events are reported on the main thread, and only for the socket opened
 *  last and not yet closed by the hub: the late frames and close of an older socket are dropped (§8.7). */
class GroupHttp(
    private val client: OkHttpClient = defaultClient,
    private val baseURL: () -> String,
    private val isOnline: () -> Boolean,
    private val mainThread: (Runnable) -> Unit = { r -> main.post(r) },
) : GroupTransport, GroupSocketFactory {
    /** Where the socket's events go (the hub's `socketDidOpen`, `socketDidReceive`, `socketDidClose`), on the main
     *  thread. */
    interface SocketEvents {
        fun opened(groupId: String)
        fun received(groupId: String, text: String)
        fun closed(groupId: String, code: Int)
    }

    var events: SocketEvents? = null

    /** The socket the hub opened last, until the hub closes it or it is reported closed. Main thread only. */
    private var current: Socket? = null

    override suspend fun request(method: String, path: String, pass: String?, json: ByteArray?): GroupResponse {
        val url = baseURL() + path
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw GroupTransportError.Failed(e)
        }
        builder.header("Accept", "application/json")
        if (pass != null) builder.header("Authorization", "Bearer $pass")
        val body = json?.toRequestBody(JSON)
        builder.method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(JSON) else null)
        val call = client.newCall(builder.build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw failure(e)
        }
        return response.use { r ->
            val bytes = try {
                withContext(Dispatchers.IO) { r.body?.bytes() ?: ByteArray(0) }
            } catch (e: IOException) {
                throw failure(e)
            }
            GroupResponse(r.code, bytes)
        }
    }

    /** No network, or one that can't be reached at all: offline. Anything else (a timeout, a refused connection, a
     *  broken answer) is a failure, shown as "Something went wrong on our side". */
    private fun failure(e: IOException): GroupTransportError =
        if (!isOnline() || isOffline(e)) GroupTransportError.Offline(e) else GroupTransportError.Failed(e)

    override fun open(groupId: String, pass: String): GroupSocket {
        val socket = Socket(groupId)
        current = socket
        val request = try {
            Request.Builder().url(socketURL(baseURL(), groupId)).header("Authorization", "Bearer $pass").build()
        } catch (e: IllegalArgumentException) {
            // A base URL that isn't one: reported as a socket that couldn't connect (it reconnects with backoff).
            mainThread(Runnable { socket.report { events?.closed(groupId, ABNORMAL) } })
            return socket
        }
        socket.ws = client.newWebSocket(request, socket.listener)
        return socket
    }

    /** One WebSocket. `close` from the hub ends it at once for the hub: nothing more of it is reported. */
    private inner class Socket(val groupId: String) : GroupSocket {
        var ws: WebSocket? = null
        private var ended = false

        override fun send(text: String) {
            ws?.send(text)
        }

        override fun close(code: Int) {
            if (current === this) current = null
            ended = true
            try {
                ws?.close(if (code == 1000 || code in 3000..4999) code else 1000, null)
            } catch (e: IllegalArgumentException) {
                ws?.cancel()
            }
        }

        /** Runs `event` on the main thread if this is still the hub's socket; `last` ends it. */
        fun report(last: Boolean = true, event: () -> Unit) {
            if (current !== this || ended) return
            if (last) {
                ended = true
                current = null
            }
            event()
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainThread(Runnable { report(last = false) { events?.opened(groupId) } })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainThread(Runnable { report(last = false) { events?.received(groupId, text) } })
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                mainThread(Runnable { report { events?.closed(groupId, code) } })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainThread(Runnable { report { events?.closed(groupId, code) } })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A refused upgrade is an HTTP answer before it (§8.7); no answer at all reconnects with backoff.
                val code = response?.let { r -> refusedUpgradeCode(r.code, errorCode(r)) } ?: ABNORMAL
                mainThread(Runnable { report { events?.closed(groupId, code) } })
            }
        }
    }

    companion object {
        private const val TAG = "groups"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val main by lazy { Handler(Looper.getMainLooper()) }

        /** The close code of a socket that ended without a close frame (no answer, or an unexpected one). */
        const val ABNORMAL = 1006

        /** No cache (every answer is `no-store`), 20 s to connect, 30 s to read; the WebSocket's frames are read
         *  without a time limit. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "Spoor/1.0 (Android)").build())
                }
                .build()
        }

        /** `wss://` (or `ws://` for a local `http://` test server) + host + `/v1/groups/{groupId}/ws`. */
        fun socketURL(base: String, groupId: String): String {
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/v1/groups/$groupId/ws"
        }

        /** §8.7: an HTTP answer instead of the upgrade, as a close code. `401` and `404 group_not_found` are 4401 (the
         *  hub checks with E2), `410 removed` 4403, `410 group_deleted` 4404, anything else 1006 (reconnects). */
        fun refusedUpgradeCode(status: Int, code: String?): Int = when {
            status == 401 -> 4401
            status == 404 && code == "group_not_found" -> 4401
            status == 410 && code == "removed" -> 4403
            status == 410 && code == "group_deleted" -> 4404
            else -> ABNORMAL
        }

        /** The `error` of a §6.1 error body, or null. */
        private fun errorCode(r: Response): String? = try {
            val text = r.peekBody(4096).string()
            Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }

        /** Failures that mean the phone can't reach the network at all (no DNS, no route). */
        fun isOffline(e: IOException): Boolean = when (e) {
            is UnknownHostException, is NoRouteToHostException -> true
            is SocketException -> e.message?.contains("unreachable", ignoreCase = true) == true
            else -> false
        }

        private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
            cont.invokeOnCancellation {
                try {
                    cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "cancelling a request failed")
                }
            }
        }
    }
}
