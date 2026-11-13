package mu.proxima.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.Beacon
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Peer
import mu.proxima.core.model.Severity
import mu.proxima.core.model.Transport
import mu.proxima.core.pipeline.PacketFactory
import mu.proxima.core.pipeline.ProximaJson
import mu.proxima.mesh.acoustic.AcousticLink
import mu.proxima.mesh.acoustic.AcousticSos
import kotlin.random.Random

/**
 * Real mesh on Google Nearby Connections (P2P_CLUSTER: every phone advertises and discovers at once).
 *
 * Receive → drop duplicate packetId → deliver to [inbound] → forward to every other neighbour with
 * ttl-1 / hopCount+1 / path+self. Packets are kept ~30 min and flushed to each new neighbour
 * (CRITICAL first), so a phone that walks into range still gets live alerts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyMeshEngine(
    private val context: Context,
    private val capabilities: DeviceCapabilities,
    private val trust: TrustEngine,
    private val scope: CoroutineScope,
    private val db: MeshDatabase = MeshDatabase.create(context),
    private val clock: () -> Long = System::currentTimeMillis,
) : MeshEngine {

    private val seen = SeenStore(db.seen(), clock)
    private val stored = db.stored()

    override val selfNodeId: String = trust.identity.nodeId

    override val requiredPermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Verified on a Galaxy S947B (API 37): Nearby startDiscovery still fails with 8034 without coarse location.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _inbound = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    override val inbound: SharedFlow<MeshPacket> = _inbound.asSharedFlow()

    private val _hops = MutableSharedFlow<HopEvent>(extraBufferCapacity = 256)
    override val hops: SharedFlow<HopEvent> = _hops.asSharedFlow()

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val packets = PacketFactory(trust, clock)

    /** All engine state is touched only from this single-threaded dispatcher. */
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private data class Endpoint(
        val nodeId: String,
        val tier: DeviceTier,
        val connected: Boolean,
        val lastSeenMs: Long,
        /** Failed/pending attempts since the last successful link (drives backoff). */
        val attempts: Int = 0,
        /** When to try connecting next; null = don't (e.g. dropped by the demo allow-list). */
        val nextAttemptMs: Long? = null,
    )

    private val endpoints = mutableMapOf<String, Endpoint>()          // endpointId → neighbour
    private val indirect = mutableMapOf<String, Peer>()              // nodeId → peer known via beacons
    private val outbox = ArrayDeque<MeshPacket>()                    // store-and-forward
    private val jobs = mutableListOf<Job>()

    private val localName = "$selfNodeId|${capabilities.tier.name}"

    // ---------------------------------------------------------------- lifecycle

    override suspend fun start() {
        if (_running.value) return
        _running.value = true
        Log.i(TAG, "mesh start node=$selfNodeId tier=${capabilities.tier}")
        MeshService.start(context)
        scope.launch { runCatching { seen.prune() } }
        jobs += scope.launch(serial) { restoreOutbox() }

        startAdvertising()
        startDiscovery()
        jobs += scope.launch(serial) { // retry: phone locked at start, permission granted later, radio toggled
            while (isActive) {
                delay(RADIO_RETRY_MS)
                if (!advertising) startAdvertising()
                if (!discovering) startDiscovery()
            }
        }

        jobs += scope.launch(serial) { // beacons → indirect peers on other phones
            while (isActive) {
                sendBeacon()
                delay(BEACON_INTERVAL_MS)
            }
        }
        jobs += scope.launch(serial) { // expire stale indirect peers and old outbox packets
            while (isActive) {
                delay(5_000)
                val now = clock()
                indirect.values.removeAll { now - it.lastSeenMs > PEER_EXPIRY_MS }
                endpoints.values.removeAll { !it.connected && now - it.lastSeenMs > FORGET_ENDPOINT_MS }
                // Nearby doesn't always report a disconnect (e.g. the peer app restarted): drop links silent > 90 s.
                endpoints.entries.filter { (_, ep) -> ep.connected && MeshRelay.isSilent(ep.lastSeenMs, now) }.forEach { (id, ep) ->
                    Log.i(TAG, "link to ${ep.nodeId} silent ${(now - ep.lastSeenMs) / 1000}s, dropping")
                    client.disconnectFromEndpoint(id)
                    endpoints[id] = ep.copy(connected = false, attempts = 0,
                        nextAttemptMs = if (MeshDemoControls.allows(ep.nodeId)) now + ReconnectPolicy.backoffMs(0) else null)
                }
                if (outbox.removeAll { now - it.createdAtMs > STORE_MS }) {
                    runCatching { stored.pruneOlderThan(now - STORE_MS) }
                }
                publishPeers()
            }
        }
        jobs += scope.launch(serial) { // reconnect known neighbours (links drop constantly in a crowd)
            while (isActive) {
                delay(1_000)
                reconnectDue()
            }
        }
        jobs += scope.launch(serial) { // demo allow-list (issue #8)
            MeshDemoControls.allowedNeighbours.collect { applyAllowList() }
        }
        jobs += scope.launch(serial) { // acoustic fallback: listen only while switched on and RF has no links
            while (isActive) {
                updateAcousticListener()
                delay(2_000)
            }
        }
        registerDebugPing()
    }

    // ---------------------------------------------------------------- debug-only adb hook

    private var pingReceiver: BroadcastReceiver? = null

    /**
     * Debug builds only: `adb shell am broadcast -a mu.proxima.mesh.PING` sends a CONTROL packet
     * (channel "ping", ignored by the incident pipeline) so multi-hop can be tested without UI.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDebugPing() {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == ACTION_ALLOW) {
                    // adb shell am broadcast -a mu.proxima.mesh.ALLOW --es nodes "SELF-AAAA,SELF-BBBB"   ("*" = everyone)
                    val nodes = intent.getStringExtra("nodes")?.trim().orEmpty()
                    val set = if (nodes.isEmpty() || nodes == "*") null else nodes.split(',').map { it.trim() }.toSet()
                    Log.i(TAG, "demo allow-list = ${set ?: "everyone"}")
                    MeshDemoControls.allowedNeighbours.value = set
                    return
                }
                if (intent.action == ACTION_ACOUSTIC) {
                    // adb shell am broadcast -a mu.proxima.mesh.ACOUSTIC -p <pkg> --ez on true
                    MeshDemoControls.acoustic.value = intent.getBooleanExtra("on", true)
                    if (intent.hasExtra("source")) { // --ei source 1|6|9: device check of mic sources
                        acoustic.sourceOverride = intent.getIntExtra("source", 6)
                        scope.launch(serial) { acousticListen?.cancel(); acousticListen = null } // restarted within 2 s
                    }
                    Log.i(TAG, "acoustic fallback = ${MeshDemoControls.acoustic.value}")
                    return
                }
                if (intent.action == ACTION_CHIRP) {
                    // Device check of the speaker→mic path only: chirps a test SOS (HIGH MEDICAL, no location)
                    // without putting anything into this phone's own pipeline. Debug builds only.
                    val sos = AcousticSos(IncidentType.MEDICAL, Severity.HIGH, selfNodeId, null, Random.nextInt(0x10000))
                    scope.launch(serial) { chirp(sos) }
                    return
                }
                scope.launch(serial) {
                    val p = packets.create(PacketKind.CONTROL, "ping from $selfNodeId", Severity.HIGH, channel = "ping")
                    Log.i(TAG, "tx PING ${p.packetId.take(8)} to ${endpoints.values.count { it.connected }} neighbours")
                    send(p)
                }
            }
        }
        val filter = IntentFilter(ACTION_PING).apply { addAction(ACTION_ALLOW); addAction(ACTION_ACOUSTIC); addAction(ACTION_CHIRP) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        pingReceiver = receiver
    }

    @Volatile private var advertising = false
    @Volatile private var discovering = false

    private fun startAdvertising() {
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { advertising = true; Log.i(TAG, "advertising") }
            .addOnFailureListener { e ->
                advertising = alreadyActive(e, ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING)
                if (!advertising) Log.e(TAG, "advertising failed (retry in ${RADIO_RETRY_MS / 1000}s): ${e.message}")
            }
    }

    private fun startDiscovery() {
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { discovering = true; Log.i(TAG, "discovering") }
            .addOnFailureListener { e ->
                discovering = alreadyActive(e, ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING)
                if (!discovering) Log.e(TAG, "discovery failed (retry in ${RADIO_RETRY_MS / 1000}s): ${e.message}")
            }
    }

    private fun alreadyActive(e: Exception, code: Int) =
        (e as? com.google.android.gms.common.api.ApiException)?.statusCode == code

    override suspend fun stop() {
        if (!_running.value) return
        advertising = false
        discovering = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        acousticListen?.cancel()
        acousticListen = null
        pingReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        pingReceiver = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        scope.launch(serial) {
            endpoints.clear()
            indirect.clear()
            publishPeers()
        }
        MeshService.stop(context)
        _running.value = false
        Log.i(TAG, "mesh stopped")
    }

    // ---------------------------------------------------------------- send / receive

    override suspend fun send(packet: MeshPacket) {
        scope.launch(serial) {
            if (!seen.markFirstSeen(packet.packetId)) return@launch
            val out = MeshRelay.originate(packet, selfNodeId)
            remember(out)
            broadcast(out, excludeEndpoint = null)
            if (acousticActive()) AcousticSos.fromPacket(out)?.let { chirp(it) }
        }.join()
    }

    // ---------------------------------------------------------------- acoustic fallback (P2)

    private val acoustic by lazy { AcousticLink(context) }
    private var acousticListen: Job? = null

    /** Only when switched on AND RF has nothing: call on [serial]. */
    private fun acousticActive() = MeshDemoControls.acoustic.value && endpoints.values.none { it.connected }

    private fun chirp(sos: AcousticSos) {
        Log.i(TAG, "acoustic chirp ${sos.type}/${sos.severity} (no RF neighbours)")
        scope.launch { runCatching { acoustic.transmit(sos.encode()) }.onFailure { Log.e(TAG, "acoustic tx failed: ${it.message}") } }
    }

    /** Runs on [serial] every few seconds: start/stop the mic listener as the switch and RF state change. */
    private fun updateAcousticListener() {
        val want = acousticActive()
        if (want && acousticListen?.isActive != true) {
            acousticListen = acoustic.listen(scope) { bytes -> onAcoustic(bytes) }
        } else if (!want && acousticListen != null) {
            acousticListen?.cancel()
            acousticListen = null
        }
    }

    private suspend fun onAcoustic(bytes: ByteArray) {
        val sos = AcousticSos.decode(bytes) ?: return
        if (AcousticSos.nodeIdBytes(sos.originNodeId) == AcousticSos.nodeIdBytes(selfNodeId)) {
            Log.i(TAG, "acoustic heard own chirp (ignored)")
            return
        }
        Log.i(TAG, "acoustic rx ${sos.type}/${sos.severity} from=${sos.originNodeId} loc=${sos.location != null}")
        scope.launch(serial) { onPacket(sos.toPacket(clock()), fromEndpoint = ACOUSTIC_ENDPOINT) }.join()
    }

    private suspend fun onPacket(packet: MeshPacket, fromEndpoint: String) {
        endpoints[fromEndpoint]?.let { ep ->
            val sender = MeshRelay.directSenderOf(packet)
            if (sender != null && sender != ep.nodeId && sender != selfNodeId) {
                Log.i(TAG, "link $fromEndpoint is now node $sender (was ${ep.nodeId}: app restarted)")
                indirect.remove(sender)
                endpoints[fromEndpoint] = ep.copy(nodeId = sender, lastSeenMs = clock())
                publishPeers()
            } else {
                endpoints[fromEndpoint] = ep.copy(lastSeenMs = clock())
            }
        }
        if (!seen.markFirstSeen(packet.packetId)) return
        val fromNode = endpoints[fromEndpoint]?.nodeId ?: MeshRelay.lastHop(packet)
        if (packet.kind != PacketKind.BEACON) {
            Log.i(TAG, "rx ${packet.kind} ${packet.packetId.take(8)} from=$fromNode hops=${packet.hopCount} ttl=${packet.ttl}")
            _hops.tryEmit(HopEvent(packet.packetId, packet.kind, packet.severity, fromNode, selfNodeId, clock()))
        } else {
            Log.d(TAG, "rx BEACON from=$fromNode origin=${packet.originNodeId} hops=${packet.hopCount}")
            onBeacon(packet)
        }
        _inbound.tryEmit(packet)
        MeshRelay.nextHop(packet, selfNodeId)?.let { fwd ->
            remember(fwd)
            broadcast(fwd, excludeEndpoint = fromEndpoint)
        }
    }

    private fun broadcast(packet: MeshPacket, excludeEndpoint: String?) {
        val targets = endpoints.filter { (id, ep) ->
            ep.connected && id != excludeEndpoint && MeshRelay.shouldSendTo(packet, ep.nodeId)
        }
        if (targets.isEmpty()) return
        sendTo(targets.keys.toList(), packet)
        if (packet.kind != PacketKind.BEACON) {
            targets.values.forEach {
                _hops.tryEmit(HopEvent(packet.packetId, packet.kind, packet.severity, selfNodeId, it.nodeId, clock()))
            }
        }
    }

    private fun sendTo(endpointIds: List<String>, packet: MeshPacket) {
        val bytes = ProximaJson.encodeToString(MeshPacket.serializer(), packet).encodeToByteArray()
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            Log.e(TAG, "packet ${packet.packetId.take(8)} too big (${bytes.size} B), dropped")
            return
        }
        client.sendPayload(endpointIds, Payload.fromBytes(bytes))
            .addOnFailureListener { Log.w(TAG, "send failed: ${it.message}") }
    }

    /** Store-and-forward: keep live packets (memory + Room) so new neighbours get them, even after a restart. */
    private fun remember(packet: MeshPacket) {
        if (!MeshRelay.isStorable(packet)) return
        // A finder's newer position replaces its older ones (stops 10 s updates filling the store).
        if (outbox.any { MeshRelay.supersededBy(it, listOf(packet)).isNotEmpty() }) return // an even newer one is kept
        val old = MeshRelay.supersededBy(packet, outbox).map { it.packetId }.toSet()
        if (old.isNotEmpty()) {
            outbox.removeAll { it.packetId in old }
            scope.launch { runCatching { stored.delete(old.toList()) } }
        }
        outbox.removeAll { it.packetId == packet.packetId }
        outbox.addLast(packet)
        while (outbox.size > OUTBOX_MAX) outbox.removeFirst()
        val json = ProximaJson.encodeToString(MeshPacket.serializer(), packet)
        scope.launch {
            runCatching { stored.upsert(StoredPacket(packet.packetId, json, packet.createdAtMs)) }
                .onFailure { Log.w(TAG, "store packet failed: ${it.message}") }
        }
    }

    private suspend fun restoreOutbox() {
        val rows = runCatching { stored.since(clock() - STORE_MS) }.getOrDefault(emptyList())
        val restored = rows.mapNotNull { runCatching { ProximaJson.decodeFromString(MeshPacket.serializer(), it.json) }.getOrNull() }
        restored.forEach { p ->
            if (outbox.none { it.packetId == p.packetId }) outbox.addLast(p)
            seen.markFirstSeen(p.packetId) // don't re-deliver our stored packets to inbound if they echo back
        }
        while (outbox.size > OUTBOX_MAX) outbox.removeFirst()
        if (restored.isNotEmpty()) Log.i(TAG, "restored ${restored.size} stored packets")
        // Rebuild this phone's own incident list (the pipeline keeps it in memory only), #51.
        val replay = MeshRelay.replayAfterRestart(restored, clock(), STORE_MS)
        if (replay.isNotEmpty()) {
            withTimeoutOrNull(5_000) { _inbound.subscriptionCount.first { it > 0 } } // pipeline listening first
            replay.forEach { _inbound.emit(it) }
            Log.i(TAG, "replayed ${replay.size} stored incidents/notices to this phone's pipeline")
        }
    }

    private fun flushTo(endpointId: String) {
        val ep = endpoints[endpointId] ?: return
        val toSend = MeshRelay.flushOrder(outbox, ep.nodeId, clock(), STORE_MS)
        if (toSend.isNotEmpty()) Log.i(TAG, "flush ${toSend.size} stored packets to ${ep.nodeId}")
        toSend.forEach { sendTo(listOf(endpointId), it) }
    }

    // ---------------------------------------------------------------- beacons / peers

    private fun sendBeacon() {
        if (endpoints.values.none { it.connected }) return
        val beacon = Beacon(
            nodeId = selfNodeId, tier = capabilities.tier, hasUwb = capabilities.hasUwb,
            llmReady = MeshBeaconInfo.llmReady.value,
            location = MeshBeaconInfo.location.value?.let { MeshBeaconInfo.coarse(it) }, // anonymous 25 m cell only
            role = MeshBeaconInfo.rolePass.value, // role phones only (#69); verified by receivers with this signed packet
        )
        val packet = packets.create(PacketKind.BEACON, ProximaJson.encodeToString(Beacon.serializer(), beacon), Severity.LOW)
        scope.launch(serial) {
            seen.markFirstSeen(packet.packetId)
            broadcast(MeshRelay.originate(packet, selfNodeId), excludeEndpoint = null)
        }
    }

    private fun onBeacon(packet: MeshPacket) {
        val b = runCatching { ProximaJson.decodeFromString(Beacon.serializer(), packet.payload) }.getOrNull() ?: return
        if (b.nodeId == selfNodeId) return
        val verified = MeshBeaconInfo.verifiedRole(b, packet, clock(), trust::verifyRoleClaim)
        if (verified != null && indirect[b.nodeId]?.role == null) Log.i(TAG, "verified ${verified.first} \"${verified.second}\" = ${b.nodeId}")
        indirect[b.nodeId] = Peer(
            nodeId = b.nodeId, shortCode = shortCode(b.nodeId), tier = b.tier, transport = Transport.NEARBY_AUTO,
            lastSeenMs = clock(), location = b.location, hasUwb = b.hasUwb, llmReady = b.llmReady, direct = false,
            role = verified?.first, roleLabel = verified?.second,
        )
        publishPeers()
    }

    private fun publishPeers() {
        val direct = endpoints.values.filter { it.connected }.map {
            Peer(nodeId = it.nodeId, shortCode = shortCode(it.nodeId), tier = it.tier, transport = Transport.NEARBY_AUTO,
                lastSeenMs = it.lastSeenMs, location = indirect[it.nodeId]?.location,
                hasUwb = indirect[it.nodeId]?.hasUwb ?: false,
                llmReady = indirect[it.nodeId]?.llmReady ?: false, direct = true,
                role = indirect[it.nodeId]?.role, roleLabel = indirect[it.nodeId]?.roleLabel)
        }
        val directIds = direct.map { it.nodeId }.toSet()
        _peers.value = direct + indirect.values.filter { it.nodeId !in directIds }
    }

    // ---------------------------------------------------------------- connections

    private fun parseName(name: String): Pair<String, DeviceTier> {
        val parts = name.split('|')
        val tier = parts.getOrNull(1)?.let { t -> DeviceTier.entries.firstOrNull { it.name == t } } ?: DeviceTier.TIER2_SCOUT
        return parts[0] to tier
    }

    private fun connect(endpointId: String) {
        client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    Log.w(TAG, "requestConnection $endpointId failed: ${e.message}")
                }
                scope.launch(serial) { clearInFlight() } // free the radio for the next candidate
            }
    }

    private fun applyAllowList() {
        val now = clock()
        endpoints.forEach { (id, ep) ->
            val allowed = MeshDemoControls.allows(ep.nodeId)
            if (ep.connected && !allowed) {
                Log.i(TAG, "demo allow-list: dropping ${ep.nodeId}")
                client.disconnectFromEndpoint(id)
                endpoints[id] = ep.copy(connected = false, nextAttemptMs = null)
            } else if (!ep.connected && allowed) {
                endpoints[id] = ep.copy(attempts = 0, nextAttemptMs = now) // reconnect loop picks it up
            }
        }
        publishPeers()
    }

    /** A connection handshake (ours or incoming) is in progress until this time: never start a second one. */
    private var inFlightUntilMs = 0L

    private fun clearInFlight() { inFlightUntilMs = 0L }

    /** Called every second on [serial]: (re)connect to ONE known neighbour whose backoff has elapsed. */
    private fun reconnectDue() {
        val now = clock()
        // Forget ghosts (e.g. the previous run of a restarted phone); discovery re-adds real phones.
        endpoints.entries.removeAll { (_, ep) ->
            (!ep.connected && ep.attempts >= ReconnectPolicy.MAX_ATTEMPTS).also { if (it) Log.i(TAG, "forget ${ep.nodeId} after ${ep.attempts} attempts") }
        }
        val candidates = endpoints.map { (id, ep) ->
            ReconnectPolicy.Candidate(id, ep.connected, MeshDemoControls.allows(ep.nodeId), ep.nextAttemptMs, ep.attempts)
        }
        val id = ReconnectPolicy.pickNext(candidates, now, inFlightUntilMs) ?: return
        val ep = endpoints.getValue(id)
        if (ep.attempts > 0) Log.i(TAG, "reconnect ${ep.nodeId} attempt ${ep.attempts + 1}")
        endpoints[id] = ep.copy(
            attempts = ep.attempts + 1,
            nextAttemptMs = now + ReconnectPolicy.backoffMs(ep.attempts + 1) + Random.nextLong(0, 1_000),
        )
        inFlightUntilMs = now + ReconnectPolicy.CONNECT_TIMEOUT_MS
        connect(id)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            scope.launch(serial) {
                val (nodeId, tier) = parseName(info.endpointName)
                if (nodeId == selfNodeId) return@launch
                Log.i(TAG, "found $nodeId ($tier) ep=$endpointId")
                val existing = endpoints[endpointId]
                if (existing?.connected == true) return@launch
                // Same phone re-found under a new endpoint id: drop the stale id so we don't dial a ghost.
                endpoints.entries.removeAll { (id, ep) -> id != endpointId && ep.nodeId == nodeId && !ep.connected }
                // Tie-break so both phones don't request at once; the reconnect loop does the actual request.
                endpoints[endpointId] = Endpoint(nodeId, tier, connected = false, lastSeenMs = clock(),
                    nextAttemptMs = ReconnectPolicy.firstAttemptAt(selfNodeId, nodeId, clock()))
                reconnectDue()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            scope.launch(serial) {
                if (endpoints[endpointId]?.connected == false) endpoints.remove(endpointId)
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (nodeId, tier) = parseName(info.endpointName)
            if (!MeshDemoControls.allows(nodeId)) {
                client.rejectConnection(endpointId)
                return
            }
            scope.launch(serial) {
                inFlightUntilMs = clock() + ReconnectPolicy.CONNECT_TIMEOUT_MS // incoming handshake uses the radio too
                val prev = endpoints[endpointId]
                endpoints[endpointId] = Endpoint(nodeId, tier, connected = false, lastSeenMs = clock(),
                    attempts = prev?.attempts ?: 0,
                    nextAttemptMs = clock() + ReconnectPolicy.backoffMs(prev?.attempts ?: 0))
            }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            scope.launch(serial) {
                clearInFlight()
                val ep = endpoints[endpointId] ?: return@launch
                if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    Log.i(TAG, "connected ${ep.nodeId}")
                    endpoints[endpointId] = ep.copy(connected = true, lastSeenMs = clock(), attempts = 0, nextAttemptMs = null)
                    publishPeers()
                    flushTo(endpointId)
                    sendBeacon()
                } else {
                    // Keep it known; the reconnect loop retries with backoff.
                    Log.w(TAG, "connection to ${ep.nodeId} failed: ${resolution.status.statusCode}")
                    if (ep.nextAttemptMs == null) {
                        endpoints[endpointId] = ep.copy(nextAttemptMs = clock() + ReconnectPolicy.backoffMs(ep.attempts))
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            scope.launch(serial) {
                val ep = endpoints[endpointId] ?: return@launch
                Log.i(TAG, "disconnected ${ep.nodeId}, will retry")
                // Keep it: Nearby rarely re-reports a found endpoint, so we must retry ourselves.
                val wasAllowed = MeshDemoControls.allows(ep.nodeId)
                endpoints[endpointId] = ep.copy(connected = false, attempts = 0,
                    nextAttemptMs = if (wasAllowed) clock() + ReconnectPolicy.backoffMs(0) else null)
                publishPeers()
            }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val packet = runCatching {
                ProximaJson.decodeFromString(MeshPacket.serializer(), bytes.decodeToString())
            }.getOrElse {
                Log.w(TAG, "bad packet from $endpointId: ${it.message}")
                return
            }
            scope.launch(serial) { onPacket(packet, endpointId) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun shortCode(nodeId: String) = nodeId.takeLast(4).uppercase()

    companion object {
        private const val TAG = "Proxima"
        const val SERVICE_ID = "mu.proxima.mesh"
        const val ACTION_PING = "mu.proxima.mesh.PING"
        const val ACTION_ALLOW = "mu.proxima.mesh.ALLOW"
        const val ACTION_ACOUSTIC = "mu.proxima.mesh.ACOUSTIC"
        const val ACTION_CHIRP = "mu.proxima.mesh.CHIRP"
        private const val ACOUSTIC_ENDPOINT = "acoustic"
        private const val BEACON_INTERVAL_MS = 10_000L
        private const val PEER_EXPIRY_MS = 35_000L
        private const val STORE_MS = 30 * 60 * 1000L
        private const val OUTBOX_MAX = 200
        private const val FORGET_ENDPOINT_MS = 10 * 60 * 1000L
        private const val RADIO_RETRY_MS = 10_000L
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
