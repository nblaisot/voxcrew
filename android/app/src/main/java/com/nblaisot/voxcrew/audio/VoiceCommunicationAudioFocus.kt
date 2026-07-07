package com.nblaisot.voxcrew.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Holds [AudioManager.AUDIOFOCUS_GAIN] for the intercom voice path so OEM routing
 * (especially Samsung BT) keeps playback on the communication device.
 */
interface IntercomAudioFocus {
    fun request(): Boolean
    fun abandon()
    fun isGranted(): Boolean
}

class VoiceCommunicationAudioFocus(
    context: Context,
    private val audioManager: AudioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager,
) : IntercomAudioFocus {
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusGranted = false
    @Volatile private var focusGranted = false

    override fun request(): Boolean {
        abandon()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .build()
            val result = audioManager.requestAudioFocus(request)
            val ok = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (ok) focusRequest = request
            runCatching {
                Log.i(TAG, "audio focus request result=$result granted=$ok")
            }
            ok
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN,
            )
            legacyFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            runCatching {
                Log.i(TAG, "audio focus request result=$result granted=$legacyFocusGranted")
            }
            legacyFocusGranted
        }
        focusGranted = granted
        return granted
    }

    override fun isGranted(): Boolean = focusGranted

    override fun abandon() {
        focusGranted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else if (legacyFocusGranted) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
            legacyFocusGranted = false
        }
    }

    companion object {
        private const val TAG = "VoiceCommunicationAudioFocus"
    }
}
