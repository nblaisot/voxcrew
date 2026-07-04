package com.nblaisot.voxcrew.lanlink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays back inbound PCM frames in the order they are received. Frames only ever
 * arrive in order and deduplicated (guaranteed by [LanAudioLink]/TCP), so playback is
 * a straight streaming write — no jitter buffer needed, backlog after a resume just
 * plays back a little behind live, which is the intended trade-off.
 */
class AudioPlayback(private val scope: CoroutineScope) {
    private var track: AudioTrack? = null
    private var idleJob: Job? = null

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    fun play(pcm: ByteArray) {
        val activeTrack = ensureTrack() ?: return
        activeTrack.write(pcm, 0, pcm.size)
        _isReceiving.value = true
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            _isReceiving.value = false
        }
    }

    fun stop() {
        idleJob?.cancel()
        _isReceiving.value = false
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed ($minBuf)")
            return null
        }
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack init failed: ${e.message}")
            return null
        }
        newTrack.play()
        track = newTrack
        return newTrack
    }

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val FRAME_BYTES = SAMPLE_RATE / 1000 * AudioCapture.FRAME_MS * AudioCapture.BYTES_PER_SAMPLE
        private const val IDLE_TIMEOUT_MS = 700L
    }
}
