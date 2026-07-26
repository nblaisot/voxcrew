package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.OverlayNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Continuous UUID presence over real LAN broadcast.
 *
 * There is no re-discovery state: every valid beacon replaces the transient record for its UUID.
 * The optional Tailscale address is connection metadata learned on LAN, never a probe target.
 */
class LanBeacon(
    private val scope: CoroutineScope,
) {
    data class PresenceSnapshot(
        val sightings: Map<String, LanPeer> = emptyMap(),
    )

    private val _presence = MutableStateFlow(PresenceSnapshot())
    val presence: StateFlow<PresenceSnapshot> = _presence.asStateFlow()

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _bindFailed = MutableStateFlow(false)
    val bindFailed: StateFlow<Boolean> = _bindFailed.asStateFlow()

    private val sightings = ConcurrentHashMap<String, LanPeer>()
    private val connectedPeerPaths = ConcurrentHashMap<String, Boolean>()
    private var socket: DatagramSocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var pruneJob: Job? = null
    private val announceSignal = Channel<Unit>(Channel.CONFLATED)
    private val pruneSignal = Channel<Unit>(Channel.CONFLATED)

    private var selfUid: String = ""
    private var selfName: String = ""
    private var tcpPort: Int = 0
    private var selfOverlayHost: String? = null
    private var overlayNetwork: OverlayNetwork? = null

    @Synchronized
    fun start(uid: String, displayName: String, tcpPort: Int) {
        stop(clearPresence = true)
        selfUid = uid
        selfName = displayName
        this.tcpPort = tcpPort
        openSocketAndLoops()
    }

    /** Advertised as transient metadata on the LAN beacon; never used for active probing. */
    fun updateOverlayNetwork(network: OverlayNetwork?) {
        synchronized(this) {
            if (overlayNetwork == network) return
            overlayNetwork = network
            selfOverlayHost = network?.ipv4Address
        }
        requestAnnouncement()
    }

    fun requestAnnouncement() {
        announceSignal.trySend(Unit)
    }

    /** A healthy TCP session keeps the UUID online even if UDP broadcast is briefly lost. */
    fun setConnectedPeers(viaOverlayByUid: Map<String, Boolean>) {
        val updated = viaOverlayByUid.filterKeys { it != selfUid }
        if (connectedPeerPaths == updated) return
        connectedPeerPaths.clear()
        connectedPeerPaths.putAll(updated)
        publish()
        pruneSignal.trySend(Unit)
    }

    @Synchronized
    fun stop(clearPresence: Boolean = true) {
        runCatching { socket?.close() }
        socket = null
        announceJob?.cancel()
        listenJob?.cancel()
        pruneJob?.cancel()
        announceJob = null
        listenJob = null
        pruneJob = null
        while (announceSignal.tryReceive().isSuccess) Unit
        while (pruneSignal.tryReceive().isSuccess) Unit
        overlayNetwork = null
        selfOverlayHost = null
        connectedPeerPaths.clear()
        _bindFailed.value = false
        if (clearPresence) {
            sightings.clear()
            publish()
        }
    }

    /** Remove transient endpoints that no longer route through a current network. */
    @Synchronized
    fun removeInvalidSightings(isValid: (LanPeer) -> Boolean) {
        val invalidUids = sightings.values.filterNot(isValid).map { it.uid }
        if (invalidUids.isEmpty()) return
        invalidUids.forEach(sightings::remove)
        publish()
    }

    /** Invalidate only transient metadata tied to a lost Tailscale network. */
    @Synchronized
    fun clearOverlayEndpoints() {
        var changed = false
        sightings.entries.toList().forEach { (uid, peer) ->
            if (peer.viaOverlay) {
                sightings.remove(uid)
                changed = true
            } else if (peer.overlayHost != null) {
                sightings[uid] = peer.copy(overlayHost = null)
                changed = true
            }
        }
        if (changed) publish()
    }

    internal fun announcedOverlayHostForTest(): String? = selfOverlayHost

    private fun openSocketAndLoops() {
        socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
            }
        }.onFailure { error ->
            Log.e(TAG, "beacon socket bind failed — LAN discovery disabled: ${error.message}")
        }.getOrNull()
        _bindFailed.value = socket == null
        if (socket == null) return

        listenJob = scope.launch(Dispatchers.IO) { listenLoop() }
        announceJob = scope.launch(Dispatchers.IO) { announcementLoop() }
        pruneJob = scope.launch { pruneLoop() }
    }

    private suspend fun announcementLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            broadcastToAllInterfaces(encode(selfUid, selfName, tcpPort, selfOverlayHost))
            withTimeoutOrNull(BROADCAST_INTERVAL_MS) { announceSignal.receive() }
        }
    }

    private fun broadcastToAllInterfaces(payload: ByteArray) {
        val targets = mutableSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                networkInterface.interfaceAddresses.forEach { address ->
                    address.broadcast?.let(targets::add)
                }
            }
        }
        targets.forEach { address ->
            runCatching {
                socket?.send(DatagramPacket(payload, payload.size, address, BEACON_PORT))
            }
        }
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(1024)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket?.receive(packet) ?: break
            } catch (_: IOException) {
                break
            }
            val decoded = runCatching { decode(packet.data.copyOf(packet.length)) }.getOrNull()
                ?: continue
            if (decoded.uid.isBlank() || decoded.uid == selfUid || decoded.displayName.isBlank()) continue
            val host = packet.address?.hostAddress ?: continue
            val viaOverlay = overlayNetwork != null && TailscaleInterface.isCgnatAddress(host)
            val peer = LanPeer(
                uid = decoded.uid,
                displayName = decoded.displayName,
                host = host,
                port = decoded.port,
                lastSeenMs = System.currentTimeMillis(),
                overlayHost = decoded.overlayHost,
                viaOverlay = viaOverlay,
            )
            upsertPresence(sightings, peer)
            publish()
            pruneSignal.trySend(Unit)
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            val nextExpiry = sightings.values
                .filterNot { connectedPeerPaths.containsKey(it.uid) }
                .minOfOrNull { it.lastSeenMs + STALE_MS }
            if (nextExpiry == null) {
                pruneSignal.receive()
                continue
            }
            withTimeoutOrNull((nextExpiry - System.currentTimeMillis()).coerceAtLeast(1L)) {
                pruneSignal.receive()
            }
            val pruned = prunePresence(
                current = sightings,
                connectedUids = connectedPeerPaths.keys,
                nowMs = System.currentTimeMillis(),
                staleMs = STALE_MS,
            )
            if (pruned.keys != sightings.keys) {
                sightings.clear()
                sightings.putAll(pruned)
                publish()
            }
        }
    }

    private fun publish() {
        val current = sightings.toMap()
        _presence.value = PresenceSnapshot(current)
        _peers.value = current.values
            .map { peer ->
                connectedPeerPaths[peer.uid]?.let { viaOverlay -> peer.copy(viaOverlay = viaOverlay) }
                    ?: peer
            }
            .sortedBy { it.uid }
    }

    private data class Decoded(
        val uid: String,
        val displayName: String,
        val port: Int,
        val overlayHost: String?,
    )

    private fun encode(uid: String, displayName: String, port: Int, overlayHost: String?): ByteArray =
        encodePresence(uid, displayName, port, overlayHost)

    private fun decode(bytes: ByteArray): Decoded? = decodePresence(bytes)?.let {
        Decoded(it.uid, it.displayName, it.port, it.overlayHost)
    }

    companion object {
        private const val TAG = "LanBeacon"
        const val BEACON_PORT = 47_100
        const val BROADCAST_INTERVAL_MS = 3_000L
        const val STALE_MS = 5 * BROADCAST_INTERVAL_MS
        private const val PROTOCOL_VERSION = 1
        private const val DELIMITER = '\u0001'

        fun encodeForTest(
            uid: String,
            displayName: String,
            port: Int,
            overlayHost: String? = null,
        ): ByteArray = encodePresence(uid, displayName, port, overlayHost)

        fun decodeForTest(bytes: ByteArray): DecodedPresence? = decodePresence(bytes)

        internal fun encodePresence(
            uid: String,
            displayName: String,
            port: Int,
            overlayHost: String?,
        ): ByteArray {
            val safeName = displayName.replace(DELIMITER, ' ')
            val overlay = overlayHost?.takeIf { it.isNotBlank() }?.replace(DELIMITER, ' ')
            val text = if (overlay == null) {
                "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port"
            } else {
                "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port$DELIMITER$overlay"
            }
            return text.toByteArray(StandardCharsets.UTF_8)
        }

        internal fun decodePresence(bytes: ByteArray): DecodedPresence? {
            val parts = String(bytes, StandardCharsets.UTF_8).split(DELIMITER)
            if (parts.isEmpty() || parts[0] != PROTOCOL_VERSION.toString()) return null
            if (parts.size != 4 && parts.size != 5) return null
            val port = parts[3].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            return DecodedPresence(
                uid = parts[1],
                displayName = parts[2],
                port = port,
                overlayHost = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

data class DecodedPresence(
    val uid: String,
    val displayName: String,
    val port: Int,
    val overlayHost: String?,
)

internal fun upsertPresence(
    current: MutableMap<String, LanPeer>,
    peer: LanPeer,
) {
    current[peer.uid] = peer
}

internal fun prunePresence(
    current: Map<String, LanPeer>,
    connectedUids: Set<String>,
    nowMs: Long,
    staleMs: Long,
): Map<String, LanPeer> = current.filterValues { peer ->
    peer.uid in connectedUids || nowMs - peer.lastSeenMs <= staleMs
}
