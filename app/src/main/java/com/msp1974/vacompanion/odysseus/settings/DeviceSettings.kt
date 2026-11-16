package com.msp1974.vacompanion.odysseus.settings

// Odysseus fork: the device keeps its own configuration (docs/odysseus/device-settings.md), so a
// restart or an update starts with the settings it had, not upstream's defaults until Home
// Assistant sends them again. The rules are DeviceSettingsRules; this is the file and the hooks:
//
// - device/DeviceManager.kt: restore(), right after APPConfig is made, before anything listens.
// - ui/VAViewModel.kt sendSettingToHA: fromDevice(), a setting changed on the device.
// - satellite/Satellite.kt handleSettings (through odysseus/OdysseusSatellite.kt): fromHa().

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

object DeviceSettings {
    private const val FILE = "odysseus_device_settings"
    private const val KEY = "kept"

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var kept = KeptSettings()

    /** Loads the kept settings into [config]. Before anything listens to it, so nothing reacts twice. */
    fun restore(context: Context, config: APPConfig) {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val saved = synchronized(lock) {
            prefs = p
            kept = DeviceSettingsRules.decode(p.getString(KEY, null))
            kept
        }
        val settings = DeviceSettingsRules.restore(saved)
        if (settings.isEmpty()) {
            Timber.i("Device settings: none kept yet")
            return
        }
        // processSettings marks HA's settings as received; these aren't HA's, so it stays as it was.
        val received = config.initSettings
        runCatching { config.processSettings(settings.toString()) }
            .onFailure { Timber.w("Device settings: could not restore: ${it.message}") }
        config.initSettings = received
        Timber.i("Device settings: restored ${settings.size} (${saved.own.size} set on the device)")
    }

    /** A setting changed on the device's settings screen ([value] in HA's units). */
    fun fromDevice(key: String, value: Any) = synchronized(lock) {
        save(DeviceSettingsRules.fromDevice(kept, key, value))
    }

    /** Home Assistant's settings event, before APPConfig applies it: what APPConfig should apply. */
    fun fromHa(settings: String, config: APPConfig): String {
        val incoming = runCatching { Json.parseToJsonElement(settings).jsonObject }.getOrNull() ?: return settings
        val result = synchronized(lock) {
            if (prefs == null) return settings
            DeviceSettingsRules.fromHa(kept, incoming, connecting = !config.initSettings).also { save(it.kept) }
        }
        if (result.resend.isNotEmpty()) {
            Timber.i("Device settings: kept the device's own ${result.resend.keys} over Home Assistant's; telling HA")
            result.resend.forEach { (k, v) -> config.pendingHaSettings[k] = v }
            config.eventBroadcaster.notifyEvent(Event("sendSettingToHA", "", ""))
        }
        return result.settings.toString()
    }

    private fun save(next: KeptSettings) {
        if (next == kept) return
        kept = next
        prefs?.edit { putString(KEY, DeviceSettingsRules.encode(next)) }
    }
}
