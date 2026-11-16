package com.msp1974.vacompanion.odysseus.wyoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WyomingSilencePolicyTest {

    @Test
    fun `waits the rest of the timeout counted from the last message`() {
        assertEquals(5_000L, WyomingSilencePolicy.readLimitMs(1_000, 1_000, 5_000, false))
        assertEquals(2_000L, WyomingSilencePolicy.readLimitMs(1_000, 4_000, 5_000, false))
    }

    @Test
    fun `gives up at once when the timeout has already passed`() {
        assertEquals(0L, WyomingSilencePolicy.readLimitMs(1_000, 6_000, 5_000, false))
        assertEquals(0L, WyomingSilencePolicy.readLimitMs(1_000, 60_000, 5_000, false))
    }

    @Test
    fun `home assistant's ping cadence never trips the watchdog`() {
        // HA pings 2 s after each pong; even with a slow 1 s pong round trip it is well inside 5 s.
        val gap = 2_000L + 1_000L
        assertTrue(WyomingSilencePolicy.readLimitMs(0, gap, 5_000, false)!! > 0)
    }

    @Test
    fun `no limit while home assistant has paused the satellite`() {
        assertNull(WyomingSilencePolicy.readLimitMs(0, 600_000, 5_000, true))
    }

    @Test
    fun `only pause-satellite makes silence expected`() {
        assertTrue(WyomingSilencePolicy.silenceExpectedAfter("pause-satellite"))
        assertFalse(WyomingSilencePolicy.silenceExpectedAfter("run-satellite"))
        assertFalse(WyomingSilencePolicy.silenceExpectedAfter("ping"))
        assertFalse(WyomingSilencePolicy.silenceExpectedAfter("audio-chunk"))
    }
}
