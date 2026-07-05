package com.nblaisot.voxcrew.lanlink

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Last-resort [FrameTransport] for [PeerLink]: routes frames uid-to-uid through the
 * existing Cloud Run WebSocket used for signaling/presence, as opaque binary
 * messages the backend forwards without ever parsing or storing them (see
 * `backend/src/ws/handler.ts` `onBinaryMessage`). Used only when direct UDP hole
 * punching fails outright, typically behind a symmetric/carrier-grade NAT.
 *
 * Wire format matches the backend's expectation: `[recipientUidLen:1 byte]
 * [recipientUid][LanProtocol.encodeFrame(frame)]`. There is no transport-level
 * handshake beyond [PeerLink]'s own Hello/resume — the WebSocket connection is
 * already authenticated, so any frame arriving on it for this peer is trusted.
 * The peer's own [RelayTransport] may not have started yet (or its collector on
 * the shared, non-replaying `incomingBinary` flow may not be attached the instant
 * we send), so Hello is resent periodically — mirroring [UdpP2pTransport]'s punch
 * loop — until the handshake completes.
 */
class RelayTransport(
    private val scope: CoroutineScope,
    private val peerLink: PeerLink,
    private val cloudChannel: BinaryRelayChannel,
    private val helloRetryIntervalMs: Long = HELLO_RETRY_INTERVAL_MS,
) : FrameTransport {
    override val label: String = "Relais cloud"

    private var localUid: String = ""
    private var peerUid: String = ""
    private var receiveJob: Job? = null
    private var helloRetryJob: Job? = null
    @Volatile private var connected = false
    @Volatile private var handshakeSent = false

    /** Ensures the cloud WebSocket is connected, then starts the Hello/resume handshake with [peerUid]. */
    fun start(localUid: String, peerUid: String) {
        this.localUid = localUid
        this.peerUid = peerUid
        connected = false
        handshakeSent = false
        peerLink.markConnecting(peerUid)
        cloudChannel.connect()
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            cloudChannel.incomingBinary.collect { bytes -> handleIncoming(bytes) }
        }
        helloRetryJob?.cancel()
        helloRetryJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive && !connected) {
                sendHello()
                delay(helloRetryIntervalMs)
            }
        }
    }

    override fun sendFrame(frame: LanFrame) {
        val recipient = peerUid
        if (recipient.isBlank()) return
        val recipientBytes = recipient.toByteArray(Charsets.UTF_8)
        if (recipientBytes.size > 0xFF) return
        val framePayload = LanProtocol.encodeFrame(frame)
        val message = ByteArray(1 + recipientBytes.size + framePayload.size)
        message[0] = recipientBytes.size.toByte()
        System.arraycopy(recipientBytes, 0, message, 1, recipientBytes.size)
        System.arraycopy(framePayload, 0, message, 1 + recipientBytes.size, framePayload.size)
        runCatching { cloudChannel.sendBinary(message) }
            .onFailure { Log.d(TAG, "relay send failed: ${it.message}") }
    }

    override fun dropAndRetry() {
        handshakeSent = false
        connected = false
        cloudChannel.connect()
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            cloudChannel.incomingBinary.collect { bytes -> handleIncoming(bytes) }
        }
        helloRetryJob?.cancel()
        helloRetryJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive && !connected) {
                sendHello()
                delay(helloRetryIntervalMs)
            }
        }
    }

    override fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        helloRetryJob?.cancel()
        helloRetryJob = null
        connected = false
        handshakeSent = false
    }

    private fun sendHello() {
        if (peerUid.isBlank()) return
        sendFrame(LanFrame.Hello(localUid, peerLink.lastContiguousInSeq()))
    }

    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        val recipientLen = raw[0].toInt() and 0xFF
        if (raw.size < 1 + recipientLen) return
        val payload = raw.copyOfRange(1 + recipientLen, raw.size)
        val frame = LanProtocol.decodeFrame(payload) ?: return
        when (frame) {
            is LanFrame.Hello -> {
                if (frame.uid != peerUid) return
                if (!handshakeSent) {
                    handshakeSent = true
                    sendHello()
                }
                if (!connected) {
                    connected = true
                    peerLink.onHandshakeComplete(this, peerUid, frame.lastContiguousSeq)
                }
            }
            else -> {
                if (!connected) return // wait for the handshake so seq bookkeeping stays consistent
                peerLink.onFrameReceived(this, frame)
            }
        }
    }

    companion object {
        private const val TAG = "RelayTransport"
        private const val HELLO_RETRY_INTERVAL_MS = 300L
    }
}
