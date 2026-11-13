package mu.proxima.ai.triage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.TriageSource

/**
 * Prompt + strict output validation for LLM triage.
 * The model only classifies (type, severity, subtype, summary, landmark, count); it never writes
 * advice. Medical steps come from TRUST's cited protocol cards. Subtype codes match RuleTriageEngine
 * so ProtocolRepository.bestFor() resolves the same cards for both paths.
 */
object TriagePrompt {

    val MEDICAL_SUBTYPES = setOf(
        "CARDIAC_ARREST", "UNCONSCIOUS", "SEIZURE", "BLEEDING", "CHEST_PAIN", "HEAT_EXHAUSTION", "FRACTURE", "GENERAL",
    )
    val HAZARD_SUBTYPES = setOf("CROWD_CRUSH", "FIRE", "ELECTRICAL", "FALLEN_BRANCH", "FLOOD")
    val PERSON_SUBTYPES = setOf("CHILD", "ELDERLY", "PERSON")
    private val PERSON_TYPES = setOf(IncidentType.LOST_PERSON, IncidentType.SIGHTING, IncidentType.FOUND)

    val SYSTEM = """
        You classify emergency reports from a crowded festival in Mauritius.
        Reports may be in English, French or Mauritian Kreol.
        Reply with ONLY one JSON object, no markdown, no extra text:
        {"type":"...","severity":"...","subtype":"...","summary":"...","landmark":"...","peopleCount":0}
        type: LOST_PERSON | SIGHTING | FOUND | MEDICAL | HAZARD | ALL_CLEAR
        severity: LOW | MEDIUM | HIGH | CRITICAL
        subtype for MEDICAL: ${MEDICAL_SUBTYPES.joinToString(" | ")}
        subtype for HAZARD: ${HAZARD_SUBTYPES.joinToString(" | ")}
        subtype for LOST_PERSON, SIGHTING, FOUND: CHILD | ELDERLY | PERSON
        subtype for ALL_CLEAR: null
        summary: English, at most 140 characters, no names, no advice.
        landmark: the place mentioned, or null. peopleCount: number of people involved, or null.
        Never give advice or instructions.
    """.trimIndent()

    fun user(report: RawReport): String {
        val hint = report.typeHint?.let { "\nThe reporter pressed the $it button." } ?: ""
        return "Report: \"${report.text.trim()}\"$hint\nJSON:"
    }

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * Parses and validates model output. Returns null when type or severity is missing or invalid,
     * so the caller falls back to rules. Everything else is normalised, never trusted as-is.
     */
    fun parse(raw: String, report: RawReport): TriageResult? {
        val obj = extractJsonObject(raw) ?: return null

        val llmType = obj.str("type")?.let { enumOrNull<IncidentType>(it) } ?: return null
        var severity = obj.str("severity")?.let { enumOrNull<Severity>(it) } ?: return null
        // A pressed category button wins, same as RuleTriageEngine.
        val type = report.typeHint ?: llmType

        val subtype = normaliseSubtype(type, obj.str("subtype"))
        if (type == IncidentType.LOST_PERSON && subtype == "CHILD") severity = Severity.CRITICAL

        val summary = obj.str("summary")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: report.text.trim().replace(Regex("\\s+"), " ")

        return TriageResult(
            type = type,
            severity = severity,
            subtype = subtype,
            summary = summary.take(140),
            landmark = obj.str("landmark")?.trim()?.take(60)?.takeIf { it.isNotEmpty() },
            peopleCount = obj.int("peopleCount")?.takeIf { it in 1..999 },
            protocolId = null, // pipeline resolves via ProtocolRepository.bestFor()
            source = TriageSource.ON_DEVICE_LLM,
            confidence = 0.8f,
        )
    }

    private fun normaliseSubtype(type: IncidentType, raw: String?): String? {
        val s = raw?.trim()?.uppercase()?.replace(' ', '_')
        return when (type) {
            IncidentType.MEDICAL -> s?.takeIf { it in MEDICAL_SUBTYPES } ?: "GENERAL"
            IncidentType.HAZARD -> s?.takeIf { it in HAZARD_SUBTYPES }
            in PERSON_TYPES -> s?.takeIf { it in PERSON_SUBTYPES } ?: "PERSON"
            else -> null
        }
    }

    /** Finds the first {...} block; tolerates ```json fences and chatter around it. */
    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenientJson.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.contentOrNull?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toDoubleOrNull()?.toInt()

    private inline fun <reified E : Enum<E>> enumOrNull(s: String): E? =
        enumValues<E>().firstOrNull { it.name == s.trim().uppercase().replace(' ', '_') }
}
