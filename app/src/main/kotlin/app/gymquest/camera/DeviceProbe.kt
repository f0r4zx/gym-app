package app.gymquest.camera

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.gymquest.core.platform.DeviceState
import app.gymquest.core.platform.ThermalState

/** Reads battery, heat and memory so the app can lower its own workload before the phone struggles. */
object DeviceProbe {
    fun read(context: Context, avgFrameMs: Double): DeviceState {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN,
                -> ThermalState.SEVERE
                else -> ThermalState.NORMAL
            }
        } else {
            ThermalState.NORMAL
        }
        return DeviceState(
            batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100,
            isCharging = battery?.isCharging ?: false,
            powerSaver = power?.isPowerSaveMode ?: false,
            thermal = thermal,
            avgFrameMs = avgFrameMs,
            freeMemoryMb = (info.availMem / (1024 * 1024)).toInt(),
        )
    }
}
