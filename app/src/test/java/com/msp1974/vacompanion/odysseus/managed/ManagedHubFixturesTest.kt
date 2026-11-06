package com.msp1974.vacompanion.odysseus.managed

import com.msp1974.vacompanion.odysseus.HubFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract C6 against the hub's real GET /devices/{id}/settings answers (HubFixtures). */
class ManagedHubFixturesTest {

    private class MemoryStore : ManagedStore {
        override var version: Long? = null
        override var values: Map<String, Any> = emptyMap()
        override var own: Map<String, Any> = emptyMap()
    }

    private class FakeSetting(var value: Any) {
        fun binding(key: String) = ManagedBinding(key, { value }) { value = it }
    }

    private fun parse(name: String) = ManagedKeys.parse(HubFixtures.load(name))!!

    @Test
    fun everyKeyTheHubSendsIsRead() {
        val reply = parse("c6_settings.json")
        assertEquals(1L, reply.version)
        assertTrue(reply.ignored.isEmpty())
        assertEquals(ManagedKeys.all.keys, reply.values.keys)
        assertEquals(true, reply.values["home.enabled"])
        assertEquals(120, reply.values["home.idle_seconds"])
        assertEquals("mute", reply.values["record.privacy_button"])
        assertEquals(8, reply.values["followup.seconds"])
    }

    @Test
    fun noneAndPartial() {
        val none = parse("c6_settings_none.json")
        assertEquals(0L, none.version)
        assertTrue(none.values.isEmpty())

        val partial = parse("c6_settings_partial.json")
        assertEquals(2L, partial.version)
        assertEquals(mapOf("today.enabled" to false, "kiosk.return_seconds" to 0), partial.values)
    }

    @Test
    fun theEngineAppliesTheHubsChangesAndGivesBack() {
        val store = MemoryStore()
        val engine = ManagedEngine(store)
        val followUp = FakeSetting(5)
        val kiosk = FakeSetting(300)
        engine.register(followUp.binding("followup.seconds"))
        engine.register(kiosk.binding("kiosk.return_seconds"))

        engine.accept(parse("c6_settings.json"))
        assertEquals(8, followUp.value)
        assertEquals(600, kiosk.value)

        // version 2 drops followup.seconds (the admin set it to null) and sets the kiosk to 0
        val diff = engine.accept(parse("c6_settings_partial.json"))
        assertEquals(5, followUp.value)
        assertFalse(engine.isManaged("followup.seconds"))
        assertEquals(0, kiosk.value)
        assertTrue("kiosk.return_seconds" in diff.changed)
        assertTrue("followup.seconds" in diff.released)
        assertEquals(2L, engine.version)
    }
}
