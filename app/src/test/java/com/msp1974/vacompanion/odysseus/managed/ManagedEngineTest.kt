package com.msp1974.vacompanion.odysseus.managed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedEngineTest {

    private class MemoryStore : ManagedStore {
        override var version: Long? = null
        override var values: Map<String, Any> = emptyMap()
        override var own: Map<String, Any> = emptyMap()
    }

    /** A fake setting store, as HomeSettings etc. */
    private class FakeSetting(var value: Any) {
        var writes = 0
        fun binding(key: String) = ManagedBinding(key, { value }) { value = it; writes++ }
    }

    private fun reply(version: Long, vararg values: Pair<String, Any>) = ManagedSettingsReply(version, mapOf(*values))

    @Test
    fun aManagedKeyIsAppliedAndTheOwnValueComesBackWhenReleased() {
        val store = MemoryStore()
        val engine = ManagedEngine(store)
        val kiosk = FakeSetting(600)
        engine.register(kiosk.binding("kiosk.return_seconds"))

        engine.accept(reply(1, "kiosk.return_seconds" to 120))
        assertEquals(120, kiosk.value)
        assertTrue(engine.isManaged("kiosk.return_seconds"))
        assertEquals(600, store.own["kiosk.return_seconds"])

        engine.accept(reply(2, "kiosk.return_seconds" to 1800))
        assertEquals(1800, kiosk.value)
        assertEquals(600, store.own["kiosk.return_seconds"]) // the own value is kept from before

        engine.accept(reply(3))
        assertEquals(600, kiosk.value)
        assertFalse(engine.isManaged("kiosk.return_seconds"))
        assertTrue(store.own.isEmpty())
        assertEquals(3L, store.version)
    }

    @Test
    fun theSameAnswerAgainWritesNothing() {
        val engine = ManagedEngine(MemoryStore())
        val home = FakeSetting(true)
        engine.register(home.binding("home.enabled"))
        engine.accept(reply(1, "home.enabled" to false))
        engine.accept(reply(1, "home.enabled" to false))
        assertEquals(1, home.writes)
    }

    @Test
    fun aValueEqualToTheDevicesIsManagedWithoutAWrite() {
        val store = MemoryStore()
        val engine = ManagedEngine(store)
        val home = FakeSetting(true)
        engine.register(home.binding("home.enabled"))
        engine.accept(reply(1, "home.enabled" to true))
        assertEquals(0, home.writes)
        assertTrue(engine.isManaged("home.enabled"))
        engine.accept(reply(2))
        assertEquals(0, home.writes)
        assertTrue(store.own.isEmpty())
    }

    @Test
    fun aKeyWithoutAStoreWaitsForItsRegistration() {
        val store = MemoryStore()
        val engine = ManagedEngine(store)
        engine.accept(reply(1, "followup.seconds" to 5))
        assertTrue(engine.isManaged("followup.seconds"))
        assertNull(store.own["followup.seconds"])

        val followup = FakeSetting(8) // E8's store registers later
        engine.register(followup.binding("followup.seconds"))
        assertEquals(5, followup.value)
        assertEquals(8, store.own["followup.seconds"])
    }

    @Test
    fun aKeyReleasedBeforeItsStoreRegistersIsGivenBackThen() {
        val store = MemoryStore()
        val first = ManagedEngine(store)
        val followup = FakeSetting(8)
        first.register(followup.binding("followup.seconds"))
        first.accept(reply(1, "followup.seconds" to 5))

        // Restart: the hub let go before the store registered again.
        val second = ManagedEngine(store)
        second.accept(reply(2))
        assertEquals(5, followup.value)
        second.register(followup.binding("followup.seconds"))
        assertEquals(8, followup.value)
        assertTrue(store.own.isEmpty())
    }

    @Test
    fun theStateSurvivesARestartAndIsAppliedAgain() {
        val store = MemoryStore()
        val home = FakeSetting(120)
        ManagedEngine(store).apply {
            register(home.binding("home.idle_seconds"))
            accept(reply(4, "home.idle_seconds" to 0))
        }
        home.value = 120 // e.g. the store's file was reset

        val again = ManagedEngine(store)
        again.register(home.binding("home.idle_seconds"))
        again.reapply()
        assertEquals(0, home.value)
        assertEquals(4L, again.version)
        assertEquals(120, store.own["home.idle_seconds"])
    }

    @Test
    fun onlyTheKeysTheHubDroppedAreReleased() {
        val store = MemoryStore()
        val engine = ManagedEngine(store)
        val a = FakeSetting(true)
        val b = FakeSetting(false)
        engine.register(a.binding("home.enabled"))
        engine.register(b.binding("kiosk.auto_reload"))
        engine.accept(reply(1, "home.enabled" to false, "kiosk.auto_reload" to true))
        val diff = engine.accept(reply(2, "kiosk.auto_reload" to true))
        assertEquals(true, a.value)
        assertEquals(true, b.value)
        assertEquals(ManagedDiff(emptySet(), emptySet(), setOf("home.enabled")), diff)
        assertEquals(setOf("kiosk.auto_reload"), engine.managedKeys)
    }

    @Test
    fun ignoredKeysAreLoggedAndAFailingStoreDoesNotStopTheOthers() {
        val logs = mutableListOf<String>()
        val engine = ManagedEngine(MemoryStore()) { logs += it }
        engine.register(ManagedBinding("home.enabled", { true }) { error("store broken") })
        val ok = FakeSetting(true)
        engine.register(ok.binding("kiosk.auto_reload"))
        engine.accept(ManagedSettingsReply(1, mapOf("home.enabled" to false, "kiosk.auto_reload" to false), listOf("x.y: unknown key")))
        assertEquals(false, ok.value)
        assertTrue(logs.any { "x.y: unknown key" in it })
        assertTrue(logs.any { "home.enabled" in it && "store broken" in it })
    }

    @Test
    fun diffNamesWhatWasSetChangedAndReleased() {
        val d = ManagedDiff.of(mapOf("a" to 1, "b" to true), mapOf("a" to 2, "c" to "mute"))
        assertEquals(ManagedDiff(setOf("c"), setOf("a"), setOf("b")), d)
        assertEquals("now set: c; changed: a; back to the device: b", d.toString())
        assertTrue(ManagedDiff.of(mapOf("a" to 1), mapOf("a" to 1)).isEmpty)
    }
}
