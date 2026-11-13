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
}
