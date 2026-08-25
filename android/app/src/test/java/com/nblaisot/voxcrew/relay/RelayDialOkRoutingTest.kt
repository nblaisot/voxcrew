package com.nblaisot.voxcrew.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDialOkRoutingTest {
    @Test
    fun `outbound dialer with waiter is not treated as inbound`() {
        assertFalse(RelayClient.isInboundDialOk(hasLocalWaiter = true))
    }

    @Test
    fun `dial_ok without waiter is inbound`() {
        assertTrue(RelayClient.isInboundDialOk(hasLocalWaiter = false))
    }
}
