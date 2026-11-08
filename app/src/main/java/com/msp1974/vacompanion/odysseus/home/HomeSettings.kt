package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the home screen's settings (Menu → Odysseus → Home screen). Stored on the
// device in their own SharedPreferences file, not sent to Home Assistant. Backed by Compose
// state so a change takes effect on screen at once (settings/PrefStore.kt).

import android.content.Context
import com.msp1974.vacompanion.odysseus.home.clock.ClockPresets
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyle
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyleCodec
import com.msp1974.vacompanion.odysseus.home.clock.NightRules
import com.msp1974.vacompanion.odysseus.settings.PrefStore
import com.msp1974.vacompanion.odysseus.settings.SECONDS_OR_NEVER

object HomeSettings {
    internal val store = PrefStore("odysseus_home")

    const val DEFAULT_IDLE_SECONDS = 120

    /** Choices offered in the settings menu: seconds, 0 = never. */
    val IDLE_CHOICES_SECONDS = listOf(60, 120, 300, 600, 0)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** Show the clock (and later pages) over the dashboard. Off = upstream behaviour. */
    var enabled by store.bool("home_screen_enabled", true, managed = "home.enabled")

    /** Seconds without a touch before going back to the clock; 0 = never. */
    var idleSeconds by store.int("home_idle_seconds", DEFAULT_IDLE_SECONDS, SECONDS_OR_NEVER, managed = "home.idle_seconds")

    /** Weather in the clock page's top left, read from Home Assistant. */
    var weatherEnabled by store.bool("home_weather_enabled", true, managed = "home.weather_enabled")

    /** The weather entity to show; blank = Auto (the first available weather entity). */
    var weatherEntity by store.string("home_weather_entity")

    /** The Today page (odysseus/home/today/), right of the clock, while linked to Odysseus. */
    var todayEnabled by store.bool("home_today_enabled", true, managed = "today.enabled")

    /** How the clock page looks (home/clock/): a preset, then whatever was changed. Default: Classic. */
    var clockStyle: ClockStyle by store.codec<ClockStyle>(
        "home_clock_style", ClockStyleCodec.decode(null), { ClockStyleCodec.decode(it) }, { ClockStyleCodec.encode(it) },
        normalize = { ClockStyle.normalized(it) },
    )

    /** Night look: the clock switches to [nightPreset] while the room is dark. Off by default. */
    var nightAuto by store.bool("home_night_auto", false)

    /** At or below this many lux the room counts as dark (NightRules). */
    var nightDarkLux by store.float("home_night_dark_lux", NightRules.DEFAULT_DARK_LUX, 0f..1000f)

    /** The preset shown at night. */
    var nightPreset by store.string("home_night_preset", ClockPresets.NIGHTSTAND) {
        it.takeIf { id -> ClockPresets.byId(id) != null } ?: ClockPresets.NIGHTSTAND
    }

    /** Lower the backlight while the night look shows. */
    var nightDim by store.bool("home_night_dim", true)

    /**
     * Music on other speakers (home/players/): while this Echo plays nothing, show and control
     * what another Home Assistant media player plays.
     */
    var otherPlayersEnabled by store.bool("home_other_players_enabled", true)

    /** The media player to follow; blank = Auto (this Echo's room first, then the latest to start). */
    var otherPlayersEntity by store.string("home_other_players_entity")
}
