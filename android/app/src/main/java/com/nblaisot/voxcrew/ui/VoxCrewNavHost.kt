package com.nblaisot.voxcrew.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.ui.debug.DebugScreen
import com.nblaisot.voxcrew.ui.home.HomeViewModel
import com.nblaisot.voxcrew.ui.login.LoginScreen
import com.nblaisot.voxcrew.ui.login.LoginViewModel
import com.nblaisot.voxcrew.ui.main.MainScreen
import com.nblaisot.voxcrew.ui.main.MainViewModel
import com.nblaisot.voxcrew.ui.navigation.Routes
import com.nblaisot.voxcrew.ui.session.SessionScreen
import com.nblaisot.voxcrew.ui.session.SessionViewModel

@Composable
fun VoxCrewNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val authUser by container.authRepository.currentUser.collectAsState()
    val start = if (authUser != null) Routes.MAIN else Routes.LOGIN

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            val vm: LoginViewModel = viewModel(factory = simpleFactory { LoginViewModel(container.authRepository) })
            LoginScreen(vm) {
                navController.navigate(Routes.MAIN) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }
        }
        composable(Routes.MAIN) {
            val appContext = LocalContext.current.applicationContext
            val vm: MainViewModel = viewModel(factory = simpleFactory {
                MainViewModel(
                    appContext = appContext,
                    authRepository = container.authRepository,
                    signalingClient = container.signalingClient,
                    rosterRepository = container.rosterRepository,
                    lanEngine = container.lanIntercomEngine,
                )
            })
            MainScreen(
                viewModel = vm,
                onSignOut = { navController.navigate(Routes.LOGIN) { popUpTo(0) } },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
            )
        }
        composable(Routes.DEBUG) {
            val vm: HomeViewModel = viewModel(factory = simpleFactory {
                HomeViewModel(container.authRepository, container.signalingClient, container)
            })
            DebugScreen(
                homeViewModel = vm,
                onBack = { navController.popBackStack() },
                onSessionReady = { id, localHost -> navController.navigate(Routes.session(id, localHost)) },
            )
        }
        composable(
            route = Routes.SESSION,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("localHost") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val localHost = entry.arguments?.getBoolean("localHost") ?: false
            val appContext = LocalContext.current.applicationContext
            val vm: SessionViewModel = viewModel(factory = simpleFactory {
                SessionViewModel(
                    appContext = appContext,
                    signalingClient = container.signalingClient,
                    orchestrator = container.connectivityOrchestrator,
                    connectionSwitcher = container.connectionSwitcher,
                )
            })
            SessionScreen(vm, sessionId, localHost) {
                navController.popBackStack()
            }
        }
    }
}
