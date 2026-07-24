package com.nblaisot.voxcrew.connectivity

import java.net.InetAddress

internal enum class IpFamily { IPV4, IPV6 }

internal data class IpAddressDescription(
    val address: String,
    val prefixLength: Int,
    val family: IpFamily,
)

internal data class LinkPropertiesDescription(
    val networkHandle: Long,
    val interfaceName: String,
    val linkAddresses: List<IpAddressDescription>,
    val routePrefixes: List<IpAddressDescription> = emptyList(),
    val dnsAddresses: Set<String> = emptySet(),
    val domains: Set<String> = emptySet(),
)

internal data class TailscaleNetworkCandidate(
    val networkHandle: Long,
    val interfaceName: String,
    val ipv4Address: String,
)

/** Pure resolver: CGNAT alone is insufficient because corporate VPNs may use it too. */
internal object TailscaleNetworkResolver {
    private const val QUAD100 = "100.100.100.100"

    fun candidate(properties: LinkPropertiesDescription): TailscaleNetworkCandidate? {
        val nodeAddress = properties.linkAddresses.singleOrNull { link ->
            link.family == IpFamily.IPV4 &&
                link.prefixLength == 32 &&
                isCgnatAddress(link.address) &&
                !link.address.startsWith("100.100.")
        } ?: return null
        val hasTailscaleIpv6 = (properties.linkAddresses + properties.routePrefixes).any { link ->
            link.family == IpFamily.IPV6 && isInTailscaleIpv6Range(link.address)
        }
        val hasQuad100 = QUAD100 in properties.dnsAddresses ||
            properties.routePrefixes.any { route ->
                route.family == IpFamily.IPV4 &&
                    route.prefixLength == 32 &&
                    route.address == QUAD100
            }
        val hasTailnetDomain = properties.domains.any { domain ->
            domain.trimEnd('.').endsWith(".ts.net", ignoreCase = true)
        }
        if (!hasTailscaleIpv6 && !hasQuad100 && !hasTailnetDomain) return null
        return TailscaleNetworkCandidate(
            networkHandle = properties.networkHandle,
            interfaceName = properties.interfaceName,
            ipv4Address = nodeAddress.address,
        )
    }

    fun select(
        candidates: List<TailscaleNetworkCandidate>,
        currentNetworkHandle: Long? = null,
    ): TailscaleNetworkCandidate? {
        if (candidates.size == 1) return candidates.single()
        if (candidates.isEmpty()) return null
        return candidates.singleOrNull { it.networkHandle == currentNetworkHandle }
    }

    fun isCgnatAddress(host: String): Boolean {
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun isInTailscaleIpv6Range(host: String): Boolean {
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (bytes.size != 16) return false
        return (bytes[0].toInt() and 0xff) == 0xfd &&
            (bytes[1].toInt() and 0xff) == 0x7a &&
            (bytes[2].toInt() and 0xff) == 0x11 &&
            (bytes[3].toInt() and 0xff) == 0x5c &&
            (bytes[4].toInt() and 0xff) == 0xa1 &&
            (bytes[5].toInt() and 0xff) == 0xe0
    }

}
