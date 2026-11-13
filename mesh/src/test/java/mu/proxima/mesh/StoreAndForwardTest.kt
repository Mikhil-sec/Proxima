package mu.proxima.mesh

import kotlinx.coroutines.runBlocking
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAndForwardTest {

    private val storeMs = 30 * 60 * 1000L
    private val now = 10_000_000L

    private fun p(id: String, sev: Severity, ageMs: Long, path: List<String> = listOf("A"), ttl: Int = 5) = MeshPacket(
        packetId = id, kind = PacketKind.INCIDENT, originNodeId = "A", originKey = "k",
        createdAtMs = now - ageMs, severity = sev, ttl = ttl, path = path, payload = "{}",
    )

    @Test fun criticalFirstThenOldestFirst() {
        val stored = listOf(
            p("low", Severity.LOW, 1_000),
            p("crit-new", Severity.CRITICAL, 1_000),
            p("high", Severity.HIGH, 5_000),
            p("crit-old", Severity.CRITICAL, 9_000),
        )
        val order = MeshRelay.flushOrder(stored, "C", now, storeMs).map { it.packetId }
        assertEquals(listOf("crit-old", "crit-new", "high", "low"), order)
    }

    @Test fun expiredPacketsAreNotFlushed() {
        val stored = listOf(p("fresh", Severity.MEDIUM, 60_000), p("stale", Severity.CRITICAL, storeMs + 1))
        assertEquals(listOf("fresh"), MeshRelay.flushOrder(stored, "C", now, storeMs).map { it.packetId })
    }

    @Test fun neighbourAlreadyOnPathGetsNothing() {
        val stored = listOf(p("x", Severity.HIGH, 1_000, path = listOf("A", "B")))
        assertTrue(MeshRelay.flushOrder(stored, "B", now, storeMs).isEmpty())
        assertEquals(1, MeshRelay.flushOrder(stored, "C", now, storeMs).size)
    }

    @Test fun seenStoreDropsDuplicatesEvenAfterRestart() = runBlocking {
        val dao = FakeSeenDao()
        val first = SeenStore(dao) { now }
        assertTrue(first.markFirstSeen("p1"))
        assertFalse(first.markFirstSeen("p1"))           // in-memory hit

        val afterRestart = SeenStore(dao) { now }        // new memory, same database
        assertFalse(afterRestart.markFirstSeen("p1"))    // Room hit
        assertTrue(afterRestart.markFirstSeen("p2"))
    }

    @Test fun seenStorePrunesOldIds() = runBlocking {
        var t = now
        val dao = FakeSeenDao()
        SeenStore(dao) { t }.markFirstSeen("old")
        t += 3 * 60 * 60 * 1000L
        SeenStore(dao) { t }.prune()
        assertTrue(SeenStore(dao) { t }.markFirstSeen("old"))
    }

    private class FakeSeenDao : SeenPacketDao {
        val rows = mutableMapOf<String, Long>()
        override suspend fun insert(row: SeenPacket): Long =
            if (rows.containsKey(row.packetId)) -1L else { rows[row.packetId] = row.firstSeenMs; rows.size.toLong() }
        override suspend fun pruneOlderThan(olderThanMs: Long): Int {
            val old = rows.filterValues { it < olderThanMs }.keys
            old.forEach { rows.remove(it) }
            return old.size
        }
    }
}
