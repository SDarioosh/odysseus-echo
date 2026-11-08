package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: the follow-up settings (Menu → Odysseus → Voice: "Answer without the wake word"
// and how long it listens). Stored on the device in their own SharedPreferences file, not sent to
// Home Assistant. The SharedPreferences keys are those of the hub-managed settings (plan contract
// C6), so Odysseus can set them (settings/PrefStore.kt, odysseus/managed/).
//
// Backed by Compose state so the settings menu shows a change at once. See docs/odysseus/follow-up.md.

import android.content.Context
import com.msp1974.vacompanion.odysseus.settings.PrefStore

object FollowUpSettings {
    internal val store = PrefStore("odysseus_followup")

    /** C6 key and SharedPreferences key: bool, answer without the wake word. */
    const val KEY_ENABLED = "followup.enabled"

    /** C6 key and SharedPreferences key: int 3..15, how long it listens. */
    const val KEY_SECONDS = "followup.seconds"

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** Listen again by itself after a question Odysseus waits on (default on). */
    var enabled by store.bool(KEY_ENABLED, true, managed = KEY_ENABLED)

    /** How long it listens for the answer, 3..15 s (default 8); out-of-range values are clamped. */
    var seconds by store.int(KEY_SECONDS, FollowUpRules.DEFAULT_SECONDS, FollowUpRules.SECONDS_RANGE, managed = KEY_SECONDS)
}
