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

    /** The node that handed us this packet (for HopEvents). */
    fun lastHop(packet: MeshPacket): String = packet.path.lastOrNull() ?: packet.originNodeId
}
