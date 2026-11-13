package mu.proxima.locate

import kotlinx.serialization.Serializable
import mu.proxima.core.pipeline.ProximaJson

/**
 * Payload of a mesh CONTROL packet on channel "uwb". Pure Kotlin so it is unit-testable.
 *
 * Handshake (tracker = controller, target = controlee):
 *   tracker → REQUEST {sessionId, targetNodeId, addr=controller addr, channel, preamble, stsKey}
 *   target  → ACCEPT  {sessionId, targetNodeId=tracker, addr=controlee addr}
 *   tracker → STOP    {sessionId, targetNodeId} when the radar closes
 */
@Serializable
data class UwbControl(
    val type: Type,
    val sessionId: Int,
    /** Node that must act on this message. */
    val targetNodeId: String,
    /** Sender's UWB short address, hex. */
    val addr: String = "",
    val channel: Int? = null,
    val preamble: Int? = null,
    /** 8-byte static-STS key, hex. Random per session. */
    val stsKey: String? = null,
) {
    enum class Type { REQUEST, ACCEPT, STOP }

    fun encode(): String = ProximaJson.encodeToString(serializer(), this)

    companion object {
        const val CHANNEL = "uwb"
        fun decode(payload: String): UwbControl? =
            runCatching { ProximaJson.decodeFromString(serializer(), payload) }.getOrNull()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
