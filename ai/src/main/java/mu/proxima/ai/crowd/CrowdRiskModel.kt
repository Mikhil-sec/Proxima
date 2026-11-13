package mu.proxima.ai.crowd

import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import kotlin.math.cos

/**
 * Pure crowd-crush risk scoring (JVM-testable). Inputs are anonymous: cell centres of phones and
 * incidents already on the pipeline. Design rules:
 * - density alone never goes above WATCH (a packed stage front is normal);
 * - WARNING needs at least one human crowd report;
 * - DANGER needs several reports, or a report + high density that is rising fast.
 */
object CrowdRiskModel {
    const val CELL_M = 25.0
    const val REPORT_RADIUS_M = 75.0
    const val REPORT_WINDOW_MS = 10 * 60_000L
    const val TREND_WINDOW_MS = 5 * 60_000L
    /** > REPORT_RADIUS_M so one report never yields two areas. */
    const val MERGE_RADIUS_M = 100.0
    /** Our own auto-raised alerts start with this; they never count as evidence (no feedback loop). */
    const val ALERT_PREFIX = "Crowd crush risk"

    data class Cell(val x: Int, val y: Int)

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun cellOf(p: GeoPoint): Cell = GeoMath.cellKey(p, CELL_M).let { Cell(it.first, it.second) }

    /** Inverse of GeoMath.cellKey (same equirectangular grid). */
    fun centreOf(c: Cell): GeoPoint {
        val lat = Math.toDegrees(c.y * CELL_M / EARTH_RADIUS_M)
        val lon = Math.toDegrees(c.x * CELL_M / (EARTH_RADIUS_M * cos(Math.toRadians(lat))))
        return GeoPoint(lat, lon)
    }

    private val crowdWords = listOf(
        "crush", "stampede", "pushing", "pushed", "trampl", "can't move", "cannot move", "cant move",
        "can't breathe", "cannot breathe", "packed", "squeez", "too many people", "bousculade", "écras", "ecras",
        "pousse", "on étouffe", "on etouffe",
    )
    private val faintSubtypes = setOf("UNCONSCIOUS", "HEAT_EXHAUSTION")

    fun isOwnAlert(i: Incident) = i.summary.startsWith(ALERT_PREFIX)

    fun isCrowdReport(i: Incident): Boolean =
        !isOwnAlert(i) && (
            (i.type == IncidentType.HAZARD && i.subtype == "CROWD_CRUSH") ||
                i.summary.lowercase().let { s -> crowdWords.any { it in s } }
            )

    fun isFaint(i: Incident) = i.type == IncidentType.MEDICAL && i.subtype in faintSubtypes

    /** Phones per 3×3 neighbourhood (≈ 75 m square), keyed by centre cell. Used for the trend history. */
    fun neighbourhoodCounts(phones: List<GeoPoint>): Map<Cell, Int> {
        val perCell = phones.groupingBy { cellOf(it) }.eachCount()
        val out = HashMap<Cell, Int>()
        for ((c, _) in perCell) for (dx in -1..1) for (dy in -1..1) {
            val n = Cell(c.x + dx, c.y + dy)
            if (n !in out) out[n] = around(perCell, n)
        }
        return out
    }

    private fun around(perCell: Map<Cell, Int>, c: Cell): Int {
        var n = 0
        for (dx in -1..1) for (dy in -1..1) n += perCell[Cell(c.x + dx, c.y + dy)] ?: 0
        return n
    }

    /**
     * @param baseline lowest neighbourhood count per cell over the last [TREND_WINDOW_MS] (for "rising").
     */
    fun evaluate(
        nowMs: Long,
        phones: List<GeoPoint>,
        incidents: List<IncidentRecord>,
        baseline: Map<Cell, Int> = emptyMap(),
        /** Ids of alerts this phone raised itself: never evidence, whatever an LLM did to their summary. */
        ownAlertIds: Set<String> = emptySet(),
    ): List<CrowdRisk> {
        val counts = neighbourhoodCounts(phones)
        val recent = incidents.filter {
            !it.resolved && it.incident.location != null && it.incident.id !in ownAlertIds &&
                nowMs - maxOf(it.incident.createdAtMs, it.lastUpdatedMs) <= REPORT_WINDOW_MS
        }
        val crowd = recent.filter { isCrowdReport(it.incident) }
        val faints = recent.filter { isFaint(it.incident) }

        // Candidate areas: wherever there are phones, plus wherever someone reported a crowd problem.
        val candidates = counts.keys + crowd.mapNotNull { r -> r.incident.location?.let { cellOf(it) } }

        val scored = candidates.mapNotNull { cell ->
            val centre = centreOf(cell)
            val near = { r: IncidentRecord -> GeoMath.distanceM(centre, r.incident.location!!) <= REPORT_RADIUS_M }
            val phonesNear = counts[cell] ?: 0
            val crowdNear = crowd.filter(near).sumOf { it.incident.reportCount.coerceAtLeast(1) }
            val faintsNear = faints.filter(near).sumOf { it.incident.reportCount.coerceAtLeast(1) }
            val was = baseline[cell]
            val rising = was != null && phonesNear - was >= 3

            val d = when { phonesNear >= 8 -> 2; phonesNear >= 4 -> 1; else -> 0 }
            val r = when { crowdNear >= 3 -> 2; crowdNear >= 1 -> 1; else -> 0 }
            val f = if (faintsNear >= 2) 1 else 0
            val t = if (rising) 1 else 0
            val score = d + r + f + t

            val level = when {
                r == 2 && (d >= 1 || f == 1 || t == 1) -> CrowdRiskLevel.DANGER
                r >= 1 && d == 2 && t == 1 -> CrowdRiskLevel.DANGER
                r >= 1 && score >= 2 -> CrowdRiskLevel.WARNING
                r >= 1 || score >= 2 -> CrowdRiskLevel.WATCH
                else -> null
            } ?: return@mapNotNull null

            val reason = buildList {
                if (phonesNear > 0) add("$phonesNear phones within 40 m" + if (rising) " (up from $was)" else "")
                if (crowdNear > 0) add("$crowdNear crowd report" + if (crowdNear > 1) "s" else "")
                if (faintsNear > 0) add("$faintsNear fainting/heat case" + if (faintsNear > 1) "s" else "")
            }.joinToString(" · ")
            CrowdRisk(centre, level, phonesNear, crowdNear, reason) to (phonesNear + 10 * crowdNear)
        }

        // Neighbourhoods and report radii overlap: keep the strongest cell, merge everything within
        // MERGE_RADIUS_M of it into that one area (one hotspot = one warning).
        val kept = mutableListOf<CrowdRisk>()
        for ((risk, _) in scored.sortedWith(compareByDescending<Pair<CrowdRisk, Int>> { it.first.level }.thenByDescending { it.second })) {
            if (kept.none { GeoMath.distanceM(it.cellCentre, risk.cellCentre) <= MERGE_RADIUS_M }) kept += risk
        }
        return kept
    }

    /** Text of the auto-raised incident. Facts only, no instructions (the AI never authors guidance). */
    fun alertText(risk: CrowdRisk): String = "$ALERT_PREFIX (${risk.level.name.lowercase()}): ${risk.reason}".take(140)
}
