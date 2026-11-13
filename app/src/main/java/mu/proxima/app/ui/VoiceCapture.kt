package mu.proxima.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.model.VoiceEvent

/**
 * One voice capture shared by Home (hold the SOS ring) and Report (tap to talk), so a hold that starts
 * on Home keeps its waveform and transcript when the Report screen takes over.
 */
class VoiceCapture(private val voice: VoiceIo, private val scope: CoroutineScope) {
    enum class Phase { Idle, Listening, Done, Failed }

    var phase by mutableStateOf(Phase.Idle); private set
    var transcript by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    /** Recent input levels 0..1, oldest first, for the waveform. */
    val levels = mutableStateListOf<Float>()

    private var job: Job? = null

    fun start() {
        reset()
        phase = Phase.Listening
        job = scope.launch {
            runCatching {
                voice.listen().collect { event ->
                    when (event) {
                        VoiceEvent.Listening -> Unit
                        is VoiceEvent.Level -> {
                            levels.add(normalise(event.rms))
                            if (levels.size > MAX_LEVELS) levels.removeAt(0)
                        }
                        is VoiceEvent.Partial -> transcript = event.text
                        is VoiceEvent.Final -> { transcript = event.text; phase = Phase.Done }
                        is VoiceEvent.Error -> { error = event.reason; phase = Phase.Failed }
                    }
                }
            }.onFailure { error = it.message ?: "Microphone unavailable"; phase = Phase.Failed }
            if (phase == Phase.Listening) phase = if (transcript.isNotBlank()) Phase.Done else Phase.Failed
        }
    }

    /** Finger lifted / "Done" tapped: let the recogniser finish and deliver its final text. */
    fun stop() = voice.stopListening()

    fun cancel() {
        job?.cancel()
        voice.stopListening()
        reset()
    }

    private fun reset() {
        job?.cancel()
        phase = Phase.Idle
        transcript = ""
        error = null
        levels.clear()
    }

    /** Fakes emit 0..1; Android SpeechRecognizer emits roughly -2..10 dB. Accept both. */
    private fun normalise(rms: Float): Float = if (rms in 0f..1f) rms else ((rms + 2f) / 12f).coerceIn(0f, 1f)

    private companion object { const val MAX_LEVELS = 48 }
}
