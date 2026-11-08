package com.msp1974.vacompanion.odysseus.settings

import com.msp1974.vacompanion.odysseus.followup.FollowUpSettings
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.kiosk.KioskSettings
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import com.msp1974.vacompanion.odysseus.record.PrivacyButtonMode
import com.msp1974.vacompanion.odysseus.record.RecorderSettings
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test

/**
 * Installed devices keep their values: every store reads the SharedPreferences file and keys it
 * always used. The only test that attaches the real stores; it sets back what other tests read.
 */
class StoreKeysTest {
    @After
    fun backToDefaults() {
        HomeSettings.enabled = true
        HomeSettings.todayEnabled = true
        MusicSettings.enabled = true
        MusicSettings.stayWhilePlaying = false
        MusicSettings.selfOverride = ""
        MusicSettings.lastRoom = ""
    }

    @Test
    fun theStoresReadTheirFilesAndKeys() {
        assertEquals(
            listOf("odysseus_home", "odysseus_kiosk", "odysseus_record", "odysseus_followup", "odysseus_music"),
            listOf(HomeSettings.store, KioskSettings.store, RecorderSettings.store, FollowUpSettings.store, MusicSettings.store).map { it.name },
        )

        HomeSettings.store.attach(
            MemoryPrefs(
                "home_screen_enabled" to false, "home_idle_seconds" to 300, "home_weather_enabled" to false,
                "home_weather_entity" to "weather.home", "home_today_enabled" to false, "home_night_auto" to true,
                "home_night_dark_lux" to 20f, "home_night_dim" to false, "home_other_players_enabled" to false,
                "home_other_players_entity" to "media_player.kitchen",
            ),
        )
        with(HomeSettings) {
            assertEquals(listOf(false, false, false, true, false, false), listOf(enabled, weatherEnabled, todayEnabled, nightAuto, nightDim, otherPlayersEnabled))
            assertEquals(300, idleSeconds)
            assertEquals("weather.home", weatherEntity)
            assertEquals(20f, nightDarkLux)
            assertEquals("media_player.kitchen", otherPlayersEntity)
        }

        KioskSettings.store.attach(MemoryPrefs("kiosk_return_seconds" to 60, "kiosk_auto_reload" to false))
        assertEquals(60, KioskSettings.returnSeconds)
        assertEquals(false, KioskSettings.autoReload)

        RecorderSettings.store.attach(
            MemoryPrefs(
                "recording_enabled" to false, "record_button_on_clock" to false, "privacy_button" to "mute",
                "privacy_button_hint_shown" to true,
            ),
        )
        assertEquals(listOf(false, false, true), with(RecorderSettings) { listOf(enabled, clockButton, privacyHintShown) })
        assertEquals(PrivacyButtonMode.MUTE, RecorderSettings.privacyButton)

        FollowUpSettings.store.attach(MemoryPrefs("followup.enabled" to false, "followup.seconds" to 5))
        assertEquals(false, FollowUpSettings.enabled)
        assertEquals(5, FollowUpSettings.seconds)

        MusicSettings.store.attach(
            MemoryPrefs(
                "music.enabled" to false, "music.stay_while_playing" to true, "music_self_override" to "media_player.a",
                "music_last_room" to "media_player.b", "music_last_client_id" to "client-c",
            ),
        )
        with(MusicSettings) {
            assertEquals(listOf(false, true), listOf(enabled, stayWhilePlaying))
            assertEquals(listOf("media_player.a", "media_player.b", "client-c"), listOf(selfOverride, lastRoom, lastClientId))
        }
    }
}
