package com.nblaisot.voxcrew.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.ui.about.AboutScreen
import com.nblaisot.voxcrew.ui.main.MainScreen
import com.nblaisot.voxcrew.ui.main.MainViewModel
import com.nblaisot.voxcrew.ui.navigation.Routes
import com.nblaisot.voxcrew.ui.profile.ProfileScreen
import com.nblaisot.voxcrew.ui.profile.ProfileViewModel

@Composable
fun VoxCrewNavHost(
    container: AppContainer,
    onQuitApplication: () -> Unit,
) {
    val navController = rememberNavController()
    val profileConfigured = container.localProfileRepository.isConfigured()
    val start = if (profileConfigured) Routes.MAIN else Routes.PROFILE

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
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
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
    }
}
