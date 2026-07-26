package com.nblaisot.voxcrew

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.relay.RelayConfigLink
import com.nblaisot.voxcrew.relay.RelayConfigLinkParser
import com.nblaisot.voxcrew.ui.VoxCrewNavHost
import com.nblaisot.voxcrew.ui.theme.VoxCrewTheme

class MainActivity : ComponentActivity() {
    private val intercomEngine
        get() = (application as VoxCrewApp).container.lanIntercomEngine

    /** When set, NavHost navigates to Relay after applying settings. */
    private var pendingRelayConfig by mutableStateOf<RelayConfigLink?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoxCrewApp).container
        applyAutomationIntent(intent, container)
        consumeRelayIntent(intent, container)
        setContent {
            VoxCrewTheme {
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    VoxCrewNavHost(
                        container = container,
                        onQuitApplication = ::quitApplication,
                        pendingRelayConfig = pendingRelayConfig,
                        onRelayConfigConsumed = { pendingRelayConfig = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as VoxCrewApp).container
        applyAutomationIntent(intent, container)
        consumeRelayIntent(intent, container)
    }

    /**
     * Apply relay deep links immediately (not only via Compose), using Android [Uri]
     * query getters so encoding quirks from browsers still work.
     */
    private fun consumeRelayIntent(intent: Intent?, container: AppContainer) {
        val link = relayLinkFromIntent(intent) ?: return
        Log.i(TAG, "relay deep link applied url=${link.url}")
        container.relaySettingsRepository.applyLink(link, enable = true)
        pendingRelayConfig = link
    }

    private fun relayLinkFromIntent(intent: Intent?): RelayConfigLink? {
        if (intent == null) return null
        if (intent.action != null &&
            intent.action != Intent.ACTION_VIEW &&
            intent.action != Intent.ACTION_MAIN
        ) {
            // Still allow VIEW-less cases with data (some OEMs).
        }
        val data = intent.data ?: return RelayConfigLinkParser.parse(intent.dataString)
        val fromParams = linkFromUriParams(data)
        if (fromParams != null) return fromParams
        return RelayConfigLinkParser.parse(data.toString())
            ?: RelayConfigLinkParser.parse(intent.dataString)
    }

    private fun linkFromUriParams(uri: Uri): RelayConfigLink? {
        val host = uri.host?.lowercase()
        val path = uri.path?.trim('/')?.lowercase().orEmpty()
        val scheme = uri.scheme?.lowercase().orEmpty()
        val looksLikeApp = scheme == "voxcrew" && (host == "relay-config" || path == "relay-config")
        val looksLikeInvite = (scheme == "https" || scheme == "http") &&
            (path == "invite" || path == "relay-config" || host == "relay-config")
        if (!looksLikeApp && !looksLikeInvite) return null
        val url = uri.getQueryParameter("url")?.trim().orEmpty()
        val secret = uri.getQueryParameter("secret")?.trim().orEmpty()
        if (url.isEmpty() || secret.isEmpty()) {
            Log.w(TAG, "relay deep link missing url/secret scheme=$scheme host=$host")
            return null
        }
        val cert = uri.getQueryParameter("certSha256")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return RelayConfigLink(url = url, secret = secret, certSha256 = cert)
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
        private const val TAG = "MainActivity"
        const val EXTRA_ENABLE_DEMO = "enable_demo"
        const val EXTRA_LOCALE = "locale"
    }
}
