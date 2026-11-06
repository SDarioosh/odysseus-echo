package com.msp1974.vacompanion.odysseus.home.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesTest {
    private val rules = NightRules(darkLux = 5f)

    private fun run(start: NightState, vararg readings: Pair<Long, Float>): NightState =
        readings.fold(start) { s, (t, lux) -> rules.update(s, lux, t) }

    @Test
    fun `light level for coming back is well above dark`() {
        assertEquals(15f, NightRules(5f).lightLux)
        assertEquals(10f, NightRules(2f).lightLux)
        assertEquals(60f, NightRules(20f).lightLux)
    }

    @Test
    fun `turns on only after a full minute of dark`() {
        val s = run(NightState(), 0L to 3f, 30_000L to 2f, 59_999L to 4f)
        assertFalse(s.active)
        assertTrue(rules.update(s, 4f, 60_000L).active)
    }

    @Test
    fun `a moment of light restarts the wait`() {
        val s = run(NightState(), 0L to 3f, 40_000L to 50f, 50_000L to 3f, 100_000L to 3f)
        assertFalse(s.active)
        assertTrue(rules.update(s, 3f, 110_000L).active)
    }

    @Test
    fun `stays on between dark and light levels`() {
        val on = NightState(active = true)
        val s = run(on, 0L to 10f, 60_000L to 14f, 600_000L to 9f)
        assertTrue(s.active)
        assertNull(s.pendingSinceMs)
    }

    @Test
    fun `turns off after ten seconds of light`() {
        val on = NightState(active = true)
        val s = run(on, 0L to 200f, 9_999L to 200f)
        assertTrue(s.active)
        assertFalse(rules.update(s, 200f, 10_000L).active)
    }

    @Test
    fun `no reading changes nothing`() {
        assertEquals(NightState(), rules.update(NightState(), -1f, 100_000L))
        assertTrue(rules.update(NightState(active = true), Float.NaN, 100_000L).active)
    }

    @Test
    fun `exactly the dark level counts as dark`() {
        assertTrue(run(NightState(), 0L to 5f, 60_000L to 5f).active)
    }
}
