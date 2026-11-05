package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the home screen's settings (Menu → Odysseus → Home screen). Stored on the
// device in their own SharedPreferences file, not sent to Home Assistant. Backed by Compose
// state so a change takes effect on screen at once.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeSettings {
    private const val PREFS = "odysseus_home"
    private const val KEY_ENABLED = "home_screen_enabled"
    private const val KEY_IDLE_SECONDS = "home_idle_seconds"
    private const val KEY_WEATHER_ENABLED = "home_weather_enabled"
    private const val KEY_WEATHER_ENTITY = "home_weather_entity"

    const val DEFAULT_IDLE_SECONDS = 120

    /** Choices offered in the settings menu: seconds, 0 = never. */
    val IDLE_CHOICES_SECONDS = listOf(60, 120, 300, 600, 0)

    private var appContext: Context? = null

    private var enabledState by mutableStateOf(true)
    private var idleSecondsState by mutableIntStateOf(DEFAULT_IDLE_SECONDS)
    private var weatherEnabledState by mutableStateOf(true)
    private var weatherEntityState by mutableStateOf("")

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        enabledState = p.getBoolean(KEY_ENABLED, true)
        idleSecondsState = p.getInt(KEY_IDLE_SECONDS, DEFAULT_IDLE_SECONDS).coerceAtLeast(0)
        weatherEnabledState = p.getBoolean(KEY_WEATHER_ENABLED, true)
        weatherEntityState = p.getString(KEY_WEATHER_ENTITY, "") ?: ""
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Show the clock (and later pages) over the dashboard. Off = upstream behaviour. */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** Seconds without a touch before going back to the clock; 0 = never. */
    var idleSeconds: Int
        get() = idleSecondsState
        set(value) {
            idleSecondsState = value.coerceAtLeast(0)
            prefs()?.edit()?.putInt(KEY_IDLE_SECONDS, idleSecondsState)?.apply()
        }

    /** Weather in the clock page's top left, read from Home Assistant. */
    var weatherEnabled: Boolean
        get() = weatherEnabledState
        set(value) {
            weatherEnabledState = value
            prefs()?.edit()?.putBoolean(KEY_WEATHER_ENABLED, value)?.apply()
        }

    /** The weather entity to show; blank = Auto (the first available weather entity). */
    var weatherEntity: String
        get() = weatherEntityState
        set(value) {
            weatherEntityState = value.trim()
            prefs()?.edit()?.putString(KEY_WEATHER_ENTITY, weatherEntityState)?.apply()
        }

    fun idleLabel(seconds: Int): String = when {
        seconds <= 0 -> "Never"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
