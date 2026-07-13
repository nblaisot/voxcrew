package com.nblaisot.voxcrew.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteSelectorTest {
    @Test
    fun resolve_builtInOnly_usesDeviceMicSpeakerAndMediaMode() {
        val selection = resolve(
            outputs = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true)),
            inputs = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC, source = true)),
        )

        assertEquals(CaptureInputKind.BUILTIN, selection.route.micKind)
        assertEquals(OutputKind.SPEAKER, selection.route.outputKind)
        assertEquals(AudioAttributes.USAGE_MEDIA, selection.route.playbackUsage)
        assertEquals(AudioManager.MODE_NORMAL, selection.route.audioMode)
        assertTrue(selection.route.routeReady)
        assertNull(AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_bluetoothMic_usesCommunicationRouteAndBluetoothIcon() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true, source = true)
        val selection = resolve(
            outputs = listOf(ble),
            inputs = listOf(ble),
            available = listOf(ble),
            active = ble,
        )

        assertEquals(CaptureInputKind.BLUETOOTH, selection.route.micKind)
        assertEquals(OutputKind.BLUETOOTH, selection.route.outputKind)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, selection.route.playbackUsage)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, selection.route.audioMode)
        assertEquals(ble, selection.communicationDevice)
        assertTrue(selection.route.routeReady)
        assertEquals(CaptureInputKind.BLUETOOTH, AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_bluetoothOutputOnly_usesMediaOutputAndDeviceMic() {
        val a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, sink = true)
        val selection = resolve(outputs = listOf(a2dp), inputs = emptyList())

        assertEquals(CaptureInputKind.BUILTIN, selection.route.micKind)
        assertEquals(OutputKind.BLUETOOTH, selection.route.outputKind)
        assertEquals(AudioAttributes.USAGE_MEDIA, selection.route.playbackUsage)
        assertNull(selection.route.captureDevice)
        assertNull(AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_bluetoothHeadsetSinkWithoutMic_usesMediaOutputAndDeviceMic() {
        val bleOutput = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true)
        val selection = resolve(outputs = listOf(bleOutput), inputs = emptyList())

        assertEquals(CaptureInputKind.BUILTIN, selection.route.micKind)
        assertEquals(OutputKind.BLUETOOTH, selection.route.outputKind)
        assertEquals(AudioAttributes.USAGE_MEDIA, selection.route.playbackUsage)
        assertNull(selection.route.captureDevice)
        assertNull(selection.communicationDevice)
        assertNull(AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_usbHeadsetMic_usesUsbMicAndUsbIcon() {
        val usb = device(AudioDeviceInfo.TYPE_USB_HEADSET, sink = true, source = true)
        val selection = resolve(outputs = listOf(usb), inputs = listOf(usb))

        assertEquals(CaptureInputKind.USB, selection.route.micKind)
        assertEquals(OutputKind.USB, selection.route.outputKind)
        assertEquals(usb, selection.route.captureDevice)
        assertEquals(usb, selection.route.outputDevice)
        assertEquals(CaptureInputKind.USB, AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_usbInputOnly_usesUsbMicAndSpeakerOutput() {
        val usbMic = device(AudioDeviceInfo.TYPE_USB_DEVICE, source = true)
        val selection = resolve(
            outputs = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true)),
            inputs = listOf(usbMic),
        )

        assertEquals(CaptureInputKind.USB, selection.route.micKind)
        assertEquals(OutputKind.SPEAKER, selection.route.outputKind)
        assertEquals(usbMic, selection.route.captureDevice)
        assertNull(selection.route.outputDevice)
    }

    @Test
    fun resolve_usbOutputOnly_usesUsbOutputAndDeviceMic() {
        val usbOut = device(AudioDeviceInfo.TYPE_USB_HEADSET, sink = true)
        val selection = resolve(outputs = listOf(usbOut), inputs = emptyList())

        assertEquals(CaptureInputKind.BUILTIN, selection.route.micKind)
        assertEquals(OutputKind.USB, selection.route.outputKind)
        assertEquals(usbOut, selection.route.outputDevice)
        assertNull(selection.route.captureDevice)
        assertNull(AudioRouteSelector.pttMicIconKind(selection.route))
    }

    @Test
    fun resolve_bluetoothAndUsbMics_prefersBluetooth() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true, source = true)
        val usb = device(AudioDeviceInfo.TYPE_USB_HEADSET, sink = true, source = true)
        val selection = resolve(
            outputs = listOf(usb, ble),
            inputs = listOf(usb, ble),
            available = listOf(ble),
            active = ble,
        )

        assertEquals(CaptureInputKind.BLUETOOTH, selection.route.micKind)
        assertEquals(ble, selection.communicationDevice)
    }

    @Test
    fun pickCommunicationDeviceType_prefersBleOverSco() {
        val available = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioRouteSelector.pickCommunicationDeviceType(available))
    }

    @Test
    fun hasBluetoothMicInput_detectsBleHeadsetSource() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true, source = true)
        val builtin = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, source = true)
        assertTrue(AudioRouteSelector.hasBluetoothMicInput(listOf(ble)))
        assertFalse(AudioRouteSelector.hasBluetoothMicInput(listOf(builtin)))
    }

    @Test
    fun resolve_missingRecordPermission_blocksRoute() {
        val selection = resolve(
            outputs = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true)),
            inputs = emptyList(),
            recordAudioGranted = false,
        )

        assertEquals(AudioPermissionIssue.RECORD_AUDIO, selection.route.permissionIssue)
        assertEquals(false, selection.route.routeReady)
    }

    @Test
    fun communicationRouteReady_acceptsScoConfirmedForBleRequestOnSameAddress() {
        val ble = device(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            address = "78:C1:1D:41:75:1F",
            sink = true,
        )
        val sco = device(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            address = "78:C1:1D:41:75:1F",
            sink = true,
        )
        assertTrue(AudioRouteSelector.communicationRouteReady(sco, ble))
        assertFalse(AudioRouteSelector.communicationRouteReady(ble, device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true)))
    }

    @Test
    fun resolve_bluetoothMic_fallsBackToOutputWhenAvailableCommListEmpty() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true, source = true)
        val selection = resolve(
            outputs = listOf(ble),
            inputs = listOf(ble),
            available = emptyList(),
        )

        assertEquals(CaptureInputKind.BLUETOOTH, selection.route.micKind)
        assertFalse(selection.route.routeReady)
        assertEquals(CaptureInputKind.BLUETOOTH, AudioRouteSelector.pttMicIconKind(selection.route))
        assertNotNull(selection.communicationDevice)
    }

    @Test
    fun resolve_missingBluetoothPermission_blocksBluetoothMicRoute() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET, sink = true, source = true)
        val selection = resolve(
            outputs = listOf(ble),
            inputs = listOf(ble),
            bluetoothConnectGranted = false,
        )

        assertEquals(AudioPermissionIssue.BLUETOOTH_CONNECT, selection.route.permissionIssue)
        assertEquals(false, selection.route.routeReady)
        assertNull(AudioRouteSelector.pttMicIconKind(selection.route))
    }

    private fun resolve(
        outputs: List<AudioDeviceInfo>,
        inputs: List<AudioDeviceInfo>,
        available: List<AudioDeviceInfo> = emptyList(),
        active: AudioDeviceInfo? = null,
        recordAudioGranted: Boolean = true,
        bluetoothConnectGranted: Boolean = true,
    ): AudioRouteSelection = AudioRouteSelector.resolve(
        outputs = outputs,
        inputs = inputs,
        availableCommunicationDevices = available,
        activeCommunicationDevice = active,
        supportsCommunicationDeviceApi = true,
        recordAudioGranted = recordAudioGranted,
        bluetoothConnectGranted = bluetoothConnectGranted,
    )

    private fun device(
        type: Int,
        address: String = "addr-$type",
        sink: Boolean = false,
        source: Boolean = false,
    ): AudioDeviceInfo = mockk {
        every { this@mockk.type } returns type
        every { productName } returns "fake-$type"
        every { this@mockk.address } returns address
        every { isSink } returns sink
        every { isSource } returns source
    }
}
