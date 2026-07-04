package com.nblaisot.voxcrew.roster

import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityStabilizerTest {
    private var nowMs = 1_000_000L

    private fun stabilizer(graceMs: Long = 20_000L) = AvailabilityStabilizer(
        graceMs = graceMs,
        clock = { nowMs },
    )

    @Test
    fun online_mapsTransportHint() {
        val stabilizer = stabilizer()
        assertEquals(
            MemberAvailability.ONLINE_CLOUD,
            stabilizer.resolve("u1", rawOnline = true, transportHint = "cloud", lastSeenMs = nowMs),
        )
        assertEquals(
            MemberAvailability.ONLINE_LOCAL,
            stabilizer.resolve("u2", rawOnline = true, transportHint = "local_lan", lastSeenMs = nowMs),
        )
    }

    @Test
    fun transientOffline_staysOnlineWithinGrace() {
        val stabilizer = stabilizer()
        stabilizer.resolve("u1", rawOnline = true, transportHint = "cloud", lastSeenMs = nowMs)
        nowMs += 5_000
        assertEquals(
            MemberAvailability.ONLINE_CLOUD,
            stabilizer.resolve("u1", rawOnline = false, transportHint = "cloud", lastSeenMs = nowMs),
        )
    }

    @Test
    fun offline_afterGraceExpires() {
        val stabilizer = stabilizer()
        stabilizer.resolve("u1", rawOnline = true, transportHint = "cloud", lastSeenMs = nowMs)
        nowMs += 21_000
        assertEquals(
            MemberAvailability.OFFLINE,
            stabilizer.resolve("u1", rawOnline = false, transportHint = "cloud", lastSeenMs = nowMs),
        )
    }

    @Test
    fun recentLastSeen_keepsOnlineDuringGrace() {
        val stabilizer = stabilizer()
        stabilizer.resolve("u1", rawOnline = true, transportHint = "cloud", lastSeenMs = nowMs)
        nowMs += 10_000
        assertEquals(
            MemberAvailability.ONLINE_CLOUD,
            stabilizer.resolve("u1", rawOnline = false, transportHint = "cloud", lastSeenMs = nowMs),
        )
    }
}
