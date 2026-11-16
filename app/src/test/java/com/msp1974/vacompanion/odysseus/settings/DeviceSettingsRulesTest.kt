package com.msp1974.vacompanion.odysseus.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceSettingsRulesTest {
    private fun ha(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val rules = DeviceSettingsRules

    @Test
    fun everySettingHomeAssistantSendsIsClassified() {
        val source = listOf(
            File("src/main/java/com/msp1974/vacompanion/settings/Settings.kt"),
            File("app/src/main/java/com/msp1974/vacompanion/settings/Settings.kt"),
        ).first { it.exists() }.readText()
        val read = Regex("""settings\["([a-z_]+)"\]""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue(read.size > 40)
        val classified = rules.KEPT + rules.NOT_SETTINGS + rules.ON_DEVICE
        assertEquals("unclassified keys", emptySet<String>(), read - classified)
        assertEquals("keys processSettings no longer reads", emptySet<String>(), classified - read)
        assertTrue(rules.KEPT.intersect(rules.NOT_SETTINGS).isEmpty())
        assertTrue(rules.KEPT.intersect(rules.ON_DEVICE).isEmpty())
    }

    @Test
    fun aSettingChangedOnTheDeviceIsKeptAsItsOwn() {
        val k = rules.fromDevice(KeptSettings(), "screen_timeout", 600)
        assertEquals(JsonPrimitive(600), k.values["screen_timeout"])
        assertEquals(setOf("screen_timeout"), k.own)
        // Not a setting: ignored.
        assertEquals(k, rules.fromDevice(k, "screen_on", true))
    }

    @Test
    fun homeAssistantsValuesAreKeptButNotItsState() {
        val r = rules.fromHa(KeptSettings(), ha("""{"screen_timeout": 300, "wake_word": "hey_athena", "screen_on": true}"""), connecting = true)
        assertEquals(setOf("screen_timeout", "wake_word"), r.kept.values.keys)
        assertTrue(r.kept.own.isEmpty())
        assertTrue(r.resend.isEmpty())
        assertEquals(ha("""{"screen_timeout": 300, "wake_word": "hey_athena", "screen_on": true}"""), r.settings)
    }

    @Test
    fun onConnectTheDevicesOwnValueWinsAndHomeAssistantIsTold() {
        val kept = rules.fromDevice(KeptSettings(), "screen_timeout", 600)
        val r = rules.fromHa(kept, ha("""{"screen_timeout": 300, "zoom_level": 120}"""), connecting = true)
        assertEquals(JsonPrimitive(600), r.settings["screen_timeout"])
        assertEquals(mapOf("screen_timeout" to JsonPrimitive(600)), r.resend)
        assertEquals(setOf("screen_timeout"), r.kept.own)
        assertEquals(JsonPrimitive(120), r.kept.values["zoom_level"])
    }

    @Test
    fun aChangeMadeInHomeAssistantWhileConnectedWins() {
        val kept = rules.fromDevice(KeptSettings(), "screen_timeout", 600)
        val r = rules.fromHa(kept, ha("""{"screen_timeout": 120}"""), connecting = false)
        assertEquals(JsonPrimitive(120), r.settings["screen_timeout"])
        assertTrue(r.resend.isEmpty())
        assertFalse("screen_timeout" in r.kept.own)
        assertEquals(JsonPrimitive(120), r.kept.values["screen_timeout"])
    }

    @Test
    fun homeAssistantRepeatingTheDevicesValueChangesNothing() {
        val kept = rules.fromDevice(KeptSettings(), "wake_word_threshold", 6.0f)
        val r = rules.fromHa(kept, ha("""{"wake_word_threshold": 6}"""), connecting = false)
        assertTrue(r.resend.isEmpty())
        assertEquals(setOf("wake_word_threshold"), r.kept.own)
    }

    @Test
    fun restoreGivesTheKeptValuesAsASettingsEvent() {
        val kept = rules.fromDevice(rules.fromDevice(KeptSettings(), "screen_timeout", 600), "mute", true)
        assertEquals(ha("""{"screen_timeout": 600, "mute": true}"""), rules.restore(kept))
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val kept = rules.fromHa(
            rules.fromDevice(KeptSettings(), "screen_timeout", 600),
            ha("""{"custom_files": {"microwakeword": []}, "ha_url": "http://ha.local:8123"}"""),
            connecting = true,
        ).kept
        assertEquals(kept, rules.decode(rules.encode(kept)))
        assertEquals(KeptSettings(), rules.decode(null))
        assertEquals(KeptSettings(), rules.decode("not json"))
    }
}
