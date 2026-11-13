package mu.proxima.mesh

import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageSource
import mu.proxima.core.pipeline.ProximaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.random.Random

/** Surprise card "Scale up" (14 Nov): the three surge rules in SurgeControl.kt + MeshRelay.evictFor. */
class SurgeControlTest {
    private val gate = GeoPoint(-20.4880, 57.4495) // Ganga Talao-ish

    private fun incident(type: IncidentType, subtype: String?, at: GeoPoint?, sev: Severity = Severity.CRITICAL) =
        MeshPacket(
            packetId = "p${Random.nextLong()}", kind = PacketKind.INCIDENT, originNodeId = "A", originKey = "kA",
            createdAtMs = 1L, severity = sev, ttl = sev.hopBudget,
            payload = ProximaJson.encodeToString(
                Incident.serializer(),
                Incident(id = "i${Random.nextLong()}", type = type, severity = sev, subtype = subtype, summary = "s",
                    location = at, createdAtMs = 1L, reporterNodeId = "A", triagedBy = TriageSource.RULES, confidence = 1f),
            ),
        )

    private fun beacon() = MeshPacket(
        packetId = "b${Random.nextLong()}", kind = PacketKind.BEACON, originNodeId = "A", originKey = "kA",
        createdAtMs = 1L, severity = Severity.LOW, ttl = 3, payload = "{}",
    )

    // ---------------------------------------------------------------- rule 1: collapse duplicate hazards

    @Test fun `same hazard in the same 25 m square is forwarded three times, then counted locally`() {
        val c = HazardCollapse(perCell = 3, windowMs = 120_000)
        val crush = { incident(IncidentType.HAZARD, "crowd_crush", gate) }
        assertEquals(listOf(true, true, true, false, false), (1..5).map { c.shouldForward(crush(), 1_000L) })
        // A different square, or a different hazard, is a different event.
        assertTrue(c.shouldForward(incident(IncidentType.HAZARD, "crowd_crush", GeoMath.offset(gate, 100.0, 0.0)), 1_000L))
        assertTrue(c.shouldForward(incident(IncidentType.HAZARD, "fire", gate), 1_000L))
        // After the window it travels again (a hazard that is still going on is news again).
        assertTrue(c.shouldForward(crush(), 1_000L + 121_000))
    }

    @Test fun `people are never collapsed - every medical and lost-person report travels`() {
        val c = HazardCollapse(perCell = 1)
        repeat(20) {
            assertTrue(c.shouldForward(incident(IncidentType.MEDICAL, "unconscious", gate), 1L))
            assertTrue(c.shouldForward(incident(IncidentType.LOST_PERSON, null, gate), 1L))
        }
        // No location = no square to compare: never collapsed.
        repeat(5) { assertTrue(c.shouldForward(incident(IncidentType.HAZARD, "fire", null), 1L)) }
        assertTrue(c.shouldForward(beacon(), 1L))
    }

    // ---------------------------------------------------------------- rule 2: rate cap + priority buffer

    @Test fun `under the cap nothing waits`() {
        val g = RelayGovernor<String>(burst = 30, perSecond = 10.0, priority = { 1 })
        repeat(30) { assertEquals(listOf("x$it"), g.offer("x$it", 0L)) }
        assertEquals(0, g.queued)
    }

    @Test fun `at equal severity a known hazard yields to a person`() {
        val crush = incident(IncidentType.HAZARD, "crowd_crush", gate)
        val person = incident(IncidentType.MEDICAL, "unconscious", gate)
        assertTrue(relayPriority(person) > relayPriority(crush))
        assertTrue(relayPriority(crush) > relayPriority(incident(IncidentType.MEDICAL, "bleeding", gate, Severity.HIGH)))
        assertEquals(0, relayPriority(beacon()))
    }

    @Test fun `over the cap the most urgent leaves first`() {
        val g = RelayGovernor<MeshPacket>(burst = 1, perSecond = 1.0, priority = ::relayPriority)
        g.offer(beacon(), 0L) // uses the one token
        val low = incident(IncidentType.HAZARD, "branch", gate, Severity.LOW)
        val crit = incident(IncidentType.MEDICAL, "unconscious", gate)
        assertTrue(g.offer(beacon(), 0L).isEmpty())
        assertTrue(g.offer(low, 0L).isEmpty())
        assertTrue(g.offer(crit, 0L).isEmpty())
        assertEquals(listOf(crit), g.drain(1_000L))
        assertEquals(listOf(low), g.drain(2_000L))
        assertEquals(PacketKind.BEACON, g.drain(3_000L).single().kind)
    }

