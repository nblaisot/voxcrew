package com.nblaisot.voxcrew.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomAudioSessionTest {
    private fun testSession(
        audioManager: FakeIntercomAudioManager,
        audioFocus: FakeAudioFocus = FakeAudioFocus(),
        permissions: FakeAudioPermissionChecker = FakeAudioPermissionChecker(),
        supportsCommunicationDeviceApi: Boolean = true,
    ): IntercomAudioSession = IntercomAudioSession(
        mockContext(),
        audioManager,
        audioFocus,
        supportsCommunicationDeviceApi = supportsCommunicationDeviceApi,
        permissionChecker = permissions,
        routingDispatcher = InlineRoutingDispatcher(),
    )

    private fun IntercomAudioSession.awaitRouting() {
        awaitRoutingApplied()
        awaitRouteReady()
    }

    @Test
    fun enterBuiltInOnlyUsesNormalMediaRoute() {
        val audioManager = FakeIntercomAudioManager()
        val audioFocus = FakeAudioFocus()
        val session = testSession(audioManager, audioFocus)

        session.enter()
        session.awaitRouting()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertFalse(audioManager.isSpeakerphoneOn)
        assertEquals(CaptureInputKind.BUILTIN, session.audioRoute.value.micKind)
        assertEquals(OutputKind.SPEAKER, session.outputKind.value)
        assertEquals(AudioAttributes.USAGE_MEDIA, session.audioRoute.value.playbackUsage)
        assertTrue(session.routeReady.value)
        assertEquals(0, audioFocus.requestCount)
        assertEquals(1, audioManager.registeredCallbacks.size)
    }

    @Test
    fun enterBluetoothMicUsesCommunicationDeviceAndFocus() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        val audioFocus = FakeAudioFocus()
        val session = testSession(audioManager, audioFocus)

        session.enter()
        session.awaitRouting()

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, audioManager.lastCommunicationDeviceType)
        assertEquals(CaptureInputKind.BLUETOOTH, session.captureInputKind.value)
        assertEquals(OutputKind.BLUETOOTH, session.outputKind.value)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, session.audioRoute.value.playbackUsage)
        assertTrue(session.routeReady.value)
        assertEquals(1, audioFocus.requestCount)
    }

    @Test
    fun preferredCaptureDevice_returnsBluetoothInputWhenPresent() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            communicationAddress = "aa:bb:cc",
        )
        val session = testSession(audioManager)

        session.enter()
        session.awaitRouting()

        assertEquals(CaptureInputKind.BLUETOOTH, session.captureInputKind.value)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, session.preferredCaptureDevice()?.type)
    }

    @Test
    fun enterUsbMicUsesNormalMediaRouteWithoutCommunicationDevice() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_USB_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_USB_HEADSET),
        )
        val session = testSession(audioManager)

        session.enter()
        session.awaitRouting()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertEquals(0, audioManager.communicationDeviceSetCount)
        assertEquals(CaptureInputKind.USB, session.captureInputKind.value)
        assertEquals(OutputKind.USB, session.outputKind.value)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, session.preferredCaptureDevice()?.type)
    }

    @Test
    fun bluetoothConfirmationDelayKeepsRouteNotReady() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            deferCommDeviceConfirmation = true,
        )
        val session = testSession(audioManager)

        session.enter()
        session.awaitRoutingApplied()

        assertFalse(session.routeReady.value)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, session.preferredCaptureDevice()?.type)
        assertTrue(session.awaitRouteReady())
    }

    @Test
    fun deviceCallbackReappliesRouteWhenBluetoothConnects() {
        val audioManager = FakeIntercomAudioManager()
        val session = testSession(audioManager)
        session.enter()
        session.awaitRouting()
        assertEquals(CaptureInputKind.BUILTIN, session.captureInputKind.value)

        audioManager.outputTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        audioManager.inputTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        audioManager.availableTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        audioManager.registeredCallbacks.first().onAudioDevicesAdded(emptyArray())

        session.awaitRouting()

        assertEquals(CaptureInputKind.BLUETOOTH, session.captureInputKind.value)
        assertEquals(OutputKind.BLUETOOTH, session.outputKind.value)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, audioManager.lastCommunicationDeviceType)
    }

    @Test
    fun deviceCallbackFallsBackWhenBluetoothDisconnects() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        val session = testSession(audioManager)
        session.enter()
        session.awaitRouting()
        assertEquals(CaptureInputKind.BLUETOOTH, session.captureInputKind.value)

        audioManager.outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        audioManager.inputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        audioManager.availableTypes = emptyList()
        audioManager.registeredCallbacks.first().onAudioDevicesRemoved(emptyArray())

        session.awaitRouting()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertEquals(CaptureInputKind.BUILTIN, session.captureInputKind.value)
        assertEquals(OutputKind.SPEAKER, session.outputKind.value)
        assertTrue(session.routeReady.value)
    }

    @Test
    fun setCommunicationDeviceFalseWaitsWhileBluetoothMicPresent() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            setCommunicationDeviceResult = false,
        )
        val session = testSession(audioManager)

        session.enter()
        session.awaitRoutingApplied()

        assertEquals(CaptureInputKind.BLUETOOTH, session.captureInputKind.value)
        assertFalse(session.routeReady.value)
        assertTrue(audioManager.communicationDeviceSetCount >= 1)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
    }

    @Test
    fun missingRecordAudioPermissionPublishesPermissionIssue() {
        val session = testSession(
            FakeIntercomAudioManager(),
            permissions = FakeAudioPermissionChecker(recordAudioGranted = false),
        )

        session.enter()
        session.awaitRoutingApplied()

        assertEquals(AudioPermissionIssue.RECORD_AUDIO, session.permissionIssue.value)
        assertFalse(session.routeReady.value)
        assertFalse(session.awaitRouteReady())
    }

    @Test
    fun missingBluetoothPermissionPublishesPermissionIssueForBluetoothRoute() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = emptyList(),
        )
        val session = testSession(
            audioManager,
            permissions = FakeAudioPermissionChecker(bluetoothConnectGranted = false),
        )

        session.enter()
        session.awaitRoutingApplied()

        assertEquals(AudioPermissionIssue.BLUETOOTH_CONNECT, session.permissionIssue.value)
        assertFalse(session.routeReady.value)
        assertFalse(session.awaitRouteReady())
    }

    @Test
    fun securityExceptionOnCommunicationDevicePublishesBluetoothPermissionIssue() {
        val audioManager = FakeIntercomAudioManager(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            throwSecurityOnSet = true,
        )
        val session = testSession(audioManager)

        session.enter()
        session.awaitRoutingApplied()

        assertEquals(AudioPermissionIssue.BLUETOOTH_CONNECT, session.permissionIssue.value)
        assertFalse(session.routeReady.value)
        assertFalse(session.awaitRouteReady())
    }

    @Test
    fun exitRestoresPreviousModeAndClearsCommunicationDevice() {
        val audioManager = FakeIntercomAudioManager(
            initialMode = AudioManager.MODE_RINGTONE,
            initialSpeakerphoneOn = true,
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            availableDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        val audioFocus = FakeAudioFocus()
        val session = testSession(audioManager, audioFocus)

        session.enter()
        session.awaitRouting()
        session.exit()

        assertEquals(AudioManager.MODE_RINGTONE, audioManager.mode)
        assertTrue(audioManager.isSpeakerphoneOn)
        assertTrue(audioManager.registeredCallbacks.isEmpty())
        assertTrue(audioFocus.abandonCount > 0)
        assertTrue(audioManager.communicationDeviceClearedCount > 0)
        assertFalse(session.routeReady.value)
    }
}

