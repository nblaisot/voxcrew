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
    private var decodedFrames = 0L
    private var writtenFrames = 0L
    private var submittedPcmFrames = 0L
    private var requestedMaxBufferMs = JitterBufferSettings.DEFAULT_MAX_ADAPTIVE_DELAY_MS
    private val playout = AdaptiveInboundPlayout(
        decoderFactory = {
            val decoder = OpusCodec.Decoder()
            object : InboundFrameDecoder {
                override fun decode(payload: ByteArray): ByteArray? =
                    runCatching { decoder.decode(payload) }
                        .onFailure { Log.w(TAG, "Opus decode failed: ${it.message}") }
                        .getOrNull()

                override fun decodeLost(): ByteArray? =
                    runCatching { decoder.decodeLost() }
                        .onFailure { Log.w(TAG, "Opus PLC failed: ${it.message}") }
                        .getOrNull()
            }
        },
        writeDecodedPcm = { pcm -> writePcmToTrack(pcm) },
        bufferedPcmMs = ::bufferedPcmMs,
        audioTrackUnderruns = ::audioTrackUnderruns,
        actualTrackBufferMs = ::actualTrackBufferMs,
        tag = TAG,
    )

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
            resizeTrackBuffer(newTrack, requestedMaxBufferMs)
            try {
                newTrack.play()
            } catch (error: Exception) {
                newTrack.release()
                return PlaybackStartResult.Failure("AudioTrack start failed: ${error.message}")
            }
            track = newTrack
            trackGeneration++
            submittedPcmFrames = 0L
            playout.reset()
            playout.start()
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

    fun play(peerUid: String, event: IncomingMediaEvent.Audio): Boolean {
        if (synchronized(lock) { track == null }) return false
        playout.enqueue(peerUid, event.sequence, event.payload, event.receivedAtNs)
        _isReceiving.value = true
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            _isReceiving.value = false
        }
        return true
    }

    fun onMediaActivity(peerUid: String, event: IncomingMediaEvent.Activity) {
        playout.onMediaActivity(peerUid, event.sequence, event.active, event.receivedAtNs)
    }

    fun onPermanentLoss(peerUid: String, event: IncomingMediaEvent.Gap) =
        playout.onPermanentLoss(peerUid, event.missingSequenceCount())

    fun setJitterBaseDelayMs(ms: Int) = playout.setBaseDelayMs(ms)

    fun setJitterMaxAdaptiveDelayMs(ms: Int) {
        requestedMaxBufferMs = JitterBufferSettings.coerceMaxAdaptiveDelayMs(ms, 20)
        playout.setMaxAdaptiveDelayMs(ms)
        synchronized(lock) { track?.let { resizeTrackBuffer(it, requestedMaxBufferMs) } }
    }

    fun setJitterAdaptiveEnabled(enabled: Boolean) = playout.setAdaptiveEnabled(enabled)

    private fun writePcmToTrack(pcm: ByteArray): Boolean {
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
        synchronized(lock) {
            if (track === activeTrack && trackGeneration == generation) {
                submittedPcmFrames += pcm.size / BYTES_PER_PCM_FRAME
            }
        }
        writtenFrames++
        if (writtenFrames == 1L || writtenFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
            val stats = playout.stats.value
            Log.i(
                TAG,
                "playback frame decoded=$decodedFrames written=$writtenFrames pcmBytes=${pcm.size} " +
                    "writeResult=${writeResult.getOrThrow()} routedType=${routedDevice(activeTrack)?.type} " +
                    "encodedDepth=${stats.encodedDepth} decodedDepth=${stats.decodedDepth} " +
                    "buffered=${bufferedPcmMs()}ms target=${stats.targetDelayMs}ms " +
                    "trackBuffer=${actualTrackBufferMs()}ms underruns=${audioTrackUnderruns()} " +
                    "expansions=${stats.pcmExpansions}",
            )
        }
        return true
    }

    fun stop() {
        idleJob?.cancel()
        _isReceiving.value = false
        synchronized(lock) { releaseTrackLocked() }
        playout.stop()
    }

    private fun releaseTrackLocked() {
        trackGeneration++
        track?.let { active ->
            runCatching { active.removeOnRoutingChangedListener(routingListener) }
            runCatching { active.stop() }
            active.release()
        }
        track = null
        submittedPcmFrames = 0L
    }

    private fun bufferedPcmMs(): Int = synchronized(lock) {
        val active = track ?: return 0
        val played = Integer.toUnsignedLong(active.playbackHeadPosition)
        val bufferedFrames = (submittedPcmFrames - played).coerceAtLeast(0L)
        (bufferedFrames * 1_000L / SAMPLE_RATE).toInt()
    }

    private fun audioTrackUnderruns(): Int = synchronized(lock) { track?.underrunCount ?: 0 }

    private fun actualTrackBufferMs(): Int = synchronized(lock) {
        track?.let { it.bufferSizeInFrames * 1_000 / SAMPLE_RATE } ?: 0
    }

    private fun resizeTrackBuffer(activeTrack: AudioTrack, maxMs: Int) {
        val requestedFrames = SAMPLE_RATE * maxMs / 1_000
        val actual = runCatching { activeTrack.setBufferSizeInFrames(requestedFrames) }.getOrDefault(0)
        if (actual <= 0) Log.w(TAG, "could not set AudioTrack buffer to ${maxMs}ms result=$actual")
    }

    private fun routedDevice(track: AudioTrack): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.routedDevice else null

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val BYTES_PER_PCM_FRAME = 2
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
