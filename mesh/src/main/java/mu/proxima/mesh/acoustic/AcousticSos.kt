package mu.proxima.mesh.acoustic

import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageSource
import kotlin.math.roundToInt

/**
 * The tiny SOS that fits through the acoustic modem: 16 bytes (≈ 3.7 s on air at 50 bit/s).
 *
 * | bytes | field |
 * |---|---|
 * | 0 | version (high 4 bits) · IncidentType ordinal (low 4 bits) |
 * | 1 | Severity ordinal |
 * | 2–5 | origin node id (the 8-hex id as 4 bytes) |
 * | 6–9, 10–13 | lat, lon × 1e4 as Int32 (~11 m, same precision class as the beacon cell); Int.MIN_VALUE = no fix |
 * | 14–15 | nonce, so repeats of one SOS dedup and two SOSes from one phone don't |
 *
 * No signature (64 B would take +10 s on air), so a receiver must show it as **unverified** and hand it to the
 * normal pipeline, which re-broadcasts it over RF as soon as any link exists. No names, no free text (privacy).
 */
data class AcousticSos(
    val type: IncidentType,
    val severity: Severity,
    /** 8 lowercase hex chars (TRUST `NodeIds`). Other ids are hashed to 4 bytes, see [nodeIdBytes]. */
    val originNodeId: String,
    val location: GeoPoint?,
    val nonce: Int,
) {
    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = ((VERSION shl 4) or type.ordinal).toByte()
        out[1] = severity.ordinal.toByte()
        putInt(out, 2, nodeIdBytes(originNodeId))
        putInt(out, 6, location?.let { (it.lat * SCALE).roundToInt() } ?: NO_FIX)
        putInt(out, 10, location?.let { (it.lon * SCALE).roundToInt() } ?: NO_FIX)
        out[14] = (nonce shr 8).toByte()
        out[15] = nonce.toByte()
        return out
    }

    /** As a normal pipeline incident. Id is stable per (origin, nonce) so the merger dedups repeats. */
    fun toIncident(nowMs: Long): Incident = Incident(
        id = "ac-$originNodeId-${"%04x".format(nonce and 0xFFFF)}",
        type = type,
        severity = severity,
        summary = "${type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} SOS heard by sound (unverified)",
        location = location?.copy(accuracyM = ACCURACY_M),
        createdAtMs = nowMs,
        reporterNodeId = originNodeId,
        triagedBy = TriageSource.MANUAL,
    )

    companion object {
        const val SIZE = 16
        const val VERSION = 1
        private const val SCALE = 1e4
        private const val NO_FIX = Int.MIN_VALUE
        private const val ACCURACY_M = 15f
        private val HEX8 = Regex("^[0-9a-f]{8}$")

        fun decode(bytes: ByteArray): AcousticSos? {
            if (bytes.size != SIZE) return null
            val b0 = bytes[0].toInt() and 0xFF
            if (b0 shr 4 != VERSION) return null
            val type = IncidentType.entries.getOrNull(b0 and 0x0F) ?: return null
            val severity = Severity.entries.getOrNull(bytes[1].toInt() and 0xFF) ?: return null
            val lat = getInt(bytes, 6)
            val lon = getInt(bytes, 10)
            val loc = if (lat == NO_FIX || lon == NO_FIX) null else GeoPoint(lat / SCALE, lon / SCALE)
            if (loc != null && (loc.lat !in -90.0..90.0 || loc.lon !in -180.0..180.0)) return null
            return AcousticSos(
                type = type,
                severity = severity,
                originNodeId = "%08x".format(getInt(bytes, 2)),
                location = loc,
                nonce = ((bytes[14].toInt() and 0xFF) shl 8) or (bytes[15].toInt() and 0xFF),
            )
        }

        /** 8-hex ids round-trip exactly; anything else (e.g. `SELF-ab12` before TRUST #19) is hashed. */
        fun nodeIdBytes(nodeId: String): Int =
            if (HEX8.matches(nodeId)) nodeId.toLong(16).toInt() else nodeId.hashCode()

        private fun putInt(b: ByteArray, at: Int, v: Int) {
            for (k in 0 until 4) b[at + k] = (v shr (24 - 8 * k)).toByte()
        }

        private fun getInt(b: ByteArray, at: Int): Int {
            var v = 0
            for (k in 0 until 4) v = (v shl 8) or (b[at + k].toInt() and 0xFF)
            return v
        }
    }
}
