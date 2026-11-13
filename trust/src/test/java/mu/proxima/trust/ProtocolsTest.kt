package mu.proxima.trust

import java.io.File
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.RawReport
import mu.proxima.core.pipeline.RuleTriageEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the real committed `protocols.json`. These cards are read aloud to someone standing over
 * a casualty, so the tests check provenance and coverage, not just that the JSON parses.
 */
class ProtocolsTest {

    /**
     * Every subtype `RuleTriageEngine` can emit, transcribed from its source by hand.
     *
     * This list alone is NOT a guard - it only says what I believed the engine emits. The real guard is
     * `every subtype the live engine emits is either covered or a documented gap`, which drives the actual
     * engine, so a renamed subtype or a changed precedence rule in the AI lane fails here.
     */
    private val triageSubtypes = setOf(
        "CARDIAC_ARREST", "CROWD_CRUSH", "FIRE", "UNCONSCIOUS", "SEIZURE", "BLEEDING", "CHEST_PAIN",
        "HEAT_EXHAUSTION", "FRACTURE", "ELECTRICAL", "FALLEN_BRANCH", "FLOOD", "GENERAL",
        "CHILD", "ELDERLY", "PERSON",
    )

    /**
     * Subtypes with no card, because the IFRC 2020 guidelines contain no protocol for them.
     * Shrinking this list is TRUST lane work; it must never grow silently.
     */
    private val knownGaps = setOf(
        "CROWD_CRUSH", "ELECTRICAL", "FALLEN_BRANCH", "FLOOD", // hazards: no IFRC protocol
        "CHILD", "ELDERLY", "PERSON",                           // lost-person: operational, not medical
    )

    private val cards: List<ProtocolCard> by lazy {
        val f = File("src/main/assets/data/protocols.json")
        assertTrue("protocols.json missing at ${f.absolutePath}", f.isFile)
        Protocols.parse(f.readText())
    }

    @Test
    fun `every card is cited and usable`() {
        assertEquals(9, cards.size)
        assertEquals(cards.size, cards.map { it.id }.distinct().size)
        assertTrue("a card with no steps would show an empty screen", cards.all { it.steps.isNotEmpty() })
        assertTrue(cards.all { it.title.isNotBlank() })
        assertTrue(cards.all { it.appliesTo.isNotEmpty() })
    }

    /** "AI selects, never authors" only holds if the provenance is actually on every card. */
    @Test
    fun `every card names an authoritative source`() {
        cards.forEach {
            assertTrue("card ${it.id} has no IFRC citation: '${it.source}'", it.source.contains("IFRC"))
        }
    }

    @Test
    fun `every step is speakable`() {
        cards.flatMap { c -> c.steps.map { c.id to it } }.forEach { (id, step) ->
            assertTrue("blank step in $id", step.text.isNotBlank())
            // Read aloud by VoiceIo; a step longer than this is a paragraph, not an instruction.
            assertTrue("step too long in $id (${step.text.length} chars)", step.text.length <= 320)
            assertTrue("step should end as a sentence in $id", step.text.trim().endsWith("."))
            step.durationSec?.let { assertTrue("non-positive timer in $id", it > 0) }
        }
    }

    @Test
    fun `a cardiac arrest selects CPR and leads with calling for help`() {
        val card = Protocols.bestFor(cards, IncidentType.MEDICAL, "CARDIAC_ARREST")

        assertNotNull(card)
        assertEquals("cpr-adult", card!!.id)
        assertTrue(card.steps.first().text.contains("emergency services"))
        // The compression rate is the one number that must survive transcription intact.
        assertTrue(card.steps.any { it.text.contains("100 to 120") })
    }

    @Test
    fun `the right card is selected for each covered subtype`() {
        assertEquals("unresponsive-breathing", Protocols.bestFor(cards, IncidentType.MEDICAL, "UNCONSCIOUS")?.id)
        assertEquals("severe-bleeding", Protocols.bestFor(cards, IncidentType.MEDICAL, "BLEEDING")?.id)
        assertEquals("seizure", Protocols.bestFor(cards, IncidentType.MEDICAL, "SEIZURE")?.id)
        assertEquals("heat-illness", Protocols.bestFor(cards, IncidentType.MEDICAL, "HEAT_EXHAUSTION")?.id)
        assertEquals("fracture", Protocols.bestFor(cards, IncidentType.MEDICAL, "FRACTURE")?.id)
        assertEquals("chest-pain", Protocols.bestFor(cards, IncidentType.MEDICAL, "CHEST_PAIN")?.id)
        assertEquals("burns", Protocols.bestFor(cards, IncidentType.HAZARD, "FIRE")?.id)
    }

