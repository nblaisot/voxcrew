package com.nblaisot.voxcrew.ui.main

import com.nblaisot.voxcrew.audio.ManualRouteStatus

/**
 * Single source of truth for PTT affordance and label. Never show "hold to talk"
 * unless [Ready] (and optionally transmitting).
 */
sealed interface PttBlockReason {
    data object Ready : PttBlockReason
    data object VoxMode : PttBlockReason
    data object Pending : PttBlockReason
    data object NoMic : PttBlockReason
    data object Background : PttBlockReason
    data object Diverged : PttBlockReason
    data object Failed : PttBlockReason
    data object NoRecipient : PttBlockReason
    data object NoLink : PttBlockReason
}

internal fun resolvePttBlockReason(
    voxEnabled: Boolean,
    appForeground: Boolean,
    micPermissionGranted: Boolean,
    audioRouteReady: Boolean,
    audioStartAllowed: Boolean,
    audioRoutePending: Boolean,
    audioRouteStatus: ManualRouteStatus,
    showAudioRetry: Boolean,
    hasActiveRecipient: Boolean,
    hasConnectedRecipient: Boolean,
    isTransmitting: Boolean,
): PttBlockReason = when {
    voxEnabled -> PttBlockReason.VoxMode
    !micPermissionGranted -> PttBlockReason.NoMic
    !appForeground -> PttBlockReason.Background
    showAudioRetry || !audioStartAllowed -> PttBlockReason.Failed
    audioRouteStatus == ManualRouteStatus.DIVERGED ||
        audioRouteStatus == ManualRouteStatus.UNAVAILABLE ||
        audioRouteStatus == ManualRouteStatus.FAILED -> PttBlockReason.Diverged
    audioRoutePending || !audioRouteReady -> PttBlockReason.Pending
    !hasActiveRecipient -> PttBlockReason.NoRecipient
    isTransmitting && !hasConnectedRecipient -> PttBlockReason.NoLink
    else -> PttBlockReason.Ready
}

internal fun pttEnabledForReason(reason: PttBlockReason): Boolean =
    reason == PttBlockReason.Ready || reason == PttBlockReason.NoLink
