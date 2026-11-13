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

/**
 * Adaptive hardware tiering. RAM alone is not enough: the Honor X8b reports 7.5 GB but has a Snapdragon 680,
 * far too slow for an on-device LLM. Tier 1 also needs a flagship-class SoC (UWB is a good proxy on our devices).
 */
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
            ramGb >= 7.0 && flagshipSoc(hasUwb) && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> DeviceTier.TIER1_COMPUTE
            else -> DeviceTier.TIER2_SCOUT
        }
        return DeviceCapabilities(tier, "${Build.MANUFACTURER} ${Build.MODEL}", hasUwb, hasCompass, ramGb, largeScreen)
    }

    /** Snapdragon 8-series (SM8xxx), Exynos 2xxx (s5e9xxx), Google Tensor, or any phone with UWB. */
    private fun flagshipSoc(hasUwb: Boolean): Boolean {
        if (hasUwb) return true
        if (Build.VERSION.SDK_INT < 31) return false
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".lowercase()
        return Regex("""sm8\d{3}|s5e9\d{3}|tensor|gs\d{3}""").containsMatchIn(soc)
    }
}
