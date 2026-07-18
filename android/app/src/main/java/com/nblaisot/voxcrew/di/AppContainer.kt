package com.nblaisot.voxcrew.di

import android.content.Context
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.audio.IntercomTelecomSession
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.auth.FirebaseAuthRepository
import com.nblaisot.voxcrew.auth.LocalProfileRepository
import com.nblaisot.voxcrew.connectivity.transport.CloudRunSignalingTransport
import com.nblaisot.voxcrew.demo.DemoModeStore
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val noBackend: Boolean = BuildConfig.NO_BACKEND

    val demoModeStore = DemoModeStore(appContext)

    // Register with Telecom as early as possible so PhoneAccount is active before addCall().
    private val intercomTelecomSession = IntercomTelecomSession(
        context = appContext,
        scope = scope,
        demoModeStore = demoModeStore,
    )

    val authRepository: AuthRepository = if (noBackend) {
        LocalProfileRepository(appContext)
    } else {
        FirebaseAuthRepository()
    }

    val localProfileRepository: LocalProfileRepository?
        get() = authRepository as? LocalProfileRepository

    val cloudTransport = CloudRunSignalingTransport(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
    )

    val signalingClient: SignalingClient? = if (noBackend) {
        null
    } else {
        SignalingClient(
            baseUrl = BuildConfig.SIGNALING_BASE_URL,
            authRepository = authRepository,
            transport = cloudTransport,
            scope = scope,
        )
    }

    val lanIntercomEngine = LanIntercomEngine(
        context = appContext,
        scope = scope,
        cloudTransport = cloudTransport,
        signalingClient = signalingClient,
        telecomSession = intercomTelecomSession,
        cloudFallbackEnabled = !noBackend,
        optInRecipients = noBackend,
        overlayFallbackEnabled = noBackend,
    )

    val rosterRepository = CrewRosterRepository(
        context = appContext,
        signalingClient = signalingClient,
        lanPeers = lanIntercomEngine.peers,
        scope = scope,
        noBackend = noBackend,
    )
}
