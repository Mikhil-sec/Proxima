package mu.proxima.ai

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.TriageEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.fake.FakeVoiceIo
import mu.proxima.ai.guide.GuideEngine
import mu.proxima.ai.guide.GuideIntentClassifier
import mu.proxima.ai.guide.HandsFreeGuide
import mu.proxima.ai.offload.TieredTriageEngine
import mu.proxima.ai.offload.TriageServer
import mu.proxima.ai.triage.GeminiNanoBackend
import mu.proxima.ai.triage.LlmTriageEngine
import mu.proxima.ai.voice.AndroidVoiceIo
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.pipeline.PacketFactory

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
        // Every phone: local LLM (Tier 1) → offload to a peer with a ready LLM → RuleTriageEngine.
        // Capabilities flip by themselves when Nano finishes downloading or a helper appears/leaves.
        val log: (String) -> Unit = { Log.i("Proxima", it) }
        val packets = PacketFactory(trust)
        val local = if (capabilities.tier == DeviceTier.TIER1_COMPUTE) {
            LlmTriageEngine(backend = GeminiNanoBackend(), scope = scope, log = log).also {
                // Tier 1 also answers TRIAGE_REQUESTs for Tier 2 phones once its own model is ready.
                TriageServer(it, mesh, trust, packets, scope, log = log).start()
            }
        } else null
        return TieredTriageEngine(local, mesh, trust, packets, scope, log = log)
    }

    /**
     * Hands-free guided first aid (#30). Rules understand the commands; on Tier 1 the on-device LLM
     * also maps free speech onto the same closed command list.
     */
    fun createGuide(
        voice: VoiceIo,
        protocols: ProtocolRepository,
        capabilities: DeviceCapabilities,
        scope: CoroutineScope,
    ): GuideEngine {
        val log: (String) -> Unit = { Log.i("Proxima", it) }
        val llm = if (capabilities.tier == DeviceTier.TIER1_COMPUTE) GeminiNanoBackend() else null
        return HandsFreeGuide(voice, protocols, scope, classifier = GuideIntentClassifier(llm, log = log), log = log)
    }

    fun createVoice(context: Context, simulation: Boolean): VoiceIo {
        // Real offline-first voice whenever the phone has a recognizer (even in SIM mode, so it gets tested).
        // The scripted fake is only for emulators without one.
        if (simulation && !SpeechRecognizer.isRecognitionAvailable(context)) return FakeVoiceIo()
        return AndroidVoiceIo(context, log = { Log.i("Proxima", it) })
    }
}
