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

// ---- #30: hands-free guided first aid (AI lane) ----

/** What the hands-free guide is doing right now. Steps always come from a vetted [ProtocolCard]. */
data class GuideState(
    val active: Boolean = false,
    val cardId: String? = null,
    val cardTitle: String? = null,
    val source: String? = null,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val stepText: String? = null,
    val listening: Boolean = false,
    /** Caption of what the user just said. */
    val heard: String? = null,
    /** Shown when a voice command changed the card, e.g. "Breathing → recovery position". */
    val notice: String? = null,
)

// ---- #30: crowd-crush early warning (AI lane) ----

enum class CrowdRiskLevel { WATCH, WARNING, DANGER }

/** Risk for one anonymous ~25 m grid cell. No individual is identified or tracked. */
data class CrowdRisk(
    val cellCentre: GeoPoint,
    val level: CrowdRiskLevel,
    /** Phones seen within ~40 m (3×3 grid cells). App users only: a lower bound on the real crowd. */
    val phonesInArea: Int,
    /** Crowd reports within 75 m in the last 10 min (merged reports count each reporter). */
    val crowdReports: Int,
    /** Plain-language reason, e.g. "9 phones within 40 m (up from 3) · 2 crowd reports". */
    val reason: String,
)
