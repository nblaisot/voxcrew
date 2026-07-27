package com.nblaisot.voxcrew.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** IPv4 address and subnet attached to an Android network. */
data class Ipv4Link(
    val address: String,
    val prefixLength: Int,
)

/** Physical network on which LAN discovery and peer TCP are valid. */
data class LanNetwork(
    val networkHandle: Long,
    val interfaceName: String,
    val ipv4Links: Set<Ipv4Link>,
)

/** Verified Tailscale network visible to this app. */
data class OverlayNetwork(
    val networkHandle: Long,
    val interfaceName: String,
    val ipv4Address: String,
)

data class ConnectivitySnapshot(
    val lanNetworks: Set<LanNetwork> = emptySet(),
    val overlayNetwork: OverlayNetwork? = null,
)

/** Testable boundary around [Network.bindSocket]. */
interface NetworkSocketBinder {
    @Throws(IOException::class)
    fun bindSocket(networkHandle: Long, socket: Socket)

    @Throws(IOException::class)
    fun bindSocket(networkHandle: Long, socket: DatagramSocket)
}

/**
 * Converts Android connectivity callbacks into the small semantic state used by VoxCrew.
 *
 * LAN identity deliberately ignores IPv6 privacy addresses, DNS and validation changes.
 * VPN identity is derived only from networks Android exposes to VoxCrew and is verified by
 * [TailscaleNetworkResolver], never by process-global interface ordering.
 */
class NetworkMonitor(context: Context) : NetworkSocketBinder {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connectivity = MutableStateFlow(ConnectivitySnapshot())
    val connectivity: StateFlow<ConnectivitySnapshot> = _connectivity.asStateFlow()

    private val lock = Any()
    private val networks = ConcurrentHashMap<Long, Network>()
    private val lanProperties = mutableMapOf<Long, LinkPropertiesDescription>()
    private val vpnProperties = mutableMapOf<Long, LinkPropertiesDescription>()
    @Volatile private var callbacksRegistered = false

    private val lanCallback = semanticCallback(isVpn = false)
    private val vpnCallback = semanticCallback(isVpn = true)

