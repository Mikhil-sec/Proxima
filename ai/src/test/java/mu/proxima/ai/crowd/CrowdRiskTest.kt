package mu.proxima.ai.crowd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.Peer
import mu.proxima.core.model.RadarFix
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TrackTarget
import mu.proxima.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import mu.proxima.core.model.CrowdRiskLevel

@OptIn(ExperimentalCoroutinesApi::class)
class CrowdRiskTest {
    // Ganga Talao (Grand Bassin), Mauritius
    private val origin = GeoPoint(-20.4186, 57.4914)
    private fun at(eastM: Double, northM: Double) = GeoPoint(
        origin.lat + Math.toDegrees(northM / 6_371_000.0),
        origin.lon + Math.toDegrees(eastM / (6_371_000.0 * cos(Math.toRadians(origin.lat)))),
    )
    private fun phones(n: Int, eastM: Double = 0.0) = List(n) { at(eastM + (it % 3) * 3.0, (it / 3) * 3.0) }

    private var nextId = 0
    private fun report(
        summary: String, eastM: Double = 0.0, ageMs: Long = 60_000, type: IncidentType = IncidentType.HAZARD,
        subtype: String? = null, count: Int = 1, resolved: Boolean = false, id: String = "i${nextId++}",
    ) = IncidentRecord(
        Incident(id, type, Severity.HIGH, subtype, summary, at(eastM, 0.0), createdAtMs = NOW - ageMs, reporterNodeId = "n", reportCount = count),
        signatureValid = true, hopsTravelled = 1, lastUpdatedMs = NOW - ageMs, resolved = resolved,
    )

    private fun eval(p: List<GeoPoint>, r: List<IncidentRecord> = emptyList(), baseline: Map<CrowdRiskModel.Cell, Int> = emptyMap()) =
        CrowdRiskModel.evaluate(NOW, p, r, baseline)

    @Test fun nothing_noRisk() = assertTrue(eval(emptyList()).isEmpty())

    @Test fun densityAloneIsAtMostWatch() {
        assertTrue(eval(phones(5)).isEmpty())
        val r = eval(phones(12)).single()
        assertEquals(CrowdRiskLevel.WATCH, r.level)
        assertEquals(12, r.phonesInArea)
    }

    @Test fun singleCrowdReportAlone_isWatch() {
        assertEquals(CrowdRiskLevel.WATCH, eval(emptyList(), listOf(report("people pushing at the gate"))).single().level)
    }

    @Test fun reportPlusDensity_isWarning() {
        val r = eval(phones(5), listOf(report("people pushing at the gate"))).single()
        assertEquals(CrowdRiskLevel.WARNING, r.level)
        assertEquals("5 phones within 40 m · 1 crowd report", r.reason)
    }

    @Test fun reasonSingularForOnePhone() {
        val r = eval(phones(1), listOf(report("people pushing at the gate"))).single()
        assertEquals("1 phone within 40 m · 1 crowd report", r.reason)
    }

    @Test fun severalReportsPlusDensity_isDanger() {
        val reports = listOf(report("stampede near the stalls"), report("can't move, too many people"), report("x", subtype = "CROWD_CRUSH"))
        assertEquals(CrowdRiskLevel.DANGER, eval(phones(5), reports).single().level)
    }

    @Test fun mergedReportCountsEachReporter() {
        assertEquals(CrowdRiskLevel.DANGER, eval(phones(4), listOf(report("people pushing", count = 3))).single().level)
    }

    @Test fun risingDensityPlusReport_isDanger() {
        val cell = CrowdRiskModel.cellOf(origin)
        val r = eval(phones(9), listOf(report("people pushing")), baseline = mapOf(cell to 2)).single()
        assertEquals(CrowdRiskLevel.DANGER, r.level)
        assertTrue(r.reason, r.reason.contains("up from 2"))
    }

    @Test fun faintClusterAddsToRisk() {
        val faints = listOf(
            report("woman collapsed", type = IncidentType.MEDICAL, subtype = "UNCONSCIOUS"),
            report("man fainted in the heat", type = IncidentType.MEDICAL, subtype = "HEAT_EXHAUSTION"),
        )
        assertEquals(CrowdRiskLevel.WATCH, eval(phones(5), faints).single().level) // no crowd report → never above WATCH
        assertEquals(CrowdRiskLevel.DANGER, eval(phones(5), faints + report("pushing", count = 3)).single().level)
    }

