package com.jacobusoettle.satmap.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Metered networks (mobile data) and the walkthrough's switch that lets mobile data count as unmetered. */
@RunWith(AndroidJUnit4::class)
class ConnectivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    @After
    fun tearDown() {
        Connectivity.shared.meteredAsUnmetered = false
    }

    private fun onMobileData() {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
    }

    @Test
    fun mobileDataIsMetered() {
        onMobileData()
        Connectivity.init(context)
        assertEquals(Connectivity.State(isOnline = true, isExpensive = true), Connectivity.shared.state.value)
        assertTrue(Connectivity.shared.isExpensiveNow)
    }

    @Test
    fun theWalkthroughsSwitchLetsMobileDataCountAsUnmetered() {
        onMobileData()
        Connectivity.init(context)
        val connectivity = Connectivity.shared
        connectivity.meteredAsUnmetered = true
        assertEquals(Connectivity.State(isOnline = true, isExpensive = false), connectivity.state.value)
        assertFalse(connectivity.isExpensiveNow)
        connectivity.meteredAsUnmetered = false
        assertEquals(Connectivity.State(isOnline = true, isExpensive = true), connectivity.state.value)
        assertTrue(connectivity.isExpensiveNow)
    }
}
