package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stub for future VAD integration — not active in MVP audio path. */
class VoiceActivatedTransmissionPolicy : TransmissionPolicy {
    override val mode: TransmissionMode = TransmissionMode.VOICE_ACTIVATED
    private val _shouldTransmit = MutableStateFlow(false)
    override val shouldTransmit: StateFlow<Boolean> = _shouldTransmit.asStateFlow()

    fun setSpeechDetected(active: Boolean) {
        _shouldTransmit.value = active
    }
}
