package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
import java.net.DatagramSocket
import java.net.Socket

internal object NoOpTestNetworkBinder : NetworkSocketBinder {
    override fun bindSocket(networkHandle: Long, socket: Socket) = Unit
    override fun bindSocket(networkHandle: Long, socket: DatagramSocket) = Unit
}

internal fun LanPeer.routed(
    networkHandle: Long = if (viaOverlay) 200L else 100L,
): RoutedPeerTarget = RoutedPeerTarget(
    peer = this,
    route = RoutedSocketPath(
        path = if (viaOverlay) PeerPath.OVERLAY else PeerPath.LAN,
        networkHandle = networkHandle,
    ),
)
