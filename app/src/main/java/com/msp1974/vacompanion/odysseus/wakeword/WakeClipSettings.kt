package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: the wake word training settings (Menu → Odysseus → Wake word training). Stored on
// the device in their own SharedPreferences file, not sent to Home Assistant and not managed by
// Odysseus (settings/PrefStore.kt). See docs/odysseus/wake-word-training.md.

import android.content.Context
import com.msp1974.vacompanion.odysseus.settings.PrefStore

object WakeClipSettings {
    internal val store = PrefStore("odysseus_wakeword")

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** Keep a near miss followed by a wake (and that wake) as training clips. Off by default. */
    var catchMisses by store.bool("catch_misses", false) { if (!it) WakeClips.forgetHeldAudio() }

    /** Clips the hub has taken from this device, ever. */
    var sentTotal by store.int("sent_total", 0, 0..Int.MAX_VALUE)
}
