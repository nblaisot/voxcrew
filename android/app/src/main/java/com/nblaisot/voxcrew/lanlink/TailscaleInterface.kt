package com.nblaisot.voxcrew.lanlink

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Detects the local Tailscale (or similar overlay) IPv4 address on 100.64.0.0/10.
 * Used as an optional overlay endpoint in LAN beacons for plan-B connectivity.
 */
object TailscaleInterface {
    fun localOverlayIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress }
            ?.firstOrNull { it != null && isTailscaleAddress(it) }
    }.getOrNull()

    fun isTailscaleAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }
}
