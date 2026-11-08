package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: the stores whose settings Odysseus may set (C6). Each store declares its managed
// fields itself (`managed = "<key>"` in settings/PrefStore.kt); their key, type and range make
// ManagedKeys.all, and their setter is the one the settings screen uses, so a managed value
// behaves as if the owner had chosen it on the device (docs/odysseus/managed-settings.md).

import android.content.Context
import com.msp1974.vacompanion.odysseus.followup.FollowUpSettings
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.kiosk.KioskSettings
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import com.msp1974.vacompanion.odysseus.record.RecorderSettings

object ManagedBindings {
    /** Every store with a managed field. A new such store is added here. */
    private val stores
        get() = listOf(HomeSettings.store, KioskSettings.store, RecorderSettings.store, FollowUpSettings.store, MusicSettings.store)

    /** Every managed setting, in declaration order. */
    val all: List<ManagedSetting> by lazy { stores.flatMap { it.managed } }

    fun registerBuiltIns(context: Context) {
        stores.forEach { it.init(context) }
        all.forEach { ManagedSettings.register(it.key, it.read, it.write) }
    }
}
