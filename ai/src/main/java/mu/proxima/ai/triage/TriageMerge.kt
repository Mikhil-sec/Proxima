package mu.proxima.ai.triage

import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageResult

/**
 * Safety net shared by every model path (local LLM and peer offload): keyword rules may RAISE
 * severity, never lower it. Under-triage is the dangerous error.
 */
object TriageMerge {
    fun merge(llm: TriageResult, rules: TriageResult): TriageResult {
        // Keyword rules found something CRITICAL of another type that the model under-rated
        // ("not breathing" read as LOST_PERSON after a wrong button tap): rules win type + severity (#16).
        if (rules.confidence >= 0.6f && rules.severity == Severity.CRITICAL &&
            llm.severity != Severity.CRITICAL && rules.type != llm.type
        ) {
            return llm.copy(
                type = rules.type,
                subtype = rules.subtype,
                severity = Severity.CRITICAL,
                landmark = llm.landmark ?: rules.landmark,
                peopleCount = llm.peopleCount ?: rules.peopleCount,
                confidence = 0.9f,
            )
        }
        val rulesMatched = rules.confidence >= 0.6f && rules.type == llm.type
        val rulesRaise = rulesMatched && rules.severity > llm.severity
        return llm.copy(
            severity = if (rulesRaise) rules.severity else llm.severity,
            // The rule that raised severity explains it (e.g. lost PERSON → lost CHILD), so its subtype wins.
            subtype = if (rulesRaise && rules.subtype != null) rules.subtype else llm.subtype,
            landmark = llm.landmark ?: rules.landmark,
            peopleCount = llm.peopleCount ?: rules.peopleCount,
            confidence = if (rulesMatched) 0.9f else llm.confidence,
        )
    }
}
