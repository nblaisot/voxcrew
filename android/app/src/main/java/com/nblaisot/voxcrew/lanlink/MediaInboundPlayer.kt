package com.nblaisot.voxcrew.lanlink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background inbound playback on the multimedia path (not Telecom / voice-call).
 * Requests [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT] so other media pauses for the
 * duration of speech, then abandons focus after an idle hangover so it can resume.
 */
class MediaInboundPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackGeneration = 0L
    private var idleJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private val decoder = OpusCodec.Decoder()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    /** Decode and play one Opus frame. Lazily opens the track and takes audio focus. */
    fun play(payload: ByteArray): Boolean {
        val pcm = runCatching { decoder.decode(payload) }
            .onFailure { Log.w(TAG, "Opus decode failed: ${it.message}") }
            .getOrNull() ?: return false
        if (!ensureReady()) return false
        val (activeTrack, generation) = synchronized(lock) {
            val current = track ?: return false
            current to trackGeneration
        }
        val writeResult = runCatching {
            drainPcm(pcm.size) { offset, byteCount ->
                activeTrack.write(pcm, offset, byteCount, AudioTrack.WRITE_BLOCKING)
            }
        }
        if (writeResult.isFailure) {
            val stillCurrent = synchronized(lock) {
                track === activeTrack && trackGeneration == generation
            }
            if (!stillCurrent) return true
            Log.e(TAG, "AudioTrack.write failed: ${writeResult.exceptionOrNull()?.message}")
            return false
        }
        _isReceiving.value = true
        scheduleIdleRelease()
        return true
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        _isReceiving.value = false
        synchronized(lock) { releaseTrackLocked() }
        abandonFocus()
    }

    private fun ensureReady(): Boolean {
        if (!requestFocus()) return false
        synchronized(lock) {
            if (track != null) return true
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false
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
                    .setBufferSizeInBytes(maxOf(minBuffer, AudioCapture.FRAME_BYTES * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (error: Exception) {
                Log.e(TAG, "AudioTrack construction failed: ${error.message}")
                return false
            }
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                return false
            }
            try {
                newTrack.play()
            } catch (error: Exception) {
                newTrack.release()
                Log.e(TAG, "AudioTrack start failed: ${error.message}")
                return false
            }
            track = newTrack
            trackGeneration++
            Log.i(TAG, "media inbound AudioTrack started usage=MEDIA")
            return true
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            _isReceiving.value = false
            synchronized(lock) { releaseTrackLocked() }
            abandonFocus()
        }
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* transient; we release on idle */ }
                .build()
            val result = audioManager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                hasFocus = true
                true
            } else {
                Log.w(TAG, "audio focus denied result=$result")
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasFocus
        }
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasFocus = false
    }

    private fun releaseTrackLocked() {
        trackGeneration++
        track?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "MediaInboundPlayer"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        const val IDLE_TIMEOUT_MS = 700L
    }
}
