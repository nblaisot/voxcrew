package com.nblaisot.voxcrew.lanlink

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nblaisot.voxcrew.audio.IntercomTelecomSession
import com.nblaisot.voxcrew.audio.ObservedAudioDeviceKind
import com.nblaisot.voxcrew.audio.PcmSpeechLeveler
import com.nblaisot.voxcrew.audio.TelecomCallState
import com.nblaisot.voxcrew.audio.VoxEchoGuard
import com.nblaisot.voxcrew.audio.VoxGate
import com.nblaisot.voxcrew.audio.VoiceDetector
import com.nblaisot.voxcrew.audio.observedDeviceKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** One continuously open capture pipeline shared by PTT/open-mic and VOX policies. */
class AudioCapture(
    private val scope: CoroutineScope,
    private val telecomSession: IntercomTelecomSession? = null,
) {
    @Volatile private var recordJob: Job? = null
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var captureGeneration = 0
    private val lock = Any()

    /** Fired when the platform re-routes the live recorder (e.g. SCO drops to builtin). */
    @Volatile var onRoutedDeviceChanged: ((ObservedAudioDeviceKind) -> Unit)? = null
    private val routingHandler = Handler(Looper.getMainLooper())
    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        onRoutedDeviceChanged?.invoke(observedDeviceKind(router.routedDevice?.type))
    }

    /** Current observed input kind of the live recorder, null when none is active. */
    fun observedRoutedKind(): ObservedAudioDeviceKind? =
        activeRecorder?.let { observedDeviceKind(routedDevice(it)?.type) }

    fun attach(
        shouldTransmit: StateFlow<Boolean>,
        onFrame: (ByteArray) -> Unit,
        onTransmissionStopped: () -> Unit = { },
        onFailure: (String) -> Unit = { },
    ): CaptureStartResult {
        val generation = replaceGeneration()
        val opened = openAndStartRecorder() ?: return CaptureStartResult.Failure("AudioRecord start failed")
        if (!storeRecorder(generation, opened.recorder)) {
            releaseRecorder(opened.recorder)
            return CaptureStartResult.Failure("AudioRecord start was superseded")
        }
        val job = scope.launch(Dispatchers.IO) {
            pttCaptureLoop(
                generation,
                opened,
                shouldTransmit,
                onFrame,
                onTransmissionStopped,
                onFailure,
            )
        }
        storeJob(generation, job)
        return CaptureStartResult.Success(job, observedDeviceKind(routedDevice(opened.recorder)?.type))
    }

    fun attachVox(
        voiceDetectorFactory: () -> VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
        isReceiving: () -> Boolean = { false },
        echoGuard: VoxEchoGuard = VoxEchoGuard(),
        onFailure: (String) -> Unit = { },
    ): CaptureStartResult {
        val generation = replaceGeneration()
        val opened = openAndStartRecorder() ?: return CaptureStartResult.Failure("AudioRecord start failed")
        if (!storeRecorder(generation, opened.recorder)) {
            releaseRecorder(opened.recorder)
            return CaptureStartResult.Failure("AudioRecord start was superseded")
        }
        val job = scope.launch(Dispatchers.IO) {
            val voiceDetector = runCatching { voiceDetectorFactory() }.getOrElse { error ->
                releaseRecorder(opened.recorder)
                clearRecorder(generation, opened.recorder)
                if (generation == captureGeneration && currentCoroutineContext().isActive) {
                    onFailure("Voice detector start failed: ${error.message}")
                }
                return@launch
            }
            voxCaptureLoop(
                generation = generation,
                opened = opened,
                voiceDetector = voiceDetector,
                gate = gate,
                onTransmittingChanged = onTransmittingChanged,
                onFrame = onFrame,
                isReceiving = isReceiving,
                echoGuard = echoGuard,
                onFailure = onFailure,
            )
        }
        storeJob(generation, job)
        return CaptureStartResult.Success(job, observedDeviceKind(routedDevice(opened.recorder)?.type))
    }

    fun detach() {
        val (job, recorder) = synchronized(lock) {
            captureGeneration++
            val previousJob = recordJob.also { recordJob = null }
            val previousRecorder = activeRecorder.also { activeRecorder = null }
            previousJob?.cancel()
            previousJob to previousRecorder
        }
        recorder?.let { runCatching { it.stop() } }
        job?.let { runCatching { runBlocking { it.join() } } }
    }

    private fun replaceGeneration(): Int {
        val (generation, previousJob, previousRecorder) = synchronized(lock) {
            captureGeneration++
            val oldJob = recordJob
            val oldRecorder = activeRecorder
            recordJob = null
            activeRecorder = null
            oldJob?.cancel()
            Triple(captureGeneration, oldJob, oldRecorder)
        }
        previousRecorder?.let { runCatching { it.stop() } }
        previousJob?.let { runCatching { runBlocking { it.join() } } }
        return generation
    }

    private fun storeRecorder(generation: Int, recorder: AudioRecord): Boolean = synchronized(lock) {
        if (generation != captureGeneration) return@synchronized false
        activeRecorder = recorder
        runCatching { recorder.addOnRoutingChangedListener(routingListener, routingHandler) }
        true
    }

    private fun clearRecorder(generation: Int, recorder: AudioRecord) {
        synchronized(lock) {
            if (generation == captureGeneration && activeRecorder === recorder) activeRecorder = null
        }
    }

    private fun storeJob(generation: Int, job: Job) {
        synchronized(lock) {
            if (generation == captureGeneration) recordJob = job else job.cancel()
        }
    }

    private suspend fun pttCaptureLoop(
        generation: Int,
        opened: OpenedRecorder,
        shouldTransmit: StateFlow<Boolean>,
        onFrame: (ByteArray) -> Unit,
        onTransmissionStopped: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val recorder = opened.recorder
        val encoder = OpusCodec.Encoder()
        val leveler = PcmSpeechLeveler()
        var fullFrames = 0
        var encodedFrames = 0L
        var wasTransmitting = false
        try {
            val frame = ByteArray(FRAME_BYTES)
            while (currentCoroutineContext().isActive && generation == captureGeneration) {
                readCompleteFrame(recorder, frame)
                fullFrames++
                logInitialFrame(fullFrames, frame, opened, recorder)
                val transmitting = shouldTransmit.value
                if (!transmitting) {
                    if (wasTransmitting) onTransmissionStopped()
                    wasTransmitting = false
                    continue
                }
                if (!wasTransmitting) leveler.reset()
                wasTransmitting = true
                val leveled = leveler.process(frame)
                val encoded = encoder.encode(leveled.bytes)
                encodedFrames++
                if (encodedFrames == 1L || encodedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
                    Log.i(
                        TAG,
                        "encoded frame=$encodedFrames bytes=${encoded.size} inputRms=${leveled.inputRms} " +
                            "outputRms=${leveled.outputRms} gain=${"%.2f".format(leveled.gain)}",
                    )
                }
                onFrame(encoded)
            }
        } catch (error: CaptureReadException) {
            if (generation == captureGeneration && currentCoroutineContext().isActive) {
                Log.e(TAG, error.message.orEmpty())
                onFailure(error.message.orEmpty())
            }
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) {
                Log.e(TAG, "capture failed: ${error.message}", error)
                onFailure(error.message ?: "capture failed")
            }
        } finally {
            clearRecorder(generation, recorder)
            releaseRecorder(recorder)
        }
    }

    private suspend fun voxCaptureLoop(
        generation: Int,
        opened: OpenedRecorder,
        voiceDetector: VoiceDetector,
        gate: VoxGate,
        onTransmittingChanged: (Boolean) -> Unit,
        onFrame: (ByteArray) -> Unit,
        isReceiving: () -> Boolean,
        echoGuard: VoxEchoGuard,
        onFailure: (String) -> Unit,
    ) {
        val recorder = opened.recorder
        val encoder = OpusCodec.Encoder()
        val leveler = PcmSpeechLeveler()
        val preRoll = ArrayDeque<ByteArray>()
        var fullFrames = 0
        var encodedFrames = 0L
        try {
            fun sendLeveled(rawPcm: ByteArray) {
                val leveled = leveler.process(rawPcm)
                val encoded = encoder.encode(leveled.bytes)
                encodedFrames++
                if (encodedFrames == 1L || encodedFrames % DIAGNOSTIC_FRAME_INTERVAL == 0L) {
                    Log.i(
                        TAG,
                        "VOX encoded frame=$encodedFrames bytes=${encoded.size} " +
                            "inputRms=${leveled.inputRms} outputRms=${leveled.outputRms} " +
                            "gain=${"%.2f".format(leveled.gain)}",
                    )
                }
                onFrame(encoded)
            }
            val frame = ByteArray(FRAME_BYTES)
            while (currentCoroutineContext().isActive && generation == captureGeneration) {
                readCompleteFrame(recorder, frame)
                fullFrames++
                logInitialFrame(fullFrames, frame, opened, recorder)

                val nowMs = SystemClock.elapsedRealtime()
                echoGuard.onReceivingChanged(isReceiving(), nowMs)
                val decision = voiceDetector.accept(bytesToShorts(frame))
                val result = gate.update(echoGuard.filterSpeechDecision(decision, nowMs), nowMs)
                onTransmittingChanged(result.transmitting)

                if (result.transmitting) {
                    if (result.onset) {
                        leveler.reset()
                        while (preRoll.isNotEmpty()) {
                            sendLeveled(preRoll.removeFirst())
                        }
                    }
                    sendLeveled(frame)
                } else {
                    preRoll.addLast(frame.copyOf())
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
            }
        } catch (error: CaptureReadException) {
            if (generation == captureGeneration && currentCoroutineContext().isActive) {
                Log.e(TAG, error.message.orEmpty())
                onFailure(error.message.orEmpty())
            }
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) {
                Log.e(TAG, "VOX capture failed: ${error.message}", error)
                onFailure(error.message ?: "VOX capture failed")
            }
        } finally {
            clearRecorder(generation, recorder)
            releaseRecorder(recorder)
            voiceDetector.close()
            echoGuard.reset()
            onTransmittingChanged(false)
        }
    }

    private fun openAndStartRecorder(): OpenedRecorder? {
        val callState = telecomSession?.currentState
        if (callState != null && !callState.mediaActive) {
            Log.w(TAG, "Telecom call has no active current endpoint; skipping AudioRecord open")
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord construction failed: ${error.message}")
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        try {
            recorder.startRecording()
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord start failed: ${error.message}")
            recorder.release()
            return null
        }
        val opened = OpenedRecorder(recorder, callState ?: TelecomCallState())
        Log.i(
            TAG,
            "AudioRecord started source=${MediaRecorder.AudioSource.VOICE_COMMUNICATION} " +
                "endpoint=${callState?.currentEndpoint?.name} endpointType=${callState?.currentEndpoint?.type} " +
                "sessionId=${recorder.audioSessionId} routedType=${routedDevice(recorder)?.type}",
        )
        return opened
    }

    private fun readCompleteFrame(recorder: AudioRecord, frame: ByteArray) {
        try {
            fillPcmFrame(frame) { offset, byteCount ->
                recorder.read(frame, offset, byteCount, AudioRecord.READ_BLOCKING)
            }
        } catch (error: IllegalStateException) {
            throw CaptureReadException(error.message ?: "AudioRecord.read failed")
        }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        runCatching { recorder.removeOnRoutingChangedListener(routingListener) }
        runCatching { recorder.stop() }
        recorder.release()
    }

    private fun logInitialFrame(
        frameNumber: Int,
        frame: ByteArray,
        opened: OpenedRecorder,
        recorder: AudioRecord,
    ) {
        if (frameNumber > INITIAL_CAPTURE_DIAGNOSTIC_FRAMES) return
        Log.i(
            TAG,
            "capture frame=$frameNumber endpointType=${opened.callState.currentEndpoint?.type} " +
                "routedType=${routedDevice(recorder)?.type} pcmRms=${pcmRms(frame)} " +
                "allZero=${frame.all { it == 0.toByte() }}",
        )
    }

    private fun routedDevice(recorder: AudioRecord): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.routedDevice else null

    private fun bytesToShorts(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2).also {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
    }

    private fun pcmRms(frame: ByteArray): Int {
        val samples = bytesToShorts(frame)
        if (samples.isEmpty()) return 0
        return kotlin.math.sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size).toInt()
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        private const val INITIAL_CAPTURE_DIAGNOSTIC_FRAMES = 5
        private const val DIAGNOSTIC_FRAME_INTERVAL = 100L
        private const val PRE_ROLL_FRAMES = 10
    }

    private data class OpenedRecorder(val recorder: AudioRecord, val callState: TelecomCallState)
    private class CaptureReadException(message: String) : IllegalStateException(message)
}

sealed interface CaptureStartResult {
    data class Success(val job: Job, val observedInput: ObservedAudioDeviceKind) : CaptureStartResult
    data class Failure(val reason: String) : CaptureStartResult
}

/** Fills exactly one PCM frame, preserving short-read bytes and failing on no progress. */
internal fun fillPcmFrame(
    frame: ByteArray,
    read: (offset: Int, byteCount: Int) -> Int,
) {
    var offset = 0
    while (offset < frame.size) {
        val count = read(offset, frame.size - offset)
        check(count in 1..(frame.size - offset)) {
            "AudioRecord.read failed code=$count offset=$offset"
        }
        offset += count
    }
}
