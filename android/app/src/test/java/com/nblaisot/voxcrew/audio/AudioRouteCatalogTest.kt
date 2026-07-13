package com.nblaisot.voxcrew.audio

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteCatalogTest {
    private val speaker = endpoint("speaker", "Phone", CallEndpointCompat.TYPE_SPEAKER)
    private val watch = endpoint("watch", "Galaxy Watch5", CallEndpointCompat.TYPE_BLUETOOTH)
    private val buds = endpoint("buds", "Galaxy Buds", CallEndpointCompat.TYPE_BLUETOOTH)
    private val usb = endpoint("usb", "USB Audio", CallEndpointCompat.TYPE_WIRED_HEADSET)

    @Test
    fun deviceIsAlwaysFirstAndDefault() {
        val choices = buildAudioRouteChoices(listOf(watch, buds, speaker))

        assertEquals(DEVICE_AUDIO_ROUTE_KEY, choices.first().key)
        assertEquals("Cet appareil", choices.first().name)
        assertEquals(CaptureInputKind.BUILTIN, choices.first().inputKind)
        assertEquals(speaker.identifier, choices.first().endpointIdentifier)
    }

    @Test
    fun watchAndBudsRemainSeparateNamedChoices() {
        val choices = buildAudioRouteChoices(listOf(speaker, watch, buds))
        val bluetooth = choices.filter { it.inputKind == CaptureInputKind.BLUETOOTH }

        assertEquals(listOf("Galaxy Buds", "Galaxy Watch5"), bluetooth.map { it.name })
        assertNotEquals(bluetooth[0].key, bluetooth[1].key)
        assertEquals(setOf("buds", "watch"), bluetooth.map { it.endpointIdentifier }.toSet())
    }

    @Test
    fun usbEndpointUsesUsbIconClassificationWithoutChangingItsTelecomIdentity() {
        val choices = buildAudioRouteChoices(
            endpoints = listOf(speaker, usb),
            usbProductNames = setOf("usb audio"),
        )
        val choice = choices.single { it.endpointIdentifier == usb.identifier }

        assertEquals(CaptureInputKind.USB, choice.inputKind)
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
            selectedAudioRouteChoice(choices, DEVICE_AUDIO_ROUTE_KEY).key,
        )
    }

    @Test
    fun removingTheSelectedAccessoryResetsSelectionToDevice() {
        val choicesAfterRemoval = buildAudioRouteChoices(listOf(speaker, watch))

        assertEquals(
            DEVICE_AUDIO_ROUTE_KEY,
            selectedAudioRouteChoice(choicesAfterRemoval, "endpoint:${buds.identifier}").key,
        )
    }

    private fun endpoint(id: String, name: String, type: Int) = TelecomEndpoint(id, name, type)
}
