package com.nblaisot.voxcrew.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Short sonification cues for link and delivery events. Uses [ToneGenerator] on the
 * notification stream so feedback stays separate from intercom voice playback.
 */
class UiFeedbackPlayer(private val scope: CoroutineScope) {
    private val debounceMs = 300L
    @Volatile private var lastConnectedMs = 0L
    @Volatile private var lastDisconnectedMs = 0L

    fun playConnected() {
        if (!shouldPlay(lastConnectedMs)) return
        lastConnectedMs = System.currentTimeMillis()
        playTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    fun playDisconnected() {
        if (!shouldPlay(lastDisconnectedMs)) return
        lastDisconnectedMs = System.currentTimeMillis()
        playTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
    }

    private fun shouldPlay(lastMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return now - lastMs >= debounceMs
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                try {
                    tone.startTone(toneType, durationMs)
                    Thread.sleep(durationMs.toLong() + 50)
                } finally {
                    tone.release()
                }
            }
        }
    }
}
