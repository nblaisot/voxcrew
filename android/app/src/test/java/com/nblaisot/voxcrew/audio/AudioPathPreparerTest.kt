package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPathPreparerTest {

    @Test
    fun prepare_warmUpBeforeCaptureAttach() {
        val order = mutableListOf<String>()

        val result = AudioPathPreparer.prepare(
            isSessionActive = true,
            awaitRouteReady = {
                order += "routeReady"
                true
            },
            awaitRoutingApplied = { order += "routingApplied" },
            warmUpPlayback = { order += "warmUp" },
            detachCapture = { order += "detach" },
            attachCapture = { order += "attach" },
            audioPrepared = false,
            forceReattach = false,
        )

        assertEquals(
            listOf("routeReady", "routingApplied", "warmUp", "detach", "attach"),
            order,
        )
        assertEquals(
            listOf(
                AudioPathPreparer.Step.ROUTE_READY,
                AudioPathPreparer.Step.ROUTING_APPLIED,
                AudioPathPreparer.Step.PLAYBACK_WARM_UP,
                AudioPathPreparer.Step.CAPTURE_ATTACH,
            ),
            result.completedSteps,
        )
        assertTrue(result.captureAttached)
    }

    @Test
    fun prepare_defersCaptureWhenRouteNotReady() {
        val order = mutableListOf<String>()

        val result = AudioPathPreparer.prepare(
            isSessionActive = true,
            awaitRouteReady = {
                order += "routeReady"
                false
            },
            awaitRoutingApplied = { order += "routingApplied" },
            warmUpPlayback = { order += "warmUp" },
            detachCapture = { order += "detach" },
            attachCapture = { order += "attach" },
            audioPrepared = false,
            forceReattach = false,
        )

        assertEquals(listOf("routeReady"), order)
        assertFalse(result.captureAttached)
    }

    @Test
    fun prepare_skipsCaptureWhenAlreadyPreparedWithoutForce() {
        val order = mutableListOf<String>()

        val result = AudioPathPreparer.prepare(
            isSessionActive = true,
            awaitRouteReady = {
                order += "routeReady"
                true
            },
            awaitRoutingApplied = { order += "routingApplied" },
            warmUpPlayback = { order += "warmUp" },
            detachCapture = { order += "detach" },
            attachCapture = { order += "attach" },
            audioPrepared = true,
            forceReattach = false,
        )

        assertEquals(listOf("routeReady", "routingApplied", "warmUp"), order)
        assertFalse(result.captureAttached)
    }
}
