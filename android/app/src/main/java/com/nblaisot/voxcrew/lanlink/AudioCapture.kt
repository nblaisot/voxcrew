package com.nblaisot.voxcrew.lanlink

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nblaisot.voxcrew.audio.CaptureAudioEffects
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.IntercomAudioSession
import com.nblaisot.voxcrew.audio.VoxEchoGuard
import com.nblaisot.voxcrew.audio.VoxGate
import com.nblaisot.voxcrew.audio.VoiceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures the microphone, encodes each 20 ms frame with Opus (see [OpusCodec]) and
 * hands the encoded payload to a callback. Two capture modes are supported:
 *
 * - [attach]: recording starts/stops with a boolean policy (PTT press/release, or open
 *   mic) — per the "no recording by default" principle, the mic is off between talkspurts.
 * - [attachVox]: the mic runs continuously while VOX is enabled (needed so a
 *   [VoiceDetector] can listen for speech onset), but *transmission* is gated by
 *   [VoxGate] — audio is analyzed in memory and only handed to the caller (i.e. only
 *   ever leaves the device) while a human voice is actually detected.
 */
class AudioCapture(
    private val scope: CoroutineScope,
    private val intercomAudioSession: IntercomAudioSession? = null,
) {
    private var policyJob: Job? = null
    @Volatile
    private var recordJob: Job? = null

    fun attach(shouldTransmit: StateFlow<Boolean>, onFrame: (ByteArray) -> Unit) {
        detach()
        policyJob = scope.launch {
            shouldTransmit.collect { active ->
                if (active) {
                    startPttCapture(onFrame)
                } else {
                    stopPttCapture()
                }
            }
        }
    }

    private fun startPttCapture(onFrame: (ByteArray) -> Unit) {
        if (recordJob?.isActive == true) return
        recordJob = scope.launch(Dispatchers.IO) {
            pttCaptureLoop(onFrame)
        }
    }

    private fun stopPttCapture() {
        recordJob?.cancel()
        recordJob = null
    }

    private suspend fun pttCaptureLoop(onFrame: (ByteArray) -> Unit) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        val opened = openRecorder(frameBytes) ?: return
        val recorder = opened.recorder
        val effects = CaptureAudioEffects.attach(recorder.audioSessionId)
        val encoder = OpusCodec.Encoder()
        var encodedFrames = 0
        var zeroReads = 0
        try {
            recorder.startRecording()
            logRecorderRoute(recorder, opened)
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read == frame.size) {
                    zeroReads = 0
                    val encoded = runCatching { encoder.encode(frame) }
                        .onFailure { Log.w(TAG, "Opus encode failed: ${it.message}") }
                        .getOrNull()
                    if (encoded != null) {
                        if (encodedFrames++ == 0) {
                            Log.i(
                                TAG,
                                "first encoded frame (${encoded.size} bytes) pcmRms=${pcmRms(frame)}",
                            )
                        }
                        onFrame(encoded)
                    }
                } else if (read > 0) {
                    Log.d(TAG, "dropping short read ($read/$frameBytes bytes)")
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error code=$read")
                } else {
                    zeroReads++
                    if (zeroReads == 1 || zeroReads % 50 == 0) {
                        Log.w(TAG, "AudioRecord.read returned 0 ($zeroReads times)")
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            effects.release()
            recorder.release()
        }
    }

    /**
     * Starts continuous VOX capture: the detector built by [voiceDetectorFactory]
     * analyzes every frame, [gate] turns those decisions into a debounced transmit/hold
     * state (reported via [onTransmittingChanged]), and only frames while the gate is
     * open reach [onFrame].
     */
    fun attachVox(
        voiceDetectorFactory: () -> VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
        isReceiving: () -> Boolean = { false },
        echoGuard: VoxEchoGuard = VoxEchoGuard(),
    ): Job {
        detach()
        val job = scope.launch(Dispatchers.IO) {
            voxCaptureLoop(
                voiceDetectorFactory(),
                gate,
                onTransmittingChanged,
                onFrame,
                isReceiving,
                echoGuard,
            )
        }
        recordJob = job
        return job
    }

    fun detach() {
        policyJob?.cancel()
        policyJob = null
        recordJob?.cancel()
        recordJob = null
    }

    private suspend fun voxCaptureLoop(
        voiceDetector: VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
        isReceiving: () -> Boolean,
        echoGuard: VoxEchoGuard,
    ) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        val opened = openRecorder(frameBytes) ?: return
        val recorder = opened.recorder
        val effects = CaptureAudioEffects.attach(recorder.audioSessionId)
        val encoder = OpusCodec.Encoder()
        val preRoll = ArrayDeque<ByteArray>()
        try {
            recorder.startRecording()
            logRecorderRoute(recorder, opened)
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read != frame.size) {
                    if (read > 0) Log.d(TAG, "dropping short read ($read/$frameBytes bytes)")
                    else if (read < 0) Log.w(TAG, "AudioRecord.read error code=$read")
                    continue
                }
                val encoded = runCatching { encoder.encode(frame) }
                    .onFailure { Log.w(TAG, "Opus encode failed: ${it.message}") }
                    .getOrNull() ?: continue

                val nowMs = SystemClock.elapsedRealtime()
                echoGuard.onReceivingChanged(isReceiving(), nowMs)
                val decision = runCatching { voiceDetector.accept(bytesToShorts(frame)) }
                    .onFailure { Log.w(TAG, "VAD inference failed: ${it.message}") }
                    .getOrNull()
                val filteredDecision = echoGuard.filterSpeechDecision(decision, nowMs)
                val result = gate.update(filteredDecision, nowMs)
                onTransmittingChanged(result.transmitting)

                if (result.transmitting) {
                    if (result.onset) {
                        while (preRoll.isNotEmpty()) onFrame(preRoll.removeFirst())
                    }
                    onFrame(encoded)
                } else {
                    preRoll.addLast(encoded)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
            }
        } finally {
            runCatching { recorder.stop() }
            effects.release()
            recorder.release()
            voiceDetector.close()
            echoGuard.reset()
            onTransmittingChanged(false)
        }
    }

    private fun openRecorder(frameBytes: Int): OpenedRecorder? {
        if (intercomAudioSession?.awaitRouteReady() == false) {
            Log.w(TAG, "route not ready; skipping AudioRecord open")
            return null
        }
        val route = intercomAudioSession?.currentRoute()
        val preferredInput = intercomAudioSession?.preferredCaptureDevice()
            ?.takeIf { it.isSource && route?.micKind != CaptureInputKind.BUILTIN }
        val audioSource = route?.captureAudioSource ?: android.media.MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed ($minBuf)")
            return null
        }
        val bufferSize = maxOf(minBuf, frameBytes * 4)
        val recorder = try {
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing — grant mic permission and retry")
            intercomAudioSession?.onRecordAudioPermissionMissing()
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${recorder.state})")
            recorder.release()
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredInput != null) {
            val routed = recorder.setPreferredDevice(preferredInput)
            Log.i(
                TAG,
                "setPreferredDevice captureType=${preferredInput.type} " +
                    "micKind=${route?.micKind} success=$routed",
            )
        } else {
            Log.i(TAG, "AudioRecord opened with platform default input source=$audioSource")
        }
        return OpenedRecorder(recorder, preferredInput, audioSource)
    }

    private fun logRecorderRoute(recorder: AudioRecord, opened: OpenedRecorder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val routedDevice = recorder.routedDevice
        Log.i(
            TAG,
            "AudioRecord recording source=${opened.audioSource} " +
                "preferredType=${opened.preferredInput?.type} routedType=${routedDevice?.type}",
        )
        intercomAudioSession?.onCaptureRouteObserved(routedDevice)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun pcmRms(frame: ByteArray): Int {
        val shorts = bytesToShorts(frame)
        if (shorts.isEmpty()) return 0
        var sum = 0.0
        for (sample in shorts) {
            val v = sample.toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / shorts.size).toInt()
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2

        /** ~200 ms of pre-roll (10 frames at 20 ms) — see [voxCaptureLoop]. */
        private const val PRE_ROLL_FRAMES = 10
    }

    private data class OpenedRecorder(
        val recorder: AudioRecord,
        val preferredInput: AudioDeviceInfo?,
        val audioSource: Int,
    )
}
