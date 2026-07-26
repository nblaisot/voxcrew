package com.nblaisot.voxcrew.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.connectivity.TailscaleAppLauncher
import com.nblaisot.voxcrew.relay.RelayConfigLink
import com.nblaisot.voxcrew.ui.about.AboutScreen
import com.nblaisot.voxcrew.ui.main.MainScreen
import com.nblaisot.voxcrew.ui.main.MainViewModel
import com.nblaisot.voxcrew.ui.navigation.Routes
import com.nblaisot.voxcrew.ui.profile.ProfileScreen
import com.nblaisot.voxcrew.ui.profile.ProfileViewModel
import com.nblaisot.voxcrew.ui.relay.RelaySettingsScreen

@Composable
fun VoxCrewNavHost(
    container: AppContainer,
    onQuitApplication: () -> Unit,
    pendingRelayConfig: RelayConfigLink? = null,
    onRelayConfigConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val profileConfigured = container.localProfileRepository.isConfigured()
    val start = if (profileConfigured) Routes.MAIN else Routes.PROFILE

    LaunchedEffect(pendingRelayConfig) {
        val link = pendingRelayConfig ?: return@LaunchedEffect
        container.relaySettingsRepository.applyLink(link, enable = true)
        onRelayConfigConsumed()
        if (profileConfigured) {
            navController.navigate(Routes.RELAY)
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.PROFILE) {
            val repo = container.localProfileRepository
            val appContext = LocalContext.current.applicationContext
            val vm: ProfileViewModel = viewModel(
                factory = simpleFactory { ProfileViewModel(appContext, repo) },
            )
            ProfileScreen(vm) {
                navController.navigate(Routes.MAIN) { popUpTo(Routes.PROFILE) { inclusive = true } }
            }
        }
        composable(Routes.MAIN) {
            val appContext = LocalContext.current.applicationContext
            val tailscaleLauncher = remember(appContext) { TailscaleAppLauncher(appContext) }
            val vm: MainViewModel = viewModel(factory = simpleFactory {
                MainViewModel(
                    appContext = appContext,
                    authRepository = container.authRepository,
                    localProfileRepository = container.localProfileRepository,
                    rosterRepository = container.rosterRepository,
                    lanEngine = container.lanIntercomEngine,
                    demoModeStore = container.demoModeStore,
                )
            })
            MainScreen(
                viewModel = vm,
                onConnectTailscale = tailscaleLauncher::connectOrInstall,
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                onNavigateToRelay = { navController.navigate(Routes.RELAY) },
                onSignOut = {
                    navController.navigate(Routes.PROFILE) { popUpTo(0) }
                },
                onQuitApplication = onQuitApplication,
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onDemoModeToggle = { container.demoModeStore.toggle() },
            )
        }
        composable(Routes.RELAY) {
            val ready by container.lanIntercomEngine.relayReady.collectAsState()
            RelaySettingsScreen(
                repository = container.relaySettingsRepository,
                relayReady = ready,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
