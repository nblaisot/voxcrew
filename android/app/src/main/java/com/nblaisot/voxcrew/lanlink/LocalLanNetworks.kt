package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.Ipv4Link
import com.nblaisot.voxcrew.connectivity.LanNetwork
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * SoftAP / tether hosts often expose the AP subnet on a local interface without a
 * ConnectivityManager [LanNetwork] (STA Wi‑Fi/Ethernet only). Enumerate those
 * interfaces so Local dial can proceed without binding to a vanished Network handle.
 */
internal object LocalLanNetworks {
    /**
     * Synthetic handle meaning "do not bindSocket — let the kernel pick the interface".
     * Never appears in ConnectivityManager invalidation sets.
     */
    const val UNBOUND_NETWORK_HANDLE = -1L

    fun enumerate(
        excludeIpv4Addresses: Set<String> = emptySet(),
        interfaces: () -> Sequence<NetworkInterface> = {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.asSequence()
            }.getOrNull() ?: emptySequence()
        },
    ): Set<LanNetwork> {
        val result = linkedSetOf<LanNetwork>()
        interfaces().forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            val name = networkInterface.name ?: return@forEach
            if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("rmnet")) {
                return@forEach
            }
            val links = linkedSetOf<Ipv4Link>()
            networkInterface.interfaceAddresses.forEach { address ->
                val inet = address.address as? Inet4Address ?: return@forEach
                val host = inet.hostAddress?.substringBefore('%') ?: return@forEach
                if (host in excludeIpv4Addresses) return@forEach
                if (TailscaleInterface.isCgnatAddress(host)) return@forEach
                if (inet.isLoopbackAddress || inet.isLinkLocalAddress) return@forEach
                val prefix = address.networkPrefixLength.toInt().coerceIn(1, 32)
                links += Ipv4Link(host, prefix)
            }
            if (links.isNotEmpty()) {
                // Stable synthetic handle per interface name for logging/identity only.
                val handle = UNBOUND_NETWORK_HANDLE
                result += LanNetwork(handle, name, links)
            }
        }
        return result
    }
}
