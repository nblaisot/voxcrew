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
    fun freshCallReassertsTheSelectionExactlyOnce() = runTest {
        // A new call can come up on the wrong endpoint; the coordinator executes the
        // user's standing selection once, then a persisting mismatch is DIVERGED —
        // banner-only, audio keeps flowing.
        val harness = Harness(selected = buds)

        harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)
        repeat(3) {
            harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
            harness.coordinator.onCurrentEndpoint(speaker)
            harness.coordinator.onActive()
        }

        assertEquals(listOf(buds), harness.requests)
        assertEquals(ManualRouteStatus.DIVERGED, harness.status)
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun earpieceTransientOnFreshCallConvergesToConfirmed() = runTest {
        // FG return: new call briefly on the earpiece while "This device" means speaker.
        val harness = Harness(selected = speaker)

        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker, buds))
        harness.coordinator.onCurrentEndpoint(earpiece)
        harness.coordinator.onActivationResult(true)

        assertEquals(listOf(speaker), harness.requests)
        assertEquals(ManualRouteStatus.REQUESTING, harness.status)
        assertTrue(harness.state.mediaActive)

        harness.coordinator.onCurrentEndpoint(speaker)

        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun explicitBluetoothChoiceRequestsTheExactEndpointOnce() = runTest {
        val harness = activeHarness(selected = speaker)

        val result = harness.coordinator.onUserSelected(choice(buds))

        assertEquals(ManualRouteCommandResult.Accepted, result)
        assertEquals(listOf(buds), harness.requests)
        assertEquals(ManualRouteStatus.REQUESTING, harness.status)
        // Audio keeps flowing on the previous route while the request settles.
        assertTrue(harness.state.mediaActive)

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
        // The call itself is still ACTIVE; the session layer rebuilds it around the
        // new selection, and audio flows until it does.
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun removedAccessoryKeepsAudioAndRestoresOnReturn() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(buds)
        harness.coordinator.onActivationResult(true)
        assertTrue(harness.state.mediaActive)

        // Accessory disappears; platform re-routes. Selection is remembered, banner
        // shows UNAVAILABLE, audio keeps flowing on the fallback.
        harness.coordinator.onAvailableEndpoints(listOf(speaker))
        harness.coordinator.onCurrentEndpoint(speaker)

        assertEquals(ManualRouteStatus.UNAVAILABLE, harness.status)
        assertEquals(buds, harness.state.selectedEndpoint)
        assertTrue(harness.requests.isEmpty())
        assertTrue(harness.state.mediaActive)

        // Accessory returns: one automatic restore request brings it back.
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))

        assertEquals(listOf(buds), harness.requests)
        harness.coordinator.onCurrentEndpoint(buds)
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
    }

    @Test
    fun removedAccessoryWithEarpieceCurrentFallsBackToSpeaker() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker, buds))
        harness.coordinator.onCurrentEndpoint(buds)
        harness.coordinator.onActivationResult(true)

        // Accessory gone and the platform landed on the earpiece: request the speaker
        // once so "This device" audio is actually audible.
        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker))
        harness.coordinator.onCurrentEndpoint(earpiece)

        assertEquals(listOf(speaker), harness.requests)
        assertEquals(ManualRouteStatus.UNAVAILABLE, harness.status)
        assertTrue(harness.state.mediaActive)
    }

    @Test
    fun profileFlipSameMacStaysConfirmed() = runTest {
        // Buds re-enumerated under a new Telecom identifier (SCO -> LE Audio) but same MAC.
        val budsSco = endpoint("buds-sco", CallEndpointCompat.TYPE_BLUETOOTH, mac = "AA:BB:CC:DD:EE:FF")
        val budsLe = endpoint("buds-le", CallEndpointCompat.TYPE_BLUETOOTH, mac = "AA:BB:CC:DD:EE:FF")
        val harness = Harness(selected = budsSco)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, budsSco))
        harness.coordinator.onCurrentEndpoint(budsSco)
        harness.coordinator.onActivationResult(true)
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)

        harness.coordinator.onAvailableEndpoints(listOf(speaker, budsLe))
        harness.coordinator.onCurrentEndpoint(budsLe)

        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
        assertTrue(harness.state.mediaActive)
        assertTrue(harness.requests.isEmpty())
    }

    @Test
    fun differentMacStillDiverges() = runTest {
        val budsA = endpoint("buds-a", CallEndpointCompat.TYPE_BLUETOOTH, mac = "AA:AA:AA:AA:AA:AA")
        val watchB = endpoint("watch-b", CallEndpointCompat.TYPE_BLUETOOTH, mac = "BB:BB:BB:BB:BB:BB")
        val harness = Harness(selected = budsA)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, budsA, watchB))
        harness.coordinator.onCurrentEndpoint(budsA)
        harness.coordinator.onActivationResult(true)
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)

        harness.coordinator.onCurrentEndpoint(watchB)

        assertEquals(ManualRouteStatus.DIVERGED, harness.status)
        // Diverged is banner-only: audio keeps flowing on the platform's route.
        assertTrue(harness.state.mediaActive)
        // No automatic re-fight after a platform-initiated divergence.
        assertTrue(harness.requests.isEmpty())
    }

    @Test
    fun selectingCurrentDeviceByMacConfirmsWithoutRequest() = runTest {
        val budsSco = endpoint("buds-sco", CallEndpointCompat.TYPE_BLUETOOTH, mac = "AA:BB:CC:DD:EE:FF")
        val budsLe = endpoint("buds-le", CallEndpointCompat.TYPE_BLUETOOTH, mac = "AA:BB:CC:DD:EE:FF")
        val harness = Harness(selected = speaker)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, budsLe))
        harness.coordinator.onCurrentEndpoint(budsLe)
        harness.coordinator.onActivationResult(true)

        // Activation on buds while speaker is selected triggers the one auto re-assert.
        assertEquals(listOf(speaker), harness.requests)

        // Menu choice was built from the SCO enumeration; platform now reports the LE one.
        val result = harness.coordinator.onUserSelected(choice(budsSco))

        assertEquals(ManualRouteCommandResult.Accepted, result)
        // Selecting the (MAC-identical) current endpoint needs no further request.
        assertEquals(listOf(speaker), harness.requests)
        assertEquals(ManualRouteStatus.CONFIRMED, harness.status)
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

    private fun endpoint(id: String, type: Int, mac: String? = null) =
        TelecomEndpoint(id, id, type, bluetoothAddress = mac)

    private fun deviceChoice() = deviceAudioRouteChoice(speaker.identifier)

    private fun choice(endpoint: TelecomEndpoint) = AudioRouteChoice(
        key = endpoint.bluetoothAddress?.let { bluetoothAudioRouteKey(it) }
            ?: "endpoint:${endpoint.identifier}",
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
        bluetoothAddress = endpoint.bluetoothAddress,
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
