package mu.proxima.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import mu.proxima.core.model.CrowdRisk
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.GuideState
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
import mu.proxima.core.model.RolePass
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

    /**
     * True if [publicKey] is this node's: its current key, or (with per-session identity, #38) a previous
     * session's key it still retains. Use this, not `== identity.publicKey`, so an app restart does not lose
     * notices sealed to the guardian before it.
     */
    fun ownsKey(publicKey: String): Boolean = publicKey == identity.publicKey

    /** Same as [ownsKey] for node ids, so "is this lost report mine?" survives an app restart. */
    fun ownsNodeId(nodeId: String): Boolean = nodeId == identity.nodeId

    /**
     * True only if [pass] is signed by the event organiser, unexpired at [nowMs], AND [packet] (e.g. the holder's
     * beacon) verifies and was signed by `pass.holderKey` - so an overheard pass cannot be replayed by another phone.
     * Default false: an engine without the organiser's key never shows anyone as verified (#38).
     */
    fun verifyRoleClaim(pass: RolePass, packet: MeshPacket, nowMs: Long): Boolean = false

    // ---------- Friend find (#38 item 3). Defaults = feature off, so the fake and simulation are unchanged. ----------

    /**
     * Tag to put in this node's beacon at [atMs]. Rotates every 10 minutes and is derived from a secret only this
     * node's paired friends hold, so bystanders cannot link two windows and nobody broadcasts a search.
     */
    fun beaconTag(atMs: Long): String? = null

    /** The local label of the paired friend whose beacon carried [tag] (current or adjacent window), else null. */
    fun matchFriend(tag: String, atMs: Long): String? = null

    /** Six digits both phones show for the pair (this node, [peerKey]). Users confirm they match before pairing. */
    fun pairingCode(peerKey: String): String? = null

    /** This node's beacon secret sealed to [peerKey]. Send only after both screens showed the same [pairingCode]. */
    fun friendInvite(peerKey: String): String? = null

    /**
     * Stores a friend from the invite [peerKey] sealed to this node. [label] is a local nickname that never leaves
     * the phone. False if the invite does not open or was not made by [peerKey].
     */
    fun acceptFriend(peerKey: String, sealedInvite: String, label: String): Boolean = false

    /** Local labels of paired friends. */
    fun friends(): List<String> = emptyList()

    /** Forget a friend on this phone. To stop THEM finding you, see the engine's own secret rotation. */
    fun removeFriend(label: String) = Unit

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

/** AI lane (#30). Reads a vetted card step by step and follows voice commands. Never authors steps. */
interface GuideEngine {
    val state: StateFlow<GuideState>
    fun start(card: ProtocolCard)
    fun next()
    fun back()
    fun repeat()
    fun stop()
}

/** AI lane (#30). Crowd-crush early warning from anonymous density cells + incident reports. */
interface CrowdRiskEngine {
    /** Only areas at WATCH or above, most severe first. */
    val risks: StateFlow<List<CrowdRisk>>
}
