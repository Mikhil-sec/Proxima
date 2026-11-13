package mu.proxima.locate

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.RadarFix
import mu.proxima.core.model.TrackTarget

/**
 * DEBUG builds only (runtime-gated on FLAG_DEBUGGABLE, same pattern as MESH's PING): drive the radar over adb
 * without the UI radar screen, so the engine can be proven on phones with simulation OFF.
 *
 *   adb shell am broadcast -a mu.proxima.locate.STATUS -p mu.proxima.app
 *       → logs self GPS fix, heading, uwbAvailable and every mesh peer (node id, rssi, hasUwb)
 *   adb shell am broadcast -a mu.proxima.locate.TRACK -p mu.proxima.app --es node SELF-XXXX
 *       [--es lat -20.4166 --es lon 57.4870]   (fixed target position, to test the GPS path)
 *       [--ei every_ms 250]                    (log throttle, default 250 ms; source changes always log)
 *       → logs `locate fix src=… d=… rel=… brg=… prox=… acc=…`
 *   adb shell am broadcast -a mu.proxima.locate.STOP -p mu.proxima.app
 *
 * Only exists when simulation is OFF (it hangs off [AndroidLocateEngine]). Never logs anything personal:
 * node ids are random mesh ids, positions are this test phone's own.
 */
internal class LocateDebugTrigger(
    private val context: Context,
    private val engine: LocateEngine,
    private val mesh: MeshEngine,
    private val scope: CoroutineScope,
) {
    private var receiver: BroadcastReceiver? = null
    private var tracking: Job? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register() {
        if (receiver != null) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(intent)
        }
        val filter = IntentFilter(ACTION_TRACK).apply { addAction(ACTION_STOP); addAction(ACTION_STATUS) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }
        receiver = r
        Log.i(TAG, "locate debug trigger ready (TRACK / STOP / STATUS)")
    }

    fun unregister() {
        stopTracking("engine stopped")
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> logStatus()
            ACTION_STOP -> stopTracking("adb STOP")
            ACTION_TRACK -> {
                val node = intent.getStringExtra("node")?.trim()
                if (node.isNullOrEmpty()) {
                    Log.w(TAG, "locate TRACK needs --es node <nodeId> (see STATUS for peer ids)")
                    return
                }
                val fixed = parsePoint(intent.getStringExtra("lat"), intent.getStringExtra("lon"))
                val everyMs = intent.getIntExtra("every_ms", DEFAULT_EVERY_MS).coerceAtLeast(0).toLong()
                startTracking(node, fixed, everyMs)
            }
        }
    }

    private fun startTracking(node: String, fixed: GeoPoint?, everyMs: Long) {
        stopTracking("new TRACK")
        val target = TrackTarget(nodeId = node, label = "adb", locations = fixed?.let { flowOf(it) } ?: emptyFlow())
        Log.i(TAG, "locate TRACK node=$node targetGps=${fixed?.let { "${it.lat},${it.lon}" } ?: "none"} uwbAvailable=${engine.uwbAvailable}")
        tracking = scope.launch {
            var lastLogMs = 0L
            var lastSource: Any? = null
            var count = 0
            engine.track(target).collect { fix ->
                count++
                val sourceChanged = fix.source != lastSource
                if (sourceChanged || fix.atMs - lastLogMs >= everyMs) {
                    Log.i(TAG, format(fix, count))
                    lastLogMs = fix.atMs
                    lastSource = fix.source
                }
            }
        }
    }

    private fun stopTracking(why: String) {
        val job = tracking ?: return
        tracking = null
        job.cancel() // cancelling the track() flow also sends the UWB STOP to the target
        Log.i(TAG, "locate TRACK stopped ($why)")
    }

    private fun logStatus() {
        val me = engine.selfLocation.value
        Log.i(
            TAG,
            "locate status self=${me?.let { "%.6f,%.6f ±%s m".format(it.lat, it.lon, it.accuracyM?.let { a -> "%.0f".format(a) } ?: "?") } ?: "none"}" +
                " heading=${engine.headingDeg.value?.let { "%.0f°".format(it) } ?: "none"} uwbAvailable=${engine.uwbAvailable}" +
                " tracking=${tracking?.isActive == true}",
        )
        val peers = mesh.peers.value
        if (peers.isEmpty()) Log.i(TAG, "locate status peers=none (is the mesh started on both phones?)")
        val now = System.currentTimeMillis()
        peers.forEach { p ->
            Log.i(TAG, "locate status peer node=${p.nodeId} rssi=${p.rssiDbm ?: "?"} uwb=${p.hasUwb} direct=${p.direct} seen=${(now - p.lastSeenMs) / 1000}s ago")
        }
    }

    companion object {
        private const val TAG = "Proxima"
        const val ACTION_TRACK = "mu.proxima.locate.TRACK"
        const val ACTION_STOP = "mu.proxima.locate.STOP"
        const val ACTION_STATUS = "mu.proxima.locate.STATUS"
        private const val DEFAULT_EVERY_MS = 250

        /** Pure, unit-tested: one grep-able line per fix. */
        internal fun format(fix: RadarFix, n: Int): String =
            "locate fix #$n src=${fix.source} d=${fix.distanceM?.let { "%.2f".format(it) } ?: "-"}" +
                " rel=${fix.relativeBearingDeg?.let { "%.0f".format(it) } ?: "-"}" +
                " brg=${fix.bearingDeg?.let { "%.0f".format(it) } ?: "-"}" +
                " prox=${fix.proximity} acc=${fix.accuracyM?.let { "%.1f".format(it) } ?: "-"}"

        internal fun parsePoint(lat: String?, lon: String?): GeoPoint? {
            val la = lat?.trim()?.toDoubleOrNull() ?: return null
            val lo = lon?.trim()?.toDoubleOrNull() ?: return null
            if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
            return GeoPoint(la, lo, accuracyM = 5f)
        }
    }
}
