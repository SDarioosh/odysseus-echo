package com.msp1974.vacompanion.odysseus

import com.msp1974.vacompanion.odysseus.SetupAddressWait.Addresses
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAddressWaitTest {

    private val none = Addresses(null, null)
    private val wifi = Addresses(null, "192.168.1.50")
    private val both = Addresses("100.101.102.103", "192.168.1.50")

    /** A fake clock that moves only when the wait sleeps. */
    private class Clock { var ms = 0L; val sleeps = mutableListOf<Long>() }

    private fun runWait(results: List<Addresses>, clock: Clock, giveUpMs: Long = 600_000, isCurrent: () -> Boolean = { true }): Pair<Addresses?, Int> {
        var calls = 0
        val found = runBlocking {
            SetupAddressWait.await(
                probe = { results[minOf(calls++, results.size - 1)] },
                isCurrent = isCurrent,
                giveUpMs = giveUpMs,
                now = { clock.ms },
                sleep = { clock.ms += it; clock.sleeps += it },
            )
        }
        return found to calls
    }

    // The bug: at boot the app looked once, found no address (Wi-Fi not up yet) and stopped.
    @Test
    fun bootWithoutAddressYetKeepsLookingUntilWifiIsUp() {
        val clock = Clock()
        val (found, calls) = runWait(listOf(none, none, none, none, none, wifi), clock)
        assertEquals(wifi, found)
        assertEquals(6, calls)
        assertEquals(5_000L, clock.ms) // found within seconds of Wi-Fi coming up, not minutes
    }

    @Test
    fun addressAlreadyThereNoWait() {
        val clock = Clock()
        val (found, calls) = runWait(listOf(both), clock)
        assertEquals(both, found)
        assertEquals(1, calls)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun looksEverySecondThenEveryThreeSeconds() {
        assertEquals(1_000L, SetupAddressWait.retryDelayMs(0))
        assertEquals(1_000L, SetupAddressWait.retryDelayMs(9))
        assertEquals(3_000L, SetupAddressWait.retryDelayMs(10))
        assertTrue((0..1000).all { SetupAddressWait.retryDelayMs(it) <= 3_000L })
    }

    @Test
    fun givesUpAfterTheSetupTimeout() {
        val clock = Clock()
        val (found, _) = runWait(listOf(none), clock, giveUpMs = 60_000)
        assertNull(found)
        assertTrue(clock.ms in 60_000L..63_000L)
    }

    @Test
    fun stopsWhenSetupIsClosed() {
        val clock = Clock()
        var open = true
        var calls = 0
        val found = runBlocking {
            SetupAddressWait.await(
                probe = { calls++; if (calls == 3) open = false; none },
                isCurrent = { open },
                giveUpMs = 600_000,
                now = { clock.ms },
                sleep = { clock.ms += it },
            )
        }
        assertNull(found)
        assertEquals(3, calls)
    }

    // Tailscale usually connects a few seconds after Wi-Fi.
    @Test
    fun tailscaleTurningUpLaterIsAGain() {
        assertTrue(SetupAddressWait.gained(wifi, both))
        assertTrue(SetupAddressWait.gained(Addresses("100.101.102.103", null), both))
        assertFalse(SetupAddressWait.gained(both, both))
        assertFalse(SetupAddressWait.gained(both, none)) // an address going away doesn't change the QR
        assertFalse(SetupAddressWait.gained(wifi, Addresses(null, "192.168.1.60")))
    }

    @Test
    fun completeOnlyWithBoth() {
        assertTrue(SetupAddressWait.complete(both))
        assertFalse(SetupAddressWait.complete(wifi))
        assertFalse(SetupAddressWait.complete(none))
    }
}
