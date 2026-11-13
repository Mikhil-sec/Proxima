package mu.proxima.locate

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LocateTargetsTest {
    @Test
    fun helpPointBecomesAStaticRadarTarget() = runBlocking {
        val p = HelpPoint("osm-node-1", "SAMU post", HelpCategory.FIRST_AID_POST, GeoPoint(-20.4166, 57.4870), "OpenStreetMap (ODbL)")
        val t = LocateTargets.helpPoint(p)
        assertEquals("help:osm-node-1", t.nodeId)
        assertEquals("SAMU post", t.label)
        assertEquals(listOf(p.location), t.locations.toList())
    }
}
