package mu.proxima.ai.triage

/**
 * One on-device LLM runtime. LlmTriageEngine only talks to this, so swapping Gemini Nano for
 * a Gemma/LiteRT-LM backend does not touch the triage logic.
 */
interface LlmBackend {
    val name: String

    /** True when [generate] can run right now, with no download pending. Must not throw. */
    suspend fun isReady(): Boolean

    /** Raw model text for one prompt. May throw; the caller falls back to rules. */
    suspend fun generate(system: String, user: String): String
}
