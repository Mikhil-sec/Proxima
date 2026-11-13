package mu.proxima.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PacketKind {
    INCIDENT,       // payload = Incident JSON
    SEALED,         // payload = encrypted bytes (base64) only recipientKey can open (e.g. FoundNotice)
    TRIAGE_REQUEST, // Tier 2 → Tier 1: payload = RawReport JSON (AI lane)
    TRIAGE_RESULT,  // Tier 1 → Tier 2: payload = TriageResult JSON (AI lane)
    BEACON,         // periodic presence: payload = Beacon JSON (topology / density)
    CONTROL,        // engine-to-engine control, e.g. UWB session params (LOCATE lane)
}

/** Wire format. Relays mutate only ttl, hopCount and path — everything else is signed. */
@Serializable
data class MeshPacket(
    val packetId: String,
    val kind: PacketKind,
    val originNodeId: String,
    /** Base64 Ed25519 public key of the origin, used to verify [signature]. */
    val originKey: String,
    val createdAtMs: Long,
    val severity: Severity = Severity.MEDIUM,
    val ttl: Int,
    val hopCount: Int = 0,
    val path: List<String> = emptyList(),
    val payload: String,
    /** For SEALED / directed packets: base64 public key of the only reader. */
    val recipientKey: String? = null,
    /** Free-form sub-channel for CONTROL packets, e.g. "uwb". */
    val channel: String? = null,
    val signature: String? = null,
) {
    /** Bytes covered by the signature. Stable across relays. */
    fun signingBytes(): ByteArray =
        listOf(packetId, kind.name, originNodeId, originKey, createdAtMs.toString(), severity.name,
            payload, recipientKey.orEmpty(), channel.orEmpty())
            .joinToString("|")
            .encodeToByteArray()
}

@Serializable
enum class DeviceTier { TIER1_COMPUTE, TIER2_SCOUT, COMMAND_CENTER }

@Serializable
enum class Transport { BLE, WIFI_DIRECT, NEARBY_AUTO, ACOUSTIC, SIMULATED }

@Serializable
data class Beacon(
    val nodeId: String,
    val tier: DeviceTier,
    val hasUwb: Boolean,
    val llmReady: Boolean,
    /** Coarse location only (rounded by sender). */
    val location: GeoPoint? = null,
)

data class Peer(
    val nodeId: String,
    val shortCode: String,
    val tier: DeviceTier,
    val transport: Transport,
    val rssiDbm: Int? = null,
    val lastSeenMs: Long,
    val location: GeoPoint? = null,
    val hasUwb: Boolean = false,
    val llmReady: Boolean = false,
    /** True if directly connected (1 hop); false if only known via beacons. */
    val direct: Boolean = true,
)

/** One packet crossing one link — drives the live hop visualizer. */
data class HopEvent(
    val packetId: String,
    val kind: PacketKind,
    val severity: Severity,
    val fromNodeId: String,
    val toNodeId: String,
    val atMs: Long,
)
