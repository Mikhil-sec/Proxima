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
        val hint = report.typeHint?.let { "\nThe reporter pressed the $it button, but panicking people press the wrong one: classify from the words." } ?: ""
        return "Report: \"${report.text.trim()}\"$hint\nJSON:"
    }

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }
    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses and validates model output. Returns null when type or severity is missing or invalid,
     * so the caller falls back to rules. Everything else is normalised, never trusted as-is.
     */
    fun parse(raw: String, report: RawReport): TriageResult? {
        val obj = extractJsonObject(raw) ?: return null
        val rawType = obj.str("type") ?: return null
        // Small models sometimes put the subtype in the type field ("type":"CROWD_CRUSH"): recover it.
        val (type, typeAsSubtype) = enumOrNull<IncidentType>(rawType)?.let { it to null }
            ?: subtypeAsType(rawType) ?: return null
        val severity = obj.str("severity")?.let { enumOrNull<Severity>(it) } ?: return null
        return sanitize(
            TriageResult(
                type = type,
                severity = severity,
                subtype = typeAsSubtype ?: obj.str("subtype"),
                summary = obj.str("summary").orEmpty(),
                landmark = obj.str("landmark"),
                peopleCount = obj.int("peopleCount"),
                source = TriageSource.ON_DEVICE_LLM,
                confidence = 0.8f,
            ),
            report,
        )
    }

    /**
     * Normalises any model-produced result, local or received from a peer, so it can't carry
     * anything we wouldn't produce ourselves: button rule (#16), valid subtype, lost child = CRITICAL,
     * clamped text and counts, and NO protocolId (cards are always resolved locally from vetted data).
     */
    fun sanitize(result: TriageResult, report: RawReport): TriageResult {
        // A pressed category button wins, same as RuleTriageEngine, unless the model rates a different
        // type CRITICAL (#16: panicking users tap the wrong button; the words win for life-threatening cases).
        val hint = report.typeHint
        // A lost child is always CRITICAL, so it overrides a wrong button like any other critical report.
        val lostChild = result.type == IncidentType.LOST_PERSON && normaliseSubtype(result.type, result.subtype) == "CHILD"
        val modelCritical = result.severity == Severity.CRITICAL || lostChild
        val type = if (hint != null && !(modelCritical && result.type != hint)) hint else result.type

        val subtype = normaliseSubtype(type, result.subtype)
        val severity = if (type == IncidentType.LOST_PERSON && subtype == "CHILD") Severity.CRITICAL else result.severity

        val summary = result.summary.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }
            ?: report.text.trim().replace(WHITESPACE, " ")

        return result.copy(
            type = type,
            severity = severity,
            subtype = subtype,
            summary = summary.take(140),
            landmark = result.landmark?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }?.take(60),
            peopleCount = result.peopleCount?.takeIf { it in 1..999 },
            protocolId = null, // pipeline resolves via ProtocolRepository.bestFor()
            confidence = result.confidence.coerceIn(0f, 1f),
        )
    }

    private fun normaliseSubtype(type: IncidentType, raw: String?): String? {
        val s = raw?.trim()?.uppercase()?.replace(' ', '_')
        // Exact code first, then a code embedded in a longer label ("MEDICAL_SEIZURE" → SEIZURE).
        fun pick(codes: Set<String>) = s?.let { v -> codes.firstOrNull { it == v } ?: codes.firstOrNull { it in v } }
        return when (type) {
            IncidentType.MEDICAL -> pick(MEDICAL_SUBTYPES) ?: "GENERAL"
            IncidentType.HAZARD -> pick(HAZARD_SUBTYPES)
            in PERSON_TYPES -> when {
                s == null -> "PERSON"
                "CHILD" in s || "KID" in s -> "CHILD" // "LOST_PERSON_CHILD"
                "ELDER" in s -> "ELDERLY"
                else -> "PERSON"
            }
            else -> null
        }
    }

    private fun subtypeAsType(raw: String): Pair<IncidentType, String>? {
        val s = raw.trim().uppercase().replace(' ', '_')
        return when (s) {
            in HAZARD_SUBTYPES -> IncidentType.HAZARD to s
            in MEDICAL_SUBTYPES -> IncidentType.MEDICAL to s
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
