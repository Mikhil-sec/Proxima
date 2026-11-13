package mu.proxima.locate.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * LOCATE P0 device check (debug builds only). Answers: does androidx.core.uwb ranging between two
 * Samsungs report distance AND azimuth? Driven by `locate/tools/uwb_check.sh`, which:
 *  1. opens this screen on both phones with `--es role controller|controlee`,
 *  2. reads each phone's "uwbcheck LOCAL ..." log line (address, channel, capabilities),
 *  3. sends each phone the peer's params via a second `am start` (handled in onNewIntent).
 * Out-of-band exchange over adb is a stand-in: the real engine will exchange the same fields
 * over the mesh as a CONTROL packet on channel "uwb".
 */
class UwbCheckActivity : Activity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView
    private var role = "controlee"
    private var session: UwbClientSessionScope? = null
    private var ranging: Job? = null
    private var pendingStart: Intent? = null
    private var samples = 0
    private var samplesWithAzimuth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.rgb(0x39, 0xFF, 0x14))
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setPadding(40, 80, 40, 40)
        }
        setContentView(text)
        role = intent.getStringExtra(EXTRA_ROLE) ?: "controlee"
        show("UWB check — role=$role\npreparing…")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fail("needs Android 12 (API 31)+")
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)) return fail("no UWB hardware")
        if (checkSelfPermission(Manifest.permission.UWB_RANGING) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.UWB_RANGING), REQ_PERMISSION)
        } else {
            openSession()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openSession()
        else fail("UWB_RANGING permission denied")
    }

    /** Second `am start` from the script carries the peer's params. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasExtra(EXTRA_PEER)) return
        if (session == null) pendingStart = intent else startRanging(intent)
    }

    private fun openSession() = ui.launch {
        try {
            val manager = UwbManager.createInstance(this@UwbCheckActivity)
            if (!manager.isAvailable()) return@launch fail("UWB is OFF: Settings → Connections → More connection settings → Ultra-Wideband (UWB). Also turn off airplane mode.")
            val scope: UwbClientSessionScope =
                if (role == "controller") manager.controllerSessionScope() else manager.controleeSessionScope()
            session = scope
            val caps = scope.rangingCapabilities
            val channel = (scope as? UwbControllerSessionScope)?.uwbComplexChannel
            val line = "uwbcheck LOCAL role=$role addr=${scope.localAddress.address.toHex()}" +
                " channel=${channel?.channel ?: -1} preamble=${channel?.preambleIndex ?: -1}" +
                " dist=${caps.isDistanceSupported} az=${caps.isAzimuthalAngleSupported}" +
                " el=${caps.isElevationAngleSupported} model=${Build.MODEL}"
            Log.i(TAG, line)
            show(
                "UWB check — role=$role  (${Build.MODEL})\n" +
                    "address  ${scope.localAddress.address.toHex()}\n" +
                    "caps     distance=${caps.isDistanceSupported} azimuth=${caps.isAzimuthalAngleSupported} elevation=${caps.isElevationAngleSupported}\n\n" +
                    "waiting for peer params from uwb_check.sh…",
            )
            pendingStart?.let { pendingStart = null; startRanging(it) }
        } catch (t: Throwable) {
            fail("session error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startRanging(start: Intent) {
        val scope = session ?: return
        val peerHex = start.getStringExtra(EXTRA_PEER) ?: return
        val sessionId = start.getIntExtra(EXTRA_SESSION, 0)
        val complexChannel = (scope as? UwbControllerSessionScope)?.uwbComplexChannel
            ?: UwbComplexChannel(start.getIntExtra(EXTRA_CHANNEL, 9), start.getIntExtra(EXTRA_PREAMBLE, 10))
        val params = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = 0,
            sessionKeyInfo = STATIC_STS_KEY,
            subSessionKeyInfo = null,
            complexChannel = complexChannel,
            peerDevices = listOf(UwbDevice(UwbAddress(peerHex.hexToBytes()))),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
        )
        Log.i(TAG, "uwbcheck START role=$role peer=$peerHex session=$sessionId ch=${complexChannel.channel} pre=${complexChannel.preambleIndex}")
        ranging?.cancel()
        ranging = ui.launch {
            try {
                scope.prepareSession(params).collect { result -> onResult(result) }
            } catch (t: Throwable) {
                fail("ranging error: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun onResult(result: RangingResult) {
        when (result) {
            is RangingResult.RangingResultPosition -> {
                samples++
                val p = result.position
                val dist = p.distance?.value
                val az = p.azimuth?.value
                val el = p.elevation?.value
                if (az != null) samplesWithAzimuth++
                Log.i(TAG, "uwbcheck POS n=$samples dist=$dist az=$az el=$el")
                show(
                    "UWB check — role=$role  (${Build.MODEL})\n\n" +
                        "DISTANCE  ${dist?.let { "%.2f m".format(it) } ?: "—"}\n" +
                        "AZIMUTH   ${az?.let { "%+.0f°".format(it) } ?: "— (none)"}\n" +
                        "ELEVATION ${el?.let { "%+.0f°".format(it) } ?: "—"}\n\n" +
                        "samples $samples · with azimuth $samplesWithAzimuth",
                )
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                Log.w(TAG, "uwbcheck PEER_DISCONNECTED reason=${result.reason}")
                show("peer disconnected (reason ${result.reason}) — keep phones within ~10 m, screens on")
            }
            is RangingResult.RangingResultFailure -> fail("ranging failure reason=${result.reason}")
            is RangingResult.RangingResultInitialized -> Log.i(TAG, "uwbcheck INITIALIZED")
        }
    }

    private fun show(msg: String) { text.text = msg }

    private fun fail(msg: String) {
        Log.e(TAG, "uwbcheck FAIL $msg")
        show("UWB check — role=$role\n\nFAILED: $msg")
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Proxima"
        const val REQ_PERMISSION = 42
        const val EXTRA_ROLE = "role"
        const val EXTRA_PEER = "peer"
        const val EXTRA_SESSION = "session"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_PREAMBLE = "preamble"
        /** Static STS key for the device check only (both sides must match). Real engine: per-session random key, sent sealed. */
        val STATIC_STS_KEY = byteArrayOf(0x08, 0x07, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

        fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
        fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
