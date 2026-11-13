package mu.proxima.core.model

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
)

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres (haversine). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from true north, 0..360. */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Bearing relative to where the phone points: 0 = straight ahead, 90 = right. */
    fun relativeBearingDeg(absoluteBearingDeg: Double, headingDeg: Double): Double =
        ((absoluteBearingDeg - headingDeg) % 360.0 + 360.0) % 360.0

    /** Offset a point by metres east/north (good enough at venue scale). */
    fun offset(origin: GeoPoint, eastM: Double, northM: Double): GeoPoint {
        val dLat = northM / EARTH_RADIUS_M
        val dLon = eastM / (EARTH_RADIUS_M * cos(Math.toRadians(origin.lat)))
        return GeoPoint(origin.lat + Math.toDegrees(dLat), origin.lon + Math.toDegrees(dLon))
    }

    /** Coarse grid cell key for heatmaps, ~[cellM] metres square. Never use raw positions for density. */
    fun cellKey(p: GeoPoint, cellM: Double = 25.0): Pair<Int, Int> {
        val northM = Math.toRadians(p.lat) * EARTH_RADIUS_M
        val eastM = Math.toRadians(p.lon) * EARTH_RADIUS_M * cos(Math.toRadians(p.lat))
        return (eastM / cellM).roundToInt() to (northM / cellM).roundToInt()
    }
}
