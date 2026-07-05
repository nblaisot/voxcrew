package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes [VoxGate]'s transmit/hold decision (see
 * [com.nblaisot.voxcrew.lanlink.AudioCapture.attachVox]) as a [TransmissionPolicy], so
 * VOX and PTT plug into [com.nblaisot.voxcrew.lanlink.LanIntercomEngine] the same way.
 * [setSpeechDetected] is called by the VOX capture loop, never directly by the UI.
 */
class VoiceActivatedTransmissionPolicy : TransmissionPolicy {
    override val mode: TransmissionMode = TransmissionMode.VOICE_ACTIVATED
    private val _shouldTransmit = MutableStateFlow(false)
    override val shouldTransmit: StateFlow<Boolean> = _shouldTransmit.asStateFlow()

    fun setSpeechDetected(active: Boolean) {
        _shouldTransmit.value = active
    }
}
