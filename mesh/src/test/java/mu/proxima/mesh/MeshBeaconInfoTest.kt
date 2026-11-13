package mu.proxima.mesh

import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshBeaconInfoTest {

    /** Ganga Talao (Grand Bassin), plus a sweep of points around it every ~3 m. */
    private val venue = GeoPoint(-20.4180, 57.4925)

    @Test fun coarseStaysInTheSameHeatmapCell() {
        for (east in -60..60 step 3) for (north in -60..60 step 3) {
            val p = GeoMath.offset(venue, east.toDouble(), north.toDouble())
            val c = MeshBeaconInfo.coarse(p)
            assertEquals("cell changed at e=$east n=$north", GeoMath.cellKey(p), GeoMath.cellKey(c))
        }
    }

    @Test fun coarseHidesTheExactPosition() {
        val a = GeoMath.offset(venue, 1.0, 1.0)
        val b = GeoMath.offset(venue, 6.0, -4.0) // a few metres away, very likely same cell
        if (GeoMath.cellKey(a) == GeoMath.cellKey(b)) {
            assertEquals(MeshBeaconInfo.coarse(a), MeshBeaconInfo.coarse(b)) // indistinguishable on the wire
        }
        val p = GeoPoint(venue.lat + 0.00001, venue.lon + 0.00001, accuracyM = 3f)
        val c = MeshBeaconInfo.coarse(p)
        assertTrue(GeoMath.distanceM(p, c) <= MeshBeaconInfo.CELL_M) // within one cell
        assertEquals(MeshBeaconInfo.CELL_M.toFloat(), c.accuracyM) // real GPS accuracy is not leaked
    }
}
