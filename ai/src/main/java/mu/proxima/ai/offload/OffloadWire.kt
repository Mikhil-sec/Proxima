package mu.proxima.ai.offload

import kotlinx.serialization.Serializable
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.TriageResult

/**
 * AI-lane-private payloads carried by the mesh (lane file: TRIAGE_REQUEST/RESULT shapes are ours).
 * Everything goes through PacketFactory (signed) and ProximaJson.
 */
object OffloadWire {
    /** CONTROL sub-channel for LLM-readiness announcements (MESH's Beacon.llmReady is not filled yet). */
    const val CHANNEL = "ai"
    /** Requests/results travel at most 2 hops (lane spec). */
    const val TTL = 2
    /** Store-and-forward can replay old packets to new neighbours: ignore anything older than this. */
    const val MAX_REQUEST_AGE_MS = 15_000L
    /** A helper that hasn't announced for this long is no longer considered available. */
    const val HELPER_EXPIRY_MS = 45_000L
    const val ANNOUNCE_EVERY_MS = 20_000L
}

/**
 * TRIAGE_REQUEST payload. Only what the model needs: the words and the pressed button.
 * No location, no name: the request is readable by every relay.
 */
@Serializable
data class OffloadRequest(
    val text: String,
    val typeHint: IncidentType? = null,
)

/** TRIAGE_RESULT payload. [requestId] = packetId of the TRIAGE_REQUEST it answers. */
@Serializable
data class OffloadResult(
    val requestId: String,
    val result: TriageResult,
)

/** CONTROL/"ai" payload: "my on-device LLM is ready to triage for you". */
@Serializable
data class LlmAnnouncement(
    val nodeId: String,
    val ready: Boolean,
)
