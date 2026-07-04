package com.nblaisot.voxcrew.di

import android.content.Context
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.auth.FirebaseAuthRepository
import com.nblaisot.voxcrew.connectivity.discovery.NsdLocalPeerDiscovery
import com.nblaisot.voxcrew.connectivity.local.LocalSignalingServer
import com.nblaisot.voxcrew.connectivity.local.QrJoinPayload
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.connectivity.orchestration.ConnectivityOrchestrator
import com.nblaisot.voxcrew.connectivity.orchestration.ConnectivityOrchestratorImpl
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.connectivity.transport.LocalLanSignalingTransport
import com.nblaisot.voxcrew.connectivity.webrtc.PeerConnectionFactoryFacade
import com.nblaisot.voxcrew.connectivity.webrtc.WebRtcConnectionSwitcher
import com.nblaisot.voxcrew.connectivity.webrtc.WebRtcConnectionSwitcherImpl
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import com.nblaisot.voxcrew.signaling.SignalingClient
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
import com.nblaisot.voxcrew.webrtc.IceServerConfig
import com.nblaisot.voxcrew.webrtc.WebRtcSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val authRepository: AuthRepository = FirebaseAuthRepository()

    val cloudTransport = CloudRunSignalingTransport(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
    )
    val localTransport = LocalLanSignalingTransport()

    val signalingClient: SignalingClient = SignalingClient(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
        transport = cloudTransport,
        scope = scope,
    )

    val localDiscovery = NsdLocalPeerDiscovery(appContext)
    val localServer = LocalSignalingServer()

    val iceServerConfig = IceServerConfig(stunUrl = BuildConfig.STUN_SERVER_URL)
    val lanIceConfig = IceServerConfig(stunUrl = BuildConfig.STUN_SERVER_URL)

    private val factoryFacade = PeerConnectionFactoryFacade(appContext)
    val connectionSwitcher: WebRtcConnectionSwitcher = WebRtcConnectionSwitcherImpl(
        factoryFacade = factoryFacade,
        cloudIce = iceServerConfig,
        lanIce = lanIceConfig,
        scope = scope,
    )

    val connectivityOrchestrator: ConnectivityOrchestrator = ConnectivityOrchestratorImpl(
        scope = scope,
        localTransport = localTransport,
        cloudTransport = cloudTransport,
        localDiscovery = localDiscovery,
        localServer = localServer,
        connectionSwitcher = connectionSwitcher,
    )

    val lanIntercomEngine = LanIntercomEngine(
        context = appContext,
        scope = scope,
        cloudTransport = cloudTransport,
    )

    val rosterRepository = CrewRosterRepository(
        context = appContext,
        signalingClient = signalingClient,
        lanPeers = lanIntercomEngine.peers,
        scope = scope,
    )

    @Deprecated("Use connectionSwitcher for generation-aware sessions")
    val webRtcSessionManager = WebRtcSessionManager(appContext, iceServerConfig)

    suspend fun createLocalSession(participantId: String): Result<Pair<String, QrJoinPayload>> {
        val sessionId = UUID.randomUUID().toString()
        val info = localServer.start(sessionId)
        localDiscovery.start(sessionId)
        localDiscovery.registerHost(info.port, instanceId = participantId.take(8))
        val descriptor = SessionDescriptor(
            sessionId = sessionId,
            participantId = participantId,
            sessionSecret = info.sessionSecret.token,
            hostParticipantId = participantId,
            isLocalHost = true,
        )
        val gen = com.nblaisot.voxcrew.connectivity.model.GenerationId.next()
        localTransport.configureEndpoint(info.host, info.port)
        localTransport.connect(descriptor, gen)
        val requestId = UUID.randomUUID().toString()
        localTransport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.CREATE_SESSION,
                requestId = requestId,
                payload = jsonPayload("name" to "local"),
            ),
        )
        val created = withTimeoutOrNull(10_000) {
            localTransport.incomingMessages.first {
                it.requestId == requestId &&
                    (it.type == SignalingMessageTypes.SESSION_CREATED || it.type == SignalingMessageTypes.ERROR)
            }
        } ?: return Result.failure(IllegalStateException("Local session timeout"))
        if (created.type == SignalingMessageTypes.ERROR) {
            return Result.failure(IllegalStateException("Local session failed"))
        }
        val qr = QrJoinPayload(
            host = info.host,
            port = info.port,
            sessionId = sessionId,
            token = info.sessionSecret.token,
        )
        return Result.success(sessionId to qr)
    }

    suspend fun joinLocalSession(payload: QrJoinPayload, participantId: String): Result<Unit> {
        localTransport.configureEndpoint(payload.host, payload.port)
        val descriptor = SessionDescriptor(
            sessionId = payload.sessionId,
            participantId = participantId,
            sessionSecret = payload.token,
            hostParticipantId = null,
            isLocalHost = false,
        )
        val gen = com.nblaisot.voxcrew.connectivity.model.GenerationId.next()
        localTransport.connect(descriptor, gen)
        val requestId = UUID.randomUUID().toString()
        localTransport.send(
            SignalingEnvelope(
                type = SignalingMessageTypes.JOIN_SESSION,
                requestId = requestId,
                payload = jsonPayload("sessionId" to payload.sessionId),
            ),
        )
        val joined = withTimeoutOrNull(10_000) {
            localTransport.incomingMessages.first {
                it.requestId == requestId &&
                    (it.type == SignalingMessageTypes.SESSION_JOINED || it.type == SignalingMessageTypes.ERROR)
            }
        } ?: return Result.failure(IllegalStateException("Local join timeout"))
        if (joined.type == SignalingMessageTypes.ERROR) {
            return Result.failure(IllegalStateException("Local join failed"))
        }
        return Result.success(Unit)
    }
}
