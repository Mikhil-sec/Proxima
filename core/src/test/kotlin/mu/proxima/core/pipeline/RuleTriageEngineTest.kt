package mu.proxima.core.pipeline

import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.IncidentType.FOUND
import mu.proxima.core.model.IncidentType.HAZARD
import mu.proxima.core.model.IncidentType.LOST_PERSON
import mu.proxima.core.model.IncidentType.MEDICAL
import mu.proxima.core.model.IncidentType.SIGHTING
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.Severity.CRITICAL
import mu.proxima.core.model.Severity.HIGH
import mu.proxima.core.model.Severity.MEDIUM
import mu.proxima.core.model.TriageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the keyword fallback's behaviour (AI lane). Kreol phrases here are the current best-effort list;
 * when a Mauritian speaker corrects RuleTriageEngine, update these cases with them.
 */
class RuleTriageEngineTest {
    private val engine = RuleTriageEngine()
    private fun classify(text: String, hint: IncidentType? = null) =
        engine.classify(RawReport(text = text, createdAtMs = 0, typeHint = hint))

    private fun assertTriage(text: String, type: IncidentType, subtype: String?, severity: Severity) {
        val r = classify(text)
        assertEquals("type for \"$text\"", type, r.type)
        assertEquals("subtype for \"$text\"", subtype, r.subtype)
        assertEquals("severity for \"$text\"", severity, r.severity)
        assertEquals(TriageSource.RULES, r.source)
    }

    // ---------- English ----------
    @Test fun english_medical() {
        assertTriage("he is not breathing", MEDICAL, "CARDIAC_ARREST", CRITICAL)
        assertTriage("my friend fainted", MEDICAL, "UNCONSCIOUS", CRITICAL)
        assertTriage("lady bleeding from her head", MEDICAL, "BLEEDING", HIGH)
        assertTriage("two people dizzy from the heat", MEDICAL, "HEAT_EXHAUSTION", MEDIUM)
    }

    @Test fun english_hazard() {
        assertTriage("people pushing, stampede at the gate", HAZARD, "CROWD_CRUSH", CRITICAL)
        assertTriage("smoke coming from the food stalls", HAZARD, "FIRE", CRITICAL)
        assertTriage("a big branch fell on the path", HAZARD, "FALLEN_BRANCH", MEDIUM)
    }

    @Test fun english_people() {
        assertTriage("I lost my daughter, a little girl in red", LOST_PERSON, "CHILD", CRITICAL)
        assertTriage("my grandmother is missing", LOST_PERSON, "ELDERLY", HIGH)
        assertTriage("I found a child near the stage", FOUND, "CHILD", HIGH)
        assertTriage("a boy crying alone near the bus stop", SIGHTING, "CHILD", MEDIUM)
    }

    // ---------- French ----------
    @Test fun french() {
        assertTriage("Il y a de la fumée derrière les stands", HAZARD, "FIRE", CRITICAL)
        assertTriage("un câble électrique est tombé", HAZARD, "ELECTRICAL", HIGH)
        assertTriage("mon fils a disparu", LOST_PERSON, "PERSON", HIGH)
    }

    // ---------- Kreol Morisien (best-effort list, to be verified) ----------
    @Test fun kreol() {
        assertTriage("ena dife kot labitasion", HAZARD, "FIRE", CRITICAL)
        assertTriage("mo pa trouv mo zanfan", LOST_PERSON, "CHILD", CRITICAL)
        assertTriage("delo pe monte kot pon", HAZARD, "FLOOD", MEDIUM)
    }

    // ---------- edge cases ----------
    @Test fun wordStartBoundary_heatDoesNotMatchTheatre() {
        assertTriage("meet me at the theatre, someone is sick", MEDICAL, "GENERAL", MEDIUM)
    }

    @Test fun pressedButtonWinsOverKeywords() {
        val r = classify("help", hint = HAZARD)
        assertEquals(HAZARD, r.type)
        assertNull(r.subtype) // "help" only matches a MEDICAL rule, so no hazard subtype
        assertEquals(MEDIUM, r.severity)
    }

    // #16: wrong quick button must not downgrade a clearly critical description
    @Test fun criticalWordsOverrideWrongButton_issue16() {
        val r = classify("a man collapsed near the food stalls, he's not breathing", hint = LOST_PERSON)
        assertEquals(MEDICAL, r.type)
        assertEquals("CARDIAC_ARREST", r.subtype)
        assertEquals(CRITICAL, r.severity)
        assertEquals("food stalls", r.landmark)
    }

    @Test fun nonCriticalWordsKeepTheButton() {
        // "bleeding" is HIGH, not CRITICAL → the pressed HAZARD button still decides the type
        val r = classify("branch fell, someone is bleeding", hint = HAZARD)
        assertEquals(HAZARD, r.type)
        assertEquals("FALLEN_BRANCH", r.subtype)
    }

    @Test fun criticalOfSameTypeAsButtonIsKept() {
        val r = classify("there is fire and smoke", hint = HAZARD)
        assertEquals(HAZARD, r.type)
        assertEquals("FIRE", r.subtype)
        assertEquals(CRITICAL, r.severity)
    }

    @Test fun noMatch_defaultsToMedicalWithLowConfidence() {
        val r = classify("something strange over there")
        assertEquals(MEDICAL, r.type)
        assertEquals(0.3f, r.confidence)
    }

    @Test fun extractsLandmarkAndCount() {
        val r = classify("3 people injured near the main entrance")
        assertEquals(3, r.peopleCount)
        assertEquals("main entrance", r.landmark)
    }

    @Test fun summaryIsCollapsedAndCapped() {
        val r = classify("  lots   of\n\nsmoke  " + "x".repeat(300))
        assertEquals(140, r.summary.length)
        assert(r.summary.startsWith("lots of smoke"))
    }
}
