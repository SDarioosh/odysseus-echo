package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: the Today page's labels and how much fits, as plain Kotlin so it is unit-tested
// (TodayFormatTest). Times arrive as ISO-8601 with the hub's offset; they are shown in the
// device's own time zone.

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TodayFormat {

    private fun instant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(iso) }.getOrNull()
    }

    /** "15:00" / "3:00 PM" in [zone]; "All day" for all-day events; "" when unreadable. */
    fun eventTime(event: TodayEvent, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        if (event.allDay) return "All day"
        val start = instant(event.start) ?: return ""
        val pattern = if (is24h) "H:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(start.atZone(zone))
    }

    /** The event has ended (or, without an end, started more than an hour ago). All-day: never. */
    fun isPast(event: TodayEvent, nowMs: Long): Boolean {
        if (event.allDay) return false
        val end = instant(event.end) ?: instant(event.start)?.plusSeconds(3600) ?: return false
        return end.toEpochMilli() <= nowMs
    }

    /** "Overdue", "Today" or null (later or undated, e.g. pinned). */
    fun dueLabel(todo: TodayTodo, today: LocalDate): String? {
        val due = todo.due?.takeIf { it.length >= 10 } ?: return null
        val day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return null
        return when {
            day.isBefore(today) -> "Overdue"
            day == today -> "Today"
            else -> null
        }
    }

    /** How many open items aren't shown: the hub's count (or the list size) minus [shown]. */
    fun more(total: Int?, listed: Int, shown: Int): Int = maxOf(0, maxOf(total ?: listed, listed) - shown)

    /** "and 3 more", or null for none. */
    fun moreLabel(n: Int): String? = if (n > 0) "and $n more" else null

    /**
     * How many to-dos and action items fit in a column of [rows] item rows. Each non-empty section
     * also takes a row for its "and N more" line when it doesn't fit whole. To-dos come first, but
     * action items keep at least one row when there are any.
     */
    fun fitColumn(rows: Int, todos: Int, actionItems: Int): Pair<Int, Int> {
        if (rows <= 0) return 0 to 0
        if (todos + actionItems <= rows) return todos to actionItems
        // Rows used, counting a "more" line for a section that isn't shown whole.
        fun used(t: Int, a: Int) = t + (if (t < todos) 1 else 0) + a + (if (a < actionItems) 1 else 0)
        // Action items get a third of the column (at least one row) first, to-dos the rest,
        // then action items any rows left over.
        var a = minOf(actionItems, maxOf(1, rows / 3))
        while (a > 0 && used(0, a) > rows) a--
        var t = todos
        while (t > 0 && used(t, a) > rows) t--
        while (a < actionItems && used(t, a + 1) <= rows) a++
        return t to a
    }

    /** Events that fit in [rows], keeping a row for "and N more" when they don't all fit. */
    fun fitEvents(rows: Int, events: Int): Int = when {
        rows <= 0 -> 0
        events <= rows -> events
        else -> rows - 1
    }

    /** "Updated 8:15" for a stale result. */
    fun updatedAt(epochMs: Long, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        val pattern = if (is24h) "H:mm" else "h:mm a"
        return "Updated " + DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }
}