    @Test fun oldResolvedAndFarReportsAreIgnored() {
        val ignored = listOf(
            report("pushing", ageMs = 11 * 60_000L),
            report("pushing", resolved = true),
            report("pushing", eastM = 300.0),
        )
        assertTrue(eval(phones(5), ignored).none { it.cellCentre.lon < at(150.0, 0.0).lon && it.level >= CrowdRiskLevel.WARNING })
    }

    @Test fun ownAlertsAreNeverEvidence() {
        val alert = report("${CrowdRiskModel.ALERT_PREFIX} (warning): 5 phones", subtype = "CROWD_CRUSH")
        assertTrue(eval(phones(3), listOf(alert)).isEmpty())
        // Even if an LLM rewrote its summary, the id excludes it.
        val rewritten = report("Crowd crush danger at the gate", subtype = "CROWD_CRUSH", id = "mine")
        assertTrue(CrowdRiskModel.evaluate(NOW, phones(3), listOf(rewritten), ownAlertIds = setOf("mine")).isEmpty())
    }

    @Test fun overlappingCellsCollapseToOneArea() {
        val r = eval(phones(5), listOf(report("people pushing")))
        assertEquals(1, r.size)
    }

    @Test fun twoSeparateHotspotsBothReported() {
        val r = eval(phones(5) + phones(5, eastM = 400.0), listOf(report("pushing"), report("pushing", eastM = 400.0)))
        assertEquals(2, r.count { it.level == CrowdRiskLevel.WARNING })
    }

    @Test fun alertTextIsFactualAndShort() {
        val t = CrowdRiskModel.alertText(eval(phones(5), listOf(report("pushing"))).single())
        assertTrue(t.startsWith(CrowdRiskModel.ALERT_PREFIX))
        assertTrue(t.length <= 140)
    }

    // ---------------------------------------------------------------- monitor

    private class FakeMesh(peers: List<Peer>) : MeshEngine {
        override val selfNodeId = "CC"
        override val requiredPermissions = emptyList<String>()
        override val running = MutableStateFlow(true)
        override val peers = MutableStateFlow(peers)
        override val inbound = MutableSharedFlow<MeshPacket>()
        override val hops = MutableSharedFlow<HopEvent>()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(packet: MeshPacket) = Unit
    }

    private class FakeLocate(self: GeoPoint?) : LocateEngine {
        override val requiredPermissions = emptyList<String>()
        override val selfLocation = MutableStateFlow(self)
        override val headingDeg = MutableStateFlow<Float?>(null)
        override val uwbAvailable = false
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override fun track(target: TrackTarget) = kotlinx.coroutines.flow.emptyFlow<RadarFix>()
    }

    private fun peer(i: Int, p: GeoPoint, seen: Long) = Peer("p$i", "P$i", DeviceTier.TIER2_SCOUT, Transport.NEARBY_AUTO, lastSeenMs = seen, location = p)

    @Test fun monitorRaisesOncePerAreaAndExcludesItsOwnAlert() = runTest {
        var now = NOW
        val mesh = FakeMesh(phones(4).mapIndexed { i, p -> peer(i, p, NOW) })
        val incidents = MutableStateFlow(listOf(report("people pushing at the gate")))
        val raised = mutableListOf<RawReport>()
        val monitor = CrowdRiskMonitor(
            mesh, FakeLocate(origin), incidents, backgroundScope,
            raise = { raw ->
                raised += raw
                Incident("alert${raised.size}", IncidentType.HAZARD, Severity.CRITICAL, "CROWD_CRUSH", "LLM-rewritten summary",
                    raw.location, createdAtMs = now, reporterNodeId = "CC").also { inc ->
                    incidents.value = incidents.value + IncidentRecord(inc, true, 0, now) // our alert comes back on the pipeline
                }
            },
            clock = { now },
        )
        runCurrent()
        assertEquals(CrowdRiskLevel.WARNING, monitor.risks.value.single().level)
        assertEquals(1, raised.size)
        assertEquals(IncidentType.HAZARD, raised.single().typeHint)

        repeat(5) { now += 10_000; advanceTimeBy(10_000); runCurrent() }
        assertEquals(1, raised.size)                                            // once per area per 10 min
        assertEquals(CrowdRiskLevel.WARNING, monitor.risks.value.single().level) // own alert didn't escalate it
    }

    @Test fun monitorWithoutRaiserNeverRaises_andDropsStalePeers() = runTest {
        val mesh = FakeMesh(phones(12).mapIndexed { i, p -> peer(i, p, NOW - 120_000) }) // all stale
        val monitor = CrowdRiskMonitor(mesh, FakeLocate(origin), MutableStateFlow(emptyList()), backgroundScope, clock = { NOW })
        runCurrent()
        assertTrue(monitor.risks.value.isEmpty())
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
