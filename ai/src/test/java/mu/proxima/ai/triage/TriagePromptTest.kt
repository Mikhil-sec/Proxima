package mu.proxima.ai.triage

import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriagePromptTest {
    private fun report(text: String, hint: IncidentType? = null) = RawReport(text = text, createdAtMs = 0, typeHint = hint)

    @Test fun parsesCleanJson() {
        val r = TriagePrompt.parse(
            """{"type":"MEDICAL","severity":"HIGH","subtype":"BLEEDING","summary":"Woman bleeding from head","landmark":"main entrance","peopleCount":1}""",
            report("lady bleeding near entrance"),
        )!!
        assertEquals(IncidentType.MEDICAL, r.type)
        assertEquals(Severity.HIGH, r.severity)
        assertEquals("BLEEDING", r.subtype)
        assertEquals("main entrance", r.landmark)
        assertEquals(1, r.peopleCount)
        assertEquals(TriageSource.ON_DEVICE_LLM, r.source)
    }

    @Test fun toleratesMarkdownFencesAndChatter() {
        val raw = "Sure! Here is the JSON:\n```json\n{\"type\":\"hazard\",\"severity\":\"critical\",\"subtype\":\"fire\",\"summary\":\"Smoke behind stalls\"}\n```"
        val r = TriagePrompt.parse(raw, report("fumée derrière les stands"))!!
        assertEquals(IncidentType.HAZARD, r.type)
        assertEquals(Severity.CRITICAL, r.severity)
        assertEquals("FIRE", r.subtype)
    }

    @Test fun rejectsUnknownTypeOrSeverity() {
        assertNull(TriagePrompt.parse("""{"type":"EARTHQUAKE","severity":"HIGH","summary":"x"}""", report("x")))
        assertNull(TriagePrompt.parse("""{"type":"MEDICAL","severity":"URGENT","summary":"x"}""", report("x")))
        assertNull(TriagePrompt.parse("no json at all", report("x")))
        assertNull(TriagePrompt.parse("""{"type":"MEDICAL",""", report("x")))
    }

    @Test fun unknownSubtypeIsNormalised() {
        val med = TriagePrompt.parse("""{"type":"MEDICAL","severity":"MEDIUM","subtype":"HEADACHE","summary":"x"}""", report("x"))!!
        assertEquals("GENERAL", med.subtype)
        val haz = TriagePrompt.parse("""{"type":"HAZARD","severity":"MEDIUM","subtype":"ALIENS","summary":"x"}""", report("x"))!!
        assertNull(haz.subtype)
        val lost = TriagePrompt.parse("""{"type":"LOST_PERSON","severity":"HIGH","subtype":null,"summary":"x"}""", report("x"))!!
        assertEquals("PERSON", lost.subtype)
    }

    @Test fun lostChildIsAlwaysCritical() {
        val r = TriagePrompt.parse(
            """{"type":"LOST_PERSON","severity":"MEDIUM","subtype":"CHILD","summary":"Girl about 6, red dress"}""",
            report("Mo pa trouv mo zanfan"),
        )!!
        assertEquals(Severity.CRITICAL, r.severity)
    }

    @Test fun pressedButtonOverridesModelType() {
        val r = TriagePrompt.parse(
            """{"type":"MEDICAL","severity":"HIGH","subtype":"GENERAL","summary":"x"}""",
            report("help my son is gone", hint = IncidentType.LOST_PERSON),
        )!!
        assertEquals(IncidentType.LOST_PERSON, r.type)
        assertEquals("PERSON", r.subtype) // MEDICAL subtype is not valid for LOST_PERSON
    }

    @Test fun criticalModelTypeBeatsWrongButton_issue16() {
        val r = TriagePrompt.parse(
            """{"type":"MEDICAL","severity":"CRITICAL","subtype":"CARDIAC_ARREST","summary":"Man not breathing"}""",
            report("a man collapsed, he's not breathing", hint = IncidentType.LOST_PERSON),
        )!!
        assertEquals(IncidentType.MEDICAL, r.type)
        assertEquals("CARDIAC_ARREST", r.subtype)
    }

    @Test fun clampsAndCleansFields() {
        val long = "a".repeat(300)
        val r = TriagePrompt.parse(
            """{"type":"MEDICAL","severity":"LOW","summary":"$long","landmark":"null","peopleCount":"0"}""",
            report("x"),
        )!!
        assertEquals(140, r.summary.length)
        assertNull(r.landmark)
        assertNull(r.peopleCount)
    }

    @Test fun emptySummaryFallsBackToReportText() {
        val r = TriagePrompt.parse("""{"type":"MEDICAL","severity":"LOW","summary":""}""", report("  man   fainted  "))!!
        assertEquals("man fainted", r.summary)
    }

    @Test fun promptMentionsPressedButton() {
        val u = TriagePrompt.user(report("help", hint = IncidentType.HAZARD))
        assert(u.contains("HAZARD"))
    }
}
