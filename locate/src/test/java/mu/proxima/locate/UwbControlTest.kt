package mu.proxima.locate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UwbControlTest {

    @Test fun requestRoundTrips() {
        val req = UwbControl(
            UwbControl.Type.REQUEST, sessionId = 445491980, targetNodeId = "node-b",
            addr = "F2FB", channel = 9, preamble = 9, stsKey = "0807010203040506",
        )
        assertEquals(req, UwbControl.decode(req.encode()))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(UwbControl.decode("not json"))
        assertNull(UwbControl.decode("""{"type":"NOPE","sessionId":1,"targetNodeId":"x"}"""))
    }

    @Test fun hexRoundTrips() {
        val bytes = byteArrayOf(0x4F, 0xDC.toByte(), 0x00, 0x7F)
        assertEquals("4FDC007F", bytes.toHex())
        assertArrayEquals(bytes, "4FDC007F".hexToBytes())
    }

    @Test fun uwbAzimuthMapsToRelativeBearing() {
        assertEquals(0.0, RadarMath.uwbRelativeBearingDeg(0.0, 2.0)!!, 1e-9)
        assertEquals(45.0, RadarMath.uwbRelativeBearingDeg(45.0, 2.0)!!, 1e-9)
        assertEquals(315.0, RadarMath.uwbRelativeBearingDeg(-45.0, 2.0)!!, 1e-9)
        assertEquals(315.0, RadarMath.uwbRelativeBearingDeg(45.0, 2.0, sign = -1)!!, 1e-9)
    }

    @Test fun uwbAzimuthIgnoredWhenTooCloseOrMissing() {
        assertNull(RadarMath.uwbRelativeBearingDeg(90.0, 0.05)) // contact range: angle is noise
        assertNull(RadarMath.uwbRelativeBearingDeg(null, 3.0))  // phone without AoA (S24U)
    }
}
