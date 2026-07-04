package com.nblaisot.voxcrew.lanlink

import kotlinx.coroutines.flow.SharedFlow

/**
 * Minimal surface [RelayTransport] needs from the cloud WebSocket: connect on demand,
 * push opaque binary frames, and observe the ones addressed to us. Kept as an interface
 * (implemented by `CloudRunSignalingTransport`) so tests can exercise the relay handshake
 * and Go-Back-N-free framing over a fake channel without a real network socket.
 */
interface BinaryRelayChannel {
    fun connect()
    fun sendBinary(bytes: ByteArray)
    val incomingBinary: SharedFlow<ByteArray>
}
