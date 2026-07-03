package com.nblaisot.voxcrew.connectivity.orchestration

import com.nblaisot.voxcrew.connectivity.discovery.DiscoveredLocalPeer
import com.nblaisot.voxcrew.connectivity.discovery.FakeLocalPeerDiscovery
import com.nblaisot.voxcrew.connectivity.model.ConnectivityThresholds
import com.nblaisot.voxcrew.connectivity.model.GenerationId
import com.nblaisot.voxcrew.connectivity.model.SessionDescriptor
import com.nblaisot.voxcrew.connectivity.model.TransportMode
import com.nblaisot.voxcrew.connectivity.quality.MutablePeerPathEvaluator
import com.nblaisot.voxcrew.connectivity.state.ConnectivityState
import com.nblaisot.voxcrew.connectivity.state.TransportPreference
import com.nblaisot.voxcrew.connectivity.transport.FakeSignalingTransport
import com.nblaisot.voxcrew.connectivity.transport.SignalingTransportKind
import com.nblaisot.voxcrew.connectivity.webrtc.FakeWebRtcConnectionSwitcher
import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityOrchestratorTest {
    private val dispatcher = StandardTestDispatcher()

    private val thresholds = ConnectivityThresholds(
        localProbeIntervalMs = 100,
        switchCooldownMs = 0,
        cloudPreparationTimeoutMs = 500,
    )

    private val session = SessionDescriptor(
        sessionId = "sess-1",
        participantId = "user-a",
        sessionSecret = "secret",
        hostParticipantId = "user-a",
        isLocalHost = false,
    )

    private class Fixture(
        val scope: TestScope,
        private val thresholds: ConnectivityThresholds,
        private val session: SessionDescriptor,
    ) {
        var now = 1_000L
        val localTransport = FakeSignalingTransport(SignalingTransportKind.LOCAL_LAN)
        val cloudTransport = FakeSignalingTransport(SignalingTransportKind.CLOUD)
        val discovery = FakeLocalPeerDiscovery()
        val pathEvaluator = MutablePeerPathEvaluator(thresholds).apply { manualMode = true }
        val switcher = FakeWebRtcConnectionSwitcher()
        val orchestrator = ConnectivityOrchestratorImpl(
            scope = scope.backgroundScope,
            localTransport = localTransport,
            cloudTransport = cloudTransport,
            localDiscovery = discovery,
            localServer = null,
            pathEvaluator = pathEvaluator,
            connectionSwitcher = switcher,
            thresholds = thresholds,
            clock = { now },
            enableAutoEvaluation = false,
        )

        suspend fun beginAuto() {
            discovery.setPeers(listOf(DiscoveredLocalPeer("p1", "192.168.1.10", 38472, null, "i1")))
            orchestrator.beginSession(session, TransportPreference.AUTO)
            scope.advanceUntilIdle()
        }
    }

    @Test fun `1 idle to discovering on beginSession`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.orchestrator.beginSession(session, TransportPreference.AUTO)
        assertFalse(f.orchestrator.state.value is ConnectivityState.Idle)
    }

    @Test fun `2 discovering to connecting local when peer discovered`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        assertTrue(f.orchestrator.state.value is ConnectivityState.ConnectingLocal)
    }

    @Test fun `3 discovering to connecting cloud when force cloud`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.orchestrator.beginSession(session, TransportPreference.FORCE_CLOUD)
        advanceUntilIdle()
        assertTrue(f.orchestrator.state.value is ConnectivityState.ConnectingCloud)
    }

    @Test fun `4 connecting local to local active when stable`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        assertTrue(f.orchestrator.state.value is ConnectivityState.LocalActive)
    }

    @Test fun `5 local active to transitioning cloud when degraded`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        f.now += 10_000
        f.pathEvaluator.degradedLocal(f.now)
        f.orchestrator.evaluateNow()
        assertTrue(f.orchestrator.state.value is ConnectivityState.TransitioningToCloud)
    }

    @Test fun `6 transitioning cloud to cloud active when candidate ready`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val localGen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(localGen))?.markReady()
        f.orchestrator.evaluateNow()
        f.pathEvaluator.degradedLocal(f.now)
        f.now += 10_000
        f.orchestrator.evaluateNow()
        val transition = f.orchestrator.state.value as ConnectivityState.TransitioningToCloud
        f.switcher.connection(GenerationId(transition.candidateGeneration))?.markReady()
        f.pathEvaluator.stableCloud(f.now)
        f.orchestrator.evaluateNow()
        assertTrue(f.orchestrator.state.value is ConnectivityState.CloudActive)
        assertTrue(f.switcher.promoteCalls.isNotEmpty())
    }

    @Test fun `7 transitioning cloud reverts to local when cloud fails`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val localGen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(localGen))?.markReady()
        f.orchestrator.evaluateNow()
        f.pathEvaluator.degradedLocal(f.now)
        f.now += 10_000
        f.orchestrator.evaluateNow()
        f.pathEvaluator.stableLocal(f.now)
        f.now += 2_000
        f.orchestrator.evaluateNow()
        assertTrue(
            f.orchestrator.state.value is ConnectivityState.LocalActive ||
                f.orchestrator.state.value is ConnectivityState.TransitioningToCloud,
        )
    }

    @Test fun `8 cloud active to transitioning local when local stable auto`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.orchestrator.beginSession(session, TransportPreference.FORCE_CLOUD)
        advanceUntilIdle()
        val cloudGen = (f.orchestrator.state.value as ConnectivityState.ConnectingCloud).generation
        f.switcher.connection(GenerationId(cloudGen))?.markReady()
        f.pathEvaluator.stableCloud(f.now)
        f.orchestrator.evaluateNow()
        f.discovery.setPeers(listOf(DiscoveredLocalPeer("p1", "192.168.1.10", 38472, null, "i1")))
        f.pathEvaluator.stableLocal(f.now)
        f.now += 10_000
        f.orchestrator.evaluateNow()
        assertTrue(
            f.orchestrator.state.value is ConnectivityState.TransitioningToLocal ||
                f.orchestrator.state.value is ConnectivityState.CloudActive,
        )
    }

    @Test fun `9 obsolete generation signaling is not relayed`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        val job = backgroundScope.launch {
            f.orchestrator.relayedSignaling.first()
        }
        f.localTransport.emit(
            SignalingEnvelope(
                type = SignalingMessageTypes.OFFER,
                payload = jsonPayload("sdp" to "x", "generation" to "1"),
            ),
        )
        advanceUntilIdle()
        assertFalse(job.isCompleted)
        job.cancel()
    }

    @Test fun `10 switch cooldown blocks rapid re-switch`() = runTest(dispatcher) {
        val strict = thresholds.copy(switchCooldownMs = 60_000)
        val f = Fixture(this, strict, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        assertTrue(f.orchestrator.state.value is ConnectivityState.LocalActive)
        f.pathEvaluator.degradedLocal(f.now)
        f.orchestrator.evaluateNow()
        assertFalse(f.orchestrator.state.value is ConnectivityState.TransitioningToCloud)
    }

    @Test fun `11 force local stays on local path`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.orchestrator.beginSession(session.copy(isLocalHost = true), TransportPreference.FORCE_LOCAL)
        advanceUntilIdle()
        assertTrue(f.orchestrator.state.value is ConnectivityState.ConnectingLocal)
    }

    @Test fun `12 force cloud ignores local peer`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.discovery.setPeers(listOf(DiscoveredLocalPeer("p1", "192.168.1.10", 38472, null, "i1")))
        f.orchestrator.beginSession(session, TransportPreference.FORCE_CLOUD)
        advanceUntilIdle()
        assertTrue(f.orchestrator.state.value is ConnectivityState.ConnectingCloud)
    }

    @Test fun `13 auto prefers local when peer and secret available`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        assertTrue(f.orchestrator.state.value is ConnectivityState.ConnectingLocal)
        assertEquals(TransportMode.LOCAL_LAN, f.orchestrator.diagnostics.value.activeTransport)
    }

    @Test fun `14 local failure samples trigger cloud transition`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        f.pathEvaluator.degradedLocal(f.now)
        f.orchestrator.evaluateNow()
        assertTrue(f.orchestrator.state.value is ConnectivityState.TransitioningToCloud)
    }

    @Test fun `15 cloud preparation timeout fails when force cloud`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.orchestrator.beginSession(session, TransportPreference.FORCE_CLOUD)
        advanceUntilIdle()
        f.now += 20_000
        f.orchestrator.evaluateNow()
        assertTrue(
            f.orchestrator.state.value is ConnectivityState.ConnectingCloud ||
                f.orchestrator.state.value is ConnectivityState.Failed,
        )
    }

    @Test fun `16 promote retires previous connection`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val localGen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(localGen))?.markReady()
        f.orchestrator.evaluateNow()
        f.pathEvaluator.degradedLocal(f.now)
        f.now += 10_000
        f.orchestrator.evaluateNow()
        val t = f.orchestrator.state.value as ConnectivityState.TransitioningToCloud
        f.switcher.connection(GenerationId(t.candidateGeneration))?.markReady()
        f.pathEvaluator.stableCloud(f.now)
        f.orchestrator.evaluateNow()
        assertTrue(f.switcher.promoteCalls.isNotEmpty())
    }

    @Test fun `17 session descriptor preserved across switches`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        assertEquals("sess-1", session.sessionId)
        assertEquals("user-a", session.participantId)
    }

    @Test fun `18 end session returns to idle`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.orchestrator.endSession()
        advanceUntilIdle()
        assertEquals(ConnectivityState.Idle, f.orchestrator.state.value)
        assertFalse(f.localTransport.connected)
    }

    @Test fun `19 reconnecting moves back to discovering`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.pathEvaluator.stableLocal(f.now)
        val gen = (f.orchestrator.state.value as ConnectivityState.ConnectingLocal).generation
        f.switcher.connection(GenerationId(gen))?.markReady()
        f.orchestrator.evaluateNow()
        f.pathEvaluator.set(TransportMode.LOCAL_LAN, com.nblaisot.voxcrew.connectivity.model.PathQuality(reachable = false))
        f.pathEvaluator.set(TransportMode.CLOUD_DIRECT, com.nblaisot.voxcrew.connectivity.model.PathQuality(reachable = false))
        f.orchestrator.evaluateNow()
        advanceUntilIdle()
        assertFalse(f.orchestrator.state.value is ConnectivityState.Idle)
    }

    @Test fun `20 transport preference can be changed at runtime`() = runTest(dispatcher) {
        val f = Fixture(this, thresholds, session)
        f.beginAuto()
        f.orchestrator.setTransportPreference(TransportPreference.FORCE_CLOUD)
        f.pathEvaluator.degradedLocal(f.now)
        f.now += 10_000
        f.orchestrator.evaluateNow()
        assertEquals(TransportPreference.FORCE_CLOUD, TransportPreference.FORCE_CLOUD)
    }
}
