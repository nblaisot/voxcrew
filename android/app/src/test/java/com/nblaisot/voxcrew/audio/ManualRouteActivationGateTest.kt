package com.nblaisot.voxcrew.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRouteActivationGateTest {
    @Test
    fun routeFailureBlocksAutomaticSessionRecreation() {
        val gate = ManualRouteActivationGate()

        gate.onRouteFailure()

        assertTrue(gate.isBlocked)
    }

    @Test
    fun nextExplicitSelectionAllowsOneCleanSessionAgain() {
        val gate = ManualRouteActivationGate()
        gate.onRouteFailure()

        gate.onUserSelection()

        assertFalse(gate.isBlocked)
    }
}
