package com.nblaisot.voxcrew.lanlink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.nblaisot.voxcrew.audio.AudioRouteSelector
import com.nblaisot.voxcrew.audio.AudioRouteState
import com.nblaisot.voxcrew.audio.IntercomAudioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays back inbound Opus frames in the order they are received, decoding each with
 * [OpusCodec] before writing PCM to the track. Frames only ever arrive in order and
 * deduplicated (guaranteed by [PeerLink] regardless of transport), so playback is a
 * straight streaming write — no jitter buffer needed, backlog after a resume just
 * plays back a little behind live, which is the intended trade-off.
 */
class AudioPlayback(
    private val scope: CoroutineScope,
    private val intercomAudioSession: IntercomAudioSession? = null,
) {
    private var track: AudioTrack? = null
    private var idleJob: Job? = null
    private val decoder = OpusCodec.Decoder()
    private var loggedRoutedDevice = false
    private var currentRoute: AudioRouteState = AudioRouteState.builtIn()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    /** Creates and starts the playback track so platform AEC has a far-end reference before capture. */
    fun warmUp() {
        if (intercomAudioSession?.awaitRouteReady() == false) return
        currentRoute = intercomAudioSession?.currentRoute() ?: AudioRouteState.builtIn()
        ensureTrack()
    }

    fun refreshRoute(route: AudioRouteState) {
        if (playbackKey(route) == playbackKey(currentRoute) && track != null) return
        if (!route.routeReady) return
        currentRoute = route
        releaseTrack()
        loggedRoutedDevice = false
        ensureTrack()
    }

    val audioSessionId: Int?
        get() = track?.audioSessionId

    fun play(payload: ByteArray) {
        val pcm = runCatching { decoder.decode(payload) }
            .onFailure { Log.w(TAG, "Opus decode failed: ${it.message}") }
            .getOrNull() ?: return
        val activeTrack = ensureTrack() ?: return
        if (!loggedRoutedDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            loggedRoutedDevice = true
            Log.i(TAG, "playback routedType=${activeTrack.routedDevice?.type}")
        }
        runCatching { activeTrack.write(pcm, 0, pcm.size) }
            .onFailure { Log.w(TAG, "AudioTrack.write failed: ${it.message}") }
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
        releaseTrack()
        loggedRoutedDevice = false
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        if (intercomAudioSession?.awaitRouteReady() == false) return null
        currentRoute = intercomAudioSession?.currentRoute() ?: currentRoute
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed ($minBuf)")
            return null
        }
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(currentRoute.playbackUsage)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            currentRoute.audioMode == android.media.AudioManager.MODE_NORMAL &&
            currentRoute.outputDevice != null
        ) {
            val preferred = newTrack.setPreferredDevice(currentRoute.outputDevice)
            Log.i(
                TAG,
                "setPreferredDevice outputType=${currentRoute.outputDevice?.type} success=$preferred",
            )
        }
        newTrack.play()
        track = newTrack
        Log.i(
            TAG,
            "AudioTrack opened usage=${currentRoute.playbackUsage} " +
                "outputKind=${currentRoute.outputKind}",
        )
        return newTrack
    }

    private fun playbackKey(route: AudioRouteState): String =
        "${route.playbackUsage}:${route.audioMode}:${AudioRouteSelector.deviceIdentity(route.outputDevice)}"

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val FRAME_BYTES = SAMPLE_RATE / 1000 * AudioCapture.FRAME_MS * AudioCapture.BYTES_PER_SAMPLE
        private const val IDLE_TIMEOUT_MS = 700L
    }
}
