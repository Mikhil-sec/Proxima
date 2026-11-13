package mu.proxima.trust

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.proxima.core.engine.HelpPointRepository
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.HelpPoint
import mu.proxima.core.model.RankedHelpPoint

/**
 * Real Mauritius help points, read from the OSM extract committed in this module's assets.
 *
 * Loads once, lazily, off the main thread and caches. ~524 features, so the whole set stays in memory
 * and [nearest] is a linear scan - fine at this size and it keeps the behaviour obvious.
 */
class AssetHelpPointRepository(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) : HelpPointRepository {

    private val mutex = Mutex()
    @Volatile private var cache: List<HelpPoint>? = null

    override suspend fun all(): List<HelpPoint> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val raw = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
                OsmHelpPoints.parse(raw)
            }.also { cache = it }
        }
    }

    /**
     * Nearest [limit] points in [categories], closest first.
     *
     * An empty [categories] means "any kind of help". Callers normally pass
     * `IncidentType.helpCategories`; note that some of those categories (MEETING_POINT,
     * ASSEMBLY_POINT, FIRST_AID_POST, AMBULANCE) have no features in the OSM extract, so a
     * caller asking only for those gets an empty list rather than a wrong answer.
     */
    override suspend fun nearest(
        from: GeoPoint,
        categories: Set<HelpCategory>,
        limit: Int,
    ): List<RankedHelpPoint> = OsmHelpPoints.rankNearest(all(), from, categories, limit)

    companion object {
        const val DEFAULT_ASSET = "data/osm_help_points_raw.json"
    }
}
