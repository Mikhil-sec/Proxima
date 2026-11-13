package mu.proxima.locate

import mu.proxima.core.model.FixSource
import mu.proxima.core.model.Proximity
import mu.proxima.core.model.RadarFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LocateDebugTriggerTest {

    @Test
    fun formatsFullUwbFix() {
        Locale.setDefault(Locale.FRANCE) // comma-decimal locale must not leak into the log
        val fix = RadarFix("SELF-AB12", 2.134, 45.0, -15.4, FixSource.UWB, Proximity.CLOSE, 0.3, 0L)
        assertEquals(
            "locate fix #7 src=UWB d=2.13 rel=-15 brg=45 prox=CLOSE acc=0.3",
            LocateDebugTrigger.format(fix, 7),
        )
    }

    @Test
    fun formatsRssiFixWithDashes() {
        Locale.setDefault(Locale.FRANCE) // comma-decimal locale must not leak into the log
        val fix = RadarFix("SELF-AB12", null, null, null, FixSource.RSSI, Proximity.NEAR, null, 0L)
        assertEquals("locate fix #1 src=RSSI d=- rel=- brg=- prox=NEAR acc=-", LocateDebugTrigger.format(fix, 1))
    }

    @Test
    fun parsesTargetPointAndRejectsGarbage() {
        val p = LocateDebugTrigger.parsePoint(" -20.4166 ", "57.4870")!!
        assertEquals(-20.4166, p.lat, 1e-9)
        assertEquals(57.4870, p.lon, 1e-9)
        assertNull(LocateDebugTrigger.parsePoint("abc", "57.4"))
        assertNull(LocateDebugTrigger.parsePoint("-20.4", null))
        assertNull(LocateDebugTrigger.parsePoint("95", "57.4"))
    }
}
