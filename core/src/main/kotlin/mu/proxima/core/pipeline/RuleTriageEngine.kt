package mu.proxima.core.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageResult
import mu.proxima.core.model.TriageSource

/**
 * Keyword fallback classifier. Runs on every phone, always works, no model needed.
 * The AI lane wraps this: LLM first, this when the LLM is missing, slow or unsure.
 * Keywords cover English, French and Kreol Morisien. The Kreol entries are best-effort guesses:
 * AI lane must have a Mauritian teammate verify/extend them (tracked in docs/lanes/AI.md).
 */
class RuleTriageEngine : TriageEngine {
    override val capability: StateFlow<TriageCapability> = MutableStateFlow(TriageCapability.RULES_ONLY)

    private data class Rule(val type: IncidentType, val subtype: String?, val severity: Severity, val words: List<String>) {
        // Word-start boundary so "heat" does not match "theatre" ("dehydrat" still matches "dehydrated").
        val patterns = words.map { Regex("(?<!\\p{L})" + Regex.escape(it)) }
        fun matches(text: String) = patterns.any { it.containsMatchIn(text) }
    }

    private fun List<String>.anyWordIn(text: String) =
        any { Regex("(?<!\\p{L})" + Regex.escape(it)).containsMatchIn(text) }

    // Order matters: first match wins, most critical first.
    private val rules = listOf(
        Rule(IncidentType.MEDICAL, "CARDIAC_ARREST", Severity.CRITICAL,
            listOf("not breathing", "no pulse", "pa respire", "respire pas", "respire plus", "cardiac", "heart attack", "cpr")),
        Rule(IncidentType.HAZARD, "CROWD_CRUSH", Severity.CRITICAL,
            listOf("crush", "stampede", "bousculade", "pushing", "squeezed", "trampled", "can't breathe in the crowd", "pous pous")),
        Rule(IncidentType.HAZARD, "FIRE", Severity.CRITICAL,
            listOf("fire", "smoke", "flames", "dife", "feu", "fumee", "fumée", "lafime", "burning")),
        Rule(IncidentType.MEDICAL, "UNCONSCIOUS", Severity.CRITICAL,
            listOf("unconscious", "collapsed", "fainted", "passed out", "evanoui", "évanoui", "sans connaissance", "tom feb")),
        Rule(IncidentType.MEDICAL, "SEIZURE", Severity.HIGH,
            listOf("seizure", "convulsion", "fits", "epilep", "épilep", "kriz")),
        Rule(IncidentType.MEDICAL, "BLEEDING", Severity.HIGH,
            listOf("bleeding", "blood", "saigne", "disan", "cut", "wound", "blessure")),
        Rule(IncidentType.MEDICAL, "CHEST_PAIN", Severity.HIGH,
            listOf("chest pain", "douleur poitrine", "lestoma fermal")),
        Rule(IncidentType.MEDICAL, "HEAT_EXHAUSTION", Severity.MEDIUM,
            listOf("heat", "dehydrat", "dizzy", "sunstroke", "insolation", "latet pe vire", "tourdi")),
        Rule(IncidentType.MEDICAL, "FRACTURE", Severity.MEDIUM,
            listOf("broken", "fracture", "kase", "cassé", "sprain", "twisted")),
        Rule(IncidentType.HAZARD, "ELECTRICAL", Severity.HIGH,
            listOf("live wire", "electric", "kouran", "électrique", "electrocut")),
        Rule(IncidentType.HAZARD, "FALLEN_BRANCH", Severity.MEDIUM,
            listOf("branch", "tree fell", "fallen tree", "branche", "arbre", "pie dibwa", "labranch")),
        Rule(IncidentType.HAZARD, "FLOOD", Severity.MEDIUM,
            listOf("flood", "inondation", "water rising", "delo pe monte", "lavalas")),
        Rule(IncidentType.FOUND, null, Severity.HIGH,
            listOf("found a child", "found a kid", "found him", "found her", "retrouvé", "retrouve", "mo finn trouv")),
        Rule(IncidentType.LOST_PERSON, null, Severity.HIGH,
            listOf("lost", "missing", "can't find", "cannot find", "separated", "wandered off", "perdi", "disparu", "pa trouv")),
        Rule(IncidentType.SIGHTING, null, Severity.MEDIUM,
            listOf("crying alone", "alone", "wandering", "saw a child", "seen a child", "tousel", "tou sel")),
        Rule(IncidentType.MEDICAL, "GENERAL", Severity.MEDIUM,
            listOf("injured", "hurt", "sick", "malad", "malade", "blese", "blessé", "doctor", "dokter", "ambulance", "help")),
    )

