package com.msp1974.vacompanion.odysseus.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LockRulesTest {

    private val zone = ZoneId.of("America/New_York")
    private val s = LockSchedule(LockRules.DEFAULT_OPEN_MINUTE, LockRules.DEFAULT_CLOSE_MINUTE, LockRules.WEEKDAYS)

    // 2026-11-02 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test
    fun offNeverLocks() {
        assertEquals(LockDecision(false, null), LockRules.decide(false, s, LockOverrides(), at(7, 23)))
    }

    @Test
    fun openOnWeekdaysFromEightToSix() {
        assertTrue(LockRules.isOpenHours(s, at(2, 8, 0)))
        assertTrue(LockRules.isOpenHours(s, at(6, 17, 59)))
        assertFalse(LockRules.isOpenHours(s, at(2, 7, 59)))
        assertFalse(LockRules.isOpenHours(s, at(2, 18, 0)))
        assertFalse(LockRules.isOpenHours(s, at(7, 12))) // Saturday
        assertFalse(LockRules.isOpenHours(s, at(8, 12))) // Sunday
    }

    @Test
    fun lockedInTheEveningUntilTheNextMorning() {
        val d = LockRules.decide(true, s, LockOverrides(), at(2, 19))
        assertEquals(LockDecision(true, ms(at(3, 8))), d)
    }

    @Test
    fun unlockedDuringTheDayUntilSix() {
        assertEquals(LockDecision(false, ms(at(2, 18))), LockRules.decide(true, s, LockOverrides(), at(2, 10)))
    }

    @Test
    fun lockedAllWeekendUntilMonday() {
        assertEquals(LockDecision(true, ms(at(9, 8))), LockRules.decide(true, s, LockOverrides(), at(6, 18)))
        assertEquals(LockDecision(true, ms(at(9, 8))), LockRules.decide(true, s, LockOverrides(), at(8, 12)))
    }

    @Test
    fun pinUnlockHoldsUntilTheNextScheduledLock() {
        // Monday 20:00: the next lock time is Tuesday 18:00.
        val o = LockRules.unlock(s, at(2, 20))
        assertEquals(ms(at(3, 18)), o.unlockedUntilMs)
        assertFalse(LockRules.decide(true, s, o, at(3, 7)).locked)
        assertTrue(LockRules.decide(true, s, o, at(3, 18)).locked)
    }

    @Test
    fun pinUnlockOnTheWeekendHoldsUntilMondayEvening() {
        val o = LockRules.unlock(s, at(7, 10))
        assertEquals(ms(at(9, 18)), o.unlockedUntilMs)
    }

    @Test
    fun lockNowHoldsUntilTheNextOpening() {
        // Leaving early on Tuesday at 16:00: locked until Wednesday 08:00.
        val o = LockRules.lock(s, at(3, 16))
        assertEquals(ms(at(4, 8)), o.lockedUntilMs)
        assertTrue(LockRules.decide(true, s, o, at(3, 17)).locked)
        assertFalse(LockRules.decide(true, s, o, at(4, 8)).locked)
    }

    @Test
    fun lockNowOnFridayHoldsUntilMonday() {
        assertEquals(ms(at(9, 8)), LockRules.lock(s, at(6, 12)).lockedUntilMs)
    }

    @Test
    fun aLockReplacesAnUnlockAndTheOtherWayRound() {
        val unlocked = LockRules.unlock(s, at(2, 20))
        val locked = LockRules.lock(s, at(2, 21))
        assertEquals(0L, locked.unlockedUntilMs)
        assertTrue(LockRules.decide(true, s, locked, at(2, 22)).locked)
        assertEquals(0L, unlocked.lockedUntilMs)
    }

    @Test
    fun expiredOverridesAreDropped() {
        val o = LockOverrides(unlockedUntilMs = 1_000, lockedUntilMs = 5_000)
        assertEquals(LockOverrides(0, 5_000), LockRules.expire(o, 1_000))
        assertEquals(LockOverrides(0, 0), LockRules.expire(o, 5_000))
    }

    @Test
    fun noDaysMeansLockedWithNoEnd() {
        val none = s.copy(days = emptySet())
        assertEquals(LockDecision(true, null), LockRules.decide(true, none, LockOverrides(), at(2, 10)))
        assertEquals(Long.MAX_VALUE, LockRules.unlock(none, at(2, 10)).unlockedUntilMs)
        assertEquals(LockDecision(false, null), LockRules.decide(true, none, LockRules.unlock(none, at(2, 10)), at(2, 11)))
    }

    @Test
    fun daylightSavingKeepsTheWallClockTime() {
        // US clocks go back on Sunday 2026-11-01; Friday 30 Oct evening locks until Monday 08:00 local.
        val friday = ZonedDateTime.of(2026, 10, 30, 19, 0, 0, 0, zone)
        assertEquals(ms(at(2, 8)), LockRules.decide(true, s, LockOverrides(), friday).untilMs)
    }

    @Test
    fun timesMustBeInOrder() {
        assertTrue(LockRules.validTimes(480, 1080))
        assertFalse(LockRules.validTimes(1080, 480))
        assertFalse(LockRules.validTimes(480, 480))
        assertTrue(LockRules.validTimes(0, 24 * 60))
    }

    @Test
    fun pinIsFourToEightDigits() {
        assertTrue(LockRules.validPin("1234"))
        assertTrue(LockRules.validPin("12345678"))
        assertFalse(LockRules.validPin("123"))
        assertFalse(LockRules.validPin("123456789"))
        assertFalse(LockRules.validPin("12a4"))
    }

    @Test
    fun pinHashMatchesOnlyTheSamePinAndSalt() {
        val h = LockRules.hashPin("salt", "4321")
        assertTrue(LockRules.pinMatches("salt", h, "4321"))
        assertFalse(LockRules.pinMatches("salt", h, "1234"))
        assertFalse(LockRules.pinMatches("other", h, "4321"))
        assertFalse(LockRules.pinMatches("salt", "", "4321"))
        assertNotEquals(h, LockRules.hashPin("other", "4321"))
    }

    @Test
    fun wrongPinsWaitLongerEachTime() {
        assertEquals(0L, LockRules.waitAfterFailures(4))
        assertEquals(30_000L, LockRules.waitAfterFailures(5))
        assertEquals(60_000L, LockRules.waitAfterFailures(6))
        assertEquals(LockRules.MAX_WAIT_MS, LockRules.waitAfterFailures(40))
        assertEquals(10_000L, LockRules.waitRemaining(5, lastFailureMs = 0, nowMs = 20_000))
        assertEquals(0L, LockRules.waitRemaining(5, lastFailureMs = 0, nowMs = 31_000))
    }

    @Test
    fun timeLabel() {
        assertEquals("08:00", LockRules.timeLabel(480))
        assertEquals("18:15", LockRules.timeLabel(1095))
    }

    @Test
    fun nextLockSkipsTheWeekend() {
        assertEquals(at(9, 18), LockRules.nextLockAt(s, at(6, 18)))
        assertNull(LockRules.nextLockAt(s.copy(days = emptySet()), at(6, 18)))
    }
}
