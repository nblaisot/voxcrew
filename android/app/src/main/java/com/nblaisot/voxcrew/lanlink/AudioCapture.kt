package com.nblaisot.voxcrew.lanlink

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures raw PCM from the microphone in 20 ms frames while transmission is active,
 * and hands each frame to [onFrame]. Recording starts/stops with the transmission
 * policy (PTT press/release, or VOX speech detection) rather than running
 * continuously, per the "no recording by default" principle.
 */
class AudioCapture(private val scope: CoroutineScope) {
    private var policyJob: Job? = null
    private var recordJob: Job? = null

    fun attach(shouldTransmit: StateFlow<Boolean>, onFrame: (ByteArray) -> Unit) {
        policyJob?.cancel()
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

    fun detach() {
        policyJob?.cancel()
        policyJob = null
        recordJob?.cancel()
        recordJob = null
    }

    private suspend fun captureLoop(onFrame: (ByteArray) -> Unit) {
        val frameBytes = SAMPLE_RATE / 1000 * FRAME_MS * BYTES_PER_SAMPLE
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed ($minBuf)")
            return
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
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord init failed: ${e.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        try {
            recorder.startRecording()
            val frame = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read > 0) {
                    onFrame(if (read == frame.size) frame.copyOf() else frame.copyOf(read))
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val BYTES_PER_SAMPLE = 2
    }
}
