package app.gymquest.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Measures how far the phone is tilted sideways (roll) from gravity, so the counter can undo the
 * tilt of the picture. That is what lets the phone lean against a bottle or sit on the floor at
 * any angle and still count correctly. The sign convention was checked on paper; confirm on a
 * real phone.
 */
class RollSensor(context: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var fx = 0f
    private var fy = 0f
    private var seeded = false

    @Volatile
    var rollDegrees: Double = 0.0
        private set

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        // Express gravity in the screen's own axes, whatever way the phone is held.
        val (sx, sy) = when (displayRotation()) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        if (!seeded) {
            fx = sx; fy = sy; seeded = true
        } else {
            fx += (sx - fx) * SMOOTHING
            fy += (sy - fy) * SMOOTHING
        }
        // Lying flat: the sideways angle is meaningless, so keep the last good value.
        if (hypot(fx, fy) < MIN_IN_PLANE) return
        rollDegrees = Math.toDegrees(atan2(fx.toDouble(), fy.toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.15f
        const val MIN_IN_PLANE = 2.5f
    }
}
