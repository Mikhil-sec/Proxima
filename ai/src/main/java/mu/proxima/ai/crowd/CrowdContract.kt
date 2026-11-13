package mu.proxima.ai.crowd

import kotlinx.coroutines.flow.StateFlow
import mu.proxima.core.model.GeoPoint

// TEMPORARY HOME: mirrors the additive contract proposed in #30. Moves to core/model + core/engine
// in a small dedicated PR after UI's 👍; only the imports change.

enum class CrowdRiskLevel { WATCH, WARNING, DANGER }

data class CrowdRisk(
    val cellCentre: GeoPoint,
    val level: CrowdRiskLevel,
    /** Phones seen within ~40 m (3×3 grid cells). App users only: a lower bound on the real crowd. */
    val phonesInArea: Int,
    /** Crowd reports within 75 m in the last 10 min (merged reports count each reporter). */
    val crowdReports: Int,
    /** Plain-language reason, e.g. "9 phones within 40 m (up from 3) · 2 crowd reports". */
    val reason: String,
)

/** AI lane. Crowd-crush early warning from anonymous density cells + incident reports. */
interface CrowdRiskEngine {
    /** Only areas at WATCH or above, most severe first. */
    val risks: StateFlow<List<CrowdRisk>>
}
