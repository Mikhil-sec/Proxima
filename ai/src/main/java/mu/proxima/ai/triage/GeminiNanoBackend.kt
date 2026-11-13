package mu.proxima.ai.triage

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * Gemini Nano through ML Kit's Prompt API. The model lives in Google's AICore system service,
 * not in our APK, and runs fully offline once downloaded.
 * This backend never starts a download itself (no surprise data use); [isReady] is false until
 * the model is on the phone. The debug LlmProbeActivity can request the download.
 */
class GeminiNanoBackend : LlmBackend {
    override val name = "gemini-nano"
    private val model by lazy { Generation.getClient() }

    override suspend fun isReady(): Boolean =
        runCatching { model.checkStatus() == FeatureStatus.AVAILABLE }.getOrDefault(false)

    override suspend fun generate(system: String, user: String): String {
        val response = model.generateContent(
            generateContentRequest(SystemInstruction(system), TextPart(user)) {
                temperature = 0.0f // classification: we want the same answer every time
                topK = 1
                maxOutputTokens = 200
            }
        )
        return response.candidates.firstOrNull()?.text ?: error("empty response")
    }
}
