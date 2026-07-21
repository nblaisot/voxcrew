package com.nblaisot.voxcrew.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Emits when the set of usable networks changes or a network's addresses change.
 * Watches VPN alongside WIFI/CELLULAR/ETHERNET so Tailscale coming up (or its
 * 100.x address changing) refreshes the beacon's announced overlay IP.
 *
 * Wi-Fi and VPN are usually up concurrently; state is tracked per network handle so
 * routine capability callbacks on either one never cause an emit storm.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkChanged: SharedFlow<Unit> = _networkChanged.asSharedFlow()

    private val knownNetworks = ConcurrentHashMap.newKeySet<Long>()
    private val linkAddressHashes = ConcurrentHashMap<Long, Int>()
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (knownNetworks.add(network.networkHandle)) {
                _networkChanged.tryEmit(Unit)
            }
        }

        override fun onLost(network: Network) {
            knownNetworks.remove(network.networkHandle)
            linkAddressHashes.remove(network.networkHandle)
            _networkChanged.tryEmit(Unit)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val hash = linkProperties.linkAddresses
                .mapNotNull { it.address?.hostAddress }
                .sorted()
                .hashCode()
            val previous = linkAddressHashes.put(network.networkHandle, hash)
            // The first event for a network is exactly when its addresses appear
            // (e.g. Tailscale's 100.x coming up) — it must emit too.
            if (previous != hash) {
                _networkChanged.tryEmit(Unit)
            }
        }
    }

    fun start() {
        if (callbackRegistered) return
        callbackRegistered = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            // The default request filters out VPN networks; Tailscale must match.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun stop() {
        if (!callbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        callbackRegistered = false
        knownNetworks.clear()
        linkAddressHashes.clear()
    }
}
