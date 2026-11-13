package mu.proxima.ai.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSupportTest {

    @Test fun mauritianEnglishFallsBackToCommonEnglish() {
        assertEquals(listOf("en-MU", "en-GB", "en-US"), VoiceSupport.languageCandidates("en-MU"))
    }

    @Test fun kreolUsesFrenchFirst() {
        assertEquals(listOf("fr-FR", "en-GB", "en-US"), VoiceSupport.languageCandidates("mfe"))
        assertEquals(listOf("fr-FR", "en-GB", "en-US"), VoiceSupport.languageCandidates("mfe-MU"))
    }

    @Test fun frenchAndDuplicates() {
        assertEquals(listOf("fr-MU", "fr-FR", "en-GB", "en-US"), VoiceSupport.languageCandidates("fr_MU"))
        assertEquals(listOf("en-US", "en-GB"), VoiceSupport.languageCandidates("en-US"))
        assertEquals(listOf("en-MU", "en-GB", "en-US"), VoiceSupport.languageCandidates(""))
    }

    @Test fun pickInstalled_exactThenSameLanguage() {
        val c = listOf("en-MU", "en-GB", "en-US")
        assertEquals("en-GB", VoiceSupport.pickInstalled(c, listOf("en-US", "en-GB")))
        assertEquals("en-IN", VoiceSupport.pickInstalled(c, listOf("en-IN")))
        assertNull(VoiceSupport.pickInstalled(c, listOf("de-DE")))
        assertNull(VoiceSupport.pickInstalled(c, emptyList()))
    }

    @Test fun rmsIsClampedTo0to1() {
        assertEquals(0f, VoiceSupport.normaliseRms(-10f))
        assertEquals(0f, VoiceSupport.normaliseRms(-2f))
        assertEquals(0.5f, VoiceSupport.normaliseRms(4f))
        assertEquals(1f, VoiceSupport.normaliseRms(25f))
    }

    @Test fun errorsAreHumanAndLanguageErrorsDetected() {
        assertTrue(VoiceSupport.isLanguageError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(VoiceSupport.isLanguageError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(VoiceSupport.errorReason(SpeechRecognizer.ERROR_NETWORK).contains("Type the report"))
        assertTrue(VoiceSupport.errorReason(999).contains("999"))
    }

    @Test fun chunkKeepsShortTextAndSplitsOnSentences() {
        assertEquals(listOf("Call for help."), VoiceSupport.chunk("  Call   for help. ", 100))
        assertEquals(emptyList<String>(), VoiceSupport.chunk("   ", 100))
        val parts = VoiceSupport.chunk("Step one is here. Step two is here. Step three.", 20)
        assertEquals(listOf("Step one is here.", "Step two is here.", "Step three."), parts)
        assertTrue(parts.all { it.length <= 20 })
    }

    @Test fun levelThrottledToAbout12Hz_issue9() {
        assertTrue(VoiceSupport.shouldEmitLevel(nowMs = 10_000, lastEmitMs = 0))
        assertFalse(VoiceSupport.shouldEmitLevel(nowMs = 1_050, lastEmitMs = 1_000))
        assertTrue(VoiceSupport.shouldEmitLevel(nowMs = 1_083, lastEmitMs = 1_000))
        // 1 s of callbacks every 10 ms → ~12 emitted
        var last = 0L; var n = 0
        for (t in 1_000L..1_999L step 10) if (VoiceSupport.shouldEmitLevel(t, last)) { last = t; n++ }
        assertTrue("got $n", n in 11..13)
    }

    @Test fun nothingHeardErrorsAreDetected() {
        assertTrue(VoiceSupport.isNothingHeardError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(VoiceSupport.isNothingHeardError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(VoiceSupport.isNothingHeardError(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test fun chunkHardSplitsWhenNoSpaces() {
        val parts = VoiceSupport.chunk("x".repeat(25), 10)
        assertEquals(listOf(10, 10, 5), parts.map { it.length })
    }
}
