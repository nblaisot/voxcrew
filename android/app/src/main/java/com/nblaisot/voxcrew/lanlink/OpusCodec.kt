package com.nblaisot.voxcrew.lanlink

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus codec via Concentus, a pure-Java/Kotlin-compatible port with no native
 * library dependency — necessary at minSdk 26, where a Opus *encoder* is not
 * guaranteed to be present through [android.media.MediaCodec]. Used on every
 * transport (LAN, direct internet, relay) so audio only needs to be encoded and
 * decoded once regardless of how many path switches happen mid-conversation.
 *
 * One instance should live for the duration of a capture/playback session:
 * Opus keeps a small amount of internal state between frames (lookahead,
 * perceptual model), so reusing it across a talkspurt gives better quality
 * than recreating it per frame.
 */
object OpusCodec {
    const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
    const val CHANNELS = 1
    const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * AudioCapture.FRAME_MS
    private const val BITRATE_BPS = 24_000
    private const val MAX_ENCODED_BYTES = 1_024

    class Encoder {
        private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(BITRATE_BPS)
        }

        /** [pcm] must be exactly [FRAME_SAMPLES] 16-bit LE mono samples (see [AudioCapture]). */
        fun encode(pcm: ByteArray): ByteArray {
            val samples = bytesToShorts(pcm)
            val output = ByteArray(MAX_ENCODED_BYTES)
            val length = encoder.encode(samples, 0, FRAME_SAMPLES, output, 0, output.size)
            return output.copyOf(length)
        }
    }

    class Decoder {
        private val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

        /** Returns 16-bit LE mono PCM decoded from [opus] (see [AudioPlayback]). */
        fun decode(opus: ByteArray): ByteArray {
            val pcmOut = ShortArray(FRAME_SAMPLES)
            val decodedSamples = decoder.decode(opus, 0, opus.size, pcmOut, 0, FRAME_SAMPLES, false)
            return shortsToBytes(pcmOut, decodedSamples)
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, count)
        return bytes
    }
}
