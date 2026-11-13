package mu.proxima.core.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.proxima.core.engine.HelpPointRepository
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.FixSource
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.NodeIdentity
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Peer
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.Proximity
import mu.proxima.core.model.RadarFix
import mu.proxima.core.model.RankedHelpPoint
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.TrackTarget
import mu.proxima.core.model.Transport
import mu.proxima.core.model.VoiceEvent
import mu.proxima.core.model.WristbandTag
import mu.proxima.core.pipeline.ProximaJson
import mu.proxima.core.pipeline.RuleTriageEngine
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/*
 * ⚠ SIMULATION — DEVELOPMENT ONLY.
 * Lets the UI lane build against live-looking telemetry before real engines exist.
 * The UI must show a visible "SIM" badge whenever any of these are active.
 * The final demo runs with simulation OFF. Each lane may edit only its own fake.
 */

/** Approximate centre of Ganga Talao (Grand Bassin) — used only to place simulated nodes. */
val SIM_VENUE_CENTER = GeoPoint(-20.4180, 57.4925)

private fun rand4() = UUID.randomUUID().toString().take(4).uppercase()

class FakeMeshEngine(private val scope: CoroutineScope) : MeshEngine {
    override val selfNodeId = "SELF-${rand4()}"
    override val requiredPermissions: List<String> = emptyList()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _inbound = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<MeshPacket> = _inbound.asSharedFlow()

    private val _hops = MutableSharedFlow<HopEvent>(extraBufferCapacity = 256)
    override val hops: SharedFlow<HopEvent> = _hops.asSharedFlow()

    private val rules = RuleTriageEngine()

    private val script = listOf(
        "Elderly man fainted near the main temple steps, not responding",
        "Small fire at a food stall near the parking, lots of smoke",
        "Big branch fell on the path near the bridge, blocking the way",
        "Lost child, girl about 6, red dress, last seen near the water",
        "Lady bleeding from the head near the entrance",
        "Crowd pushing too much near the gate, people falling",
        "Zanfan perdi kot laport, garson 5 an, simiz ble",
        "Boy crying alone near the bus stop",
        "Two people dizzy from the heat near the stalls",
    )

    override suspend fun start() {
        if (_running.value) return
        _running.value = true
        val tiers = listOf(DeviceTier.TIER1_COMPUTE, DeviceTier.TIER2_SCOUT, DeviceTier.TIER2_SCOUT,
            DeviceTier.TIER1_COMPUTE, DeviceTier.TIER2_SCOUT, DeviceTier.COMMAND_CENTER)
        _peers.value = tiers.mapIndexed { i, tier ->
            val angle = Math.toRadians(i * 60.0 + Random.nextDouble(-15.0, 15.0))
            val dist = Random.nextDouble(25.0, 160.0)
            Peer(
                nodeId = "N-${rand4()}",
                shortCode = "${tier.name.first()}$i",
                tier = tier,
                transport = Transport.SIMULATED,
                rssiDbm = -45 - (dist / 4).toInt(),
                lastSeenMs = System.currentTimeMillis(),
                location = GeoMath.offset(SIM_VENUE_CENTER, dist * kotlin.math.sin(angle), dist * kotlin.math.cos(angle)),
                hasUwb = tier == DeviceTier.TIER1_COMPUTE,
                llmReady = tier == DeviceTier.TIER1_COMPUTE,
                direct = i % 2 == 0,
            )
        }
        scope.launch { // RSSI jitter
            while (isActive && _running.value) {
                delay(1_000)
                _peers.update { list ->
                    list.map { it.copy(rssiDbm = (it.rssiDbm ?: -70) + Random.nextInt(-3, 4), lastSeenMs = System.currentTimeMillis()) }
                }
            }
        }
        scope.launch { // scripted incoming incidents
            delay(3_000)
            while (isActive && _running.value) {
                emitScriptedIncident()
                delay(Random.nextLong(10_000, 18_000))
            }
        }
    }

    override suspend fun stop() {
        _running.value = false
        _peers.value = emptyList()
    }

    override suspend fun send(packet: MeshPacket) {
        val peers = _peers.value
        peers.filter { it.direct }.forEach {
            _hops.tryEmit(HopEvent(packet.packetId, packet.kind, packet.severity, selfNodeId, it.nodeId, System.currentTimeMillis()))
        }
        delay(400)
        peers.filter { !it.direct }.forEach { far ->
            val via = peers.filter { it.direct }.randomOrNull() ?: return@forEach
            _hops.tryEmit(HopEvent(packet.packetId, packet.kind, packet.severity, via.nodeId, far.nodeId, System.currentTimeMillis()))
        }
    }

