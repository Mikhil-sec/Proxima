package mu.proxima.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test fun backoffDoublesThenCaps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map { ReconnectPolicy.backoffMs(it) })
        assertEquals(30_000L, ReconnectPolicy.backoffMs(500)) // no overflow
        assertEquals(2_000L, ReconnectPolicy.backoffMs(-1))
    }

    @Test fun tieBreakSmallerIdGoesFirst() {
        assertEquals(100L, ReconnectPolicy.firstAttemptAt("A", "B", 100L))
        assertEquals(100L + ReconnectPolicy.TIE_BREAK_WAIT_MS, ReconnectPolicy.firstAttemptAt("B", "A", 100L))
    }

    @Test fun oneAttemptAtATimeFewestAttemptsFirst() {
        val c = listOf(
            ReconnectPolicy.Candidate("ghost", connected = false, allowed = true, nextAttemptMs = 0, attempts = 6),
            ReconnectPolicy.Candidate("fresh", connected = false, allowed = true, nextAttemptMs = 10, attempts = 1),
            ReconnectPolicy.Candidate("linked", connected = true, allowed = true, nextAttemptMs = 0, attempts = 0),
            ReconnectPolicy.Candidate("blocked", connected = false, allowed = false, nextAttemptMs = 0, attempts = 0),
        )
        assertEquals("fresh", ReconnectPolicy.pickNext(c, nowMs = 50, inFlightUntilMs = 0))
        assertEquals(null, ReconnectPolicy.pickNext(c, nowMs = 50, inFlightUntilMs = 51)) // another attempt in flight
        assertEquals(null, ReconnectPolicy.pickNext(c.drop(2), nowMs = 50, inFlightUntilMs = 0))
    }

    @Test fun dueOnlyWhenDisconnectedAllowedAndTimeReached() {
        assertTrue(ReconnectPolicy.isDue(connected = false, allowed = true, nextAttemptMs = 50, nowMs = 50))
        assertFalse(ReconnectPolicy.isDue(connected = false, allowed = true, nextAttemptMs = 51, nowMs = 50))
        assertFalse(ReconnectPolicy.isDue(connected = true, allowed = true, nextAttemptMs = 0, nowMs = 50))
        assertFalse(ReconnectPolicy.isDue(connected = false, allowed = false, nextAttemptMs = 0, nowMs = 50))
        assertFalse(ReconnectPolicy.isDue(connected = false, allowed = true, nextAttemptMs = null, nowMs = 50))
    }
}
