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

    fun displayName(context: Context, label: String): String = when (label) {
        LOCAL -> context.getString(R.string.path_local)
        VPN -> context.getString(R.string.path_vpn)
        else -> label
    }
}
