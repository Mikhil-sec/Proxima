package mu.proxima.ai.guide

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.ai.triage.LlmBackend

/**
 * Keyword rules first; only phrases the rules don't understand go to the on-device LLM, which must
 * answer with ONE label from a closed list. The LLM never sees or produces protocol text.
 */
class GuideIntentClassifier(
    private val backend: LlmBackend?,
    private val timeoutMs: Long = 2_500,
    private val log: (String) -> Unit = {},
) {
    suspend fun classify(text: String, currentCardId: String?, currentCardTitle: String?): GuideIntent {
        val ruled = GuideCommands.interpret(text, currentCardId)
        if (ruled != GuideIntent.None || backend == null || text.isBlank()) return ruled
        if (!backend.isReady()) return GuideIntent.None

        val raw = try {
            withTimeoutOrNull(timeoutMs) { backend.generate(SYSTEM, user(text, currentCardTitle)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("guide: llm failed (${e.message})"); null
        } ?: return GuideIntent.None

        val label = Regex("[A-Z_]{4,}").findAll(raw.uppercase()).map { it.value }.firstOrNull { it in GuideCommands.LABELS }
            ?: return GuideIntent.None
        return GuideCommands.fromLabel(label, currentCardId).also { log("guide: llm \"$text\" → $label") }
    }

    private companion object {
        val SYSTEM = """
            A first-aid helper reads instructions aloud and listens to the rescuer.
            Classify what the rescuer said into exactly ONE label and reply with the label only:
            NEXT (ready for the next step), BACK (previous step), REPEAT (didn't hear / say again),
            STOP (end guidance, professionals arrived), NOT_BREATHING (casualty not breathing),
            BREATHING (casualty breathing normally now), BLEEDING (heavy bleeding), SEIZURE (fitting),
            UNRESPONSIVE (casualty unconscious), NONE (anything else).
        """.trimIndent()

        fun user(text: String, title: String?) = "Current card: ${title ?: "none"}\nRescuer said: \"$text\"\nLabel:"
    }
}
