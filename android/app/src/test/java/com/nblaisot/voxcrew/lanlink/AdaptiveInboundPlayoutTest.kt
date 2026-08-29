package com.nblaisot.voxcrew.lanlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferSettingsTest {
    @Test
    fun baseDelayIsAlignedToFrameMs() {
        assertEquals(40, JitterBufferSettings.coerceBaseDelayMs(45))
        assertEquals(20, JitterBufferSettings.coerceBaseDelayMs(10))
        assertEquals(80, JitterBufferSettings.coerceBaseDelayMs(99))
    }

    @Test
    fun adaptiveCeilingUsesTheNewBoundedSemantics() {
        assertEquals(40, JitterBufferSettings.coerceMaxAdaptiveDelayMs(20, 40))
        assertEquals(80, JitterBufferSettings.coerceMaxAdaptiveDelayMs(80, 40))
        assertEquals(160, JitterBufferSettings.coerceMaxAdaptiveDelayMs(400, 40))
    }
}

class AdaptiveInboundPlayoutTest {
    @Test
    fun prebufferWaitsForFortyMillisecondsThenProducesTenMillisecondQuanta() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.setBaseDelayMs(40)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)

        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        assertFalse(playout.processOneQuantumForTest())

        playout.enqueue(PEER_A, 2, pcmFrame(2_000), 20_000_001)
        assertTrue(playout.processOneQuantumForTest())
        assertEquals(AdaptiveInboundPlayout.QUANTUM_BYTES, output.single().size)
        assertEquals(1_000, firstSample(output.single()).toInt())
    }

    @Test
    fun everyRealFrameIsDecodedOnceAndKeptInOrder() {
        val output = mutableListOf<ByteArray>()
        var decodeCount = 0
        val playout = createPlayout(output) { payload ->
            decodeCount++
            payload
        }
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        repeat(3) { index ->
            playout.enqueue(PEER_A, index + 1L, pcmFrame((index + 1) * 1_000), index * 20_000_000L + 1)
        }

        repeat(6) { assertTrue(playout.processOneQuantumForTest()) }

        assertEquals(3, decodeCount)
        assertEquals(listOf(1_000, 1_000, 2_000, 2_000, 3_000, 3_000), output.map { firstSample(it).toInt() })
    }

    @Test
    fun rfcStyleArrivalJitterRaisesTargetButKeepsItBounded() {
        val playout = createPlayout(mutableListOf())
        playout.setBaseDelayMs(40)
        playout.setMaxAdaptiveDelayMs(80)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(1_000), 20_000_001)
        playout.enqueue(PEER_A, 3, pcmFrame(1_000), 80_000_001)

        assertTrue(playout.stats.value.targetDelayMs in 50..80)
    }

    @Test
    fun adaptiveDelayDoesNotShrinkDuringSpeechAndReturnsToBaseAfterStableIdle() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.setBaseDelayMs(40)
        playout.setMaxAdaptiveDelayMs(80)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(1_000), 80_000_001)
        val raised = playout.stats.value.targetDelayMs
        playout.enqueue(PEER_A, 3, pcmFrame(1_000), 100_000_001)
        assertEquals(raised, playout.stats.value.targetDelayMs)

        playout.onMediaActivity(PEER_A, 4, false, 120_000_001)
        while (playout.processOneQuantumForTest()) Unit
        playout.onMediaActivity(PEER_A, 5, true, 11_000_000_001)

        assertEquals(40, playout.stats.value.targetDelayMs)
    }

    @Test
    fun temporaryGapExpandsPcmWithoutConsumingTheDelayedPacket() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(2_000), 20_000_001)
        repeat(4) { assertTrue(playout.processOneQuantumForTest()) }

        assertTrue(playout.processOneQuantumForTest())
        assertEquals(1, playout.stats.value.pcmExpansions)

        playout.enqueue(PEER_A, 3, pcmFrame(3_000), 70_000_001)
        assertTrue(playout.processOneQuantumForTest())
        assertTrue(playout.processOneQuantumForTest())
        assertEquals(7, output.size)
        assertTrue(output.takeLast(2).all { rms(it) > 500 })
    }

    @Test
    fun repeatedExpansionFadesToSilenceBySixtyMilliseconds() {
        val smoother = PcmTailSmoother()
        smoother.acceptActual(pcmQuantum(5_000))

        val expansions = List(6) { smoother.expand() }

        assertTrue(rms(expansions.first()) > 1_000)
        assertEquals(0, rms(expansions.last()))
    }

    @Test
    fun endedShortTalkspurtDrainsWithoutWaitingForBaseDelay() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.onMediaActivity(PEER_A, 2, false, 20_000_001)

        assertTrue(playout.processOneQuantumForTest())
        assertTrue(playout.processOneQuantumForTest())
        assertFalse(playout.processOneQuantumForTest())
    }

    @Test
    fun simultaneousPeersAreMixedAndClippedWithoutSharingDecoderState() {
        val output = mutableListOf<ByteArray>()
        var decodersCreated = 0
        val playout = AdaptiveInboundPlayout(
            decoderFactory = {
                decodersCreated++
                fakeDecoder { payload -> payload }
            },
            writeDecodedPcm = { pcm -> output += pcm.copyOf(); true },
            startWorker = false,
        )
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.onMediaActivity(PEER_B, 0, true, 1)
        repeat(2) { index ->
            playout.enqueue(PEER_A, index + 1L, pcmFrame(25_000), index * 20_000_000L + 1)
            playout.enqueue(PEER_B, index + 1L, pcmFrame(25_000), index * 20_000_000L + 1)
        }

        assertTrue(playout.processOneQuantumForTest())

        assertEquals(2, decodersCreated)
        assertEquals(Short.MAX_VALUE, firstSample(output.single()))
    }

    @Test
    fun declaredPermanentLossUsesDecoderPlcInSequence() {
        val decodedOrder = mutableListOf<Int>()
        val output = mutableListOf<ByteArray>()
        val playout = AdaptiveInboundPlayout(
            decoderFactory = {
                object : InboundFrameDecoder {
                    override fun decode(payload: ByteArray): ByteArray {
                        decodedOrder += firstSample(payload).toInt()
                        return payload
                    }

                    override fun decodeLost(): ByteArray {
                        decodedOrder += -1
                        return pcmFrame(0)
                    }
                }
            },
            writeDecodedPcm = { pcm -> output += pcm.copyOf(); true },
            startWorker = false,
        )
        playout.setBaseDelayMs(20)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.onPermanentLoss(PEER_A, 1)
        playout.enqueue(PEER_A, 3, pcmFrame(3_000), 40_000_001)

        repeat(6) { assertTrue(playout.processOneQuantumForTest()) }

        assertEquals(listOf(1_000, -1, 3_000), decodedOrder)
        assertEquals(1, playout.stats.value.permanentLossConcealments)
    }

    @Test
    fun resetClearsAllPeerQueues() {
        val playout = createPlayout(mutableListOf())
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(1_000), 20_000_001)

        playout.reset()

        assertFalse(playout.processOneQuantumForTest())
        assertEquals(0, playout.stats.value.totalBufferedMs)
    }

    private fun createPlayout(
        output: MutableList<ByteArray>,
        decode: (ByteArray) -> ByteArray? = { it },
    ): AdaptiveInboundPlayout = AdaptiveInboundPlayout(
        decoderFactory = { fakeDecoder(decode) },
        writeDecodedPcm = { pcm -> output += pcm.copyOf(); true },
        startWorker = false,
    )

    private fun pcmFrame(value: Int): ByteArray {
        val output = ByteArray(AudioCapture.FRAME_BYTES)
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .put(ShortArray(OpusCodec.FRAME_SAMPLES) { value.toShort() })
        return output
    }

    private fun pcmQuantum(value: Int): ByteArray {
        val output = ByteArray(AdaptiveInboundPlayout.QUANTUM_BYTES)
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .put(ShortArray(AdaptiveInboundPlayout.QUANTUM_SAMPLES) { value.toShort() })
        return output
    }

    private fun firstSample(pcm: ByteArray): Short =
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).short

    private fun rms(pcm: ByteArray): Int {
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toInt()
    }

    private fun fakeDecoder(decode: (ByteArray) -> ByteArray?): InboundFrameDecoder =
        object : InboundFrameDecoder {
            override fun decode(payload: ByteArray): ByteArray? = decode.invoke(payload)
            override fun decodeLost(): ByteArray = pcmFrame(0)
        }

    companion object {
        private const val PEER_A = "peer-a"
        private const val PEER_B = "peer-b"
    }
}
