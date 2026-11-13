package mu.proxima.trust

import java.io.File
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL committed OSM extract, not a fixture: the dataset is a deliverable, so a
 * change to it should break these tests and be looked at.
 *
 * Landmarks are asserted by OSM id rather than by name - ids are stable, names get re-tagged and
 * carry accents that a bad source encoding would silently mangle.
 */
class OsmHelpPointsTest {

    /** Port Louis city centre (Place d'Armes). */
    private val portLouis = GeoPoint(-20.1619, 57.4989)

    /** Ganga Talao / Grand Bassin - the Maha Shivaratri site the product is designed for. */
    private val gangaTalao = GeoPoint(-20.4181, 57.4914)

    private val raw: String by lazy {
        val f = File("src/main/assets/data/osm_help_points_raw.json")
        assertTrue("dataset missing at ${f.absolutePath} - run scripts/fetch_help_points.sh", f.isFile)
        f.readText()
    }
    private val points: List<HelpPoint> by lazy { OsmHelpPoints.parse(raw) }

    @Test
    fun `parses every element of the committed extract`() {
        // The fetch returned 524 elements and all of them carry a category tag and coordinates.
        assertEquals(524, points.size)
        assertTrue("some points lost their coordinates", points.all { it.location.lat != 0.0 })
        assertTrue("every point must be inside the Mauritius bbox", points.all {
            it.location.lat in -20.56..-19.95 && it.location.lon in 57.28..57.83
        })
    }

    @Test
    fun `every point is attributed and labelled`() {
        assertTrue(points.all { it.source == "OpenStreetMap (ODbL)" })
        // ~180 features are unnamed in OSM; they must still get a human label, never a blank.
        assertTrue("unnamed features must fall back to a category label", points.none { it.name.isBlank() })
        assertTrue(points.all { it.id.startsWith("osm:") })
        assertEquals(points.size, points.map { it.id }.distinct().size)
    }

    @Test
    fun `records the OSM base timestamp for the README`() {
        assertEquals("2026-11-13T10:42:12Z", OsmHelpPoints.osmBaseTimestamp(raw))
    }

    @Test
    fun `nearest police to Port Louis is the Prefecture, about 300 m away`() {
        val hits = OsmHelpPoints.rankNearest(points, portLouis, setOf(HelpCategory.POLICE), limit = 3)

        assertEquals(3, hits.size)
        assertEquals("osm:node/317032737", hits.first().point.id)
        assertTrue("expected ~303 m, got ${hits.first().distanceM}", hits.first().distanceM in 250.0..400.0)
        assertFalse(hits.first().point.name.isBlank())
        // North-ish from Place d'Armes, and always a usable compass bearing.
        assertTrue(hits.all { it.bearingDeg in 0.0..360.0 })
        assertEquals(hits.map { it.distanceM }.sorted(), hits.map { it.distanceM })
        assertTrue(hits.all { it.point.category == HelpCategory.POLICE })
    }

    @Test
    fun `a medical incident in Port Louis routes to a real hospital within a kilometre`() {
        val hits = OsmHelpPoints.rankNearest(
            points, portLouis, IncidentType.MEDICAL.helpCategories, limit = 3,
        )

        assertEquals(3, hits.size)
        // 452 m, 504 m, 855 m. The 4th is already past a kilometre, so 3 is the honest claim here.
        assertTrue("got ${hits.map { it.distanceM }}", hits.all { it.distanceM < 1_000.0 })
        // Dr Abdool Gaffoor Jeetoo Hospital is the nearest actual hospital to the city centre.
        assertNotNull(hits.firstOrNull { it.point.id == "osm:way/372014045" })
    }

    /**
     * Documents a real, uncomfortable property of the demo venue: at Ganga Talao the nearest medical
     * facility is ~8.5 km away. The guided-response UI therefore has to render kilometre distances,
     * not just "120 m". If a future dataset makes this pass trivially, re-check the venue story.
     */
    @Test
    fun `Ganga Talao has no help point nearby`() {
        val medical = OsmHelpPoints.rankNearest(
            points, gangaTalao, IncidentType.MEDICAL.helpCategories, limit = 1,
        )

        assertTrue(medical.isNotEmpty())
        assertTrue(
            "nearest medical help at Ganga Talao was ${medical.first().distanceM} m",
            medical.first().distanceM > 8_000.0,
        )
    }

    /** OSM has no assembly points in Mauritius, so a hazard must not be routed to one. */
    @Test
    fun `categories absent from OSM return nothing rather than a wrong answer`() {
        val absent = setOf(
            HelpCategory.ASSEMBLY_POINT,
            HelpCategory.MEETING_POINT,
            HelpCategory.FIRST_AID_POST,
            HelpCategory.AMBULANCE,
            HelpCategory.DEFIBRILLATOR,
        )
        assertTrue(OsmHelpPoints.rankNearest(points, gangaTalao, absent, limit = 5).isEmpty())

        // ...but a hazard still gets fire stations and police, so the feature degrades instead of failing.
        val hazard = OsmHelpPoints.rankNearest(points, portLouis, IncidentType.HAZARD.helpCategories, limit = 3)
        assertTrue(hazard.isNotEmpty())
    }

    @Test
    fun `empty categories means any help, and limits are respected`() {
        assertEquals(7, OsmHelpPoints.rankNearest(points, portLouis, emptySet(), limit = 7).size)
        assertTrue(OsmHelpPoints.rankNearest(points, portLouis, emptySet(), limit = 0).isEmpty())
        assertTrue(OsmHelpPoints.rankNearest(points, portLouis, emptySet(), limit = -1).isEmpty())
    }
}