private class FakeAudioFocus : IntercomAudioFocus {
    var requestCount = 0
    var abandonCount = 0
    private var granted = false

    override fun request(): Boolean {
        requestCount++
        granted = true
        return true
    }

    override fun abandon() {
        abandonCount++
        granted = false
    }

    override fun isGranted(): Boolean = granted
}

private class FakeAudioPermissionChecker(
    private val recordAudioGranted: Boolean = true,
    private val bluetoothConnectGranted: Boolean = true,
) : AudioPermissionChecker {
    override fun hasRecordAudioPermission(): Boolean = recordAudioGranted
    override fun hasBluetoothConnectPermission(): Boolean = bluetoothConnectGranted
}

private class FakeIntercomAudioManager(
    initialMode: Int = AudioManager.MODE_NORMAL,
    initialSpeakerphoneOn: Boolean = false,
    outputDeviceTypes: List<Int> = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
    inputDeviceTypes: List<Int> = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
    availableDeviceTypes: List<Int> = emptyList(),
    private val communicationAddress: String = "fake-addr",
    private val deferCommDeviceConfirmation: Boolean = false,
    private val setCommunicationDeviceResult: Boolean = true,
    private val throwSecurityOnSet: Boolean = false,
) : IntercomAudioManager {
    override var mode: Int = initialMode
    override var isSpeakerphoneOn: Boolean = initialSpeakerphoneOn
    val registeredCallbacks = mutableListOf<AudioDeviceCallback>()
    var outputTypes: List<Int> = outputDeviceTypes
    var inputTypes: List<Int> = inputDeviceTypes
    var availableTypes: List<Int> = availableDeviceTypes
    var communicationDeviceSetCount = 0
    var communicationDeviceClearedCount = 0
    var scoStartCount = 0
    var lastCommunicationDeviceType: Int? = null
    private var commDeviceChangedListener: (() -> Unit)? = null

    fun device(type: Int, deviceAddress: String = communicationAddress): AudioDeviceInfo =
        mockk {
            every { this@mockk.type } returns type
            every { productName } returns "fake-$type"
            every { this@mockk.address } returns deviceAddress
            val inInputs = type in inputTypes
            val inOutputs = type in outputTypes
            val inAvailable = type in availableTypes
            every { isSource } returns (inInputs || inAvailable)
            every { isSink } returns (inOutputs || inAvailable)
        }

    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
        registeredCallbacks += callback
    }

    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
        registeredCallbacks -= callback
    }

    override fun getDevices(flags: Int): Array<out AudioDeviceInfo> {
        val types = when (flags) {
            AudioManager.GET_DEVICES_INPUTS -> inputTypes
            else -> outputTypes
        }
        return types.map { type -> device(type) }.toTypedArray()
    }

    override fun availableCommunicationDevices(): List<AudioDeviceInfo> =
        availableTypes.map { type -> device(type) }

    override fun communicationDevice(): AudioDeviceInfo? =
        lastCommunicationDeviceType?.let { type -> device(type) }

    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        communicationDeviceSetCount++
        if (throwSecurityOnSet) throw SecurityException("missing bluetooth permission")
        if (!setCommunicationDeviceResult) return false
        if (!deferCommDeviceConfirmation) {
            lastCommunicationDeviceType = device.type
            commDeviceChangedListener?.invoke()
        }
        return true
    }

    override fun clearCommunicationDevice() {
        communicationDeviceClearedCount++
        lastCommunicationDeviceType = null
        if (!deferCommDeviceConfirmation) {
            commDeviceChangedListener?.invoke()
        }
    }

    override fun startBluetoothSco(): Boolean {
        scoStartCount++
        return true
    }

    override fun stopBluetoothSco() = Unit

    override fun setOnCommunicationDeviceChangedListener(listener: (() -> Unit)?) {
        commDeviceChangedListener = listener
    }
}

private fun mockContext(): Context = mockk(relaxed = true)
