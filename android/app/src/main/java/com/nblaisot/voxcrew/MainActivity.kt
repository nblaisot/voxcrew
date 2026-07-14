package com.nblaisot.voxcrew

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nblaisot.voxcrew.ui.VoxCrewNavHost
import com.nblaisot.voxcrew.ui.theme.VoxCrewTheme

class MainActivity : ComponentActivity() {
    private val intercomEngine
        get() = (application as VoxCrewApp).container.lanIntercomEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoxCrewApp).container
        setContent {
            VoxCrewTheme {
                VoxCrewNavHost(
                    container = container,
                    onQuitApplication = ::quitApplication,
                )
            }
        }
    }

    private fun quitApplication() {
        finishAndRemoveTask()
    }

    override fun onStart() {
        super.onStart()
        intercomEngine.setAppForeground(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) intercomEngine.setAppForeground(false)
        super.onStop()
    }
}
