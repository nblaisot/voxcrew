package com.nblaisot.voxcrew.lanlink

/**
 * Controls whether newly discovered roster peers are auto-included as outbound recipients.
 */
object ActiveRecipientPolicy {
    /**
     * In opt-in mode, crew sync only updates the known peer set — active recipients stay unchanged.
     * In legacy mode, empty active set becomes all crew members and new peers are auto-added.
     */
    fun recipientsAfterCrewSync(
        currentActive: Set<String>,
        crewUids: Set<String>,
        previousKnownCrew: Set<String>,
        optInMode: Boolean,
    ): Set<String> {
        if (optInMode) return currentActive
        val newPeers = crewUids - previousKnownCrew
        var active = currentActive
        if (active.isEmpty() && crewUids.isNotEmpty()) {
            active = crewUids
        } else if (newPeers.isNotEmpty()) {
            active = active + newPeers
        }
        return active
    }
}
