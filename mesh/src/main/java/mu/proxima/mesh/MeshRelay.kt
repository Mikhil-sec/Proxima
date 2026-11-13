package mu.proxima.mesh

import mu.proxima.core.model.MeshPacket

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

    /** The node that handed us this packet (for HopEvents). */
    fun lastHop(packet: MeshPacket): String = packet.path.lastOrNull() ?: packet.originNodeId
}
