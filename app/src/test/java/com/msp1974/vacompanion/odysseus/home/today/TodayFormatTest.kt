package com.msp1974.vacompanion.odysseus.home.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

class TodayFormatTest {
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val ams: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val dentist = TodayEvent(
        title = "Dentist", start = "2026-11-06T15:00:00-05:00", end = "2026-11-06T15:30:00-05:00",
    )

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun eventTimeInTheDevicesZone() {
        assertEquals("15:00", TodayFormat.eventTime(dentist, ny, true, Locale.US))
        assertEquals("3:00 PM", TodayFormat.eventTime(dentist, ny, false, Locale.US))
        assertEquals("21:00", TodayFormat.eventTime(dentist, ams, true, Locale.US))
        assertEquals("21:00", TodayFormat.eventTime(dentist.copy(start = "2026-11-06T20:00:00Z"), ams, true, Locale.US))
        assertEquals("All day", TodayFormat.eventTime(dentist.copy(allDay = true, start = "2026-11-06"), ny, true))
        assertEquals("", TodayFormat.eventTime(dentist.copy(start = "soon"), ny, true))
    }

    @Test
    fun pastEvents() {
        assertFalse(TodayFormat.isPast(dentist, ms("2026-11-06T15:29:00-05:00")))
        assertTrue(TodayFormat.isPast(dentist, ms("2026-11-06T15:30:00-05:00")))
        // No end: an hour after the start.
        val noEnd = dentist.copy(end = null)
        assertFalse(TodayFormat.isPast(noEnd, ms("2026-11-06T15:59:00-05:00")))
        assertTrue(TodayFormat.isPast(noEnd, ms("2026-11-06T16:00:00-05:00")))
        assertFalse(TodayFormat.isPast(dentist.copy(allDay = true), ms("2026-11-06T23:00:00-05:00")))
    }

    @Test
    fun dueLabels() {
        val today = LocalDate.of(2026, 11, 6)
        assertEquals("Overdue", TodayFormat.dueLabel(TodayTodo(title = "a", due = "2026-11-05"), today))
        assertEquals("Today", TodayFormat.dueLabel(TodayTodo(title = "a", due = "2026-11-06"), today))
        assertEquals("Today", TodayFormat.dueLabel(TodayTodo(title = "a", due = "2026-11-06T18:00:00-05:00"), today))
        assertNull(TodayFormat.dueLabel(TodayTodo(title = "a", due = "2026-11-07"), today))
        assertNull(TodayFormat.dueLabel(TodayTodo(title = "a", due = null), today))
        assertNull(TodayFormat.dueLabel(TodayTodo(title = "a", due = "later"), today))
    }

    @Test
    fun moreCounts() {
        // Contract example: 9 open to-dos, 1 listed and shown.
        assertEquals(8, TodayFormat.more(9, 1, 1))
        assertEquals(3, TodayFormat.more(null, 5, 2))
        // A count lower than the list (a stale count) never hides listed items.
        assertEquals(2, TodayFormat.more(1, 4, 2))
        assertEquals(0, TodayFormat.more(0, 0, 0))
        assertEquals("and 8 more", TodayFormat.moreLabel(8))
        assertNull(TodayFormat.moreLabel(0))
    }

    @Test
    fun columnFitsEverythingWhenItCan() {
        assertEquals(3 to 2, TodayFormat.fitColumn(5, 3, 2))
        assertEquals(0 to 0, TodayFormat.fitColumn(5, 0, 0))
        assertEquals(0 to 0, TodayFormat.fitColumn(0, 3, 3))
    }

    @Test
    fun columnSharesRowsAndKeepsMoreLines() {
        fun used(rows: Int, t: Int, a: Int): Int {
            val (ts, as_) = TodayFormat.fitColumn(rows, t, a)
            return ts + (if (ts < t) 1 else 0) + as_ + (if (as_ < a) 1 else 0)
        }
        // Two "more" lines need two rows; from there on nothing overflows.
        for (rows in 2..8) for (t in 0..8) for (a in 0..5) {
            assertTrue("rows=$rows t=$t a=$a", used(rows, t, a) <= rows)
        }
        assertEquals(3 to 1, TodayFormat.fitColumn(5, 8, 1))   // 3 to-dos + more, 1 item
        assertEquals(2 to 2, TodayFormat.fitColumn(6, 8, 5))   // 2 + more, 2 + more
        assertEquals(0 to 5, TodayFormat.fitColumn(6, 0, 10))  // all rows for action items
        assertEquals(5 to 0, TodayFormat.fitColumn(6, 10, 0))
    }

    @Test
    fun eventsFit() {
        assertEquals(4, TodayFormat.fitEvents(6, 4))
        assertEquals(5, TodayFormat.fitEvents(6, 10))
        assertEquals(0, TodayFormat.fitEvents(0, 3))
    }
}
