package mu.proxima.core.model

import kotlinx.serialization.Serializable

/**
 * Everything that travels on the mesh is an Incident of one of these types.
 * One pipeline, many uses — do not add parallel message systems.
 */
@Serializable
enum class IncidentType {
    LOST_PERSON, // guardian reports a missing child/person (minimal description, no name, no photo)
    SIGHTING,    // "I think I saw them here" — only shown to guardian + command center
    FOUND,       // person found; links to a LOST_PERSON via relatedIncidentId
    MEDICAL,     // someone needs medical help
    HAZARD,      // fire, fallen branch, crush risk, structural, electrical...
    ALL_CLEAR;   // closes an incident via relatedIncidentId

    /** Which help points the guided-response screen should route to. */
    val helpCategories: Set<HelpCategory>
        get() = when (this) {
            LOST_PERSON, SIGHTING, FOUND -> setOf(HelpCategory.MEETING_POINT, HelpCategory.POLICE)
            MEDICAL -> setOf(HelpCategory.FIRST_AID_POST, HelpCategory.HEALTH_CENTRE, HelpCategory.HOSPITAL)
            HAZARD -> setOf(HelpCategory.FIRE_STATION, HelpCategory.POLICE, HelpCategory.ASSEMBLY_POINT)
            ALL_CLEAR -> emptySet()
        }
}

@Serializable
enum class Severity(val hopBudget: Int) {
    LOW(3), MEDIUM(5), HIGH(7), CRITICAL(10);
}

/** Who classified the incident — shown in the UI so judges see the tiering. */
@Serializable
enum class TriageSource { ON_DEVICE_LLM, OFFLOADED_LLM, RULES, MANUAL }

@Serializable
data class Incident(
    val id: String,
    val type: IncidentType,
    val severity: Severity,
    /** Machine code, e.g. CARDIAC_ARREST, FIRE, FALLEN_BRANCH, CHILD. Free-form UPPER_SNAKE. */
    val subtype: String? = null,
    /** Human summary, ≤ 140 chars. Never a person's name. */
    val summary: String,
    val location: GeoPoint? = null,
    val landmark: String? = null,
    val peopleCount: Int? = null,
    val createdAtMs: Long,
    /** Ephemeral node id of the reporting phone — NOT a person identifier. */
    val reporterNodeId: String,
    val protocolId: String? = null,
    val relatedIncidentId: String? = null,
    val reportCount: Int = 1,
    val triagedBy: TriageSource = TriageSource.RULES,
    val confidence: Float = 1f,
)

/** Local view of an incident: the incident plus what this phone knows about it. */
data class IncidentRecord(
    val incident: Incident,
    val signatureValid: Boolean,
    val hopsTravelled: Int,
    val lastUpdatedMs: Long,
    val resolved: Boolean = false,
    /**
     * Base64 public key of the phone that reported it (from the packet envelope). Lets a finder seal a
     * FoundNotice to the guardian of a LOST_PERSON report without any wristband hardware.
     */
    val originKey: String? = null,
)
