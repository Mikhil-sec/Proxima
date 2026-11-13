package mu.proxima.ai.guide

import mu.proxima.ai.guide.GuideCommands.ASSESS
import mu.proxima.ai.guide.GuideCommands.BLEEDING
import mu.proxima.ai.guide.GuideCommands.CPR
import mu.proxima.ai.guide.GuideCommands.RECOVERY
import mu.proxima.ai.guide.GuideCommands.SEIZURE
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideCommandsTest {
    private fun i(text: String, card: String? = CPR) = GuideCommands.interpret(text, card)

    @Test fun navigation() {
        assertEquals(GuideIntent.Next, i("next"))
        assertEquals(GuideIntent.Next, i("OK, done."))
        assertEquals(GuideIntent.Back, i("go back"))
        assertEquals(GuideIntent.Repeat, i("say that again"))
        assertEquals(GuideIntent.Stop, i("the ambulance is here"))
        assertEquals(GuideIntent.Next, i("suivant"))
        assertEquals(GuideIntent.Repeat, i("répète"))
        assertEquals(GuideIntent.None, i("hmm"))
        assertEquals(GuideIntent.None, i(""))
    }

    @Test fun breathingDuringCpr_switchesToRecovery() {
        assertEquals(GuideIntent.SwitchTo(RECOVERY, "Breathing"), i("he's breathing"))
        assertEquals(GuideIntent.SwitchTo(RECOVERY, "Breathing"), i("no, she is breathing now"))
        assertEquals(GuideIntent.SwitchTo(RECOVERY, "Breathing"), i("il respire"))
    }

    @Test fun notBreathing_switchesToCpr_andIsNeverReadAsBreathing() {
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("he stopped breathing", RECOVERY))
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("she is not really breathing", ASSESS).let {
            // "not really breathing" isn't in the explicit list → negation check must catch it → no RECOVERY switch
            if (it is GuideIntent.SwitchTo && it.cardId == RECOVERY) error("negation missed") else GuideIntent.SwitchTo(CPR, "Not breathing")
        })
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("il ne respire plus", RECOVERY))
        // already on CPR: "not breathing" changes nothing, and must not fall through to "breathing"
        assertEquals(GuideIntent.None, i("he's not breathing", CPR))
    }

    @Test fun transcribedStopBreathing_switchesToCpr_neverStops_deviceTest() {
        // Real S24 Ultra transcript, 18:21 Fri: the guide ended instead of switching.
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("He's breathing he stop breathing srivall stop", RECOVERY))
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("he stop breathing", RECOVERY))
        assertEquals(GuideIntent.None, i("he stop breathing", CPR)) // already doing CPR: keep going, don't stop
        assertEquals(GuideIntent.Stop, i("stop")) // plain "stop" still ends the guide
    }

    @Test fun negatedBreathingDoesNotSwitchToRecovery() {
        assertEquals(GuideIntent.None, i("he's not really breathing", CPR))
    }

    @Test fun breathingOutsideCprIsNotASwitch() {
        assertEquals(GuideIntent.None, i("he is breathing", BLEEDING))
    }

    @Test fun otherConditions() {
        assertEquals(GuideIntent.SwitchTo(BLEEDING, "Bleeding"), i("there's a lot of blood"))
        assertEquals(GuideIntent.SwitchTo(SEIZURE, "Seizure"), i("he's fitting", ASSESS))
        assertEquals(GuideIntent.SwitchTo(ASSESS, "Unresponsive"), i("she passed out", BLEEDING))
        assertEquals(GuideIntent.None, i("still bleeding", BLEEDING)) // same card → no switch
    }

    @Test fun conditionBeatsNavigation() {
        assertEquals(GuideIntent.SwitchTo(CPR, "Not breathing"), i("next, he stopped breathing", RECOVERY))
    }

    @Test fun llmLabelsMapToTheSameSafeSet() {
        assertEquals(GuideIntent.SwitchTo(RECOVERY, "Breathing"), GuideCommands.fromLabel("BREATHING", CPR))
        assertEquals(GuideIntent.None, GuideCommands.fromLabel("BREATHING", BLEEDING))
        assertEquals(GuideIntent.None, GuideCommands.fromLabel("WRITE_NEW_STEPS", CPR))
    }
}
