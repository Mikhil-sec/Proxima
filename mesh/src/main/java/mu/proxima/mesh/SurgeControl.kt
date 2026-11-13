package mu.proxima.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.pipeline.ProximaJson
import kotlin.math.min

/*
 * Surprise card "Scale up": thousands of users at once (14 Nov).
 *
 * What breaks: every relay forwards every packet instantly to every neighbour. When a crowd crush makes hundreds
 * of people press SOS within seconds, the radio links saturate, and an unrelated cardiac arrest waits behind
 * hundreds of copies of the same crush, or is evicted from the store-and-forward buffer by a flood of beacons.
 *
 * The adaptation is three pure rules, unit-tested on the JVM and used by NearbyMeshEngine:
 * 1. [HazardCollapse]: one hazard is one event. A relay forwards the first few reports of the same hazard in the
 *    same 25 m square, then counts the rest locally. People (medical, lost) are never collapsed.
 * 2. [RelayGovernor]: a per-phone rate cap with a priority buffer. Over the cap, packets wait; the most severe goes
 *    first; when the buffer is full the least important is shed (relayed beacons, then LOW). Own SOS never waits.
 * 3. [MeshRelay.evictFor]: store-and-forward evicts the least severe packet, not the oldest.
 *
 * With normal traffic (a demo, a few phones) none of these limits is reached, so behaviour is unchanged.
 */

/**
 * Relay priority, higher = more urgent: relayed beacons lowest, then by severity. At equal severity a report that
 * can be collapsed (a located hazard, [hazardKey] not null) yields to one that can't (a person: medical, lost):
 * a cardiac arrest must not wait behind the fourth copy of a crush everyone already knows about.
 */
fun relayPriority(packet: MeshPacket, hazardKey: String? = HazardCollapse.keyOf(packet)): Int =
    if (packet.kind == PacketKind.BEACON) 0 else packet.severity.ordinal * 2 + 1 + (if (hazardKey == null) 1 else 0)

/**
 * Rule 1. Collapse duplicate hazard reports before they are forwarded.
 * Key = hazard subtype + 25 m cell. Within [windowMs], only the first [perCell] reports per key are forwarded.
 * Everything is still delivered to this phone's own pipeline, which merges it into one incident with a count.
 */
class HazardCollapse(
    private val perCell: Int = 3,
    private val windowMs: Long = 2 * 60_000L,
) {
    private val forwarded = HashMap<String, ArrayDeque<Long>>()

    /** True if this relay should forward [packet] (always true for anything but a located HAZARD report). */
    fun shouldForward(packet: MeshPacket, nowMs: Long): Boolean = shouldForwardKey(keyOf(packet), nowMs)

    /** Same rule on a precomputed [keyOf] (null = not a hazard: always forward). */
    fun shouldForwardKey(key: String?, nowMs: Long): Boolean {
        key ?: return true
        val times = forwarded.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size >= perCell) return false
        times.addLast(nowMs)
        if (forwarded.size > MAX_KEYS) forwarded.entries.removeAll { (_, t) -> t.isEmpty() || nowMs - t.last() > windowMs }
        return true
    }

    companion object {
        private const val MAX_KEYS = 512

        /** Collapse key for a hazard report with a location, else null (never collapsed). */
        fun keyOf(packet: MeshPacket): String? {
            if (packet.kind != PacketKind.INCIDENT) return null
            val incident = runCatching { ProximaJson.decodeFromString(Incident.serializer(), packet.payload) }.getOrNull() ?: return null
            if (incident.type != IncidentType.HAZARD) return null // people are never merged away
            val at = incident.location ?: return null
            val (e, n) = GeoMath.cellKey(at, MeshBeaconInfo.CELL_M)
            return "${incident.subtype.orEmpty()}@$e,$n"
        }
    }
}

/** What the relay is doing right now, for the dev sheet and the Command Center. */
data class RelayLoad(
    val queued: Int = 0,
    val relayed: Long = 0,
    val shed: Long = 0,
    val collapsed: Long = 0,
    val peakQueue: Int = 0,
)

/**
 * Rule 2. Token bucket ([burst] packets, refilled at [perSecond]) in front of the radio, with a priority buffer of
 * at most [maxQueue] items. [offer] and [drain] return what may go on the air now, most urgent first.
 * Not thread-safe: NearbyMeshEngine calls it from its single serial dispatcher.
 */
class RelayGovernor<T>(
    private val burst: Int = 30,
    private val perSecond: Double = 10.0,
    private val maxQueue: Int = 200,
    private val priority: (T) -> Int,
) {
    private class Waiting<T>(val item: T, val priority: Int, val seq: Long)

    private val queue = ArrayList<Waiting<T>>()
    private var tokens = burst.toDouble()
    private var lastMs: Long? = null
    private var seq = 0L

    var relayed = 0L; private set
    var shed = 0L; private set
    var peakQueue = 0; private set
    val queued: Int get() = queue.size

    fun offer(item: T, nowMs: Long): List<T> {
        queue += Waiting(item, priority(item), seq++)
        if (queue.size > maxQueue) {
            // Shed the least urgent; among equals the oldest (most likely already carried by other phones).
            val victim = queue.minWith(compareBy<Waiting<T>> { it.priority }.thenBy { it.seq })
            queue.remove(victim)
            shed++
        }
        peakQueue = maxOf(peakQueue, queue.size)
        return drain(nowMs)
    }

    fun drain(nowMs: Long): List<T> {
        refill(nowMs)
        if (queue.isEmpty() || tokens < 1.0) return emptyList()
        val n = min(tokens.toInt(), queue.size)
        // Most urgent first; first in, first out within a priority.
        val out = queue.sortedWith(compareByDescending<Waiting<T>> { it.priority }.thenBy { it.seq }).take(n)
        queue.removeAll(out.toSet())
        tokens -= out.size
        relayed += out.size
        return out.map { it.item }
    }

    private fun refill(nowMs: Long) {
        val last = lastMs
        lastMs = nowMs
        if (last != null && nowMs > last) tokens = min(burst.toDouble(), tokens + (nowMs - last) / 1000.0 * perSecond)
    }
}

/** Live relay load of this phone (UI reads it; MESH writes it). */
object MeshLoad {
    private val _state = MutableStateFlow(RelayLoad())
    val state: StateFlow<RelayLoad> = _state.asStateFlow()
    internal fun publish(load: RelayLoad) { if (_state.value != load) _state.value = load }
}
