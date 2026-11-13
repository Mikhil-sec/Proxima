package mu.proxima.ai.triage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageResult
import mu.proxima.core.pipeline.RuleTriageEngine
import kotlin.time.TimeSource

/**
 * Tier 1 triage: on-device LLM first, RuleTriageEngine whenever the model is missing, slow,
 * throws, or returns anything that does not validate. Never fails, never blocks longer than [timeoutMs].
 *
 * Safety net: if the keyword rules match the same incident type with a HIGHER severity
 * (e.g. "not breathing" → CRITICAL), the higher severity wins. Under-triage is the dangerous error.
 */
class LlmTriageEngine(
    private val backend: LlmBackend,
    private val scope: CoroutineScope,
    private val rules: RuleTriageEngine = RuleTriageEngine(),
    private val timeoutMs: Long = 6_000,
    private val recheckMs: Long = 60_000,
    private val log: (String) -> Unit = {},
) : TriageEngine {

    private val _capability = MutableStateFlow(TriageCapability.RULES_ONLY)
    override val capability: StateFlow<TriageCapability> = _capability.asStateFlow()

    /** One generation at a time; on-device runtimes don't parallelise well. */
    private val gate = Mutex()

    /** Child scope with its own SupervisorJob: a crashing generation must never cancel the app scope. */
    private val genScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    init {
        // Poll until the model is usable (e.g. Gemini Nano finishes downloading), then stop.
        scope.launch {
            while (isActive) {
                if (backend.isReady()) {
                    _capability.value = TriageCapability.ON_DEVICE_LLM
                    log("triage: ${backend.name} ready → ON_DEVICE_LLM")
                    break
                }
                delay(recheckMs)
            }
        }
    }

    override suspend fun triage(report: RawReport): TriageResult {
        val fallback = rules.classify(report)
        if (_capability.value != TriageCapability.ON_DEVICE_LLM) return fallback

        // Run in the engine scope and only *await* with a timeout, so a runtime that ignores
        // cancellation still can't hold the SOS past the budget.
        val started = TimeSource.Monotonic.markNow()
        val job = genScope.async { gate.withLock { backend.generate(TriagePrompt.SYSTEM, TriagePrompt.user(report)) } }
        val raw = try {
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (e: Exception) {
            log("triage: ${backend.name} failed (${e.javaClass.simpleName}: ${e.message}) → rules")
            null
        }
        if (raw == null) {
            if (job.isActive) { job.cancel(); log("triage: ${backend.name} timeout ${timeoutMs}ms → rules") }
            return fallback
        }

        val parsed = TriagePrompt.parse(raw, report)
            ?: return fallback.also { log("triage: invalid LLM output → rules: ${raw.take(200)}") }
        val merged = TriageMerge.merge(parsed, fallback)
        // Success used to be silent, so a demo phone could only be checked on screen. Labels only, never report text.
        val raisedBy = if (label(merged) != label(parsed)) " (before rules: ${label(parsed)})" else ""
        log("triage: ON_DEVICE_LLM ${label(merged)} in ${started.elapsedNow().inWholeMilliseconds} ms$raisedBy")
        return merged
    }

    private fun label(r: TriageResult) = "${r.type}/${r.subtype ?: "-"}/${r.severity}"
}