    @Test fun `a full buffer sheds beacons first and never a critical while something less urgent waits`() {
        val g = RelayGovernor<MeshPacket>(burst = 1, perSecond = 0.0, maxQueue = 5, priority = ::relayPriority)
        g.offer(beacon(), 0L) // spends the only token
        val crit = incident(IncidentType.MEDICAL, "unconscious", gate)
        g.offer(crit, 0L)
        repeat(50) { g.offer(beacon(), 0L) }
        repeat(3) { g.offer(incident(IncidentType.HAZARD, "branch", gate, Severity.LOW), 0L) }
        assertEquals(5, g.queued)
        assertEquals(49L, g.shed) // 54 waited, 5 kept: the critical, 3 LOW and 1 beacon
        // What survived: the critical, the 3 LOW and the newest beacon. Given a token, the critical leaves first.
        val survivors = RelayGovernor<MeshPacket>(burst = 1, perSecond = 1.0, maxQueue = 5, priority = ::relayPriority)
        survivors.offer(beacon(), 0L) // spends the token
        survivors.offer(crit, 0L)
        repeat(50) { survivors.offer(beacon(), 0L) }
        assertEquals(listOf(crit), survivors.drain(1_000L))
    }

    // ---------------------------------------------------------------- rule 3: severity-aware store-and-forward

    @Test fun `store-and-forward evicts the least severe, not the oldest`() {
        val oldCritical = incident(IncidentType.MEDICAL, "unconscious", gate).copy(createdAtMs = 1)
        val newLow = incident(IncidentType.HAZARD, "branch", gate, Severity.LOW).copy(createdAtMs = 99)
        assertEquals(newLow, MeshRelay.evictFor(listOf(oldCritical, newLow)))
        val olderLow = newLow.copy(packetId = "older", createdAtMs = 50)
        assertEquals(olderLow, MeshRelay.evictFor(listOf(oldCritical, newLow, olderLow)))
    }

    // ---------------------------------------------------------------- the surge model

    /**
     * A MODEL, not a measurement. A 500 x 500 m festival ground: 1,000 phones spread out, plus [crowd] phones packed
     * within 30 m of the gate (a crush is a density problem). Each phone links to its 6 nearest within 60 m (Nearby
     * caps connections); links carry data both ways. A phone's radio delivers [radioPerSecond] payloads a second; a broadcast to k neighbours
     * costs k. Every phone beacons every 10 s (LOW, 3 hops). From t = 0 to 10 s the packed phones report the same
     * crowd crush (CRITICAL hazard, 10 hops). At t = 8 s a phone on the far side of the gate, ~90 m from the
     * Command Center, reports an unconscious person (CRITICAL medical): its route crosses the crowd.
     */
    class Result(val targetLatencyS: Double?, val maxWaiting: Int, val sends: Long, val shed: Long, val collapsed: Long)

    private fun simulate(surgeRules: Boolean, radioPerSecond: Int, crowd: Int = 400, seed: Int = 7, targetY: Double = 330.0, capPerSecond: Double = 10.0): Result {
        val rnd = Random(seed)
        val spread = 1000
        val n = spread + crowd
        val gateX = 250.0; val gateY = 190.0
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until spread) { xs[i] = rnd.nextDouble(0.0, 500.0); ys[i] = rnd.nextDouble(0.0, 500.0) }
        for (i in spread until n) {
            val r = 30.0 * Math.sqrt(rnd.nextDouble()); val a = rnd.nextDouble(0.0, 2 * Math.PI)
            xs[i] = gateX + r * Math.cos(a); ys[i] = gateY + r * Math.sin(a)
        }
        val cc = (0 until spread).minBy { Math.hypot(xs[it] - 250.0, ys[it] - 250.0) }
        val target = (0 until spread).minBy { Math.hypot(xs[it] - 250.0, ys[it] - targetY) }
        // Each phone dials its 6 nearest within 60 m; a Nearby connection carries data both ways, so links are two-way.
        val links = Array(n) { HashSet<Int>() }
        for (i in 0 until n) {
            (0 until n).asSequence().filter { it != i }.map { it to Math.hypot(xs[it] - xs[i], ys[it] - ys[i]) }
                .filter { it.second <= 60.0 }.sortedBy { it.second }.take(6).forEach { (j, _) -> links[i].add(j); links[j].add(i) }
        }
        val neighbours = Array(n) { links[it].toIntArray() }

