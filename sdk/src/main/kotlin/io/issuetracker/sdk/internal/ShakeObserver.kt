package io.issuetracker.sdk.internal

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects shake via the accelerometer. Same threshold/debounce as iOS:
 * a typical wrist shake produces peak magnitude around 2.5 g; normal
 * walking stays under ~1.5 g. Debounce prevents one shake firing the
 * handler multiple times.
 *
 * Note: emulator's "Virtual sensors" panel can be used to fake
 * accelerometer input during development.
 */
internal object ShakeObserver : SensorEventListener {
    private const val SHAKE_THRESHOLD_G = 2.5
    private const val DEBOUNCE_MS = 1500L

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var onShake: (() -> Unit)? = null
    private var lastFiredAt = 0L

    fun install(application: Application, onShake: () -> Unit) {
        if (this.onShake != null) return
        this.onShake = onShake
        val sm = application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        if (magnitude >= SHAKE_THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastFiredAt >= DEBOUNCE_MS) {
                lastFiredAt = now
                onShake?.invoke()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
