package com.nblaisot.voxcrew.lanlink

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Detects the local Tailscale (or similar overlay) IPv4 address on 100.64.0.0/10.
 * Used as an optional overlay endpoint in LAN beacons for plan-B connectivity.
 */
object TailscaleInterface {
    data class OverlayCandidate(val ifaceName: String, val address: String)

    fun localOverlayIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { ni ->
                ni.inetAddresses.toList().asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { addr ->
                        addr.hostAddress?.let { OverlayCandidate(ni.name, it) }
                    }
            }
            ?.toList()
            ?.let { selectLocalOverlayIpv4(it) }
    }.getOrNull()

    /**
     * Prefer `tun*` (Tailscale) over other 100.x VPNs, then a stable iface/address order
     * so dual-tun / Zscaler coexistence does not flip the announced overlay IP every call.
     */
    fun selectLocalOverlayIpv4(candidates: List<OverlayCandidate>): String? {
        val ts = candidates.filter { isTailscaleAddress(it.address) }
        if (ts.isEmpty()) return null
        val tun = ts.filter { it.ifaceName.startsWith("tun", ignoreCase = true) }
        val pool = tun.ifEmpty { ts }
        return pool.minWithOrNull(
            compareBy<OverlayCandidate> { it.ifaceName.lowercase() }
                .thenBy { it.address },
        )?.address
    }

    fun isTailscaleAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }
}
