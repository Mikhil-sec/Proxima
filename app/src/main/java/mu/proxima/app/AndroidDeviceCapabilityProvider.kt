package mu.proxima.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import mu.proxima.core.engine.DeviceCapabilityProvider
import mu.proxima.core.model.DeviceCapabilities
import mu.proxima.core.model.DeviceTier

/** Adaptive hardware tiering. TODO(UI): refine thresholds after testing on all 6 devices. */
class AndroidDeviceCapabilityProvider(private val context: Context) : DeviceCapabilityProvider {
    override fun detect(): DeviceCapabilities {
        val pm = context.packageManager
        val hasUwb = Build.VERSION.SDK_INT >= 31 && pm.hasSystemFeature("android.hardware.uwb")
        val sensors = context.getSystemService(SensorManager::class.java)
        val hasCompass = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val mem = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val ramGb = mem.totalMem / 1_073_741_824.0
        val largeScreen = context.resources.configuration.smallestScreenWidthDp >= 600
        val tier = when {
            largeScreen -> DeviceTier.COMMAND_CENTER
            ramGb >= 7.0 && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> DeviceTier.TIER1_COMPUTE
            else -> DeviceTier.TIER2_SCOUT
        }
        return DeviceCapabilities(tier, "${Build.MANUFACTURER} ${Build.MODEL}", hasUwb, hasCompass, ramGb, largeScreen)
    }
}
