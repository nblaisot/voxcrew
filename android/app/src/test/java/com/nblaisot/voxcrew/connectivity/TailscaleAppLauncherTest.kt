package com.nblaisot.voxcrew.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleAppLauncherTest {
    @Test
    fun `installed supported app receives package scoped connection request`() {
        val platform = FakePlatform(installed = true, receiverAvailable = true)

        val result = TailscaleAppLauncher.forTesting(platform).connectOrInstall()

        assertEquals(TailscaleLaunchResult.CONNECTION_REQUESTED, result)
        assertEquals(
            TailscaleAppLauncher.TAILSCALE_PACKAGE to TailscaleAppLauncher.CONNECT_VPN_ACTION,
            platform.sentBroadcast,
        )
    }

    @Test
    fun `installed app opens when direct connection is unsupported`() {
        val platform = FakePlatform(installed = true, receiverAvailable = false)

        val result = TailscaleAppLauncher.forTesting(platform).connectOrInstall()

        assertEquals(TailscaleLaunchResult.APP_OPENED, result)
        assertEquals(TailscaleAppLauncher.TAILSCALE_PACKAGE, platform.openedApp)
    }

    @Test
    fun `installed app opens when connection broadcast fails`() {
        val platform = FakePlatform(
            installed = true,
            receiverAvailable = true,
            broadcastSucceeds = false,
        )

        val result = TailscaleAppLauncher.forTesting(platform).connectOrInstall()

        assertEquals(TailscaleLaunchResult.APP_OPENED, result)
    }

    @Test
    fun `missing app opens Play Store`() {
        val platform = FakePlatform(installed = false)

        val result = TailscaleAppLauncher.forTesting(platform).connectOrInstall()

        assertEquals(TailscaleLaunchResult.STORE_OPENED, result)
        assertEquals(listOf(TailscaleAppLauncher.PLAY_STORE_URI), platform.openedUris)
    }

    @Test
    fun `missing Play Store falls back to web listing`() {
        val platform = FakePlatform(installed = false, marketOpens = false)

        val result = TailscaleAppLauncher.forTesting(platform).connectOrInstall()

        assertEquals(TailscaleLaunchResult.STORE_OPENED, result)
        assertEquals(
            listOf(
                TailscaleAppLauncher.PLAY_STORE_URI,
                TailscaleAppLauncher.PLAY_STORE_WEB_URI,
            ),
            platform.openedUris,
        )
    }

    @Test
    fun `complete launch failure is reported without retrying unrelated actions`() {
        val installedPlatform = FakePlatform(
            installed = true,
            receiverAvailable = false,
            appOpens = false,
        )
        val missingPlatform = FakePlatform(
            installed = false,
            marketOpens = false,
            webOpens = false,
        )

        assertEquals(
            TailscaleLaunchResult.FAILED,
            TailscaleAppLauncher.forTesting(installedPlatform).connectOrInstall(),
        )
        assertEquals(
            TailscaleLaunchResult.FAILED,
            TailscaleAppLauncher.forTesting(missingPlatform).connectOrInstall(),
        )
        assertTrue(installedPlatform.openedUris.isEmpty())
    }

    private class FakePlatform(
        private val installed: Boolean,
        private val receiverAvailable: Boolean = false,
        private val broadcastSucceeds: Boolean = true,
        private val appOpens: Boolean = true,
        private val marketOpens: Boolean = true,
        private val webOpens: Boolean = true,
    ) : TailscalePlatform {
        var sentBroadcast: Pair<String, String>? = null
        var openedApp: String? = null
        val openedUris = mutableListOf<String>()

        override fun isAppInstalled(packageName: String): Boolean = installed

        override fun hasBroadcastReceiver(packageName: String, action: String): Boolean =
            receiverAvailable

        override fun sendBroadcast(packageName: String, action: String): Boolean {
            sentBroadcast = packageName to action
            return broadcastSucceeds
        }

        override fun openApp(packageName: String): Boolean {
            openedApp = packageName
            return appOpens
        }

        override fun openUri(uri: String): Boolean {
            openedUris += uri
            return when (uri) {
                TailscaleAppLauncher.PLAY_STORE_URI -> marketOpens
                TailscaleAppLauncher.PLAY_STORE_WEB_URI -> webOpens
                else -> false
            }
        }
    }
}
