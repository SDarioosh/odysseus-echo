package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's settings (Menu → Odysseus → Recording). Stored on the device in
// their own SharedPreferences file, not sent to Home Assistant. Compose state, so a change shows
// at once (settings/PrefStore.kt). The `onSet` hooks run whoever sets them: the settings screen
// or Odysseus (odysseus/managed/).

import android.content.Context
import com.msp1974.vacompanion.odysseus.settings.PrefStore

object RecorderSettings {
    internal val store = PrefStore("odysseus_record")

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context)

    /** Recording allowed at all. Off: the buttons are hidden and presses are refused. */
    var enabled by store.bool("recording_enabled", true, managed = "record.enabled") {
        if (!it) RecorderController.onRecordingDisabled()
        RecordButton.refreshMute()
    }

    /** A small record button on the clock page. */
    var clockButton by store.bool("record_button_on_clock", true, managed = "record.clock_button")

    /**
     * What the Echo Show's privacy button does (RecordButton.kt). Does nothing without the button.
     * Mute by default: the button cuts the microphone in hardware, so a recording it starts is
     * silent (docs/odysseus/echo-show-5.md, "Privacy button").
     */
    var privacyButton: PrivacyButtonMode by store.codec<PrivacyButtonMode>(
        "privacy_button", PrivacyButtonMode.MUTE, { PrivacyButtonMode.fromKey(it) }, { it.key },
        managed = "record.privacy_button", choices = PrivacyButtonMode.entries.map { it.key }.toSet(),
    ) { RecordButton.refreshMute() }

    /** "The privacy button now records" was shown once. */
    var privacyHintShown by store.bool("privacy_button_hint_shown", false)
}
