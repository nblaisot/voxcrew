package com.nblaisot.voxcrew.lanlink

import java.net.DatagramSocket

/**
 * One shared UDP socket for STUN discovery and hole punching across all [PeerConnection]s.
 * Closing is owned by [LanIntercomEngine], not individual transports.
 */
class SharedUdpSocket {
    private var socket: DatagramSocket? = null

    @Synchronized
    fun open(): DatagramSocket {
        socket?.let { return it }
        val newSocket = DatagramSocket(0)
        socket = newSocket
        return newSocket
    }

    val localPort: Int get() = socket?.localPort ?: 0

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
