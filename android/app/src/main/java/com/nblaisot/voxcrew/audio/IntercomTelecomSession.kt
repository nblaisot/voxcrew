package com.nblaisot.voxcrew.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Registers a self-managed Telecom call while the intercom session is active so the OS
 * treats VoxCrew as a VoIP call (helps Bluetooth routing on some OEMs, e.g. Samsung).
 * Audio routing remains in [IntercomAudioSession]; this only signals call state to Telecom.
 */
class IntercomTelecomSession(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val callsManager = CallsManager(context.applicationContext)
    private var sessionJob: Job? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            } catch (e: Exception) {
                Log.w(TAG, "Telecom registration failed: ${e.message}")
            }
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                val attributes = CallAttributesCompat(
                    displayName = DISPLAY_NAME,
                    address = Uri.fromParts(SCHEME, HOST, null),
                    direction = CallAttributesCompat.DIRECTION_OUTGOING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = 0,
                )
                callsManager.addCall(
                    callAttributes = attributes,
                    onAnswer = { },
                    onDisconnect = { },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    runBlocking(coroutineContext) {
                        runCatching { setActive() }
                            .onFailure { error ->
                                Log.w(TAG, "Telecom setActive failed: ${error.message}")
                            }
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching {
                                disconnect(DisconnectCause(DisconnectCause.LOCAL))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom intercom session failed: ${e.message}")
            }
        }
    }

    fun stop() {
        val job = sessionJob ?: return
        sessionJob = null
        scope.launch(Dispatchers.IO) {
            job.cancelAndJoin()
        }
    }

    companion object {
        private const val TAG = "IntercomTelecomSession"
        private const val DISPLAY_NAME = "VoxCrew"
        private const val SCHEME = "sip"
        private const val HOST = "intercom@voxcrew"
    }
}
