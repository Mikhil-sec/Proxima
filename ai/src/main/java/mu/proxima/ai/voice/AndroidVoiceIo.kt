package mu.proxima.ai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.model.VoiceEvent
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Offline-first speech in and out.
 * - listen(): on-device SpeechRecognizer (Android 12+) in the closest installed language; if that
 *   fails on language, one retry with the standard recognizer preferring offline. Emits Listening →
 *   Level… → Partial… → Final | Error, then completes.
 * - speak(): TextToSpeech; suspends until the audio has played. A new speak() interrupts the old one.
 */
class AndroidVoiceIo(
    context: Context,
    private val log: (String) -> Unit = {},
) : VoiceIo {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val requiredPermissions: List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    // ---------------------------------------------------------------- speech-to-text

    @Volatile private var activeRecognizer: SpeechRecognizer? = null
    @Volatile private var activeStop: (() -> Unit)? = null

    override fun listen(languageTag: String): Flow<VoiceEvent> = callbackFlow {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(VoiceEvent.Error(VoiceSupport.errorReason(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)))
            close(); awaitClose { }; return@callbackFlow
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            trySend(VoiceEvent.Error("No speech recognizer on this phone. Type the report instead."))
            close(); awaitClose { }; return@callbackFlow
        }

        val candidates = VoiceSupport.languageCandidates(languageTag)
        var recognizer: SpeechRecognizer? = null
        var lastPartial: String? = null   // best text so far, delivered as Final on hold-to-talk release (#9)
        var stopRequested = false
        var finished = false
        var lastLevelAt = 0L

        fun finish(event: VoiceEvent) {
            if (finished) return
            finished = true
            trySend(event)
            close()
        }
        /** Final with what was heard, or [errorCode]'s message if nothing was. */
        fun finishWithHeard(errorCode: Int) =
            finish(lastPartial?.let { VoiceEvent.Final(it) } ?: VoiceEvent.Error(VoiceSupport.errorReason(errorCode)))

        fun start(onDevice: Boolean, language: String, sr: SpeechRecognizer) {
            if (finished) { sr.destroy(); return } // released during the language check: don't start a dangling mic
            recognizer?.takeIf { it !== sr }?.destroy()
            recognizer = sr
            activeRecognizer = sr
            log("voice: listening onDevice=$onDevice lang=$language")
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { trySend(VoiceEvent.Listening) }
                override fun onRmsChanged(rmsdB: Float) {
                    val now = SystemClock.elapsedRealtime()
                    if (VoiceSupport.shouldEmitLevel(now, lastLevelAt)) { // ~12 Hz for the SOS ring (#9)
                        lastLevelAt = now
                        trySend(VoiceEvent.Level(VoiceSupport.normaliseRms(rmsdB)))
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.firstText()?.let { lastPartial = it; trySend(VoiceEvent.Partial(it)) }
                }
                override fun onResults(results: Bundle?) {
                    val text = results.firstText()
                    if (text != null) finish(VoiceEvent.Final(text)) else finishWithHeard(SpeechRecognizer.ERROR_NO_MATCH)
                }
                override fun onError(error: Int) {
                    if (onDevice && !stopRequested && VoiceSupport.isLanguageError(error)) {
                        log("voice: on-device language error $error → standard recognizer")
                        start(onDevice = false, language = language, sr = SpeechRecognizer.createSpeechRecognizer(app))
                        return
                    }
                    log("voice: error $error (stopRequested=$stopRequested, heard=${lastPartial != null})")
                    if (stopRequested || VoiceSupport.isNothingHeardError(error)) finishWithHeard(error)
                    else finish(VoiceEvent.Error(VoiceSupport.errorReason(error)))
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            sr.startListening(recognizerIntent(language))
        }

        // Hold-to-talk release: stop capture, let the recognizer finish, but never wait more than 2 s.
        val stopThis: () -> Unit = {
            if (!stopRequested && !finished) {
                stopRequested = true
                recognizer?.stopListening()
                launch { delay(2_000); finishWithHeard(SpeechRecognizer.ERROR_NO_MATCH) }
            }
        }
        activeStop = stopThis

        launch {
            val onDeviceOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
            if (onDeviceOk) {
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                val installed = try {
                    installedOnDeviceLanguages(sr, candidates.first())
                } catch (e: CancellationException) {
                    sr.destroy() // listener cancelled during the check: don't leak the recognizer
                    throw e
                }
                // Unknown (API < 33 or check failed) → just try the first candidate; onError retries if needed.
                val lang = if (installed == null) candidates.first() else VoiceSupport.pickInstalled(candidates, installed)
                if (lang != null) { start(onDevice = true, language = lang, sr = sr); return@launch }
                log("voice: no on-device language among $candidates (installed=$installed)")
                sr.destroy()
            }
            start(onDevice = false, language = candidates.first(), sr = SpeechRecognizer.createSpeechRecognizer(app))
        }

        awaitClose {
            if (activeStop === stopThis) activeStop = null
            recognizer?.let { if (activeRecognizer === it) activeRecognizer = null; it.destroy() }
        }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer must be created and used on the main thread

    /** Hold-to-talk release: the open listen() flow ends with Final(what was heard) within ~2 s. */
    override fun stopListening() {
        main.post { activeStop?.invoke() }
    }

    private fun recognizerIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Panicked people pause mid-sentence; don't cut them off too early.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
    }

    /** Installed on-device languages (API 33+), or null when unknown. */
    private suspend fun installedOnDeviceLanguages(sr: SpeechRecognizer, probeLanguage: String): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { cont ->
                sr.checkRecognitionSupport(recognizerIntent(probeLanguage), app.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (cont.isActive) cont.resume(support.installedOnDeviceLanguages)
                    }
                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- text-to-speech

    private val ttsReady = CompletableDeferred<Boolean>()
    @Volatile private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Bumped by every speak()/stopSpeaking(); an older speak() stops queueing chunks when it changes. */
    private val generation = AtomicInteger(0)

    private fun engine(): TextToSpeech = tts ?: synchronized(this) {
        tts ?: TextToSpeech(app) { status ->
            ttsReady.complete(status == TextToSpeech.SUCCESS)
            log("voice: tts init ${if (status == TextToSpeech.SUCCESS) "ok" else "FAILED ($status)"}")
        }.also { t ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
                override fun onError(utteranceId: String, errorCode: Int) { pending.remove(utteranceId)?.complete(Unit) }
                override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
            })
            tts = t
        }
    }

    override suspend fun speak(text: String, languageTag: String) {
        val myGen = generation.incrementAndGet()
        val engine = engine()
        if (withTimeoutOrNull(5_000) { ttsReady.await() } != true) { log("voice: tts not ready"); return }
        if (generation.get() != myGen) return

        setBestLanguage(engine, languageTag)
        val chunks = VoiceSupport.chunk(text, TextToSpeech.getMaxSpeechInputLength())
        for ((i, chunk) in chunks.withIndex()) {
            if (generation.get() != myGen) return
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>().also { pending[id] = it }
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(chunk, mode, null, id) != TextToSpeech.SUCCESS) { pending.remove(id); return }
            try {
                // Safety timeout in case the engine never calls back (~150 ms per char is very slow speech).
                withTimeoutOrNull(5_000L + chunk.length * 150L) { done.await() }
            } catch (e: CancellationException) {
                if (generation.get() == myGen) engine.stop()
                throw e
            } finally {
                pending.remove(id)
            }
        }
    }

    override fun stopSpeaking() {
        generation.incrementAndGet()
        tts?.stop()
    }

    private fun setBestLanguage(engine: TextToSpeech, tag: String) {
        for (candidate in VoiceSupport.languageCandidates(tag)) {
            val result = engine.setLanguage(Locale.forLanguageTag(candidate))
            if (result >= TextToSpeech.LANG_AVAILABLE) return
        }
        log("voice: no TTS voice for $tag; using engine default")
    }
}
