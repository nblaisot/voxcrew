package com.nblaisot.voxcrew.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleNetworkResolverTest {
    @Test
    fun `fold 5 selects Tailscale tun1 and rejects Zscaler tun0`() {
        val zscaler = description(
            handle = 155L,
            iface = "tun0",
            links = listOf(ipv4("100.64.0.1", 16), ipv6("fc00::6440:1", 112)),
            routes = listOf(ipv4("0.0.0.0", 1)),
            dns = setOf("100.64.0.2"),
        )
        val tailscale = description(
            handle = 610L,
            iface = "tun1",
            links = listOf(
                ipv4("100.107.118.93", 32),
                ipv6("fd7a:115c:a1e0::7a33:765e", 128),
            ),
            routes = listOf(
                ipv4("100.65.176.46", 32),
                ipv4("100.100.100.100", 32),
                ipv6("fd7a:115c:a1e0::", 48),
            ),
            dns = setOf("100.100.100.100"),
            domains = setOf("tailc102f6.ts.net", "lan"),
        )

        val snapshot = semanticConnectivitySnapshot(
            lanProperties = emptyList(),
            vpnProperties = listOf(zscaler, tailscale),
        )

        assertEquals("100.107.118.93", snapshot.overlayNetwork?.ipv4Address)
        assertEquals(610L, snapshot.overlayNetwork?.networkHandle)
    }

    @Test
    fun `cgnat slash 32 without Tailscale evidence is rejected`() {
        val corporate = description(
            handle = 12L,
            iface = "tun0",
            links = listOf(ipv4("100.90.1.2", 32)),
            routes = listOf(ipv4("0.0.0.0", 0)),
        )

        assertNull(TailscaleNetworkResolver.candidate(corporate))
    }

    @Test
    fun `ambiguous verified overlays do not select an arbitrary interface`() {
        val first = verifiedTailscale(1L, "tun0", "100.80.0.1")
        val second = verifiedTailscale(2L, "tun1", "100.81.0.1")

        assertNull(
            semanticConnectivitySnapshot(emptyList(), listOf(first, second)).overlayNetwork,
        )
        assertEquals(
            2L,
            semanticConnectivitySnapshot(
                emptyList(),
                listOf(first, second),
                currentOverlayHandle = 2L,
            ).overlayNetwork?.networkHandle,
        )
    }

    @Test
    fun `semantic LAN state ignores IPv6 DNS and route churn`() {
        val before = description(
            handle = 609L,
            iface = "wlan0",
            links = listOf(ipv4("192.168.86.213", 24), ipv6("fe80::1", 64)),
            dns = setOf("192.168.86.1"),
        )
        val after = description(
            handle = 609L,
            iface = "wlan0",
            links = listOf(
                ipv4("192.168.86.213", 24),
                ipv6("fe80::1", 64),
                ipv6("fd41:c1d9:9a2b:4084::123", 64),
            ),
            routes = listOf(ipv6("fd41:c1d9:9a2b:4084::", 64)),
            dns = setOf("1.1.1.1"),
        )

        assertEquals(
            semanticConnectivitySnapshot(listOf(before), emptyList()),
            semanticConnectivitySnapshot(listOf(after), emptyList()),
        )
    }

    @Test
    fun `captured WiFi callback sequence produces one usable LAN transition`() {
        val callbacks = listOf(
            description(609L, "wlan0", emptyList()),
            description(609L, "wlan0", listOf(ipv6("fe80::1", 64))),
            description(
                609L,
                "wlan0",
                listOf(ipv6("fe80::1", 64), ipv4("192.168.86.213", 24)),
            ),
            description(
                609L,
                "wlan0",
                listOf(
                    ipv6("fe80::1", 64),
                    ipv4("192.168.86.213", 24),
                    ipv6("fd41:c1d9:9a2b:4084::123", 64),
                ),
            ),
            description(
                609L,
                "wlan0",
                listOf(
                    ipv6("fe80::1", 64),
                    ipv4("192.168.86.213", 24),
                    ipv6("fd41:c1d9:9a2b:4084::123", 64),
                    ipv6("fd41:c1d9:9a2b:4084::456", 64),
                ),
            ),
        )

        val distinct = callbacks
            .map { semanticConnectivitySnapshot(listOf(it), emptyList()) }
            .distinct()

        assertEquals(2, distinct.size)
        assertTrue(distinct.first().lanNetworks.isEmpty())
        assertEquals("192.168.86.213", distinct.last().lanNetworks.single().ipv4Links.single().address)
    }

    @Test
    fun `cgnat range helper is exact`() {
        assertTrue(TailscaleNetworkResolver.isCgnatAddress("100.64.0.1"))
        assertTrue(TailscaleNetworkResolver.isCgnatAddress("100.127.255.254"))
    }

    private fun verifiedTailscale(handle: Long, iface: String, host: String) = description(
        handle = handle,
        iface = iface,
        links = listOf(ipv4(host, 32), ipv6("fd7a:115c:a1e0::$handle", 128)),
    )

    private fun description(
        handle: Long,
        iface: String,
        links: List<IpAddressDescription>,
        routes: List<IpAddressDescription> = emptyList(),
        dns: Set<String> = emptySet(),
        domains: Set<String> = emptySet(),
    ) = LinkPropertiesDescription(handle, iface, links, routes, dns, domains)

    private fun ipv4(host: String, prefix: Int) =
        IpAddressDescription(host, prefix, IpFamily.IPV4)

    private fun ipv6(host: String, prefix: Int) =
        IpAddressDescription(host, prefix, IpFamily.IPV6)
}
