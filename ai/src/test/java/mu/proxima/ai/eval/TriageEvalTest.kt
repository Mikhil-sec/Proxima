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

        // Kreol is best-effort and not tuned (pivot 18:04 Fri; spoken Kreol reaches triage via the French
        // recognizer), so it is scored separately from the English + French headline.
        class Group {
            val rules = Score("Rules only (`RuleTriageEngine`)")
            val model = Score("$model alone (parsed + sanitised)")
            val ship = Score("**Shipped: $model + rules safety net**")
        }
        val headline = Group(); val kreol = Group(); val heldOut = Group()
        // fs* = written by a teammate, labels verbatim, never used to tune anything: the honest number.
        val freshEnFr = Group(); val freshKreol = Group()
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
            val g = if (c.lang == "mfe") kreol else headline
            if (c.id.startsWith("ho") && c.lang != "mfe") { heldOut.rules.add(c, r); heldOut.model.add(c, parsed); heldOut.ship.add(c, shipped) }
            if (c.id.startsWith("fs")) (if (c.lang == "mfe") freshKreol else freshEnFr).let { f -> f.rules.add(c, r); f.model.add(c, parsed); f.ship.add(c, shipped) }
            val expect = "${c.types.joinToString("/")} · ${c.subtypes.ifEmpty { listOf("-") }.joinToString("/")} · ${c.severities.joinToString("/")}"
            "| ${c.id} | ${c.lang} | ${c.hint ?: ""} | ${c.text.replace("|", "\\|")} | $expect | ${g.rules.add(c, r)} | ${g.model.add(c, parsed)} | ${g.ship.add(c, shipped)} |"
        }

        val n = rows.size
        val report = buildString {
            appendLine("# Triage accuracy eval")
            appendLine()
            appendLine("_Generated by `TriageEvalTest` (`-PproximaEval=score:$model`). Do not edit by hand; see `ai/eval/README.md`._")
            appendLine()
            appendLine("$n emergency reports (wrong quick button and ambiguous cases included). Expected labels were written **before** any model was run; where two answers are reasonable, both are accepted.")
            appendLine()
            if (freshEnFr.ship.n + freshKreol.ship.n > 0) {
                appendLine("### Fresh set: written by a teammate, never used for tuning (the honest number)")
                appendLine()
                appendLine("Reports and expected labels written by another team member who had not seen the other cases; labels used verbatim (one accepted severity each, so stricter than the sets below). Run once, after all code changes.")
                appendLine()
                appendLine("| Path (English + French, ${freshEnFr.ship.n}) | Type correct | Type + subtype | Severity exact | Under-triaged |")
                appendLine("|---|---|---|---|---|")
                appendLine(freshEnFr.rules.line()); appendLine(freshEnFr.model.line()); appendLine(freshEnFr.ship.line())
                appendLine()
                appendLine("| Path (Kreol, best-effort, ${freshKreol.ship.n}) | Type correct | Type + subtype | Severity exact | Under-triaged |")
                appendLine("|---|---|---|---|---|")
                appendLine(freshKreol.rules.line()); appendLine(freshKreol.model.line()); appendLine(freshKreol.ship.line())
                appendLine()
            }
            appendLine("### English + French, all sets (${headline.ship.n} reports, includes cases used for tuning)")
            appendLine()
            appendLine("| Path | Type correct | Type + subtype | Severity in range | Under-triaged |")
            appendLine("|---|---|---|---|---|")
            appendLine(headline.rules.line()); appendLine(headline.model.line()); appendLine(headline.ship.line())
            appendLine()
            if (heldOut.ship.n > 0) {
                appendLine("Of these, the ${heldOut.ship.n} `ho*` reports were written after the first run but before any code fix. When first run (`REPORT_v2.md`) the shipped path under-triaged **5** of them. With the current code it under-triages **${heldOut.ship.under}** (rules alone: **${heldOut.rules.under}**), but the severity floor was designed after seeing those failures, so treat ${heldOut.ship.under} as optimistic. A fresh set written by a teammate is the clean check.")
                appendLine()
            }
            appendLine("### Mauritian Kreol: best-effort, not tuned (${kreol.ship.n} reports)")
            appendLine()
            appendLine("Kreol is not a supported language: no speech recognizer exists for it, so spoken Kreol reaches triage through the French recognizer, and these typed phrases were not checked by a native speaker. Shown for transparency only. The rules score is not meaningful here: the same person wrote the Kreol keywords and these test phrases, so they share words. What it does show is that the model alone is weak in Kreol and the rules safety net keeps severity up.")
            appendLine()
            appendLine("| Path | Type correct | Type + subtype | Severity in range | Under-triaged |")
            appendLine("|---|---|---|---|---|")
            appendLine(kreol.rules.line()); appendLine(kreol.model.line()); appendLine(kreol.ship.line())
            appendLine()
            appendLine("### Notes")
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
