package com.msp1974.vacompanion.odysseus.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class HaBackoffTest {
    @Test
    fun doublesUpToThirtySecondsAndResets() {
        val b = HaBackoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.nextDelayMs() })
        repeat(100) { b.nextDelayMs() }
        assertEquals(30_000L, b.nextDelayMs())
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }
}
