package mu.proxima.trust

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.pipeline.ProximaJson

/**
 * Selection logic for the vetted protocol cards, kept Android-free so it is unit-tested directly.
 *
 * The cards themselves live in `assets/data/protocols.json`, transcribed from the IFRC 2020 first aid
 * guidelines with a page citation on each one. Nothing here writes or edits a step: the AI picks a card
 * by `(type, subtype)` and the card is read out exactly as transcribed.
 */
object Protocols {

    fun parse(json: String): List<ProtocolCard> =
        ProximaJson.decodeFromString(ProtocolFile.serializer(), json).cards

    /**
     * Most specific card wins: an exact [subtype] match first, then the type's generic card
     * (one with no subtypes), then null.
     *
     * Returning null is deliberate and must stay possible. Several subtypes the triage engine can emit
     * have no card yet because the IFRC source has no protocol for them - CROWD_CRUSH, ELECTRICAL,
     * FALLEN_BRANCH, FLOOD - and showing an unrelated card would be worse than showing none.
     */
    fun bestFor(cards: List<ProtocolCard>, type: IncidentType, subtype: String?): ProtocolCard? {
        if (subtype != null) {
            cards.firstOrNull { type in it.appliesTo && subtype in it.subtypes }?.let { return it }
        }
        return cards.firstOrNull { type in it.appliesTo && it.subtypes.isEmpty() }
    }

    /** Only `cards` is read; the `_source` / `_rules` provenance keys are ignored by ProximaJson. */
    @Serializable
    private data class ProtocolFile(val cards: List<ProtocolCard> = emptyList())
}

/** Vetted response protocols, read from this module's assets. Loaded once, lazily, off the main thread. */
class AssetProtocolRepository(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) : ProtocolRepository {

    private val mutex = Mutex()
    @Volatile private var cache: List<ProtocolCard>? = null

    override suspend fun all(): List<ProtocolCard> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val raw = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
                Protocols.parse(raw)
            }.also { cache = it }
        }
    }

    override suspend fun byId(id: String): ProtocolCard? = all().firstOrNull { it.id == id }

    override suspend fun bestFor(type: IncidentType, subtype: String?): ProtocolCard? =
        Protocols.bestFor(all(), type, subtype)

    companion object {
        const val DEFAULT_ASSET = "data/protocols.json"
    }
}
