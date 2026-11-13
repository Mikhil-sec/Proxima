package mu.proxima.trust

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.RankedHelpPoint
import mu.proxima.core.pipeline.ProximaJson

/**
 * Maps the raw Overpass response committed at `assets/data/osm_help_points_raw.json` to [HelpPoint]s.
 *
 * The repo keeps the *raw* OSM response and maps it here, rather than shipping a pre-baked list, so the
 * ODbL source and the fetch timestamp stay verifiable against OpenStreetMap and so the tag mapping is
 * reviewable. Deliberately free of Android imports: this object is covered by JVM unit tests.
 */
object OsmHelpPoints {

    /** Attribution required by ODbL. Goes on every [HelpPoint.source]. */
    const val SOURCE = "OpenStreetMap (ODbL)"

    /** `amenity=*` wins over the other tags: it is the most consistently used in the Mauritius extract. */
    private val BY_AMENITY = mapOf(
        "hospital" to HelpCategory.HOSPITAL,
        "clinic" to HelpCategory.HEALTH_CENTRE,
        "doctors" to HelpCategory.HEALTH_CENTRE,
        "pharmacy" to HelpCategory.PHARMACY,
        "police" to HelpCategory.POLICE,
        "fire_station" to HelpCategory.FIRE_STATION,
        "drinking_water" to HelpCategory.WATER,
        "toilets" to HelpCategory.TOILETS,
    )

    /**
     * Queried, but the Mauritius extract currently contains none of these (only `emergency=yes`).
     * Kept so a later re-fetch, or a venue overlay, picks them up without a code change.
     */
    private val BY_EMERGENCY = mapOf(
        "ambulance_station" to HelpCategory.AMBULANCE,
        "assembly_point" to HelpCategory.ASSEMBLY_POINT,
        "defibrillator" to HelpCategory.DEFIBRILLATOR,
    )

    private val BY_HEALTHCARE = mapOf(
        "hospital" to HelpCategory.HOSPITAL,
        "clinic" to HelpCategory.HEALTH_CENTRE,
        "centre" to HelpCategory.HEALTH_CENTRE,
        "doctor" to HelpCategory.HEALTH_CENTRE,
        "pharmacy" to HelpCategory.PHARMACY,
    )

    /** Shown instead of a name for the ~180 features OSM has not named. Never left blank in the UI. */
    private val FALLBACK_LABEL = mapOf(
        HelpCategory.HOSPITAL to "Hospital",
        HelpCategory.HEALTH_CENTRE to "Health centre",
        HelpCategory.FIRST_AID_POST to "First aid post",
        HelpCategory.PHARMACY to "Pharmacy",
        HelpCategory.POLICE to "Police station",
        HelpCategory.FIRE_STATION to "Fire station",
        HelpCategory.AMBULANCE to "Ambulance station",
        HelpCategory.ASSEMBLY_POINT to "Assembly point",
        HelpCategory.MEETING_POINT to "Meeting point",
        HelpCategory.DEFIBRILLATOR to "Defibrillator",
        HelpCategory.WATER to "Drinking water",
        HelpCategory.TOILETS to "Toilets",
    )

    /** Parses a raw Overpass JSON document. Elements with no category or no coordinates are dropped. */
    fun parse(json: String): List<HelpPoint> =
        ProximaJson.decodeFromString(OverpassResponse.serializer(), json)
            .elements
            .mapNotNull(::toHelpPoint)

    /**
     * Nearest [limit] points in [categories], closest first. Pure so it is unit-tested directly;
     * [AssetHelpPointRepository.nearest] is a thin wrapper over this.
     *
     * An empty [categories] means "any kind of help".
     */
    fun rankNearest(
        points: List<HelpPoint>,
        from: GeoPoint,
        categories: Set<HelpCategory>,
        limit: Int,
    ): List<RankedHelpPoint> {
        if (limit <= 0) return emptyList()
        return points.asSequence()
            .filter { categories.isEmpty() || it.category in categories }
            .map {
                RankedHelpPoint(
                    point = it,
                    distanceM = GeoMath.distanceM(from, it.location),
                    bearingDeg = GeoMath.bearingDeg(from, it.location),
                )
            }
            .sortedBy { it.distanceM }
            .take(limit)
            .toList()
    }

    /** `timestamp_osm_base` from the response - the cut-off date of the OSM data, for the README. */
    fun osmBaseTimestamp(json: String): String? =
        ProximaJson.decodeFromString(OverpassResponse.serializer(), json).osm3s?.timestampOsmBase

    private fun toHelpPoint(e: OverpassElement): HelpPoint? {
        val category = categoryOf(e.tags) ?: return null
        val lat = e.lat ?: e.center?.lat ?: return null
        val lon = e.lon ?: e.center?.lon ?: return null
        return HelpPoint(
            id = "osm:${e.type}/${e.id}",
            name = nameOf(e.tags, category),
            category = category,
            location = GeoPoint(lat, lon),
            source = SOURCE,
            phone = e.tags["phone"] ?: e.tags["contact:phone"],
            openingHours = e.tags["opening_hours"],
        )
    }

    private fun categoryOf(tags: Map<String, String>): HelpCategory? =
        BY_AMENITY[tags["amenity"]]
            ?: BY_EMERGENCY[tags["emergency"]]
            ?: BY_HEALTHCARE[tags["healthcare"]]

    private fun nameOf(tags: Map<String, String>, category: HelpCategory): String =
        tags["name"]?.takeIf { it.isNotBlank() }
            ?: tags["name:en"]?.takeIf { it.isNotBlank() }
            ?: tags["official_name"]?.takeIf { it.isNotBlank() }
            ?: FALLBACK_LABEL.getValue(category)

    // ---- Overpass wire shapes (only the fields we use; ProximaJson ignores the rest) ----

    @Serializable
    private data class OverpassResponse(
        val elements: List<OverpassElement> = emptyList(),
        val osm3s: Osm3s? = null,
    )

    @Serializable
    private data class Osm3s(
        @SerialName("timestamp_osm_base") val timestampOsmBase: String? = null,
    )

    /** `node` carries `lat`/`lon`; `way` and `relation` carry `center` (we asked for `out center`). */
    @Serializable
    private data class OverpassElement(
        val type: String,
        val id: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Center(val lat: Double, val lon: Double)
}
