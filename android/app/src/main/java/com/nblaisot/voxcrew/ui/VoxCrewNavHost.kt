package com.nblaisot.voxcrew.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.ui.about.AboutScreen
import com.nblaisot.voxcrew.ui.login.LoginScreen
import com.nblaisot.voxcrew.ui.login.LoginViewModel
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
    val authUser by container.authRepository.currentUser.collectAsState()
    val profileConfigured = container.localProfileRepository?.isConfigured() == true
    val start = when {
        BuildConfig.NO_BACKEND -> if (profileConfigured) Routes.MAIN else Routes.PROFILE
        authUser != null -> Routes.MAIN
        else -> Routes.LOGIN
    }

    NavHost(navController = navController, startDestination = start) {
        if (BuildConfig.NO_BACKEND) {
            composable(Routes.PROFILE) {
                val repo = container.localProfileRepository
                    ?: error("Local profile repository required in no-backend mode")
                val appContext = LocalContext.current.applicationContext
                val vm: ProfileViewModel = viewModel(
                    factory = simpleFactory { ProfileViewModel(appContext, repo) },
                )
                ProfileScreen(vm) {
                    navController.navigate(Routes.MAIN) { popUpTo(Routes.PROFILE) { inclusive = true } }
                }
            }
        } else {
            composable(Routes.LOGIN) {
                val appContext = LocalContext.current.applicationContext
                val vm: LoginViewModel = viewModel(
                    factory = simpleFactory {
                        LoginViewModel(appContext, container.authRepository)
                    },
                )
                LoginScreen(vm) {
                    navController.navigate(Routes.MAIN) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }
        }
        composable(Routes.MAIN) {
            val appContext = LocalContext.current.applicationContext
            val vm: MainViewModel = viewModel(factory = simpleFactory {
                MainViewModel(
                    appContext = appContext,
                    noBackend = container.noBackend,
                    authRepository = container.authRepository,
                    localProfileRepository = container.localProfileRepository,
                    signalingClient = container.signalingClient,
                    rosterRepository = container.rosterRepository,
                    lanEngine = container.lanIntercomEngine,
                    demoModeStore = container.demoModeStore,
                )
            })
            MainScreen(
                viewModel = vm,
                noBackend = container.noBackend,
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                onSignOut = {
                    val destination = if (BuildConfig.NO_BACKEND) Routes.PROFILE else Routes.LOGIN
                    navController.navigate(destination) { popUpTo(0) }
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
