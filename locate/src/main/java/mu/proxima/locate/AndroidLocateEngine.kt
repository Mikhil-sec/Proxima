package mu.proxima.locate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import java.util.concurrent.atomic.AtomicReference
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.FixSource
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.RadarFix
import mu.proxima.core.model.TrackTarget

/**
 * Real LocateEngine: fused GPS + true-north compass on every phone, RSSI "warmer/colder" fallback.
 * UWB precision mode (P1) plugs into [track] once the device check passes.
 */
class AndroidLocateEngine(
    context: Context,
    private val capabilities: DeviceCapabilities,
    private val mesh: MeshEngine,
    trust: TrustEngine,
    private val scope: CoroutineScope,
) : LocateEngine {

    private val appContext = context.applicationContext
    /** Null on phones without UWB hardware or below Android 12. */
    private val uwb: UwbRanger? =
        if (capabilities.hasUwb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) UwbRanger(context, mesh, trust, scope) else null
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (capabilities.hasUwb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.UWB_RANGING)
    }

    private val _self = MutableStateFlow<GeoPoint?>(null)
    override val selfLocation: StateFlow<GeoPoint?> = _self.asStateFlow()
    private val _heading = MutableStateFlow<Float?>(null)
    override val headingDeg: StateFlow<Float?> = _heading.asStateFlow()

    /** UWB hardware + Android 12+. The user can still have UWB switched off; [track] then falls back to GPS/RSSI. */
    override val uwbAvailable: Boolean = uwb != null

    private var started = false
    private var locationActive = false
    private var waitForPermission: Job? = null
    private var declinationDeg = 0f
    private var declinationFrom: GeoPoint? = null
    private val smoother = HeadingSmoother()
    private val rotation = FloatArray(9)
    /** Debug builds only (no-op otherwise): adb TRACK/STOP/STATUS, see [LocateDebugTrigger]. */
    private val debugTrigger = LocateDebugTrigger(appContext, this, mesh, scope) { compassInfo() }

    /** Rotation-vector heading accuracy estimate (values[4], degrees); null if the phone doesn't report it. */
    @Volatile private var compassAccDeg: Float? = null
    /** Last SensorManager.SENSOR_STATUS_* for the rotation vector; -1 = not reported yet. */
    @Volatile private var compassStatus: Int = -1

    /** For the debug STATUS line: what the phone says about its own compass (to pick a calibration-hint threshold). */
    internal fun compassInfo(): String =
        "compassAcc=${compassAccDeg?.let { "±%.0f°".format(java.util.Locale.US, it) } ?: "?"} compassStatus=${statusName(compassStatus)}"

    // ---------- lifecycle ----------

    override suspend fun start() {
        if (started) return
        started = true
        startHeading()
        uwb?.startResponder() // so a guardian can range to us when we are the finder
        debugTrigger.register()
        if (hasLocationPermission()) startLocation()
        else waitForPermission = scope.launch {
            // The UI asks for permissions once; pick location up as soon as it's granted.
            while (isActive && !hasLocationPermission()) delay(1_000)
            if (isActive) startLocation()
        }
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        waitForPermission?.cancel()
        debugTrigger.unregister()
        sensors.unregisterListener(headingListener)
        if (locationActive) fused.removeLocationUpdates(locationListener)
        locationActive = false
        smoother.reset()
        uwb?.stop()
    }

    // ---------- position ----------

    private val locationListener = LocationListener { loc: Location ->
        val p = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
        _self.value = p
        updateDeclination(p, loc.altitude.toFloat(), loc.time)
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission() before every call
    private fun startLocation() {
        if (locationActive || !hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false) // a rough fix now beats a perfect one later in an emergency
            .build()
        try {
            fused.requestLocationUpdates(request, locationListener, Looper.getMainLooper())
            locationActive = true
            fused.lastLocation.addOnSuccessListener { it?.let(locationListener::onLocationChanged) }
            Log.i(TAG, "locate: fused location started")
        } catch (e: SecurityException) {
            Log.w(TAG, "locate: location permission revoked", e)
        }
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Magnetic → true north. Mauritius is about −20°, so skipping this would point people the wrong way. */
    private fun updateDeclination(p: GeoPoint, altitudeM: Float, timeMs: Long) {
        val last = declinationFrom
        if (last != null && GeoMath.distanceM(last, p) < 5_000) return // varies slowly; recompute every ~5 km
        declinationDeg = GeomagneticField(p.lat.toFloat(), p.lon.toFloat(), altitudeM, timeMs).declination
        declinationFrom = p
        Log.i(TAG, "locate: declination ${"%.1f".format(declinationDeg)}°")
    }

    // ---------- heading ----------

    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // values[4] = estimated heading accuracy in radians, -1 when unavailable (TYPE_ROTATION_VECTOR only).
            compassAccDeg = event.values.getOrNull(4)?.takeIf { it >= 0f }?.let { Math.toDegrees(it.toDouble()).toFloat() }
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val magnetic = RadarMath.headingFromRotationMatrix(rotation) ?: return
            val trueNorth = RadarMath.normalizeDeg(magnetic + declinationDeg)
            _heading.value = smoother.update(trueNorth).toFloat()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            compassStatus = accuracy
            Log.i(TAG, "locate: compass status=${statusName(accuracy)}" +
                if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) " (needs calibration: figure-8)" else "")
        }
    }

    private fun startHeading() {
        if (!capabilities.hasCompass) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sensors.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    // ---------- radar ----------

    /**
     * Fix chain: UWB (reading < 1.5 s old) → GPS (both sides have a fresh fix) → RSSI (target is a
     * mesh peer we can hear) → last GPS fix even if stale. Emits every [TICK_MS] so the arrow follows the user turning.
     */
    override fun track(target: TrackTarget): Flow<RadarFix> = channelFlow {
        val targetFix = AtomicReference<Pair<GeoPoint, Long>?>(null)
        val uwbFix = AtomicReference<UwbReading?>(null)
        launch { target.locations.collect { targetFix.set(it to System.currentTimeMillis()) } }
        uwb?.let { ranger ->
            val azimuth = UwbAzimuthFilter() // one per tracking session; cleans AoA spikes before the arrow sees them
            launch {
                // Only phones that advertise UWB in their mesh beacon can answer. Waiting here keeps us from
                // flooding the mesh with REQUESTs every 3 s for help points, non-UWB phones or nodes not in range yet.
                mesh.peers.first { peers -> peers.any { it.nodeId == target.nodeId && it.hasUwb } }
                ranger.range(target.nodeId).collect { r ->
                    uwbFix.set(r.copy(azimuthDeg = azimuth.update(r.azimuthDeg, r.distanceM, r.atMs)))
                }
            }
        }

        while (isActive) {
            val now = System.currentTimeMillis()
            val me = _self.value
            val tf = targetFix.get()
            val there = tf?.first
            val gpsFresh = me != null && there != null && now - tf.second < GPS_FRESH_MS
            val reading = uwbFix.get()?.takeIf { now - it.atMs < UWB_FRESH_MS }
            val peer = mesh.peers.value.firstOrNull { it.nodeId == target.nodeId }
            val rssi = peer?.rssiDbm?.takeIf { now - peer.lastSeenMs < RSSI_FRESH_MS }

            val fix = when {
                reading != null -> uwbFix(target.nodeId, reading, now)
                gpsFresh -> gpsFix(target.nodeId, me, there, now)
                rssi != null -> RadarFix(
                    targetNodeId = target.nodeId, distanceM = null, bearingDeg = null, relativeBearingDeg = null,
                    source = FixSource.RSSI, proximity = RadarMath.proximityForRssi(rssi), accuracyM = null, atMs = now,
                )
                me != null && there != null -> gpsFix(target.nodeId, me, there, now)
                else -> null // nothing known yet: UI shows "searching"
            }
            if (fix != null) send(fix)
            delay(TICK_MS)
        }
    }

    /** UWB: centimetre distance; arrow only when this phone measures angle of arrival (S23+ yes, S24U no). */
    private fun uwbFix(nodeId: String, r: UwbReading, now: Long): RadarFix {
        val relative = RadarMath.uwbRelativeBearingDeg(r.azimuthDeg, r.distanceM)
        val heading = _heading.value
        return RadarFix(
            targetNodeId = nodeId,
            distanceM = r.distanceM.coerceAtLeast(0.0), // UWB reads slightly negative at contact range
            bearingDeg = if (relative != null && heading != null) RadarMath.normalizeDeg(heading + relative) else null,
            relativeBearingDeg = relative,
            source = FixSource.UWB,
            proximity = RadarMath.proximityForDistance(r.distanceM),
            accuracyM = UWB_ACCURACY_M,
            atMs = now,
        )
    }

    private fun gpsFix(nodeId: String, me: GeoPoint, there: GeoPoint, now: Long): RadarFix {
        val d = GeoMath.distanceM(me, there)
        val b = GeoMath.bearingDeg(me, there)
        return RadarFix(
            targetNodeId = nodeId,
            distanceM = d,
            bearingDeg = b,
            relativeBearingDeg = _heading.value?.let { GeoMath.relativeBearingDeg(b, it.toDouble()) },
            source = FixSource.GPS,
            proximity = RadarMath.proximityForDistance(d),
            accuracyM = RadarMath.combinedAccuracyM(me.accuracyM, there.accuracyM),
            atMs = now,
        )
    }

    private companion object {
        const val TAG = "Proxima"
        fun statusName(s: Int) = when (s) {
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "NO_CONTACT"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            else -> "?"
        }
        const val TICK_MS = 100L
        const val GPS_FRESH_MS = 30_000L
        const val RSSI_FRESH_MS = 10_000L
        const val UWB_FRESH_MS = 1_500L
        /** Measured spread in the 13 Nov device check: ±0.1 m at 2 m, ±0.3 m at 5 m. */
        const val UWB_ACCURACY_M = 0.3
    }
}
