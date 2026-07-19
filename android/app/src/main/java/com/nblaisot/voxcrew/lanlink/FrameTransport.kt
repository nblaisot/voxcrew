package com.nblaisot.voxcrew.lanlink

/**
 * A pluggable pipe that can carry [LanFrame]s to and from exactly one peer.
 * [PeerLink] is transport-agnostic: whichever concrete implementation is
 * currently active (LAN TCP, hole-punched UDP, cloud relay) drives it by
 * calling [PeerLink.onHandshakeComplete] once its own Hello/resume exchange
 * succeeds, [PeerLink.onFrameReceived] for every other frame, and
 * [PeerLink.onDisconnected] when it drops — and is itself only ever asked to
 * [sendFrame].
 */
interface FrameTransport {
    /** Locale-independent path token surfaced in the UI, e.g. [PathLabels.LOCAL]. */
    val label: String

    /**
     * Best-effort, non-blocking send. Implementations must never perform socket IO on the
     * caller's thread and remain safe when not currently connected (silently dropped).
     */
    fun sendFrame(frame: LanFrame)

    /**
     * The current connection looks stale or unhealthy (e.g. [PeerLink] has not seen any
     * activity in a while): drop it and let the transport retry on its own, without a full
     * [stop] — this is what lets a transport recover from a blip without the path manager
     * having to intervene.
     */
    fun dropAndRetry()

    /** Releases all resources; safe to call multiple times. Final — use [dropAndRetry] to retry. */
    fun stop()
}
