package com.nblaisot.voxcrew.audio

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineStateTest {
    private val speaker = TelecomEndpoint("speaker", "Speaker", CallEndpointCompat.TYPE_SPEAKER)
    private val bluetooth = TelecomEndpoint("buds", "Galaxy Buds", CallEndpointCompat.TYPE_BLUETOOTH)

    @Test
    fun readinessRequiresActiveCallAndMatchingConfirmedEndpoint() {
        val call = TelecomCallState(
            phase = TelecomCallPhase.ACTIVE,
            currentEndpoint = speaker,
            selectedEndpoint = speaker,
            availableEndpoints = listOf(speaker, bluetooth),
        )
        val speakerPipeline = AudioPipelineState.Ready(
            endpointKey = call.endpointKey!!,
            observedInput = ObservedAudioDeviceKind.BUILTIN,
            observedOutput = ObservedAudioDeviceKind.BUILTIN,
        )
        val bluetoothPipeline = speakerPipeline.copy(
            endpointKey = "${bluetooth.type}:${bluetooth.identifier}",
        )

        assertTrue(isConfirmedDuplexReady(call, speakerPipeline))
        assertFalse(isConfirmedDuplexReady(call, bluetoothPipeline))
        assertFalse(isConfirmedDuplexReady(call.copy(phase = TelecomCallPhase.INACTIVE), speakerPipeline))
        assertFalse(isConfirmedDuplexReady(call, AudioPipelineState.Failed("read failed")))
    }

    @Test
    fun staleAvailabilityDoesNotAffectReadiness() {
        val call = TelecomCallState(
            phase = TelecomCallPhase.ACTIVE,
            currentEndpoint = speaker,
            selectedEndpoint = speaker,
            availableEndpoints = listOf(bluetooth),
        )
        val pipeline = AudioPipelineState.Ready(
            endpointKey = call.endpointKey!!,
            observedInput = ObservedAudioDeviceKind.BUILTIN,
            observedOutput = ObservedAudioDeviceKind.BUILTIN,
        )

        assertTrue(isConfirmedDuplexReady(call, pipeline))
    }

    @Test
    fun currentEndpointIsObservedButNotReadyUntilItMatchesUserSelection() {
        val call = TelecomCallState(
            phase = TelecomCallPhase.ACTIVE,
            currentEndpoint = speaker,
            selectedEndpoint = bluetooth,
            availableEndpoints = listOf(speaker, bluetooth),
        )
        val pipeline = AudioPipelineState.Ready(
            endpointKey = call.endpointKey!!,
            observedInput = ObservedAudioDeviceKind.BUILTIN,
            observedOutput = ObservedAudioDeviceKind.BUILTIN,
        )

        assertFalse(call.mediaActive)
        assertFalse(isConfirmedDuplexReady(call, pipeline))
    }
}
