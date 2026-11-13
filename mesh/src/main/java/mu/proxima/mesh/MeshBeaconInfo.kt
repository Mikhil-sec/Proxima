package mu.proxima.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import kotlin.math.cos

/**
 * What this phone advertises in its BEACON every ~10 s. The mesh can't see GPS or the AI runtime, so the app
 * wires these once (UI lane, ProximaGraph):
 *
 *   scope.launch { locate.selfLocation.collect { MeshBeaconInfo.location.value = it } }
 *   scope.launch { triage.capability.collect { MeshBeaconInfo.llmReady.value = it == TriageCapability.ON_DEVICE_LLM } }
 *
 * Privacy: the raw position never leaves the phone. [coarse] snaps it to the centre of its anonymous
 * [GeoMath.cellKey] grid cell (25 m) before it goes on the wire — the same cells the density heatmap uses.
 */
object MeshBeaconInfo {
    val location: MutableStateFlow<GeoPoint?> = MutableStateFlow(null)
    val llmReady: MutableStateFlow<Boolean> = MutableStateFlow(false)

    const val CELL_M = 25.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Centre of the grid cell containing [p]; `GeoMath.cellKey(coarse(p)) == GeoMath.cellKey(p)`. No accuracy, no altitude. */
    fun coarse(p: GeoPoint, cellM: Double = CELL_M): GeoPoint {
        val (e, n) = GeoMath.cellKey(p, cellM)
        val lat = Math.toDegrees(n * cellM / EARTH_RADIUS_M)
        val lon = Math.toDegrees(e * cellM / (EARTH_RADIUS_M * cos(Math.toRadians(lat))))
        return GeoPoint(lat, lon, accuracyM = cellM.toFloat())
    }
}
