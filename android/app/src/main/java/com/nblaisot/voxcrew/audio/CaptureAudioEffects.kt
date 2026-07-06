package com.nblaisot.voxcrew.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Attaches platform capture preprocessing (AEC, noise suppression, AGC) to an
 * [android.media.AudioRecord] session. All effects are optional — OEMs may not
 * expose every effect on every device.
 */
class CaptureAudioEffects private constructor(
    private val aec: AudioEffectHandle?,
    private val ns: AudioEffectHandle?,
    private val agc: AudioEffectHandle?,
) {
    val diagnostics: CaptureAudioDiagnostics
        get() = CaptureAudioDiagnostics(
            aecAvailable = aec?.isAvailable == true,
            aecEnabled = aec?.enabled == true,
            nsAvailable = ns?.isAvailable == true,
            nsEnabled = ns?.enabled == true,
            agcAvailable = agc?.isAvailable == true,
            agcEnabled = agc?.enabled == true,
        )

    private var released = false

    fun release() {
        if (released) return
        released = true
        aec?.release()
        ns?.release()
        agc?.release()
    }

    companion object {
        private const val TAG = "CaptureAudioEffects"

        fun attach(
            sessionId: Int,
            factories: AudioEffectFactories = AudioEffectFactories.Default,
        ): CaptureAudioEffects {
            val aec = factories.createAec(sessionId)?.also { effect ->
                if (effect.isAvailable) effect.enabled = true
            }
            val ns = factories.createNs(sessionId)?.also { effect ->
                if (effect.isAvailable) effect.enabled = true
            }
            val agc = factories.createAgc(sessionId)?.also { effect ->
                if (effect.isAvailable) effect.enabled = true
            }
            val effects = CaptureAudioEffects(aec, ns, agc)
            runCatching {
                Log.i(
                    TAG,
                    "session=$sessionId aec=${effects.diagnostics.aecAvailable}/${effects.diagnostics.aecEnabled} " +
                        "ns=${effects.diagnostics.nsAvailable}/${effects.diagnostics.nsEnabled} " +
                        "agc=${effects.diagnostics.agcAvailable}/${effects.diagnostics.agcEnabled}",
                )
            }
            return effects
        }
    }
}

data class CaptureAudioDiagnostics(
    val aecAvailable: Boolean,
    val aecEnabled: Boolean,
    val nsAvailable: Boolean,
    val nsEnabled: Boolean,
    val agcAvailable: Boolean,
    val agcEnabled: Boolean,
)

/** Injectable factories so unit tests can stub unavailable effects without hardware. */
interface AudioEffectHandle {
    val isAvailable: Boolean
    var enabled: Boolean
    fun release()
}

class AudioEffectFactories(
    val createAec: (Int) -> AudioEffectHandle?,
    val createNs: (Int) -> AudioEffectHandle?,
    val createAgc: (Int) -> AudioEffectHandle?,
) {
    companion object {
        val Default = AudioEffectFactories(
            createAec = { sessionId ->
                AcousticEchoCanceler.create(sessionId)?.let { PlatformAudioEffectHandle(it) }
            },
            createNs = { sessionId ->
                NoiseSuppressor.create(sessionId)?.let { PlatformAudioEffectHandle(it) }
            },
            createAgc = { sessionId ->
                AutomaticGainControl.create(sessionId)?.let { PlatformAudioEffectHandle(it) }
            },
        )
    }
}

private class PlatformAudioEffectHandle(
    private val effect: AudioEffect,
) : AudioEffectHandle {
    override val isAvailable: Boolean = true
    override var enabled: Boolean
        get() = effect.enabled
        set(value) {
            effect.enabled = value
        }

    override fun release() {
        effect.release()
    }
}
