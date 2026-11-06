package com.expuz.ai.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Reports whether the device currently has a default network at all. It does
 * not require validated internet access: a LAN-only Wi-Fi network can still
 * host the Open WebUI server.
 */
class NetworkMonitor(
    context: Context,
    private val onChanged: (online: Boolean) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishState()
        override fun onLost(network: Network) = publishState()
        override fun onUnavailable() = publishState()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = publishState()
    }

    fun start() {
        if (started) return
        started = true
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Throwable) {
            // If the platform refuses the callback, keep the UI usable and let
            // WebView report the actual connection error.
        }
        publishState()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
            // Already unregistered.
        }
    }

    private fun publishState() {
        val online = connectivityManager?.activeNetwork != null
        mainHandler.post { onChanged(online) }
    }
}
