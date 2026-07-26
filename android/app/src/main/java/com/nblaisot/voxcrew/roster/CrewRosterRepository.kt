package com.nblaisot.voxcrew.roster

import android.content.Context
import com.nblaisot.voxcrew.lanlink.LanPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * UUID-keyed roster. Discovery supplies transient endpoints; disk stores only the UUID and last
 * display name required to render an intelligible offline row after restart.
 */
class CrewRosterRepository(
    context: Context,
    private val lanPeers: StateFlow<List<LanPeer>>,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _members = MutableStateFlow<List<CrewMember>>(emptyList())
    val members: StateFlow<List<CrewMember>> = _members.asStateFlow()

    private var activeRecipientUids: Set<String> = emptySet()
    private var localUid: String? = null
    private var observeJob: Job? = null
    private var knownPeers: Map<String, String> = loadOrMigrateKnownPeers()

    fun start(localUid: String, localDisplayName: String?) {
        observeJob?.cancel()
        this.localUid = localUid
        observeJob = scope.launch {
            lanPeers.collect { peers ->
                rematch(localUid, peers)
            }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        localUid = null
        _members.value = emptyList()
    }

    fun setActiveRecipients(uids: Set<String>) {
        activeRecipientUids = uids
        _members.update { list -> list.map { it.copy(isActiveRecipient = it.uid in uids) } }
    }

    fun forgetMember(uid: String) {
        knownPeers = knownPeers - uid
        saveKnownPeers(knownPeers)
        rematchNow()
    }

    private fun rematchNow() {
        val uid = localUid ?: return
        rematch(uid, lanPeers.value)
    }

    private fun rematch(localUid: String, peers: List<LanPeer>) {
        val merged = mergeKnownPeers(
            localUid = localUid,
            knownPeers = knownPeers,
            livePeers = peers,
            activeUids = activeRecipientUids,
        )
        if (merged.knownPeers != knownPeers) {
            knownPeers = merged.knownPeers
            saveKnownPeers(knownPeers)
        }
        _members.value = merged.members
    }

    private fun loadOrMigrateKnownPeers(): Map<String, String> {
        val stored = prefs.getString(KEY_KNOWN_PEERS, null)?.let(::decodeKnownPeers)
        if (stored != null) {
            prefs.edit().remove(LEGACY_KEY_CACHE).apply()
            return stored
        }
        val migrated = decodeLegacyKnownPeers(prefs.getString(LEGACY_KEY_CACHE, null), json)
        saveKnownPeers(migrated)
        prefs.edit().remove(LEGACY_KEY_CACHE).apply()
        return migrated
    }

    private fun decodeKnownPeers(raw: String): Map<String, String>? = runCatching {
        json.decodeFromString<List<KnownPeer>>(raw)
            .filter { it.uid.isNotBlank() && it.displayName.isNotBlank() }
            .associate { it.uid to it.displayName }
    }.getOrNull()

    private fun saveKnownPeers(peers: Map<String, String>) {
        val stored = peers.map { (uid, displayName) -> KnownPeer(uid, displayName) }
        prefs.edit().putString(KEY_KNOWN_PEERS, json.encodeToString(stored)).apply()
    }

    @Serializable
    private data class KnownPeer(
        val uid: String,
        val displayName: String,
    )

    companion object {
        private const val PREFS = "voxcrew_roster"
        private const val KEY_KNOWN_PEERS = "known_peer_names_v1"
        private const val LEGACY_KEY_CACHE = "seen_members_v2"
    }
}

internal data class KnownPeerMerge(
    val knownPeers: Map<String, String>,
    val members: List<CrewMember>,
)

internal fun mergeKnownPeers(
    localUid: String,
    knownPeers: Map<String, String>,
    livePeers: List<LanPeer>,
    activeUids: Set<String>,
): KnownPeerMerge {
    val liveByUid = livePeers
        .asSequence()
        .filter { it.uid.isNotBlank() && it.uid != localUid }
        .associateBy { it.uid }
    val updatedKnown = knownPeers
        .filterKeys { it != localUid }
        .toMutableMap()
        .apply {
            liveByUid.forEach { (uid, peer) -> this[uid] = peer.displayName }
        }
    val members = updatedKnown.map { (uid, storedName) ->
        val live = liveByUid[uid]
        CrewMember(
            uid = uid,
            displayName = live?.displayName ?: storedName,
            availability = when {
                live == null -> MemberAvailability.OFFLINE
                live.viaOverlay -> MemberAvailability.ONLINE_OVERLAY
                else -> MemberAvailability.ONLINE_LOCAL
            },
            lastSeenMs = live?.lastSeenMs,
            isActiveRecipient = uid in activeUids,
        )
    }.sortedBy { it.displayName.lowercase() }
    return KnownPeerMerge(updatedKnown, members)
}

@Serializable
private data class LegacyCachedMember(
    val uid: String,
    val displayName: String,
)

internal fun decodeLegacyKnownPeers(raw: String?, json: Json): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        json.decodeFromString<List<LegacyCachedMember>>(raw)
            .filter { it.uid.isNotBlank() && it.displayName.isNotBlank() }
            .associate { it.uid to it.displayName }
    }.getOrElse { emptyMap() }
}
