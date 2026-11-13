package mu.proxima.ai.offload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.fake.FakeTrustEngine
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Peer
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.TriageSource
import mu.proxima.core.pipeline.PacketFactory
import mu.proxima.core.pipeline.ProximaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OffloadTest {

    /** In-memory broadcast mesh: send() delivers to every other node's inbound. */
    private class Bus { val nodes = mutableListOf<TestMesh>() }

    private class TestMesh(override val selfNodeId: String, private val bus: Bus) : MeshEngine {
        override val requiredPermissions = emptyList<String>()
        override val running = MutableStateFlow(true)
        override val peers = MutableStateFlow(emptyList<Peer>())
        val input = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
        override val inbound = input
        override val hops = MutableSharedFlow<HopEvent>()
        val sent = mutableListOf<MeshPacket>()
        init { bus.nodes += this }
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(packet: MeshPacket) {
            sent += packet
            bus.nodes.filter { it !== this }.forEach { it.input.tryEmit(packet) }
        }
    }

    /** Stand-in for a Tier 1 LlmTriageEngine. */
    private class FakeLlm(
        ready: Boolean = true,
        var delayMs: Long = 100,
        var answer: (RawReport) -> TriageResult = {
            TriageResult(IncidentType.MEDICAL, Severity.HIGH, "BLEEDING", "Head wound near gate", "gate", 1,
                source = TriageSource.ON_DEVICE_LLM, confidence = 0.8f)
        },
    ) : TriageEngine {
        val state = MutableStateFlow(if (ready) TriageCapability.ON_DEVICE_LLM else TriageCapability.RULES_ONLY)
        override val capability: StateFlow<TriageCapability> = state
        var calls = 0
        override suspend fun triage(report: RawReport): TriageResult { calls++; delay(delayMs); return answer(report) }
    }

    private class World(scope: TestScope, helperLlm: FakeLlm?) {
        val clock = { scope.testScheduler.currentTime }
        val bus = Bus()
        val scoutTrust = FakeTrustEngine()
        val helperTrust = FakeTrustEngine()
        val scoutMesh = TestMesh("SCOUT", bus)
        val helperMesh = TestMesh("HELPER", bus)
        val scout = TieredTriageEngine(null, scoutMesh, scoutTrust, PacketFactory(scoutTrust, clock), scope.backgroundScope, clock = clock)
        val server = helperLlm?.let {
            TriageServer(it, helperMesh, helperTrust, PacketFactory(helperTrust, clock), scope.backgroundScope, clock = clock).also { s -> s.start() }
        }
    }

    private fun report(text: String, hint: IncidentType? = null) = RawReport(text = text, createdAtMs = 0, typeHint = hint)

    @Test fun noHelper_rulesOnly_andNothingSent() = runTest {
        val w = World(this, helperLlm = null)
        runCurrent()
        assertEquals(TriageCapability.RULES_ONLY, w.scout.capability.value)
        val r = w.scout.triage(report("lady bleeding near the gate"))
        assertEquals(TriageSource.RULES, r.source)
        assertTrue(w.scoutMesh.sent.isEmpty())
    }

    @Test fun readyHelper_announces_andAnswers() = runTest {
        val llm = FakeLlm()
        val w = World(this, llm)
        runCurrent()
        assertEquals(TriageCapability.OFFLOAD_TO_PEER, w.scout.capability.value)

        val r = w.scout.triage(report("lady bleeding near the gate"))
        assertEquals(TriageSource.OFFLOADED_LLM, r.source)
        assertEquals("BLEEDING", r.subtype)
        assertEquals(1, llm.calls)
        val req = w.scoutMesh.sent.single { it.kind == PacketKind.TRIAGE_REQUEST }
        assertEquals(OffloadWire.TTL, req.ttl)
        // privacy: the request carries only text + button, never a location
        assertTrue(!req.payload.contains("location"))
    }

    @Test fun helperNotReady_neverAnnounced_rulesOnly() = runTest {
        val w = World(this, FakeLlm(ready = false))
        runCurrent()
        assertEquals(TriageCapability.RULES_ONLY, w.scout.capability.value)
        assertEquals(TriageSource.RULES, w.scout.triage(report("fire near the stalls")).source)
    }

    @Test fun slowHelper_rulesAfterFiveSeconds() = runTest {
        val w = World(this, FakeLlm(delayMs = 30_000))
        runCurrent()
        val start = testScheduler.currentTime
        val r = w.scout.triage(report("lady bleeding near the gate"))
        assertEquals(TriageSource.RULES, r.source)
        assertEquals(5_000, testScheduler.currentTime - start)
    }

    @Test fun peerAnswerIsSanitised_noProtocolId_validSubtype() = runTest {
        val llm = FakeLlm(answer = {
            TriageResult(IncidentType.MEDICAL, Severity.HIGH, "MADE_UP", "x".repeat(400), null, 5000,
                protocolId = "evil-card", source = TriageSource.ON_DEVICE_LLM, confidence = 7f)
        })
        val w = World(this, llm)
        runCurrent()
        val r = w.scout.triage(report("someone is hurt"))
        assertEquals(TriageSource.OFFLOADED_LLM, r.source)
        assertNull(r.protocolId) // cards are always chosen locally from vetted data
        assertEquals("GENERAL", r.subtype)
        assertEquals(140, r.summary.length)
        assertNull(r.peopleCount)
        assertTrue(r.confidence <= 1f)
    }

    @Test fun underRatingHelper_rulesSafetyNetStillApplies() = runTest {
        val llm = FakeLlm(answer = {
            TriageResult(IncidentType.MEDICAL, Severity.MEDIUM, "UNCONSCIOUS", "Old man collapsed", null, null,
                source = TriageSource.ON_DEVICE_LLM, confidence = 0.8f)
        })
        val w = World(this, llm)
        runCurrent()
        val r = w.scout.triage(report("old man collapsed, he is not breathing"))
        assertEquals(Severity.CRITICAL, r.severity)
        assertEquals(TriageSource.OFFLOADED_LLM, r.source)
    }

    @Test fun staleReplayedRequest_isIgnoredByServer() = runTest {
        val llm = FakeLlm()
        val w = World(this, llm)
        runCurrent()
        testScheduler.advanceTimeBy(60_000)
        val old = PacketFactory(w.scoutTrust) { testScheduler.currentTime - 20_000 }.create(
            PacketKind.TRIAGE_REQUEST,
            ProximaJson.encodeToString(OffloadRequest.serializer(), OffloadRequest("fire")),
            ttl = 2,
        )
        w.helperMesh.input.tryEmit(old)
        runCurrent()
        assertEquals(0, llm.calls)
    }

    @Test fun resultAddressedToSomeoneElse_isIgnored() = runTest {
        val w = World(this, FakeLlm(delayMs = 30_000)) // genuine helper too slow to matter
        runCurrent()
        val answer = async { w.scout.triage(report("lady bleeding near the gate")) }
        runCurrent()
        val requestId = w.scoutMesh.sent.single { it.kind == PacketKind.TRIAGE_REQUEST }.packetId
        val forged = PacketFactory(w.helperTrust) { testScheduler.currentTime }.create(
            PacketKind.TRIAGE_RESULT,
            ProximaJson.encodeToString(
                OffloadResult.serializer(),
                OffloadResult(requestId, TriageResult(IncidentType.HAZARD, Severity.LOW, null, "nothing", source = TriageSource.ON_DEVICE_LLM, confidence = 1f)),
            ),
            recipientKey = FakeTrustEngine().identity.publicKey, // a stranger's key
            ttl = 1,
        )
        w.scoutMesh.input.tryEmit(forged)
        assertEquals(TriageSource.RULES, answer.await().source) // forged reply ignored → rules at timeout
    }

    @Test fun unsignedResult_isIgnored() = runTest {
        val w = World(this, FakeLlm(delayMs = 30_000))
        runCurrent()
        val answer = async { w.scout.triage(report("lady bleeding near the gate")) }
        runCurrent()
        val requestId = w.scoutMesh.sent.single { it.kind == PacketKind.TRIAGE_REQUEST }.packetId
        val unsigned = PacketFactory(w.helperTrust) { testScheduler.currentTime }.create(
            PacketKind.TRIAGE_RESULT,
            ProximaJson.encodeToString(
                OffloadResult.serializer(),
                OffloadResult(requestId, TriageResult(IncidentType.HAZARD, Severity.LOW, null, "nothing", source = TriageSource.ON_DEVICE_LLM, confidence = 1f)),
            ),
            ttl = 1,
        ).copy(signature = null)
        w.scoutMesh.input.tryEmit(unsigned)
        assertEquals(TriageSource.RULES, answer.await().source)
    }

    @Test fun silentHelperExpires() = runTest {
        val llm = FakeLlm()
        val w = World(this, llm)
        runCurrent()
        assertEquals(TriageCapability.OFFLOAD_TO_PEER, w.scout.capability.value)
        w.server!!.stop()
        testScheduler.advanceTimeBy(OffloadWire.HELPER_EXPIRY_MS + 10_000)
        runCurrent()
        assertEquals(TriageCapability.RULES_ONLY, w.scout.capability.value)
    }

    @Test fun localLlmReady_noOffload() = runTest {
        val bus = Bus()
        val trust = FakeTrustEngine()
        val mesh = TestMesh("T1", bus)
        val local = FakeLlm()
        val engine = TieredTriageEngine(local, mesh, trust, PacketFactory(trust), backgroundScope)
        runCurrent()
        assertEquals(TriageCapability.ON_DEVICE_LLM, engine.capability.value)
        assertEquals(TriageSource.ON_DEVICE_LLM, engine.triage(report("fire")).source)
        assertTrue(mesh.sent.isEmpty())
    }
}
