package com.nblaisot.voxcrew.lanlink

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val context: Context,
    private val scope: CoroutineScope,
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

    private val lanSightings = ConcurrentHashMap<String, LanPeer>()
    private val overlaySightings = ConcurrentHashMap<String, LanPeer>()
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var pruneJob: Job? = null
    private var overlayProbeJob: Job? = null

    private var selfUid: String = ""
    private var selfName: String = ""
    private var tcpPort: Int = 0
    private var selfOverlayHost: String? = null
    private val overlayProbeTargets = ConcurrentHashMap<String, String>()

    @Synchronized
    fun start(uid: String, displayName: String, tcpPort: Int, overlayHost: String? = null) {
        stop(clearPresence = true)
        selfUid = uid
        selfName = displayName
        this.tcpPort = tcpPort
        selfOverlayHost = overlayHost?.takeIf { it.isNotBlank() }
        openSocketAndLoops()
    }

    /**
     * Rebind the UDP socket and refresh [selfOverlayHost] without wiping LAN/overlay
     * sightings (network-change safe).
     */
    @Synchronized
    fun rebind(overlayHost: String? = null) {
        if (selfUid.isBlank()) return
        selfOverlayHost = overlayHost?.takeIf { it.isNotBlank() }
        closeSocketAndLoops()
        openSocketAndLoops()
        publish()
    }

    fun setOverlayProbeTargets(targets: Map<String, String>) {
        overlayProbeTargets.clear()
        targets.forEach { (uid, host) ->
            if (uid.isNotBlank() && host.isNotBlank() && uid != selfUid) {
                overlayProbeTargets[uid] = host
            }
        }
    }

    @Synchronized
    fun stop(clearPresence: Boolean = true) {
        closeSocketAndLoops()
        overlayProbeTargets.clear()
        if (clearPresence) {
            lanSightings.clear()
            overlaySightings.clear()
            publish()
        }
    }

    private fun openSocketAndLoops() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("voxcrew-lan-beacon")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock unavailable: ${it.message}") }

        socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
            }
        }.onFailure { Log.w(TAG, "beacon socket bind failed: ${it.message}") }.getOrNull()

        if (socket == null) return
        listenJob = scope.launch(Dispatchers.IO) { listenLoop() }
        broadcastJob = scope.launch(Dispatchers.IO) { broadcastLoop() }
        pruneJob = scope.launch { pruneLoop() }
        overlayProbeJob = scope.launch(Dispatchers.IO) { overlayProbeLoop() }
    }

    private fun closeSocketAndLoops() {
        runCatching { socket?.close() }
        socket = null
        broadcastJob?.cancel()
        listenJob?.cancel()
        pruneJob?.cancel()
        overlayProbeJob?.cancel()
        broadcastJob = null
        listenJob = null
        pruneJob = null
        overlayProbeJob = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private suspend fun broadcastLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val payload = encode(selfUid, selfName, tcpPort, selfOverlayHost)
            broadcastToAllInterfaces(payload)
            delay(BROADCAST_INTERVAL_MS)
        }
    }

    private suspend fun overlayProbeLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val payload = encode(selfUid, selfName, tcpPort, selfOverlayHost)
            overlayProbeTargets.values.distinct().forEach { host ->
                runCatching {
                    val address = InetAddress.getByName(host)
                    socket?.send(DatagramPacket(payload, payload.size, address, BEACON_PORT))
                }
            }
            delay(BROADCAST_INTERVAL_MS)
        }
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
            val viaOverlay = TailscaleInterface.isTailscaleAddress(host)
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
            publish()
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            delay(PRUNE_INTERVAL_MS)
            val now = System.currentTimeMillis()
            var changed = false
            lanSightings.filterValues { now - it.lastSeenMs > STALE_MS }.keys.forEach {
                lanSightings.remove(it)
                changed = true
            }
            overlaySightings.filterValues { now - it.lastSeenMs > STALE_MS }.keys.forEach {
                overlaySightings.remove(it)
                changed = true
            }
            if (changed) publish()
        }
    }

    private fun publish() {
        val lan = lanSightings.toMap()
        val overlay = overlaySightings.toMap()
        _presence.value = PresenceSnapshot(lanSightings = lan, overlaySightings = overlay)
        val uids = lan.keys + overlay.keys
        _peers.value = uids.map { uid ->
            lan[uid] ?: overlay.getValue(uid)
        }
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
        /** Drop a sighting after roughly three missed announces. */
        const val STALE_MS = 9_000L
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
