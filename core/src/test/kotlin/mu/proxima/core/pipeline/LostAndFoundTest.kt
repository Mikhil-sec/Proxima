package mu.proxima.core.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.EmptyProtocolRepository
import mu.proxima.core.fake.FakeLocateEngine
import mu.proxima.core.fake.FakeTrustEngine
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.Peer
import mu.proxima.core.model.RawReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every packet sent by one node is delivered to every other node on the same bus (1 hop). */
private class LoopMesh(override val selfNodeId: String, private val bus: MutableSharedFlow<MeshPacket>, scope: CoroutineScope) : MeshEngine {
    override val requiredPermissions: List<String> = emptyList()
    override val running: StateFlow<Boolean> = MutableStateFlow(true)
    override val peers: StateFlow<List<Peer>> = MutableStateFlow(emptyList())
    private val _inbound = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<MeshPacket> = _inbound
    override val hops: SharedFlow<HopEvent> = MutableSharedFlow()
    private val sent = mutableSetOf<String>()

    init {
        scope.launch { bus.collect { if (it.packetId !in sent) _inbound.emit(it.copy(hopCount = 1)) } }
    }

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun send(packet: MeshPacket) { sent += packet.packetId; bus.emit(packet) }
}

/** A phone after an app restart with per-session identity: a new key, plus the previous session's, retained. */
private class RestartedTrust(
    private val previous: FakeTrustEngine,
    private val now: FakeTrustEngine = FakeTrustEngine(),
) : TrustEngine by now {
    override fun ownsKey(publicKey: String) = publicKey == now.identity.publicKey || publicKey == previous.identity.publicKey
    override fun ownsNodeId(nodeId: String) = nodeId == now.identity.nodeId || nodeId == previous.identity.nodeId
    override fun open(sealedB64: String) = now.open(sealedB64) ?: previous.open(sealedB64)
}

class LostAndFoundTest {
    private fun node(id: String, bus: MutableSharedFlow<MeshPacket>, scope: CoroutineScope): Pair<IncidentPipeline, FakeTrustEngine> {
        val trust = FakeTrustEngine()
        val pipeline = IncidentPipeline(LoopMesh(id, bus, scope), RuleTriageEngine(), trust, FakeLocateEngine(scope), EmptyProtocolRepository(), scope)
        pipeline.start()
        return pipeline to trust
    }

    @Test
    fun `finder with the child reaches only the guardian, without any wristband`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
        val (guardian, guardianTrust) = node("GUARDIAN", bus, scope)
        val (finder, _) = node("FINDER", bus, scope)
        val (bystander, _) = node("BYSTANDER", bus, scope)
        Thread.sleep(100) // let the bus collectors subscribe

        val lost = guardian.report(RawReport("Lost child, girl about 6, red dress", System.currentTimeMillis(), typeHint = IncidentType.LOST_PERSON))
        assertEquals(IncidentType.LOST_PERSON, lost.type)

        // The finder sees the alert, with the guardian's key attached, and says "I'm with them".
        val seen = withTimeout(2_000) { finder.incidents.first { list -> list.any { it.incident.id == lost.id } } }
            .first { it.incident.id == lost.id }
        assertEquals(guardianTrust.identity.publicKey, seen.originKey)
        assertTrue(finder.reportFound(seen, landmark = "Near the food stalls"))

        // Only the guardian decrypts it.
        val notice = withTimeout(2_000) { guardian.foundNotices.first { it.tagId == lost.id } }
        assertEquals("Near the food stalls", notice.landmark)
        assertNotNull(notice.location)
        Thread.sleep(200)
        assertTrue(bystander.foundNotices.replayCache.isEmpty())
        assertTrue(finder.foundNotices.replayCache.isEmpty())
        scope.cancel()
    }

    /**
     * #38: a fresh identity per app session must not cost a guardian the finder's notices. The finder seals to
     * the key in the alert, which is the guardian's PREVIOUS session key once the guardian's app has restarted.
     */
    @Test
    fun `guardian still receives the finder after an app restart with a new identity`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
        val onAir = java.util.Collections.synchronizedList(mutableListOf<MeshPacket>())
        scope.launch { bus.collect { onAir += it } }

        // As in the app, the mesh id IS the trust node id.
        val firstSession = FakeTrustEngine()
        val guardianScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val guardian = IncidentPipeline(LoopMesh(firstSession.identity.nodeId, bus, guardianScope), RuleTriageEngine(),
            firstSession, FakeLocateEngine(guardianScope), EmptyProtocolRepository(), guardianScope).also { it.start() }
        val (finder, _) = node("FINDER", bus, scope)
        Thread.sleep(100)

        val lost = guardian.report(RawReport("Lost child, boy about 5, blue shirt", System.currentTimeMillis(), typeHint = IncidentType.LOST_PERSON))
        val seen = withTimeout(2_000) { finder.incidents.first { list -> list.any { it.incident.id == lost.id } } }
            .first { it.incident.id == lost.id }

        // The guardian's app restarts: new identity, in-memory incidents gone, previous session's key retained.
        guardianScope.cancel()
        val secondSession = RestartedTrust(previous = firstSession)
        val restarted = IncidentPipeline(LoopMesh(secondSession.identity.nodeId, bus, scope), RuleTriageEngine(),
            secondSession, FakeLocateEngine(scope), EmptyProtocolRepository(), scope).also { it.start() }
        Thread.sleep(100)
        // A neighbour's store-and-forward re-delivers the guardian's own alert.
        bus.emit(onAir.first { it.kind == mu.proxima.core.model.PacketKind.INCIDENT && it.payload.contains(lost.id) }.copy(packetId = "relay-1"))
        withTimeout(2_000) { restarted.incidents.first { list -> list.any { it.incident.id == lost.id } } }

        assertTrue(finder.reportFound(seen, landmark = "By the temple steps"))

        val notice = withTimeout(2_000) { restarted.foundNotices.first { it.tagId == lost.id } }
        assertEquals("By the temple steps", notice.landmark)
        scope.cancel()
    }

    @Test
    fun `alert without a reporter key cannot be answered`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val (finder, _) = node("FINDER", MutableSharedFlow(extraBufferCapacity = 8), scope)
        val orphan = mu.proxima.core.model.IncidentRecord(
            incident = mu.proxima.core.model.Incident(
                id = "x", type = IncidentType.LOST_PERSON, severity = mu.proxima.core.model.Severity.HIGH,
                summary = "Lost child", createdAtMs = 0, reporterNodeId = "N-1",
            ),
            signatureValid = false, hopsTravelled = 2, lastUpdatedMs = 0, originKey = null,
        )
        assertFalse(finder.reportFound(orphan))
        scope.cancel()
    }
}
