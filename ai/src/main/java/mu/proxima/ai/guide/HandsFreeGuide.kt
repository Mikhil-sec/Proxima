package mu.proxima.ai.guide

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.ai.voice.VoiceSupport
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.VoiceEvent
import mu.proxima.core.model.GuideState
import mu.proxima.core.engine.GuideEngine

/**
 * Hands-free guided first aid: speak one vetted step → listen → act on the command → repeat.
 * - Never listens while speaking (no self-hearing), and buttons share the same command queue as voice.
 * - Steps with durationSec (e.g. 2 min of compressions) advance on their own.
 * - Condition changes ("he's breathing") switch to another vetted card by id. Steps are never altered.
 */
class HandsFreeGuide(
    private val voice: VoiceIo,
    private val protocols: ProtocolRepository,
    private val scope: CoroutineScope,
    private val classifier: GuideIntentClassifier = GuideIntentClassifier(null),
    private val languageTag: String = "en-MU",
    private val relistenDelayMs: Long = 300,
    private val log: (String) -> Unit = {},
) : GuideEngine {

    private val _state = MutableStateFlow(GuideState())
    override val state: StateFlow<GuideState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var commands = Channel<GuideIntent>(Channel.UNLIMITED)

    override fun start(card: ProtocolCard) {
        job?.cancel()
        voice.stopSpeaking()
        val ch = Channel<GuideIntent>(Channel.UNLIMITED).also { commands = it }
        job = scope.launch {
            try {
                run(card, ch)
            } finally {
                voice.stopListening()
            }
        }
    }

    // Buttons: cut the current sentence short and act immediately.
    override fun next() { voice.stopSpeaking(); commands.trySend(GuideIntent.Next) }
    override fun back() { voice.stopSpeaking(); commands.trySend(GuideIntent.Back) }
    override fun repeat() { voice.stopSpeaking(); commands.trySend(GuideIntent.Repeat) }
    override fun stop() { voice.stopSpeaking(); commands.trySend(GuideIntent.Stop) }

    private suspend fun run(first: ProtocolCard, ch: Channel<GuideIntent>) = coroutineScope {
        if (first.steps.isEmpty()) { _state.value = GuideState(); return@coroutineScope }
        var card = first
        var idx = 0
        var notice: String? = null

        show(card, idx, notice)
        say("${card.title}.")
        sayStep(card, idx)

        while (isActive) {
            val listener = launch { listenLoop(card, ch) }
            val timerMs = card.steps[idx].durationSec?.let { it * 1_000L }
            val intent = if (timerMs != null) withTimeoutOrNull(timerMs) { ch.receive() } ?: GuideIntent.TimerElapsed else ch.receive()
            listener.cancelAndJoin()
            _state.update { it.copy(listening = false) }

            when (intent) {
                GuideIntent.Next, GuideIntent.TimerElapsed -> if (idx < card.steps.lastIndex) {
                    idx++; show(card, idx, notice); sayStep(card, idx)
                } else if (intent != GuideIntent.TimerElapsed) {
                    say("That was the last step. Say repeat to hear it again, or stop.")
                }
                GuideIntent.Back -> { if (idx > 0) idx--; show(card, idx, notice); sayStep(card, idx) }
                GuideIntent.Repeat -> sayStep(card, idx)
                GuideIntent.Stop -> {
                    say("Guide stopped.")
                    _state.value = GuideState()
                    return@coroutineScope
                }
                is GuideIntent.SwitchTo -> {
                    val next = runCatching { protocols.byId(intent.cardId) }.getOrNull()
                    if (next == null || next.steps.isEmpty()) {
                        log("guide: card ${intent.cardId} missing, staying on ${card.id}")
                    } else {
                        notice = "${intent.reason} → ${next.title}"
                        log("guide: ${card.id} → ${next.id} (${intent.reason})")
                        card = next; idx = 0
                        show(card, idx, notice)
                        say("${intent.reason}. Switching to: ${card.title}.")
                        sayStep(card, idx)
                    }
                }
                GuideIntent.None -> Unit
            }
        }
    }

    /** Listens repeatedly until one phrase maps to a command, then hands it to the main loop. */
    private suspend fun listenLoop(card: ProtocolCard, ch: Channel<GuideIntent>) = coroutineScope {
        var failures = 0
        while (isActive) {
            _state.update { it.copy(listening = true) }
            var heard: String? = null
            var error: String? = null
            voice.listen(languageTag).collect { e ->
                when (e) {
                    is VoiceEvent.Partial -> _state.update { it.copy(heard = e.text) }
                    is VoiceEvent.Final -> heard = e.text
                    is VoiceEvent.Error -> error = e.reason
                    else -> Unit
                }
            }
            val text = heard
            if (text != null) {
                failures = 0
                _state.update { it.copy(heard = text) }
                val intent = classifier.classify(text, card.id, card.title)
                if (intent != GuideIntent.None) { ch.send(intent); return@coroutineScope }
            } else if (error != null && error !in SILENCE) { // silence during CPR is normal: keep listening
                failures++
                val fatal = error!!.contains("permission", ignoreCase = true) || error!!.contains("No speech recognizer")
                if (fatal || failures >= MAX_LISTEN_FAILURES) {
                    log("guide: voice unavailable ($error); buttons only")
                    _state.update { it.copy(listening = false) }
                    return@coroutineScope
                }
            }
            delay(relistenDelayMs)
        }
    }

    private fun show(card: ProtocolCard, idx: Int, notice: String?) {
        _state.update {
            GuideState(
                active = true, cardId = card.id, cardTitle = card.title, source = card.source,
                stepIndex = idx, stepCount = card.steps.size, stepText = card.steps[idx].text,
                listening = false, heard = it.heard, notice = notice,
            )
        }
    }

    private suspend fun sayStep(card: ProtocolCard, idx: Int) =
        say("Step ${idx + 1} of ${card.steps.size}. ${card.steps[idx].text}")

    private suspend fun say(text: String) = runCatching { voice.speak(text, languageTag) }.let { }

    private companion object {
        /** Real voice failures in a row (silence never counts) before falling back to buttons only. */
        const val MAX_LISTEN_FAILURES = 5
        private val SILENCE = setOf(
            VoiceSupport.errorReason(android.speech.SpeechRecognizer.ERROR_NO_MATCH),
            VoiceSupport.errorReason(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }
}