    @Test
    fun `an unknown or missing medical subtype falls back to the generic assessment card`() {
        assertEquals("assess-person", Protocols.bestFor(cards, IncidentType.MEDICAL, "GENERAL")?.id)
        assertEquals("assess-person", Protocols.bestFor(cards, IncidentType.MEDICAL, null)?.id)
        assertEquals("assess-person", Protocols.bestFor(cards, IncidentType.MEDICAL, "SOMETHING_NEW")?.id)
    }

    /**
     * Hazards other than fire have no card and must return null rather than an unrelated one.
     * CROWD_CRUSH is the painful one: it is CRITICAL severity and it is the Maha Shivaratri scenario.
     */
    @Test
    fun `uncovered hazards return nothing rather than the wrong card`() {
        assertNull(Protocols.bestFor(cards, IncidentType.HAZARD, "CROWD_CRUSH"))
        assertNull(Protocols.bestFor(cards, IncidentType.HAZARD, "ELECTRICAL"))
        assertNull(Protocols.bestFor(cards, IncidentType.HAZARD, "FALLEN_BRANCH"))
        assertNull(Protocols.bestFor(cards, IncidentType.HAZARD, "FLOOD"))
        assertNull(Protocols.bestFor(cards, IncidentType.LOST_PERSON, "CHILD"))
        assertNull(Protocols.bestFor(cards, IncidentType.ALL_CLEAR, null))
    }

    /** Fails if a card uses a subtype the triage engine never emits (a typo), or if a gap appears unannounced. */
    @Test
    fun `card subtypes match the triage engine, and gaps are the documented ones`() {
        val covered = cards.flatMap { it.subtypes }.toSet()

        assertTrue(
            "cards reference subtypes RuleTriageEngine never emits: ${covered - triageSubtypes}",
            covered.all { it in triageSubtypes },
        )
        assertEquals(
            "coverage changed - update knownGaps and the lane file",
            knownGaps, triageSubtypes - covered - setOf("GENERAL"),
        )
    }

    /**
     * The real drift guard: drives the live `RuleTriageEngine` with one trigger phrase per subtype and checks
     * that whatever it actually emits either resolves to a card or is a documented gap.
     *
     * This is here because the AI lane owns `RuleTriageEngine` and changed its precedence rules at 13:xx Fri
     * (#16). If they rename a subtype code or reorder the rules so a phrase classifies differently, the
     * hardcoded list above would not notice but this will.
     */
    @Test
    fun `every subtype the live engine emits is either covered or a documented gap`() {
        val engine = RuleTriageEngine()
        val phrases = mapOf(
            "he is not breathing" to "CARDIAC_ARREST",
            "there is a stampede" to "CROWD_CRUSH",
            "there is a fire near the stalls" to "FIRE",
            "she collapsed" to "UNCONSCIOUS",
            "he is having a seizure" to "SEIZURE",
            "his arm is bleeding" to "BLEEDING",
            "he has chest pain" to "CHEST_PAIN",
            "she is dehydrated" to "HEAT_EXHAUSTION",
            "his leg is broken" to "FRACTURE",
            "there is a live wire on the ground" to "ELECTRICAL",
            "a fallen tree is blocking the path" to "FALLEN_BRANCH",
            "the road is flooded" to "FLOOD",
            "someone is injured" to "GENERAL",
            "I lost my child" to "CHILD",
            "I lost my grandmother" to "ELDERLY",
            "my friend is missing" to "PERSON",
        )

        val emitted = mutableSetOf<String>()
        phrases.forEach { (text, expected) ->
            val result = engine.classify(RawReport(text = text, createdAtMs = 0L))
            assertEquals("'$text' classified differently than TRUST assumes", expected, result.subtype)
            val subtype = result.subtype!!
            emitted += subtype

            val card = Protocols.bestFor(cards, result.type, subtype)
            if (subtype in knownGaps) {
                assertNull("$subtype is a documented gap but resolved to ${card?.id}", card)
            } else {
                assertNotNull("$subtype has no protocol card and is not a documented gap", card)
            }
        }

        // If the engine gains or renames a subtype, this is what catches it.
        assertEquals(
            "RuleTriageEngine subtypes changed - re-check protocols.json coverage",
            triageSubtypes, emitted,
        )
    }
}
