package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the Music page's settings. Stored on the device in their own SharedPreferences
// file (odysseus_music), not sent to Home Assistant. `music.enabled` and `music.stay_while_playing`
// are hub-managed settings (plan contract C7), bound in odysseus/managed/ManagedBindings.kt.
// Backed by Compose state so the settings menu and the page see a change at once.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object MusicSettings {
    private const val PREFS = "odysseus_music"

    /** C7 key and SharedPreferences key: bool, the Music page (default on). */
    const val KEY_ENABLED = "music.enabled"

    /** C7 key and SharedPreferences key: bool, don't go back to the clock while this room plays (default off). */
    const val KEY_STAY_WHILE_PLAYING = "music.stay_while_playing"

    private const val KEY_SELF_OVERRIDE = "music_self_override"
    private const val KEY_LAST_ROOM = "music_last_room"
    private const val KEY_LAST_CLIENT_ID = "music_last_client_id"

    private var appContext: Context? = null

    private var enabledState by mutableStateOf(true)
    private var stayWhilePlayingState by mutableStateOf(false)
    private var selfOverrideState by mutableStateOf("")
    private var lastRoomState by mutableStateOf("")

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        enabledState = p.getBoolean(KEY_ENABLED, true)
        stayWhilePlayingState = p.getBoolean(KEY_STAY_WHILE_PLAYING, false)
        selfOverrideState = p.getString(KEY_SELF_OVERRIDE, "") ?: ""
        lastRoomState = p.getString(KEY_LAST_ROOM, "") ?: ""
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Music page; shown only while Home Assistant is signed in and MA has players. */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** The Music page doesn't go back to the clock while something plays here. */
    var stayWhilePlaying: Boolean
        get() = stayWhilePlayingState
        set(value) {
            stayWhilePlayingState = value
            prefs()?.edit()?.putBoolean(KEY_STAY_WHILE_PLAYING, value)?.apply()
        }

    /** "This device's player": an MA media_player entity id; blank = Auto (SelfPlayer.kt). On-device only. */
    var selfOverride: String
        get() = selfOverrideState
        set(value) {
            selfOverrideState = value.trim()
            prefs()?.edit()?.putString(KEY_SELF_OVERRIDE, selfOverrideState)?.apply()
        }

    /**
     * The Sendspin client id (= MA's player id) last seen, so this device still finds its own player
     * while the Sendspin player is off or not started yet. Set by MusicRepository.
     */
    var lastClientId: String
        get() = prefs()?.getString(KEY_LAST_CLIENT_ID, "") ?: ""
        set(value) {
            if (value.isBlank() || value == lastClientId) return
            prefs()?.edit()?.putString(KEY_LAST_CLIENT_ID, value)?.apply()
        }

    /** The room last selected on the Music page (entity id); blank = none yet. */
    var lastRoom: String
        get() = lastRoomState
        set(value) {
            lastRoomState = value.trim()
            prefs()?.edit()?.putString(KEY_LAST_ROOM, lastRoomState)?.apply()
        }
}
