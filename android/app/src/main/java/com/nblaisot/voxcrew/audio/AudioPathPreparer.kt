package com.nblaisot.voxcrew.audio

/**
 * VoIP prepare sequence shared by [com.nblaisot.voxcrew.lanlink.LanIntercomEngine].
 * [awaitRouteReady] only gates on permissions; playback/capture may start before the
 * platform confirms the Bluetooth communication device (Samsung unlocks BT after streams run).
 */
object AudioPathPreparer {
    enum class Step {
        ROUTE_READY,
        ROUTING_APPLIED,
        PLAYBACK_WARM_UP,
        CAPTURE_ATTACH,
    }

    data class Result(
        val completedSteps: List<Step>,
        val captureAttached: Boolean,
    )

    fun prepare(
        isSessionActive: Boolean,
        awaitRouteReady: () -> Boolean,
        awaitRoutingApplied: () -> Unit,
        warmUpPlayback: () -> Unit,
        detachCapture: () -> Unit,
        attachCapture: () -> Unit,
        audioPrepared: Boolean,
        forceReattach: Boolean,
    ): Result {
        if (!isSessionActive) {
            return Result(emptyList(), captureAttached = false)
        }
        val steps = mutableListOf<Step>()
        if (!awaitRouteReady()) {
            return Result(steps, captureAttached = false)
        }
        steps += Step.ROUTE_READY
        awaitRoutingApplied()
        steps += Step.ROUTING_APPLIED
        warmUpPlayback()
        steps += Step.PLAYBACK_WARM_UP
        if (audioPrepared && !forceReattach) {
            return Result(steps, captureAttached = false)
        }
        detachCapture()
        attachCapture()
        steps += Step.CAPTURE_ATTACH
        return Result(steps, captureAttached = true)
    }
}
