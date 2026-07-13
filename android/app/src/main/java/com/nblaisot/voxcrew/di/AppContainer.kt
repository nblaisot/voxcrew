package com.nblaisot.voxcrew.di

import android.content.Context
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.audio.IntercomTelecomSession
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.auth.FirebaseAuthRepository
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Register with Telecom as early as possible so PhoneAccount is active before addCall().
    private val intercomTelecomSession = IntercomTelecomSession(appContext, scope)

    val authRepository: AuthRepository = FirebaseAuthRepository()

    val cloudTransport = CloudRunSignalingTransport(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
    )

    val signalingClient: SignalingClient = SignalingClient(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
        transport = cloudTransport,
        scope = scope,
    )

    val lanIntercomEngine = LanIntercomEngine(
        context = appContext,
        scope = scope,
        cloudTransport = cloudTransport,
        signalingClient = signalingClient,
        telecomSession = intercomTelecomSession,
    )

    val rosterRepository = CrewRosterRepository(
        context = appContext,
        signalingClient = signalingClient,
        lanPeers = lanIntercomEngine.peers,
        scope = scope,
    )
}
