package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: the kiosk settings (Menu → Odysseus → Kiosk). Stored on the device in their own
// SharedPreferences file, not sent to Home Assistant. Backed by Compose state so the settings
// menu shows a change at once (settings/PrefStore.kt). See docs/odysseus/kiosk.md.

import android.content.Context
import com.msp1974.vacompanion.odysseus.settings.PrefStore
import com.msp1974.vacompanion.odysseus.settings.SECONDS_OR_NEVER

object KioskSettings {
    internal val store = PrefStore("odysseus_kiosk")

    const val DEFAULT_RETURN_SECONDS = 600

    /** Choices offered in the settings menu: seconds, 0 = never. */
    val RETURN_CHOICES_SECONDS = listOf(60, 120, 300, 600, 1800, 0)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** Seconds another app may stay in front before the app comes back; 0 = never. */
    var returnSeconds by store.int("kiosk_return_seconds", DEFAULT_RETURN_SECONDS, SECONDS_OR_NEVER, managed = "kiosk.return_seconds")

    /** Reload the dashboard by itself when it fails to load. */
    var autoReload by store.bool("kiosk_auto_reload", true, managed = "kiosk.auto_reload")
}
