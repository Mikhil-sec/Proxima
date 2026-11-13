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

    @Test fun controlAndBeaconPacketsAreNeverReplayed() {
        val uwb = p("uwb", Severity.HIGH, 1_000).copy(kind = PacketKind.CONTROL)
        val beacon = p("beacon", Severity.LOW, 1_000).copy(kind = PacketKind.BEACON)
        val sos = p("sos", Severity.CRITICAL, 1_000)
        assertFalse(MeshRelay.isStorable(uwb))
        assertFalse(MeshRelay.isStorable(beacon))
        assertTrue(MeshRelay.isStorable(sos))
        assertEquals(listOf("sos"), MeshRelay.flushOrder(listOf(uwb, beacon, sos), "C", now, storeMs).map { it.packetId })
    }

    @Test fun unrelayedBeaconNamesTheNodeAtTheOtherEndOfTheLink() {
        val beacon = p("b", Severity.LOW, 0).copy(kind = PacketKind.BEACON, originNodeId = "newid01")
        assertEquals("newid01", MeshRelay.directSenderOf(beacon))
        assertEquals(null, MeshRelay.directSenderOf(beacon.copy(hopCount = 1)))  // relayed: origin is further away
        assertEquals(null, MeshRelay.directSenderOf(p("i", Severity.HIGH, 0)))    // incidents prove nothing
        assertFalse(MeshRelay.isSilent(lastSeenMs = 0, nowMs = 85_000))           // ~8 missed beacons: keep (Doze)
        assertTrue(MeshRelay.isSilent(lastSeenMs = 0, nowMs = 91_000))
    }

    @Test fun afterRestartOnlyLiveIncidentsAndSealedNoticesAreReplayedOldestFirst() {
        val stored = listOf(
            p("lost-report", Severity.HIGH, 5_000),
            p("notice", Severity.CRITICAL, 1_000).copy(kind = PacketKind.SEALED),
            p("triage", Severity.HIGH, 2_000).copy(kind = PacketKind.TRIAGE_REQUEST), // AI must not redo it
            p("expired", Severity.CRITICAL, storeMs + 1),
        )
        assertEquals(listOf("lost-report", "notice"), MeshRelay.replayAfterRestart(stored, now, storeMs).map { it.packetId })
    }

    @Test fun finderPositionUpdatesReplaceEachOtherButNothingElse() {
        fun notice(id: String, age: Long, origin: String = "F", to: String = "G") =
            p(id, Severity.CRITICAL, age).copy(kind = PacketKind.SEALED, originNodeId = origin, recipientKey = to)
        val sos = p("sos", Severity.CRITICAL, 60_000)
        val old1 = notice("n1", 30_000); val old2 = notice("n2", 20_000)
        val otherFinder = notice("n-other", 20_000, origin = "F2")
        val otherGuardian = notice("n-otherG", 20_000, to = "G2")
        val stored = listOf(sos, old1, old2, otherFinder, otherGuardian)

        val newest = notice("n3", 10_000)
        assertEquals(setOf("n1", "n2"), MeshRelay.supersededBy(newest, stored).map { it.packetId }.toSet())
        assertTrue(MeshRelay.supersededBy(sos, stored).isEmpty())          // incidents never replace anything
        assertTrue(MeshRelay.supersededBy(old1, listOf(newest)).isEmpty()) // an older one never replaces a newer one
        val invite = notice("inv", 5_000).copy(channel = "friend")        // #61: other channel, same two phones
        assertTrue(MeshRelay.supersededBy(invite, stored + newest).isEmpty())
        assertTrue(MeshRelay.supersededBy(notice("n4", 1_000), listOf(invite)).isEmpty())
        // 180 updates in 30 min from one finder now cost one slot, so the SOS is never evicted by them.
        val flood = (1..180).map { notice("u$it", (181 - it) * 10_000L) }
        assertEquals(179, MeshRelay.supersededBy(flood.last(), flood).size)
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
