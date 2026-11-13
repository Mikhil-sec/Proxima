package mu.proxima.ai.triage

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.model.TriageSource
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmTriageEngineTest {

    private class FakeBackend(
        var ready: Boolean = true,
        var delayMs: Long = 0,
        var output: () -> String = { """{"type":"MEDICAL","severity":"HIGH","subtype":"BLEEDING","summary":"Bleeding"}""" },
    ) : LlmBackend {
        override val name = "fake"
        var calls = 0
        override suspend fun isReady() = ready
        override suspend fun generate(system: String, user: String): String {
            calls++
            delay(delayMs)
            return output()
        }
    }

    private fun report(text: String) = RawReport(text = text, createdAtMs = 0)

    @Test fun notReady_usesRulesWithoutCallingModel() = runTest {
        val backend = FakeBackend(ready = false)
        val engine = LlmTriageEngine(backend, backgroundScope)
        runCurrent()
        assertEquals(TriageCapability.RULES_ONLY, engine.capability.value)
        val r = engine.triage(report("lady bleeding near the gate"))
        assertEquals(TriageSource.RULES, r.source)
        assertEquals(0, backend.calls)
    }

    @Test fun becomesReadyLater_switchesToLlm() = runTest {
        val backend = FakeBackend(ready = false)
        val engine = LlmTriageEngine(backend, backgroundScope, recheckMs = 1_000)
        runCurrent()
        assertEquals(TriageCapability.RULES_ONLY, engine.capability.value)
        backend.ready = true
        testScheduler.advanceTimeBy(1_001)
        runCurrent()
        assertEquals(TriageCapability.ON_DEVICE_LLM, engine.capability.value)
    }

    @Test fun ready_usesLlm() = runTest {
        val engine = LlmTriageEngine(FakeBackend(), backgroundScope)
        runCurrent()
        val r = engine.triage(report("someone is hurt"))
        assertEquals(TriageSource.ON_DEVICE_LLM, r.source)
        assertEquals("BLEEDING", r.subtype)
    }

    @Test fun slowModel_fallsBackToRulesWithinBudget() = runTest {
        val engine = LlmTriageEngine(FakeBackend(delayMs = 10_000), backgroundScope, timeoutMs = 6_000)
        runCurrent()
        val start = testScheduler.currentTime
        val r = engine.triage(report("lady bleeding near the gate"))
        assertEquals(TriageSource.RULES, r.source)
        assertEquals(6_000, testScheduler.currentTime - start)
    }

    @Test fun crashingModel_fallsBackToRules_andEngineKeepsWorking() = runTest {
        var crash = true
        val backend = FakeBackend(output = {
            if (crash) error("native crash") else """{"type":"HAZARD","severity":"MEDIUM","subtype":"FLOOD","summary":"Water rising"}"""
        })
        val engine = LlmTriageEngine(backend, backgroundScope)
        runCurrent()
        assertEquals(TriageSource.RULES, engine.triage(report("water rising near the bridge")).source)
        crash = false
        assertEquals(TriageSource.ON_DEVICE_LLM, engine.triage(report("water rising near the bridge")).source)
    }

    @Test fun garbageOutput_fallsBackToRules() = runTest {
        val engine = LlmTriageEngine(FakeBackend(output = { "I cannot help with that." }), backgroundScope)
        runCurrent()
        assertEquals(TriageSource.RULES, engine.triage(report("fire behind the stalls")).source)
    }

    @Test fun rulesCanRaiseButNotLowerSeverity() = runTest {
        // LLM under-triages a cardiac arrest; keyword rules say CRITICAL for the same type → CRITICAL wins.
        val under = FakeBackend(output = { """{"type":"MEDICAL","severity":"MEDIUM","subtype":"UNCONSCIOUS","summary":"Old man collapsed"}""" })
        val e1 = LlmTriageEngine(under, backgroundScope)
        runCurrent()
        val r1 = e1.triage(report("old man collapsed, he is not breathing"))
        assertEquals(Severity.CRITICAL, r1.severity)
        assertEquals(TriageSource.ON_DEVICE_LLM, r1.source)

        // LLM rates higher than rules → LLM's higher severity is kept.
        val over = FakeBackend(output = { """{"type":"MEDICAL","severity":"CRITICAL","subtype":"BLEEDING","summary":"Heavy bleeding"}""" })
        val e2 = LlmTriageEngine(over, backgroundScope)
        runCurrent()
        assertEquals(Severity.CRITICAL, e2.triage(report("he is bleeding a lot")).severity)
    }

    @Test fun wrongButtonAndUnderRatingModel_rulesCriticalWins_issue16() = runTest {
        // User tapped LOST_PERSON; model obeys the button and says LOST_PERSON/MEDIUM; words say cardiac arrest.
        val backend = FakeBackend(output = { """{"type":"LOST_PERSON","severity":"MEDIUM","subtype":"PERSON","summary":"Man collapsed near food stalls"}""" })
        val engine = LlmTriageEngine(backend, backgroundScope)
        runCurrent()
        val r = engine.triage(
            RawReport(text = "a man collapsed near the food stalls, he's not breathing", createdAtMs = 0, typeHint = IncidentType.LOST_PERSON)
        )
        assertEquals(IncidentType.MEDICAL, r.type)
        assertEquals("CARDIAC_ARREST", r.subtype)
        assertEquals(Severity.CRITICAL, r.severity)
        assertEquals("Man collapsed near food stalls", r.summary)
    }

    @Test fun differentTypeFromRules_noSeverityBump() = runTest {
        // Rules read "sick" as MEDICAL/GENERAL; the LLM says HAZARD. Types differ → rules can't bump severity.
        val backend = FakeBackend(output = { """{"type":"HAZARD","severity":"LOW","subtype":null,"summary":"Loose fence panel"}""" })
        val engine = LlmTriageEngine(backend, backgroundScope)
        runCurrent()
        val r = engine.triage(report("loose fence panel, makes me sick to look at"))
        assertEquals(IncidentType.HAZARD, r.type)
        assertEquals(Severity.LOW, r.severity)
    }
}
