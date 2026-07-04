package com.nblaisot.voxcrew.connectivity.local

import java.util.concurrent.ConcurrentHashMap

internal class LocalPresenceStore {
    data class Entry(
        val uid: String,
        val email: String,
        var transportHint: String,
        var online: Boolean,
        var lastSeenMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val staleMs = 30_000L

    fun register(uid: String, email: String, transportHint: String = "local_lan"): Entry {
        val now = System.currentTimeMillis()
        val entry = Entry(uid, email, transportHint, true, now)
        entries[uid] = entry
        return entry
    }

    fun heartbeat(uid: String, transportHint: String): Entry? {
        val entry = entries[uid] ?: return null
        entry.lastSeenMs = System.currentTimeMillis()
        entry.transportHint = transportHint
        entry.online = true
        return entry
    }

    fun markOffline(uid: String): Entry? {
        val entry = entries[uid] ?: return null
        entry.online = false
        entry.lastSeenMs = System.currentTimeMillis()
        return entry
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): List<Entry> {
        entries.values.forEach { entry ->
            if (entry.online && nowMs - entry.lastSeenMs > staleMs) {
                entry.online = false
            }
        }
        return entries.values.toList()
    }

    fun clear() = entries.clear()
}
