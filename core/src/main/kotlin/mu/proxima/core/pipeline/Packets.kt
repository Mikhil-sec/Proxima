package mu.proxima.core.pipeline

import kotlinx.serialization.json.Json
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import java.util.UUID

/** The one Json config for everything on the wire. */
val ProximaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Builds signed packets. Every lane that sends must go through this. */
class PacketFactory(
    private val trust: TrustEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun create(
        kind: PacketKind,
        payload: String,
        severity: Severity = Severity.MEDIUM,
        ttl: Int = severity.hopBudget,
        recipientKey: String? = null,
        channel: String? = null,
    ): MeshPacket = trust.sign(
        MeshPacket(
            packetId = UUID.randomUUID().toString(),
            kind = kind,
            originNodeId = trust.identity.nodeId,
            originKey = trust.identity.publicKey,
            createdAtMs = clock(),
            severity = severity,
            ttl = ttl,
            payload = payload,
            recipientKey = recipientKey,
            channel = channel,
        )
    )
}
