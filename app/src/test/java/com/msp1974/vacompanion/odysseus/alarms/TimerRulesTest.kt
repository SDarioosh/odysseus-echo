package com.msp1974.vacompanion.odysseus.alarms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRulesTest {
    private val t0 = 1_000_000L

    private fun started(id: String = "t1", total: Long = 600, name: String? = null, minutes: Long? = 10): JsonObject =
        buildJsonObject {
            put("id", id); put("total_seconds", total)
            name?.let { put("name", it) }
            minutes?.let { put("start_minutes", it) }
        }

    @Test fun startedTimerCountsDownFromTotalSeconds() {
        val r = TimerRules.apply(emptyList(), TimerRules.STARTED, started(name = "pasta"), t0)
        val t = r.timers.single()
        assertEquals(t0 + 600_000, t.endsAtMs)
        assertEquals("Pasta", TimerRules.title(t))
        assertEquals(300_000, t.leftMs(t0 + 300_000))
        assertNull(r.finished)
    }

    @Test fun unnamedTimerIsCalledByItsLength() {
        val t = TimerRules.apply(emptyList(), TimerRules.STARTED, started(total = 5400, minutes = null).let {
            buildJsonObject { it.forEach { (k, v) -> put(k, v) }; put("start_hours", 1); put("start_minutes", 30) }
        }, t0).timers.single()
        assertEquals("1 h 30 min timer", TimerRules.title(t))
    }

    @Test fun pauseResumeAndAddTimeUseTheRemainingSeconds() {
        var timers = TimerRules.apply(emptyList(), TimerRules.STARTED, started(), t0).timers
        // Paused with 4 min left.
        timers = TimerRules.apply(timers, TimerRules.UPDATED, buildJsonObject {
            put("id", "t1"); put("is_active", false); put("total_seconds", 240)
        }, t0 + 360_000).timers
        assertFalse(timers.single().running)
        assertEquals(240_000, timers.single().leftMs(t0 + 999_999))
        // Resumed later: 4 min from then.
        timers = TimerRules.apply(timers, TimerRules.UPDATED, buildJsonObject {
            put("id", "t1"); put("is_active", true); put("total_seconds", 240)
        }, t0 + 1_000_000).timers
        assertTrue(timers.single().running)
        assertEquals(t0 + 1_240_000, timers.single().endsAtMs)
        // The label keeps the length it was set for.
        assertEquals("10 min timer", TimerRules.title(timers.single()))
    }

    @Test fun cancelledTimerGoes() {
        val timers = TimerRules.apply(emptyList(), TimerRules.STARTED, started(), t0).timers
        assertTrue(TimerRules.apply(timers, TimerRules.CANCELLED, buildJsonObject { put("id", "t1") }, t0).timers.isEmpty())
    }

    @Test fun finishedTimerIsReportedAndRemoved() {
        val timers = TimerRules.apply(emptyList(), TimerRules.STARTED, started(name = "eggs"), t0).timers
        val r = TimerRules.apply(timers, TimerRules.FINISHED, buildJsonObject { put("id", "t1") }, t0 + 600_000)
        assertTrue(r.timers.isEmpty())
        assertEquals("Eggs", TimerRules.title(r.finished!!))
        // A finished timer this device never heard of still rings.
        assertEquals("t9", TimerRules.apply(emptyList(), TimerRules.FINISHED, buildJsonObject { put("id", "t9") }, t0).finished!!.id)
    }

    @Test fun eventsWithoutIdOrForUnknownTimersChangeNothing() {
        val timers = TimerRules.apply(emptyList(), TimerRules.STARTED, started(), t0).timers
        assertEquals(timers, TimerRules.apply(timers, TimerRules.UPDATED, buildJsonObject { put("id", "x"); put("total_seconds", 1) }, t0).timers)
        assertEquals(timers, TimerRules.apply(timers, TimerRules.CANCELLED, buildJsonObject { }, t0).timers)
        assertEquals(timers, TimerRules.apply(timers, TimerRules.STARTED, buildJsonObject { put("id", "t2") }, t0).timers)
    }

    @Test fun endedAndCountdown() {
        val timers = TimerRules.apply(emptyList(), TimerRules.STARTED, started(), t0).timers
        assertTrue(TimerRules.ended(timers, t0 + 599_999).isEmpty())
        assertEquals(1, TimerRules.ended(timers, t0 + 600_000).size)
        assertEquals("10:00", TimerRules.countdown(600_000))
        assertEquals("0:01", TimerRules.countdown(1))
        assertEquals("0:00", TimerRules.countdown(0))
        assertEquals("1:02:03", TimerRules.countdown(3_723_000))
        assertEquals("2 min 30 s", TimerRules.duration(150))
        assertEquals("45 s", TimerRules.duration(45))
    }
}
