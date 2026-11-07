package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: the device settings Odysseus may set (C6), bound to their stores. Each setter is
// the one the settings screen uses, so a managed value behaves as if the owner had chosen it on
// the device. A setting built later registers its own keys the same way (docs/odysseus/managed-settings.md).

import android.content.Context
import com.msp1974.vacompanion.odysseus.followup.FollowUpSettings
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.kiosk.KioskSettings
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import com.msp1974.vacompanion.odysseus.record.PrivacyButtonMode
import com.msp1974.vacompanion.odysseus.record.RecordButton
import com.msp1974.vacompanion.odysseus.record.RecorderController
import com.msp1974.vacompanion.odysseus.record.RecorderSettings

object ManagedBindings {
    fun registerBuiltIns(context: Context) {
        HomeSettings.init(context)
        KioskSettings.init(context)
        RecorderSettings.init(context)
        FollowUpSettings.init(context)
        MusicSettings.init(context)

        ManagedSettings.registerBool("home.enabled", { HomeSettings.enabled }) { HomeSettings.enabled = it }
        ManagedSettings.registerInt("home.idle_seconds", { HomeSettings.idleSeconds }) { HomeSettings.idleSeconds = it }
        ManagedSettings.registerBool("home.weather_enabled", { HomeSettings.weatherEnabled }) { HomeSettings.weatherEnabled = it }
        ManagedSettings.registerBool("today.enabled", { HomeSettings.todayEnabled }) { HomeSettings.todayEnabled = it }

        ManagedSettings.registerInt("kiosk.return_seconds", { KioskSettings.returnSeconds }) { KioskSettings.returnSeconds = it }
        ManagedSettings.registerBool("kiosk.auto_reload", { KioskSettings.autoReload }) { KioskSettings.autoReload = it }

        // As the Recording section does when the owner flips these.
        ManagedSettings.registerBool("record.enabled", { RecorderSettings.enabled }) {
            RecorderSettings.enabled = it
            if (!it) RecorderController.onRecordingDisabled()
            RecordButton.refreshMute()
        }
        ManagedSettings.registerBool("record.clock_button", { RecorderSettings.clockButton }) { RecorderSettings.clockButton = it }
        ManagedSettings.register("record.privacy_button", { RecorderSettings.privacyButton.key }) {
            RecorderSettings.privacyButton = PrivacyButtonMode.fromKey(it as String)
            RecordButton.refreshMute()
        }
        ManagedSettings.registerBool(FollowUpSettings.KEY_ENABLED, { FollowUpSettings.enabled }) { FollowUpSettings.enabled = it }
        ManagedSettings.registerInt(FollowUpSettings.KEY_SECONDS, { FollowUpSettings.seconds }) { FollowUpSettings.seconds = it }

        // C7: the Music page (odysseus/music/).
        ManagedSettings.registerBool(MusicSettings.KEY_ENABLED, { MusicSettings.enabled }) { MusicSettings.enabled = it }
        ManagedSettings.registerBool(MusicSettings.KEY_STAY_WHILE_PLAYING, { MusicSettings.stayWhilePlaying }) { MusicSettings.stayWhilePlaying = it }
    }
}
