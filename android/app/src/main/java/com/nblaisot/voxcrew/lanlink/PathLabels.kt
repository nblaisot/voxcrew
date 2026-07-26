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
    /** Optional self-hosted Mac Mini / crew relay (UUID dial). */
    const val CLOUD = "Cloud"

    fun displayName(context: Context, label: String): String = when (label) {
        LOCAL -> context.getString(R.string.path_local)
        VPN -> context.getString(R.string.path_vpn)
        CLOUD -> context.getString(R.string.path_cloud)
        else -> label
    }
}
