package com.nblaisot.voxcrew.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkChanged: SharedFlow<Unit> = _networkChanged.asSharedFlow()

    private var lastNetworkId: Long? = null
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitIfNetworkChanged(network)
        }

        override fun onLost(network: Network) {
            _networkChanged.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            emitIfNetworkChanged(network)
        }
    }

    fun start() {
        if (callbackRegistered) return
        callbackRegistered = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun stop() {
        if (!callbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        callbackRegistered = false
        lastNetworkId = null
    }

    private fun emitIfNetworkChanged(network: Network) {
        val id = network.networkHandle
        if (lastNetworkId != null && lastNetworkId != id) {
            _networkChanged.tryEmit(Unit)
        }
        lastNetworkId = id
    }
}
