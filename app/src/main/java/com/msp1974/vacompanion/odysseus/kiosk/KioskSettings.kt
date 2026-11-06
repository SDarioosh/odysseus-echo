package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: the kiosk settings (Menu → Odysseus → Kiosk). Stored on the device in their own
// SharedPreferences file, not sent to Home Assistant. Backed by Compose state so the settings
// menu shows a change at once. See docs/odysseus/kiosk.md.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object KioskSettings {
    private const val PREFS = "odysseus_kiosk"
    private const val KEY_RETURN_SECONDS = "kiosk_return_seconds"
    private const val KEY_AUTO_RELOAD = "kiosk_auto_reload"

    const val DEFAULT_RETURN_SECONDS = 600

    /** Choices offered in the settings menu: seconds, 0 = never. */
    val RETURN_CHOICES_SECONDS = listOf(60, 120, 300, 600, 1800, 0)

    private var appContext: Context? = null

    private var returnSecondsState by mutableIntStateOf(DEFAULT_RETURN_SECONDS)
    private var autoReloadState by mutableStateOf(true)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        returnSecondsState = p.getInt(KEY_RETURN_SECONDS, DEFAULT_RETURN_SECONDS).coerceAtLeast(0)
        autoReloadState = p.getBoolean(KEY_AUTO_RELOAD, true)
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Seconds another app may stay in front before the app comes back; 0 = never. */
    var returnSeconds: Int
        get() = returnSecondsState
        set(value) {
            returnSecondsState = value.coerceAtLeast(0)
            prefs()?.edit()?.putInt(KEY_RETURN_SECONDS, returnSecondsState)?.apply()
        }

    /** Reload the dashboard by itself when it fails to load. */
    var autoReload: Boolean
        get() = autoReloadState
        set(value) {
            autoReloadState = value
            prefs()?.edit()?.putBoolean(KEY_AUTO_RELOAD, value)?.apply()
        }

    fun returnLabel(seconds: Int): String = when {
        seconds <= 0 -> "Never"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
