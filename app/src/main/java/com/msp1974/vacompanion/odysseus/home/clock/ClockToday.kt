package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: what the clock page's Odysseus elements say (next event, counts), as plain
// Kotlin so it is unit-tested (ClockTodayTest). The data is the Today page's (today/TodayClient).

import com.msp1974.vacompanion.odysseus.home.today.TodayData
import com.msp1974.vacompanion.odysseus.home.today.TodayEvent
import java.time.Instant
import java.time.OffsetDateTime

object ClockToday {

    private fun instant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(iso) }.getOrNull()
    }

    /**
     * The event to show as "next": one going on now (started, not ended), else the first that
     * starts later. All-day events and unreadable times are skipped. Null when nothing is left.
     */
    fun nextEvent(events: List<TodayEvent>, nowMs: Long): TodayEvent? {
        val timed = events.filter { !it.allDay }.mapNotNull { ev -> instant(ev.start)?.let { ev to it.toEpochMilli() } }
        val now = timed.firstOrNull { (ev, start) ->
            val end = instant(ev.end)?.toEpochMilli() ?: (start + 3_600_000L)
            start <= nowMs && nowMs < end
        }
        if (now != null) return now.first
        return timed.filter { it.second > nowMs }.minByOrNull { it.second }?.first
    }

    /** The timed event after [event] (for "Then 6:30 Dinner"); null when none. */
    fun eventAfter(events: List<TodayEvent>, event: TodayEvent): TodayEvent? {
        val start = instant(event.start)?.toEpochMilli() ?: return null
        return events.filter { !it.allDay && it !== event }
            .mapNotNull { ev -> instant(ev.start)?.toEpochMilli()?.let { ev to it } }
            .filter { it.second > start }
            .minByOrNull { it.second }?.first
    }

    /** "now", "in 29 min", "in 1 h 20 min", "in 3 h"; "" when the start can't be read. */
    fun relative(event: TodayEvent, nowMs: Long): String {
        val start = instant(event.start)?.toEpochMilli() ?: return ""
        val minutes = ((start - nowMs) / 60_000L).toInt()
        return when {
            minutes <= 0 -> "now"
            minutes < 60 -> "in $minutes min"
            minutes < 120 && minutes % 60 != 0 -> "in 1 h ${minutes % 60} min"
            else -> "in ${(minutes + 30) / 60} h"
        }
    }

    /** Open to-dos and action items, by the hub's counts where it sent them. */
    fun counts(data: TodayData): Pair<Int, Int> =
        (data.counts.todosOpen ?: data.todos.size) to (data.counts.actionItemsOpen ?: data.actionItems.size)

    /** "3 to-dos · 2 action items"; "" when both are zero. */
    fun countsLabel(todos: Int, actionItems: Int): String = listOfNotNull(
        todos.takeIf { it > 0 }?.let { if (it == 1) "1 to-do" else "$it to-dos" },
        actionItems.takeIf { it > 0 }?.let { if (it == 1) "1 action item" else "$it action items" },
    ).joinToString(" · ")

    /** The Today headline, else the first focus item's title; null for neither. */
    fun headline(data: TodayData): String? =
        data.headline?.takeIf { it.isNotBlank() } ?: data.focus.firstOrNull()?.title?.takeIf { it.isNotBlank() }
}
