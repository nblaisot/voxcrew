package com.nblaisot.voxcrew.connectivity

import com.nblaisot.voxcrew.webrtc.IceTransportState
import com.nblaisot.voxcrew.webrtc.PeerState
import com.nblaisot.voxcrew.webrtc.WebRtcDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.min

enum class ConnectionHealth {
    IDLE,
    CONNECTING,
    READY,
    ICE_DISCONNECTED,
    RECONNECTING_AUDIO,
    PEER_DISCONNECTED,
}

class ConnectionSupervisor(
    private val scope: CoroutineScope,
    private val diagnostics: StateFlow<WebRtcDiagnostics>,
    private val peerOnline: () -> Boolean,
    private val onIceRestart: suspend () -> Unit,
    private val onFullReconnect: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var watchJob: Job? = null
    private var tickerJob: Job? = null
    private var iceDisconnectedSinceMs: Long? = null
    private var fullRetryAttempt = 0
    private var lastIceRestartAtMs = 0L
    private var connectingSinceMs: Long? = null

    private val _health = kotlinx.coroutines.flow.MutableStateFlow(ConnectionHealth.IDLE)
    val health: StateFlow<ConnectionHealth> = _health

    fun start() {
        watchJob?.cancel()
        tickerJob?.cancel()
        watchJob = scope.launch {
            diagnostics.collect { diag ->
                evaluate(diag)
            }
        }
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                evaluate(diagnostics.value)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        tickerJob?.cancel()
        watchJob = null
        tickerJob = null
        iceDisconnectedSinceMs = null
        fullRetryAttempt = 0
        _health.value = ConnectionHealth.IDLE
    }

    fun onNetworkChanged() {
        scope.launch {
            _health.value = ConnectionHealth.RECONNECTING_AUDIO
            runCatching { onIceRestart() }
        }
    }

    fun onPeerLeft() {
        _health.value = ConnectionHealth.PEER_DISCONNECTED
        iceDisconnectedSinceMs = null
        fullRetryAttempt = 0
    }

    fun onSessionStarting() {
        _health.value = ConnectionHealth.CONNECTING
        iceDisconnectedSinceMs = null
        fullRetryAttempt = 0
    }

    fun onSessionReady() {
        _health.value = ConnectionHealth.READY
        iceDisconnectedSinceMs = null
        fullRetryAttempt = 0
    }

    private suspend fun evaluate(diag: WebRtcDiagnostics) {
        if (!peerOnline()) {
            _health.value = ConnectionHealth.PEER_DISCONNECTED
            return
        }

        when {
            diag.peerState == PeerState.CONNECTED &&
                (diag.iceState == IceTransportState.CONNECTED || diag.iceState == IceTransportState.COMPLETED) -> {
                iceDisconnectedSinceMs = null
                fullRetryAttempt = 0
                _health.value = ConnectionHealth.READY
            }
            diag.iceState == IceTransportState.DISCONNECTED -> {
                val now = clock()
                if (iceDisconnectedSinceMs == null) iceDisconnectedSinceMs = now
                _health.value = ConnectionHealth.ICE_DISCONNECTED
                if (now - iceDisconnectedSinceMs!! >= 5_000 &&
                    (lastIceRestartAtMs == 0L || now - lastIceRestartAtMs >= 10_000)
                ) {
                    lastIceRestartAtMs = now
                    _health.value = ConnectionHealth.RECONNECTING_AUDIO
                    runCatching { onIceRestart() }
                }
            }
            diag.iceState == IceTransportState.FAILED || diag.peerState == PeerState.FAILED -> {
                scheduleFullReconnect()
            }
            diag.peerState == PeerState.CONNECTING || diag.iceState == IceTransportState.CHECKING -> {
                if (_health.value != ConnectionHealth.RECONNECTING_AUDIO) {
                    _health.value = ConnectionHealth.CONNECTING
                }
            }
        }

        maybeRecoverFromStall()
    }

    /**
     * If the connection never progresses past CONNECTING (lost offer/answer, peer
     * restarted mid-negotiation, ...) nothing else will retry: force a full
     * reconnect after a stall timeout.
     */
    private suspend fun maybeRecoverFromStall() {
        if (_health.value != ConnectionHealth.CONNECTING) {
            connectingSinceMs = null
            return
        }
        val now = clock()
        val since = connectingSinceMs ?: now.also { connectingSinceMs = it }
        if (now - since >= CONNECT_STALL_TIMEOUT_MS) {
            connectingSinceMs = null
            scheduleFullReconnect()
        }
    }

    private suspend fun scheduleFullReconnect() {
        if (!peerOnline()) return
        _health.value = ConnectionHealth.RECONNECTING_AUDIO
        fullRetryAttempt += 1
        val backoff = min(30_000L, 2_000L shl min(fullRetryAttempt, 4))
        delay(backoff)
        if (!coroutineContext.isActive || !peerOnline()) return
        runCatching { onFullReconnect() }
    }

    private companion object {
        const val CONNECT_STALL_TIMEOUT_MS = 20_000L
    }
}
