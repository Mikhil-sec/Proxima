package mu.proxima.locate

/**
 * Cleans raw UWB angle-of-arrival before it drives the radar arrow (pure, unit-tested on the JVM).
 *
 * Measured 13 Nov 18:14 (S23+ → S26+ at ~1.7 m): raw azimuth jitters ±5°, has single-sample spikes of ±40°,
 * and sometimes reports exactly ±90° (edge of the antenna's field of view = "don't know"). Unfiltered,
 * the arrow would flick sideways several times a second in front of a panicking parent.
 *
 * Steps per reading: drop contact-range (< [RadarMath.UWB_MIN_AOA_DISTANCE_M]) and edge (|az| ≥ [edgeDeg])
 * samples → median of the last [window] good samples (kills single spikes) → exponential smoothing
 * ([alpha]) so the arrow glides. If no good sample arrived for [holdMs], returns null: the radar shows
 * distance only rather than a stale direction.
 *
 * Azimuth stays in −90..+90 (it never wraps), so plain linear averaging is correct here.
 */
class UwbAzimuthFilter(
    private val window: Int = 5,
    private val alpha: Double = 0.35,
    private val edgeDeg: Double = 85.0,
    private val holdMs: Long = 1_500L,
) {
    private val recent = ArrayDeque<Double>()
    private var smoothed: Double? = null
    private var lastGoodMs = 0L

    /** Feed one UWB reading; returns the azimuth to display (degrees, + = right) or null for "no arrow". */
    fun update(azimuthDeg: Double?, distanceM: Double, atMs: Long): Double? {
        val good = azimuthDeg != null && !azimuthDeg.isNaN() &&
            distanceM >= RadarMath.UWB_MIN_AOA_DISTANCE_M && kotlin.math.abs(azimuthDeg) < edgeDeg
        if (good) {
            if (lastGoodMs != 0L && atMs - lastGoodMs > holdMs) reset() // gap: don't blend with an old direction
            recent.addLast(azimuthDeg!!)
            while (recent.size > window) recent.removeFirst()
            val median = recent.sorted()[recent.size / 2]
            val s = smoothed
            smoothed = if (s == null) median else s + alpha * (median - s)
            lastGoodMs = atMs
        }
        return smoothed?.takeIf { atMs - lastGoodMs <= holdMs }
    }

    fun reset() {
        recent.clear()
        smoothed = null
        lastGoodMs = 0L
    }
}
