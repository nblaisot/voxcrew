package com.nblaisot.voxcrew.roster

/**
 * Soft forget: drop a peer from the remembered roster. Live nearby peers can still
 * appear from discovery; they are not re-cached until they leave and return.
 */
object SoftForgetPolicy {
    /** UIDs that should appear after forgetting [forgottenUid]. */
    fun visibleUids(
        cachedUids: Set<String>,
        livePeerUids: Set<String>,
        forgottenUid: String,
    ): Set<String> = (cachedUids - forgottenUid) + livePeerUids

    /**
     * After a soft forget, skip writing [forgottenUid] to disk while it remains live.
     * Once absent from live peers, clear the skip so a later rediscovery can cache again.
     */
    fun skipCacheAfterTick(
        previousSkip: Set<String>,
        livePeerUids: Set<String>,
    ): Set<String> = previousSkip.intersect(livePeerUids)

    fun shouldPersistToCache(uid: String, skipCacheUids: Set<String>): Boolean =
        uid !in skipCacheUids
}
