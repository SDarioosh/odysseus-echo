package com.msp1974.vacompanion.odysseus.kiosk

import com.msp1974.vacompanion.odysseus.kiosk.ReturnPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnPolicyTest {

    private val minute = 60_000L

    @Test
    fun neverWhenTurnedOff() {
        assertEquals(Action.Never, ReturnPolicy.next(nowMs = 0, hiddenSinceMs = 0, timeoutMs = 0, screenOn = true))
    }

    @Test
    fun waitsOutTheRestOfTheTimeout() {
        val action = ReturnPolicy.next(nowMs = 3 * minute, hiddenSinceMs = 0, timeoutMs = 10 * minute, screenOn = true)
        assertEquals(Action.Wait(7 * minute), action)
    }

    @Test
    fun comesBackOnceTheTimeoutHasPassed() {
        assertEquals(Action.Return, ReturnPolicy.next(nowMs = 10 * minute, hiddenSinceMs = 0, timeoutMs = 10 * minute, screenOn = true))
        assertEquals(Action.Return, ReturnPolicy.next(nowMs = 60 * minute, hiddenSinceMs = 0, timeoutMs = 10 * minute, screenOn = true))
    }

    @Test
    fun waitsWhileTheScreenIsOff() {
        val action = ReturnPolicy.next(nowMs = 20 * minute, hiddenSinceMs = 0, timeoutMs = 10 * minute, screenOn = false)
        assertEquals(Action.Wait(ReturnPolicy.SCREEN_OFF_RECHECK_MS), action)
    }

    @Test
    fun screenOffBeforeTheTimeoutStillWaitsOutTheTimeout() {
        val action = ReturnPolicy.next(nowMs = minute, hiddenSinceMs = 0, timeoutMs = 10 * minute, screenOn = false)
        assertEquals(Action.Wait(9 * minute), action)
    }
}
