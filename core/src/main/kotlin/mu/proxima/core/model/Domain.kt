package mu.proxima.core.model

import kotlinx.serialization.Serializable

// ---------- Device tiering ----------

data class DeviceCapabilities(
    val tier: DeviceTier,
    val model: String,
    val hasUwb: Boolean,
    val hasCompass: Boolean,
    val totalRamGb: Double,
    val isLargeScreen: Boolean,
)

// ---------- AI / triage ----------

@Serializable
data class RawReport(
    val text: String,
    val createdAtMs: Long,
    val location: GeoPoint? = null,
    /** Set when the user tapped a category button before speaking. */
    val typeHint: IncidentType? = null,
)

@Serializable
data class TriageResult(
    val type: IncidentType,
    val severity: Severity,
    val subtype: String? = null,
    val summary: String,
    val landmark: String? = null,
    val peopleCount: Int? = null,
    val protocolId: String? = null,
    val source: TriageSource,
    val confidence: Float,
)

enum class TriageCapability { ON_DEVICE_LLM, OFFLOAD_TO_PEER, RULES_ONLY }

sealed interface VoiceEvent {
    data object Listening : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Level(val rms: Float) : VoiceEvent // for the live waveform
    data class Error(val reason: String) : VoiceEvent
}

// ---------- Locate / radar ----------

enum class FixSource { UWB, GPS, RSSI, SIMULATED }

enum class Proximity { FAR, NEAR, CLOSE, HERE }

data class RadarFix(
    val targetNodeId: String,
    val distanceM: Double?,
    /** Absolute bearing to target, degrees from north. */
    val bearingDeg: Double?,
    /** Bearing relative to where the phone points (0 = ahead). Null when no heading. */
    val relativeBearingDeg: Double?,
    val source: FixSource,
    val proximity: Proximity,
    val accuracyM: Double?,
    val atMs: Long,
)

data class TrackTarget(
    val nodeId: String,
    val label: String,
    /** Latest known positions of the target (e.g. from sealed FoundNotice updates). */
    val locations: kotlinx.coroutines.flow.Flow<GeoPoint>,
)

// ---------- Trust / pairing ----------

data class NodeIdentity(val nodeId: String, val publicKey: String)

/**
 * Printed on the child's wristband as a QR. Contains NO personal data:
 * a random tag id and the guardian's public key.
 */
@Serializable
data class WristbandTag(
    val tagId: String,
    val guardianKey: String,
    val createdAtMs: Long,
) {
    fun toQrPayload(): String = "PRX1:$tagId:$guardianKey:$createdAtMs"

    companion object {
        fun parse(qr: String): WristbandTag? {
            val parts = qr.trim().split(":")
            if (parts.size != 4 || parts[0] != "PRX1") return null
            val created = parts[3].toLongOrNull() ?: return null
            return WristbandTag(parts[1], parts[2], created)
        }
    }
}

/** Encrypted to the guardian. Relays cannot read it. */
@Serializable
data class FoundNotice(
    val tagId: String,
    val finderNodeId: String,
    val location: GeoPoint? = null,
    val landmark: String? = null,
    val note: String? = null,
    val atMs: Long,
)

// ---------- Dataset ----------

@Serializable
enum class HelpCategory {
    HOSPITAL, HEALTH_CENTRE, FIRST_AID_POST, PHARMACY, POLICE, FIRE_STATION,
    AMBULANCE, ASSEMBLY_POINT, MEETING_POINT, DEFIBRILLATOR, WATER, TOILETS,
}

@Serializable
data class HelpPoint(
    val id: String,
    val name: String,
    val category: HelpCategory,
    val location: GeoPoint,
    /** Dataset attribution, e.g. "OpenStreetMap (ODbL)" or "data.govmu.org". */
    val source: String,
    val phone: String? = null,
    val openingHours: String? = null,
)

data class RankedHelpPoint(
    val point: HelpPoint,
    val distanceM: Double,
    val bearingDeg: Double,
)

@Serializable
data class ProtocolStep(
    val text: String,
    /** Optional timer for the step (e.g. CPR cycles). */
    val durationSec: Int? = null,
)

/** Vetted guidance card. The AI only SELECTS a card — it never writes medical steps. */
@Serializable
data class ProtocolCard(
    val id: String,
    val title: String,
    val appliesTo: List<IncidentType>,
    val subtypes: List<String> = emptyList(),
    val steps: List<ProtocolStep>,
    /** Citation of the authoritative source the steps were taken from. */
    val source: String,
)
