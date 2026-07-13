package com.nblaisot.voxcrew.audio

import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomRouteCoordinatorTest {
    private val earpiece = endpoint("earpiece", CallEndpointCompat.TYPE_EARPIECE)
    private val speaker = endpoint("speaker", CallEndpointCompat.TYPE_SPEAKER)
    private val watch = endpoint("watch", CallEndpointCompat.TYPE_BLUETOOTH)
    private val buds = endpoint("buds", CallEndpointCompat.TYPE_BLUETOOTH)

    @Test
    fun exactSelectedBluetoothEndpointWinsOverOtherBluetoothDevices() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)

        assertEquals(listOf(buds), harness.requests)
        assertEquals(buds, harness.state.selectedEndpoint)
        assertEquals(speaker, harness.state.currentEndpoint)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun mismatchedCurrentEndpointNeverOpensMedia() = runTest {
        val harness = Harness(selected = speaker)
        harness.coordinator.onAvailableEndpoints(listOf(earpiece, speaker))
        harness.coordinator.onCurrentEndpoint(earpiece)
        harness.coordinator.onActivationResult(true)

        assertFalse(harness.state.selectionConfirmed)
        assertFalse(harness.state.mediaActive)
        assertEquals(listOf(speaker), harness.requests)
    }

    @Test
    fun confirmationMakesExactSelectionReady() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)
        harness.coordinator.onCurrentEndpoint(buds)

        assertTrue(harness.state.selectionConfirmed)
        assertTrue(harness.state.mediaActive)
        assertNull(harness.state.routeRequestWarning)
    }

    @Test
    fun duplicateMismatchEventsDoNotDuplicateRequests() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)

        repeat(3) {
            harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
            harness.coordinator.onCurrentEndpoint(speaker)
        }

        assertEquals(listOf(buds), harness.requests)
    }

    @Test
    fun explicitSelectionChangeRequestsTheNewEndpointOnce() = runTest {
        val harness = Harness(selected = speaker)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, watch, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)

        harness.coordinator.onSelectedEndpoint(buds)
        harness.coordinator.onSelectedEndpoint(buds)

        assertEquals(listOf(buds), harness.requests)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun platformMovingAwayAfterConfirmationCausesOneNewRequest() = runTest {
        val harness = Harness(selected = buds)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(buds)
        harness.coordinator.onActivationResult(true)

        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onCurrentEndpoint(speaker)

        assertEquals(listOf(buds), harness.requests)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun failedRequestKeepsMediaClosedAndPublishesWarning() = runTest {
        val harness = Harness(selected = buds, requestSucceeds = false)
        harness.coordinator.onAvailableEndpoints(listOf(speaker, buds))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)

        assertFalse(harness.state.mediaActive)
        assertNull(harness.state.sessionIssue)
        assertEquals(RouteRequestWarning.ENDPOINT_CHANGE_FAILED, harness.state.routeRequestWarning)
    }

    @Test
    fun inactiveAndDisconnectStatesNeverExposeReadyMedia() = runTest {
        val harness = Harness(selected = speaker)
        harness.coordinator.onAvailableEndpoints(listOf(speaker))
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onActivationResult(true)
        assertTrue(harness.state.mediaActive)

        harness.coordinator.onInactive()
        assertEquals(TelecomCallPhase.INACTIVE, harness.state.phase)
        assertFalse(harness.state.mediaActive)

        harness.coordinator.onDisconnected()
        assertEquals(TelecomCallPhase.STOPPED, harness.state.phase)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun addedCallRemainsStartingUntilActivation() = runTest {
        val harness = Harness(selected = speaker)
        harness.coordinator.onCurrentEndpoint(speaker)
        harness.coordinator.onCallReady()

        assertEquals(TelecomCallPhase.STARTING, harness.state.phase)
        assertFalse(harness.state.mediaActive)
    }

    @Test
    fun activationFailureIsFatalAndTimerFree() = runTest {
        val harness = Harness(selected = speaker)
        harness.coordinator.onActivationResult(false)

        assertEquals(TelecomCallPhase.FAILED, harness.state.phase)
        assertEquals(AudioSessionIssue.TELECOM_UNAVAILABLE, harness.state.sessionIssue)
        assertFalse(harness.state.mediaActive)
    }

    private fun endpoint(id: String, type: Int) = TelecomEndpoint(id, id, type)

    private class Harness(
        selected: TelecomEndpoint,
        requestSucceeds: Boolean = true,
    ) {
        val requests = mutableListOf<TelecomEndpoint>()
        var state = TelecomCallState()
        val coordinator = TelecomRouteCoordinator(
            requestEndpoint = {
                requests += it
                requestSucceeds
            },
            publishState = { published, _ -> state = published },
            selectedEndpoint = selected,
        )
    }
}
