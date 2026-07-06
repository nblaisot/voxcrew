package com.nblaisot.voxcrew.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomAudioSessionTest {
    @Test
    fun hasHeadsetConnectedDetectsWiredAndBluetoothTypes() {
        assertTrue(
            IntercomAudioSession.hasHeadsetConnected(
                listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            ),
        )
        assertTrue(
            IntercomAudioSession.hasHeadsetConnected(
                listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            ),
        )
        assertFalse(
            IntercomAudioSession.hasHeadsetConnected(
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
    }

    @Test
    fun enterSetsCommunicationModeAndSpeakerphoneWithoutHeadset() {
        val audioManager = FakeIntercomAudioManager()
        val session = IntercomAudioSession(mockContext(), audioManager)

        session.enter()

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        assertTrue(audioManager.isSpeakerphoneOn)
        assertEquals(IntercomAudioSession.RoutingLabel.SPEAKER, session.routingLabel.value)
        assertEquals(1, audioManager.registeredCallbacks.size)
    }

    @Test
    fun enterDisablesSpeakerphoneWhenHeadsetPresent() {
        val audioManager = FakeIntercomAudioManager(
            outputTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        )
        val session = IntercomAudioSession(mockContext(), audioManager)

        session.enter()

        assertFalse(audioManager.isSpeakerphoneOn)
        assertEquals(IntercomAudioSession.RoutingLabel.HEADSET, session.routingLabel.value)
    }

    @Test
    fun exitRestoresPreviousModeAndSpeakerphone() {
        val audioManager = FakeIntercomAudioManager(
            initialMode = AudioManager.MODE_NORMAL,
            initialSpeakerphoneOn = false,
        )
        val session = IntercomAudioSession(mockContext(), audioManager)

        session.enter()
        session.exit()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertFalse(audioManager.isSpeakerphoneOn)
        assertTrue(audioManager.registeredCallbacks.isEmpty())
    }

    @Test
    fun enterIsIdempotent() {
        val audioManager = FakeIntercomAudioManager(initialMode = AudioManager.MODE_NORMAL)
        val session = IntercomAudioSession(mockContext(), audioManager)

        session.enter()
        audioManager.mode = AudioManager.MODE_RINGTONE
        session.enter()

        assertEquals(AudioManager.MODE_RINGTONE, audioManager.mode)
    }
}

private class FakeIntercomAudioManager(
    initialMode: Int = AudioManager.MODE_NORMAL,
    initialSpeakerphoneOn: Boolean = false,
    private val outputTypes: List<Int> = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
) : IntercomAudioManager {
    override var mode: Int = initialMode
    override var isSpeakerphoneOn: Boolean = initialSpeakerphoneOn
    val registeredCallbacks = mutableListOf<AudioDeviceCallback>()

    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
        registeredCallbacks += callback
    }

    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
        registeredCallbacks -= callback
    }

    override fun getDevices(flags: Int): Array<out AudioDeviceInfo> =
        outputTypes.map { type ->
            mockk<AudioDeviceInfo> { every { this@mockk.type } returns type }
        }.toTypedArray()

    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean = true

    override fun clearCommunicationDevice() = Unit
}

private fun mockContext(): Context = mockk(relaxed = true)
