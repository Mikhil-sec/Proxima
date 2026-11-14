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
import kotlinx.coroutines.flow.flow
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
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.TriageSource
import mu.proxima.core.model.WristbandTag
import java.util.UUID

/**
 * The glue between engines. The UI calls this; it never calls MeshEngine.send directly.
 *
 *   report(raw) → TriageEngine → Incident → sign → MeshEngine.send
 *   MeshEngine.inbound → verify → decode → IncidentMerger → incidents (StateFlow for UI)
 *   finder picks the LOST_PERSON alert they are with → FoundNotice sealed to that reporter's key
 *     → guardian's foundNotices → radar   (wristband tags remain supported but are optional)
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

    /**
     * SOS / report button. Returns the incident as broadcast.
     * [triaged]: the result the user was already shown (#10). Passing it skips a second triage call, so the alert
     * that goes out is exactly the one on the review screen and an on-device LLM is not run twice.
     */
    suspend fun report(raw: RawReport, triaged: TriageResult? = null): Incident {
        val withLocation = if (raw.location == null) raw.copy(location = locate.selfLocation.value) else raw
        val result = triaged ?: runCatching { triage.triage(withLocation) }
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

    /**
     * Finder side, no hardware: "I am with the person in this LOST_PERSON alert."
     * Sends one FoundNotice (tagId = the lost incident's id) sealed to the phone that reported it.
     * Returns false if the alert carries no reporter key (e.g. it reached us unsigned).
     */
    suspend fun reportFound(lost: IncidentRecord, landmark: String? = null, note: String? = null): Boolean {
        val guardianKey = lost.originKey ?: return false
        sendFoundNotice(lost.incident.id, guardianKey, landmark, note)
        return true
    }

    /** Finder side: keep the guardian's radar updated until cancelled. */
    fun shareLocationWithGuardian(lost: IncidentRecord, everyMs: Long = 10_000L): Job = scope.launch {
        while (isActive) {
            if (!reportFound(lost)) return@launch
            delay(everyMs)
        }
    }

    /** Finder side: scanned a wristband. Sends one sealed notice to the guardian. */
    suspend fun reportFound(tag: WristbandTag, landmark: String? = null, note: String? = null) =
        sendFoundNotice(tag.tagId, tag.guardianKey, landmark, note)

    /** Finder side: keep the guardian's radar updated until cancelled. */
    fun shareLocationWithGuardian(tag: WristbandTag, everyMs: Long = 10_000L): Job = scope.launch {
        while (isActive) {
            reportFound(tag)
            delay(everyMs)
        }
    }

    private suspend fun sendFoundNotice(tagId: String, guardianKey: String, landmark: String?, note: String?) {
        val notice = FoundNotice(
            tagId = tagId,
            finderNodeId = mesh.selfNodeId,
            location = locate.selfLocation.value,
            landmark = landmark,
            note = note,
            atMs = clock(),
        )
        val sealed = trust.seal(ProximaJson.encodeToString(FoundNotice.serializer(), notice).encodeToByteArray(), guardianKey)
        mesh.send(packets.create(PacketKind.SEALED, sealed, Severity.CRITICAL, recipientKey = guardianKey))
    }

    /** Guardian side: radar target that follows the finder's sealed location updates. */
    fun trackFinder(notice: FoundNotice, label: String = "Finder"): TrackTarget {
        // Only this finder, and only newer positions: store-and-forward can flush older notices late, and a second
        // person answering the same alert must not move this radar (LOCATE review on #14).
        val locations: Flow<GeoPoint> = flow {
            var newest = Long.MIN_VALUE
            foundNotices.collect { n ->
                if (n.tagId == notice.tagId && n.finderNodeId == notice.finderNodeId && n.atMs > newest) {
                    newest = n.atMs
                    n.location?.let { emit(it) }
                }
            }
        }
        return TrackTarget(nodeId = notice.finderNodeId, label = label, locations = locations)
    }

    private suspend fun broadcast(incident: Incident) {
        accept(incident, signatureValid = true, hops = 0, originKey = trust.identity.publicKey)
        val payload = ProximaJson.encodeToString(Incident.serializer(), incident)
        mesh.send(packets.create(PacketKind.INCIDENT, payload, incident.severity))
    }

    private fun handle(packet: MeshPacket) {
        val valid = runCatching { trust.verify(packet) }.getOrDefault(false)
        when (packet.kind) {
            PacketKind.INCIDENT -> runCatching {
                ProximaJson.decodeFromString(Incident.serializer(), packet.payload)
            }.onSuccess { accept(it, valid, packet.hopCount, packet.originKey) }

            PacketKind.SEALED -> {
                if (!valid || packet.recipientKey?.let(trust::ownsKey) != true) return
                val bytes = trust.open(packet.payload) ?: return
                val notice = runCatching {
                    ProximaJson.decodeFromString(FoundNotice.serializer(), bytes.decodeToString())
                }.getOrNull() ?: return
                val mineLost = _incidents.value.any {
                    it.incident.id == notice.tagId && it.incident.type == IncidentType.LOST_PERSON &&
                        (it.incident.reporterNodeId == mesh.selfNodeId || trust.ownsNodeId(it.incident.reporterNodeId))
                }
                if (mineLost || trust.myWristbands().any { it.tagId == notice.tagId }) _foundNotices.tryEmit(notice)
            }

            // Handled inside the AI / LOCATE / MESH engines themselves.
            PacketKind.TRIAGE_REQUEST, PacketKind.TRIAGE_RESULT, PacketKind.BEACON, PacketKind.CONTROL -> Unit
        }
    }

    private fun accept(incident: Incident, signatureValid: Boolean, hops: Int, originKey: String? = null) {
        _incidents.update { merger.apply(it, incident, signatureValid, hops, clock(), originKey) }
    }
}
