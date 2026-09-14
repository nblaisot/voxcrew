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
        playout.setMaxAdaptiveDelayMs(160)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(1_000), 20_000_001)
        playout.enqueue(PEER_A, 3, pcmFrame(1_000), 80_000_001)

        assertTrue(playout.stats.value.targetDelayMs in 50..160)
    }

    @Test
    fun adaptiveDelayDoesNotShrinkDuringSpeechAndReturnsToBaseAfterStableIdle() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.setBaseDelayMs(40)
        playout.setMaxAdaptiveDelayMs(160)
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
    fun temporaryGapFadesToSilenceWithoutConsumingTheDelayedPacket() {
        val output = mutableListOf<ByteArray>()
        val playout = createPlayout(output)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(2_000), 20_000_001)
        repeat(4) { assertTrue(playout.processOneQuantumForTest()) }

        assertTrue(playout.processOneQuantumForTest())
        assertEquals(0, lastSample(output.last()).toInt())
        assertEquals(0, playout.stats.value.pcmExpansions)

        playout.enqueue(PEER_A, 3, pcmFrame(3_000), 70_000_001)
        assertTrue(playout.processOneQuantumForTest())
        assertTrue(playout.processOneQuantumForTest())
        assertEquals(7, output.size)
        assertTrue(output.takeLast(2).all { rms(it) > 500 })
    }

    @Test
    fun boundedExpansionUtilityFadesToSilenceBySixtyMilliseconds() {
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
        assertTrue(playout.processOneQuantumForTest())
        assertEquals(0, lastSample(output.last()).toInt())
        assertFalse(playout.processOneQuantumForTest())
    }

    @Test
    fun startupPrimingContinuesPastJitterTargetUntilHardwareThreshold() {
        val kinds = mutableListOf<PlayoutQuantumKind>()
        val sink = ThresholdSink(startupThresholdMs = 80)
        val playout = AdaptiveInboundPlayout(
            decoderFactory = { fakeDecoder { it } },
            writeDecodedPcm = { quantum ->
                kinds += quantum.kind
                sink.writeQuantum()
                true
            },
            startWorker = false,
        )
        playout.setBaseDelayMs(40)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(1_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(2_000), 20_000_001)
        playout.onMediaActivity(PEER_A, 3, false, 40_000_001)

        repeat(8) { assertTrue(playout.processOneSinkQuantumForTest(sink.state())) }

        assertTrue(sink.started)
        assertEquals(80, sink.bufferedMs)
        assertEquals(List(4) { PlayoutQuantumKind.AUDIO }, kinds.take(4))
        assertEquals(PlayoutQuantumKind.CONCEALMENT, kinds[4])
        assertTrue(kinds.drop(5).all { it == PlayoutQuantumKind.SILENCE })
        assertFalse(playout.processOneSinkQuantumForTest(sink.state()))
    }

    @Test
    fun hardwareSinkIsNotPrimedWithSilenceBeforeSpeechIsReady() {
        val output = mutableListOf<PlayoutQuantum>()
        val playout = AdaptiveInboundPlayout(
            decoderFactory = { fakeDecoder { it } },
            writeDecodedPcm = { quantum -> output += quantum; true },
            startWorker = false,
        )
        playout.onMediaActivity(PEER_A, 0, true, 1)

        assertFalse(
            playout.processOneSinkQuantumForTest(
                AudioSinkState(requiresPriming = true, startupThresholdMs = 80, actualBufferMs = 80),
            ),
        )
        assertTrue(output.isEmpty())
    }

    @Test
    fun adaptiveReserveDoesNotInflateTheRunningHardwareQueue() {
        val playout = createPlayout(mutableListOf())
        playout.setBaseDelayMs(80)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        repeat(4) { index ->
            playout.enqueue(PEER_A, index + 1L, pcmFrame(1_000), index * 20_000_000L + 1)
        }

        assertFalse(
            playout.processOneSinkQuantumForTest(
                AudioSinkState(
                    bufferedPcmMs = AdaptiveInboundPlayout.HARDWARE_SINK_TARGET_MS,
                    requiresPriming = false,
                    startupThresholdMs = 160,
                    actualBufferMs = 160,
                ),
            ),
        )
    }

    @Test
    fun longGapKeepsBluetoothSinkClockedWithSilentQuanta() {
        val kinds = mutableListOf<PlayoutQuantumKind>()
        val playout = AdaptiveInboundPlayout(
            decoderFactory = { fakeDecoder { it } },
            writeDecodedPcm = { quantum -> kinds += quantum.kind; true },
            startWorker = false,
        )
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(4_000), 1)
        playout.enqueue(PEER_A, 2, pcmFrame(4_000), 20_000_001)
        repeat(4) { assertTrue(playout.processOneQuantumForTest(keepAlive = true)) }

        repeat(200) { assertTrue(playout.processOneQuantumForTest(keepAlive = true)) }

        assertEquals(1, kinds.count { it == PlayoutQuantumKind.CONCEALMENT })
        assertTrue(kinds.count { it == PlayoutQuantumKind.SILENCE } >= 199)
        assertTrue(playout.stats.value.silentKeepaliveQuanta >= 199)
    }

    @Test
    fun delayedPacketAfterSilentKeepaliveIsStillDecodedExactlyOnce() {
        var decoded = 0
        val kinds = mutableListOf<PlayoutQuantumKind>()
        val playout = AdaptiveInboundPlayout(
            decoderFactory = { fakeDecoder { decoded++; it } },
            writeDecodedPcm = { quantum -> kinds += quantum.kind; true },
            startWorker = false,
        )
        playout.setBaseDelayMs(20)
        playout.setAdaptiveEnabled(false)
        playout.onMediaActivity(PEER_A, 0, true, 1)
        playout.enqueue(PEER_A, 1, pcmFrame(2_000), 1)
        repeat(2) { playout.processOneQuantumForTest(keepAlive = true) }
        repeat(100) { playout.processOneQuantumForTest(keepAlive = true) }

        playout.enqueue(PEER_A, 2, pcmFrame(3_000), 1_020_000_001)
        repeat(2) { playout.processOneQuantumForTest(keepAlive = true) }

        assertEquals(2, decoded)
        assertEquals(PlayoutQuantumKind.AUDIO, kinds.last())
    }

    @Test
    fun resumeCrossfadeRunsAcrossTheWholeTenMillisecondQuantum() {
        val smoother = PcmTailSmoother()
        smoother.acceptActual(pcmQuantum(5_000))
        repeat(6) { smoother.expand() }
        smoother.silence()

        val resumed = smoother.acceptActual(pcmQuantum(8_000))

        assertTrue(firstSample(resumed).toInt() < 100)
        assertTrue(lastSample(resumed).toInt() > 7_900)
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
            writeDecodedPcm = { quantum -> output += quantum.pcm.copyOf(); true },
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
            writeDecodedPcm = { quantum -> output += quantum.pcm.copyOf(); true },
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
        writeDecodedPcm = { quantum -> output += quantum.pcm.copyOf(); true },
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

    private fun lastSample(pcm: ByteArray): Short =
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).getShort(pcm.size - 2)

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

    private class ThresholdSink(private val startupThresholdMs: Int) {
        var bufferedMs = 0
            private set
        val started: Boolean get() = bufferedMs >= startupThresholdMs

        fun writeQuantum() {
            bufferedMs += AdaptiveInboundPlayout.QUANTUM_MS
        }

        fun state(): AudioSinkState = AudioSinkState(
            bufferedPcmMs = bufferedMs,
            requiresPriming = !started,
            startupThresholdMs = startupThresholdMs,
            actualBufferMs = startupThresholdMs,
        )
    }

    companion object {
        private const val PEER_A = "peer-a"
        private const val PEER_B = "peer-b"
    }
}
