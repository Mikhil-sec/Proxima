package mu.proxima.ai.guide

/** What a spoken phrase means for the guide. The AI only ever chooses one of these. */
sealed interface GuideIntent {
    data object Next : GuideIntent
    data object Back : GuideIntent
    data object Repeat : GuideIntent
    data object Stop : GuideIntent
    /** Condition changed → switch to another vetted card (ids from trust/assets/data/protocols.json). */
    data class SwitchTo(val cardId: String, val reason: String) : GuideIntent
    data object None : GuideIntent
    /** A timed step (durationSec) elapsed. Produced by the guide itself, never by speech. */
    data object TimerElapsed : GuideIntent
}

/**
 * Keyword interpretation of short spoken commands (EN + FR), negation-aware.
 * Conditions beat navigation ("he's not breathing, next" → CPR). Only switches to a DIFFERENT card.
 */
object GuideCommands {
    const val CPR = "cpr-adult"
    const val RECOVERY = "unresponsive-breathing"
    const val BLEEDING = "severe-bleeding"
    const val SEIZURE = "seizure"
    const val ASSESS = "assess-person"
    const val CHEST_PAIN = "chest-pain"
    const val BURNS = "burns"

    private val notBreathing = listOf(
        "not breathing", "isn't breathing", "isnt breathing", "is not breathing", "stopped breathing",
        "stop breathing", "stops breathing", "stopping breathing", // recognizers often write "stopped" as "stop"
        "no breathing", "no longer breathing", "not breathing anymore", "can't feel", "no breath",
        "ne respire plus", "ne respire pas", "respire pas", "respire plus", "pa respire", "pe pa respire",
    )
    private val breathing = listOf("breathing", "breathes", "breathe", "respire", "respiration")
    // "no" is deliberately absent: "no, he's breathing now" is positive. "no breathing" is in [notBreathing].
    private val negators = listOf("not", "isn't", "isnt", "stopped", "pas", "plus", "pa", "never")

    private val stop = listOf("stop", "cancel", "ambulance is here", "paramedics are here", "help is here", "arrête", "arrete", "annule")
    private val next = listOf("next", "done", "continue", "go on", "okay next", "ok next", "suivant", "ensuite", "fini", "c'est fait")
    private val back = listOf("back", "previous", "go back", "retour", "précédent", "precedent")
    private val repeat = listOf("repeat", "again", "say again", "say that again", "what", "pardon", "répète", "repete", "encore")

    private val conditions = listOf(
        Triple(listOf("bleeding", "blood", "saigne", "sang", "disan"), BLEEDING, "Bleeding"),
        Triple(listOf("seizure", "fitting", "convuls", "shaking", "crise", "kriz"), SEIZURE, "Seizure"),
        Triple(listOf("unconscious", "unresponsive", "not responding", "passed out", "fainted", "évanoui", "evanoui", "inconscient"), ASSESS, "Unresponsive"),
        Triple(listOf("chest pain", "douleur poitrine", "mal à la poitrine"), CHEST_PAIN, "Chest pain"),
        Triple(listOf("burn", "brûl", "brul"), BURNS, "Burn"),
    )

    fun interpret(text: String, currentCardId: String?): GuideIntent {
        val t = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank()) return GuideIntent.None

        // Breathing status FIRST: "he stop breathing" (as transcribed) must switch to CPR, never end the guide.
        if (notBreathing.any { t.hasWord(it) }) {
            return switchIfDifferent(CPR, "Not breathing", currentCardId) // already on CPR → None: keep going
        }

        if (stop.any { t.hasWholeWord(it) }) return GuideIntent.Stop
        if (breathing.any { t.hasWord(it) } && !negatedBreathing(t)) {
            // Only meaningful while doing CPR or assessing; elsewhere "breathing" is not a reason to switch.
            if (currentCardId == CPR || currentCardId == ASSESS) return switchIfDifferent(RECOVERY, "Breathing", currentCardId)
        }
        for ((words, cardId, reason) in conditions) {
            if (words.any { t.hasWord(it) }) {
                val intent = switchIfDifferent(cardId, reason, currentCardId)
                if (intent != GuideIntent.None) return intent
            }
        }

        return when {
            back.any { t.hasWholeWord(it) } -> GuideIntent.Back
            repeat.any { t.hasWholeWord(it) } -> GuideIntent.Repeat
            next.any { t.hasWholeWord(it) } -> GuideIntent.Next
            else -> GuideIntent.None
        }
    }

    private fun switchIfDifferent(cardId: String, reason: String, current: String?): GuideIntent =
        if (cardId == current) GuideIntent.None else GuideIntent.SwitchTo(cardId, reason)

    /** "he is not really breathing" → negated: a negator within the 3 words before "breath…/respir…". */
    private fun negatedBreathing(t: String): Boolean {
        val words = t.trim().split(' ')
        words.forEachIndexed { i, w ->
            if (w.startsWith("breath") || w.startsWith("respir")) {
                if (words.subList(maxOf(0, i - 3), i).any { it in negators }) return true
            }
        }
        return false
    }

    /** Navigation words must match whole words: "stop" must never fire on "stopped breathing". */
    private fun String.hasWholeWord(phrase: String): Boolean =
        Regex("(?<![\\p{L}])" + Regex.escape(phrase) + "(?![\\p{L}])").containsMatchIn(this)

    /** Condition stems match at a word START (same idea as RuleTriageEngine): "convuls" → "convulsing". */
    private fun String.hasWord(phrase: String): Boolean =
        Regex("(?<![\\p{L}])" + Regex.escape(phrase)).containsMatchIn(this)

    /** Closed label set the LLM may choose from (see GuideIntentClassifier). */
    val LABELS = listOf("NEXT", "BACK", "REPEAT", "STOP", "NOT_BREATHING", "BREATHING", "BLEEDING", "SEIZURE", "UNRESPONSIVE", "NONE")

    fun fromLabel(label: String, currentCardId: String?): GuideIntent = when (label.trim().uppercase()) {
        "NEXT" -> GuideIntent.Next
        "BACK" -> GuideIntent.Back
        "REPEAT" -> GuideIntent.Repeat
        "STOP" -> GuideIntent.Stop
        "NOT_BREATHING" -> switchIfDifferent(CPR, "Not breathing", currentCardId)
        "BREATHING" -> if (currentCardId == CPR || currentCardId == ASSESS) switchIfDifferent(RECOVERY, "Breathing", currentCardId) else GuideIntent.None
        "BLEEDING" -> switchIfDifferent(BLEEDING, "Bleeding", currentCardId)
        "SEIZURE" -> switchIfDifferent(SEIZURE, "Seizure", currentCardId)
        "UNRESPONSIVE" -> switchIfDifferent(ASSESS, "Unresponsive", currentCardId)
        else -> GuideIntent.None
    }
}
