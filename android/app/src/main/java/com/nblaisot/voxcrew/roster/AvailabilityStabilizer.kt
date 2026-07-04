package com.nblaisot.voxcrew.roster

internal class AvailabilityStabilizer(
    private val graceMs: Long = 20_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class OnlineRecord(val atMs: Long, val transportHint: String)

    private val lastOnline = mutableMapOf<String, OnlineRecord>()

    fun resolve(
        uid: String,
        rawOnline: Boolean,
        transportHint: String,
        lastSeenMs: Long?,
    ): MemberAvailability {
        val now = clock()
        if (rawOnline) {
            val at = maxOf(now, lastSeenMs ?: now)
            lastOnline[uid] = OnlineRecord(at, transportHint)
            return availabilityFor(transportHint)
        }
        val record = lastOnline[uid]
        val effectiveLastSeen = record?.atMs ?: 0L
        if (effectiveLastSeen > 0L && now - effectiveLastSeen < graceMs) {
            return availabilityFor(record?.transportHint ?: transportHint)
        }
        return MemberAvailability.OFFLINE
    }

    private fun availabilityFor(hint: String): MemberAvailability = when (hint) {
        "local_lan" -> MemberAvailability.ONLINE_LOCAL
        else -> MemberAvailability.ONLINE_CLOUD
    }
}
