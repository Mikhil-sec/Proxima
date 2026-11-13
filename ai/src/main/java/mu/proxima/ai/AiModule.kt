package mu.proxima.ai

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.fake.FakeVoiceIo
import mu.proxima.ai.triage.GeminiNanoBackend
import mu.proxima.ai.triage.LlmTriageEngine
import mu.proxima.ai.voice.AndroidVoiceIo
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.DeviceTier
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
        // Tier 1: on-device LLM (Gemini Nano), with RuleTriageEngine fallback built in.
        // Capability reports RULES_ONLY until the model is actually on the phone.
        // TODO(AI): Tier 2 offload to a Tier 1 peer (TRIAGE_REQUEST/RESULT) + TriageServer on Tier 1.
        if (capabilities.tier != DeviceTier.TIER1_COMPUTE) return RuleTriageEngine()
        return LlmTriageEngine(
            backend = GeminiNanoBackend(),
            scope = scope,
            log = { Log.i("Proxima", it) },
        )
    }

    fun createVoice(context: Context, simulation: Boolean): VoiceIo {
        // Real offline-first voice whenever the phone has a recognizer (even in SIM mode, so it gets tested).
        // The scripted fake is only for emulators without one.
        if (simulation && !SpeechRecognizer.isRecognitionAvailable(context)) return FakeVoiceIo()
        return AndroidVoiceIo(context, log = { Log.i("Proxima", it) })
    }
}
