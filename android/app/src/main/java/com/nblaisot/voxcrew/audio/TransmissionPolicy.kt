package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.flow.StateFlow

interface TransmissionPolicy {
    val mode: TransmissionMode
    val shouldTransmit: StateFlow<Boolean>
}
