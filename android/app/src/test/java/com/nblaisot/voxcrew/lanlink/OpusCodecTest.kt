package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class OpusCodecTest {

    private fun sineWavePcm(frequencyHz: Double, amplitude: Int = 12_000): ByteArray {
        val bytes = ByteArray(OpusCodec.FRAME_SAMPLES * 2)
        for (i in 0 until OpusCodec.FRAME_SAMPLES) {
            val t = i / OpusCodec.SAMPLE_RATE.toDouble()
            val sample = (amplitude * sin(2 * PI * frequencyHz * t)).toInt().toShort().toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun rms(pcm: ByteArray): Double {
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            count++
            i += 2
        }
        return sqrt(sumSquares / count)
    }

    @Test
    fun `encoding then decoding a frame compresses and preserves size and energy`() {
        val encoder = OpusCodec.Encoder()
        val decoder = OpusCodec.Decoder()
        val original = sineWavePcm(440.0)

        val encoded = encoder.encode(original)
        assertTrue("Opus should compress a 640-byte PCM frame", encoded.size < original.size)

        val decoded = decoder.decode(encoded)
        assertEquals(original.size, decoded.size)
        assertTrue("decoded audio should not be silence", rms(decoded) > 1_000)
    }

    @Test
    fun `multiple consecutive frames round trip without throwing`() {
        val encoder = OpusCodec.Encoder()
        val decoder = OpusCodec.Decoder()
        repeat(10) { frameIndex ->
            val pcm = sineWavePcm(220.0 + frameIndex * 10)
            val decoded = decoder.decode(encoder.encode(pcm))
            assertEquals(pcm.size, decoded.size)
        }
    }

    @Test
    fun `declared loss produces one frame of plc and preserves decoder continuity`() {
        val encoder = OpusCodec.Encoder()
        val decoder = OpusCodec.Decoder()
        val first = encoder.encode(sineWavePcm(300.0))
        val afterLoss = encoder.encode(sineWavePcm(320.0))

        assertEquals(AudioCapture.FRAME_BYTES, decoder.decode(first).size)
        assertEquals(AudioCapture.FRAME_BYTES, decoder.decodeLost().size)
        assertEquals(AudioCapture.FRAME_BYTES, decoder.decode(afterLoss).size)
    }

    @Test
    fun `silence encodes to a very small packet`() {
        val encoder = OpusCodec.Encoder()
        val silence = ByteArray(OpusCodec.FRAME_SAMPLES * 2)
        val encoded = encoder.encode(silence)
        assertTrue("silence should compress to well under the raw frame size", encoded.size < 50)
    }
}
