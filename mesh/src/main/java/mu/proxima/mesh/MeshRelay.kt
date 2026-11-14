package mu.proxima.mesh

import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind

/**
 * Pure relay rules (no Android), so they are unit-tested on the JVM.
 *
 * Path convention: `path` lists every node the packet has left, origin first. The last entry is
 * always the node we received it from. Relays change only ttl, hopCount and path (signature stays valid).
 */
object MeshRelay {

    /** What the origin puts on the wire. */
    fun originate(packet: MeshPacket, selfNodeId: String): MeshPacket =
        packet.copy(hopCount = 0, path = listOf(selfNodeId))

    /** The copy to forward after receiving [packet], or null if it must not travel further. */
    fun nextHop(packet: MeshPacket, selfNodeId: String): MeshPacket? {
        if (packet.ttl <= 1) return null
        if (packet.originNodeId == selfNodeId) return null // our own packet echoed back
        return packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
            path = packet.path + selfNodeId,
        )
    }

    /** Don't send back to the node it came from or to any node already on its path. */
    fun shouldSendTo(packet: MeshPacket, neighbourNodeId: String): Boolean =
        neighbourNodeId !in packet.path

    /**
     * Only packets that still mean something minutes later are kept for store-and-forward.
     * BEACON (re-sent every 10 s anyway) and CONTROL (live signalling such as a UWB session handshake, #24)
     * would be stale or harmful if replayed to a phone that walks into range 20 minutes later.
     */
    fun isStorable(packet: MeshPacket): Boolean =
        packet.kind != PacketKind.BEACON && packet.kind != PacketKind.CONTROL

    /**
     * Store-and-forward flush for a newly connected neighbour: only live packets (≤ [storeMs] old)
     * that it hasn't already carried, most severe first, oldest first within a severity.
     */
    fun flushOrder(stored: Collection<MeshPacket>, neighbourNodeId: String, nowMs: Long, storeMs: Long): List<MeshPacket> =
        stored.asSequence()
            .filter { isStorable(it) && nowMs - it.createdAtMs <= storeMs && it.ttl >= 1 && shouldSendTo(it, neighbourNodeId) }
            .sortedWith(compareByDescending<MeshPacket> { it.severity.ordinal }.thenBy { it.createdAtMs })
            .toList()

    /**
     * A BEACON that has not been relayed (hopCount 0) was sent by the phone at the other end of this link,
     * so its origin is that link's current node id, even if the app restarted with a new id on the same link (#24).
     */
    fun directSenderOf(packet: MeshPacket): String? =
        if (packet.kind == PacketKind.BEACON && packet.hopCount == 0) packet.originNodeId else null

    /** A connected link that has carried nothing (not even a 10 s beacon) for this long is treated as dead. */
    const val SILENT_LINK_MS = 90_000L

    fun isSilent(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs > SILENT_LINK_MS

    /**
     * Stored packets that [newer] replaces. A finder shares its position with the guardian every 10 s as SEALED
     * CRITICAL packets; keeping all of them would fill the store (200 max, oldest evicted, so the original SOS goes
     * first) and flood a newly met phone with stale positions. Only the newest SEALED packet per
     * (origin, recipient, channel) is worth carrying. Found notices have no channel; friend invites (#61) use "friend".
     */
    fun supersededBy(newer: MeshPacket, stored: Collection<MeshPacket>): List<MeshPacket> =
        if (newer.kind != PacketKind.SEALED || newer.recipientKey == null) emptyList()
        else stored.filter {
            it.kind == PacketKind.SEALED && it.packetId != newer.packetId &&
                it.originNodeId == newer.originNodeId && it.recipientKey == newer.recipientKey &&
                it.channel == newer.channel && // a friend invite (#61, channel "friend") never replaces a found notice
                it.createdAtMs <= newer.createdAtMs
        }

    /**
     * After an app restart, which stored packets are handed once more to THIS phone's pipeline (its incident list
     * lives in memory). Without this the guardian's own LOST report is gone, and when a neighbour re-forwards it the
     * mesh drops it as already seen, so the finder's sealed notice is refused (#51). Only incidents and sealed
     * notices: replaying triage requests would make the AI redo work.
     */
    fun replayAfterRestart(stored: Collection<MeshPacket>, nowMs: Long, storeMs: Long): List<MeshPacket> =
        stored.filter {
            (it.kind == PacketKind.INCIDENT || it.kind == PacketKind.SEALED) && nowMs - it.createdAtMs <= storeMs
        }.sortedBy { it.createdAtMs }

    /** The node that handed us this packet (for HopEvents). */
    fun lastHop(packet: MeshPacket): String = packet.path.lastOrNull() ?: packet.originNodeId

    /**
     * Surge rule 3 (scale-up card): when the store-and-forward buffer is full, evict the least severe packet,
     * oldest first within a severity. (It used to drop the oldest, so a flood of LOW could push out a CRITICAL.)
     */
    fun evictFor(stored: Collection<MeshPacket>): MeshPacket? =
        stored.minWithOrNull(compareBy<MeshPacket> { it.severity.ordinal }.thenBy { it.createdAtMs })
}
