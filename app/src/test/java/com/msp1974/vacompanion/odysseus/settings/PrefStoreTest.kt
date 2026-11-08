package com.msp1974.vacompanion.odysseus.settings

import com.msp1974.vacompanion.odysseus.managed.ManagedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefStoreTest {

    private enum class Mode(val key: String) { A("a"), B("b") }

    private class Fields {
        val store = PrefStore("test")
        val sets = mutableListOf<Boolean>()
        var on by store.bool("on", true, managed = "x.on") { sets += it }
        var secs by store.int("secs", 8, 3..15, managed = "x.secs")
        var lux by store.float("lux", 5f, 0f..1000f)
        var name by store.string("name")
        var mode by store.codec<Mode>("mode", Mode.A, { k -> Mode.entries.firstOrNull { it.key == k } ?: Mode.A }, { it.key },
            managed = "x.mode", choices = setOf("a", "b"))
    }

    @Test
    fun defaultsUntilInitThenTheStoredValues() {
        val f = Fields()
        assertEquals(true, f.on)
        assertEquals(8, f.secs)
        f.store.attach(MemoryPrefs("on" to false, "secs" to 99, "lux" to 20f, "name" to "kitchen", "mode" to "b"))
        assertEquals(false, f.on)
        assertEquals(15, f.secs) // kept in range on load too
        assertEquals(20f, f.lux)
        assertEquals("kitchen", f.name)
        assertEquals(Mode.B, f.mode)
        assertTrue(f.sets.isEmpty()) // loading is no set
    }

    @Test
    fun aSetIsNormalizedPersistedAndHooked() {
        val f = Fields()
        val prefs = MemoryPrefs()
        f.store.attach(prefs)
        f.secs = 1
        f.name = "  hall "
        f.mode = Mode.B
        f.on = false
        assertEquals(3, f.secs)
        assertEquals(mapOf("secs" to 3, "name" to "hall", "mode" to "b", "on" to false), prefs.values)
        assertEquals(listOf(false), f.sets)
    }

    @Test
    fun attachingAgainKeepsTheFirstFile() {
        val f = Fields()
        f.store.attach(MemoryPrefs("secs" to 5))
        f.store.attach(MemoryPrefs("secs" to 10))
        assertEquals(5, f.secs)
    }

    @Test
    fun managedFieldsDeclareTheirKeyTypeAndBinding() {
        val f = Fields()
        f.store.attach(MemoryPrefs())
        val managed = f.store.managed.associateBy { it.key }
        assertEquals(listOf("x.on", "x.secs", "x.mode"), f.store.managed.map { it.key })
        assertTrue(managed.getValue("x.on").spec is ManagedKey.Bool)
        val secs = managed.getValue("x.secs").spec as ManagedKey.IntRange
        assertEquals(3 to 15, secs.min to secs.max)
        assertEquals(setOf("a", "b"), (managed.getValue("x.mode").spec as ManagedKey.Choice).choices)

        // The hub reads and writes the store's value through the same setter, hooks included.
        assertEquals("a", managed.getValue("x.mode").read())
        managed.getValue("x.mode").write("b")
        assertEquals(Mode.B, f.mode)
        managed.getValue("x.on").write(false)
        assertFalse(f.on)
        assertEquals(listOf(false), f.sets)
        assertEquals(8, managed.getValue("x.secs").read())
    }
}
