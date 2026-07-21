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
    fun divergedSelectionStillCarriesMedia() {
        // Sound always flows: the platform's current endpoint carries audio even while
        // the user's selection is not honored yet (selection status is banner-only).
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

        assertFalse(call.selectionConfirmed)
        assertTrue(call.mediaActive)
        assertTrue(isConfirmedDuplexReady(call, pipeline))
    }

    @Test
    fun noCurrentEndpointMeansNoMedia() {
        val call = TelecomCallState(
            phase = TelecomCallPhase.ACTIVE,
            currentEndpoint = null,
            selectedEndpoint = speaker,
            availableEndpoints = listOf(speaker),
        )

        assertFalse(call.mediaActive)
    }
}
