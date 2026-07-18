package com.nblaisot.voxcrew.roster

import android.content.Context
import com.nblaisot.voxcrew.lanlink.LanPeer
import com.nblaisot.voxcrew.signaling.PresenceMember
import com.nblaisot.voxcrew.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val signalingClient: SignalingClient?,
    private val lanPeers: StateFlow<List<LanPeer>>,
    private val scope: CoroutineScope,
    private val noBackend: Boolean = false,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _members = MutableStateFlow<List<CrewMember>>(emptyList())
    val members: StateFlow<List<CrewMember>> = _members.asStateFlow()

    private var activeRecipientUids: Set<String> = emptySet()
    private var localUid: String? = null
    private var localDisplayName: String? = null
    private var observeJob: Job? = null
    private val availabilityStabilizer = AvailabilityStabilizer()
    /** Soft-forgotten UIDs not re-written to disk while still visible via discovery. */
    private val skipCacheUids = mutableSetOf<String>()

    fun start(localUid: String, localDisplayName: String?) {
        observeJob?.cancel()
        this.localUid = localUid
        this.localDisplayName = localDisplayName
        observeJob = scope.launch {
            if (noBackend || signalingClient == null) {
                lanPeers.collect { peers ->
                    _members.value = mergeNoBackend(localUid, peers, activeRecipientUids)
                }
            } else {
                combine(signalingClient.presenceMembers, lanPeers) { cloudPresence, peers ->
                    mergeCloud(localUid, localDisplayName, cloudPresence, peers, activeRecipientUids)
                }.collect { _members.value = it }
            }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        localUid = null
        localDisplayName = null
        skipCacheUids.clear()
        _members.value = emptyList()
    }

    fun setActiveRecipients(uids: Set<String>) {
        activeRecipientUids = uids
        _members.update { list -> list.map { it.copy(isActiveRecipient = it.uid in uids) } }
    }

    /**
     * Soft forget: drop [uid] from the persisted roster. Peers still on the network
     * remain visible (muted by the engine); they vanish once discovery loses them and
     * can reappear later as a fresh discovery.
     */
    fun forgetMember(uid: String) {
        val cache = loadCache().toMutableMap()
        cache.remove(uid)
        saveCache(cache)
        skipCacheUids.add(uid)
        rematchNow()
    }

    private fun rematchNow() {
        val uid = localUid ?: return
        _members.value = if (noBackend || signalingClient == null) {
            mergeNoBackend(uid, lanPeers.value, activeRecipientUids)
        } else {
            mergeCloud(
                localUid = uid,
                localDisplayName = localDisplayName,
                presence = signalingClient.presenceMembers.value,
                lanPeers = lanPeers.value,
                activeUids = activeRecipientUids,
            )
        }
    }

    private fun mergeNoBackend(
        localUid: String,
        lanPeers: List<LanPeer>,
        activeUids: Set<String>,
    ): List<CrewMember> {
        val cache = loadCache().toMutableMap()
        val byUid = linkedMapOf<String, CrewMember>()
        val liveUids = lanPeers.map { it.uid }.toSet()
        skipCacheUids.retainAll(SoftForgetPolicy.skipCacheAfterTick(skipCacheUids, liveUids))

        lanPeers.filter { it.uid != localUid }.forEach { peer ->
            if (SoftForgetPolicy.shouldPersistToCache(peer.uid, skipCacheUids)) {
                cache[peer.uid] = CachedMember(peer.uid, peer.displayName, peer.lastSeenMs, peer.overlayHost)
            }
            byUid[peer.uid] = CrewMember(
                uid = peer.uid,
                displayName = peer.displayName,
                availability = if (peer.viaOverlay) {
                    MemberAvailability.ONLINE_OVERLAY
                } else {
                    MemberAvailability.ONLINE_LOCAL
                },
                lastSeenMs = peer.lastSeenMs,
                isActiveRecipient = peer.uid in activeUids,
            )
        }

        cache.values.forEach { cached ->
            if (cached.uid == localUid) return@forEach
            if (byUid.containsKey(cached.uid)) return@forEach
            byUid[cached.uid] = CrewMember(
                uid = cached.uid,
                displayName = cached.displayName,
                availability = MemberAvailability.OFFLINE,
                lastSeenMs = cached.lastSeenMs,
                isActiveRecipient = cached.uid in activeUids,
            )
        }

        saveCache(cache)
        return byUid.values
            .filter { it.uid != localUid }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun mergeCloud(
        localUid: String,
        localDisplayName: String?,
        presence: List<PresenceMember>,
        lanPeers: List<LanPeer>,
        activeUids: Set<String>,
    ): List<CrewMember> {
        val cache = loadCache().toMutableMap()
        val byUid = linkedMapOf<String, CrewMember>()
        val liveUids = (presence.map { it.uid } + lanPeers.map { it.uid }).toSet()
        skipCacheUids.retainAll(SoftForgetPolicy.skipCacheAfterTick(skipCacheUids, liveUids))

        presence.forEach { p ->
            val name = p.email.ifBlank { p.uid }
            if (SoftForgetPolicy.shouldPersistToCache(p.uid, skipCacheUids)) {
                cache[p.uid] = CachedMember(p.uid, name, p.lastSeenMs, overlayHost = null)
            }
            val availability = availabilityStabilizer.resolve(
                uid = p.uid,
                rawOnline = p.online,
                transportHint = p.transportHint,
                lastSeenMs = p.lastSeenMs,
            )
            byUid[p.uid] = CrewMember(
                uid = p.uid,
                displayName = name,
                email = p.email,
                availability = availability,
                lastSeenMs = p.lastSeenMs,
                isSelf = p.uid == localUid,
                isActiveRecipient = p.uid in activeUids,
            )
        }

        lanPeers.filter { it.uid != localUid }.forEach { peer ->
            val displayName = byUid[peer.uid]?.displayName?.takeIf { it.isNotBlank() } ?: peer.displayName
            if (SoftForgetPolicy.shouldPersistToCache(peer.uid, skipCacheUids)) {
                cache[peer.uid] = CachedMember(peer.uid, displayName, peer.lastSeenMs, peer.overlayHost)
            }
            byUid[peer.uid] = CrewMember(
                uid = peer.uid,
                displayName = displayName,
                email = byUid[peer.uid]?.email,
                availability = MemberAvailability.ONLINE_LOCAL,
                lastSeenMs = peer.lastSeenMs,
                isActiveRecipient = peer.uid in activeUids,
            )
        }

        cache.values.forEach { cached ->
            if (cached.uid == localUid) return@forEach
            if (byUid.containsKey(cached.uid)) return@forEach
            byUid[cached.uid] = CrewMember(
                uid = cached.uid,
                displayName = cached.displayName,
                availability = MemberAvailability.OFFLINE,
                lastSeenMs = cached.lastSeenMs,
                isActiveRecipient = cached.uid in activeUids,
            )
        }

        saveCache(cache)
        return byUid.values
            .filter { it.uid != localUid }
            .sortedBy { it.displayName.lowercase() }
    }

    @Serializable
    private data class CachedMember(
        val uid: String,
        val displayName: String,
        val lastSeenMs: Long? = null,
        val overlayHost: String? = null,
    )

    private fun loadCache(): Map<String, CachedMember> = runCatching {
        val raw = prefs.getString(KEY_CACHE, null) ?: return emptyMap()
        json.decodeFromString<List<CachedMember>>(raw).associateBy { it.uid }
    }.getOrElse { emptyMap() }

    private fun saveCache(cache: Map<String, CachedMember>) {
        prefs.edit().putString(KEY_CACHE, json.encodeToString(cache.values.toList())).apply()
    }

    companion object {
        private const val PREFS = "voxcrew_roster"
        private const val KEY_CACHE = "seen_members_v2"
    }
}
