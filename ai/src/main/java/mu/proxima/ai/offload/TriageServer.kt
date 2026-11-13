package mu.proxima.ai.offload

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageSource
import mu.proxima.core.pipeline.PacketFactory
import mu.proxima.core.pipeline.ProximaJson

/**
 * Tier 1 side of offload. While the local LLM is ready it:
 * - announces readiness on CONTROL/"ai" every 20 s (2 hops), and
 * - answers fresh, signed TRIAGE_REQUESTs from peers with a TRIAGE_RESULT.
 * It only answers with a real model result: if its own LLM falls back to rules, it stays silent
 * (the requester already has the same rules result and stops waiting at its own timeout).
 */
class TriageServer(
    private val local: TriageEngine,
    private val mesh: MeshEngine,
    private val trust: TrustEngine,
    private val packets: PacketFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** At most 2 peer requests in flight; extra ones are dropped (requester falls back to rules). */
    private val slots = Semaphore(2)
    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { mesh.inbound.collect { if (it.kind == PacketKind.TRIAGE_REQUEST) onRequest(it) } },
            scope.launch {
                while (isActive) {
                    if (ready()) announce()
                    delay(OffloadWire.ANNOUNCE_EVERY_MS)
                }
            },
        )
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs = emptyList() }

    private fun ready() = local.capability.value == TriageCapability.ON_DEVICE_LLM

    private suspend fun announce() {
        val payload = ProximaJson.encodeToString(LlmAnnouncement.serializer(), LlmAnnouncement(mesh.selfNodeId, ready = true))
        runCatching {
            mesh.send(packets.create(PacketKind.CONTROL, payload, Severity.LOW, ttl = OffloadWire.TTL, channel = OffloadWire.CHANNEL))
        }.onFailure { log("offload: announce failed: ${it.message}") }
    }

    private fun onRequest(packet: MeshPacket) {
        if (packet.originNodeId == mesh.selfNodeId || !ready()) return
        if (clock() - packet.createdAtMs > OffloadWire.MAX_REQUEST_AGE_MS) return // replayed by store-and-forward
        if (!trust.verify(packet)) { log("offload: unsigned/invalid request dropped"); return }
        val request = runCatching { ProximaJson.decodeFromString(OffloadRequest.serializer(), packet.payload) }.getOrNull()
            ?: return
        if (!slots.tryAcquire()) { log("offload: busy, request ${packet.packetId.take(8)} dropped"); return }

        scope.launch {
            try {
                val raw = RawReport(text = request.text.take(1_000), createdAtMs = packet.createdAtMs, typeHint = request.typeHint)
                val result = local.triage(raw)
                if (result.source != TriageSource.ON_DEVICE_LLM) return@launch // only real model answers
                val reply = OffloadResult(requestId = packet.packetId, result = result)
                mesh.send(
                    packets.create(
                        PacketKind.TRIAGE_RESULT,
                        ProximaJson.encodeToString(OffloadResult.serializer(), reply),
                        result.severity,
                        ttl = (packet.hopCount + 1).coerceIn(1, OffloadWire.TTL),
                        recipientKey = packet.originKey,
                    )
                )
                log("offload: answered ${packet.packetId.take(8)} for ${packet.originNodeId} → ${result.type}/${result.severity}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("offload: answering failed: ${e.message}")
            } finally {
                slots.release()
            }
        }
    }
}
