package com.nblaisot.voxcrew.roster

import android.content.Context
import com.nblaisot.voxcrew.lanlink.LanPeer
import com.nblaisot.voxcrew.signaling.PresenceMember
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local (LAN) peers are merged directly from [LanPeer] discovery — no dependence on
 * cloud presence or on any transport being "active" for visibility. As soon as two
 * devices see each other's beacon they show up as [MemberAvailability.ONLINE_LOCAL],
 * independent of whether a conversation is currently connected.
 */
class CrewRosterRepository(
    context: Context,
    private val signalingClient: SignalingClient,
    private val lanPeers: StateFlow<List<LanPeer>>,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _members = MutableStateFlow<List<CrewMember>>(emptyList())
    val members: StateFlow<List<CrewMember>> = _members.asStateFlow()

    private var selectedUid: String? = null
    private var localUid: String? = null
    private var localEmail: String? = null
    private val availabilityStabilizer = AvailabilityStabilizer()

    fun start(localUid: String, localEmail: String?) {
        this.localUid = localUid
        this.localEmail = localEmail
        scope.launch {
            combine(signalingClient.presenceMembers, lanPeers) { cloudPresence, peers ->
                merge(localUid, localEmail, cloudPresence, peers, selectedUid)
            }.collect { _members.value = it }
        }
    }

    fun select(uid: String?) {
        selectedUid = uid
        _members.update { list -> list.map { it.copy(isSelected = it.uid == uid) } }
    }

    private fun merge(
        localUid: String,
        localEmail: String?,
        presence: List<PresenceMember>,
        lanPeers: List<LanPeer>,
        selectedUid: String?,
    ): List<CrewMember> {
        val cache = loadCache().toMutableMap()
        val byUid = linkedMapOf<String, CrewMember>()

        presence.forEach { p ->
            cache[p.uid] = CachedMember(p.uid, p.email, p.lastSeenMs)
            val availability = availabilityStabilizer.resolve(
                uid = p.uid,
                rawOnline = p.online,
                transportHint = p.transportHint,
                lastSeenMs = p.lastSeenMs,
            )
            byUid[p.uid] = CrewMember(
                uid = p.uid,
                email = p.email,
                availability = availability,
                lastSeenMs = p.lastSeenMs,
                isSelf = p.uid == localUid,
                isSelected = p.uid == selectedUid,
            )
        }

        lanPeers.filter { it.uid != localUid }.forEach { peer ->
            val email = byUid[peer.uid]?.email?.takeIf { it.isNotBlank() } ?: peer.displayName
            cache[peer.uid] = CachedMember(peer.uid, email, peer.lastSeenMs)
            byUid[peer.uid] = CrewMember(
                uid = peer.uid,
                email = email,
                availability = MemberAvailability.ONLINE_LOCAL,
                lastSeenMs = peer.lastSeenMs,
                isSelected = peer.uid == selectedUid,
            )
        }

        cache.values.forEach { cached ->
            if (cached.uid == localUid) return@forEach
            if (byUid.containsKey(cached.uid)) return@forEach
            byUid[cached.uid] = CrewMember(
                uid = cached.uid,
                email = cached.email,
                availability = MemberAvailability.OFFLINE,
                lastSeenMs = cached.lastSeenMs,
                isSelected = cached.uid == selectedUid,
            )
        }

        saveCache(cache)
        return byUid.values
            .filter { it.uid != localUid }
            .sortedBy { it.email.lowercase() }
    }

    @Serializable
    private data class CachedMember(val uid: String, val email: String, val lastSeenMs: Long?)

    private fun loadCache(): Map<String, CachedMember> = runCatching {
        val raw = prefs.getString(KEY_CACHE, null) ?: return emptyMap()
        json.decodeFromString<List<CachedMember>>(raw).associateBy { it.uid }
    }.getOrElse { emptyMap() }

    private fun saveCache(cache: Map<String, CachedMember>) {
        prefs.edit().putString(KEY_CACHE, json.encodeToString(cache.values.toList())).apply()
    }

    companion object {
        private const val PREFS = "voxcrew_roster"
        private const val KEY_CACHE = "seen_members"
    }
}
