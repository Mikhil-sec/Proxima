package mu.proxima.core.pipeline

import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import kotlin.math.abs

/**
 * Rule-based de-duplication: 15 people reporting the same fire become one incident with reportCount = 15.
 * Deliberately simple and explainable ("a simple rule that works beats a model that doesn't").
 */
class IncidentMerger(
    private val radiusM: Double = 75.0,
    private val windowMs: Long = 10 * 60_000L,
) {
    private val mergeable = setOf(IncidentType.MEDICAL, IncidentType.HAZARD)

    /** Returns the updated list. [incoming] may be new, an update of a known id, or a duplicate report. */
    fun apply(
        current: List<IncidentRecord>,
        incoming: Incident,
        signatureValid: Boolean,
        hops: Int,
        nowMs: Long,
        originKey: String? = null,
    ): List<IncidentRecord> {
        // Closing events
        if (incoming.type == IncidentType.ALL_CLEAR || incoming.type == IncidentType.FOUND) {
            val target = incoming.relatedIncidentId
            val closed = current.map {
                if (it.incident.id == target) it.copy(resolved = true, lastUpdatedMs = nowMs) else it
            }
            return if (incoming.type == IncidentType.FOUND) {
                listOf(IncidentRecord(incoming, signatureValid, hops, nowMs, originKey = originKey)) + closed
            } else closed
        }

        // Same id = re-broadcast / update
        val sameId = current.indexOfFirst { it.incident.id == incoming.id }
        if (sameId >= 0) {
            return current.toMutableList().also {
                it[sameId] = it[sameId].copy(incident = incoming, lastUpdatedMs = nowMs)
            }
        }

        // Duplicate report of the same real-world event
        if (incoming.type in mergeable) {
            val dup = current.indexOfFirst { isSameEvent(it.incident, incoming) && !it.resolved }
            if (dup >= 0) {
                val existing = current[dup].incident
                val merged = existing.copy(
                    severity = maxOf(existing.severity, incoming.severity),
                    reportCount = existing.reportCount + incoming.reportCount,
                    summary = if (incoming.confidence > existing.confidence) incoming.summary else existing.summary,
                    subtype = existing.subtype ?: incoming.subtype,
                    landmark = existing.landmark ?: incoming.landmark,
                    location = existing.location ?: incoming.location,
                    protocolId = existing.protocolId ?: incoming.protocolId,
                )
                return current.toMutableList().also {
                    it[dup] = it[dup].copy(incident = merged, lastUpdatedMs = nowMs)
                }
            }
        }

        return listOf(IncidentRecord(incoming, signatureValid, hops, nowMs, originKey = originKey)) + current
    }

    private fun isSameEvent(a: Incident, b: Incident): Boolean {
        if (a.type != b.type) return false
        if (abs(a.createdAtMs - b.createdAtMs) > windowMs) return false
        if (a.subtype != null && b.subtype != null && a.subtype != b.subtype) return false
        val la = a.location
        val lb = b.location
        return if (la != null && lb != null) {
            GeoMath.distanceM(la, lb) <= radiusM
        } else {
            a.landmark != null && a.landmark.equals(b.landmark, ignoreCase = true)
        }
    }
}
