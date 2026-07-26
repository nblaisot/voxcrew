package com.nblaisot.voxcrew.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayEnvelopeTest {

    @Test
    fun `envelope round-trips peer uid and frame`() {
        val frame = byteArrayOf(1, 2, 3, 9)
        val packed = RelayClient.encodeEnvelope("peer-uid", frame)
        val decoded = RelayClient.decodeEnvelope(packed)!!
        assertEquals("peer-uid", decoded.peerUid)
        assertArrayEquals(frame, decoded.frame)
    }

    @Test
    fun `decode rejects truncated envelope`() {
        assertNull(RelayClient.decodeEnvelope(byteArrayOf(0, 5, 1, 2)))
    }
}
