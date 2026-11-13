package mu.proxima.ai.debug

import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.ai.triage.TriagePrompt
import mu.proxima.core.model.RawReport
import java.io.File

/**
 * DEBUG-ONLY device spike: which on-device LLM runtime should the AI lane use?
 * Measures load time, per-triage latency, memory and JSON validity for each runtime.
 *
 * Launch:   adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity
 * Options:  --ez nanoDownload true   allow Gemini Nano download via AICore (off by default)
 *           --es backend cpu|gpu     LiteRT-LM backend (default gpu, falls back to cpu)
 *           --ez voice true          voice check only: speak a sentence, then listen once (mic permission needed)
 *           --es lang en-MU|fr-MU|mfe  language for the voice check
 *           --ez guide true          hands-free guide on the real protocols.json; --es card cpr-adult (default)
 * LiteRT-LM runs only if a *.litertlm file is in /sdcard/Android/data/mu.proxima.app/files/models/
 * Results:  adb logcat -d -s Proxima:*
 */
class LlmProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            setPadding(32, 120, 32, 32)
            textSize = 12f
            setTextColor(0xFF39FF14.toInt())
            setBackgroundColor(0xFF000000.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(out) })

        val allowNanoDownload = intent.getBooleanExtra("nanoDownload", false)
        val backend = intent.getStringExtra("backend") ?: "gpu"
        if (intent.getBooleanExtra("guide", false)) {
            scope.launch { runCatching { probeGuide(intent.getStringExtra("card") ?: "cpr-adult") }.onFailure { log("GUIDE error: ${it.javaClass.simpleName}: ${it.message}") } }
            return
        }
        if (intent.getBooleanExtra("voice", false)) {
            scope.launch { runCatching { probeVoice(intent.getStringExtra("lang") ?: "en-MU") }.onFailure { log("VOICE error: ${it.javaClass.simpleName}: ${it.message}") } }
            return
        }
        scope.launch {
            log("=== LLM probe start · ${android.os.Build.MODEL} · sdk ${android.os.Build.VERSION.SDK_INT} ===")
            mem("baseline")
            runCatching { probeGeminiNano(allowNanoDownload) }.onFailure { log("NANO error: ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { probeLiteRtLm(backend) }.onFailure { log("LITERT error: ${it.javaClass.simpleName}: ${it.message}") }
            log("=== LLM probe done ===")
        }
    }

    override fun onDestroy() { guide?.stop(); scope.cancel(); super.onDestroy() }

    // ---------- Gemini Nano (ML Kit GenAI Prompt API, runs inside AICore) ----------
    private suspend fun probeGeminiNano(allowDownload: Boolean) {
        log("--- Gemini Nano (ML Kit Prompt API) ---")
        val model = Generation.getClient()
        val status = model.checkStatus()
        log("NANO status=${statusName(status)}")
        when (status) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE -> {
                if (!allowDownload) { log("NANO downloadable; rerun with --ez nanoDownload true to fetch it"); return }
                model.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> log("NANO download started")
                        is DownloadStatus.DownloadProgress -> Log.d(TAG, "NANO ${s.totalBytesDownloaded} bytes")
                        DownloadStatus.DownloadCompleted -> log("NANO download complete")
                        is DownloadStatus.DownloadFailed -> log("NANO download failed: ${s.e.message}")
                    }
                }
                if (model.checkStatus() != FeatureStatus.AVAILABLE) return
            }
            else -> return
        }
        CASES.forEachIndexed { i, case ->
            val t0 = SystemClock.elapsedRealtime()
            val text = withTimeoutOrNull(TIMEOUT_MS) {
                model.generateContent(
                    generateContentRequest(SystemInstruction(SYSTEM_PROMPT), TextPart(userPrompt(case))) {
                        temperature = 0.0f
                        topK = 1
                        maxOutputTokens = 160
                    }
                ).candidates.firstOrNull()?.text
            }
            report("NANO", i, case, text, SystemClock.elapsedRealtime() - t0)
        }
        mem("after nano")
        model.close()
    }

    // ---------- LiteRT-LM (Gemma .litertlm sideloaded into app storage) ----------
    private suspend fun probeLiteRtLm(backendName: String) {
        log("--- LiteRT-LM ---")
        val dir = File(getExternalFilesDir(null), "models")
        val modelFile = dir.listFiles { f -> f.name.endsWith(".litertlm") }?.maxByOrNull { it.length() }
        if (modelFile == null) { log("LITERT no *.litertlm in ${dir.path}; skipped"); return }
        log("LITERT model=${modelFile.name} size=${modelFile.length() / 1_048_576} MB backend=$backendName")

        val engine = withContext(Dispatchers.Default) {
            fun build(b: Backend) = Engine(EngineConfig(modelPath = modelFile.path, backend = b, cacheDir = cacheDir.path))
            val t0 = SystemClock.elapsedRealtime()
            val e = try {
                build(if (backendName == "cpu") Backend.CPU() else Backend.GPU()).also { it.initialize() }
            } catch (t: Throwable) {
                log("LITERT $backendName init failed (${t.message}); retrying on CPU")
                build(Backend.CPU()).also { it.initialize() }
            }
            log("LITERT load_ms=${SystemClock.elapsedRealtime() - t0}")
            e
        }
        mem("after litert load")
        try {
            CASES.forEachIndexed { i, case ->
                val t0 = SystemClock.elapsedRealtime()
                val text = withTimeoutOrNull(TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        // fresh conversation per report so earlier cases don't leak into the context
                        engine.createConversation(
                            ConversationConfig(systemInstruction = Contents.of(SYSTEM_PROMPT))
                        ).use { conv ->
                            val sb = StringBuilder()
                            conv.sendMessageAsync(userPrompt(case)).collect { sb.append(it.toString()) }
                            sb.toString()
                        }
                    }
                }
                report("LITERT", i, case, text, SystemClock.elapsedRealtime() - t0)
            }
            mem("after litert runs")
        } finally {
            engine.close()
        }
    }

    // ---------- Hands-free guide check ----------
    @kotlinx.serialization.Serializable
    private data class ProtocolFile(val cards: List<mu.proxima.core.model.ProtocolCard>)

    private var guide: mu.proxima.core.engine.GuideEngine? = null

    private suspend fun probeGuide(cardId: String) {
        val json = assets.open("data/protocols.json").bufferedReader().use { it.readText() }
        val cards = mu.proxima.core.pipeline.ProximaJson.decodeFromString(ProtocolFile.serializer(), json).cards
        val repo = object : mu.proxima.core.engine.ProtocolRepository {
            override suspend fun all() = cards
            override suspend fun byId(id: String) = cards.firstOrNull { it.id == id }
            override suspend fun bestFor(type: mu.proxima.core.model.IncidentType, subtype: String?) = null
        }
        val card = repo.byId(cardId) ?: run { log("GUIDE no card $cardId (have ${cards.map { it.id }})"); return }
        val caps = mu.proxima.core.model.DeviceCapabilities(mu.proxima.core.model.DeviceTier.TIER1_COMPUTE, android.os.Build.MODEL, false, false, 0.0, false)
        val voice = mu.proxima.ai.voice.AndroidVoiceIo(this, log = { log(it) })
        val g = mu.proxima.ai.AiModule.createGuide(voice, repo, caps, scope).also { guide = it }
        log("=== GUIDE probe start · ${card.id} · ${cards.size} cards ===")
        scope.launch {
            g.state.collect { s -> if (s.active) log("GUIDE [${s.cardId} ${s.stepIndex + 1}/${s.stepCount}] listening=${s.listening} heard=${s.heard} notice=${s.notice}") }
        }
        g.start(card)
    }

    // ---------- VoiceIo check ----------
    private suspend fun probeVoice(lang: String) {
        log("=== VOICE probe start · lang=$lang ===")
        val voice = mu.proxima.ai.voice.AndroidVoiceIo(this, log = { log(it) })
        val t0 = SystemClock.elapsedRealtime()
        voice.speak("Voice check. After the beep, describe an emergency in one sentence.", lang)
        log("VOICE speak_ms=${SystemClock.elapsedRealtime() - t0}")
        var levels = 0
        var maxLevel = 0f
        val t1 = SystemClock.elapsedRealtime()
        voice.listen(lang).collect { e ->
            when (e) {
                is mu.proxima.core.model.VoiceEvent.Level -> { levels++; maxLevel = maxOf(maxLevel, e.rms) }
                else -> log("VOICE +${SystemClock.elapsedRealtime() - t1}ms $e")
            }
        }
        log("VOICE levels=$levels maxLevel=${"%.2f".format(maxLevel)}")
        log("=== VOICE probe done ===")
    }

    // ---------- helpers ----------
    private fun report(tag: String, i: Int, case: String, raw: String?, ms: Long) {
        if (raw == null) { log("$tag case$i TIMEOUT >${TIMEOUT_MS}ms"); return }
        val parsed = runCatching { TriagePrompt.parse(raw, RawReport(text = case, createdAtMs = 0)) ?: error("invalid") }
        val verdict = parsed.fold({ "OK ${it.type}/${it.severity}/${it.subtype}" }, { "BAD_JSON(${it.javaClass.simpleName})" })
        log("$tag case$i ms=$ms $verdict")
        Log.i(TAG, "$tag case$i in=\"$case\" out=${raw.replace('\n', ' ').take(400)}")
    }

    private fun mem(label: String) {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pssMb = Debug.getPss() / 1024
        log("MEM $label: app_pss=${pssMb}MB device_avail=${mi.availMem / 1_048_576}MB lowMemory=${mi.lowMemory}")
    }

    private fun statusName(s: Int) = when (s) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($s)"
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    private companion object {
        const val TAG = "Proxima"
        const val TIMEOUT_MS = 15_000L // probe only; production budget is 6 s (see docs/lanes/AI.md)

        val CASES = listOf(
            "A lady is bleeding from her head near the main entrance, two people with her",
            "Mo pa trouv mo zanfan, enn tifi 6 an, rob rouz, li ti pre kot delo",
            "Il y a de la fumée et des flammes derrière les stands de nourriture",
            "an old man just collapsed near the temple steps, he is not breathing",
        )

        // Same prompt + validator as the shipped LlmTriageEngine, so the probe measures what ships.
        fun userPrompt(report: String) = TriagePrompt.user(RawReport(text = report, createdAtMs = 0))
        val SYSTEM_PROMPT = TriagePrompt.SYSTEM
    }
}
