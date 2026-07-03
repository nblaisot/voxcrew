package com.nblaisot.voxcrew.audio

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToTalkTransmissionPolicyTest {
    @Test
    fun pressEnablesTransmitReleaseDisables() = runTest {
        val policy = PushToTalkTransmissionPolicy(hangoverMs = 0, scope = this)
        assertFalse(policy.shouldTransmit.value)
        policy.onPress()
        assertTrue(policy.shouldTransmit.value)
        policy.onRelease()
        advanceUntilIdle()
        assertFalse(policy.shouldTransmit.value)
    }
}
