package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.ConnectivitySnapshot
import com.nblaisot.voxcrew.connectivity.Ipv4Link
import com.nblaisot.voxcrew.connectivity.LanNetwork
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

enum class PeerPath {
    LAN,
    OVERLAY,
}

data class RoutedSocketPath(
    val path: PeerPath,
    val networkHandle: Long,
) {
    val label: String
        get() = if (path == PeerPath.OVERLAY) PathLabels.VPN else PathLabels.LOCAL

    /** SoftAP / interface-fallback Local dial — no ConnectivityManager Network to bind. */
    val isUnbound: Boolean
        get() = networkHandle == LocalLanNetworks.UNBOUND_NETWORK_HANDLE
}

data class RoutedPeerTarget(
    val peer: LanPeer,
    val route: RoutedSocketPath,
)

internal fun routePeer(
    peer: LanPeer,
    snapshot: ConnectivitySnapshot,
    localInterfaceNetworks: Collection<LanNetwork> = emptyList(),
): RoutedPeerTarget? {
    if (peer.viaOverlay) {
        val overlay = snapshot.overlayNetwork ?: return null
        return RoutedPeerTarget(
            peer = peer,
            route = RoutedSocketPath(PeerPath.OVERLAY, overlay.networkHandle),
        )
    }
    val lan = snapshot.lanNetworks
        .filter { network ->
            network.ipv4Links.any { link -> ipv4PrefixContains(link, peer.host) }
        }
        .minWithOrNull(compareBy({ it.interfaceName }, { it.networkHandle }))
    if (lan != null) {
        return RoutedPeerTarget(
            peer = peer,
            route = RoutedSocketPath(PeerPath.LAN, lan.networkHandle),
        )
    }
    // Hotspot AP host: peer is on a local SoftAP/tether subnet with no STA Network.
    val softApMatch = localInterfaceNetworks.any { network ->
        network.ipv4Links.any { link -> ipv4PrefixContains(link, peer.host) }
    }
    if (!softApMatch) return null
    return RoutedPeerTarget(
        peer = peer,
        route = RoutedSocketPath(PeerPath.LAN, LocalLanNetworks.UNBOUND_NETWORK_HANDLE),
    )
}

internal fun classifyAcceptedSocket(
    socket: Socket,
    snapshot: ConnectivitySnapshot,
): RoutedSocketPath? {
    val localHost = socket.localAddress?.hostAddress?.substringBefore('%')
    val remoteHost = socket.inetAddress?.hostAddress?.substringBefore('%')
    snapshot.overlayNetwork?.takeIf { it.ipv4Address == localHost }?.let {
        return RoutedSocketPath(PeerPath.OVERLAY, it.networkHandle)
    }
    snapshot.lanNetworks
        .filter { network -> network.ipv4Links.any { it.address == localHost } }
        .minWithOrNull(compareBy({ it.interfaceName }, { it.networkHandle }))
        ?.let { return RoutedSocketPath(PeerPath.LAN, it.networkHandle) }

    // Some OEM socket implementations report a wildcard local endpoint after accept.
    // Resolve only against the semantic routes Android exposed to this app.
    if (remoteHost != null) {
        snapshot.lanNetworks
            .filter { network ->
                network.ipv4Links.any { link -> ipv4PrefixContains(link, remoteHost) }
            }
            .minWithOrNull(compareBy({ it.interfaceName }, { it.networkHandle }))
            ?.let { return RoutedSocketPath(PeerPath.LAN, it.networkHandle) }
        snapshot.overlayNetwork?.takeIf {
            TailscaleInterface.isCgnatAddress(remoteHost)
        }?.let { return RoutedSocketPath(PeerPath.OVERLAY, it.networkHandle) }
    }
    return null
}

internal fun ipv4PrefixContains(link: Ipv4Link, host: String): Boolean {
    val network = runCatching { InetAddress.getByName(link.address) }.getOrNull() as? Inet4Address
        ?: return false
    val candidate = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address
        ?: return false
    val prefix = link.prefixLength.coerceIn(0, 32)
    val networkValue = network.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
    val candidateValue = candidate.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    return (networkValue and mask) == (candidateValue and mask)
}
