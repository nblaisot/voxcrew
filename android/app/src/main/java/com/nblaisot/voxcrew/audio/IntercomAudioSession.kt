package com.nblaisot.voxcrew.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IntercomAudioManager {
    var mode: Int
    var isSpeakerphoneOn: Boolean
    fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?)
    fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback)
    fun getDevices(flags: Int): Array<out AudioDeviceInfo>
    fun availableCommunicationDevices(): List<AudioDeviceInfo>
    fun communicationDevice(): AudioDeviceInfo?
    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean
    fun clearCommunicationDevice()
    fun startBluetoothSco(): Boolean
    fun stopBluetoothSco()
    fun setOnCommunicationDeviceChangedListener(listener: (() -> Unit)?)
}

interface AudioPermissionChecker {
    fun hasRecordAudioPermission(): Boolean
    fun hasBluetoothConnectPermission(): Boolean
}

private class AndroidAudioPermissionChecker(
    private val context: Context,
) : AudioPermissionChecker {
    override fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

class IntercomAudioSession(
    context: Context,
    private val audioManager: IntercomAudioManager = AndroidIntercomAudioManager(
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    ),
    private val audioFocus: IntercomAudioFocus = VoiceCommunicationAudioFocus(context),
    private val supportsCommunicationDeviceApi: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    private val permissionChecker: AudioPermissionChecker = AndroidAudioPermissionChecker(context.applicationContext),
    routingDispatcher: RoutingDispatcher? = null,
) {
    private val routingThread: HandlerThread? = if (routingDispatcher == null) {
        HandlerThread("IntercomAudioRouting").apply { start() }
    } else {
        null
    }
    private val routing: RoutingDispatcher = routingDispatcher
        ?: HandlerRoutingDispatcher(checkNotNull(routingThread))
    private val router = AndroidAudioRouter(
        audioManager = audioManager,
        audioFocus = audioFocus,
        supportsCommunicationDeviceApi = supportsCommunicationDeviceApi,
    )

    private var savedMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var active = false
    private val routeReadyAwaiters = CopyOnWriteArrayList<CountDownLatch>()

    private val _audioRoute = MutableStateFlow(AudioRouteState.builtIn(routeReady = false))
    val audioRoute: StateFlow<AudioRouteState> = _audioRoute.asStateFlow()

    private val _routeReady = MutableStateFlow(false)
    val routeReady: StateFlow<Boolean> = _routeReady.asStateFlow()

    private val _captureInputKind = MutableStateFlow(CaptureInputKind.BUILTIN)
    val captureInputKind: StateFlow<CaptureInputKind> = _captureInputKind.asStateFlow()

    private val _outputKind = MutableStateFlow(OutputKind.SPEAKER)
    val outputKind: StateFlow<OutputKind> = _outputKind.asStateFlow()

    private val _permissionIssue = MutableStateFlow<AudioPermissionIssue?>(null)
    val permissionIssue: StateFlow<AudioPermissionIssue?> = _permissionIssue.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            postOnRoutingThread { applyRoutingInternal() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            postOnRoutingThread { applyRoutingInternal() }
        }
    }

    private val commDeviceChangedListener: () -> Unit = {
        postOnRoutingThread {
            if (router.deferringCommDeviceCallbacks()) return@postOnRoutingThread
            applyRoutingInternal()
        }
    }

    val isActive: Boolean get() = active

    fun enter() {
        if (active) return
        active = true
        savedMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.registerAudioDeviceCallback(deviceCallback, routing.callbackHandler)
        if (supportsCommunicationDeviceApi) {
            audioManager.setOnCommunicationDeviceChangedListener(commDeviceChangedListener)
        }
        postOnRoutingThread {
            applyRoutingInternal()
            logInfo(TAG, "entered audio routing route=${_audioRoute.value}")
        }
    }

    fun retryAudioFocus(): Boolean {
        if (!active) return false
        return if (_audioRoute.value.audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            audioFocus.request()
        } else {
            true
        }
    }

    fun isAudioFocusGranted(): Boolean =
        _audioRoute.value.audioMode != AudioManager.MODE_IN_COMMUNICATION || audioFocus.isGranted()

    fun exit() {
        if (!active) return
        active = false
        runOnRoutingThread {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            if (supportsCommunicationDeviceApi) {
                audioManager.setOnCommunicationDeviceChangedListener(null)
            }
            router.release()
            publishRoute(AudioRouteState.builtIn(routeReady = false))
            releaseRouteReadyAwaiters()
            logInfo(TAG, "routing thread cleaned up")
        }
        savedSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
        savedMode?.let { audioManager.mode = it }
        savedMode = null
        savedSpeakerphoneOn = null
        logInfo(TAG, "restored previous audio mode")
    }

    fun awaitRoutingApplied() {
        if (!active) return
        runOnRoutingThread { Unit }
    }

    fun awaitRouteReady(): Boolean {
        if (!active) return false
        if (_audioRoute.value.permissionIssue != null) return false
        return true
    }

    fun currentRoute(): AudioRouteState = _audioRoute.value

    fun preferredCaptureDevice(): AudioDeviceInfo? {
        if (!active) return null
        val route = runOnRoutingThread { _audioRoute.value }
        val capture = route.captureDevice?.takeIf { it.isSource } ?: return null
        return when (route.micKind) {
            CaptureInputKind.BLUETOOTH ->
                capture.takeIf { AudioRouteSelector.isBluetoothCaptureDevice(it) }
            CaptureInputKind.USB -> capture
            CaptureInputKind.BUILTIN -> null
        }
    }

    fun onRecordAudioPermissionMissing() {
        postOnRoutingThread {
            publishRoute(
                AudioRouteState.builtIn(
                    routeReady = false,
                    permissionIssue = AudioPermissionIssue.RECORD_AUDIO,
                ),
            )
        }
    }

    fun onCaptureRouteObserved(routedDevice: AudioDeviceInfo?) {
        if (routedDevice == null) return
        val current = _audioRoute.value
        if (current.micKind == CaptureInputKind.BLUETOOTH &&
            current.audioMode == AudioManager.MODE_IN_COMMUNICATION &&
            !AudioRouteSelector.isBluetoothCaptureType(routedDevice.type)
        ) {
            logWarn(
                TAG,
                "capture not on Bluetooth device while BT comm route active routedType=${routedDevice.type}",
            )
        }
    }

    fun refreshRouting() {
        if (!active) return
        postOnRoutingThread { applyRoutingInternal() }
    }

    fun reapplyRouting() {
        if (!active) return
        postOnRoutingThread { applyRoutingInternal() }
    }

    private fun postOnRoutingThread(block: () -> Unit) {
        routing.post(Runnable(block))
    }

    private fun <T> runOnRoutingThread(block: () -> T): T {
        if (routing.isOnRoutingThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        routing.post(
            Runnable {
                try {
                    result = block()
                } catch (t: Throwable) {
                    error = t
                } finally {
                    latch.countDown()
                }
            },
        )
        latch.await(ROUTING_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun applyRoutingInternal() {
        if (!active) return
        val route = router.applyRouting(permissionChecker = permissionChecker)
        publishRoute(route)
    }

    private fun publishRoute(route: AudioRouteState) {
        _audioRoute.value = route
        _captureInputKind.value = route.micKind
        _outputKind.value = route.outputKind
        _permissionIssue.value = route.permissionIssue
        setRouteReady(route.routeReady)
        if (!route.routeReady && route.permissionIssue != null) {
            releaseRouteReadyAwaiters()
        }
        logInfo(
            TAG,
            "routing mode=${route.audioMode} usage=${route.playbackUsage} ready=${route.routeReady} " +
                "output=${route.outputDevice?.type} mic=${route.micKind} capture=${route.captureDevice?.type} " +
                "permission=${route.permissionIssue}",
        )
    }

    private fun setRouteReady(ready: Boolean) {
        if (_routeReady.value == ready) return
        _routeReady.value = ready
        if (ready) {
            releaseRouteReadyAwaiters()
        }
    }

    private fun releaseRouteReadyAwaiters() {
        routeReadyAwaiters.forEach { it.countDown() }
        routeReadyAwaiters.clear()
    }

    companion object {
        private const val TAG = "IntercomAudioSession"
        private const val ROUTING_OPERATION_TIMEOUT_MS = 3_000L
        private const val ROUTING_READY_TIMEOUT_MS = 30_000L

        @Deprecated("Use AudioRouteSelector.isHeadsetPresent", ReplaceWith("AudioRouteSelector.isHeadsetPresent(devices)"))
        fun hasHeadsetConnected(devices: List<Int>): Boolean = AudioRouteSelector.isHeadsetPresent(devices)
    }
}

private class AndroidAudioRouter(
    private val audioManager: IntercomAudioManager,
    private val audioFocus: IntercomAudioFocus,
    private val supportsCommunicationDeviceApi: Boolean,
) {
    private var scoStarted = false
    private var suppressCommDeviceCallbacks = false

    fun deferringCommDeviceCallbacks(): Boolean = suppressCommDeviceCallbacks

    private fun clearCommunicationDeviceQuietly() {
        suppressCommDeviceCallbacks = true
        try {
            runCatching { audioManager.clearCommunicationDevice() }
        } finally {
            suppressCommDeviceCallbacks = false
        }
    }

    fun applyRouting(
        permissionChecker: AudioPermissionChecker,
    ): AudioRouteState {
        val recordAudioGranted = permissionChecker.hasRecordAudioPermission()
        val bluetoothConnectGranted = permissionChecker.hasBluetoothConnectPermission()
        val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = getDevices(AudioManager.GET_DEVICES_INPUTS)
        val availableCommunicationDevices = if (supportsCommunicationDeviceApi && bluetoothConnectGranted) {
            runCatching { audioManager.availableCommunicationDevices() }
                .getOrElse { error ->
                    if (error is SecurityException) {
                        return permissionRoute(AudioPermissionIssue.BLUETOOTH_CONNECT)
                    }
                    logWarn(TAG, "availableCommunicationDevices failed: ${error.message}")
                    emptyList()
                }
        } else {
            emptyList()
        }
        val activeCommunicationDevice = if (supportsCommunicationDeviceApi && bluetoothConnectGranted) {
            runCatching { audioManager.communicationDevice() }
                .getOrElse { error ->
                    if (error is SecurityException) {
                        return permissionRoute(AudioPermissionIssue.BLUETOOTH_CONNECT)
                    }
                    logWarn(TAG, "communicationDevice failed: ${error.message}")
                    null
                }
        } else {
            null
        }

        val selection = AudioRouteSelector.resolve(
            outputs = outputs,
            inputs = inputs,
            availableCommunicationDevices = availableCommunicationDevices,
            activeCommunicationDevice = activeCommunicationDevice,
            supportsCommunicationDeviceApi = supportsCommunicationDeviceApi,
            recordAudioGranted = recordAudioGranted,
            bluetoothConnectGranted = bluetoothConnectGranted,
        )
        return applySelection(
            selection = selection,
            outputs = outputs,
            inputs = inputs,
            availableCommunicationDevices = availableCommunicationDevices,
            activeCommunicationDevice = activeCommunicationDevice,
            recordAudioGranted = recordAudioGranted,
            bluetoothConnectGranted = bluetoothConnectGranted,
        )
    }

    fun release() {
        if (supportsCommunicationDeviceApi) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        stopSco()
        audioFocus.abandon()
    }

    private fun hasBluetoothMicPresent(
        inputs: List<AudioDeviceInfo>,
        route: AudioRouteState,
    ): Boolean =
        AudioRouteSelector.hasBluetoothMicInput(inputs) ||
            AudioRouteSelector.isBluetoothCaptureDevice(route.captureDevice)

    private fun applySelection(
        selection: AudioRouteSelection,
        outputs: List<AudioDeviceInfo>,
        inputs: List<AudioDeviceInfo>,
        availableCommunicationDevices: List<AudioDeviceInfo>,
        activeCommunicationDevice: AudioDeviceInfo?,
        recordAudioGranted: Boolean,
        bluetoothConnectGranted: Boolean,
    ): AudioRouteState {
        val route = selection.route
        if (route.permissionIssue != null) {
            applyMediaMode()
            return route
        }

        if (route.audioMode != AudioManager.MODE_IN_COMMUNICATION) {
            applyMediaMode()
            return route.copy(routeReady = true)
        }

        val communicationDevice = selection.communicationDevice
        if (communicationDevice == null || !communicationDevice.isSink) {
            if (hasBluetoothMicPresent(inputs, route)) {
                logWarn(TAG, "Bluetooth mic route had no communication sink; waiting")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
                ensureAudioFocus()
                return route.copy(routeReady = false)
            }
            logWarn(TAG, "Bluetooth mic route had no communication sink; falling back")
            return applyFallbackWithoutBluetoothMic(
                outputs,
                inputs,
                availableCommunicationDevices,
                activeCommunicationDevice,
                recordAudioGranted,
                bluetoothConnectGranted,
            )
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        ensureAudioFocus()

        if (supportsCommunicationDeviceApi) {
            if (AudioRouteSelector.communicationRouteReady(activeCommunicationDevice, communicationDevice)) {
                stopSco()
                return route.copy(routeReady = true)
            }
            val accepted = runCatching { audioManager.setCommunicationDevice(communicationDevice) }
                .getOrElse { error ->
                    if (error is SecurityException) {
                        return permissionRoute(AudioPermissionIssue.BLUETOOTH_CONNECT)
                    }
                    logWarn(TAG, "setCommunicationDevice failed: ${error.message}")
                    false
                }
            if (!accepted) {
                logWarn(TAG, "setCommunicationDevice returned false; clearing and retrying once")
                clearCommunicationDeviceQuietly()
                val retryAccepted = runCatching { audioManager.setCommunicationDevice(communicationDevice) }
                    .getOrDefault(false)
                if (retryAccepted) {
                    val confirmedDevice = runCatching { audioManager.communicationDevice() }.getOrNull()
                    return route.copy(
                        routeReady = AudioRouteSelector.communicationRouteReady(
                            confirmedDevice,
                            communicationDevice,
                        ),
                    )
                }
                if (hasBluetoothMicPresent(inputs, route)) {
                    logWarn(TAG, "setCommunicationDevice failed; BT mic still present — waiting")
                    return route.copy(routeReady = false)
                }
                logWarn(TAG, "setCommunicationDevice retry failed; falling back without Bluetooth mic")
                return applyFallbackWithoutBluetoothMic(
                    outputs,
                    inputs,
                    availableCommunicationDevices,
                    activeCommunicationDevice,
                    recordAudioGranted,
                    bluetoothConnectGranted,
                )
            }
            val confirmedDevice = runCatching { audioManager.communicationDevice() }.getOrNull()
            val routeReady = AudioRouteSelector.communicationRouteReady(confirmedDevice, communicationDevice)
            if (!routeReady) {
                logWarn(
                    TAG,
                    "setCommunicationDevice accepted but route not confirmed " +
                        "requestedType=${communicationDevice.type} confirmedType=${confirmedDevice?.type}",
                )
            }
            return route.copy(routeReady = routeReady)
        }

        val scoOk = if (selection.needsLegacyBluetoothSco) startSco() else true
        return if (scoOk) {
            route.copy(routeReady = true)
        } else {
            if (hasBluetoothMicPresent(inputs, route)) {
                logWarn(TAG, "startBluetoothSco failed; BT mic still present — waiting")
                return route.copy(routeReady = false)
            }
            logWarn(TAG, "startBluetoothSco failed; falling back")
            applyFallbackWithoutBluetoothMic(
                outputs,
                inputs,
                availableCommunicationDevices,
                activeCommunicationDevice,
                recordAudioGranted,
                bluetoothConnectGranted,
            )
        }
    }

    private fun applyFallbackWithoutBluetoothMic(
        outputs: List<AudioDeviceInfo>,
        inputs: List<AudioDeviceInfo>,
        availableCommunicationDevices: List<AudioDeviceInfo>,
        activeCommunicationDevice: AudioDeviceInfo?,
        recordAudioGranted: Boolean,
        bluetoothConnectGranted: Boolean,
    ): AudioRouteState {
        val fallback = AudioRouteSelector.resolve(
            outputs = outputs,
            inputs = inputs,
            availableCommunicationDevices = availableCommunicationDevices,
            activeCommunicationDevice = activeCommunicationDevice,
            supportsCommunicationDeviceApi = supportsCommunicationDeviceApi,
            recordAudioGranted = recordAudioGranted,
            bluetoothConnectGranted = bluetoothConnectGranted,
            ignoreBluetoothMicrophones = true,
        ).route
        applyMediaMode()
        return fallback.copy(routeReady = fallback.permissionIssue == null)
    }

    private fun applyMediaMode() {
        if (supportsCommunicationDeviceApi && hasCommunicationDevice()) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        stopSco()
        audioFocus.abandon()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun ensureAudioFocus() {
        if (!audioFocus.isGranted()) {
            audioFocus.request()
        }
    }

    private fun hasCommunicationDevice(): Boolean =
        runCatching { audioManager.communicationDevice() != null }.getOrDefault(false)

    private fun getDevices(flags: Int): List<AudioDeviceInfo> =
        runCatching { audioManager.getDevices(flags).toList() }
            .getOrElse { error ->
                logWarn(TAG, "getDevices($flags) failed: ${error.message}")
                emptyList()
            }

    private fun permissionRoute(issue: AudioPermissionIssue): AudioRouteState {
        applyMediaMode()
        return AudioRouteState.builtIn(routeReady = false, permissionIssue = issue)
    }

    private fun startSco(): Boolean {
        if (scoStarted) return true
        return audioManager.startBluetoothSco().also { scoStarted = it }
    }

    private fun stopSco() {
        if (!scoStarted) return
        audioManager.stopBluetoothSco()
        scoStarted = false
    }

    companion object {
        private const val TAG = "AndroidAudioRouter"
    }
}

interface RoutingDispatcher {
    val callbackHandler: Handler?
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
    fun isOnRoutingThread(): Boolean
}

internal class InlineRoutingDispatcher : RoutingDispatcher {
    override val callbackHandler: Handler? = null

    override fun post(runnable: Runnable) {
        runnable.run()
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        runnable.run()
    }

    override fun removeCallbacks(runnable: Runnable) = Unit

    override fun isOnRoutingThread(): Boolean = true
}

private class HandlerRoutingDispatcher(
    routingThread: HandlerThread,
) : RoutingDispatcher {
    private val handler = Handler(routingThread.looper)

    override val callbackHandler: Handler = handler

    override fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun isOnRoutingThread(): Boolean = Looper.myLooper() == handler.looper
}

private class AndroidIntercomAudioManager(
    private val audioManager: AudioManager,
) : IntercomAudioManager {
    private var platformCommDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    override var mode: Int
        get() = audioManager.mode
        set(value) {
            audioManager.mode = value
        }

    override var isSpeakerphoneOn: Boolean
        @Suppress("DEPRECATION")
        get() = audioManager.isSpeakerphoneOn
        @Suppress("DEPRECATION")
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

    override fun availableCommunicationDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return audioManager.availableCommunicationDevices
    }

    override fun communicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.communicationDevice
    }

    override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return audioManager.setCommunicationDevice(device)
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    override fun startBluetoothSco(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        true
    }.getOrDefault(false)

    override fun stopBluetoothSco() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    override fun setOnCommunicationDeviceChangedListener(listener: (() -> Unit)?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        platformCommDeviceListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        platformCommDeviceListener = null
        if (listener == null) return
        val platformListener = AudioManager.OnCommunicationDeviceChangedListener {
            listener()
        }
        platformCommDeviceListener = platformListener
        audioManager.addOnCommunicationDeviceChangedListener(
            { runnable -> runnable.run() },
            platformListener,
        )
    }
}

private fun logInfo(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}

private fun logWarn(tag: String, message: String) {
    runCatching { Log.w(tag, message) }
}
