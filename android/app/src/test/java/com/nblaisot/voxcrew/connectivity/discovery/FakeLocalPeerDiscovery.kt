package com.nblaisot.voxcrew.connectivity.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeLocalPeerDiscovery : LocalPeerDiscovery {
    private val _peers = MutableStateFlow<List<DiscoveredLocalPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredLocalPeer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(LocalDiscoveryState.STOPPED)
    override val state: StateFlow<LocalDiscoveryState> = _state.asStateFlow()

    fun setPeers(peers: List<DiscoveredLocalPeer>) {
        _peers.value = peers
    }

    override suspend fun start(sessionId: String) {
        _state.value = LocalDiscoveryState.DISCOVERING
    }

    override suspend fun stop() {
        _peers.value = emptyList()
        _state.value = LocalDiscoveryState.STOPPED
    }
}
