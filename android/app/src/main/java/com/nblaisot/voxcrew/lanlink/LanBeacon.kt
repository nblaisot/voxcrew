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

    private var selfUid: String = ""
    private var selfName: String = ""
    private var tcpPort: Int = 0

    @Synchronized
    fun start(uid: String, displayName: String, tcpPort: Int) {
        stop()
        selfUid = uid
        selfName = displayName
        this.tcpPort = tcpPort

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
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        broadcastJob?.cancel()
        listenJob?.cancel()
        pruneJob?.cancel()
        broadcastJob = null
        listenJob = null
        pruneJob = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        peerMap.clear()
        _peers.value = emptyList()
    }

    private suspend fun broadcastLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val payload = encode(selfUid, selfName, tcpPort)
            broadcastToAllInterfaces(payload)
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
            peerMap[decoded.uid] = LanPeer(decoded.uid, decoded.displayName, host, decoded.port, System.currentTimeMillis())
            _peers.value = peerMap.values.toList()
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val stale = peerMap.filterValues { now - it.lastSeenMs > STALE_MS }.keys
            if (stale.isNotEmpty()) {
                stale.forEach { peerMap.remove(it) }
                _peers.value = peerMap.values.toList()
            }
        }
    }

    private data class Decoded(val uid: String, val displayName: String, val port: Int)

    private fun encode(uid: String, displayName: String, port: Int): ByteArray {
        val safeName = displayName.replace(DELIMITER, ' ')
        return "$PROTOCOL_VERSION$DELIMITER$uid$DELIMITER$safeName$DELIMITER$port"
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Decoded? {
        val text = String(bytes, StandardCharsets.UTF_8)
        val parts = text.split(DELIMITER)
        if (parts.size != 4 || parts[0] != PROTOCOL_VERSION.toString()) return null
        val port = parts[3].toIntOrNull() ?: return null
        return Decoded(uid = parts[1], displayName = parts[2], port = port)
    }

    companion object {
        private const val TAG = "LanBeacon"
        const val BEACON_PORT = 47100
        const val BROADCAST_INTERVAL_MS = 2_000L
        const val STALE_MS = 6_000L
        private const val PROTOCOL_VERSION = 1
        private const val DELIMITER = '\u0001'
    }
}
