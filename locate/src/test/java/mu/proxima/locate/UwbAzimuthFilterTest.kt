package mu.proxima.locate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UwbAzimuthFilterTest {

    /**
     * Real S23+ readings (azimuth°, distance m), 13 Nov 18:14:48–18:15:06, ~3.3 Hz: target held ~45° right,
     * then moved ~45° left. Contains a single −24° dip, two exact −90° edge values and a 48→−11 move.
     */
    private val recorded = listOf(
        33 to 1.66, 33 to 1.65, 29 to 1.66, 32 to 1.65, 32 to 1.65, 34 to 1.65, 39 to 1.63, 40 to 1.62, 40 to 1.62,
        46 to 1.60, 41 to 1.55, 41 to 1.64, 24 to 1.74, 45 to 1.71, -90 to 1.66, -90 to 1.66, 48 to 1.63, 50 to 1.59,
        -11 to 1.67, -7 to 1.67, -1 to 1.64, 4 to 1.62, 0 to 1.64, -3 to 1.72, -11 to 1.73, -8 to 1.78, -7 to 1.79,
        -7 to 1.81, -9 to 1.82, -13 to 1.75, -13 to 1.75, -15 to 1.74, -14 to 1.77, -14 to 1.77, -16 to 1.76,
        -14 to 1.77, -19 to 1.77, -18 to 1.76, -19 to 1.74, -20 to 1.74, -16 to 1.75, -15 to 1.74, -13 to 1.77,
        -18 to 1.73, -18 to 1.74, -23 to 1.73, -19 to 1.74, -22 to 1.72, -19 to 1.74, -19 to 1.75, -16 to 1.74,
        -15 to 1.74, -16 to 1.74, -17 to 1.75, -19 to 1.71, -19 to 1.69, -17 to 1.72, -21 to 1.73, -21 to 1.73,
    )

    private fun replay(f: UwbAzimuthFilter = UwbAzimuthFilter()) =
        recorded.mapIndexed { i, (az, d) -> f.update(az.toDouble(), d, 1_000L + i * 300L) }

    @Test
    fun recordedRun_edgeValuesNeverSwingTheArrowLeft() {
        val out = replay()
        // While the target is on the right (first 18 samples, incl. the two −90° edges) the arrow stays right.
        out.take(18).forEach { assertTrue("arrow swung left: $it", it!! > 25.0) }
    }

    @Test
    fun recordedRun_settlesOnTheLeftAndMovesSmoothly() {
        val out = replay().map { it!! }
        assertEquals(-18.0, out.last(), 4.0)
        val maxStep = out.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("arrow jumped $maxStep° in one reading", maxStep < 25.0) // raw data jumps 138°
    }

    @Test
    fun singleSpikeIsRejected() {
        val f = UwbAzimuthFilter()
        var t = 0L
        repeat(6) { f.update(10.0, 2.0, t); t += 300 }
        val afterSpike = f.update(50.0, 2.0, t)!!
        assertEquals(10.0, afterSpike, 0.5)
    }

    @Test
    fun contactRangeAndEdgeOnlyGiveNoArrow() {
        val f = UwbAzimuthFilter()
        assertNull(f.update(30.0, 0.2, 0))      // < 0.5 m: angle is noise
        assertNull(f.update(30.0, 0.4, 100))    // measured 35–89° jumps at 0.30–0.34 m
        assertNull(f.update(-90.0, 2.0, 300))  // field-of-view edge
        assertNull(f.update(null, 2.0, 600))   // no AoA (e.g. S24 Ultra)
    }

    @Test
    fun staleDirectionIsDroppedAndNotBlendedAfterAGap() {
        val f = UwbAzimuthFilter()
        repeat(5) { f.update(40.0, 2.0, it * 300L) }
        assertNotNull(f.update(null, 2.0, 2_000))  // 0.8 s since last good: still shown
        assertNull(f.update(null, 2.0, 3_000))     // 1.8 s: hidden
        assertEquals(-30.0, f.update(-30.0, 2.0, 3_300)!!, 1e-9) // fresh start, no drag from the old 40°
    }
}
