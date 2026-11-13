package mu.proxima.ai.eval

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.proxima.ai.triage.TriageMerge
import mu.proxima.ai.triage.TriagePrompt
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.TriageResult
import mu.proxima.core.pipeline.RuleTriageEngine
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Offline triage accuracy eval (see ai/eval/README.md). Skipped unless run with -PproximaEval=...
 *  - export: writes the EXACT shipped prompts for every case to eval/out/prompts.jsonl
 *  - score:  reads the model's raw replies (eval/out/<model>_raw.jsonl), runs them through the
 *            shipped parse → sanitize → rules safety net, and writes eval/REPORT.md
 */
class TriageEvalTest {
    private val mode = System.getProperty("proxima.eval").orEmpty()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dir = File("eval") // unit tests run with the module dir (ai/) as working dir

    @Serializable data class Case(
        val id: String, val lang: String, val text: String, val hint: String? = null,
        val types: List<String>, val severities: List<String>, val subtypes: List<String> = emptyList(),
    )
    @Serializable data class Prompt(val id: String, val system: String, val user: String)
    @Serializable data class Raw(val id: String, val raw: String? = null, val ms: Long? = null, val error: String? = null)

    private fun cases() = File(dir, "cases.jsonl").readLines().filter { it.isNotBlank() }.map { json.decodeFromString<Case>(it) }
    private fun Case.report() = RawReport(text = text, createdAtMs = 0, typeHint = hint?.let { IncidentType.valueOf(it) })

    @Test fun export() {
        assumeTrue(mode == "export")
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "prompts.jsonl").writeText(
            cases().joinToString("\n", postfix = "\n") { c ->
                json.encodeToString(Prompt(c.id, TriagePrompt.SYSTEM, TriagePrompt.user(c.report())))
            }
        )
    }

    private companion object { const val BUDGET_MS = 6_000L }

    private class Score(val name: String) {
        var type = 0; var subtype = 0; var severity = 0; var under = 0; var n = 0
        fun line() = "| $name | ${pct(type)} | ${pct(subtype)} | ${pct(severity)} | **$under** |"
        private fun pct(x: Int) = "$x/$n (${x * 100 / n.coerceAtLeast(1)}%)"
    }

    private fun Score.add(c: Case, r: TriageResult?): String {
        n++
        if (r == null) return "no valid JSON"
        val typeOk = r.type.name in c.types
        val subOk = typeOk && (c.subtypes.isEmpty() || r.subtype in c.subtypes)
        val sevOk = r.severity.name in c.severities
        val minSev = c.severities.minOf { mu.proxima.core.model.Severity.valueOf(it).ordinal }
        val isUnder = r.severity.ordinal < minSev
        if (typeOk) type++
        if (subOk) subtype++
        if (sevOk) severity++
        if (isUnder) under++
        val mark = when { isUnder -> "⚠ UNDER"; typeOk && subOk && sevOk -> "✓"; else -> "✗" }
        return "$mark ${r.type}/${r.subtype ?: "-"}/${r.severity}"
    }

    @Test fun score() {
        assumeTrue(mode.startsWith("score"))
        val model = mode.substringAfter("score:", "gemma").substringBefore("@")
        val device = mode.substringAfter("@", "device not recorded")
        val rawFile = File(dir, "out/${model}_raw.jsonl")
        val raws = rawFile.readLines().filter { it.isNotBlank() }.map { json.decodeFromString<Raw>(it) }.associateBy { it.id }
        val rules = RuleTriageEngine()

        val sRules = Score("Rules only (`RuleTriageEngine`)")
        val sModel = Score("$model alone (parsed + sanitised)")
        val sShip = Score("**Shipped: $model + rules safety net**")
        var valid = 0
        var late = 0
        val latencies = mutableListOf<Long>()
        val rows = cases().map { c ->
            val report = c.report()
            val r = rules.classify(report)
            val raw = raws[c.id]
            val parsed = raw?.raw?.let { TriagePrompt.parse(it, report) }
            if (parsed != null) valid++
            raw?.ms?.let { latencies += it }
            // The app waits at most 6 s (LlmTriageEngine.timeoutMs); a later reply means rules answered.
            val inTime = (raw?.ms ?: 0) <= BUDGET_MS
            if (parsed != null && !inTime) late++
            val shipped = parsed?.takeIf { inTime }?.let { TriageMerge.merge(it, r) } ?: r
            val expect = "${c.types.joinToString("/")} · ${c.subtypes.ifEmpty { listOf("-") }.joinToString("/")} · ${c.severities.joinToString("/")}"
            "| ${c.id} | ${c.lang} | ${c.hint ?: ""} | ${c.text.replace("|", "\\|")} | $expect | ${sRules.add(c, r)} | ${sModel.add(c, parsed)} | ${sShip.add(c, shipped)} |"
        }

        val n = rows.size
        val report = buildString {
            appendLine("# Triage accuracy eval")
            appendLine()
            appendLine("_Generated by `TriageEvalTest` (`-PproximaEval=score:$model`). Do not edit by hand; see `ai/eval/README.md`._")
            appendLine()
            appendLine("$n emergency reports (English, French, Mauritian Kreol, wrong quick button, ambiguous). Expected labels were written **before** any model was run; where two answers are reasonable, both are accepted. Kreol phrases are best-effort and not yet checked by a native speaker.")
            appendLine()
            appendLine("| Path | Type correct | Type + subtype | Severity in range | Under-triaged |")
            appendLine("|---|---|---|---|---|")
            appendLine(sRules.line()); appendLine(sModel.line()); appendLine(sShip.line())
            appendLine()
            appendLine("- Model replies that were valid JSON with a valid type and severity: **$valid/$n**.")
            appendLine("- Valid replies slower than the app's ${BUDGET_MS / 1000} s budget (the shipped path used rules instead): **$late**.")
            if (latencies.isNotEmpty()) appendLine("- Latency per report ($device): median ${latencies.sorted()[latencies.size / 2]} ms, max ${latencies.max()} ms.")
            appendLine("- **Under-triaged** = severity below the lowest acceptable level. It is the dangerous error, so it is counted separately.")
            appendLine("- The shipped path is the model's answer passed through `TriagePrompt.sanitize` and `TriageMerge` (keyword rules can raise severity, never lower it); if the reply is invalid or late, rules answer.")
            appendLine()
            appendLine("| id | lang | button | report | expected (type · subtype · severity) | rules | $model | shipped |")
            appendLine("|---|---|---|---|---|---|---|---|")
            rows.forEach { appendLine(it) }
        }
        File(dir, "REPORT.md").writeText(report)
        println(report.lines().take(14).joinToString("\n"))
    }
}
