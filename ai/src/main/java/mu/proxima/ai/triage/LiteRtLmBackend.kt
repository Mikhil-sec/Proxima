package mu.proxima.ai.triage

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gemma 4 E2B (or any *.litertlm model) through LiteRT-LM, sideloaded into
 * `/sdcard/Android/data/mu.proxima.app/files/models/`. Never downloaded by the app.
 *
 * The model loads ONCE in the background when this is created (GPU, falling back to CPU), and the
 * single instance is shared by triage and the hands-free guide: a 2.6 GB model can't be loaded twice.
 * [isReady] is false while loading, if no model file exists, or if loading failed, so callers keep
 * using peer offload / keyword rules exactly as before.
 */
class LiteRtLmBackend internal constructor(
    private val modelDir: File,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val loader: (File) -> LoadedModel,
) : LlmBackend {

    constructor(modelDir: File, cacheDir: File, scope: CoroutineScope, log: (String) -> Unit = {}) :
        this(modelDir, scope, log, { file -> LiteRtLmModel.load(file, cacheDir, log) })

    /** The runtime behind the backend; a seam so JVM tests don't need the native library. */
    interface LoadedModel {
        /** Must stop promptly when the calling coroutine is cancelled (timeout), or it blocks the next caller. */
        suspend fun generate(system: String, user: String): String
        fun close()
    }

    override val name = "gemma-litertlm"

    private val model = CompletableDeferred<LoadedModel?>()

    /** One conversation at a time: triage and the guide share one engine. */
    private val gate = Mutex()

    init {
        scope.launch(Dispatchers.Default) {
            val file = modelDir.listFiles { f -> f.name.endsWith(".litertlm") }?.maxByOrNull { it.length() }
            if (file == null) {
                log("llm: no *.litertlm in ${modelDir.path}; on-device LLM off")
                model.complete(null)
                return@launch
            }
            model.complete(
                runCatching { loader(file) }
                    .onFailure { log("llm: ${file.name} failed to load (${it.javaClass.simpleName}: ${it.message})") }
                    .getOrNull()
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun isReady(): Boolean = model.isCompleted && model.getCompleted() != null

    override suspend fun generate(system: String, user: String): String {
        val m = model.await() ?: error("no on-device model")
        return gate.withLock { withContext(Dispatchers.Default) { m.generate(system, user) } }
    }
}

/** The real LiteRT-LM engine. Android only (native code); not exercised by JVM tests. */
private class LiteRtLmModel(private val engine: Engine) : LiteRtLmBackend.LoadedModel {

    override suspend fun generate(system: String, user: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            // classification: same answer every time
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        )
        // Fresh conversation per call so earlier reports never leak into the context.
        val conversation = engine.createConversation(config)
        try {
            // Streaming, not the blocking sendMessage: when triage's 6 s timeout cancels us, collection
            // stops and we cancel the native generation, so the shared engine is free for the next SOS.
            val text = StringBuilder()
            conversation.sendMessageAsync(user).collect { chunk ->
                chunk.contents.contents.filterIsInstance<Content.Text>().forEach { text.append(it.text) }
            }
            return text.toString().ifBlank { error("empty response") }
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } finally {
            conversation.close()
        }
    }

    override fun close() = engine.close()

    companion object {
        fun load(file: File, cacheDir: File, log: (String) -> Unit): LiteRtLmModel {
            fun build(backend: Backend) =
                Engine(EngineConfig(modelPath = file.path, backend = backend, cacheDir = cacheDir.path))
            val t0 = SystemClock.elapsedRealtime()
            val engine = try {
                build(Backend.GPU()).also { it.initialize() }
            } catch (t: Throwable) {
                log("llm: GPU init failed (${t.message}); using CPU")
                build(Backend.CPU()).also { it.initialize() }
            }
            log("llm: ${file.name} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            return LiteRtLmModel(engine)
        }
    }
}

/**
 * Uses the first backend that is ready, in order. Lets one build run Gemma (sideloaded) on our
 * phones and Gemini Nano on phones where AICore offers it, with no change to the callers.
 */
class FirstReadyBackend(private val backends: List<LlmBackend>) : LlmBackend {
    override val name: String get() = backends.joinToString("|") { it.name }

    private suspend fun active(): LlmBackend? = backends.firstOrNull { runCatching { it.isReady() }.getOrDefault(false) }

    override suspend fun isReady(): Boolean = active() != null

    override suspend fun generate(system: String, user: String): String =
        (active() ?: error("no ready LLM backend")).generate(system, user)
}
