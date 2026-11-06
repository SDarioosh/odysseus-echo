package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's settings (Menu → Odysseus → Recording). Stored on the device in
// their own SharedPreferences file, not sent to Home Assistant. Compose state, so a change shows
// at once.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object RecorderSettings {
    private const val PREFS = "odysseus_record"
    private const val KEY_ENABLED = "recording_enabled"
    private const val KEY_CLOCK_BUTTON = "record_button_on_clock"

    private var appContext: Context? = null

    private var enabledState by mutableStateOf(true)
    private var clockButtonState by mutableStateOf(true)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        enabledState = p.getBoolean(KEY_ENABLED, true)
        clockButtonState = p.getBoolean(KEY_CLOCK_BUTTON, true)
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Recording allowed at all. Off: the buttons are hidden and presses are refused. */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** A small record button on the clock page. */
    var clockButton: Boolean
        get() = clockButtonState
        set(value) {
            clockButtonState = value
            prefs()?.edit()?.putBoolean(KEY_CLOCK_BUTTON, value)?.apply()
        }
}
