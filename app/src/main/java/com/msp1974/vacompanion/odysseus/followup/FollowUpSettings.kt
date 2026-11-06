package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: the follow-up settings (Menu → Odysseus → Voice: "Answer without the wake word"
// and how long it listens). Stored on the device in their own SharedPreferences file, not sent to
// Home Assistant. The keys are those of the hub-managed settings (plan contract C6), so Odysseus can
// set them through the same setters (bound in odysseus/managed/ManagedBindings.kt).
//
// Backed by Compose state so the settings menu shows a change at once. See docs/odysseus/follow-up.md.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object FollowUpSettings {
    private const val PREFS = "odysseus_followup"

    /** C6 key and SharedPreferences key: bool, answer without the wake word. */
    const val KEY_ENABLED = "followup.enabled"

    /** C6 key and SharedPreferences key: int 3..15, how long it listens. */
    const val KEY_SECONDS = "followup.seconds"

    private var appContext: Context? = null

    private var enabledState by mutableStateOf(true)
    private var secondsState by mutableIntStateOf(FollowUpRules.DEFAULT_SECONDS)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        enabledState = p.getBoolean(KEY_ENABLED, true)
        secondsState = FollowUpRules.clampSeconds(p.getInt(KEY_SECONDS, FollowUpRules.DEFAULT_SECONDS))
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Listen again by itself after a question Odysseus waits on (default on). */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** How long it listens for the answer, 3..15 s (default 8); out-of-range values are clamped. */
    var seconds: Int
        get() = secondsState
        set(value) {
            secondsState = FollowUpRules.clampSeconds(value)
            prefs()?.edit()?.putInt(KEY_SECONDS, secondsState)?.apply()
        }
}
