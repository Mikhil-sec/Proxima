package mu.proxima.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import mu.proxima.core.model.Beacon
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.Role
import mu.proxima.core.model.RolePass
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

    /**
     * Role phones only (#55, #69): the organiser-signed pass this phone carries in every beacon. Set by the app
     * once TRUST has delivered the pass (TRUST role-phone mode, stable identity). Null on every ordinary phone.
     */
    val rolePass: MutableStateFlow<RolePass?> = MutableStateFlow(null)

    /**
     * The role a receiving phone may show for a beacon, or null. A pass alone proves nothing (it travels in the
     * clear and can be replayed), so it counts only if [verify] (= `trust.verifyRoleClaim`) accepts it together
     * with the signed BEACON [packet] it arrived in. Any failure, including a throwing verifier, means "no role".
     */
    fun verifiedRole(
        beacon: Beacon,
        packet: MeshPacket,
        nowMs: Long,
        verify: (RolePass, MeshPacket, Long) -> Boolean,
    ): Pair<Role, String>? {
        val pass = beacon.role ?: return null
        if (beacon.nodeId != packet.originNodeId) return null // the role must belong to the node that signed it
        if (pass.expiresAtMs <= nowMs) return null
        return if (runCatching { verify(pass, packet, nowMs) }.getOrDefault(false)) pass.role to pass.label else null
    }

    /** The key that signed this peer's beacon (#61), for pairing. Only when the beacon names its own signer. */
    fun signerKey(beacon: Beacon, packet: MeshPacket): String? =
        if (beacon.nodeId == packet.originNodeId && packet.originKey.isNotEmpty()) packet.originKey else null

    /**
     * Local friend label if this beacon's rotating tag matches one of THIS phone's friends (#61). The match happens
     * only here, against beacons already heard; nobody broadcasts a search. Any failure means "not a friend".
     */
    fun friendLabel(beacon: Beacon, packet: MeshPacket, nowMs: Long, match: (String, Long) -> String?): String? {
        val tag = beacon.friendTag ?: return null
        if (beacon.nodeId != packet.originNodeId) return null
        return runCatching { match(tag, nowMs) }.getOrNull()
    }

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
