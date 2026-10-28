package com.jacobusoettle.satmap.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether the device can reach the network (and whether that network is metered: mobile data or a hotspot),
 *  so the map doesn't wait on requests while offline and Wi-Fi-only downloads know when to continue. */
class Connectivity private constructor(context: Context) {
    data class State(
        val isOnline: Boolean,
        /** Metered: mobile data or a hotspot (iOS: cellular or a personal hotspot). */
        val isExpensive: Boolean,
    )

    companion object {
        lateinit var shared: Connectivity
            private set

        fun init(context: Context) {
            shared = Connectivity(context.applicationContext)
        }

        /** Online means the network offers the internet, without Android's VALIDATED check: iOS's `.satisfied`
         *  doesn't validate either (a drone controller's Wi-Fi without internet is caught by TileRepository's own
         *  network pause). */
        private fun read(c: NetworkCapabilities?) = State(
            isOnline = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isExpensive = c != null && !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // The first state is read at once. iOS starts at "online" until its first path update, but Android's
    // default-network callback never fires when there is no network at all, so that would stay online for good.
    private val _state = MutableStateFlow(read(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }))

    /** Published on the main thread when it changes. */
    val state: StateFlow<State> = _state.asStateFlow()

    val isOnline: Boolean get() = state.value.isOnline

    @Volatile
    private var current = _state.value

    /** Thread-safe snapshots for use off the main thread. */
    val isOnlineNow: Boolean get() = current.isOnline
    val isExpensiveNow: Boolean get() = current.isExpensive

    /** The default network (Wi-Fi-only downloads bind their sockets to it). */
    @Volatile
    var network: Network? = cm.activeNetwork
        private set

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                network = n
                update(read(caps))
            }

            override fun onLost(n: Network) {
                if (network == n) network = null
                update(State(isOnline = false, isExpensive = false))
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun update(v: State) {
        current = v
        if (_state.value != v) _state.value = v
    }
}
