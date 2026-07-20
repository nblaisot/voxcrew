package com.nblaisot.voxcrew.lanlink

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nblaisot.voxcrew.audio.ObservedAudioDeviceKind
import com.nblaisot.voxcrew.audio.TelecomCallState
import com.nblaisot.voxcrew.audio.observedDeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Streaming voice-call playback. Routing remains exclusively owned by Telecom. */
class AudioPlayback(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackGeneration = 0L
    private var idleJob: Job? = null
    private val decoder = OpusCodec.Decoder()
    private var decodedFrames = 0L
    private var writtenFrames = 0L

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    /** Fired when the platform re-routes the live track (e.g. SCO drops to speaker). */
    @Volatile var onRoutedDeviceChanged: ((ObservedAudioDeviceKind) -> Unit)? = null
    private val routingHandler = Handler(Looper.getMainLooper())
    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        onRoutedDeviceChanged?.invoke(observedDeviceKind(router.routedDevice?.type))
    }

    /** Current observed output kind of the live track, null when none is active. */
    fun observedRoutedKind(): ObservedAudioDeviceKind? = synchronized(lock) {
        track?.let { observedDeviceKind(routedDevice(it)?.type) }
    }

    fun open(callState: TelecomCallState): PlaybackStartResult {
        if (!callState.mediaActive) return PlaybackStartResult.Failure("Telecom call is not media-active")
        synchronized(lock) {
            releaseTrackLocked()
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return PlaybackStartResult.Failure("invalid AudioTrack buffer size=$minBuffer")
            val newTrack = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
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
                return PlaybackStartResult.Failure("AudioTrack construction failed: ${error.message}")
            }
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                return PlaybackStartResult.Failure("AudioTrack was not initialized")
            }
            try {
                newTrack.play()
            } catch (error: Exception) {
                newTrack.release()
                return PlaybackStartResult.Failure("AudioTrack start failed: ${error.message}")
            }
            track = newTrack
            trackGeneration++
            runCatching { newTrack.addOnRoutingChangedListener(routingListener, routingHandler) }
            val routedType = routedDevice(newTrack)?.type
            Log.i(
                TAG,
                "AudioTrack started stream=${AudioManager.STREAM_VOICE_CALL} " +
                    "endpoint=${callState.currentEndpoint?.name} endpointType=${callState.currentEndpoint?.type} " +
                    "routedType=$routedType",
            )
            return PlaybackStartResult.Success(observedDeviceKind(routedType))
        }
    }

    fun play(payload: ByteArray): Boolean {
        val pcm = runCatching { decoder.decode(payload) }
            .onFailure { Log.w(TAG, "Opus decode failed: ${it.message}") }
            .getOrNull() ?: return false
        decodedFrames++
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
            val error = writeResult.exceptionOrNull()
            val stillCurrent = synchronized(lock) {
                track === activeTrack && trackGeneration == generation
            }
            if (!stillCurrent) {
                Log.i(TAG, "discarding write result from replaced AudioTrack")
                return true
            }
            Log.e(TAG, "AudioTrack.write failed: ${error?.message}", error)
            return false
        }
        writtenFrames++
        if (writtenFrames == 1L || writtenFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
            Log.i(
                TAG,
                "playback frame decoded=$decodedFrames written=$writtenFrames pcmBytes=${pcm.size} " +
                    "writeResult=${writeResult.getOrThrow()} routedType=${routedDevice(activeTrack)?.type}",
            )
        }
        _isReceiving.value = true
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            _isReceiving.value = false
        }
        return true
    }

    fun stop() {
        idleJob?.cancel()
        _isReceiving.value = false
        synchronized(lock) { releaseTrackLocked() }
    }

    private fun releaseTrackLocked() {
        trackGeneration++
        track?.let { active ->
            runCatching { active.removeOnRoutingChangedListener(routingListener) }
            runCatching { active.stop() }
            active.release()
        }
        track = null
    }

    private fun routedDevice(track: AudioTrack): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.routedDevice else null

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val IDLE_TIMEOUT_MS = 700L
        private const val DIAGNOSTIC_FRAME_INTERVAL = 100L
    }
}

sealed interface PlaybackStartResult {
    data class Success(val observedOutput: ObservedAudioDeviceKind) : PlaybackStartResult
    data class Failure(val reason: String) : PlaybackStartResult
}

/** Drains a complete decoded frame, preserving progress across short writes. */
internal fun drainPcm(
    byteCount: Int,
    write: (offset: Int, byteCount: Int) -> Int,
): Int {
    var offset = 0
    while (offset < byteCount) {
        val count = write(offset, byteCount - offset)
        check(count in 1..(byteCount - offset)) {
            "AudioTrack.write failed code=$count offset=$offset size=$byteCount"
        }
        offset += count
    }
    return offset
}
