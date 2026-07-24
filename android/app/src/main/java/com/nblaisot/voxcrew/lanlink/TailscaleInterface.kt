package com.nblaisot.voxcrew.lanlink

import java.net.InetAddress

/** Address helpers only; local overlay identity comes from NetworkMonitor. */
object TailscaleInterface {
    /** True for an IPv4 address in RFC 6598 CGNAT space; not proof of Tailscale identity. */
    fun isCgnatAddress(host: String): Boolean {
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (bytes.size != 4) return false
        return (bytes[0].toInt() and 0xff) == 100 &&
            (bytes[1].toInt() and 0xff) in 64..127
    }

    @Deprecated("CGNAT membership alone does not identify a Tailscale network")
    fun isTailscaleAddress(host: String): Boolean = isCgnatAddress(host)
}
