package mu.proxima.ai.offload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.ai.triage.TriageMerge
import mu.proxima.ai.triage.TriagePrompt
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.TriageSource
import mu.proxima.core.pipeline.PacketFactory
import mu.proxima.core.pipeline.ProximaJson
import mu.proxima.core.pipeline.RuleTriageEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine AiModule returns on every phone. Lane spec order:
 * 1. local on-device LLM ready → ON_DEVICE_LLM
 * 2. else a peer recently announced a ready LLM → OFFLOAD_TO_PEER (TRIAGE_REQUEST, ttl 2, wait ≤ 5 s)
 * 3. else → RULES_ONLY
 * Never throws; every path ends in RuleTriageEngine. Peer answers get the same validation and
 * safety net as local ones (TriagePrompt.sanitize + TriageMerge), and are marked OFFLOADED_LLM.
 */
class TieredTriageEngine(
    private val local: TriageEngine?, // null on Tier 2 / Command Center
    private val mesh: MeshEngine,
    private val trust: TrustEngine,
    private val packets: PacketFactory,
    private val scope: CoroutineScope,
    private val rules: RuleTriageEngine = RuleTriageEngine(),
    private val offloadTimeoutMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : TriageEngine {

    /** nodeId → last time it announced a ready LLM. */
    private val helpers = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TriageResult>>()

    override val capability: StateFlow<TriageCapability> =
        combine(local?.capability ?: flowOf(TriageCapability.RULES_ONLY), helpers) { localCap, h ->
            when {
                localCap == TriageCapability.ON_DEVICE_LLM -> TriageCapability.ON_DEVICE_LLM
                h.isNotEmpty() -> TriageCapability.OFFLOAD_TO_PEER
                else -> TriageCapability.RULES_ONLY
            }
        }.stateIn(scope, SharingStarted.Eagerly, TriageCapability.RULES_ONLY)

    init {
        scope.launch {
            mesh.inbound.collect { p ->
                when {
                    p.kind == PacketKind.TRIAGE_RESULT -> onResult(p)
                    p.kind == PacketKind.CONTROL && p.channel == OffloadWire.CHANNEL -> onAnnouncement(p)
                }
            }
        }
        scope.launch { // expire helpers that went quiet (walked away, battery died, LLM unloaded)
            while (isActive) {
                delay(5_000)
                val now = clock()
                helpers.value = helpers.value.filterValues { now - it <= OffloadWire.HELPER_EXPIRY_MS }
            }
        }
    }

    override suspend fun triage(report: RawReport): TriageResult {
        local?.let { if (it.capability.value == TriageCapability.ON_DEVICE_LLM) return it.triage(report) }
        val fallback = rules.classify(report)
        if (helpers.value.isEmpty()) return fallback
        return offload(report, fallback) ?: fallback
    }

    private suspend fun offload(report: RawReport, fallback: TriageResult): TriageResult? {
        val request = OffloadRequest(text = report.text.take(1_000), typeHint = report.typeHint)
        val packet = runCatching {
            packets.create(
                PacketKind.TRIAGE_REQUEST,
                ProximaJson.encodeToString(OffloadRequest.serializer(), request),
                fallback.severity,
                ttl = OffloadWire.TTL,
            )
        }.getOrElse { log("offload: could not build request: ${it.message}"); return null }

        val answer = CompletableDeferred<TriageResult>()
        pending[packet.packetId] = answer
        try {
            runCatching { mesh.send(packet) }.onFailure { log("offload: send failed: ${it.message}"); return null }
            val peer = withTimeoutOrNull(offloadTimeoutMs) { answer.await() }
            if (peer == null) { log("offload: no answer in ${offloadTimeoutMs}ms → rules"); return null }
            val checked = TriagePrompt.sanitize(peer.copy(source = TriageSource.OFFLOADED_LLM), report)
            return TriageMerge.merge(checked, fallback).copy(source = TriageSource.OFFLOADED_LLM)
        } finally {
            pending.remove(packet.packetId)
        }
    }

    private fun onResult(packet: MeshPacket) {
        if (packet.recipientKey != null && packet.recipientKey != trust.identity.publicKey) return // not for us
        if (!trust.verify(packet)) { log("offload: invalid result signature dropped"); return }
        val reply = runCatching { ProximaJson.decodeFromString(OffloadResult.serializer(), packet.payload) }.getOrNull() ?: return
        pending[reply.requestId]?.complete(reply.result) // first valid answer wins; late ones are ignored
    }

    private fun onAnnouncement(packet: MeshPacket) {
        if (packet.originNodeId == mesh.selfNodeId) return
        if (clock() - packet.createdAtMs > OffloadWire.HELPER_EXPIRY_MS) return // replayed by store-and-forward
        if (!trust.verify(packet)) return
        val a = runCatching { ProximaJson.decodeFromString(LlmAnnouncement.serializer(), packet.payload) }.getOrNull() ?: return
        helpers.value = if (a.ready) helpers.value + (packet.originNodeId to clock()) else helpers.value - packet.originNodeId
    }
}
