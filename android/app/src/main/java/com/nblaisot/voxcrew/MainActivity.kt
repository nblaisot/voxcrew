package com.nblaisot.voxcrew

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.ui.VoxCrewNavHost
import com.nblaisot.voxcrew.ui.theme.VoxCrewTheme

class MainActivity : ComponentActivity() {
    private val intercomEngine
        get() = (application as VoxCrewApp).container.lanIntercomEngine

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoxCrewApp).container
        applyDemoIntent(intent, container)
        setContent {
            VoxCrewTheme {
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    VoxCrewNavHost(
                        container = container,
                        onQuitApplication = ::quitApplication,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDemoIntent(intent, (application as VoxCrewApp).container)
    }

    /**
     * Automation / deep-link hook for Play screenshots.
     * Human easter egg remains: 5 taps on the About title.
     */
    private fun applyDemoIntent(intent: Intent?, container: AppContainer) {
        val enable = intent?.getBooleanExtra(EXTRA_ENABLE_DEMO, false) == true ||
            intent?.getStringExtra(EXTRA_ENABLE_DEMO)?.equals("true", ignoreCase = true) == true
        if (enable) {
            container.demoModeStore.setEnabled(true)
        }
    }

    private fun quitApplication() {
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        intercomEngine.setAppForeground(true)
    }

    override fun onPause() {
        if (!isChangingConfigurations) {
            intercomEngine.setAppForeground(false)
        }
        super.onPause()
    }

    companion object {
        const val EXTRA_ENABLE_DEMO = "enable_demo"
    }
}
