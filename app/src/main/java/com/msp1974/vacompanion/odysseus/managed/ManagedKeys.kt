package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: the settings Odysseus may set on this device (plan contract C6), and how its
// answer is read. Pure, unit-tested (docs/odysseus/managed-settings.md).
//
//   GET <base>/devices/{device_id}/settings
//   -> {"version": 7, "settings": {"kiosk.return_seconds": 600, "today.enabled": true, ...}}
//
// A key that is absent or null is the device's own. Unknown keys and values of the wrong type or
// out of range are ignored (and logged by the caller): the device keeps its own value for them.
// Values are plain Kotlin: Boolean, Int or String.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One key of C6's table: its type and the values it accepts. */
sealed class ManagedKey(val key: String) {
    /** The value if [element] is a valid one for this key, else null. */
    abstract fun read(element: JsonElement): Any?

    /** The value as JSON, for the device's own store of applied values. */
    fun toJson(value: Any): JsonPrimitive = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    class Bool(key: String) : ManagedKey(key) {
        override fun read(element: JsonElement): Any? =
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }

    class IntRange(key: String, val min: Int, val max: Int) : ManagedKey(key) {
        override fun read(element: JsonElement): Any? {
            val p = (element as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
            val n = p.longOrNull ?: return null
            return if (n in min..max) n.toInt() else null
        }
    }

    class Choice(key: String, val choices: Set<String>) : ManagedKey(key) {
        override fun read(element: JsonElement): Any? =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in choices }
    }
}

/** A settings answer: the hub's version, the valid values, and what was ignored ("key: reason"). */
data class ManagedSettingsReply(
    val version: Long,
    val values: Map<String, Any>,
    val ignored: List<String> = emptyList(),
)

object ManagedKeys {
    /** "0 = never" values: up to a day. */
    private const val MAX_SECONDS = 86_400

    val all: Map<String, ManagedKey> = listOf(
        ManagedKey.Bool("home.enabled"),
        ManagedKey.IntRange("home.idle_seconds", 0, MAX_SECONDS),
        ManagedKey.Bool("home.weather_enabled"),
        ManagedKey.Bool("today.enabled"),
        ManagedKey.IntRange("kiosk.return_seconds", 0, MAX_SECONDS),
        ManagedKey.Bool("kiosk.auto_reload"),
        ManagedKey.Bool("record.enabled"),
        ManagedKey.Bool("record.clock_button"),
        ManagedKey.Choice("record.privacy_button", setOf("record", "mute")),
        ManagedKey.Bool("followup.enabled"),
        ManagedKey.IntRange("followup.seconds", 3, 15),
    ).associateBy { it.key }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun path(deviceId: String): String =
        "/devices/${java.net.URLEncoder.encode(deviceId, "UTF-8").replace("+", "%20")}/settings"

    /** Reads the hub's answer; null when it isn't one (no object, no `version`, no `settings` object). */
    fun parse(body: String): ManagedSettingsReply? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val settings = when (val s = root["settings"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> s
            else -> return null
        }
        val values = linkedMapOf<String, Any>()
        val ignored = mutableListOf<String>()
        for ((key, element) in settings) {
            if (element is JsonNull) continue // the device's own
            val spec = all[key]
            if (spec == null) {
                ignored += "$key: unknown key"
                continue
            }
            val value = spec.read(element)
            if (value == null) ignored += "$key: bad value" else values[key] = value
        }
        return ManagedSettingsReply(version, values, ignored)
    }

    /** A stored value read back with its key's type; null when the key or the value no longer fits. */
    fun decodeStored(key: String, element: JsonElement): Any? = all[key]?.read(element)
}
