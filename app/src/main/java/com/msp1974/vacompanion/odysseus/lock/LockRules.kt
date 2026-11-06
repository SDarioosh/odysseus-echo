package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: the rules of Lock mode (locked outside the work hours, PIN to unlock). Plain
// Kotlin, no Android, unit-tested (LockRulesTest). LockMode runs them; see docs/odysseus/lock-mode.md.

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** Open from [openMinute] to [closeMinute] (minutes after midnight, open < close) on [days]. */
data class LockSchedule(
    val openMinute: Int,
    val closeMinute: Int,
    val days: Set<DayOfWeek>,
)

/** A lock or unlock that holds until a time (epoch ms; 0 = none), set by the PIN, Home Assistant or "Lock now". */
data class LockOverrides(
    val unlockedUntilMs: Long = 0L,
    val lockedUntilMs: Long = 0L,
)

/** Whether it is locked now, and when that changes by itself (epoch ms; null = not by itself). */
data class LockDecision(val locked: Boolean, val untilMs: Long?)

object LockRules {
    const val DEFAULT_OPEN_MINUTE = 8 * 60
    const val DEFAULT_CLOSE_MINUTE = 18 * 60
    val WEEKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    /** The times step by this in the settings menu. */
    const val STEP_MINUTES = 15

    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 8

    /** Wrong PINs allowed before a wait; each further wrong PIN doubles the wait, up to the max. */
    const val FREE_ATTEMPTS = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 5 * 60_000L

    fun isOpenHours(s: LockSchedule, t: ZonedDateTime): Boolean {
        if (t.dayOfWeek !in s.days) return false
        val minute = t.hour * 60 + t.minute
        return minute >= s.openMinute && minute < s.closeMinute
    }

    /** The first time after [t] at minute [minuteOfDay] of a scheduled day, or null with no days. */
    private fun nextAt(s: LockSchedule, t: ZonedDateTime, minuteOfDay: Int): ZonedDateTime? {
        val midnight = t.toLocalDate().atStartOfDay(t.zone)
        for (d in 0L..7L) {
            val day = midnight.plusDays(d)
            if (day.dayOfWeek !in s.days) continue
            // From the local date and time again, so a daylight saving change keeps the wall-clock time.
            val at = day.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(t.zone)
            if (at.isAfter(t)) return at
        }
        return null
    }

    /** When the schedule next locks (a scheduled day's lock time after [t]). */
    fun nextLockAt(s: LockSchedule, t: ZonedDateTime): ZonedDateTime? = nextAt(s, t, s.closeMinute)

    /** When the schedule next opens (a scheduled day's open time after [t]). */
    fun nextOpenAt(s: LockSchedule, t: ZonedDateTime): ZonedDateTime? = nextAt(s, t, s.openMinute)

    fun decide(enabled: Boolean, s: LockSchedule, o: LockOverrides, t: ZonedDateTime): LockDecision {
        if (!enabled) return LockDecision(false, null)
        val now = t.toInstant().toEpochMilli()
        if (o.lockedUntilMs > now) return LockDecision(true, o.lockedUntilMs.takeIf { it != Long.MAX_VALUE })
        if (o.unlockedUntilMs > now) return LockDecision(false, o.unlockedUntilMs.takeIf { it != Long.MAX_VALUE })
        return if (isOpenHours(s, t)) {
            LockDecision(false, nextLockAt(s, t)?.toInstant()?.toEpochMilli())
        } else {
            LockDecision(true, nextOpenAt(s, t)?.toInstant()?.toEpochMilli())
        }
    }

    /** Unlocked (PIN or Home Assistant) until the schedule's next lock time. */
    fun unlock(s: LockSchedule, t: ZonedDateTime): LockOverrides =
        LockOverrides(unlockedUntilMs = nextLockAt(s, t)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE)

    /** Locked ("Lock now" or Home Assistant) until the schedule's next open time. */
    fun lock(s: LockSchedule, t: ZonedDateTime): LockOverrides =
        LockOverrides(lockedUntilMs = nextOpenAt(s, t)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE)

    /** Overrides that have run out are dropped, so an old one can never come back. */
    fun expire(o: LockOverrides, nowMs: Long): LockOverrides = LockOverrides(
        unlockedUntilMs = o.unlockedUntilMs.takeIf { it > nowMs } ?: 0L,
        lockedUntilMs = o.lockedUntilMs.takeIf { it > nowMs } ?: 0L,
    )

    /** Open must come before close on the same day. */
    fun validTimes(openMinute: Int, closeMinute: Int): Boolean =
        openMinute in 0 until 24 * 60 && closeMinute in 1..24 * 60 && openMinute < closeMinute

    fun validPin(pin: String): Boolean =
        pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

    fun hashPin(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Compares in constant time, so the time taken says nothing about how much matched. */
    fun pinMatches(salt: String, storedHash: String, pin: String): Boolean {
        if (storedHash.isEmpty()) return false
        return MessageDigest.isEqual(hashPin(salt, pin).toByteArray(), storedHash.toByteArray())
    }

    /** How long to wait after [failures] wrong PINs in a row before the next try. */
    fun waitAfterFailures(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }

    /** Milliseconds still to wait before a PIN may be tried, 0 = now. */
    fun waitRemaining(failures: Int, lastFailureMs: Long, nowMs: Long): Long =
        (lastFailureMs + waitAfterFailures(failures) - nowMs).coerceAtLeast(0L)

    fun timeLabel(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)
}
