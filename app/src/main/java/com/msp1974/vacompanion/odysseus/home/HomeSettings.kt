package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the home screen's settings (Menu → Odysseus → Home screen). Stored on the
// device in their own SharedPreferences file, not sent to Home Assistant. Backed by Compose
// state so a change takes effect on screen at once.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import com.msp1974.vacompanion.odysseus.home.clock.ClockPresets
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyle
import com.msp1974.vacompanion.odysseus.home.clock.NightRules
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyleCodec

object HomeSettings {
    private const val PREFS = "odysseus_home"
    private const val KEY_ENABLED = "home_screen_enabled"
    private const val KEY_IDLE_SECONDS = "home_idle_seconds"
    private const val KEY_WEATHER_ENABLED = "home_weather_enabled"
    private const val KEY_WEATHER_ENTITY = "home_weather_entity"
    private const val KEY_TODAY_ENABLED = "home_today_enabled"
    private const val KEY_CLOCK_STYLE = "home_clock_style"
    private const val KEY_NIGHT_AUTO = "home_night_auto"
    private const val KEY_NIGHT_DARK_LUX = "home_night_dark_lux"
    private const val KEY_NIGHT_PRESET = "home_night_preset"
    private const val KEY_NIGHT_DIM = "home_night_dim"
    private const val KEY_OTHER_PLAYERS = "home_other_players_enabled"
    private const val KEY_OTHER_PLAYERS_ENTITY = "home_other_players_entity"

    const val DEFAULT_IDLE_SECONDS = 120

    /** Choices offered in the settings menu: seconds, 0 = never. */
    val IDLE_CHOICES_SECONDS = listOf(60, 120, 300, 600, 0)

    private var appContext: Context? = null

    private var enabledState by mutableStateOf(true)
    private var idleSecondsState by mutableIntStateOf(DEFAULT_IDLE_SECONDS)
    private var weatherEnabledState by mutableStateOf(true)
    private var weatherEntityState by mutableStateOf("")
    private var todayEnabledState by mutableStateOf(true)
    private var clockStyleState by mutableStateOf(ClockStyleCodec.decode(null))
    private var nightAutoState by mutableStateOf(false)
    private var nightDarkLuxState by mutableFloatStateOf(NightRules.DEFAULT_DARK_LUX)
    private var nightPresetState by mutableStateOf(ClockPresets.NIGHTSTAND)
    private var nightDimState by mutableStateOf(true)
    private var otherPlayersState by mutableStateOf(true)
    private var otherPlayersEntityState by mutableStateOf("")

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        enabledState = p.getBoolean(KEY_ENABLED, true)
        idleSecondsState = p.getInt(KEY_IDLE_SECONDS, DEFAULT_IDLE_SECONDS).coerceAtLeast(0)
        weatherEnabledState = p.getBoolean(KEY_WEATHER_ENABLED, true)
        weatherEntityState = p.getString(KEY_WEATHER_ENTITY, "") ?: ""
        todayEnabledState = p.getBoolean(KEY_TODAY_ENABLED, true)
        clockStyleState = ClockStyleCodec.decode(p.getString(KEY_CLOCK_STYLE, null))
        nightAutoState = p.getBoolean(KEY_NIGHT_AUTO, false)
        nightDarkLuxState = p.getFloat(KEY_NIGHT_DARK_LUX, NightRules.DEFAULT_DARK_LUX).coerceIn(0f, 1000f)
        nightPresetState = p.getString(KEY_NIGHT_PRESET, null)?.takeIf { ClockPresets.byId(it) != null } ?: ClockPresets.NIGHTSTAND
        nightDimState = p.getBoolean(KEY_NIGHT_DIM, true)
        otherPlayersState = p.getBoolean(KEY_OTHER_PLAYERS, true)
        otherPlayersEntityState = p.getString(KEY_OTHER_PLAYERS_ENTITY, "") ?: ""
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

    /** The Today page (odysseus/home/today/), right of the clock, while linked to Odysseus. */
    var todayEnabled: Boolean
        get() = todayEnabledState
        set(value) {
            todayEnabledState = value
            prefs()?.edit()?.putBoolean(KEY_TODAY_ENABLED, value)?.apply()
        }

    /** How the clock page looks (home/clock/): a preset, then whatever was changed. Default: Classic. */
    var clockStyle: ClockStyle
        get() = clockStyleState
        set(value) {
            clockStyleState = ClockStyle.normalized(value)
            prefs()?.edit()?.putString(KEY_CLOCK_STYLE, ClockStyleCodec.encode(clockStyleState))?.apply()
        }

    /** Night look: the clock switches to [nightPreset] while the room is dark. Off by default. */
    var nightAuto: Boolean
        get() = nightAutoState
        set(value) {
            nightAutoState = value
            prefs()?.edit()?.putBoolean(KEY_NIGHT_AUTO, value)?.apply()
        }

    /** At or below this many lux the room counts as dark (NightRules). */
    var nightDarkLux: Float
        get() = nightDarkLuxState
        set(value) {
            nightDarkLuxState = value.coerceIn(0f, 1000f)
            prefs()?.edit()?.putFloat(KEY_NIGHT_DARK_LUX, nightDarkLuxState)?.apply()
        }

    /** The preset shown at night. */
    var nightPreset: String
        get() = nightPresetState
        set(value) {
            nightPresetState = value.takeIf { ClockPresets.byId(it) != null } ?: ClockPresets.NIGHTSTAND
            prefs()?.edit()?.putString(KEY_NIGHT_PRESET, nightPresetState)?.apply()
        }

    /** Lower the backlight while the night look shows. */
    var nightDim: Boolean
        get() = nightDimState
        set(value) {
            nightDimState = value
            prefs()?.edit()?.putBoolean(KEY_NIGHT_DIM, value)?.apply()
        }

    /**
     * Music on other speakers (home/players/): while this Echo plays nothing, show and control
     * what another Home Assistant media player plays.
     */
    var otherPlayersEnabled: Boolean
        get() = otherPlayersState
        set(value) {
            otherPlayersState = value
            prefs()?.edit()?.putBoolean(KEY_OTHER_PLAYERS, value)?.apply()
        }

    /** The media player to follow; blank = Auto (this Echo's room first, then the latest to start). */
    var otherPlayersEntity: String
        get() = otherPlayersEntityState
        set(value) {
            otherPlayersEntityState = value.trim()
            prefs()?.edit()?.putString(KEY_OTHER_PLAYERS_ENTITY, otherPlayersEntityState)?.apply()
        }

    fun idleLabel(seconds: Int): String = when {
        seconds <= 0 -> "Never"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
