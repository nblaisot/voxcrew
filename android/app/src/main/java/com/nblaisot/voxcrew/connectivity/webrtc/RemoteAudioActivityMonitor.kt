package com.nblaisot.voxcrew.connectivity.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport

class RemoteAudioActivityMonitor(
    private val peerConnectionProvider: () -> PeerConnection?,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 150L,
    private val holdMs: Long = 300L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private var pollJob: Job? = null
    private var lastBytesReceived: Long? = null
    private var lastActivityAtMs = 0L

    fun start() {
        stop()
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                pollStats()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        lastBytesReceived = null
        lastActivityAtMs = 0L
        _isReceiving.value = false
    }

    private fun pollStats() {
        val pc = peerConnectionProvider() ?: return
        pc.getStats(object : RTCStatsCollectorCallback {
            override fun onStatsDelivered(report: RTCStatsReport) {
                var activeNow = false
                for (stats in report.statsMap.values) {
                    if (stats.type != "inbound-rtp") continue
                    if (stats.members["kind"] != "audio") continue
                    val bytes = (stats.members["bytesReceived"] as? Number)?.toLong()
                    if (bytes != null) {
                        val previous = lastBytesReceived
                        lastBytesReceived = bytes
                        if (previous != null && bytes > previous) {
                            lastActivityAtMs = clock()
                            activeNow = true
                        }
                    }
                    val level = (stats.members["audioLevel"] as? Number)?.toDouble()
                    if (level != null && level > 0.01) {
                        lastActivityAtMs = clock()
                        activeNow = true
                    }
                }
                val now = clock()
                val receiving = activeNow ||
                    (lastActivityAtMs > 0L && now - lastActivityAtMs < holdMs)
                _isReceiving.value = receiving
            }
        })
    }
}
