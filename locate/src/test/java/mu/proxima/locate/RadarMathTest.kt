package mu.proxima.locate

import mu.proxima.core.model.Proximity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RadarMathTest {

    /** Rotation matrix (device → world) for a phone with yaw [yawDeg] (clockwise from north) and pitch [pitchDeg] (0 = flat, 90 = upright). */
    private fun pose(yawDeg: Double, pitchDeg: Double): FloatArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        // Device axes expressed in world coords (X=east, Y=north, Z=up).
        val fwd = doubleArrayOf(sin(y), cos(y), 0.0)
        val right = doubleArrayOf(cos(y), -sin(y), 0.0)
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        // Tilting the top edge up by p: deviceY = fwd*cos + up*sin ; deviceZ = -fwd*sin + up*cos
        val dx = right
        val dy = DoubleArray(3) { fwd[it] * cos(p) + up[it] * sin(p) }
        val dz = DoubleArray(3) { -fwd[it] * sin(p) + up[it] * cos(p) }
        // Row-major R where column j = device axis j in world coords.
        return FloatArray(9) { i -> val row = i / 3; when (i % 3) { 0 -> dx[row]; 1 -> dy[row]; else -> dz[row] }.toFloat() }
    }

    @Test fun headingFlatPhone() {
        assertEquals(0.0, RadarMath.headingFromRotationMatrix(pose(0.0, 0.0))!!, 0.5)
        assertEquals(90.0, RadarMath.headingFromRotationMatrix(pose(90.0, 0.0))!!, 0.5)
        assertEquals(225.0, RadarMath.headingFromRotationMatrix(pose(225.0, 0.0))!!, 0.5)
    }

    @Test fun headingUprightAndTiltedAgree() {
        for (pitch in listOf(0.0, 30.0, 45.0, 70.0, 90.0)) {
            assertEquals("pitch $pitch", 135.0, RadarMath.headingFromRotationMatrix(pose(135.0, pitch))!!, 0.5)
        }
    }

    @Test fun headingNullWhenDegenerate() {
        assertNull(RadarMath.headingFromRotationMatrix(FloatArray(9)))
    }

    @Test fun smootherWrapsAroundNorth() {
        val s = HeadingSmoother(alpha = 0.5)
        s.update(350.0)
        val v = s.update(10.0)
        val distFromNorth = minOf(v, 360 - v)
        assert(distFromNorth < 10.0) { "expected near north, got $v" }
    }

    @Test fun proximityRings() {
        assertEquals(Proximity.HERE, RadarMath.proximityForDistance(1.0))
        assertEquals(Proximity.CLOSE, RadarMath.proximityForDistance(10.0))
        assertEquals(Proximity.NEAR, RadarMath.proximityForDistance(40.0))
        assertEquals(Proximity.FAR, RadarMath.proximityForDistance(200.0))
        assertEquals(Proximity.HERE, RadarMath.proximityForRssi(-50))
        assertEquals(Proximity.FAR, RadarMath.proximityForRssi(-95))
    }

    @Test fun combinedAccuracy() {
        assertEquals(5.0, RadarMath.combinedAccuracyM(3f, 4f)!!, 1e-9)
        assertNull(RadarMath.combinedAccuracyM(null, null))
    }
}
