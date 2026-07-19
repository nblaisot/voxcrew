package com.nblaisot.voxcrew.lanlink

import android.content.Context
import com.nblaisot.voxcrew.R

/**
 * Locale-independent path tokens stored on transports / peer metrics.
 * Localize only when displaying via [displayName].
 */
object PathLabels {
    const val LOCAL = "Local"
    const val VPN = "VPN"
    const val DIRECT_INTERNET = "Direct internet"
    const val CLOUD_RELAY = "Cloud relay"

    fun displayName(context: Context, label: String): String = when (label) {
        LOCAL -> context.getString(R.string.path_local)
        VPN -> context.getString(R.string.path_vpn)
        DIRECT_INTERNET -> context.getString(R.string.path_direct_internet)
        CLOUD_RELAY -> context.getString(R.string.path_cloud_relay)
        else -> label
    }
}
