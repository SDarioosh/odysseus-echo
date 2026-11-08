package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the Music page's settings. Stored on the device in their own SharedPreferences
// file (odysseus_music), not sent to Home Assistant. `music.enabled` and `music.stay_while_playing`
// are hub-managed settings (plan contract C7, odysseus/managed/). Backed by Compose state so the
// settings menu and the page see a change at once (settings/PrefStore.kt).

import android.content.Context
import com.msp1974.vacompanion.odysseus.settings.PrefStore

object MusicSettings {
    internal val store = PrefStore("odysseus_music")

    /** C7 key and SharedPreferences key: bool, the Music page (default on). */
    const val KEY_ENABLED = "music.enabled"

    /** C7 key and SharedPreferences key: bool, don't go back to the clock while this room plays (default off). */
    const val KEY_STAY_WHILE_PLAYING = "music.stay_while_playing"

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** The Music page; shown only while Home Assistant is signed in and MA has players. */
    var enabled by store.bool(KEY_ENABLED, true, managed = KEY_ENABLED)

    /** The Music page doesn't go back to the clock while something plays here. */
    var stayWhilePlaying by store.bool(KEY_STAY_WHILE_PLAYING, false, managed = KEY_STAY_WHILE_PLAYING)

    /** "This device's player": an MA media_player entity id; blank = Auto (SelfPlayer.kt). On-device only. */
    var selfOverride by store.string("music_self_override")

    private var lastClientIdPref by store.string("music_last_client_id") { it }

    /**
     * The Sendspin client id (= MA's player id) last seen, so this device still finds its own player
     * while the Sendspin player is off or not started yet. Set by MusicRepository.
     */
    var lastClientId: String
        get() = lastClientIdPref
        set(value) {
            if (value.isBlank() || value == lastClientIdPref) return
            lastClientIdPref = value
        }

    /** The room last selected on the Music page (entity id); blank = none yet. */
    var lastRoom by store.string("music_last_room")
}
