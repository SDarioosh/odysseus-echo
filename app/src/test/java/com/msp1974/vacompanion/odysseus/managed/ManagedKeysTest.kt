package com.msp1974.vacompanion.odysseus.managed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedKeysTest {

    @Test
    fun readsEveryKeyOfTheContract() {
        val r = ManagedKeys.parse(
            """{"version": 7, "settings": {
                "home.enabled": true, "home.idle_seconds": 0, "home.weather_enabled": false,
                "today.enabled": true, "kiosk.return_seconds": 600, "kiosk.auto_reload": false,
                "record.enabled": true, "record.clock_button": false, "record.privacy_button": "mute",
                "followup.enabled": true, "followup.seconds": 8,
                "music.enabled": false, "music.stay_while_playing": true}}""",
        )!!
        assertEquals(7L, r.version)
        assertEquals(ManagedKeys.all.keys, r.values.keys)
        assertEquals(0, r.values["home.idle_seconds"])
        assertEquals(600, r.values["kiosk.return_seconds"])
        assertEquals("mute", r.values["record.privacy_button"])
        assertEquals(false, r.values["kiosk.auto_reload"])
        assertEquals(false, r.values["music.enabled"])
        assertEquals(true, r.values["music.stay_while_playing"])
        assertTrue(r.ignored.isEmpty())
    }

    @Test
    fun nullAndAbsentKeysAreTheDevicesOwn() {
        val r = ManagedKeys.parse("""{"version": 3, "settings": {"home.enabled": null, "today.enabled": false}}""")!!
        assertEquals(mapOf<String, Any>("today.enabled" to false), r.values)
        assertTrue(r.ignored.isEmpty())
    }

    @Test
    fun unknownKeysAndBadValuesAreIgnoredAndReported() {
        val r = ManagedKeys.parse(
            """{"version": 2, "settings": {
                "screen.brightness": 40,
                "home.enabled": "true",
                "home.idle_seconds": -5,
                "kiosk.return_seconds": 60.5,
                "record.privacy_button": "sleep",
                "followup.seconds": 30,
                "followup.enabled": 1,
                "today.enabled": true}}""",
        )!!
        assertEquals(mapOf<String, Any>("today.enabled" to true), r.values)
        assertEquals(
            setOf(
                "screen.brightness: unknown key", "home.enabled: bad value", "home.idle_seconds: bad value",
                "kiosk.return_seconds: bad value", "record.privacy_button: bad value",
                "followup.seconds: bad value", "followup.enabled: bad value",
            ),
            r.ignored.toSet(),
        )
    }

    @Test
    fun followupSecondsKeepsToThreeToFifteen() {
        fun secs(n: Int) = ManagedKeys.parse("""{"version": 1, "settings": {"followup.seconds": $n}}""")!!.values["followup.seconds"]
        assertNull(secs(2))
        assertEquals(3, secs(3))
        assertEquals(15, secs(15))
        assertNull(secs(16))
    }

    @Test
    fun anAnswerWithoutVersionOrSettingsObjectIsNotOne() {
        assertNull(ManagedKeys.parse("not json"))
        assertNull(ManagedKeys.parse("""{"settings": {}}"""))
        assertNull(ManagedKeys.parse("""{"version": "7", "settings": {}}"""))
        assertNull(ManagedKeys.parse("""{"version": 7, "settings": []}"""))
        assertNull(ManagedKeys.parse("[]"))
    }

    @Test
    fun noSettingsMeansNothingManaged() {
        assertEquals(ManagedSettingsReply(4, emptyMap()), ManagedKeys.parse("""{"version": 4}"""))
        assertEquals(ManagedSettingsReply(5, emptyMap()), ManagedKeys.parse("""{"version": 5, "settings": {}, "extra": 1}"""))
    }

    @Test
    fun pathEncodesTheDeviceId() {
        assertEquals("/devices/abc-123/settings", ManagedKeys.path("abc-123"))
        assertEquals("/devices/a%2Fb%20c/settings", ManagedKeys.path("a/b c"))
    }

    @Test
    fun storedValuesRoundTripWithTheirType() {
        for ((key, value) in listOf("home.enabled" to true, "home.idle_seconds" to 300, "record.privacy_button" to "record")) {
            val spec = ManagedKeys.all.getValue(key)
            assertEquals(value, ManagedKeys.decodeStored(key, spec.toJson(value)))
        }
        assertNull(ManagedKeys.decodeStored("nope", ManagedKeys.all.getValue("home.enabled").toJson(true)))
    }
}
