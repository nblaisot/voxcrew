package com.nblaisot.voxcrew.lanlink

/** Pure event-driven demand state; it contains no clock or retry policy. */
internal class MediaDemandState {
    private var sessionActive = false
    private var appForeground = false
    private var voxEnabled = false
    private var microphonePermissionGranted = false
    private var pipelineUsable = true
    private var outbound = false
    private val remotePeers = mutableSetOf<String>()

    @Synchronized
    fun setSessionActive(active: Boolean): Boolean {
        if (sessionActive == active) return false
        sessionActive = active
        return true
    }

    @Synchronized
    fun setAppForeground(foreground: Boolean): Boolean {
        if (appForeground == foreground) return false
        appForeground = foreground
        return true
    }

    @Synchronized
    fun setVoxEnabled(enabled: Boolean): Boolean {
        if (voxEnabled == enabled) return false
        voxEnabled = enabled
        return true
    }

    @Synchronized
    fun setMicrophonePermissionGranted(granted: Boolean): Boolean {
        if (microphonePermissionGranted == granted) return false
        microphonePermissionGranted = granted
        return true
    }

    @Synchronized
    fun setPipelineUsable(usable: Boolean): Boolean {
        if (pipelineUsable == usable) return false
        pipelineUsable = usable
        return true
    }

    @Synchronized
    fun setOutbound(active: Boolean): Boolean {
        if (outbound == active) return false
        outbound = active
        return true
    }

    @Synchronized
    fun setRemote(peerUid: String, active: Boolean): Boolean =
        if (active) remotePeers.add(peerUid) else remotePeers.remove(peerUid)

    @Synchronized
    fun isDemanded(): Boolean = sessionActive &&
        microphonePermissionGranted &&
        pipelineUsable &&
        (voxEnabled || appForeground || remotePeers.isNotEmpty())

    @Synchronized
    fun isOutbound(): Boolean = outbound

    @Synchronized
    fun endSession() {
        sessionActive = false
        outbound = false
        remotePeers.clear()
        pipelineUsable = true
    }
}

internal enum class TelecomDemandAction {
    NONE,
    ACTIVATE,
    DISCONNECT,
}

/** The intercom has no hold state: zero media demand always ends an existing call. */
internal fun telecomDemandAction(
    demanded: Boolean,
    isActive: Boolean,
    hasCall: Boolean,
): TelecomDemandAction = when {
    demanded && !isActive -> TelecomDemandAction.ACTIVATE
    !demanded && hasCall -> TelecomDemandAction.DISCONNECT
    else -> TelecomDemandAction.NONE
}
