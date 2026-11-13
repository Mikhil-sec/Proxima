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
import mu.proxima.core.model.TriageResult
import mu.proxima.core.pipeline.ProximaJson
import java.io.File

/**
 * DEBUG-ONLY device spike: which on-device LLM runtime should the AI lane use?
 * Measures load time, per-triage latency, memory and JSON validity for each runtime.
 *
 * Launch:   adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity
 * Options:  --ez nanoDownload true   allow Gemini Nano download via AICore (off by default)
 *           --es backend cpu|gpu     LiteRT-LM backend (default gpu, falls back to cpu)
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
        scope.launch {
            log("=== LLM probe start · ${android.os.Build.MODEL} · sdk ${android.os.Build.VERSION.SDK_INT} ===")
            mem("baseline")
            runCatching { probeGeminiNano(allowNanoDownload) }.onFailure { log("NANO error: ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { probeLiteRtLm(backend) }.onFailure { log("LITERT error: ${it.javaClass.simpleName}: ${it.message}") }
            log("=== LLM probe done ===")
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

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

    // ---------- helpers ----------
    private fun report(tag: String, i: Int, case: String, raw: String?, ms: Long) {
        if (raw == null) { log("$tag case$i TIMEOUT >${TIMEOUT_MS}ms"); return }
        val json = raw.substringAfter("{", "").substringBeforeLast("}", "").let { if (it.isEmpty()) "" else "{$it}" }
        val parsed = runCatching {
            // `source`/`confidence` are filled by our code, not the model
            val withMeta = json.trimEnd('}') + ""","source":"ON_DEVICE_LLM","confidence":0.0}"""
            ProximaJson.decodeFromString(TriageResult.serializer(), withMeta)
        }
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

        fun userPrompt(report: String) = "Report: \"$report\"\nJSON:"

        val SYSTEM_PROMPT = """
            You classify emergency reports from a crowded festival in Mauritius.
            Reports may be in English, French or Mauritian Kreol.
            Reply with ONLY one JSON object, no markdown, no extra text:
            {"type":"...","severity":"...","subtype":"...","summary":"...","landmark":"...","peopleCount":0}
            type: LOST_PERSON | SIGHTING | FOUND | MEDICAL | HAZARD | ALL_CLEAR
            severity: LOW | MEDIUM | HIGH | CRITICAL
            subtype for MEDICAL: CARDIAC_ARREST | UNCONSCIOUS | SEIZURE | BLEEDING | CHEST_PAIN | HEAT_EXHAUSTION | FRACTURE | GENERAL
            subtype for HAZARD: CROWD_CRUSH | FIRE | ELECTRICAL | FALLEN_BRANCH | FLOOD
            subtype for other types: null
            summary: English, at most 140 characters, no names.
            landmark: the place mentioned, or null. peopleCount: number of people involved, or null.
            Never give advice or instructions.
        """.trimIndent()
    }
}
