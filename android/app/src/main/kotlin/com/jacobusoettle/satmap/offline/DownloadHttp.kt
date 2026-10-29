package com.jacobusoettle.satmap.offline

import android.net.Network
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The HTTP clients of downloads (iOS `OfflineManager.makeSession`), separate from MapLibre's: one for any network,
 *  one bound to the Wi-Fi network a Wi-Fi-only run started on (its sockets fail when Wi-Fi goes instead of moving to
 *  mobile data), and one for the road and place lookups. */
object DownloadHttp {
    /** Sent with every tile request (iOS: "SatMap/1.0 (iOS)"). */
    const val userAgent = "SatMap/1.0 (Android)"

    /** Identifies the app to the OpenStreetMap services, as their usage policies ask (iOS: "iPhone offline map"). */
    const val lookupUserAgent = "SatMap/1.0 (Android offline map; https://github.com/JacobusOettle/satmap)"

    /** No cache; as many connections per host as the download job may run at once (only used where the server doesn't
     *  multiplex over HTTP/2); 20 s timeouts, short enough that a slow spot doesn't hold up the progress for long (a
     *  timed-out tile is tried again). OkHttp never waits for connectivity (iOS `waitsForConnectivity = false`). */
    val anyNetwork: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(null)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .addInterceptor(userAgent(userAgent))
            .build()
    }

    /** The Wi-Fi-only client of the network last asked for, so runs on the same Wi-Fi reuse its connections. */
    private var wifi: Pair<Network, OkHttpClient>? = null

    /** iOS's `allowsExpensiveNetworkAccess = false`: the same client bound to `network`, the unmetered network the run
     *  starts on (`startNextIfIdle` has checked that). Its sockets and name lookups go through that network only, so
     *  its requests fail when Wi-Fi goes instead of moving to mobile data, and the area waits. A different
     *  `socketFactory` and `dns` make a different OkHttp address, so pooled mobile connections are never reused. */
    @Synchronized
    fun wifiOnly(network: Network): OkHttpClient {
        wifi?.let { (n, client) -> if (n == network) return client }
        val client = anyNetwork.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .build()
        wifi = network to client
        return client
    }

    /** Roads and places: 20 s timeouts and the lookup User-Agent (OkHttp's own may be refused). */
    val lookups: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(userAgent(lookupUserAgent))
            .build()
    }

    private fun userAgent(value: String) = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", value).build())
    }
}

/** A whole answer of a road or place lookup: its status and body. */
internal class HttpAnswer(val code: Int, val data: ByteArray)

/** Runs the call on OkHttp's threads and reads the whole answer there. Cancelling the coroutine cancels the call, so a
 *  body still arriving stops at once (as a cancelled URLSession task does). */
internal suspend fun Call.answer(): HttpAnswer = suspendCancellableCoroutine { c ->
    c.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val answer = try {
                response.use { HttpAnswer(it.code, it.body?.bytes() ?: ByteArray(0)) }
            } catch (e: IOException) {
                c.resumeWithException(e)
                return
            }
            c.resume(answer)
        }

        override fun onFailure(call: Call, e: IOException) {
            c.resumeWithException(e)
        }
    })
}
