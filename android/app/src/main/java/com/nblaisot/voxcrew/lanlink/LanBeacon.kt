package com.nblaisot.voxcrew.lanlink

import android.util.Log
import com.nblaisot.voxcrew.connectivity.NetworkSocketBinder
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
 * Peer discovery via periodic UDP broadcast. LAN and overlay (Tailscale) sightings
 * are stored in **separate** maps so an overlay unicast probe never erases a live
 * LAN sighting (and vice versa).
 *
 * Discovery is for join/roster only — it does not keep the TCP mesh alive.
 *
 * Beacon payload (v1): `1<SOH>uid<SOH>displayName<SOH>tcpPort`
 * Extended (overlay): `1<SOH>uid<SOH>displayName<SOH>tcpPort<SOH>overlayHost`
 */
class LanBeacon(
    private val scope: CoroutineScope,
    private val networkSocketBinder: NetworkSocketBinder? = null,
) {
    data class PresenceSnapshot(
        val lanSightings: Map<String, LanPeer> = emptyMap(),
        val overlaySightings: Map<String, LanPeer> = emptyMap(),
    )

    private val _presence = MutableStateFlow(PresenceSnapshot())
    val presence: StateFlow<PresenceSnapshot> = _presence.asStateFlow()

    /** Merged roster view: LAN sighting preferred when both planes have the UID. */
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    /** True while the beacon socket could not bind — discovery is silently dead otherwise. */
    private val _bindFailed = MutableStateFlow(false)
    val bindFailed: StateFlow<Boolean> = _bindFailed.asStateFlow()

    private val lanSightings = ConcurrentHashMap<String, LanPeer>()
    private val overlaySightings = ConcurrentHashMap<String, LanPeer>()
    private val lastKnownPeers = ConcurrentHashMap<String, LanPeer>()
    private var socket: DatagramSocket? = null
    @Volatile private var overlaySocket: DatagramSocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var pruneJob: Job? = null
    private val announceSignal = Channel<Unit>(Channel.CONFLATED)
    private val pruneSignal = Channel<Unit>(Channel.CONFLATED)
    private val connectedPeerPaths = ConcurrentHashMap<String, Boolean>()
    private val lastImmediateResponseMs = ConcurrentHashMap<String, Long>()

    private var selfUid: String = ""
    private var selfName: String = ""
    private var tcpPort: Int = 0
    private var selfOverlayHost: String? = null
    private var overlayNetwork: OverlayNetwork? = null
    private val overlayProbeTargets = ConcurrentHashMap<String, String>()

    @Synchronized
    fun start(uid: String, displayName: String, tcpPort: Int) {
        stop(clearPresence = true)
        selfUid = uid
        selfName = displayName
        this.tcpPort = tcpPort
        selfOverlayHost = null
        openSocketAndLoops()
    }

    /** Update only the routed overlay sender; the LAN listener survives VPN churn. */
    @Synchronized
    fun updateOverlayNetwork(network: OverlayNetwork?) {
        if (overlayNetwork == network) return
        runCatching { overlaySocket?.close() }
        overlaySocket = null
        overlayNetwork = network
        selfOverlayHost = network?.ipv4Address
        val binder = networkSocketBinder
        if (network != null && binder != null) {
            overlaySocket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    binder.bindSocket(network.networkHandle, this)
                    bind(InetSocketAddress(0))
                }
            }.onFailure { error ->
                Log.w(TAG, "overlay sender unavailable: ${error.message}")
            }.getOrNull()
        }
        announceSignal.trySend(Unit)
    }

    fun setOverlayProbeTargets(targets: Map<String, String>) {
        val updated = targets.mapNotNull { (uid, host) ->
            if (uid.isNotBlank() && host.isNotBlank() && uid != selfUid) {
                uid to host
            } else null
        }.toMap()
        if (overlayProbeTargets == updated) return
        overlayProbeTargets.clear()
        overlayProbeTargets.putAll(updated)
        announceSignal.trySend(Unit)
    }

    /** Wake discovery immediately after a semantic LAN transition. */
    fun requestAnnouncement() {
        announceSignal.trySend(Unit)
    }

    /** Healthy TCP peers stay present without requiring a three-second UDP heartbeat. */
    fun setConnectedPeers(viaOverlayByUid: Map<String, Boolean>) {
        val updated = viaOverlayByUid.filterKeys { it != selfUid }
        if (connectedPeerPaths == updated) return
        connectedPeerPaths.clear()
        connectedPeerPaths.putAll(updated)
        publish()
        pruneSignal.trySend(Unit)
        announceSignal.trySend(Unit)
    }

    @Synchronized
    fun stop(clearPresence: Boolean = true) {
        closeSocketAndLoops()
        runCatching { overlaySocket?.close() }
        overlaySocket = null
        overlayNetwork = null
        selfOverlayHost = null
        overlayProbeTargets.clear()
        connectedPeerPaths.clear()
        lastImmediateResponseMs.clear()
        _bindFailed.value = false
        if (clearPresence) {
            lanSightings.clear()
            overlaySightings.clear()
            lastKnownPeers.clear()
            publish()
        }
    }

    /**
     * Drops LAN sightings only (previous-subnet IPs). Used on connectivity change.
     */
    @Synchronized
    fun clearLanSightings() {
        if (lanSightings.isEmpty()) {
            // Still refresh the snapshot so callers see overlay-only presence.
            publish()
            return
        }
        lanSightings.clear()
        publish()
    }

    /** Remove only sightings that no longer resolve through a currently valid LAN. */
    @Synchronized
    fun removeInvalidLanSightings(isValid: (LanPeer) -> Boolean) {
        val invalidUids = lanSightings.values.filterNot(isValid).map { it.uid }
        if (invalidUids.isEmpty()) return
        invalidUids.forEach(lanSightings::remove)
        publish()
    }

    /** Drops live overlay sightings (TCP registry is cleared separately by the engine). */
    @Synchronized
    fun clearOverlaySightings() {
        if (overlaySightings.isEmpty()) {
            publish()
            return
        }
        overlaySightings.clear()
        publish()
    }

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

    private fun closeSocketAndLoops() {
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
    }

    internal fun announcedOverlayHostForTest(): String? = selfOverlayHost

    private suspend fun announcementLoop() {
        val burstDelays = ArrayDeque(listOf(STARTUP_BURST_SECOND_MS, STARTUP_BURST_THIRD_MS))
        while (scope.isActive && socket?.isClosed == false) {
            val payload = encode(selfUid, selfName, tcpPort, selfOverlayHost)
            broadcastToAllInterfaces(payload)
            overlayProbeTargets.values.distinct().forEach { host ->
                sendOverlay(payload, host)
            }
            val waitMs = burstDelays.removeFirstOrNull()
                ?: steadyAnnouncementIntervalMs(
                    hasDisconnectedPeer = hasDisconnectedPeer(),
                )
            withTimeoutOrNull(waitMs) { announceSignal.receive() }
        }
    }

    private fun hasDisconnectedPeer(): Boolean {
        val relevantUids = lanSightings.keys + overlaySightings.keys + overlayProbeTargets.keys
        return relevantUids.any { !connectedPeerPaths.containsKey(it) }
    }

    private fun broadcastToAllInterfaces(payload: ByteArray) {
        val targets = mutableSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { targets.add(it) }
                }
            }
        }
        targets.forEach { addr ->
            runCatching {
                socket?.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
            }
        }
    }

    private fun sendOverlay(payload: ByteArray, host: String) {
        runCatching {
            val address = InetAddress.getByName(host)
            overlaySocket?.send(DatagramPacket(payload, payload.size, address, BEACON_PORT))
        }
    }

    private fun sendDirectResponse(host: String, viaOverlay: Boolean) {
        val payload = encode(selfUid, selfName, tcpPort, selfOverlayHost)
        if (viaOverlay) {
            sendOverlay(payload, host)
        } else {
            runCatching {
                val address = InetAddress.getByName(host)
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
            } catch (e: IOException) {
                break
            }
            val decoded = runCatching { decode(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
            if (decoded.uid.isBlank() || decoded.uid == selfUid) continue
            val host = packet.address?.hostAddress ?: continue
            // CGNAT alone is not a path identity: classify it as overlay only while a
            // Tailscale network has been positively resolved for this app.
            val viaOverlay = overlayNetwork != null && TailscaleInterface.isCgnatAddress(host)
            val peer = LanPeer(
                uid = decoded.uid,
                displayName = decoded.displayName,
                host = host,
                port = decoded.port,
                lastSeenMs = System.currentTimeMillis(),
                overlayHost = decoded.overlayHost ?: if (viaOverlay) host else null,
                viaOverlay = viaOverlay,
            )
            if (viaOverlay) {
                overlaySightings[decoded.uid] = peer
            } else {
                lanSightings[decoded.uid] = peer
            }
            lastKnownPeers[decoded.uid] = peer
            publish()
            pruneSignal.trySend(Unit)
            if (shouldRespondImmediately(decoded.uid, peer.lastSeenMs)) {
                sendDirectResponse(host, viaOverlay)
            }
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val expiringSightings = lanSightings.values + overlaySightings.values
            val nextExpiry = expiringSightings.minOfOrNull { it.lastSeenMs + STALE_MS }
            if (nextExpiry == null) {
                pruneSignal.receive()
                continue
            }
            withTimeoutOrNull((nextExpiry - now).coerceAtLeast(1L)) { pruneSignal.receive() }
            val pruneNow = System.currentTimeMillis()
            var changed = false
            lanSightings.filterValues { pruneNow - it.lastSeenMs > STALE_MS }.keys.forEach {
                lanSightings.remove(it)
                changed = true
            }
            overlaySightings.filterValues { pruneNow - it.lastSeenMs > STALE_MS }.keys.forEach {
                overlaySightings.remove(it)
                changed = true
            }
            if (changed) publish()
        }
    }

    private fun shouldRespondImmediately(uid: String, nowMs: Long): Boolean {
        val previous = lastImmediateResponseMs.put(uid, nowMs) ?: return true
        return if (nowMs - previous >= IMMEDIATE_RESPONSE_RATE_LIMIT_MS) {
            true
        } else {
            lastImmediateResponseMs[uid] = previous
            false
        }
    }

    private fun publish() {
        val lan = lanSightings.toMap()
        val overlay = overlaySightings.toMap()
        _presence.value = PresenceSnapshot(lanSightings = lan, overlaySightings = overlay)
        _peers.value = mergedBeaconPeers(lan, overlay, connectedPeerPaths, lastKnownPeers)
    }

    private data class Decoded(
        val uid: String,
        val displayName: String,
        val port: Int,
        val overlayHost: String?,
    )

    private fun encode(uid: String, displayName: String, port: Int, overlayHost: String?): ByteArray {
        val safeName = displayName.replace(DELIMITER, ' ')
        val overlay = overlayHost?.takeIf { it.isNotBlank() }?.replace(DELIMITER, ' ')
        val text = if (overlay == null) {
            "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port"
        } else {
            "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port$DELIMITER$overlay"
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Decoded? {
        val text = String(bytes, StandardCharsets.UTF_8)
        val parts = text.split(DELIMITER)
        if (parts.isEmpty() || parts[0] != PROTOCOL_VERSION.toString()) return null
        if (parts.size != 4 && parts.size != 5) return null
        val port = parts[3].toIntOrNull() ?: return null
        return Decoded(
            uid = parts[1],
            displayName = parts[2],
            port = port,
            overlayHost = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        private const val TAG = "LanBeacon"
        const val BEACON_PORT = 47100
        /** Calm join/roster cadence — path failover is owned by TCP health, not beacons. */
        const val BROADCAST_INTERVAL_MS = 3_000L
        const val CONNECTED_SAFETY_INTERVAL_MS = 30_000L
        private const val STARTUP_BURST_SECOND_MS = 1_000L
        private const val STARTUP_BURST_THIRD_MS = 2_000L
        private const val IMMEDIATE_RESPONSE_RATE_LIMIT_MS = 1_000L
        /**
         * Drop a sighting after five missed announces. Presence staleness only affects
         * roster display and warm-standby priming — failover is owned by TCP health —
         * so a couple of lost UDP packets must not flap a peer offline.
         */
        const val STALE_MS = 5 * BROADCAST_INTERVAL_MS
        @Deprecated("Presence pruning is deadline-driven")
        const val PRUNE_INTERVAL_MS = 1_000L
        /** Warm Tailscale standby after one missed announce interval while LAN still listed. */
        const val MISSED_BEACON_MS = BROADCAST_INTERVAL_MS
        private const val PROTOCOL_VERSION = 1
        private const val DELIMITER = '\u0001'

        fun encodeForTest(uid: String, displayName: String, port: Int, overlayHost: String? = null): ByteArray {
            val safeName = displayName.replace(DELIMITER, ' ')
            val overlay = overlayHost?.takeIf { it.isNotBlank() }
            val text = if (overlay == null) {
                "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port"
            } else {
                "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port$DELIMITER$overlay"
            }
            return text.toByteArray(StandardCharsets.UTF_8)
        }

        fun decodeForTest(bytes: ByteArray): Decoded? {
            val text = String(bytes, StandardCharsets.UTF_8)
            val parts = text.split(DELIMITER)
            if (parts.isEmpty() || parts[0] != PROTOCOL_VERSION.toString()) return null
            if (parts.size != 4 && parts.size != 5) return null
            val port = parts[3].toIntOrNull() ?: return null
            return Decoded(
                uid = parts[1],
                displayName = parts[2],
                port = port,
                overlayHost = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
            )
        }

        data class Decoded(
            val uid: String,
            val displayName: String,
            val port: Int,
            val overlayHost: String?,
        )
    }
}

internal fun steadyAnnouncementIntervalMs(hasDisconnectedPeer: Boolean): Long =
    if (hasDisconnectedPeer) LanBeacon.BROADCAST_INTERVAL_MS else LanBeacon.CONNECTED_SAFETY_INTERVAL_MS

internal fun mergedBeaconPeers(
    lan: Map<String, LanPeer>,
    overlay: Map<String, LanPeer>,
    connectedPaths: Map<String, Boolean>,
    lastKnown: Map<String, LanPeer>,
): List<LanPeer> = (lan.keys + overlay.keys + connectedPaths.keys).mapNotNull { uid ->
    val peer = lan[uid] ?: overlay[uid] ?: lastKnown[uid] ?: return@mapNotNull null
    connectedPaths[uid]?.let { viaOverlay -> peer.copy(viaOverlay = viaOverlay) } ?: peer
}
