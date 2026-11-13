package mu.proxima.locate

import mu.proxima.core.model.Proximity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure maths behind the radar (no Android imports, unit-tested on the JVM). */
object RadarMath {

    /**
     * Heading (degrees clockwise from MAGNETIC north, 0..360) of the direction the user is "pointing"
     * the phone, from a rotation matrix (SensorManager.getRotationMatrixFromVector, row-major 3x3,
     * device → world with world X=east, Y=north, Z=up).
     *
     * We add the horizontal parts of the phone's top edge (+Y) and its back (−Z). Held flat, the
     * top edge points forward; held upright like a camera, the back points forward; tilted in
     * between, both point the same way. So one formula works for every grip without a jump at
     * a threshold. Returns null when the phone is in a pose with no usable forward direction.
     */
    fun headingFromRotationMatrix(r: FloatArray): Double? {
        require(r.size >= 9) { "expected a 3x3 rotation matrix" }
        val east = (r[1] - r[2]).toDouble()
        val north = (r[4] - r[5]).toDouble()
        if (hypot(east, north) < 0.2) return null
        return normalizeDeg(Math.toDegrees(atan2(east, north)))
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Distance → coarse proximity ring for the radar and haptics. */
    fun proximityForDistance(distanceM: Double): Proximity = when {
        distanceM < 3 -> Proximity.HERE
        distanceM < 15 -> Proximity.CLOSE
        distanceM < 60 -> Proximity.NEAR
        else -> Proximity.FAR
    }

    /**
     * Bluetooth/Wi-Fi RSSI → proximity, for "warmer / colder" when there is no GPS fix (indoors).
     * Thresholds are typical phone-to-phone BLE values; tune them on the demo phones.
     */
    fun proximityForRssi(rssiDbm: Int): Proximity = when {
        rssiDbm >= -55 -> Proximity.HERE
        rssiDbm >= -70 -> Proximity.CLOSE
        rssiDbm >= -85 -> Proximity.NEAR
        else -> Proximity.FAR
    }

    /**
     * UWB azimuth (−90..+90, 0 = straight out of the phone's back) → radar relative bearing (0..360).
     * Null when there is no azimuth or the peer is too close for the angle to mean anything
     * (device check 13 Nov: below ~0.3 m it flips between ±90°; 19:12 re-check: 35–89° jumps at 0.30–0.34 m, so cut at 0.5 m).
     * [sign] = +1 if positive azimuth means "to the right". VERIFY ON DEVICE and flip if the arrow is mirrored.
     */
    fun uwbRelativeBearingDeg(azimuthDeg: Double?, distanceM: Double, sign: Int = UWB_AZIMUTH_SIGN): Double? {
        if (azimuthDeg == null || distanceM < UWB_MIN_AOA_DISTANCE_M) return null
        return normalizeDeg(azimuthDeg * sign)
    }

    const val UWB_AZIMUTH_SIGN = 1
    const val UWB_MIN_AOA_DISTANCE_M = 0.5

    /** Combined 1-sigma uncertainty of a distance between two independent fixes. */
    fun combinedAccuracyM(a: Float?, b: Float?): Double? {
        if (a == null && b == null) return null
        val x = (a ?: 0f).toDouble()
        val y = (b ?: 0f).toDouble()
        return sqrt(x * x + y * y)
    }
}

/**
 * Low-pass filter for angles. Averages the unit vector (sin, cos) instead of the raw degrees,
 * so 359° → 1° is a 2° step, not a 358° spin.
 */
class HeadingSmoother(private val alpha: Double = 0.15) {
    private var s = 0.0
    private var c = 0.0
    private var primed = false

    fun update(deg: Double): Double {
        val rad = Math.toRadians(deg)
        if (!primed) {
            s = sin(rad); c = cos(rad); primed = true
        } else {
            s += alpha * (sin(rad) - s)
            c += alpha * (cos(rad) - c)
        }
        return RadarMath.normalizeDeg(Math.toDegrees(atan2(s, c)))
    }

    fun reset() { primed = false }
}
