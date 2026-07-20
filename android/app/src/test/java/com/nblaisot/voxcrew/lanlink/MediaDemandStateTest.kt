package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDemandStateTest {
    @Test
    fun `idle launch has no Telecom media demand`() {
        assertFalse(MediaDemandState().isDemanded())
    }

    @Test
    fun `foreground PTT prepares Telecom before any transmission`() {
        val state = readySession()

        assertFalse(state.isDemanded())
        assertTrue(state.setAppForeground(true))
        assertTrue(state.isDemanded())
        assertTrue(state.setOutbound(true))
        assertTrue(state.isDemanded())
        state.setOutbound(false)
        assertTrue(state.isDemanded())
    }

    @Test
    fun `background PTT releases Telecom but VOX keeps it ready`() {
        val state = readySession()
        state.setAppForeground(true)
        state.setAppForeground(false)
        assertFalse(state.isDemanded())

        state.setVoxEnabled(true)
        assertTrue(state.isDemanded())
        state.setVoxEnabled(false)
        assertFalse(state.isDemanded())
    }

    @Test
    fun `remote media does not activate Telecom in background when VOX is off`() {
        val state = readySession()

        assertTrue(state.setRemote("peer-a", true))
        assertFalse(state.isDemanded())
        assertTrue(state.hasRemoteDemand())
        state.setRemote("peer-b", true)
        state.setRemote("peer-a", false)
        assertFalse(state.isDemanded())
        assertTrue(state.hasRemoteDemand())
        state.setRemote("peer-b", false)
        assertFalse(state.isDemanded())
        assertFalse(state.hasRemoteDemand())
    }

    @Test
    fun `remote media while foreground still has Telecom from app visibility`() {
        val state = readySession()
        state.setAppForeground(true)
        assertTrue(state.isDemanded())
        state.setRemote("peer-a", true)
        assertTrue(state.isDemanded())
        state.setRemote("peer-a", false)
        assertTrue(state.isDemanded())
    }

    @Test
    fun `microphone permission blocks every demand source`() {
        val state = readySession()
        state.setAppForeground(true)
        assertTrue(state.isDemanded())

        state.setMicrophonePermissionGranted(false)
        assertFalse(state.isDemanded())
        state.setMicrophonePermissionGranted(true)
        assertTrue(state.isDemanded())
    }

    @Test
    fun `clearRemoteDemand drops stale remote peers`() {
        val state = readySession()
        state.setRemote("peer-a", true)
        assertTrue(state.hasRemoteDemand())
        assertTrue(state.clearRemoteDemand())
        assertFalse(state.hasRemoteDemand())
        assertFalse(state.clearRemoteDemand())
    }

    @Test
    fun `pipeline failure is not a demand input`() {
        val state = readySession()
        state.setAppForeground(true)
        assertTrue(state.isDemanded())
        // Demand stays while duplex is Failed — that is pipeline observation only.
        assertEquals(
            TelecomDemandAction.NONE,
            telecomDemandAction(demanded = state.isDemanded(), isActive = true, hasCall = true),
        )
        assertEquals(
            TelecomDemandAction.ACTIVATE,
            telecomDemandAction(demanded = state.isDemanded(), isActive = false, hasCall = false),
        )
    }

    @Test
    fun `failed prepare does not disconnect while foreground PTT still demands media`() {
        val state = readySession()
        state.setAppForeground(true)
        assertTrue(state.isDemanded())

        assertEquals(
            TelecomDemandAction.NONE,
            telecomDemandAction(
                demanded = state.isDemanded(),
                isActive = true,
                hasCall = true,
            ),
        )
        assertEquals(
            TelecomDemandAction.ACTIVATE,
            telecomDemandAction(
                demanded = state.isDemanded(),
                isActive = false,
                hasCall = true,
            ),
        )
    }

    @Test
    fun `duplicate events do not create transitions`() {
        val state = MediaDemandState()
        assertTrue(state.setAppForeground(true))
        assertFalse(state.setAppForeground(true))
        assertTrue(state.setRemote("peer-a", true))
        assertFalse(state.setRemote("peer-a", true))
        assertTrue(state.setOutbound(true))
        assertFalse(state.setOutbound(true))
    }

    @Test
    fun `ending session releases media without forgetting app visibility`() {
        val state = readySession()
        state.setAppForeground(true)
        state.setRemote("peer-a", true)
        state.setOutbound(true)
        state.endSession()
        assertFalse(state.isDemanded())
        assertFalse(state.isOutbound())

        state.setSessionActive(true)
        assertTrue(state.isDemanded())
    }

    @Test
    fun `idle media demand fully disconnects instead of holding a call`() {
        assertEquals(
            TelecomDemandAction.DISCONNECT,
            telecomDemandAction(demanded = false, isActive = true, hasCall = true),
        )
        assertEquals(
            TelecomDemandAction.DISCONNECT,
            telecomDemandAction(demanded = false, isActive = false, hasCall = true),
        )
    }

    @Test
    fun `new demand activates once and an active call is retained`() {
        assertEquals(
            TelecomDemandAction.NONE,
            telecomDemandAction(demanded = false, isActive = false, hasCall = false),
        )
        assertEquals(
            TelecomDemandAction.ACTIVATE,
            telecomDemandAction(demanded = true, isActive = false, hasCall = false),
        )
        assertEquals(
            TelecomDemandAction.NONE,
            telecomDemandAction(demanded = true, isActive = true, hasCall = true),
        )
    }

    private fun readySession() = MediaDemandState().apply {
        setSessionActive(true)
        setMicrophonePermissionGranted(true)
    }
}
