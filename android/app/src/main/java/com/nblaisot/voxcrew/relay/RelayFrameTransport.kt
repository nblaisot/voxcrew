package com.nblaisot.voxcrew.relay

import com.nblaisot.voxcrew.lanlink.FrameTransport
import com.nblaisot.voxcrew.lanlink.LanFrame
import com.nblaisot.voxcrew.lanlink.PathLabels
import com.nblaisot.voxcrew.lanlink.PeerLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import kotlin.coroutines.CoroutineContext

/**
 * Per-peer Cloud [FrameTransport] over the shared [RelayClient] WebSocket.
 * Hello/resume semantics match LAN TCP: both sides may dial; first Hello wins.
 */
class RelayFrameTransport(
    private val peerUid: String,
    private val localUid: String,
    private val client: RelayBinarySender,
    ioDispatcher: CoroutineContext = Dispatchers.IO,
) : FrameTransport {
    override val label: String = PathLabels.CLOUD

    private var peerLink: PeerLink? = null
    @Volatile private var open = true
    @Volatile private var handshakeDone = false
    @Volatile private var helloSent = false
    private val ioScope = CoroutineScope(ioDispatcher)
    private var handshakeJob: Job? = null

    fun attach(link: PeerLink) {
        peerLink = link
    }

    fun detach() {
        handshakeJob?.cancel()
        open = false
        handshakeDone = false
        helloSent = false
        peerLink = null
    }

    fun startHandshake(link: PeerLink) {
        peerLink = link
        open = true
        handshakeDone = false
        helloSent = false
        handshakeJob?.cancel()
        handshakeJob = ioScope.launch { sendHello() }
    }

    fun onRemoteFrame(frame: LanFrame) {
        val link = peerLink
        if (frame is LanFrame.Hello && frame.uid == peerUid) {
            if (!helloSent) sendHello()
            completeHandshake(link, frame.lastContiguousSeq)
            return
        }
        if (!handshakeDone) {
            return
        }
        if (link != null) link.onFrameReceived(this, frame)
    }

    fun onPeerGone() {
        val link = peerLink
        handshakeDone = false
        helloSent = false
        if (link != null) link.onDisconnected(this, peerUid)
    }

    override fun sendFrame(frame: LanFrame) {
        if (!open) return
        if (!handshakeDone) {
            // Connected Cloud icon must not outlive a writable relay pipe.
            val link = peerLink
            if (link != null &&
                link.isActiveTransport(this) &&
                link.state.value is PeerLink.LinkState.Connected
            ) {
                link.onDisconnected(this, peerUid)
            }
            return
        }
        client.sendBinary(peerUid, frame)
    }

    override fun dropAndRetry() {
        handshakeDone = false
        helloSent = false
        val link = peerLink ?: return
        link.onDisconnected(this, peerUid)
        handshakeJob?.cancel()
        handshakeJob = ioScope.launch { sendHello() }
    }

    override fun stop() {
        handshakeJob?.cancel()
        open = false
        handshakeDone = false
        helloSent = false
        // Do not call onDisconnected on replace/stop — PeerLink owns link state.
        // Real remote loss uses onPeerGone / dropAndRetry (matches LAN TCP stop).
    }

    private fun sendHello() {
        val link = peerLink ?: return
        helloSent = true
        client.sendBinary(peerUid, LanFrame.Hello(localUid, link.lastContiguousInSeq()))
    }

    private fun completeHandshake(link: PeerLink?, peerAnnounced: Long) {
        if (link == null || handshakeDone) return
        handshakeDone = true
        link.onHandshakeComplete(this, peerUid, peerAnnounced)
    }
}
