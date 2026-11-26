package com.carteltv.tv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the default network currently reaches the internet. Starts as online so code that runs before [start]
 * (and unit tests) behaves as it did without a monitor. A LAN-only network reports offline, so only gate work
 * that needs the wider internet on it.
 */
object Connectivity {
    private val state = MutableStateFlow(true)
    val online: StateFlow<Boolean> = state

    val isOnline: Boolean get() = state.value

    fun start(ctx: Context) {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        state.value = cm.getNetworkCapabilities(cm.activeNetwork)?.reachesInternet() ?: false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.reachesInternet()
            }

            override fun onLost(network: Network) {
                state.value = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    /** Returns at once when online, otherwise when the network next validates. Cancels with the caller. */
    suspend fun awaitOnline() {
        state.first { it }
    }

    private fun NetworkCapabilities.reachesInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
