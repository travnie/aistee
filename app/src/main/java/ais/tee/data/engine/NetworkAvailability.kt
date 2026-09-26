package ais.tee.data.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default-network reachability from a ConnectivityManager callback; no polling. */
internal class NetworkAvailability(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = mutableOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            mutableOnline.value = capabilities.hasInternet()
        }

        override fun onLost(network: Network) {
            mutableOnline.value = false
        }
    }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasInternet()
    }

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
