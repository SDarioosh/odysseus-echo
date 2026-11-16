package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: what rings when (docs/odysseus/alarms-timers.md). Pure, unit-tested.
//
// - Each ring has a key: "alarm:<id>@<epoch minute>" for one ring of an alarm, "timer:<id>" for a
//   timer, "snooze:<n>" for a snoozed ring. A key rings once (the device remembers the keys it rang).
// - A ring missed by more than MISSED_MS (the device was off or the app stopped) is skipped, so an
//   Echo that comes back at noon doesn't ring the 7:00 alarm.
// - A ring stops by itself after MAX_RING_MS. A snooze rings again SNOOZE_MS later.
// - One ring at a time: a second one due while one rings waits until it stops (it is not lost).

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Something due to ring at [atMs]. */
@Serializable
data class Due(
    val key: String,
    val kind: Kind,
    /** "Gym", "Alarm", "Pasta", "10 min timer". */
    val title: String,
    /** "7:30 AM", "10 min": the second line on the ringing card. */
    val detail: String,
    val atMs: Long,
) {
    enum class Kind { ALARM, TIMER }
}

/** A snoozed ring: rings again at [atMs] as [due]. */
@Serializable
data class Snooze(val key: String, val due: Due, val atMs: Long)

object RingRules {
    const val MISSED_MS = 10 * 60_000L
    const val MAX_RING_MS = 10 * 60_000L
    const val SNOOZE_MS = 9 * 60_000L
    /** Rung keys are kept this long (longer than any ring could be late). */
    const val KEEP_RUNG_MS = 26 * 3_600_000L

    fun alarmKey(id: String, atMs: Long) = "alarm:$id@${atMs / 60_000L}"
    fun timerKey(id: String) = "timer:$id"

    fun alarmDue(a: Alarm, at: ZonedDateTime): Due = Due(
        key = alarmKey(a.id, at.toInstant().toEpochMilli()),
        kind = Due.Kind.ALARM,
        title = a.label ?: "Alarm",
        detail = AlarmRules.timeLabel(a.time),
        atMs = at.toInstant().toEpochMilli(),
    )

    fun timerDue(t: DeviceTimer, atMs: Long): Due =
        Due(timerKey(t.id), Due.Kind.TIMER, TimerRules.title(t), TimerRules.duration(t.setSeconds), atMs)

    /**
     * Every ring from [nowMs] - MISSED_MS on, not yet rung, soonest first: each alarm's next one or
     * two rings (the next after an already rung one), each running timer's end, each snooze.
     */
    fun upcoming(
        alarms: List<Alarm>, timers: List<DeviceTimer>, snoozes: List<Snooze>, rung: Set<String>,
        nowMs: Long, zone: ZoneId,
    ): List<Due> {
        val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs - MISSED_MS), zone)
        val out = mutableListOf<Due>()
        for (a in alarms) {
            var after = from
            repeat(2) {
                val at = AlarmRules.nextRing(a, after, zone) ?: return@repeat
                val due = alarmDue(a, at)
                if (due.key !in rung) { out += due; return@repeat }
                after = at
            }
        }
        for (t in timers) {
            val end = t.endsAtMs ?: continue
            val due = timerDue(t, end)
            if (due.key !in rung && end >= nowMs - MISSED_MS) out += due
        }
        for (s in snoozes) if (s.key !in rung) out += s.due.copy(key = s.key, atMs = s.atMs)
        return out.distinctBy { it.key }.sortedBy { it.atMs }
    }

    /** The first of [upcoming] that is due at [nowMs], or null. */
    fun dueNow(upcoming: List<Due>, nowMs: Long): Due? = upcoming.firstOrNull { it.atMs <= nowMs }

    /** When to look again: the next ring after [nowMs], at most [maxWaitMs] away (clock changes, new lists). */
    fun nextCheckMs(upcoming: List<Due>, nowMs: Long, maxWaitMs: Long = 60_000L): Long {
        val next = upcoming.firstOrNull { it.atMs > nowMs }?.atMs ?: (nowMs + maxWaitMs)
        return minOf(next, nowMs + maxWaitMs)
    }

    /** Keys rung within KEEP_RUNG_MS of [nowMs] (key → when it rang). */
    fun pruneRung(rung: Map<String, Long>, nowMs: Long): Map<String, Long> =
        rung.filterValues { nowMs - it < KEEP_RUNG_MS }

    /** [due] snoozed at [nowMs]; [count] makes the key unique. */
    fun snooze(due: Due, nowMs: Long, count: Long): Snooze = Snooze("snooze:$count", due, nowMs + SNOOZE_MS)
}
