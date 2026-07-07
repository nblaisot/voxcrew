package com.nblaisot.voxcrew.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

enum class CaptureInputKind {
    BUILTIN,
    BLUETOOTH,
    USB,
}

enum class OutputKind {
    SPEAKER,
    BLUETOOTH,
    USB,
    WIRED,
}

enum class CaptureSource {
    DEVICE_MIC,
    HEADSET_MIC,
}

enum class AudioPermissionIssue {
    RECORD_AUDIO,
    BLUETOOTH_CONNECT,
}

data class AudioRouteState(
    val micKind: CaptureInputKind,
    val captureDevice: AudioDeviceInfo?,
    val outputDevice: AudioDeviceInfo?,
    val outputKind: OutputKind,
    val captureSource: CaptureSource,
    val playbackUsage: Int,
    val audioMode: Int,
    val routeReady: Boolean,
    val permissionIssue: AudioPermissionIssue? = null,
) {
    val captureInput: AudioDeviceInfo? get() = captureDevice
    val captureInputKind: CaptureInputKind get() = micKind
    val captureAudioSource: Int
        get() = if (audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

    companion object {
        fun builtIn(
            routeReady: Boolean = true,
            permissionIssue: AudioPermissionIssue? = null,
        ): AudioRouteState = AudioRouteState(
            micKind = CaptureInputKind.BUILTIN,
            captureDevice = null,
            outputDevice = null,
            outputKind = OutputKind.SPEAKER,
            captureSource = CaptureSource.DEVICE_MIC,
            playbackUsage = AudioAttributes.USAGE_MEDIA,
            audioMode = AudioManager.MODE_NORMAL,
            routeReady = routeReady,
            permissionIssue = permissionIssue,
        )
    }
}

typealias AudioRoute = AudioRouteState

data class AudioRouteSelection(
    val route: AudioRouteState,
    val communicationDevice: AudioDeviceInfo? = null,
    val needsLegacyBluetoothSco: Boolean = false,
)

object AudioRouteSelector {
    private val BLUETOOTH_MIC_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private val BLUETOOTH_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    private val WIRED_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )

    private val EXTERNAL_OUTPUT_PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )

    val COMMUNICATION_TYPE_PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun isHeadsetPresent(deviceTypes: Collection<Int>): Boolean =
        deviceTypes.any { it in BLUETOOTH_OUTPUT_TYPES || it in USB_TYPES || it in WIRED_OUTPUT_TYPES }

    fun pickCommunicationDeviceType(availableTypes: Collection<Int>): Int? =
        COMMUNICATION_TYPE_PRIORITY.firstOrNull { it in availableTypes }

    fun pickCaptureInputType(inputTypes: Collection<Int>): Int? =
        COMMUNICATION_TYPE_PRIORITY.firstOrNull { it in inputTypes }
            ?: inputTypes.firstOrNull { it in USB_TYPES }

    fun shouldUseSpeakerphone(connectedTypes: Collection<Int>): Boolean =
        !isHeadsetPresent(connectedTypes)

    fun pttMicIconKind(route: AudioRouteState): CaptureInputKind? {
        if (!route.routeReady) return null
        return when (route.micKind) {
            CaptureInputKind.BLUETOOTH -> CaptureInputKind.BLUETOOTH
            CaptureInputKind.USB -> CaptureInputKind.USB
            CaptureInputKind.BUILTIN -> null
        }
    }

    fun pttMicIconKind(route: AudioRouteState, routeReady: Boolean): CaptureInputKind? =
        pttMicIconKind(route.copy(routeReady = routeReady))

    fun resolve(
        outputs: List<AudioDeviceInfo>,
        inputs: List<AudioDeviceInfo>,
        availableCommunicationDevices: List<AudioDeviceInfo>,
        activeCommunicationDevice: AudioDeviceInfo?,
        supportsCommunicationDeviceApi: Boolean,
        recordAudioGranted: Boolean,
        bluetoothConnectGranted: Boolean,
        ignoreBluetoothMicrophones: Boolean = false,
    ): AudioRouteSelection {
        if (!recordAudioGranted) {
            return AudioRouteSelection(
                AudioRouteState.builtIn(
                    routeReady = false,
                    permissionIssue = AudioPermissionIssue.RECORD_AUDIO,
                ),
            )
        }

        val outputSinks = outputs.filter { it.isSink }
        val inputSources = inputs.filter { it.isSource }

        if (!ignoreBluetoothMicrophones) {
            val bluetoothSelection = resolveBluetoothMicRoute(
                outputSinks = outputSinks,
                inputSources = inputSources,
                availableCommunicationDevices = availableCommunicationDevices,
                activeCommunicationDevice = activeCommunicationDevice,
                supportsCommunicationDeviceApi = supportsCommunicationDeviceApi,
                bluetoothConnectGranted = bluetoothConnectGranted,
            )
            if (bluetoothSelection != null) return bluetoothSelection
        }

        val usbInput = pickUsbInput(inputSources)
        if (usbInput != null) {
            val usbOutput = matchingOutput(outputSinks, usbInput, USB_TYPES)
                ?: pickOutput(outputSinks, USB_TYPES)
                ?: pickExternalOutput(outputSinks)
            return AudioRouteSelection(
                AudioRouteState(
                    micKind = CaptureInputKind.USB,
                    captureDevice = usbInput,
                    outputDevice = usbOutput,
                    outputKind = outputKindFor(usbOutput),
                    captureSource = CaptureSource.HEADSET_MIC,
                    playbackUsage = AudioAttributes.USAGE_MEDIA,
                    audioMode = AudioManager.MODE_NORMAL,
                    routeReady = true,
                ),
            )
        }

        val externalOutput = pickExternalOutput(outputSinks)
        if (externalOutput != null) {
            return AudioRouteSelection(
                AudioRouteState(
                    micKind = CaptureInputKind.BUILTIN,
                    captureDevice = null,
                    outputDevice = externalOutput,
                    outputKind = outputKindFor(externalOutput),
                    captureSource = CaptureSource.DEVICE_MIC,
                    playbackUsage = AudioAttributes.USAGE_MEDIA,
                    audioMode = AudioManager.MODE_NORMAL,
                    routeReady = true,
                ),
            )
        }

        return AudioRouteSelection(AudioRouteState.builtIn())
    }

    fun deviceIdentity(device: AudioDeviceInfo?): String? {
        if (device == null) return null
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
        return "${device.type}:$address"
    }

    fun sameDevice(a: AudioDeviceInfo?, b: AudioDeviceInfo?): Boolean {
        if (a == null || b == null) return a == b
        val aIdentity = deviceIdentity(a)
        val bIdentity = deviceIdentity(b)
        return if (aIdentity != null && bIdentity != null) aIdentity == bIdentity else a.type == b.type
    }

    private fun resolveBluetoothMicRoute(
        outputSinks: List<AudioDeviceInfo>,
        inputSources: List<AudioDeviceInfo>,
        availableCommunicationDevices: List<AudioDeviceInfo>,
        activeCommunicationDevice: AudioDeviceInfo?,
        supportsCommunicationDeviceApi: Boolean,
        bluetoothConnectGranted: Boolean,
    ): AudioRouteSelection? {
        val bluetoothInput = pickBluetoothInput(inputSources)
        val communicationDevice = if (supportsCommunicationDeviceApi) {
            pickBluetoothCommunicationDevice(availableCommunicationDevices)
        } else {
            null
        }

        if (bluetoothInput == null && communicationDevice == null) return null

        val bluetoothOutput = communicationDevice ?: matchingOutput(outputSinks, bluetoothInput, BLUETOOTH_OUTPUT_TYPES)
            ?: pickOutput(outputSinks, BLUETOOTH_MIC_TYPES)

        if (supportsCommunicationDeviceApi && !bluetoothConnectGranted) {
            return AudioRouteSelection(
                AudioRouteState(
                    micKind = CaptureInputKind.BLUETOOTH,
                    captureDevice = bluetoothInput,
                    outputDevice = bluetoothOutput,
                    outputKind = OutputKind.BLUETOOTH,
                    captureSource = CaptureSource.HEADSET_MIC,
                    playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                    routeReady = false,
                    permissionIssue = AudioPermissionIssue.BLUETOOTH_CONNECT,
                ),
                communicationDevice = bluetoothOutput,
            )
        }

        val target = communicationDevice ?: bluetoothOutput ?: bluetoothInput
        val activeMatchesTarget = !supportsCommunicationDeviceApi || sameDevice(activeCommunicationDevice, target)
        return AudioRouteSelection(
            route = AudioRouteState(
                micKind = CaptureInputKind.BLUETOOTH,
                captureDevice = bluetoothInput?.takeIf { it.isSource },
                outputDevice = target?.takeIf { it.isSink } ?: bluetoothOutput,
                outputKind = OutputKind.BLUETOOTH,
                captureSource = CaptureSource.HEADSET_MIC,
                playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                routeReady = activeMatchesTarget,
            ),
            communicationDevice = target?.takeIf { it.isSink },
            needsLegacyBluetoothSco = !supportsCommunicationDeviceApi,
        )
    }

    private fun pickBluetoothCommunicationDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val sinks = devices.filter { it.isSink }
        val type = pickCommunicationDeviceType(sinks.map { it.type })
        return type?.let { picked -> sinks.firstOrNull { it.type == picked } }
    }

    private fun pickBluetoothInput(inputs: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val type = pickCaptureInputType(inputs.map { it.type })?.takeIf { it in BLUETOOTH_MIC_TYPES }
        return type?.let { picked -> inputs.firstOrNull { it.type == picked } }
    }

    private fun pickUsbInput(inputs: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val type = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        ).firstOrNull { preferred -> inputs.any { it.type == preferred } }
        return type?.let { picked -> inputs.firstOrNull { it.type == picked } }
    }

    private fun pickExternalOutput(outputs: List<AudioDeviceInfo>): AudioDeviceInfo? =
        EXTERNAL_OUTPUT_PRIORITY.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        }

    private fun pickOutput(outputs: List<AudioDeviceInfo>, types: Set<Int>): AudioDeviceInfo? =
        EXTERNAL_OUTPUT_PRIORITY.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type && it.type in types }
        }

    private fun matchingOutput(
        outputs: List<AudioDeviceInfo>,
        input: AudioDeviceInfo?,
        allowedTypes: Set<Int>,
    ): AudioDeviceInfo? {
        if (input == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val address = input.address
            if (address.isNotEmpty()) {
                outputs.firstOrNull { it.type in allowedTypes && it.address == address }?.let { return it }
            }
        }
        return outputs.firstOrNull { it.type == input.type && it.type in allowedTypes }
    }

    private fun outputKindFor(output: AudioDeviceInfo?): OutputKind =
        when (output?.type) {
            null,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            -> OutputKind.SPEAKER
            in BLUETOOTH_OUTPUT_TYPES -> OutputKind.BLUETOOTH
            in USB_TYPES -> OutputKind.USB
            else -> OutputKind.WIRED
        }
}
