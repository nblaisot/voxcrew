package com.nblaisot.voxcrew.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.ui.home.HomeScreen
import com.nblaisot.voxcrew.ui.home.HomeViewModel
import com.nblaisot.voxcrew.ui.login.LoginScreen
import com.nblaisot.voxcrew.ui.login.LoginViewModel
import com.nblaisot.voxcrew.ui.navigation.Routes
import com.nblaisot.voxcrew.ui.session.SessionScreen
import com.nblaisot.voxcrew.ui.session.SessionViewModel

@Composable
fun VoxCrewNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val authUser by container.authRepository.currentUser.collectAsState()
    val start = if (authUser != null) Routes.HOME else Routes.LOGIN

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LOGIN) {
            val vm: LoginViewModel = viewModel(factory = simpleFactory { LoginViewModel(container.authRepository) })
            LoginScreen(vm) {
                navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = simpleFactory {
                HomeViewModel(container.authRepository, container.signalingClient)
            })
            HomeScreen(
                viewModel = vm,
                onSessionReady = { id -> navController.navigate(Routes.session(id)) },
                onSignOut = { navController.navigate(Routes.LOGIN) { popUpTo(0) } },
            )
        }
        composable(
            route = Routes.SESSION,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val appContext = LocalContext.current.applicationContext
            val vm: SessionViewModel = viewModel(factory = simpleFactory {
                SessionViewModel(
                    appContext = appContext,
                    signalingClient = container.signalingClient,
                    webRtc = container.webRtcSessionManager,
                )
            })
            SessionScreen(vm, sessionId) {
                navController.popBackStack()
            }
        }
    }
}
