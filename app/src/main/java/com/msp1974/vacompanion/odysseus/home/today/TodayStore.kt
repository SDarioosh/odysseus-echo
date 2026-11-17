package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: what the Today page shows, as plain Kotlin (no Android, no Compose) so the
// rules are unit-tested (TodayStoreTest). TodayRepository wraps it in Compose state.
//
// - Not linked to Odysseus: NotLinked (the page isn't in the list then, but says so anyway).
// - Nothing fetched yet: Loading, or Unreachable once a fetch has failed.
// - A result is kept for KEEP_MS (2 h) after the last successful fetch, also while later fetches
//   fail (then marked stale), and dropped once it is for a day before today. "Today" is the
//   hub's day (the offset of its `generated` time), so a device whose own time zone differs
//   doesn't drop a current result in the evening.
// - Refreshes (refreshDue) every interval after the last attempt, failed or not, so a hub that
//   is down isn't asked more often, and soon after midnight for the new day.

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface TodayView {
    data object NotLinked : TodayView
    data object Loading : TodayView
    data class Unreachable(val message: String) : TodayView
    data class Ready(
        val data: TodayData,
        /** When [data] was fetched (epoch ms). */
        val fetchedAtMs: Long,
        /** The last fetch failed; [data] is older. */
        val stale: Boolean,
        val error: String? = null,
    ) : TodayView
}

class TodayStore(private val keepMs: Long = KEEP_MS) {
    var data: TodayData? = null
        private set
    var fetchedAtMs: Long = 0L
        private set
    var error: String? = null
        private set
    /** When the last fetch ended, successful or not (epoch ms, 0 = never). */
    var lastAttemptMs: Long = 0L
        private set

    fun onSuccess(result: TodayData, nowMs: Long) {
        data = result
        fetchedAtMs = nowMs
        error = null
        lastAttemptMs = nowMs
    }

    fun onFailure(message: String, nowMs: Long) {
        error = message
        lastAttemptMs = nowMs
    }

    /** Forget everything (unlinked). */
    fun clear() {
        data = null
        fetchedAtMs = 0L
        error = null
        lastAttemptMs = 0L
    }

    /**
     * Whether to fetch again now: never tried, [intervalMs] since the last attempt (a clock that
     * went backwards counts), or the result is for an earlier day and the last attempt is at
     * least [ROLLOVER_RETRY_MS] old (the first refresh after midnight; a hub still on the old day
     * is asked at most that often).
     */
    fun refreshDue(nowMs: Long, today: LocalDate, intervalMs: Long): Boolean {
        if (lastAttemptMs <= 0L || nowMs < lastAttemptMs) return true
        val age = nowMs - lastAttemptMs
        if (age >= intervalMs) return true
        val d = data ?: return false
        return isForEarlierDay(d, today) && age >= ROLLOVER_RETRY_MS
    }

    /** Whether a fetch younger than [minAgeMs] already succeeded (no need to ask again). */
    fun isFresh(nowMs: Long, minAgeMs: Long): Boolean =
        data != null && error == null && nowMs - fetchedAtMs in 0 until minAgeMs

    fun view(nowMs: Long, today: LocalDate, linked: Boolean): TodayView {
        if (!linked) return TodayView.NotLinked
        val d = data
        val usable = d != null && nowMs - fetchedAtMs <= keepMs && !isForEarlierDay(d, today)
        if (d != null && usable) return TodayView.Ready(d, fetchedAtMs, stale = error != null, error = error)
        val e = error
        return if (e != null) TodayView.Unreachable(e) else TodayView.Loading
    }

    companion object {
        const val OFFLINE_MESSAGE = "No network connection"

        /** [view] while the device has no network: a result is stale, nothing yet is unreachable. */
        fun offline(view: TodayView): TodayView = when (view) {
            is TodayView.Ready -> if (view.stale) view else view.copy(stale = true, error = OFFLINE_MESSAGE)
            TodayView.Loading -> TodayView.Unreachable(OFFLINE_MESSAGE)
            else -> view
        }

        const val KEEP_MS = 2 * 60 * 60_000L
        const val ROLLOVER_RETRY_MS = 5 * 60_000L

        /**
         * Today's date for [data]: at [nowMs] in the hub's offset (that of its `generated` time),
         * or in [fallback] (the device's zone) without one. The hub's `date` is its own day.
         */
        fun today(data: TodayData?, nowMs: Long, fallback: ZoneId): LocalDate {
            val offset = data?.generated?.let { runCatching { OffsetDateTime.parse(it).offset }.getOrNull() }
            val instant = Instant.ofEpochMilli(nowMs)
            return if (offset != null) instant.atOffset(offset).toLocalDate() else instant.atZone(fallback).toLocalDate()
        }

        /** The result is for a day before [today] (fetched before midnight). Unparsable: keep it. */
        fun isForEarlierDay(data: TodayData, today: LocalDate): Boolean {
            val day = runCatching { LocalDate.parse(data.date.take(10)) }.getOrNull() ?: return false
            return day.isBefore(today)
        }
    }
}
