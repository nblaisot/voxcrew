package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PushToTalkTransmissionPolicy(
    private val hangoverMs: Long = 150L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TransmissionPolicy {
    override val mode: TransmissionMode = TransmissionMode.PUSH_TO_TALK
    private val _shouldTransmit = MutableStateFlow(false)
    override val shouldTransmit: StateFlow<Boolean> = _shouldTransmit.asStateFlow()

    private var releaseJob: Job? = null

    fun onPress() {
        releaseJob?.cancel()
        _shouldTransmit.value = true
    }

    fun onRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(hangoverMs)
            _shouldTransmit.value = false
        }
    }

    fun cancel() {
        releaseJob?.cancel()
        _shouldTransmit.value = false
    }
}
