package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.audio.PushToTalkTransmissionPolicy
import com.nblaisot.voxcrew.audio.TransmissionPolicy
import com.nblaisot.voxcrew.audio.VoiceActivatedTransmissionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single facade for the whole local-mode audio path: discovery, TCP link, capture
 * and playback. Deliberately owned by [com.nblaisot.voxcrew.di.AppContainer] (an
 * application-scoped singleton, independent from any Activity/ViewModel) so that
 * receiving audio and VOX transmission keep working when the app is backgrounded or
 * the screen is off — only the foreground service and its notification observe it.
 */
class LanIntercomEngine(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val beacon = LanBeacon(context, scope)
    private val link = LanAudioLink(scope)
    private val capture = AudioCapture(scope)
    private val playback = AudioPlayback(scope)

    private val pttPolicy = PushToTalkTransmissionPolicy()
    private val voxPolicy = VoiceActivatedTransmissionPolicy()
    private var activePolicy: TransmissionPolicy = pttPolicy
    private var policyWatchJob: Job? = null

    val peers: StateFlow<List<LanPeer>> = beacon.peers
    val linkState: StateFlow<LanAudioLink.LinkState> = link.state
    val isReceiving: StateFlow<Boolean> = playback.isReceiving

    private val _selectedPeerUid = MutableStateFlow<String?>(null)
    val selectedPeerUid: StateFlow<String?> = _selectedPeerUid.asStateFlow()

    private val _voxEnabled = MutableStateFlow(false)
    val voxEnabled: StateFlow<Boolean> = _voxEnabled.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    val statusText: StateFlow<String> = combine(peers, selectedPeerUid, linkState) { peerList, selected, link ->
        describeStatus(peerList, selected, link)
    }.stateIn(scope, SharingStarted.Eagerly, describeStatus(peers.value, selectedPeerUid.value, linkState.value))

    private var started = false
    private var localUid: String = ""

    fun start(uid: String, displayName: String) {
        if (started) return
        started = true
        localUid = uid

        link.startServer(uid)
        link.onInboundPeer = { peerUid -> if (_selectedPeerUid.value == null) selectPeer(peerUid) }
        beacon.start(uid, displayName, link.localPort)

        capture.attach(activePolicy.shouldTransmit) { pcm -> link.send(pcm) }
        watchPolicy(activePolicy)

        scope.launch(Dispatchers.IO) {
            link.incomingAudio.collect { pcm -> playback.play(pcm) }
        }
        scope.launch {
            beacon.peers.collect { list ->
                val selected = _selectedPeerUid.value
                if (selected != null) {
                    list.firstOrNull { it.uid == selected }?.let { link.setTarget(it) }
                } else if (list.size == 1) {
                    selectPeer(list.first().uid)
                }
            }
        }
    }

    fun selectPeer(uid: String) {
        _selectedPeerUid.value = uid
        val peer = beacon.peers.value.firstOrNull { it.uid == uid }
            ?: LanPeer(uid = uid, displayName = uid, host = "", port = 0, lastSeenMs = System.currentTimeMillis())
        link.setTarget(peer)
    }

    fun clearSelection() {
        _selectedPeerUid.value = null
        link.setTarget(null)
    }

    fun setVoxEnabled(enabled: Boolean) {
        _voxEnabled.value = enabled
        activePolicy = if (enabled) {
            voxPolicy.setSpeechDetected(true)
            voxPolicy
        } else {
            voxPolicy.setSpeechDetected(false)
            pttPolicy.cancel()
            pttPolicy
        }
        watchPolicy(activePolicy)
        capture.attach(activePolicy.shouldTransmit) { pcm -> link.send(pcm) }
    }

    fun pttPress() {
        if (_voxEnabled.value) return
        pttPolicy.onPress()
    }

    fun pttRelease() {
        if (_voxEnabled.value) return
        pttPolicy.onRelease()
    }

    private fun watchPolicy(policy: TransmissionPolicy) {
        policyWatchJob?.cancel()
        policyWatchJob = scope.launch {
            policy.shouldTransmit.collect { _isTransmitting.value = it }
        }
    }

    private fun describeStatus(peerList: List<LanPeer>, selected: String?, linkState: LanAudioLink.LinkState): String {
        if (selected == null) {
            return if (peerList.isEmpty()) "Recherche de coéquipiers…" else "Coéquipier détecté"
        }
        return when (linkState) {
            is LanAudioLink.LinkState.Connected -> "Local — connecté"
            is LanAudioLink.LinkState.Connecting -> "Local — connexion…"
            is LanAudioLink.LinkState.Disconnected -> "Local — reconnexion…"
            LanAudioLink.LinkState.Idle -> "Local — en attente…"
        }
    }
}
