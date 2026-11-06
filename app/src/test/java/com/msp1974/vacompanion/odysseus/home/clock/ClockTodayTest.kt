package com.msp1974.vacompanion.odysseus.home.clock

import com.msp1974.vacompanion.odysseus.home.today.TodayCounts
import com.msp1974.vacompanion.odysseus.home.today.TodayData
import com.msp1974.vacompanion.odysseus.home.today.TodayEvent
import com.msp1974.vacompanion.odysseus.home.today.TodayFocus
import com.msp1974.vacompanion.odysseus.home.today.TodayTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class ClockTodayTest {
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    private val now = ms("2026-11-06T15:01:00-05:00")

    private val allDay = TodayEvent(uid = "a", title = "Holiday", start = "2026-11-06T00:00:00-05:00", allDay = true)
    private val standup = TodayEvent(uid = "s", title = "Standup", start = "2026-11-06T09:30:00-05:00", end = "2026-11-06T09:45:00-05:00")
    private val dentist = TodayEvent(uid = "d", title = "Dentist", start = "2026-11-06T15:30:00-05:00", end = "2026-11-06T16:15:00-05:00")
    private val dinner = TodayEvent(uid = "n", title = "Dinner", start = "2026-11-06T18:30:00-05:00")

    @Test
    fun `next event skips past and all-day events`() {
        assertEquals(dentist, ClockToday.nextEvent(listOf(allDay, standup, dinner, dentist), now))
    }

    @Test
    fun `an event going on now comes first`() {
        val call = TodayEvent(uid = "c", title = "Call", start = "2026-11-06T14:45:00-05:00", end = "2026-11-06T15:15:00-05:00")
        assertEquals(call, ClockToday.nextEvent(listOf(dentist, call), now))
        assertEquals("now", ClockToday.relative(call, now))
    }

    @Test
    fun `an event without an end counts as an hour long`() {
        val noEnd = TodayEvent(uid = "x", title = "Walk", start = "2026-11-06T14:30:00-05:00")
        assertEquals(noEnd, ClockToday.nextEvent(listOf(noEnd), now))
        assertNull(ClockToday.nextEvent(listOf(noEnd), ms("2026-11-06T15:31:00-05:00")))
    }

    @Test
    fun `nothing left`() {
        assertNull(ClockToday.nextEvent(listOf(standup, allDay), now))
        assertNull(ClockToday.nextEvent(emptyList(), now))
    }

    @Test
    fun `event after`() {
        assertEquals(dinner, ClockToday.eventAfter(listOf(standup, dentist, dinner, allDay), dentist))
        assertNull(ClockToday.eventAfter(listOf(dinner), dinner))
    }

    @Test
    fun `relative labels`() {
        fun at(min: Long) = TodayEvent(start = OffsetDateTime.parse("2026-11-06T15:01:00-05:00").plusMinutes(min).toString())
        assertEquals("in 29 min", ClockToday.relative(at(29), now))
        assertEquals("in 1 h 20 min", ClockToday.relative(at(80), now))
        assertEquals("in 1 h", ClockToday.relative(at(60), now))
        assertEquals("in 3 h", ClockToday.relative(at(170), now))
        assertEquals("", ClockToday.relative(TodayEvent(start = "garbage"), now))
    }

    @Test
    fun `counts prefer the hub's numbers`() {
        val data = TodayData(todos = listOf(TodayTodo(ref = "1"), TodayTodo(ref = "2")), counts = TodayCounts(todosOpen = 5))
        assertEquals(5 to 0, ClockToday.counts(data))
        assertEquals(2 to 0, ClockToday.counts(data.copy(counts = TodayCounts())))
    }

    @Test
    fun `counts label`() {
        assertEquals("3 to-dos · 2 action items", ClockToday.countsLabel(3, 2))
        assertEquals("1 to-do", ClockToday.countsLabel(1, 0))
        assertEquals("1 action item", ClockToday.countsLabel(0, 1))
        assertEquals("", ClockToday.countsLabel(0, 0))
    }

    @Test
    fun `headline falls back to focus`() {
        assertEquals("Quiet day", ClockToday.headline(TodayData(headline = "Quiet day")))
        assertEquals("Ship it", ClockToday.headline(TodayData(headline = " ", focus = listOf(TodayFocus(title = "Ship it")))))
        assertNull(ClockToday.headline(TodayData()))
    }
}
