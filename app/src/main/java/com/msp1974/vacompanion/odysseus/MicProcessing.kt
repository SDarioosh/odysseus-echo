package com.msp1974.vacompanion.odysseus

// Odysseus fork: whether the app runs its own WebRTC processing (noise suppression, gain control,
// high-pass filter) on the microphone. Off by default. The Echo Show 5's audio front-end (Amazon's
// ASP on its 4-mic array) already removes noise and levels speech, and a second noise suppressor
// and gain control on top of it distort speech and make Whisper mishear
// (docs/odysseus/echo-show-5.md). Read on every frame, so a change applies at once.
// Hooks: audio/MicrophoneInput.kt (readShort), ui/layouts/config/ExperimentalConfig.kt (the
// setting), ui/layouts/config/AudioConfig.kt (the gain control and noise suppression labels).

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.msp1974.vacompanion.audio.AudioEffectSource
import com.msp1974.vacompanion.ui.layouts.config.ConfigItem
import timber.log.Timber

object MicProcessing {
    const val KEY = "odysseus_app_mic_processing"
    const val DEFAULT = false

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var lastLogged: Boolean? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: PreferenceManager.getDefaultSharedPreferences(context.applicationContext).also { prefs = it }

    /** Whether the app's own WebRTC processing runs. Logs each change. */
    fun isOn(context: Context): Boolean {
        val on = prefs(context).getBoolean(KEY, DEFAULT)
        if (lastLogged != on) {
            lastLogged = on
            Timber.i("App microphone processing (WebRTC noise suppression and gain control): ${if (on) "on" else "off, the device's own audio is sent as it is"}")
        }
        return on
    }

    fun set(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY, on) }

    /** The label for a gain control or noise suppression [source] while the app's processing is off, else null. */
    fun offLabel(context: Context, source: String): String? =
        if (source == AudioEffectSource.WEBRTC && !isOn(context)) "Off (device's own)" else null

    /** The setting, under Settings → Experimental. */
    fun toggle(context: Context, group: String) = ConfigItem.Toggle(group, "App microphone processing",
        "Run the app's own noise suppression and gain control on the microphone. Leave it off on an Echo Show: its microphones already remove noise and level your voice, and doing it twice makes speech harder to understand",
        Icons.Default.GraphicEq, key = KEY,
        get = { isOn(context) }, set = { set(context, it) })
}
