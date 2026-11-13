package mu.proxima.mesh

/**
 * When to (re)try a Nearby connection to a known neighbour. Pure, JVM-tested.
 *
 * Nearby usually does not report an endpoint as "found" again after a link drops, so the engine keeps
 * known endpoints and retries them itself, backing off so two phones don't hammer each other.
 */
object ReconnectPolicy {
    const val FIRST_MS = 2_000L
    const val MAX_MS = 30_000L
    /** The phone with the larger nodeId waits this long on first discovery (tie-break, see engine). */
    const val TIE_BREAK_WAIT_MS = 6_000L

    /** Delay before attempt number [attempt] (0-based): 2 s, 4 s, 8 s, 16 s, 30 s, 30 s … */
    fun backoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 10)
        return (FIRST_MS shl shift).coerceAtMost(MAX_MS)
    }

    /** First attempt time on discovery: the smaller nodeId asks right away, the other waits. */
    fun firstAttemptAt(selfNodeId: String, remoteNodeId: String, nowMs: Long): Long =
        if (selfNodeId < remoteNodeId) nowMs else nowMs + TIE_BREAK_WAIT_MS

    /** Ready to try now? `nextAttemptMs == null` means "don't retry" (e.g. dropped by the demo allow-list). */
    fun isDue(connected: Boolean, allowed: Boolean, nextAttemptMs: Long?, nowMs: Long): Boolean =
        !connected && allowed && nextAttemptMs != null && nowMs >= nextAttemptMs

    /**
     * Seen on a Honor X8b: several requestConnection calls in parallel all fail (8007 RADIO_ERROR / 8012 IO_ERROR)
     * and keep the radio busy, which also slowed relaying to 7–10 s. So: ONE attempt in flight at a time.
     */
    const val CONNECT_TIMEOUT_MS = 15_000L

    /** After this many failed attempts an endpoint is forgotten (ghost from a restarted phone); discovery re-adds real ones. */
    const val MAX_ATTEMPTS = 8

    data class Candidate(val id: String, val connected: Boolean, val allowed: Boolean, val nextAttemptMs: Long?, val attempts: Int)

    /** The single endpoint to try now, or null. Fewest attempts first (fresh phones before likely ghosts). */
    fun pickNext(candidates: Collection<Candidate>, nowMs: Long, inFlightUntilMs: Long): String? {
        if (nowMs < inFlightUntilMs) return null
        return candidates
            .filter { isDue(it.connected, it.allowed, it.nextAttemptMs, nowMs) }
            .minWithOrNull(compareBy<Candidate> { it.attempts }.thenBy { it.nextAttemptMs })
            ?.id
    }
}
