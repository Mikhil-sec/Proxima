package mu.proxima.ai.guide

import kotlinx.coroutines.flow.StateFlow
import mu.proxima.core.model.ProtocolCard

// TEMPORARY HOME: these mirror the additive contract proposed in #30. When UI gives 👍 they move to
// core/model/Domain.kt + core/engine/Engines.kt in a small dedicated PR, and only the imports change.

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

/** AI lane. Reads a vetted card step by step and follows voice commands. */
interface GuideEngine {
    val state: StateFlow<GuideState>
    fun start(card: ProtocolCard)
    fun next()
    fun back()
    fun repeat()
    fun stop()
}
