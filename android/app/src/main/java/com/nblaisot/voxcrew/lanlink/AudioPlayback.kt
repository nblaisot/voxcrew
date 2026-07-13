package com.nblaisot.voxcrew.lanlink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import android.media.AudioManager
import com.nblaisot.voxcrew.audio.AudioRouteSelector
import com.nblaisot.voxcrew.audio.AudioRouteState
import com.nblaisot.voxcrew.audio.CaptureInputKind
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
 * [OpusCodec] before writing PCM to the track.
 */
class AudioPlayback(
    private val scope: CoroutineScope,
    private val intercomAudioSession: IntercomAudioSession? = null,
) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var idleJob: Job? = null
    private val decoder = OpusCodec.Decoder()
    private var loggedRoutedDevice = false
    private var loggedIncomingAudio = false
    private var currentRoute: AudioRouteState = AudioRouteState.builtIn()
    private var activePlaybackKey: String? = null

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    /** Creates and starts the playback track so platform AEC has a far-end reference before capture. */
    fun warmUp() {
        val route = intercomAudioSession?.currentRoute() ?: return
        if (!canUseRoute(route)) return
        synchronized(lock) {
            ensureTrackForRouteLocked(route)
        }
    }

    fun refreshRoute(route: AudioRouteState) {
        if (!canUseRoute(route)) return
        synchronized(lock) {
            ensureTrackForRouteLocked(route, forceRecreate = true)
        }
    }

    val audioSessionId: Int?
        get() = synchronized(lock) { track?.audioSessionId }

    fun play(payload: ByteArray) {
        val pcm = runCatching { decoder.decode(payload) }
            .onFailure { Log.w(TAG, "Opus decode failed: ${it.message}") }
            .getOrNull() ?: return
        if (!loggedIncomingAudio) {
            loggedIncomingAudio = true
            Log.i(TAG, "incoming audio frame decoded pcmBytes=${pcm.size}")
        }
        val activeTrack = synchronized(lock) {
            val route = intercomAudioSession?.currentRoute()
            if (route != null && canUseRoute(route)) {
                ensureTrackForRouteLocked(route)
            }
            track
        } ?: return
        logRoutedDeviceOnce(activeTrack)
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
        synchronized(lock) {
            releaseTrackLocked()
            loggedRoutedDevice = false
            loggedIncomingAudio = false
            activePlaybackKey = null
        }
    }

    private fun canUseRoute(route: AudioRouteState): Boolean {
        if (route.permissionIssue != null) return false
        return route.routeReady ||
            (route.audioMode == AudioManager.MODE_IN_COMMUNICATION &&
                route.micKind == CaptureInputKind.BLUETOOTH)
    }

    private fun releaseTrackLocked() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    private fun ensureTrackForRouteLocked(
        route: AudioRouteState,
        forceRecreate: Boolean = false,
    ): AudioTrack? {
        val key = playbackKey(route)
        if (!forceRecreate && track != null && key == activePlaybackKey) {
            return track
        }
        if (track != null) {
            Log.i(TAG, "recreating AudioTrack key=$key previous=$activePlaybackKey")
            releaseTrackLocked()
            loggedRoutedDevice = false
        }
        currentRoute = route
        activePlaybackKey = key
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed ($minBuf)")
            return null
        }
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(route.playbackUsage)
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
        route.outputDevice?.let { output ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                route.audioMode != android.media.AudioManager.MODE_IN_COMMUNICATION
            ) {
                val preferred = newTrack.setPreferredDevice(output)
                Log.i(
                    TAG,
                    "setPreferredDevice outputType=${output.type} mode=${route.audioMode} success=$preferred",
                )
            }
        }
        newTrack.play()
        track = newTrack
        Log.i(
            TAG,
            "AudioTrack opened usage=${route.playbackUsage} outputKind=${route.outputKind} " +
                "outputType=${route.outputDevice?.type}",
        )
        return newTrack
    }

    private fun logRoutedDeviceOnce(activeTrack: AudioTrack) {
        if (loggedRoutedDevice || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        loggedRoutedDevice = true
        val routedDevice = activeTrack.routedDevice
        Log.i(
            TAG,
            "playback routedType=${routedDevice?.type} expectedType=${currentRoute.outputDevice?.type} " +
                "outputKind=${currentRoute.outputKind} mode=${currentRoute.audioMode}",
        )
        if (currentRoute.outputDevice != null &&
            !AudioRouteSelector.sameDevice(routedDevice, currentRoute.outputDevice)
        ) {
            Log.w(
                TAG,
                "playback route mismatch — expected ${currentRoute.outputDevice?.type} " +
                    "got ${routedDevice?.type}",
            )
        }
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
