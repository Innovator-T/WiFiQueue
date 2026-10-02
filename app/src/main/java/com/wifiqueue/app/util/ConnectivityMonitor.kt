package com.wifiqueue.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ConnectivityMonitor(private val context: Context) {
    fun observeWifiState(): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isWifiConnected(connectivityManager))
            }

            override fun onLost(network: Network) {
                trySend(isWifiConnected(connectivityManager))
            }
        }

        trySend(isWifiConnected(connectivityManager))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        }

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    private fun isWifiConnected(connectivityManager: ConnectivityManager): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