        class Pkt(val id: Int, val prio: Int, val key: String?)
        class Fwd(val p: Pkt, val ttl: Int, val from: Int)
        val seen = Array(n) { HashSet<Int>() }
        val radio = Array(n) { ArrayDeque<Fwd>() }
        val budget = DoubleArray(n)
        val collapse = Array(n) { HazardCollapse() }
        val governor = Array(n) { RelayGovernor<Fwd>(burst = maxOf(3, (capPerSecond * 3).toInt()), perSecond = capPerSecond, priority = { it.p.prio }) }
        var inFlight = ArrayList<Triple<Int, Fwd, Int>>()
        var nextId = 0
        var sends = 0L; var collapsedCount = 0L; var maxWaiting = 0
        var arrived: Long? = null
        val targetId = -1
        val dt = 50L

        fun originate(at: Int, p: Pkt, ttl: Int) { seen[at].add(p.id); radio[at].addLast(Fwd(p, ttl, -1)) } // own: never waits
        fun receive(at: Int, f: Fwd, from: Int, now: Long) {
            if (!seen[at].add(f.p.id)) return
            if (at == cc && f.p.id == targetId && arrived == null) arrived = now
            if (f.ttl <= 1) return
            val fwd = Fwd(f.p, f.ttl - 1, from)
            if (!surgeRules) { radio[at].addLast(fwd); return }
            if (!collapse[at].shouldForwardKey(f.p.key, now)) { collapsedCount++; return }
            governor[at].offer(fwd, now).forEach { radio[at].addLast(it) }
        }

        var now = 0L
        while (now <= 40_000L) {
            if (now % 10_000L == 0L) (0 until n).forEach { originate(it, Pkt(nextId++, 0, null), 3) }
            if (now < 10_000L) (spread until n).filter { rnd.nextDouble() < dt / 10_000.0 }.forEach {
                originate(it, Pkt(nextId++, 8, "crowd_crush@${(xs[it] / 25).toInt()},${(ys[it] / 25).toInt()}"), 10)
            }
            if (now == 8_000L) originate(target, Pkt(targetId, 9, null), 10) // relayPriority: CRITICAL person 8, CRITICAL hazard 7 -> +1 here
            val delivering = inFlight; inFlight = ArrayList()
            delivering.forEach { (to, f, from) -> receive(to, f, from, now) }
            for (i in 0 until n) {
                if (surgeRules && now % 100L == 0L) governor[i].drain(now).forEach { radio[i].addLast(it) }
                // A broadcast to k links costs k; let the budget hold at least one full broadcast, or a busy phone deadlocks.
                budget[i] = minOf(budget[i] + radioPerSecond * dt / 1000.0, maxOf(radioPerSecond, neighbours[i].size).toDouble())
                while (radio[i].isNotEmpty()) {
                    val f = radio[i].first()
                    val targets = neighbours[i].filter { it != f.from }
                    if (budget[i] < targets.size) break
                    radio[i].removeFirst(); budget[i] -= targets.size; sends += targets.size
                    targets.forEach { inFlight += Triple(it, f, i) }
                }
                maxWaiting = maxOf(maxWaiting, radio[i].size + if (surgeRules) governor[i].queued else 0)
            }
            now += dt
        }
        return Result(arrived?.let { (it - 8_000L) / 1000.0 }, maxWaiting, sends, governor.sumOf { it.shed }, collapsedCount)
    }

    /**
     * Prints the slide table and asserts only what the model supports. At 60 and 30 payloads/s the rules cut radio
     * load and the worst backlog and never slow the unrelated critical. At 15/s NEITHER design delivers it: with
     * "first copy wins" dedup, a copy that took a slow detour (fewer hops left) is kept and the later, shorter one is
     * dropped, so the hop budget runs out. Known limitation; the fix is to re-forward a copy with more hops left.
     */
    @Test fun `surge model - the rules cut load and never slow the critical`() {
        for (radio in listOf(60, 30, 15)) {
            val plain = simulate(false, radio)
            val rules = simulate(true, radio)
            for ((name, r) in listOf("plain flooding" to plain, "surge rules" to rules)) println(
                "SURGE MODEL radio=$radio/s $name: unrelated critical reached the post in " +
                    (r.targetLatencyS?.let { "%.1f s".format(it) } ?: "NOT within 32 s") +
                    ", most waiting at one phone=${r.maxWaiting}, radio sends=${r.sends}, hazard copies not forwarded=${r.collapsed}",
            )
            if (radio >= 30) {
                assertNotNull(rules.targetLatencyS)
                assertTrue(rules.targetLatencyS!! <= (plain.targetLatencyS ?: Double.MAX_VALUE))
                assertTrue(rules.sends < plain.sends * 0.8)
                assertTrue(rules.maxWaiting * 2 < plain.maxWaiting)
            }
        }
    }
}
