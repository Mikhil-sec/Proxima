package mu.proxima.ai.guide

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mu.proxima.ai.triage.LlmBackend
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.VoiceIo
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.ProtocolStep
import mu.proxima.core.model.VoiceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandsFreeGuideTest {

    /** Each listen() pops the next scripted utterance; when the script is empty it waits forever (silence). */
    private class ScriptedVoice(vararg script: String?) : VoiceIo {
        val queue = ArrayDeque(script.toList())
        val spoken = mutableListOf<String>()
        var listening = false
        override val requiredPermissions = emptyList<String>()
        override fun listen(languageTag: String): Flow<VoiceEvent> = flow {
            emit(VoiceEvent.Listening)
            listening = true
            try {
                delay(500)
                if (queue.isEmpty()) awaitCancellation()
                val next = queue.removeFirst()
                emit(if (next == null) VoiceEvent.Error("No speech heard. Tap the mic and speak.") else VoiceEvent.Final(next))
            } finally { listening = false }
        }
        override fun stopListening() = Unit
        override suspend fun speak(text: String, languageTag: String) {
            assertFalse("must never listen while speaking", listening)
            spoken += text; delay(1_000)
        }
        override fun stopSpeaking() = Unit
    }

    private val cpr = ProtocolCard("cpr-adult", "Not breathing - start CPR", listOf(IncidentType.MEDICAL), listOf("CARDIAC_ARREST"),
        listOf(ProtocolStep("Call for help."), ProtocolStep("Push hard and fast.", durationSec = 120), ProtocolStep("Get a defibrillator.")), "IFRC 2020")
    private val recovery = ProtocolCard("unresponsive-breathing", "Unresponsive but breathing - recovery position", listOf(IncidentType.MEDICAL),
        listOf("UNCONSCIOUS"), listOf(ProtocolStep("Roll them onto their side."), ProtocolStep("Keep checking breathing.")), "IFRC 2020")

    private val repo = object : ProtocolRepository {
        val cards = listOf(cpr, recovery)
        override suspend fun all() = cards
        override suspend fun byId(id: String) = cards.firstOrNull { it.id == id }
        override suspend fun bestFor(type: IncidentType, subtype: String?) = null
    }

    @Test fun readsTitleThenFirstStep_andShowsState() = runTest {
        val voice = ScriptedVoice()
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(cpr)
        advanceTimeBy(2_500); runCurrent()
        assertEquals(listOf("Not breathing - start CPR.", "Step 1 of 3. Call for help."), voice.spoken)
        val s = guide.state.value
        assertTrue(s.active); assertEquals("cpr-adult", s.cardId); assertEquals(0, s.stepIndex); assertEquals(3, s.stepCount)
        assertTrue(s.listening)
    }

    @Test fun voiceNext_advances_andRepeatRereads() = runTest {
        val voice = ScriptedVoice("next", "repeat")
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(cpr)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, guide.state.value.stepIndex)
        assertEquals(2, voice.spoken.count { it.startsWith("Step 2 of 3") }) // spoken, then repeated
    }

    @Test fun breathing_switchesFromCprToRecoveryCard() = runTest {
        val voice = ScriptedVoice("he's breathing again")
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(cpr)
        advanceTimeBy(10_000); runCurrent()
        val s = guide.state.value
        assertEquals("unresponsive-breathing", s.cardId)
        assertEquals(0, s.stepIndex)
        assertEquals("Breathing → Unresponsive but breathing - recovery position", s.notice)
        assertTrue(voice.spoken.contains("Breathing. Switching to: Unresponsive but breathing - recovery position."))
        assertEquals("he's breathing again", s.heard)
    }

    @Test fun silenceNeverStopsTheGuide() = runTest {
        val voice = ScriptedVoice(*arrayOfNulls<String>(60)) // 60 "no speech" sessions in a row
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(recovery)
        advanceTimeBy(60 * 900L); runCurrent()
        assertTrue(guide.state.value.active)
        assertTrue(guide.state.value.listening)
    }

    @Test fun timedStepAdvancesByItself() = runTest {
        val voice = ScriptedVoice("next") // to step 2 (120 s), then silence
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(cpr)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, guide.state.value.stepIndex)
        advanceTimeBy(121_000); runCurrent()
        assertEquals(2, guide.state.value.stepIndex)
    }

    @Test fun buttonsWorkAndStopEnds() = runTest {
        val voice = ScriptedVoice()
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(recovery)
        advanceTimeBy(3_000); runCurrent()
        guide.next(); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, guide.state.value.stepIndex)
        guide.back(); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, guide.state.value.stepIndex)
        guide.stop(); advanceTimeBy(2_000); runCurrent()
        assertFalse(guide.state.value.active)
        assertEquals("Guide stopped.", voice.spoken.last())
    }

    @Test fun missingCardKeepsCurrentCard() = runTest {
        val voice = ScriptedVoice("there's a lot of blood") // severe-bleeding is not in this repo
        val guide = HandsFreeGuide(voice, repo, backgroundScope)
        guide.start(cpr)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("cpr-adult", guide.state.value.cardId)
    }

    @Test fun llmOnlyConsultedWhenRulesDontUnderstand() = runTest {
        var asked = 0
        val llm = object : LlmBackend {
            override val name = "fake"
            override suspend fun isReady() = true
            override suspend fun generate(system: String, user: String): String { asked++; return "BREATHING" }
        }
        val voice = ScriptedVoice("next", "his chest is going up and down by itself")
        val guide = HandsFreeGuide(voice, repo, backgroundScope, classifier = GuideIntentClassifier(llm))
        guide.start(cpr)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(1, asked) // "next" handled by rules; only the free-form phrase reached the LLM
        assertEquals("unresponsive-breathing", guide.state.value.cardId)
    }
}
