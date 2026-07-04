package com.nblaisot.voxcrew.connectivity.webrtc

enum class GlareAction {
    ANSWER,
    IGNORE,
}

enum class NegotiationPhase {
    IDLE,
    OFFER_IN_FLIGHT,
    HAVE_LOCAL_OFFER,
    ANSWERING,
}

class WebRtcNegotiationGuard(
    private val isInitiator: Boolean,
    private val staleAfterMs: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var phase = NegotiationPhase.IDLE
    private var phaseChangedAtMs = 0L

    /**
     * A negotiation stuck in a non-idle phase (offer sent but answer lost, peer
     * restarted, signaling dropped, ...) must not block renegotiation forever.
     */
    private fun effectivePhase(): NegotiationPhase {
        if (phase != NegotiationPhase.IDLE && clock() - phaseChangedAtMs >= staleAfterMs) {
            phase = NegotiationPhase.IDLE
        }
        return phase
    }

    private fun setPhase(newPhase: NegotiationPhase) {
        phase = newPhase
        phaseChangedAtMs = clock()
    }

    fun canStartOffer(): Boolean = effectivePhase() == NegotiationPhase.IDLE && isInitiator

    fun onOfferStarted() = setPhase(NegotiationPhase.OFFER_IN_FLIGHT)

    fun onOfferSent() = setPhase(NegotiationPhase.HAVE_LOCAL_OFFER)

    fun onAnswerStarted() = setPhase(NegotiationPhase.ANSWERING)

    fun onNegotiationComplete() = setPhase(NegotiationPhase.IDLE)

    fun reset() = setPhase(NegotiationPhase.IDLE)

    fun handleIncomingOffer(): GlareAction {
        return when (effectivePhase()) {
            NegotiationPhase.OFFER_IN_FLIGHT, NegotiationPhase.HAVE_LOCAL_OFFER ->
                if (isInitiator) GlareAction.IGNORE else GlareAction.ANSWER
            else -> GlareAction.ANSWER
        }
    }
}
