package mu.proxima.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Peer
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TrackTarget
import mu.proxima.core.pipeline.PacketFactory

/**
 * Friend find (#38, #61): pairing and "where was their phone last heard".
 *
 * Pairing: both people look at the same six digits (derived from both phones' keys), then each phone sends the other
 * its beacon secret, sealed to that phone's key, as a CONTROL packet on channel "friend". Nothing is stored unless
 * the person on this phone confirmed the code.
 *
 * Finding: nothing is sent at all. The mesh already marks beacons whose rotating tag matches a friend
 * (`Peer.friendLabel`); this only reads `mesh.peers`.
 */
class FriendLink(private val graph: ProximaGraph) {
    /** A phone that confirmed the code on its side and is waiting for this one. */
    data class Request(val peerKey: String, val nodeId: String, val invite: String, val code: String)

    /** False in simulation and on engines without friend find: the UI hides the feature. */
    val available: Boolean = runCatching { graph.trust.beaconTag(System.currentTimeMillis()) }.getOrNull() != null

    var labels by mutableStateOf(runCatching { graph.trust.friends() }.getOrDefault(emptyList())); private set
    var incoming by mutableStateOf<Request?>(null); private set
    /** Nicknames confirmed here that the other phone hasn't confirmed yet. */
    var waiting by mutableStateOf<List<String>>(emptyList()); private set

    /** Peer key → nickname chosen here: codes confirmed on this phone, the other phone's secret not here yet. */
    private val confirmed = mutableMapOf<String, String>()
    private val packets = PacketFactory(graph.trust)

    fun codeFor(peer: Peer): String? = peer.publicKey?.let { runCatching { graph.trust.pairingCode(it) }.getOrNull() }

    /** The person here says both screens show the same code: send our secret, keep theirs when it arrives. */
    fun confirm(peerKey: String, label: String): Boolean {
        val invite = runCatching { graph.trust.friendInvite(peerKey) }.getOrNull() ?: return false
        graph.scope.launch {
            graph.mesh.send(packets.create(PacketKind.CONTROL, invite, Severity.HIGH, recipientKey = peerKey, channel = CHANNEL))
        }
        val theirs = incoming?.takeIf { it.peerKey == peerKey }
        if (theirs != null) {
            store(peerKey, theirs.invite, label)
            incoming = null
        } else {
            confirmed[peerKey] = label
            waiting = confirmed.values.toList()
        }
        return true
    }

    fun decline() { incoming = null }

    fun remove(label: String) {
        runCatching { graph.trust.removeFriend(label) }
        refresh()
    }

    /** Called for every inbound packet; ignores everything but a friend invite sealed to this phone. */
    fun onPacket(p: MeshPacket) {
        if (!available || p.kind != PacketKind.CONTROL || p.channel != CHANNEL) return
        if (p.recipientKey?.let(graph.trust::ownsKey) != true || !graph.trust.verify(p)) return
        val label = confirmed.remove(p.originKey)
        if (label != null) {
            waiting = confirmed.values.toList()
            store(p.originKey, p.payload, label)
        } else {
            val code = runCatching { graph.trust.pairingCode(p.originKey) }.getOrNull() ?: return
            incoming = Request(p.originKey, p.originNodeId, p.payload, code)
        }
    }

    /** The phone currently carrying [label]'s tag, as last heard on the mesh. */
    fun heard(label: String, peers: List<Peer>): Peer? = peers.filter { it.friendLabel == label }.maxByOrNull { it.lastSeenMs }

    /** Radar target: the 25 m cell their phone was last heard in. UWB / signal strength refine it when in range. */
    fun track(label: String): TrackTarget? {
        val now = heard(label, graph.mesh.peers.value) ?: return null
        val cells: Flow<mu.proxima.core.model.GeoPoint> =
            graph.mesh.peers.mapNotNull { heard(label, it)?.location }.distinctUntilChanged()
        return TrackTarget(nodeId = now.nodeId, label = label, locations = cells)
    }

    private fun store(peerKey: String, invite: String, label: String) {
        runCatching { graph.trust.acceptFriend(peerKey, invite, label) }
        refresh()
    }

    private fun refresh() { labels = runCatching { graph.trust.friends() }.getOrDefault(emptyList()) }

    private companion object { const val CHANNEL = "friend" }
}
