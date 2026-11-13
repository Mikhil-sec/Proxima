package mu.proxima.locate

import kotlinx.coroutines.flow.flowOf
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.TrackTarget

/**
 * Ready-made [TrackTarget]s, so every "which way do I walk?" in the app uses the one radar
 * ([mu.proxima.core.engine.LocateEngine.track]).
 *
 * Guided response → nearest help point:
 * ```
 * val best = helpPoints.nearest(self, setOf(HelpCategory.FIRST_AID_POST, HelpCategory.AMBULANCE)).first()
 * locate.track(LocateTargets.helpPoint(best.point)).collect { fix ->
 *     // fix.distanceM, fix.relativeBearingDeg (0..360 clockwise: 0 ahead, 90 right, 270 left), fix.proximity
 * }
 * ```
 * A help point never answers UWB or appears as a mesh peer, so the engine uses GPS + compass for it
 * (no mesh traffic). Cancel the collector when the card closes.
 */
object LocateTargets {
    /** Prefix that cannot collide with a mesh node id (those are "SELF-xxxx"), so UWB/RSSI never match it. */
    const val HELP_POINT_PREFIX = "help:"

    fun helpPoint(point: HelpPoint): TrackTarget = TrackTarget(
        nodeId = HELP_POINT_PREFIX + point.id,
        label = point.name,
        locations = flowOf(point.location),
    )
}
