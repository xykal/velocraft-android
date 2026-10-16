package io.velocraft.android.controls

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.velocraft.android.bridge.NativeEngineBridge
import kotlin.math.abs

/**
 * High-Frequency Gyroscope Aiming Controller for VeloCraft Pro HUD.
 * Uses Android's hardware gyroscope at SENSOR_DELAY_GAME (200Hz+) to feed sub-pixel
 * camera deltas into the lock-free C++ input queue for precision PvP tracking and bow aiming.
 */
class GyroAimController(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    var sensitivityX: Float = 14.5f
    var sensitivityY: Float = 11.0f
    var invertY: Boolean = false
    private val deadzoneRadPerSec: Float = 0.012f
    private var lastTimestampNs: Long = 0L

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun start() {
        if (!isEnabled || gyroSensor == null) return
        lastTimestampNs = 0L
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastTimestampNs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled || event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        if (lastTimestampNs != 0L) {
            val dtSec = (event.timestamp - lastTimestampNs) * 1e-9f
            if (dtSec in 0.0005f..0.1f) {
                // In landscape orientation:
                // axisX (event.values[0]) = pitch/yaw depending on landscape rotation
                // axisY (event.values[1]) = pitch
                val rawYaw = -event.values[0]
                val rawPitch = if (invertY) -event.values[1] else event.values[1]

                val filteredYaw = if (abs(rawYaw) < deadzoneRadPerSec) 0f else rawYaw
                val filteredPitch = if (abs(rawPitch) < deadzoneRadPerSec) 0f else rawPitch

                if (filteredYaw != 0f || filteredPitch != 0f) {
                    val deltaX = filteredYaw * sensitivityX * 100f * dtSec
                    val deltaY = filteredPitch * sensitivityY * 100f * dtSec
                    NativeEngineBridge.sendCameraDelta(deltaX, deltaY)
                }
            }
        }
        lastTimestampNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
