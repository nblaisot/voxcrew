package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OpenMicTransmissionPolicy : TransmissionPolicy {
    override val mode: TransmissionMode = TransmissionMode.OPEN_MIC
    private val _shouldTransmit = MutableStateFlow(true)
    override val shouldTransmit: StateFlow<Boolean> = _shouldTransmit.asStateFlow()
}
