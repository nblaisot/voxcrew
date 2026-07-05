package com.nblaisot.voxcrew.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test seam around [AudioManager] for communication-mode routing. */
interface IntercomAudioManager {
    var mode: Int
    var isSpeakerphoneOn: Boolean
    fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?)
    fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback)
    fun getDevices(flags: Int): Array<out AudioDeviceInfo>
    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean
    fun clearCommunicationDevice()
}

/**
 * Puts the device on the VoIP audio path for the intercom session: communication mode,
 * speakerphone when no headset is connected, and automatic routing updates on plug/unplug.
 */
class IntercomAudioSession(
    context: Context,
    private val audioManager: IntercomAudioManager = AndroidIntercomAudioManager(
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    ),
) {
    private val deviceCallbackHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    private var savedMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var active = false

    private val _routingLabel = MutableStateFlow(RoutingLabel.EARPIECE)
    val routingLabel: StateFlow<RoutingLabel> = _routingLabel.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyRouting()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyRouting()
    }

    fun enter() {
        if (active) return
        active = true
        savedMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, deviceCallbackHandler)
        applyRouting()
        runCatching { Log.i(TAG, "entered communication mode (routing=${_routingLabel.value})") }
    }

    fun exit() {
        if (!active) return
        active = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        savedSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
        savedMode?.let { audioManager.mode = it }
        savedMode = null
        savedSpeakerphoneOn = null
        _routingLabel.value = RoutingLabel.EARPIECE
        runCatching { Log.i(TAG, "restored previous audio mode") }
    }

    private fun applyRouting() {
        val headsetConnected = audioManager.hasHeadsetConnected()
        if (headsetConnected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val headset = audioManager.findCommunicationHeadset()
                if (headset != null) {
                    audioManager.setCommunicationDevice(headset)
                }
            }
            audioManager.isSpeakerphoneOn = false
            _routingLabel.value = RoutingLabel.HEADSET
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = true
            _routingLabel.value = RoutingLabel.SPEAKER
        }
    }

    enum class RoutingLabel {
        SPEAKER,
        EARPIECE,
        HEADSET,
    }

    companion object {
        private const val TAG = "IntercomAudioSession"

        fun hasHeadsetConnected(devices: List<Int>): Boolean =
            devices.any { type ->
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
    }
}

private class AndroidIntercomAudioManager(
    private val audioManager: AudioManager,
) : IntercomAudioManager {
    override var mode: Int
        get() = audioManager.mode
        set(value) {
            audioManager.mode = value
        }

    override var isSpeakerphoneOn: Boolean
        get() = audioManager.isSpeakerphoneOn
        set(value) {
            audioManager.isSpeakerphoneOn = value
        }

    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    override fun getDevices(flags: Int): Array<out AudioDeviceInfo> = audioManager.getDevices(flags)

    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return audioManager.setCommunicationDevice(device)
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }
}

private fun IntercomAudioManager.hasHeadsetConnected(): Boolean {
    val types = getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    return IntercomAudioSession.hasHeadsetConnected(types)
}

private fun IntercomAudioManager.findCommunicationHeadset(): AudioDeviceInfo? {
    val preferred = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return preferred.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
}
