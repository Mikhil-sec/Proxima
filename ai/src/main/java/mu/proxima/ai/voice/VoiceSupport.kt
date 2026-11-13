package mu.proxima.ai.voice

import android.speech.SpeechRecognizer

/** Pure helpers for AndroidVoiceIo (JVM-testable, no Android runtime calls). */
object VoiceSupport {

    /**
     * Language tags to try, most specific first. Speech models rarely exist for Mauritian locales,
     * so we fall back to the closest widely available one. Kreol Morisien (mfe) is French-based,
     * so French recognition is the best available approximation.
     */
    fun languageCandidates(tag: String): List<String> {
        val clean = tag.trim().replace('_', '-').ifEmpty { "en-MU" }
        return when (clean.substringBefore('-').lowercase()) {
            "en" -> listOf(clean, "en-GB", "en-US")
            "fr" -> listOf(clean, "fr-FR", "en-GB", "en-US")
            "mfe", "cpf" -> listOf("fr-FR", "en-GB", "en-US")
            else -> listOf(clean, "en-US")
        }.distinctBy { it.lowercase() }
    }

    /**
     * First candidate that is installed (exact tag, then same language). If nothing matches,
     * returns null so the caller can choose another recognizer.
     */
    fun pickInstalled(candidates: List<String>, installed: List<String>): String? {
        if (installed.isEmpty()) return null
        val lower = installed.map { it.lowercase() }
        candidates.firstOrNull { it.lowercase() in lower }?.let { return it }
        for (c in candidates) {
            val lang = c.substringBefore('-').lowercase()
            installed.firstOrNull { it.substringBefore('-').lowercase() == lang }?.let { return it }
        }
        return null
    }

    /** onRmsChanged gives roughly -2..10 dB; map to 0..1 for the waveform. */
    fun normaliseRms(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

    fun isLanguageError(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    /** Short, user-facing reason. The UI shows it as-is under the mic button. */
    fun errorReason(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again or type it."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the mic and speak."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice is busy. Try again in a second."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem. Type the report instead."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "Offline voice not available. Type the report instead."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Voice language not installed. Type the report instead."
        else -> "Voice error ($code). Type the report instead."
    }

    /** Splits text into chunks the TTS engine accepts, preferring sentence boundaries. */
    fun chunk(text: String, maxLen: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxLen) return listOf(clean)
        val out = mutableListOf<String>()
        var rest = clean
        while (rest.length > maxLen) {
            val window = rest.substring(0, maxLen)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
                .takeIf { it > 0 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: maxLen
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
