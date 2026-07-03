package com.nblaisot.voxcrew.connectivity.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

class NsdLocalPeerDiscovery(
    context: Context,
) : LocalPeerDiscovery {
    companion object {
        const val SERVICE_TYPE = "_voxcrew._tcp."
        const val PROTOCOL_VERSION = "1"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val peers = mutableMapOf<String, DiscoveredLocalPeer>()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredLocalPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredLocalPeer>> = _discoveredPeers.asStateFlow()

    private val _state = MutableStateFlow(LocalDiscoveryState.STOPPED)
    override val state: StateFlow<LocalDiscoveryState> = _state.asStateFlow()

    private var sessionIdHash: String? = null

    override suspend fun start(sessionId: String) {
        stop()
        sessionIdHash = sha256(sessionId).take(16)
        _state.value = LocalDiscoveryState.STARTING
        startDiscovery()
    }

    override suspend fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        peers.clear()
        _discoveredPeers.value = emptyList()
        _state.value = LocalDiscoveryState.STOPPED
    }

    fun registerHost(port: Int, instanceId: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "voxcrew-$instanceId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("protocolVersion", PROTOCOL_VERSION)
            sessionIdHash?.let { setAttribute("sessionIdHash", it) }
            setAttribute("hostRole", "host")
            setAttribute("instanceId", instanceId)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                _state.value = LocalDiscoveryState.ERROR
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _state.value = LocalDiscoveryState.DISCOVERING
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val hash = info.attributes["sessionIdHash"]?.let { String(it) }
                        if (sessionIdHash != null && hash != null && hash != sessionIdHash) return
                        val host = info.host?.hostAddress ?: return
                        val peer = DiscoveredLocalPeer(
                            serviceName = info.serviceName,
                            host = host,
                            port = info.port,
                            sessionIdHash = hash,
                            instanceId = info.attributes["instanceId"]?.let { String(it) },
                        )
                        peers[info.serviceName] = peer
                        _discoveredPeers.value = peers.values.toList()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                peers.remove(service.serviceName)
                _discoveredPeers.value = peers.values.toList()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                _state.value = LocalDiscoveryState.ERROR
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) = Unit
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
