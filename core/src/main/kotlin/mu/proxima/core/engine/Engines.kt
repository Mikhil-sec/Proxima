package mu.proxima.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.NodeIdentity
import mu.proxima.core.model.Peer
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.RadarFix
import mu.proxima.core.model.RankedHelpPoint
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.TrackTarget
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.VoiceEvent
import mu.proxima.core.model.WristbandTag

/*
 * THE CONTRACT. Every lane implements one or more of these; the UI only talks to these.
 * Changing anything here requires an `interface-change` GitHub issue first (see CLAUDE.md).
 * Every engine lists the Android runtime permissions it needs so the UI can request them in one place.
 */

/** MESH lane. Multi-hop store-and-forward. */
interface MeshEngine {
    val selfNodeId: String
    val requiredPermissions: List<String>
    val running: StateFlow<Boolean>
    val peers: StateFlow<List<Peer>>
    /** Packets delivered to THIS node, already de-duplicated. Relaying happens inside the engine. */
    val inbound: SharedFlow<MeshPacket>
    /** Every link crossing seen by this node, for the hop visualizer. */
    val hops: SharedFlow<HopEvent>

    suspend fun start()
    suspend fun stop()

    /** Send a signed packet. The engine handles ttl/path/relay/retry. */
    suspend fun send(packet: MeshPacket)
}

/** AI lane. Turns messy human input into a structured incident. Must never throw: fall back to rules. */
interface TriageEngine {
    val capability: StateFlow<TriageCapability>
    suspend fun triage(report: RawReport): TriageResult
}

/** AI lane. Offline speech-to-text and text-to-speech. */
interface VoiceIo {
    val requiredPermissions: List<String>
    /** Starts listening; completes after a Final or Error event. */
    fun listen(languageTag: String = "en-MU"): Flow<VoiceEvent>
    fun stopListening()
    suspend fun speak(text: String, languageTag: String = "en-MU")
    fun stopSpeaking()
}

/** LOCATE lane. Position, heading and radar fixes (UWB → GPS → RSSI fallback chain). */
interface LocateEngine {
    val requiredPermissions: List<String>
    val selfLocation: StateFlow<GeoPoint?>
    /** Degrees from true north; null when no compass. */
    val headingDeg: StateFlow<Float?>
    val uwbAvailable: Boolean

    suspend fun start()
    suspend fun stop()

    /** Continuous radar fixes toward a target. Picks the best available source. */
    fun track(target: TrackTarget): Flow<RadarFix>
}

/** TRUST lane. Identity, signing, sealed messages, wristband pairing. */
interface TrustEngine {
    val identity: NodeIdentity

    fun sign(packet: MeshPacket): MeshPacket
    fun verify(packet: MeshPacket): Boolean

    /** Encrypt so that only [recipientKey] can read. Returns base64. */
    fun seal(plaintext: ByteArray, recipientKey: String): String
    /** Returns null if not addressed to this node or tampered. */
    fun open(sealedB64: String): ByteArray?

    /** Guardian side: mint a new wristband tag bound to this phone's key. */
    fun createWristband(): WristbandTag
    /** Tags this phone minted (so it knows which FoundNotices are its own). */
    fun myWristbands(): List<WristbandTag>
}

/** TRUST lane. The real dataset (Mauritius help points). */
interface HelpPointRepository {
    suspend fun all(): List<HelpPoint>
    suspend fun nearest(from: GeoPoint, categories: Set<HelpCategory>, limit: Int = 3): List<RankedHelpPoint>
}

/** TRUST lane. Vetted response protocols (cited sources). */
interface ProtocolRepository {
    suspend fun all(): List<ProtocolCard>
    suspend fun byId(id: String): ProtocolCard?
    suspend fun bestFor(type: IncidentType, subtype: String?): ProtocolCard?
}

/** UI lane. Decides the tier at startup. */
interface DeviceCapabilityProvider {
    fun detect(): DeviceCapabilities
}