    private suspend fun emitScriptedIncident() {
        val peers = _peers.value.ifEmpty { return }
        val origin = peers.random()
        val text = script.random()
        val r = rules.classify(RawReport(text, System.currentTimeMillis(), origin.location))
        val incident = Incident(
            id = UUID.randomUUID().toString(), type = r.type, severity = r.severity, subtype = r.subtype,
            summary = r.summary, location = origin.location, landmark = r.landmark, peopleCount = r.peopleCount,
            createdAtMs = System.currentTimeMillis(), reporterNodeId = origin.nodeId, triagedBy = r.source, confidence = r.confidence,
        )
        val path = (peers - origin).shuffled().take(Random.nextInt(0, 3)).map { it.nodeId }
        val full = listOf(origin.nodeId) + path + selfNodeId
        full.zipWithNext().forEach { (a, b) ->
            _hops.tryEmit(HopEvent("sim", PacketKind.INCIDENT, incident.severity, a, b, System.currentTimeMillis()))
            delay(350)
        }
        _inbound.tryEmit(
            MeshPacket(
                packetId = UUID.randomUUID().toString(), kind = PacketKind.INCIDENT, originNodeId = origin.nodeId,
                originKey = "SIMKEY-${origin.nodeId}", createdAtMs = incident.createdAtMs, severity = incident.severity,
                ttl = incident.severity.hopBudget - full.size + 1, hopCount = full.size - 1, path = full.dropLast(1),
                payload = ProximaJson.encodeToString(Incident.serializer(), incident),
                // every 4th simulated packet is unsigned → shows the amber "unverified relay" state
                signature = if (Random.nextInt(4) == 0) null else "SIM",
            )
        )
    }
}

class FakeTrustEngine : TrustEngine {
    override val identity = NodeIdentity("SELF-${rand4()}", "SIMKEY-${UUID.randomUUID()}")
    private val minted = mutableListOf<WristbandTag>()

    override fun sign(packet: MeshPacket) = packet.copy(signature = "SIM")
    override fun verify(packet: MeshPacket) = packet.signature != null

    override fun seal(plaintext: ByteArray, recipientKey: String): String =
        "SIMSEALED|$recipientKey|" + Base64.getEncoder().encodeToString(plaintext)

    override fun open(sealedB64: String): ByteArray? {
        val parts = sealedB64.split("|")
        if (parts.size != 3 || parts[0] != "SIMSEALED" || parts[1] != identity.publicKey) return null
        return Base64.getDecoder().decode(parts[2])
    }

    override fun createWristband(): WristbandTag =
        WristbandTag(UUID.randomUUID().toString().take(8), identity.publicKey, System.currentTimeMillis()).also { minted += it }

    override fun myWristbands(): List<WristbandTag> = minted.toList()
}

class FakeLocateEngine(private val scope: CoroutineScope) : LocateEngine {
    override val requiredPermissions: List<String> = emptyList()
    private val _self = MutableStateFlow<GeoPoint?>(SIM_VENUE_CENTER)
    override val selfLocation: StateFlow<GeoPoint?> = _self.asStateFlow()
    private val _heading = MutableStateFlow<Float?>(0f)
    override val headingDeg: StateFlow<Float?> = _heading.asStateFlow()
    override val uwbAvailable = false

    override suspend fun start() {
        scope.launch { // slow wobble so the radar visibly reacts to "turning"
            var t = 0.0
            while (isActive) {
                t += 0.05
                _heading.value = ((kotlin.math.sin(t) * 40 + 360) % 360).toFloat()
                delay(50)
            }
        }
    }

    override suspend fun stop() = Unit

    /** Uses real target locations if any arrive, otherwise simulates a target walking toward you. */
    override fun track(target: TrackTarget): Flow<RadarFix> = channelFlow {
        var latest: GeoPoint? = null
        launch { target.locations.collect { latest = it } }
        var simDist = 140.0
        while (isActive) {
            val me = _self.value ?: SIM_VENUE_CENTER
            val there = latest ?: GeoMath.offset(me, simDist * 0.6, simDist * 0.8).also { simDist = (simDist - 1.5).coerceAtLeast(1.0) }
            val d = GeoMath.distanceM(me, there)
            val b = GeoMath.bearingDeg(me, there)
            val h = _heading.value
            send(
                RadarFix(
                    targetNodeId = target.nodeId, distanceM = d, bearingDeg = b,
                    relativeBearingDeg = h?.let { GeoMath.relativeBearingDeg(b, it.toDouble()) },
                    source = FixSource.SIMULATED,
                    proximity = when { d < 3 -> Proximity.HERE; d < 15 -> Proximity.CLOSE; d < 60 -> Proximity.NEAR; else -> Proximity.FAR },
                    accuracyM = 8.0, atMs = System.currentTimeMillis(),
                )
            )
            delay(200)
        }
    }
}

class FakeVoiceIo : VoiceIo {
    override val requiredPermissions: List<String> = emptyList()
    override fun listen(languageTag: String): Flow<VoiceEvent> = flow {
        emit(VoiceEvent.Listening)
        repeat(20) { emit(VoiceEvent.Level(Random.nextFloat())); delay(80) }
        emit(VoiceEvent.Partial("there's a man collapsed"))
        delay(400)
        emit(VoiceEvent.Final("There's a man collapsed near the food stalls, he's not breathing"))
    }
    override fun stopListening() = Unit
    override suspend fun speak(text: String, languageTag: String) = Unit
    override fun stopSpeaking() = Unit
}

/** Not fake data: an honest empty state until the TRUST lane ships the real dataset. */
class EmptyHelpPointRepository : HelpPointRepository {
    override suspend fun all(): List<HelpPoint> = emptyList()
    override suspend fun nearest(from: GeoPoint, categories: Set<HelpCategory>, limit: Int): List<RankedHelpPoint> = emptyList()
}

class EmptyProtocolRepository : ProtocolRepository {
    override suspend fun all(): List<ProtocolCard> = emptyList()
    override suspend fun byId(id: String): ProtocolCard? = null
    override suspend fun bestFor(type: IncidentType, subtype: String?): ProtocolCard? = null
}
