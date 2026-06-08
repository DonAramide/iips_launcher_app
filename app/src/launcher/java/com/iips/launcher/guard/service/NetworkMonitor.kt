package com.iips.launcher.guard.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class NetworkMonitor(context: Context, private val onStatusChanged: (Boolean) -> Unit) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isOnline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (!isOnline) {
                isOnline = true
                Log.d("NetworkMonitor", "Network is online")
                onStatusChanged(true)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            val hasNetwork = isCurrentlyConnected()
            if (!hasNetwork && isOnline) {
                isOnline = false
                Log.d("NetworkMonitor", "Network is offline")
                onStatusChanged(false)
            }
        }
    }

    fun startMonitoring() {
        try {
            isOnline = isCurrentlyConnected()
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to start network monitoring: ${e.message}")
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to stop network monitoring: ${e.message}")
        }
    }

    private fun isCurrentlyConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
