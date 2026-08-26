package com.codefall.rain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * Turns the device's gravity vector into a single "which way is down, across the
 * screen" number in -1..1, corrected for display rotation.
 *
 * Registered only while the rain is actually visible — a sensor left listening
 * behind a locked screen is a quiet battery leak.
 */
class TiltSource(context: Context, private val onTilt: (Float) -> Unit) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // TYPE_GRAVITY is already low-pass filtered; the raw accelerometer is the fallback.
    private val sensor: Sensor? = sensorManager?.let {
        it.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private var registered = false

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        if (registered || sensor == null) return
        registered = sensorManager?.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_GAME
        ) ?: false
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        onTilt(0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax = event.values.getOrElse(0) { 0f }
        val ay = event.values.getOrElse(1) { 0f }

        // Sensor axes are fixed to the device, so rotate them into screen space.
        val screenX = when (displayRotation()) {
            Surface.ROTATION_90 -> -ay
            Surface.ROTATION_180 -> -ax
            Surface.ROTATION_270 -> ay
            else -> ax
        }

        // The gravity vector points opposite to "down", so tipping the right edge down
        // gives a negative x. Negate it so positive means "rain should go right".
        onTilt((-screenX / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appContext.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } catch (_: UnsupportedOperationException) {
        // Application context has no display on some older builds.
        Surface.ROTATION_0
    }
}
