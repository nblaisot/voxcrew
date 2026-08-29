package com.nblaisot.voxcrew.lanlink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
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
    @Volatile private var idleJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    @Volatile private var idleDeadlineMs = 0L
    private var submittedPcmFrames = 0L
    private var writtenQuanta = 0L
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
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                Log.i(TAG, "media inbound lost audio focus change=$change")
                stop()
            }
        }
    }

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    /** Queue one Opus frame for paced playout. Lazily opens the track and takes audio focus. */
    fun play(peerUid: String, event: IncomingMediaEvent.Audio): Boolean {
        playout.start()
        playout.enqueue(peerUid, event.sequence, event.payload, event.receivedAtNs)
        _isReceiving.value = true
        scheduleIdleRelease()
        return true
    }

    fun onMediaActivity(peerUid: String, event: IncomingMediaEvent.Activity) {
        playout.start()
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
        synchronized(lock) {
            if (track === activeTrack && trackGeneration == generation) {
                submittedPcmFrames += pcm.size / BYTES_PER_PCM_FRAME
            }
        }
        writtenQuanta++
        if (writtenQuanta == 1L || writtenQuanta % DIAGNOSTIC_QUANTUM_INTERVAL == 0L) {
            val stats = playout.stats.value
            Log.i(
                TAG,
                "media playout quanta=$writtenQuanta encodedDepth=${stats.encodedDepth} " +
                    "decodedDepth=${stats.decodedDepth} buffered=${bufferedPcmMs()}ms " +
                    "target=${stats.targetDelayMs}ms trackBuffer=${actualTrackBufferMs()}ms " +
                    "underruns=${audioTrackUnderruns()} expansions=${stats.pcmExpansions}",
            )
        }
        scheduleIdleRelease()
        return true
    }

    fun stop() {
        idleJob?.cancel()
        idleJob = null
        _isReceiving.value = false
        synchronized(lock) { releaseTrackLocked() }
        playout.stop()
        abandonFocus()
    }

    private fun ensureReady(): Boolean {
        synchronized(lock) {
            if (track != null) {
                // Track already open — still need focus for this burst.
            } else {
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
                resizeTrackBuffer(newTrack, requestedMaxBufferMs)
                try {
                    newTrack.play()
                } catch (error: Exception) {
                    newTrack.release()
                    Log.e(TAG, "AudioTrack start failed: ${error.message}")
                    return false
                }
                track = newTrack
                trackGeneration++
                submittedPcmFrames = 0L
                Log.i(TAG, "media inbound AudioTrack started usage=MEDIA")
            }
        }
        if (!requestFocus()) {
            synchronized(lock) { releaseTrackLocked() }
            return false
        }
        return true
    }

    @Synchronized
    private fun scheduleIdleRelease() {
        idleDeadlineMs = SystemClock.elapsedRealtime() + idleTimeoutMs
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (true) {
                val remainingMs = idleDeadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                delay(remainingMs)
            }
            _isReceiving.value = false
            playout.reset()
            synchronized(lock) { releaseTrackLocked() }
            abandonFocus()
            idleJob = null
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
                .setOnAudioFocusChangeListener(focusChangeListener)
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
                focusChangeListener,
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
            audioManager.abandonAudioFocus(focusChangeListener)
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

    companion object {
        private const val TAG = "MediaInboundPlayer"
        private const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val BYTES_PER_PCM_FRAME = 2
        private const val DIAGNOSTIC_QUANTUM_INTERVAL = 200L
        const val IDLE_TIMEOUT_MS = 700L
    }
}
