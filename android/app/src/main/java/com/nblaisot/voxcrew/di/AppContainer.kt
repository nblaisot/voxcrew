package com.nblaisot.voxcrew.di

import android.content.Context
import com.nblaisot.voxcrew.audio.IntercomTelecomSession
import com.nblaisot.voxcrew.auth.LocalProfileRepository
import com.nblaisot.voxcrew.demo.DemoModeStore
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import com.nblaisot.voxcrew.roster.CrewRosterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val demoModeStore = DemoModeStore(appContext)

    // Register with Telecom as early as possible so PhoneAccount is active before addCall().
    private val intercomTelecomSession = IntercomTelecomSession(
        context = appContext,
        scope = scope,
        demoModeStore = demoModeStore,
    )

    val authRepository = LocalProfileRepository(appContext)
    val localProfileRepository: LocalProfileRepository get() = authRepository

    val lanIntercomEngine = LanIntercomEngine(
        context = appContext,
        scope = scope,
        telecomSession = intercomTelecomSession,
        optInRecipients = true,
        overlayFallbackEnabled = true,
    )

    val rosterRepository = CrewRosterRepository(
        context = appContext,
        lanPeers = lanIntercomEngine.peers,
        scope = scope,
    )
}
