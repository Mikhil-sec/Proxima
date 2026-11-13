package mu.proxima.core.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.FoundNotice
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TrackTarget
import mu.proxima.core.model.TriageSource
import mu.proxima.core.model.WristbandTag
import java.util.UUID

/**
 * The glue between engines. The UI calls this; it never calls MeshEngine.send directly.
 *
 *   report(raw) → TriageEngine → Incident → sign → MeshEngine.send
 *   MeshEngine.inbound → verify → decode → IncidentMerger → incidents (StateFlow for UI)
 *   finder scans wristband → FoundNotice sealed to guardian → guardian's foundNotices → radar
 */
class IncidentPipeline(
    private val mesh: MeshEngine,
    private val triage: TriageEngine,
    private val trust: TrustEngine,
    private val locate: LocateEngine,
    private val protocols: ProtocolRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val packets = PacketFactory(trust, clock)
    private val merger = IncidentMerger()

    private val _incidents = MutableStateFlow<List<IncidentRecord>>(emptyList())
    /** Merged, de-duplicated incidents, newest first. Drives lists, heatmap and Command Center. */
    val incidents: StateFlow<List<IncidentRecord>> = _incidents.asStateFlow()

    private val _foundNotices = MutableSharedFlow<FoundNotice>(replay = 8, extraBufferCapacity = 32)
    /** Decrypted FoundNotices for wristbands THIS phone minted (guardian side). */
    val foundNotices: SharedFlow<FoundNotice> = _foundNotices.asSharedFlow()

    private var inboundJob: Job? = null

    fun start() {
        if (inboundJob != null) return
        inboundJob = scope.launch { mesh.inbound.collect { handle(it) } }
    }

    /** SOS / report button. Returns the incident as broadcast. */
    suspend fun report(raw: RawReport): Incident {
        val withLocation = if (raw.location == null) raw.copy(location = locate.selfLocation.value) else raw
        val result = runCatching { triage.triage(withLocation) }
            .getOrElse { RuleTriageEngine().classify(withLocation) }
        val protocolId = result.protocolId ?: protocols.bestFor(result.type, result.subtype)?.id
        val incident = Incident(
            id = UUID.randomUUID().toString(),
            type = result.type,
            severity = result.severity,
            subtype = result.subtype,
            summary = result.summary,
            location = withLocation.location,
            landmark = result.landmark,
            peopleCount = result.peopleCount,
            createdAtMs = clock(),
            reporterNodeId = mesh.selfNodeId,
            protocolId = protocolId,
            triagedBy = result.source,
            confidence = result.confidence,
        )
        broadcast(incident)
        return incident
    }

    /** Close an incident everywhere (e.g. medic arrived, hazard cleared). */
    suspend fun resolve(incidentId: String, note: String = "Resolved") {
        val original = _incidents.value.firstOrNull { it.incident.id == incidentId }?.incident ?: return
        broadcast(
            Incident(
                id = UUID.randomUUID().toString(),
                type = IncidentType.ALL_CLEAR,
                severity = Severity.LOW,
                summary = note.take(140),
                location = original.location,
                createdAtMs = clock(),
                reporterNodeId = mesh.selfNodeId,
                relatedIncidentId = incidentId,
                triagedBy = TriageSource.MANUAL,
            )
        )
    }

    /** Finder side: scanned a wristband. Sends one sealed notice to the guardian. */
    suspend fun reportFound(tag: WristbandTag, landmark: String? = null, note: String? = null) {
        val notice = FoundNotice(
            tagId = tag.tagId,
            finderNodeId = mesh.selfNodeId,
            location = locate.selfLocation.value,
            landmark = landmark,
            note = note,
            atMs = clock(),
        )
        val sealed = trust.seal(ProximaJson.encodeToString(FoundNotice.serializer(), notice).encodeToByteArray(), tag.guardianKey)
        mesh.send(packets.create(PacketKind.SEALED, sealed, Severity.CRITICAL, recipientKey = tag.guardianKey))
    }

    /** Finder side: keep the guardian's radar updated until cancelled. */
    fun shareLocationWithGuardian(tag: WristbandTag, everyMs: Long = 10_000L): Job = scope.launch {
        while (isActive) {
            reportFound(tag)
            delay(everyMs)
        }
    }

    /** Guardian side: radar target that follows the finder's sealed location updates. */
    fun trackFinder(notice: FoundNotice, label: String = "Finder"): TrackTarget {
        val locations: Flow<GeoPoint> = foundNotices
            .filter { it.tagId == notice.tagId }
            .mapNotNull { it.location }
        return TrackTarget(nodeId = notice.finderNodeId, label = label, locations = locations)
    }

    private suspend fun broadcast(incident: Incident) {
        accept(incident, signatureValid = true, hops = 0)
        val payload = ProximaJson.encodeToString(Incident.serializer(), incident)
        mesh.send(packets.create(PacketKind.INCIDENT, payload, incident.severity))
    }

    private fun handle(packet: MeshPacket) {
        val valid = runCatching { trust.verify(packet) }.getOrDefault(false)
        when (packet.kind) {
            PacketKind.INCIDENT -> runCatching {
                ProximaJson.decodeFromString(Incident.serializer(), packet.payload)
            }.onSuccess { accept(it, valid, packet.hopCount) }

            PacketKind.SEALED -> {
                if (!valid || packet.recipientKey != trust.identity.publicKey) return
                val bytes = trust.open(packet.payload) ?: return
                val notice = runCatching {
                    ProximaJson.decodeFromString(FoundNotice.serializer(), bytes.decodeToString())
                }.getOrNull() ?: return
                if (trust.myWristbands().any { it.tagId == notice.tagId }) _foundNotices.tryEmit(notice)
            }

            // Handled inside the AI / LOCATE / MESH engines themselves.
            PacketKind.TRIAGE_REQUEST, PacketKind.TRIAGE_RESULT, PacketKind.BEACON, PacketKind.CONTROL -> Unit
        }
    }

    private fun accept(incident: Incident, signatureValid: Boolean, hops: Int) {
        _incidents.update { merger.apply(it, incident, signatureValid, hops, clock()) }
    }
}
