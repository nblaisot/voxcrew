package com.nblaisot.voxcrew.audio

import android.media.AudioDeviceInfo
import androidx.core.telecom.CallEndpointCompat

enum class CaptureInputKind {
    BUILTIN,
    BLUETOOTH,
    USB,
    WIRED,
}

const val DEVICE_AUDIO_ROUTE_KEY = "device"

enum class AudioRouteTarget {
    DEVICE,
    BLUETOOTH,
    WIRED_USB,
}

enum class ManualRouteStatus {
    STARTING,
    REQUESTING,
    CONFIRMED,
    DIVERGED,
    UNAVAILABLE,
    FAILED,
}

/** A user-selectable route backed by an exact Telecom endpoint when it is an accessory. */
data class AudioRouteChoice(
    val key: String,
    val name: String,
    val inputKind: CaptureInputKind,
    val target: AudioRouteTarget,
    val endpointIdentifier: String?,
    val endpointType: Int,
    /** Stable Bluetooth MAC when known; used for menu identity across rename/UUID churn. */
    val bluetoothAddress: String? = null,
)

data class AudioRouteSelectionState(
    val availableChoices: List<AudioRouteChoice> = listOf(deviceAudioRouteChoice()),
    val selectedChoice: AudioRouteChoice = deviceAudioRouteChoice(),
    val status: ManualRouteStatus = ManualRouteStatus.STARTING,
    val confirmedChoiceKey: String? = null,
    val errorCode: Int? = null,
)

fun deviceAudioRouteChoice(
    endpointIdentifier: String? = null,
    name: String = "This device",
): AudioRouteChoice =
    AudioRouteChoice(
        key = DEVICE_AUDIO_ROUTE_KEY,
        name = name,
        inputKind = CaptureInputKind.BUILTIN,
        target = AudioRouteTarget.DEVICE,
        endpointIdentifier = endpointIdentifier,
        endpointType = CallEndpointCompat.TYPE_SPEAKER,
    )

enum class OutputKind {
    SPEAKER,
    BLUETOOTH,
    USB,
    WIRED,
}

enum class AudioPermissionIssue {
    RECORD_AUDIO,
    BLUETOOTH_CONNECT,
}

enum class AudioSessionIssue {
    TELECOM_UNAVAILABLE,
    AUDIO_PIPELINE_FAILED,
}

enum class TelecomCallPhase {
    STARTING,
    ACTIVE,
    INACTIVE,
    FAILED,
    STOPPED,
}

/** A Telecom endpoint detached from framework objects for deterministic tests. */
data class TelecomEndpoint(
    val identifier: String,
    val name: String,
    val type: Int,
    /** Bluetooth MAC when resolved; null for non-BT or unresolved endpoints. */
    val bluetoothAddress: String? = null,
) {
    val isAccessory: Boolean
        get() = type == CallEndpointCompat.TYPE_BLUETOOTH ||
            type == CallEndpointCompat.TYPE_WIRED_HEADSET
}

/** Stable menu key for a Bluetooth accessory identified by MAC. */
fun bluetoothAudioRouteKey(address: String): String = "bt:$address"

/**
 * Same physical device: exact Telecom identifier, or same Bluetooth MAC. Telecom mints a
 * new identifier when buds flip SCO/LE Audio profiles, so identifier equality alone would
 * report false divergence for the very device the user selected.
 */
fun sameTelecomEndpoint(a: TelecomEndpoint?, b: TelecomEndpoint?): Boolean =
    a != null && b != null &&
        (
            a.identifier == b.identifier ||
                (a.bluetoothAddress != null && a.bluetoothAddress == b.bluetoothAddress)
            )

/**
 * Telecom owns routing. The current endpoint is the only statement about where call media
 * actually flows; available endpoints are choices, never readiness signals.
 */
data class TelecomCallState(
    val phase: TelecomCallPhase = TelecomCallPhase.STOPPED,
    val currentEndpoint: TelecomEndpoint? = null,
    val selectedEndpoint: TelecomEndpoint? = null,
    val availableEndpoints: List<TelecomEndpoint> = emptyList(),
    val sessionIssue: AudioSessionIssue? = null,
) {
    val selectionConfirmed: Boolean
        get() = sameTelecomEndpoint(currentEndpoint, selectedEndpoint)

    val mediaActive: Boolean
        get() = phase == TelecomCallPhase.ACTIVE && selectionConfirmed && sessionIssue == null

    val endpointKey: String?
        get() = currentEndpoint?.let { "${it.type}:${it.identifier}" }

    val micKind: CaptureInputKind
        get() = when (currentEndpoint?.type) {
            CallEndpointCompat.TYPE_BLUETOOTH -> CaptureInputKind.BLUETOOTH
            CallEndpointCompat.TYPE_WIRED_HEADSET -> CaptureInputKind.WIRED
            else -> CaptureInputKind.BUILTIN
        }

    val outputKind: OutputKind
        get() = when (currentEndpoint?.type) {
            CallEndpointCompat.TYPE_BLUETOOTH -> OutputKind.BLUETOOTH
            CallEndpointCompat.TYPE_WIRED_HEADSET -> OutputKind.WIRED
            else -> OutputKind.SPEAKER
        }
}

enum class ObservedAudioDeviceKind {
    BUILTIN,
    BLUETOOTH,
    USB,
    WIRED,
    UNKNOWN,
}

sealed interface AudioPipelineState {
    data object Closed : AudioPipelineState
    data class Opening(val endpointKey: String) : AudioPipelineState
    data class Ready(
        val endpointKey: String,
        val observedInput: ObservedAudioDeviceKind,
        val observedOutput: ObservedAudioDeviceKind,
    ) : AudioPipelineState
    data class Failed(
        val reason: String,
        val issue: AudioSessionIssue = AudioSessionIssue.AUDIO_PIPELINE_FAILED,
    ) : AudioPipelineState
}

fun isConfirmedDuplexReady(
    call: TelecomCallState,
    pipeline: AudioPipelineState,
): Boolean = call.mediaActive &&
    pipeline is AudioPipelineState.Ready &&
    pipeline.endpointKey == call.endpointKey

fun observedDeviceKind(type: Int?): ObservedAudioDeviceKind = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> ObservedAudioDeviceKind.BLUETOOTH
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> ObservedAudioDeviceKind.USB
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    -> ObservedAudioDeviceKind.WIRED
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    -> ObservedAudioDeviceKind.BUILTIN
    else -> ObservedAudioDeviceKind.UNKNOWN
}

typealias AudioRoute = TelecomCallState
