package com.nblaisot.voxcrew.connectivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

enum class TailscaleLaunchResult {
    CONNECTION_REQUESTED,
    APP_OPENED,
    STORE_OPENED,
    FAILED,
}

/**
 * Best-effort bridge to the separately installed Tailscale Android app.
 *
 * Tailscale owns VPN consent, login and tunnel state. VoxCrew only asks it to connect and keeps
 * using its own network monitoring as the source of truth for overlay availability.
 */
class TailscaleAppLauncher private constructor(
    private val platform: TailscalePlatform,
) {
    constructor(context: Context) : this(AndroidTailscalePlatform(context.applicationContext))

    fun connectOrInstall(): TailscaleLaunchResult {
        if (!platform.isAppInstalled(TAILSCALE_PACKAGE)) {
            return when {
                platform.openUri(PLAY_STORE_URI) -> TailscaleLaunchResult.STORE_OPENED
                platform.openUri(PLAY_STORE_WEB_URI) -> TailscaleLaunchResult.STORE_OPENED
                else -> TailscaleLaunchResult.FAILED
            }
        }

        val supportsDirectConnect = platform.hasBroadcastReceiver(
            packageName = TAILSCALE_PACKAGE,
            action = CONNECT_VPN_ACTION,
        )
        val connectionRequested = supportsDirectConnect &&
            platform.sendBroadcast(
                packageName = TAILSCALE_PACKAGE,
                action = CONNECT_VPN_ACTION,
            )
        if (connectionRequested) {
            return TailscaleLaunchResult.CONNECTION_REQUESTED
        }

        return if (platform.openApp(TAILSCALE_PACKAGE)) {
            TailscaleLaunchResult.APP_OPENED
        } else {
            TailscaleLaunchResult.FAILED
        }
    }

    companion object {
        internal const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        internal const val CONNECT_VPN_ACTION = "com.tailscale.ipn.CONNECT_VPN"
        internal const val PLAY_STORE_URI = "market://details?id=$TAILSCALE_PACKAGE"
        internal const val PLAY_STORE_WEB_URI =
            "https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE"

        internal fun forTesting(platform: TailscalePlatform) = TailscaleAppLauncher(platform)
    }
}

internal interface TailscalePlatform {
    fun isAppInstalled(packageName: String): Boolean

    fun hasBroadcastReceiver(packageName: String, action: String): Boolean

    fun sendBroadcast(packageName: String, action: String): Boolean

    fun openApp(packageName: String): Boolean

    fun openUri(uri: String): Boolean
}

private class AndroidTailscalePlatform(
    private val context: Context,
) : TailscalePlatform {
    private val packageManager = context.packageManager

    override fun isAppInstalled(packageName: String): Boolean =
        launchIntent(packageName) != null

    override fun hasBroadcastReceiver(packageName: String, action: String): Boolean {
        val intent = packageScopedIntent(packageName, action)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryBroadcastReceivers(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                ).isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryBroadcastReceivers(intent, 0).isNotEmpty()
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun sendBroadcast(packageName: String, action: String): Boolean = try {
        context.sendBroadcast(packageScopedIntent(packageName, action))
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun openApp(packageName: String): Boolean =
        launchIntent(packageName)?.let(::startActivity) ?: false

    override fun openUri(uri: String): Boolean = startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
    )

    private fun launchIntent(packageName: String): Intent? = try {
        packageManager.getLaunchIntentForPackage(packageName)
    } catch (_: RuntimeException) {
        null
    }

    private fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun packageScopedIntent(packageName: String, action: String): Intent =
        Intent(action).setPackage(packageName)
}