    private fun semanticCallback(isVpn: Boolean) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!callbacksRegistered) return
            networks[network.networkHandle] = network
            // API 26+ immediately follows with ordered capability/link-property callbacks.
            // Waiting for LinkProperties avoids publishing a path with no usable address.
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (!callbacksRegistered) return
            networks[network.networkHandle] = network
            synchronized(lock) {
                val target = if (isVpn) vpnProperties else lanProperties
                target[network.networkHandle] = linkProperties.toDescription(network.networkHandle)
                publishLocked()
            }
        }

        override fun onLost(network: Network) {
            if (!callbacksRegistered) return
            networks.remove(network.networkHandle)
            synchronized(lock) {
                if (isVpn) vpnProperties.remove(network.networkHandle)
                else lanProperties.remove(network.networkHandle)
                publishLocked()
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (callbacksRegistered) return
            callbacksRegistered = true
        }
        val lanRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // Keep the default NOT_VPN capability so a VPN whose underlying transport is
            // Wi-Fi cannot be mistaken for the physical LAN.
            .build()
        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(lanRequest, lanCallback)
            connectivityManager.registerNetworkCallback(vpnRequest, vpnCallback)
        } catch (error: RuntimeException) {
            stop()
            throw error
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!callbacksRegistered) return
            callbacksRegistered = false
        }
        runCatching { connectivityManager.unregisterNetworkCallback(lanCallback) }
        runCatching { connectivityManager.unregisterNetworkCallback(vpnCallback) }
        networks.clear()
        synchronized(lock) {
            lanProperties.clear()
            vpnProperties.clear()
            val previous = _connectivity.value
            if (previous != ConnectivitySnapshot()) {
                _connectivity.value = ConnectivitySnapshot()
            }
        }
    }

    override fun bindSocket(networkHandle: Long, socket: Socket) {
        val network = networks[networkHandle]
            ?: throw IOException("Android network $networkHandle is no longer available")
        try {
            network.bindSocket(socket)
        } catch (error: IOException) {
            evictStaleNetwork(networkHandle, error)
            throw error
        }
    }

    override fun bindSocket(networkHandle: Long, socket: DatagramSocket) {
        val network = networks[networkHandle]
            ?: throw IOException("Android network $networkHandle is no longer available")
        try {
            network.bindSocket(socket)
        } catch (error: IOException) {
            evictStaleNetwork(networkHandle, error)
            throw error
        }
    }

    /** Drop a Network that rejected bind (EPERM / gone) so reconcile can clear lanDialFailed. */
    private fun evictStaleNetwork(networkHandle: Long, error: IOException) {
        Log.w(TAG, "evicting stale network handle=$networkHandle: ${error.message}")
        networks.remove(networkHandle)
        synchronized(lock) {
            lanProperties.remove(networkHandle)
            vpnProperties.remove(networkHandle)
            publishLocked()
        }
    }

    private fun publishLocked() {
        val previous = _connectivity.value
        val next = semanticConnectivitySnapshot(
            lanProperties = lanProperties.values,
            vpnProperties = vpnProperties.values,
            currentOverlayHandle = previous.overlayNetwork?.networkHandle,
        )
        if (next == previous) return
        logChanges(previous, next)
        _connectivity.value = next
    }

    private fun logChanges(previous: ConnectivitySnapshot, next: ConnectivitySnapshot) {
        val oldLan = previous.lanNetworks.associateBy { it.networkHandle }
        val newLan = next.lanNetworks.associateBy { it.networkHandle }
        (newLan.keys - oldLan.keys).forEach { handle ->
            Log.i(TAG, "LAN_ADDED handle=$handle interface=${newLan.getValue(handle).interfaceName}")
        }
        (oldLan.keys - newLan.keys).forEach { handle ->
            Log.i(TAG, "LAN_REMOVED handle=$handle interface=${oldLan.getValue(handle).interfaceName}")
        }
        (oldLan.keys intersect newLan.keys).forEach { handle ->
            if (oldLan.getValue(handle) != newLan.getValue(handle)) {
                Log.i(TAG, "LAN_ADDRESS_CHANGED handle=$handle")
            }
        }
        val oldOverlay = previous.overlayNetwork
        val newOverlay = next.overlayNetwork
        when {
            oldOverlay == null && newOverlay != null ->
                Log.i(TAG, "OVERLAY_ADDED handle=${newOverlay.networkHandle} interface=${newOverlay.interfaceName}")
            oldOverlay != null && newOverlay == null ->
                Log.i(TAG, "OVERLAY_REMOVED handle=${oldOverlay.networkHandle} interface=${oldOverlay.interfaceName}")
            oldOverlay != null && newOverlay != null && oldOverlay != newOverlay ->
                Log.i(
                    TAG,
                    "OVERLAY_REPLACED oldHandle=${oldOverlay.networkHandle} " +
                        "newHandle=${newOverlay.networkHandle} interface=${newOverlay.interfaceName}",
                )
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

internal fun semanticConnectivitySnapshot(
    lanProperties: Collection<LinkPropertiesDescription>,
    vpnProperties: Collection<LinkPropertiesDescription>,
    currentOverlayHandle: Long? = null,
): ConnectivitySnapshot {
    val lan = lanProperties.mapNotNullTo(linkedSetOf()) { properties ->
        val links = properties.linkAddresses
            .filter { it.family == IpFamily.IPV4 && it.prefixLength in 1..32 }
            .mapTo(linkedSetOf()) { Ipv4Link(it.address, it.prefixLength) }
        if (links.isEmpty() || properties.interfaceName.isBlank()) null
        else LanNetwork(properties.networkHandle, properties.interfaceName, links)
    }
    val selected = TailscaleNetworkResolver.select(
        candidates = vpnProperties.mapNotNull(TailscaleNetworkResolver::candidate),
        currentNetworkHandle = currentOverlayHandle,
    )
    return ConnectivitySnapshot(
        lanNetworks = lan,
        overlayNetwork = selected?.let {
            OverlayNetwork(it.networkHandle, it.interfaceName, it.ipv4Address)
        },
    )
}

internal fun LinkProperties.toDescription(networkHandle: Long): LinkPropertiesDescription =
    LinkPropertiesDescription(
        networkHandle = networkHandle,
        interfaceName = interfaceName.orEmpty(),
        linkAddresses = linkAddresses.mapNotNull { link ->
            val address = link.address ?: return@mapNotNull null
            val family = when (address) {
                is Inet4Address -> IpFamily.IPV4
                else -> IpFamily.IPV6
            }
            address.hostAddress?.substringBefore('%')?.let {
                IpAddressDescription(it, link.prefixLength, family)
            }
        },
        routePrefixes = routes.mapNotNull { route ->
            val destination = route.destination ?: return@mapNotNull null
            val address = destination.address ?: return@mapNotNull null
            val family = if (address is Inet4Address) IpFamily.IPV4 else IpFamily.IPV6
            address.hostAddress?.substringBefore('%')?.let {
                IpAddressDescription(it, destination.prefixLength, family)
            }
        },
        dnsAddresses = dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet(),
        domains = domains.orEmpty().split(' ').filter { it.isNotBlank() }.toSet(),
    )
