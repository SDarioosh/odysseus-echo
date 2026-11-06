package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: the clock's automatic night look (Menu → Odysseus → Home screen → Clock style →
// Night look; off by default). Reads the ambient light sensor while the feature is on, decides
// with NightRules, and publishes `active` as Compose state; ClockPage then draws the night style
// and, if chosen, lowers the screen's backlight while the clock shows.

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import kotlinx.coroutines.delay
import timber.log.Timber

object NightMode {
    /** The night look is on now. */
    var active by mutableStateOf(false)
        private set

    /** The last ambient light reading (lux); -1 = none yet. */
    var lux by mutableFloatStateOf(-1f)
        private set

    private var state = NightState()

    private fun evaluate(darkLux: Float) {
        val next = NightRules(darkLux).update(state, lux, SystemClock.elapsedRealtime())
        if (next.active != state.active) {
            Timber.i("Night: %s (%.1f lux, dark at %.0f)", if (next.active) "on" else "off", lux, darkLux)
            active = next.active
        }
        state = next
    }

    private fun reset() {
        state = NightState()
        active = false
    }

    /** Keeps the light sensor read while [on]; composed by HomeHost. */
    @Composable
    fun Driver(on: Boolean) {
        val darkLux = HomeSettings.nightDarkLux
        if (on) LightReadings()
        LaunchedEffect(on, darkLux) {
            if (!on) {
                reset()
                return@LaunchedEffect
            }
            // The sensor only reports changes, so time has to pass on a timer too.
            while (true) {
                evaluate(darkLux)
                delay(2_000)
            }
        }
    }

    /** Listens to the ambient light sensor while composed (also used by the settings' live reading). */
    @Composable
    fun LightReadings() {
        val context = LocalContext.current
        DisposableEffect(context) {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event?.values?.firstOrNull()?.let { lux = it }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            else Timber.w("Night: no ambient light sensor")
            onDispose { manager?.unregisterListener(listener) }
        }
    }

    /**
     * While composed, the window's backlight is at its lowest; the previous window setting comes
     * back on dispose. A window override, not the system setting, so Home Assistant's brightness
     * and auto-brightness are left alone.
     */
    @Composable
    fun DimBacklight() {
        val activity = LocalContext.current.findActivity() ?: return
        DisposableEffect(activity) {
            val window = activity.window
            val before = window.attributes.screenBrightness
            window.attributes = window.attributes.also { it.screenBrightness = NIGHT_BRIGHTNESS }
            onDispose {
                window.attributes = window.attributes.also {
                    it.screenBrightness = if (before == NIGHT_BRIGHTNESS) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else before
                }
            }
        }
    }

    private const val NIGHT_BRIGHTNESS = 0.01f

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
