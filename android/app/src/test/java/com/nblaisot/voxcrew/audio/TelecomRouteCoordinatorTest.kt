package com.nblaisot.voxcrew.audio

import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomRouteCoordinatorTest {
    private val earpiece = endpoint("earpiece", CallEndpointCompat.TYPE_EARPIECE)
    private val speaker = endpoint("speaker", CallEndpointCompat.TYPE_SPEAKER)
    private val watch = endpoint("watch", CallEndpointCompat.TYPE_BLUETOOTH)
    private val buds = endpoint("buds", CallEndpointCompat.TYPE_BLUETOOTH)

    @Test
    fun platformEventsNeverRequestARoute() = runTest {
        val harness = Harness(selected = buds)

        harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)
        repeat(3) {
            harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
            harness.coordinator.onCurrentEndpoint(speaker)
            harness.coordinator.onActive()
        }

        assertTrue(harness.requests.isEmpty())
        assertEquals(ManualRouteStatus.DIVERGED, harness.status)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun explicitBluetoothChoiceRequestsTheExactEndpointOnce() = runTest {
        val harness = activeHarness(selected = speaker)

        val result = harness.coordinator.onUserSelected(choice(buds))

        assertEquals(ManualRouteCommandResult.Accepted, result)
        assertEquals(listOf(buds), harness.requests)
        assertEquals(ManualRouteStatus.REQUESTING, harness.status)
        assertFalse(harness.state.mediaActive)

        harness.coordinator.onCurrentEndpoint(buds)
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun watchAndBudsRemainIndependentManualTargets() = runTest {
        val harness = activeHarness(selected = speaker)

        harness.coordinator.onUserSelected(choice(watch))
        harness.coordinator.onCurrentEndpoint(watch)
        harness.coordinator.onUserSelected(choice(buds))

        assertEquals(listOf(watch, buds), harness.requests)
        assertEquals(buds, harness.state.selectedEndpoint)
    }

    @Test
    fun selectingTheCurrentEndpointConfirmsWithoutARequest() = runTest {
        val harness = activeHarness(selected = speaker)

        val result = harness.coordinator.onUserSelected(deviceChoice())

        assertEquals(ManualRouteCommandResult.Accepted, result)
        assertTrue(harness.requests.isEmpty())
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun deviceChoiceResolvesSpeakerAndNeverEarpiece() = runTest {
        val harness = Harness(selected = earpiece)
        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker, buds))
        harness.coordinator.onCurrentEndpoint(earpiece)
        harness.coordinator.onActivationResult(true)

        harness.coordinator.onUserSelected(deviceChoice())

        assertEquals(listOf(speaker), harness.requests)
        assertEquals(CallEndpointCompat.TYPE_SPEAKER, harness.state.selectedEndpoint?.type)
    }

    @Test
    fun aSecondClickIsRejectedWhileTheFirstRequestIsRunning() = runTest {
        val requestGate = CompletableDeferred<Unit>()
        val harness = activeHarness(selected = speaker, requestGate = requestGate)

        val first = async { harness.coordinator.onUserSelected(choice(buds)) }
        harness.requestStarted.await()
        val second = harness.coordinator.onUserSelected(choice(watch))
        requestGate.complete(Unit)

        assertEquals(ManualRouteCommandResult.Busy, second)
        assertEquals(ManualRouteCommandResult.Accepted, first.await())
        assertEquals(listOf(buds), harness.requests)
    }

    @Test
    fun failedRequestPublishesFailureAndErrorCode() = runTest {
        val harness = activeHarness(
            selected = speaker,
            requestResult = EndpointRequestResult(errorCode = 1),
        )

        val result = harness.coordinator.onUserSelected(choice(buds))

        assertEquals(ManualRouteCommandResult.Failed, result)
        assertEquals(ManualRouteStatus.FAILED, harness.status)
        assertEquals(1, harness.errorCode)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun removedAccessoryBecomesUnavailableWithoutFallbackOrRequest() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(buds)
        harness.coordinator.onActivationResult(true)
        assertTrue(harness.state.mediaActive)

        harness.coordinator.onAvailableEndpoints(listOf(speaker))
        harness.coordinator.onCurrentEndpoint(speaker)

        assertEquals(ManualRouteStatus.UNAVAILABLE, harness.status)
        assertEquals(buds, harness.state.selectedEndpoint)
        assertTrue(harness.requests.isEmpty())
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun inactiveAndDisconnectStatesNeverExposeReadyMedia() = runTest {
        val harness = activeHarness(selected = speaker)
        assertTrue(harness.state.mediaActive)

        harness.coordinator.onInactive()
        assertEquals(TelecomCallPhase.INACTIVE, harness.state.phase)
        assertFalse(harness.state.mediaActive)

        harness.coordinator.onDisconnected()
        assertEquals(TelecomCallPhase.STOPPED, harness.state.phase)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun activationFailureIsFatalAndTimerFree() = runTest {
        val harness = Harness(selected = speaker)

        harness.coordinator.onActivationResult(false)

        assertEquals(TelecomCallPhase.FAILED, harness.state.phase)
        assertEquals(AudioSessionIssue.TELECOM_UNAVAILABLE, harness.state.sessionIssue)
        assertEquals(ManualRouteStatus.FAILED, harness.status)
        assertFalse(harness.state.mediaActive)
    }

    private suspend fun activeHarness(
        selected: TelecomEndpoint,
        requestResult: EndpointRequestResult = EndpointRequestResult(success = true),
        requestGate: CompletableDeferred<Unit>? = null,
    ): Harness = Harness(selected, requestResult, requestGate).also { harness ->
        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker, watch, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)
    }

    private fun endpoint(id: String, type: Int) = TelecomEndpoint(id, id, type)

    private fun deviceChoice() = deviceAudioRouteChoice(speaker.identifier)

    private fun choice(endpoint: TelecomEndpoint) = AudioRouteChoice(
        key = "endpoint:${endpoint.identifier}",
        name = endpoint.name,
        inputKind = if (endpoint.type == CallEndpointCompat.TYPE_BLUETOOTH) {
            CaptureInputKind.BLUETOOTH
        } else {
            CaptureInputKind.WIRED
        },
        target = if (endpoint.type == CallEndpointCompat.TYPE_BLUETOOTH) {
            AudioRouteTarget.BLUETOOTH
        } else {
            AudioRouteTarget.WIRED_USB
        },
        endpointIdentifier = endpoint.identifier,
        endpointType = endpoint.type,
    )

    private class Harness(
        selected: TelecomEndpoint,
        private val requestResult: EndpointRequestResult = EndpointRequestResult(success = true),
        private val requestGate: CompletableDeferred<Unit>? = null,
    ) {
        val requests = mutableListOf<TelecomEndpoint>()
        val requestStarted = CompletableDeferred<Unit>()
        var state = TelecomCallState()
        var status = ManualRouteStatus.STARTING
        var errorCode: Int? = null
        val coordinator = TelecomRouteCoordinator(
            requestEndpoint = {
                requests += it
                requestStarted.complete(Unit)
                requestGate?.await()
                requestResult
            },
            publishState = { published, _ -> state = published },
            publishRouteStatus = { published, error ->
                status = published
                errorCode = error
            },
            selectedEndpoint = selected,
        )
    }
}
