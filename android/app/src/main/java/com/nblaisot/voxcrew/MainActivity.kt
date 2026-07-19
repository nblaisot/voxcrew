package com.nblaisot.voxcrew

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
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
        applyAutomationIntent(intent, container)
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
        applyAutomationIntent(intent, (application as VoxCrewApp).container)
    }

    /**
     * Automation / deep-link hooks for Play screenshots.
     * Human easter egg remains: 5 taps on the About title.
     */
    private fun applyAutomationIntent(intent: Intent?, container: AppContainer) {
        applyLocaleExtra(intent)
        val enable = intent?.getBooleanExtra(EXTRA_ENABLE_DEMO, false) == true ||
            intent?.getStringExtra(EXTRA_ENABLE_DEMO)?.equals("true", ignoreCase = true) == true
        if (enable) {
            container.demoModeStore.setEnabled(true)
        }
    }

    /** Optional BCP-47 tag, e.g. `en-US` / `fr-FR`, for screenshot automation. */
    private fun applyLocaleExtra(intent: Intent?) {
        val tag = intent?.getStringExtra(EXTRA_LOCALE)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java)
                .applicationLocales = LocaleList.forLanguageTags(tag)
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
        const val EXTRA_LOCALE = "locale"
    }
}