    private val childWords = listOf("child", "kid", "boy", "girl", "my son", "our son", "her son", "his son", "daughter", "zanfan", "enfant", "garson", "tifi", "fiy", "fille", "baby", "bebe")
    private val elderWords = listOf("old man", "old woman", "elderly", "grandmother", "grandfather", "granny", "granmer", "granper", "dadi", "nana", "vieux", "vieille")
    private val landmarkRegex = Regex("""\b(?:near|next to|beside|behind|at|pre|kot|près de|à côté de)\s+(?:the\s+|le\s+|la\s+)?([\p{L}\d' -]{3,32})""", RegexOption.IGNORE_CASE)
    private val countRegex = Regex("""(\d{1,3})\s*(?:people|persons|ppl|dimounn|personnes|injured|victims)""", RegexOption.IGNORE_CASE)

    override suspend fun triage(report: RawReport): TriageResult = classify(report)

    /** CHILD / ELDERLY / PERSON from the words, for lost-person, sighting and found reports. Also used by the LLM sanitiser. */
    fun personSubtype(text: String): String {
        val t = text.lowercase()
        return when {
            childWords.anyWordIn(t) -> "CHILD"
            elderWords.anyWordIn(t) -> "ELDERLY"
            else -> "PERSON"
        }
    }

    fun classify(report: RawReport): TriageResult {
        val text = report.text.lowercase()
        // #16: a panicking user can tap the wrong quick button. The button keeps its type unless the
        // words match a CRITICAL rule of another type ("not breathing", "fire", "stampede"...): then the words win.
        // A lost child is CRITICAL too, so it also beats a wrong button.
        val lostRule = rules.first { it.type == IncidentType.LOST_PERSON }
        val foundRule = rules.first { it.type == IncidentType.FOUND } // "found a child who lost her mum" stays FOUND
        val critical = rules.firstOrNull { r -> r.severity == Severity.CRITICAL && r.matches(text) }
            ?: lostRule.takeIf { it.matches(text) && childWords.anyWordIn(text) && !foundRule.matches(text) }
        val hinted = report.typeHint?.takeUnless { critical != null && critical.type != it }
        val match = (if (hinted == null) critical else null)
            ?: rules.firstOrNull { r -> (hinted == null || r.type == hinted) && r.matches(text) }
            ?: rules.firstOrNull { r -> r.matches(text) }

        val type = hinted ?: match?.type ?: IncidentType.MEDICAL
        var subtype = if (match?.type == type) match.subtype else null
        var severity = if (match?.type == type) match.severity else Severity.MEDIUM

        if (type == IncidentType.LOST_PERSON || type == IncidentType.SIGHTING || type == IncidentType.FOUND) {
            subtype = personSubtype(text)
            if (type == IncidentType.LOST_PERSON && subtype == "CHILD") severity = Severity.CRITICAL
        }

        return TriageResult(
            type = type,
            severity = severity,
            subtype = subtype,
            summary = report.text.trim().replace(Regex("\\s+"), " ").take(140),
            landmark = landmarkRegex.find(report.text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', '!'),
            peopleCount = countRegex.find(report.text)?.groupValues?.get(1)?.toIntOrNull(),
            protocolId = null, // pipeline resolves via ProtocolRepository.bestFor()
            source = TriageSource.RULES,
            confidence = if (match != null) 0.6f else 0.3f,
        )
    }
}
