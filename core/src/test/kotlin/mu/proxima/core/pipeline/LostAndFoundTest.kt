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
