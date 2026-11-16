package com.msp1974.vacompanion.odysseus.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RingRulesTest {
    private val zone = ZoneId.of("America/New_York")

    // 2026-11-16 is a Monday.
    private fun ms(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private val weekdays = AlarmRules.of(HubAlarm("al_1", "07:00", listOf(1, 2, 3, 4, 5), label = "Gym"))!!

    private fun upcoming(now: Long, rung: Set<String> = emptySet(), timers: List<DeviceTimer> = emptyList(), snoozes: List<Snooze> = emptyList()) =
        RingRules.upcoming(listOf(weekdays), timers, snoozes, rung, now, zone)

    @Test fun alarmIsDueAtItsTimeAndOnlyOnce() {
        val before = upcoming(ms(16, 6, 59))
        assertNull(RingRules.dueNow(before, ms(16, 6, 59)))
        assertEquals(ms(16, 7), RingRules.nextCheckMs(before, ms(16, 6, 59, 30)))

        val due = RingRules.dueNow(upcoming(ms(16, 7, 0, 1)), ms(16, 7, 0, 1))!!
        assertEquals("Gym", due.title)
        assertEquals(Due.Kind.ALARM, due.kind)

        // Rung: tomorrow's is next, not today's again.
        val after = upcoming(ms(16, 7, 1), rung = setOf(due.key))
        assertNull(RingRules.dueNow(after, ms(16, 7, 1)))
        assertEquals(ms(17, 7), after.first().atMs)
    }

    @Test fun aRingMissedByMoreThanTenMinutesIsSkipped() {
        assertTrue(RingRules.dueNow(upcoming(ms(16, 7, 9)), ms(16, 7, 9)) != null)
        assertNull(RingRules.dueNow(upcoming(ms(16, 7, 11)), ms(16, 7, 11)))
    }

    @Test fun timerIsDueAtItsEndOnce() {
        val t = DeviceTimer("t1", "pasta", 600, endsAtMs = ms(16, 12, 10))
        val due = RingRules.dueNow(upcoming(ms(16, 12, 10), timers = listOf(t)), ms(16, 12, 10))!!
        assertEquals(RingRules.timerKey("t1"), due.key)
        assertEquals("Pasta", due.title)
        assertNull(RingRules.dueNow(upcoming(ms(16, 12, 10), rung = setOf(due.key), timers = listOf(t)), ms(16, 12, 10)))
        // Paused timers never ring.
        val paused = t.copy(endsAtMs = null, pausedLeftMs = 1000)
        assertNull(RingRules.dueNow(upcoming(ms(16, 12, 10), timers = listOf(paused)), ms(16, 12, 10)))
    }

    @Test fun snoozeRingsAgainNineMinutesLater() {
        val due = RingRules.dueNow(upcoming(ms(16, 7)), ms(16, 7))!!
        val s = RingRules.snooze(due, ms(16, 7, 1), 1)
        val list = upcoming(ms(16, 7, 2), rung = setOf(due.key), snoozes = listOf(s))
        assertNull(RingRules.dueNow(list, ms(16, 7, 2)))
        val again = RingRules.dueNow(upcoming(ms(16, 7, 10), rung = setOf(due.key), snoozes = listOf(s)), ms(16, 7, 10))!!
        assertEquals("Gym", again.title)
        assertEquals(s.key, again.key)
    }

    @Test fun soonestFirstAndChecksAtLeastEveryMinute() {
        val t = DeviceTimer("t1", null, 60, endsAtMs = ms(16, 6, 30))
        val list = upcoming(ms(16, 6, 0), timers = listOf(t))
        assertEquals(listOf(RingRules.timerKey("t1")), list.take(1).map { it.key })
        assertEquals(ms(16, 6, 1), RingRules.nextCheckMs(list, ms(16, 6, 0)))
    }

    @Test fun rungKeysAreForgottenAfterADay() {
        val kept = RingRules.pruneRung(mapOf("a" to 0L, "b" to RingRules.KEEP_RUNG_MS), RingRules.KEEP_RUNG_MS + 1)
        assertEquals(setOf("b"), kept.keys)
    }
}
