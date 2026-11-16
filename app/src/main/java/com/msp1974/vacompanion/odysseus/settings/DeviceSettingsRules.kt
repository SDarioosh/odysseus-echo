package com.msp1974.vacompanion.odysseus.settings

// Odysseus fork: which of Home Assistant's settings the device keeps for itself, and who wins when
// the two disagree (docs/odysseus/device-settings.md). Pure; DeviceSettings is the Android side.
//
// Upstream VACA keeps most of its configuration in memory only and waits for Home Assistant to send
// it again after every start (`settings` event, APPConfig.processSettings). Whatever HA doesn't
// send back, or sends back stale, was lost on every restart, and an update is a restart.

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the device keeps: the last value of each setting (HA's units and key), and which it set itself. */
data class KeptSettings(
    val values: Map<String, JsonElement> = emptyMap(),
    /** Keys last changed on the device (its settings screen), not by Home Assistant. */
    val own: Set<String> = emptySet(),
)

object DeviceSettingsRules {
    /**
     * Every key APPConfig.processSettings reads that APPConfig keeps in memory only. The device
     * keeps these. A new key in processSettings must go here, in [NOT_SETTINGS] or in [ON_DEVICE]
     * (DeviceSettingsRulesTest reads Settings.kt and fails otherwise).
     */
    val KEPT: Set<String> = setOf(
        "ha_port", "ha_url", "ha_dashboard",
        "advanced_gain", "wake_word_engine", "wake_word", "wake_word_sound", "alarm_sound",
        "wake_word_threshold", "raw_proximity_threshold", "ducking_volume", "mic_gain", "mute",
        "screen_brightness", "screen_auto_brightness", "screen_always_on", "diagnostics_enabled",
        "zoom_level", "text_size", "screen_on_wake_word", "screen_on_bump", "screen_on_proximity",
        "screen_on_motion", "motion_detection_mode", "motion_detection_sensitivity", "screen_timeout",
        "bump_sensitivity", "screen_saver_disable_on_touch", "screen_orientation_mode",
        "continue_conversation", "quick_actions", "custom_files",
        "rtsp_enabled", "rtsp_port", "rtsp_resolution", "rtsp_fps", "rtsp_bitrate",
        "rtsp_keyframe_interval", "rtsp_username", "rtsp_password", "audio_output", "audio_input",
    )

    /**
     * Keys in the settings event that are the device's current state or HA's version, not
     * configuration: the screen being on or blanked right now, Do Not Disturb and the volumes
     * (Android keeps both itself), the integration's version and the app version it needs.
     */
    val NOT_SETTINGS: Set<String> = setOf(
        "screen_on", "screen_saver", "do_not_disturb",
        "music_volume", "notification_volume", "alarm_volume",
        "integration_version", "min_required_apk_version",
    )

    /** Keys APPConfig already stores in the device's SharedPreferences. */
    val ON_DEVICE: Set<String> = setOf(
        "pipeline_timeout", "area", "duck_scope", "duck_zone", "dark_mode", "enable_network_recovery",
        "suppress_wake_sound", "mic_min_level", "sendspin_enabled", "sendspin_preferred_server",
        "sendspin_overlay_enabled", "wake_word_arbitration", "bluetooth_proxy_enabled",
        "camera_rotation_offset", "camera_mirror", "show_status_bar",
    )

    /** [value] as JSON, for a value the settings screen hands VAViewModel.sendSettingToHA. */
    fun json(value: Any): JsonElement? = when (value) {
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> null
    }

    /** The same value: numbers compare by value (the screen sends 6.0 where HA sends 6). */
    fun same(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == b) return true
        if (a !is JsonPrimitive || b !is JsonPrimitive || a.isString || b.isString) return false
        val x = a.doubleOrNull ?: return false
        return x == b.doubleOrNull
    }

    /** A setting changed on the device: it is kept and is now the device's own. */
    fun fromDevice(kept: KeptSettings, key: String, value: Any): KeptSettings {
        if (key !in KEPT) return kept
        val v = json(value) ?: return kept
        return KeptSettings(kept.values + (key to v), kept.own + key)
    }

    /** What Home Assistant's settings event becomes, what the device keeps, what it tells HA. */
    data class FromHa(
        val settings: JsonObject,
        val kept: KeptSettings,
        /** The device's own values HA had wrong; sent back to HA. */
        val resend: Map<String, JsonElement>,
    )

    /**
     * Home Assistant's settings event. [connecting]: the settings HA sends when it (re)connects or
     * when the device asks for them (APPConfig.initSettings was false), as opposed to a change made
     * in HA while connected.
     *
     * - A value the device set itself wins over HA's when HA is connecting: HA may send back a value
     *   from before the device's change (the device then tells HA its value again).
     * - Any other value from HA wins and is kept. A change made in HA while connected makes the
     *   setting HA's again.
     */
    fun fromHa(kept: KeptSettings, incoming: JsonObject, connecting: Boolean): FromHa {
        val settings = incoming.toMutableMap()
        val values = kept.values.toMutableMap()
        val own = kept.own.toMutableSet()
        val resend = linkedMapOf<String, JsonElement>()
        for ((key, ha) in incoming) {
            if (key !in KEPT || ha is JsonNull) continue
            val mine = values[key]
            when {
                key in own && mine != null && connecting && !same(mine, ha) -> {
                    settings[key] = mine
                    resend[key] = mine
                }
                else -> {
                    values[key] = ha
                    if (!connecting && !same(mine, ha)) own -= key
                }
            }
        }
        return FromHa(JsonObject(settings), KeptSettings(values, own), resend)
    }

    /** The kept values as a settings event, to apply at start. Empty: nothing kept yet. */
    fun restore(kept: KeptSettings): JsonObject = JsonObject(kept.values.filterKeys { it in KEPT })

    fun encode(kept: KeptSettings): String = buildJsonObject {
        put("values", JsonObject(kept.values))
        put("own", JsonArray(kept.own.sorted().map { JsonPrimitive(it) }))
    }.toString()

    fun decode(text: String?): KeptSettings {
        if (text.isNullOrBlank()) return KeptSettings()
        return runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            KeptSettings(
                o["values"]?.jsonObject?.filterKeys { it in KEPT } ?: emptyMap(),
                o["own"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it in KEPT }?.toSet() ?: emptySet(),
            )
        }.getOrDefault(KeptSettings())
    }
}
