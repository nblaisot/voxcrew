package com.nblaisot.voxcrew.connectivity.discovery

import kotlinx.coroutines.flow.StateFlow

data class DiscoveredLocalPeer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val sessionIdHash: String?,
    val instanceId: String?,
    val discoveredAtMs: Long = System.currentTimeMillis(),
)

enum class LocalDiscoveryState {
    STOPPED,
    STARTING,
    DISCOVERING,
    ERROR,
}

interface LocalPeerDiscovery {
    val discoveredPeers: StateFlow<List<DiscoveredLocalPeer>>
    val state: StateFlow<LocalDiscoveryState>

    suspend fun start(sessionId: String)
    suspend fun stop()

    fun registerHost(port: Int, instanceId: String) = Unit

    fun unregisterHost() = Unit

    fun freshDiscoveredPeers(maxAgeMs: Long = 30_000): List<DiscoveredLocalPeer> =
        discoveredPeers.value.filter { System.currentTimeMillis() - it.discoveredAtMs <= maxAgeMs }
}
