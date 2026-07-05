package com.nblaisot.voxcrew.lanlink

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
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
class AudioCapture(private val scope: CoroutineScope) {
    private var policyJob: Job? = null
    private var recordJob: Job? = null

    fun attach(shouldTransmit: StateFlow<Boolean>, onFrame: (ByteArray) -> Unit) {
        detach()
        policyJob = scope.launch {
            shouldTransmit.collect { active ->
                if (active) {
                    if (recordJob?.isActive != true) {
                        recordJob = scope.launch(Dispatchers.IO) { captureLoop(onFrame) }
                    }
                } else {
                    recordJob?.cancel()
                    recordJob = null
                }
            }
        }
    }

    /**
     * Starts continuous VOX capture: the detector built by [voiceDetectorFactory]
     * analyzes every frame, [gate] turns those decisions into a debounced transmit/hold
     * state (reported via [onTransmittingChanged]), and only frames while the gate is
     * open reach [onFrame]. The detector is constructed lazily on [Dispatchers.IO]
     * (rather than by the caller) since building it loads and initializes an ONNX
     * model — no reason to pay that cost on the caller's thread. Returns the underlying
     * job so the caller can track/cancel it, though [detach] is the normal way to stop
     * it (and always wins a race with a stale [attach] call).
     */
    fun attachVox(
        voiceDetectorFactory: () -> VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
    ): Job {
        detach()
        val job = scope.launch(Dispatchers.IO) {
            voxCaptureLoop(voiceDetectorFactory(), gate, onTransmittingChanged, onFrame)
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

    private suspend fun captureLoop(onFrame: (ByteArray) -> Unit) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        val recorder = openRecorder(frameBytes) ?: return
        val encoder = OpusCodec.Encoder()
        try {
            recorder.startRecording()
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read == frame.size) {
                    val encoded = runCatching { encoder.encode(frame) }
                        .onFailure { Log.w(TAG, "Opus encode failed: ${it.message}") }
                        .getOrNull()
                    if (encoded != null) onFrame(encoded)
                } else if (read > 0) {
                    // A short read means a malformed Opus frame (fixed frame size); drop it
                    // rather than desync the encoder — only expected transiently at start/stop.
                    Log.d(TAG, "dropping short read ($read/$frameBytes bytes)")
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun voxCaptureLoop(
        voiceDetector: VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
    ) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        val recorder = openRecorder(frameBytes) ?: return
        val encoder = OpusCodec.Encoder()
        // Frames captured while the gate is closed, replayed the instant it opens so the
        // first syllable of a talkspurt isn't clipped while the detector was still deciding.
        val preRoll = ArrayDeque<ByteArray>()
        try {
            recorder.startRecording()
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read != frame.size) {
                    if (read > 0) Log.d(TAG, "dropping short read ($read/$frameBytes bytes)")
                    continue
                }
                val encoded = runCatching { encoder.encode(frame) }
                    .onFailure { Log.w(TAG, "Opus encode failed: ${it.message}") }
                    .getOrNull() ?: continue

                val decision = runCatching { voiceDetector.accept(bytesToShorts(frame)) }
                    .onFailure { Log.w(TAG, "VAD inference failed: ${it.message}") }
                    .getOrNull()
                val result = gate.update(decision, SystemClock.elapsedRealtime())
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
            recorder.release()
            voiceDetector.close()
            onTransmittingChanged(false)
        }
    }

    private fun openRecorder(frameBytes: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed ($minBuf)")
            return null
        }
        val bufferSize = maxOf(minBuf, frameBytes * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO permission missing: ${e.message}")
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord init failed: ${e.message}")
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2

        /** ~200 ms of pre-roll (10 frames at 20 ms) — see [voxCaptureLoop]. */
        private const val PRE_ROLL_FRAMES = 10
    }
}
