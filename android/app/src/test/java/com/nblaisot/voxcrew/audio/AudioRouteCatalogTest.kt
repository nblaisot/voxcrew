package com.nblaisot.voxcrew.audio

import androidx.core.telecom.CallEndpointCompat
import com.nblaisot.voxcrew.demo.DemoFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteCatalogTest {
    private val speaker = endpoint("speaker", "Phone", CallEndpointCompat.TYPE_SPEAKER)
    private val watch = endpoint(
        "watch-uuid",
        "Galaxy Watch5",
        CallEndpointCompat.TYPE_BLUETOOTH,
        bluetoothAddress = "AA:BB:CC:DD:EE:01",
    )
    private val buds = endpoint(
        "buds-uuid",
        "Galaxy Buds",
        CallEndpointCompat.TYPE_BLUETOOTH,
        bluetoothAddress = "AA:BB:CC:DD:EE:02",
    )
    private val usb = endpoint("usb", "USB Audio", CallEndpointCompat.TYPE_WIRED_HEADSET)

    @Test
    fun deviceIsAlwaysFirstAndDefault() {
        val choices = buildAudioRouteChoices(listOf(watch, buds, speaker))

        assertEquals(DEVICE_AUDIO_ROUTE_KEY, choices.first().key)
        assertEquals("This device", choices.first().name)
        assertEquals(CaptureInputKind.BUILTIN, choices.first().inputKind)
        assertEquals(AudioRouteTarget.DEVICE, choices.first().target)
        assertEquals(speaker.identifier, choices.first().endpointIdentifier)
    }

    @Test
    fun watchAndBudsRemainSeparateNamedChoices() {
        val choices = buildAudioRouteChoices(listOf(speaker, watch, buds))
        val bluetooth = choices.filter { it.inputKind == CaptureInputKind.BLUETOOTH }

        assertEquals(listOf("Galaxy Buds", "Galaxy Watch5"), bluetooth.map { it.name })
        assertTrue(bluetooth.all { it.target == AudioRouteTarget.BLUETOOTH })
        assertNotEquals(bluetooth[0].key, bluetooth[1].key)
        assertEquals(
            setOf(bluetoothAudioRouteKey("AA:BB:CC:DD:EE:02"), bluetoothAudioRouteKey("AA:BB:CC:DD:EE:01")),
            bluetooth.map { it.key }.toSet(),
        )
        assertEquals(setOf("buds-uuid", "watch-uuid"), bluetooth.map { it.endpointIdentifier }.toSet())
    }

    @Test
    fun sameMacTwoNamesCollapsesToOnePreferredRow() {
        val oldName = endpoint(
            "uuid-old",
            "Buds (old)",
            CallEndpointCompat.TYPE_BLUETOOTH,
            bluetoothAddress = "AA:BB:CC:DD:EE:02",
        )
        val newName = endpoint(
            "uuid-new",
            "Nicolas' Buds",
            CallEndpointCompat.TYPE_BLUETOOTH,
            bluetoothAddress = "AA:BB:CC:DD:EE:02",
        )
        val choices = buildAudioRouteChoices(
            endpoints = listOf(speaker, oldName, newName),
            preferredBluetoothNames = setOf("nicolas' buds"),
        )
        val bluetooth = choices.filter { it.inputKind == CaptureInputKind.BLUETOOTH }

        assertEquals(1, bluetooth.size)
        assertEquals("Nicolas' Buds", bluetooth.single().name)
        assertEquals(bluetoothAudioRouteKey("AA:BB:CC:DD:EE:02"), bluetooth.single().key)
        assertEquals("uuid-new", bluetooth.single().endpointIdentifier)
    }

    @Test
    fun renameRemapsSelectionByMac() {
        val oldChoice = buildAudioRouteChoices(listOf(speaker, buds))
            .single { it.bluetoothAddress == "AA:BB:CC:DD:EE:02" }
        val renamed = endpoint(
            "buds-renamed-uuid",
            "New Buds Name",
            CallEndpointCompat.TYPE_BLUETOOTH,
            bluetoothAddress = "AA:BB:CC:DD:EE:02",
        )
        val afterRename = buildAudioRouteChoices(listOf(speaker, renamed))
        val rematched = selectedAudioRouteChoice(afterRename, oldChoice)

        assertEquals(bluetoothAudioRouteKey("AA:BB:CC:DD:EE:02"), rematched.key)
        assertEquals("New Buds Name", rematched.name)
        assertEquals("buds-renamed-uuid", rematched.endpointIdentifier)
    }

    @Test
    fun usbEndpointUsesUsbIconClassificationWithoutChangingItsTelecomIdentity() {
        val choices = buildAudioRouteChoices(
            endpoints = listOf(speaker, usb),
            usbProductNames = setOf("usb audio"),
        )
        val choice = choices.single { it.endpointIdentifier == usb.identifier }

        assertEquals(CaptureInputKind.USB, choice.inputKind)
        assertEquals(AudioRouteTarget.WIRED_USB, choice.target)
        assertEquals(CallEndpointCompat.TYPE_WIRED_HEADSET, choice.endpointType)
    }

    @Test
    fun nonAccessoryEndpointsAreNotExposedAsExtraChoices() {
        val earpiece = endpoint("earpiece", "Earpiece", CallEndpointCompat.TYPE_EARPIECE)
        val choices = buildAudioRouteChoices(listOf(earpiece, speaker))

        assertEquals(1, choices.size)
        assertTrue(choices.none { it.endpointType == CallEndpointCompat.TYPE_EARPIECE })
    }

    @Test
    fun connectingAnAccessoryDoesNotReplaceTheDefaultDeviceSelection() {
        val choices = buildAudioRouteChoices(listOf(speaker, buds))

        assertEquals(
            DEVICE_AUDIO_ROUTE_KEY,
            selectedAudioRouteChoice(choices, deviceAudioRouteChoice()).key,
        )
    }

    @Test
    fun removingTheSelectedAccessoryKeepsTheManualChoiceUnavailable() {
        val selectedBuds = buildAudioRouteChoices(listOf(speaker, buds))
            .single { it.endpointIdentifier == buds.identifier }
        val choicesAfterRemoval = buildAudioRouteChoices(listOf(speaker, watch))

        assertEquals(
            bluetoothAudioRouteKey("AA:BB:CC:DD:EE:02"),
            selectedAudioRouteChoice(choicesAfterRemoval, selectedBuds).key,
        )
    }

    @Test
    fun demoBluetoothFixturesAppearAsNamedChoices() {
        val choices = buildAudioRouteChoices(listOf(speaker) + DemoFixtures.bluetoothEndpoints())
        val bluetooth = choices.filter { it.inputKind == CaptureInputKind.BLUETOOTH }

        assertEquals(
            listOf("Galaxy Watch 8", "Nicolas' earbuds"),
            bluetooth.map { it.name },
        )
        assertTrue(bluetooth.all { DemoFixtures.isDemoAudioRouteKey(it.key) })
    }

    private fun endpoint(
        id: String,
        name: String,
        type: Int,
        bluetoothAddress: String? = null,
    ) = TelecomEndpoint(id, name, type, bluetoothAddress = bluetoothAddress)
}
