package com.msp1974.vacompanion.odysseus.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReloadBackoffTest {

    private val backoff = ReloadBackoff(firstMs = 5_000, maxMs = 300_000)

    private fun next() = backoff.nextDelayMs(failed = true, networkUp = true, enabled = true)

    @Test
    fun noReloadUnlessTheLoadFailed() {
        assertNull(backoff.nextDelayMs(failed = false, networkUp = true, enabled = true))
    }

    @Test
    fun noReloadWhenTurnedOff() {
        assertNull(backoff.nextDelayMs(failed = true, networkUp = true, enabled = false))
    }

    @Test
    fun noReloadWithoutNetwork() {
        assertNull(backoff.nextDelayMs(failed = true, networkUp = false, enabled = true))
    }

    @Test
    fun waitDoublesUpToTheCap() {
        val waits = List(9) { next() }
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L),
            waits,
        )
    }

    @Test
    fun manyFailuresStayAtTheCap() {
        repeat(100) { next() }
        assertEquals(300_000L, next())
    }

    @Test
    fun aLoadedPageStartsAgainFromTheFirstWait() {
        repeat(4) { next() }
        backoff.observe(loaded = true, networkUp = true)
        assertEquals(5_000L, next())
    }

    @Test
    fun networkComingBackStartsAgainFromTheFirstWait() {
        repeat(4) { next() }
        backoff.observe(loaded = false, networkUp = false)
        backoff.observe(loaded = false, networkUp = true)
        assertEquals(5_000L, next())
    }

    @Test
    fun networkStayingUpKeepsTheWaitGrowing() {
        repeat(2) { next() }
        backoff.observe(loaded = false, networkUp = true)
        assertEquals(20_000L, next())
    }

    @Test
    fun showingTheDashboardStartsAgainFromTheFirstWait() {
        repeat(4) { next() }
        backoff.onShown()
        assertEquals(5_000L, next())
    }
}
