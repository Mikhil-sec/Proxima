package mu.proxima.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.fake.FakeVoiceIo
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.pipeline.RuleTriageEngine

/**
 * AI lane entry point. The app calls ONLY this. Keep signatures stable.
 * Target: Tier 1 → on-device LLM (falls back to rules); Tier 2 → offload to a Tier 1 peer (falls back to rules).
 */
object AiModule {
    fun createTriage(
        context: Context,
        capabilities: DeviceCapabilities,
        mesh: MeshEngine,
        trust: TrustEngine,
        scope: CoroutineScope,
        simulation: Boolean,
    ): TriageEngine {
        // TODO(AI): tiered engine (LLM / offload / rules) + TriageServer answering TRIAGE_REQUEST on Tier 1
        return RuleTriageEngine()
    }

    fun createVoice(context: Context, simulation: Boolean): VoiceIo {
        // TODO(AI): offline SpeechRecognizer + TextToSpeech
        return FakeVoiceIo()
    }
}
