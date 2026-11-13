package mu.proxima.locate

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import mu.proxima.core.pipeline.PacketFactory
import java.security.SecureRandom

/** One UWB measurement toward a peer. Azimuth is relative to the phone's back (0 = straight ahead). */
data class UwbReading(val distanceM: Double, val azimuthDeg: Double?, val atMs: Long)

/**
 * UWB precision mode. Session parameters travel over the mesh as signed CONTROL packets
 * (channel "uwb"), so no pairing UI is needed: the tracker asks, the target's engine answers.
 * Only constructed on API 31+ phones with UWB hardware (see AndroidLocateEngine).
 */
@RequiresApi(Build.VERSION_CODES.S)
class UwbRanger(
    context: Context,
    private val mesh: MeshEngine,
    private val trust: TrustEngine,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val packets = PacketFactory(trust)
    private val random = SecureRandom()
    private var responder: Job? = null
    /** Controlee sessions we are serving, by tracker nodeId. */
    private val serving = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Job>>()

    private val controls: Flow<Pair<MeshPacket, UwbControl>> = mesh.inbound
        .filter { it.kind == PacketKind.CONTROL && it.channel == UwbControl.CHANNEL }
        .filter { runCatching { trust.verify(it) }.getOrDefault(false) }
        .mapNotNull { p -> UwbControl.decode(p.payload)?.let { p to it } }
        .filter { (_, msg) -> msg.targetNodeId == mesh.selfNodeId }

    // ---------- target side (controlee): always listening while the engine runs ----------

    fun startResponder() {
        if (responder != null) return
        responder = scope.launch {
            controls.collect { (packet, msg) ->
                when (msg.type) {
                    UwbControl.Type.REQUEST -> serve(packet.originNodeId, msg)
                    UwbControl.Type.STOP -> serving[packet.originNodeId]?.takeIf { it.first == msg.sessionId }?.let {
                        it.second.cancel(); serving.remove(packet.originNodeId)
                        Log.i(TAG, "uwb: tracker stopped session ${msg.sessionId}")
                    }
                    UwbControl.Type.ACCEPT -> Unit // handled inside range()
                }
            }
        }
    }

    fun stop() {
        responder?.cancel(); responder = null
        serving.values.forEach { it.second.cancel() }; serving.clear()
    }

    private fun serve(trackerNodeId: String, req: UwbControl) {
        serving.remove(trackerNodeId)?.second?.cancel()
        val job = scope.launch {
            try {
                val manager = UwbManager.createInstance(appContext)
                if (!manager.isAvailable()) {
                    Log.w(TAG, "uwb: asked to range but UWB is off")
                    return@launch
                }
                val session = manager.controleeSessionScope()
                send(UwbControl(UwbControl.Type.ACCEPT, req.sessionId, trackerNodeId, addr = session.localAddress.address.toHex()))
                val params = parameters(
                    sessionId = req.sessionId,
                    stsKey = req.stsKey ?: return@launch,
                    channel = UwbComplexChannel(req.channel ?: return@launch, req.preamble ?: return@launch),
                    peerHex = req.addr,
                )
                Log.i(TAG, "uwb: serving tracker $trackerNodeId session ${req.sessionId}")
                session.prepareSession(params).collect { r ->
                    if (r is RangingResult.RangingResultFailure || r is RangingResult.RangingResultPeerDisconnected) {
                        throw CancellationException("uwb session ended")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "uwb: controlee error ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        serving[trackerNodeId] = req.sessionId to job
    }

    // ---------- tracker side (controller) ----------

    /** Cold flow of readings toward [targetNodeId]. Retries forever; cancel the collector to stop and send STOP. */
    fun range(targetNodeId: String): Flow<UwbReading> = channelFlow {
        val manager = UwbManager.createInstance(appContext)
        var sessionId = 0
        try {
            while (isActive) {
                try {
                    if (!manager.isAvailable()) { delay(RETRY_MS); continue }
                    val session = manager.controllerSessionScope()
                    val aoa = session.rangingCapabilities.isAzimuthalAngleSupported
                    sessionId = random.nextInt(Int.MAX_VALUE - 1) + 1
                    val key = ByteArray(8).also(random::nextBytes)
                    val channel = session.uwbComplexChannel
                    // Subscribe before sending so a fast ACCEPT is not missed.
                    val sid = sessionId
                    val accept = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(ACCEPT_TIMEOUT_MS) {
                            controls.first { (_, m) -> m.type == UwbControl.Type.ACCEPT && m.sessionId == sid }.second
                        }
                    }
                    send(
                        UwbControl(
                            UwbControl.Type.REQUEST, sessionId, targetNodeId,
                            addr = session.localAddress.address.toHex(),
                            channel = channel.channel, preamble = channel.preambleIndex, stsKey = key.toHex(),
                        )
                    )
                    val reply = accept.await() ?: run { delay(RETRY_MS); null } ?: continue
                    Log.i(TAG, "uwb: ranging with $targetNodeId session $sessionId aoa=$aoa")
                    session.prepareSession(parameters(sessionId, key.toHex(), channel, reply.addr)).collect { r ->
                        when (r) {
                            is RangingResult.RangingResultPosition -> {
                                val d = r.position.distance?.value?.toDouble() ?: return@collect
                                val az = if (aoa) r.position.azimuth?.value?.toDouble() else null
                                send(UwbReading(d, az, System.currentTimeMillis()))
                            }
                            is RangingResult.RangingResultPeerDisconnected, is RangingResult.RangingResultFailure ->
                                throw IllegalStateException("session ended: $r")
                            else -> Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "uwb: controller retry after ${t.javaClass.simpleName}: ${t.message}")
                    delay(RETRY_MS)
                }
            }
        } finally {
            if (sessionId != 0) withContext(NonCancellable) {
                runCatching { send(UwbControl(UwbControl.Type.STOP, sessionId, targetNodeId)) }
            }
        }
    }

    // ---------- helpers ----------

    private suspend fun send(msg: UwbControl) {
        mesh.send(packets.create(PacketKind.CONTROL, msg.encode(), Severity.MEDIUM, ttl = CONTROL_TTL, channel = UwbControl.CHANNEL))
    }

    private fun parameters(sessionId: Int, stsKey: String, channel: UwbComplexChannel, peerHex: String) = RangingParameters(
        uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
        sessionId = sessionId,
        subSessionId = 0,
        sessionKeyInfo = stsKey.hexToBytes(),
        subSessionKeyInfo = null,
        complexChannel = channel,
        peerDevices = listOf(UwbDevice(UwbAddress(peerHex.hexToBytes()))),
        updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
    )

    private companion object {
        const val TAG = "Proxima"
        const val ACCEPT_TIMEOUT_MS = 5_000L
        const val RETRY_MS = 3_000L
        /** UWB only reaches tens of metres, so the handshake never needs many hops. */
        const val CONTROL_TTL = 3
    }
}
