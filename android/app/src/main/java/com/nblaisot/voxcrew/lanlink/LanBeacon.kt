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
 * Peer discovery via periodic UDP broadcast — deliberately not mDNS/NSD (the flaky
 * part of the previous implementation). Every instance broadcasts its identity on a
 * fixed port to the subnet-directed broadcast address of every active interface, and
 * listens for the same. This works identically whether both devices are plain WiFi
 * clients on the same AP, or one device is the mobile hotspot and the other connects
 * to it — both cases put the two devices on the same broadcast domain.
 *
 * Beacon payload (v1): `1<SOH>uid<SOH>displayName<SOH>tcpPort`
 * Extended (overlay): `1<SOH>uid<SOH>displayName<SOH>tcpPort<SOH>overlayHost`
 */
class LanBeacon(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, LanPeer>()
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
        stop()
        selfUid = uid
        selfName = displayName
        this.tcpPort = tcpPort
        selfOverlayHost = overlayHost?.takeIf { it.isNotBlank() }

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

    fun setOverlayProbeTargets(targets: Map<String, String>) {
        overlayProbeTargets.clear()
        targets.forEach { (uid, host) ->
            if (uid.isNotBlank() && host.isNotBlank() && uid != selfUid) {
                overlayProbeTargets[uid] = host
            }
        }
    }

    @Synchronized
    fun stop() {
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
        overlayProbeTargets.clear()
        peerMap.clear()
        _peers.value = emptyList()
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
            peerMap[decoded.uid] = LanPeer(
                uid = decoded.uid,
                displayName = decoded.displayName,
                host = if (viaOverlay) host else host,
                port = decoded.port,
                lastSeenMs = System.currentTimeMillis(),
                overlayHost = decoded.overlayHost ?: if (viaOverlay) host else null,
                viaOverlay = viaOverlay,
            )
            _peers.value = peerMap.values.toList()
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            delay(PRUNE_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val stale = peerMap.filterValues { now - it.lastSeenMs > STALE_MS }.keys
            if (stale.isNotEmpty()) {
                stale.forEach { peerMap.remove(it) }
                _peers.value = peerMap.values.toList()
            }
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
        /** Faster cadence so LAN loss is noticed within ~1.5–2.5s. */
        const val BROADCAST_INTERVAL_MS = 1_000L
        const val STALE_MS = 2_500L
        const val PRUNE_INTERVAL_MS = 250L
        /** After one missed beacon, warm Tailscale standby while LAN may still recover. */
        const val MISSED_BEACON_MS = 1_000L
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
