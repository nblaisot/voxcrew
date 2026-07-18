package com.nblaisot.voxcrew.lanlink

/**
 * Chooses whether inbound Opus plays through the Telecom voice-call path or a
 * standalone multimedia [MediaInboundPlayer].
 */
enum class InboundPlaybackMode {
    /** Duplex Telecom / STREAM_VOICE_CALL (foreground PTT or VOX). */
    TELECOM,

    /** Background + VOX off: USAGE_MEDIA with transient audio focus. */
    MEDIA,
}

object InboundPlaybackPolicy {
    fun mode(appForeground: Boolean, voxEnabled: Boolean): InboundPlaybackMode =
        if (!appForeground && !voxEnabled) InboundPlaybackMode.MEDIA else InboundPlaybackMode.TELECOM
}
