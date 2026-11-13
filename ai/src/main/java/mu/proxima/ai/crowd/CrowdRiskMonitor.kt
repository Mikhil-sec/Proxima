package mu.proxima.ai.crowd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.CrowdRisk
import mu.proxima.core.model.CrowdRiskLevel
import mu.proxima.core.engine.CrowdRiskEngine

/**
 * Re-scores crowd risk every [tickMs] from live mesh peers (anonymous cell centres, #28), this phone's
 * own location and the pipeline's incidents. When [raise] is set (Command Center only), each area at
 * WARNING or above produces ONE HAZARD/CROWD_CRUSH incident per [raiseEveryMs] on the normal pipeline.
 */
class CrowdRiskMonitor(
    private val mesh: MeshEngine,
    private val locate: LocateEngine,
    private val incidents: StateFlow<List<IncidentRecord>>,
    private val scope: CoroutineScope,
    /** Raises the alert on the pipeline and returns the created incident (so we can exclude it later). */
    private val raise: (suspend (RawReport) -> Incident?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 10_000,
    private val raiseEveryMs: Long = 10 * 60_000L,
    private val peerStaleMs: Long = 60_000,
    private val log: (String) -> Unit = {},
) : CrowdRiskEngine {

    private val _risks = MutableStateFlow<List<CrowdRisk>>(emptyList())
    override val risks: StateFlow<List<CrowdRisk>> = _risks.asStateFlow()

    private val history = ArrayDeque<Pair<Long, Map<CrowdRiskModel.Cell, Int>>>()
    private val lastRaised = HashMap<CrowdRiskModel.Cell, Long>()
    private val ownAlertIds = HashSet<String>()

    init {
        scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { log("crowd: tick failed: ${it.message}") }
                delay(tickMs)
            }
        }
    }

    internal suspend fun tick() {
        val now = clock()
        val phones = mesh.peers.value
            .filter { now - it.lastSeenMs <= peerStaleMs }
            .mapNotNull { it.location } + listOfNotNull(locate.selfLocation.value)

        while (history.isNotEmpty() && now - history.first().first > CrowdRiskModel.TREND_WINDOW_MS) history.removeFirst()
        val baseline = HashMap<CrowdRiskModel.Cell, Int>()
        for ((_, counts) in history) for ((cell, n) in counts) baseline[cell] = minOf(baseline[cell] ?: n, n)
        history.addLast(now to CrowdRiskModel.neighbourhoodCounts(phones))

        val out = CrowdRiskModel.evaluate(now, phones, incidents.value, baseline, ownAlertIds)
        if (out != _risks.value) log("crowd: ${out.joinToString { "${it.level}@${it.phonesInArea}p/${it.crowdReports}r" }.ifEmpty { "clear" }}")
        _risks.value = out

        val raiser = raise ?: return
        for (risk in out) {
            if (risk.level < CrowdRiskLevel.WARNING) continue
            val cell = CrowdRiskModel.cellOf(risk.cellCentre)
            val nearbyRaised = lastRaised.any { (c, t) -> now - t < raiseEveryMs && kotlin.math.abs(c.x - cell.x) <= 2 && kotlin.math.abs(c.y - cell.y) <= 2 }
            if (nearbyRaised) continue
            lastRaised[cell] = now
            log("crowd: raising ${risk.level} incident: ${risk.reason}")
            runCatching {
                raiser(RawReport(text = CrowdRiskModel.alertText(risk), createdAtMs = now, location = risk.cellCentre, typeHint = IncidentType.HAZARD))
            }.onSuccess { it?.let { inc -> ownAlertIds += inc.id } }
                .onFailure { log("crowd: raise failed: ${it.message}") }
        }
    }
}
