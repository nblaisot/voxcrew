package com.nblaisot.voxcrew.lanlink

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nblaisot.voxcrew.audio.AudioRouteSelector
import com.nblaisot.voxcrew.audio.CaptureAudioEffects
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.IntercomAudioSession
import com.nblaisot.voxcrew.audio.AudioRouteState
import com.nblaisot.voxcrew.audio.VoxEchoGuard
import com.nblaisot.voxcrew.audio.VoxGate
import com.nblaisot.voxcrew.audio.VoiceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures the microphone, encodes each 20 ms frame with Opus (see [OpusCodec]) and
 * hands the encoded payload to a callback. Two capture modes are supported:
 *
 * - [attach]: `AudioRecord` stays open for the intercom session; [shouldTransmit] gates
 *   encoding and network send only (PTT press/release does not open/close the mic).
 * - [attachVox]: the mic runs continuously while VOX is enabled (needed so a
 *   [VoiceDetector] can listen for speech onset), but *transmission* is gated by
 *   [VoxGate] — audio is analyzed in memory and only handed to the caller (i.e. only
 *   ever leaves the device) while a human voice is actually detected.
 */
class AudioCapture(
    private val scope: CoroutineScope,
    private val intercomAudioSession: IntercomAudioSession? = null,
    private val playbackSessionIdProvider: (() -> Int?)? = null,
) {
    @Volatile
    private var recordJob: Job? = null
    @Volatile
    private var captureGeneration = 0
    private val lock = Any()

    fun attach(shouldTransmit: StateFlow<Boolean>, onFrame: (ByteArray) -> Unit) {
        val (generation, previous) = synchronized(lock) {
            captureGeneration++
            val gen = captureGeneration
            val old = recordJob
            recordJob = null
            old?.cancel()
            gen to old
        }
        previous?.let { job ->
            runCatching { runBlocking { job.join() } }
        }
        synchronized(lock) {
            if (generation != captureGeneration) return
            recordJob = scope.launch(Dispatchers.IO) {
                pttCaptureLoop(generation, shouldTransmit, onFrame)
            }
        }
    }

    private suspend fun pttCaptureLoop(
        generation: Int,
        shouldTransmit: StateFlow<Boolean>,
        onFrame: (ByteArray) -> Unit,
    ) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        var opened: OpenedRecorder? = null
        while (currentCoroutineContext().isActive && generation == captureGeneration) {
            opened = openRecorder(frameBytes)
            if (opened != null) break
            delay(CAPTURE_OPEN_RETRY_MS)
        }
        val openedRecorder = opened ?: return
        val recorder = openedRecorder.recorder
        val effectsSessionId = playbackSessionIdProvider?.invoke()?.takeIf { it > 0 }
            ?: recorder.audioSessionId
        val effects = CaptureAudioEffects.attach(effectsSessionId)
        val encoder = OpusCodec.Encoder()
        var encodedFrames = 0
        var zeroReads = 0
        var fullFrames = 0
        try {
            recorder.startRecording()
            logRecorderRoute(recorder, openedRecorder)
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive && generation == captureGeneration) {
                val read = recorder.read(frame, 0, frame.size)
                if (read == frame.size) {
                    zeroReads = 0
                    fullFrames++
                    logInitialFrame(fullFrames, frame, openedRecorder, recorder)
                    if (!shouldTransmit.value) continue
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
        val (generation, previous) = synchronized(lock) {
            captureGeneration++
            val gen = captureGeneration
            val old = recordJob
            recordJob = null
            old?.cancel()
            gen to old
        }
        previous?.let { job ->
            runCatching { runBlocking { job.join() } }
        }
        val job = scope.launch(Dispatchers.IO) {
            if (generation != captureGeneration) return@launch
            voxCaptureLoop(
                    voiceDetectorFactory(),
                    gate,
                    onTransmittingChanged,
                    onFrame,
                    isReceiving,
                echoGuard,
            )
        }
        synchronized(lock) {
            if (generation == captureGeneration) {
                recordJob = job
            }
        }
        return job
    }

    fun detach() {
        synchronized(lock) {
            captureGeneration++
            val job = recordJob
            recordJob = null
            job?.cancel()
        }
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
        val effectsSessionId = playbackSessionIdProvider?.invoke()?.takeIf { it > 0 }
            ?: recorder.audioSessionId
        val effects = CaptureAudioEffects.attach(effectsSessionId)
        val encoder = OpusCodec.Encoder()
        val preRoll = ArrayDeque<ByteArray>()
        var fullFrames = 0
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
                fullFrames++
                logInitialFrame(fullFrames, frame, opened, recorder)
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
            Log.w(TAG, "audio permissions block capture; skipping AudioRecord open")
            return null
        }
        val route = intercomAudioSession?.currentRoute()
        val bluetoothPending = route?.micKind == CaptureInputKind.BLUETOOTH && route.routeReady == false
        if (route?.micKind == CaptureInputKind.BLUETOOTH &&
            !bluetoothPending &&
            !AudioRouteSelector.isBluetoothCaptureDevice(route.captureDevice)
        ) {
            Log.i(
                TAG,
                "BT capture route not stable captureType=${route.captureDevice?.type}; deferring AudioRecord open",
            )
            return null
        }
        var preferredInput = intercomAudioSession?.preferredCaptureDevice()
        if (route?.micKind == CaptureInputKind.BLUETOOTH && preferredInput == null) {
            if (bluetoothPending) {
                Log.i(
                    TAG,
                    "BT route pending; opening capture on default device to unlock platform routing",
                )
            } else {
                Log.i(TAG, "BT route missing preferred capture device; deferring AudioRecord open")
                return null
            }
        }
        val playbackSessionId = playbackSessionIdProvider?.invoke()
        val audioSource = route?.captureAudioSource ?: MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed ($minBuf)")
            return null
        }
        val bufferSize = maxOf(minBuf, frameBytes * 4)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
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
                    "micKind=${route?.micKind} mode=${route?.audioMode} source=$audioSource " +
                    "playbackSessionId=$playbackSessionId success=$routed",
            )
        } else {
            Log.i(
                TAG,
                "AudioRecord opened source=$audioSource micKind=${route?.micKind} " +
                    "playbackSessionId=$playbackSessionId",
            )
        }
        return OpenedRecorder(recorder, preferredInput, audioSource, route ?: AudioRouteState.builtIn())
    }

    private fun logRecorderRoute(recorder: AudioRecord, opened: OpenedRecorder) {
        val routedDevice = routedDevice(recorder)
        Log.i(
            TAG,
            "AudioRecord recording source=${opened.audioSource} " +
                "routeMic=${opened.route.micKind} preferredType=${opened.preferredInput?.type} " +
                "routedType=${routedDevice?.type}",
        )
        intercomAudioSession?.onCaptureRouteObserved(routedDevice)
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
            "capture frame=$frameNumber source=${opened.audioSource} routeMic=${opened.route.micKind} " +
                "preferredType=${opened.preferredInput?.type} routedType=${routedDevice(recorder)?.type} " +
                "pcmRms=${pcmRms(frame)} allZero=${isAllZeroPcm(frame)}",
        )
    }

    private fun routedDevice(recorder: AudioRecord): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.routedDevice else null

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

    private fun isAllZeroPcm(frame: ByteArray): Boolean = frame.all { it == 0.toByte() }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2
        private const val INITIAL_CAPTURE_DIAGNOSTIC_FRAMES = 5
        private const val CAPTURE_OPEN_RETRY_MS = 200L

        /** ~200 ms of pre-roll (10 frames at 20 ms) — see [voxCaptureLoop]. */
        private const val PRE_ROLL_FRAMES = 10
    }

    private data class OpenedRecorder(
        val recorder: AudioRecord,
        val preferredInput: AudioDeviceInfo?,
        val audioSource: Int,
        val route: AudioRouteState,
    )
}
