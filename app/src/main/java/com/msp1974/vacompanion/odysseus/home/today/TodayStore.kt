package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: what the Today page shows, as plain Kotlin (no Android, no Compose) so the
// rules are unit-tested (TodayStoreTest). TodayRepository wraps it in Compose state.
//
// - Not linked to Odysseus: NotLinked (the page isn't in the list then, but says so anyway).
// - Nothing fetched yet: Loading, or Unreachable once a fetch has failed.
// - A result is kept for KEEP_MS (2 h) after the last successful fetch, also while later fetches
//   fail (then marked stale), and dropped once it is for a day before today.

import java.time.LocalDate

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

    fun onSuccess(result: TodayData, nowMs: Long) {
        data = result
        fetchedAtMs = nowMs
        error = null
    }

    fun onFailure(message: String, @Suppress("UNUSED_PARAMETER") nowMs: Long) {
        error = message
    }

    /** Forget everything (unlinked). */
    fun clear() {
        data = null
        fetchedAtMs = 0L
        error = null
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
        const val KEEP_MS = 2 * 60 * 60_000L

        /** The result is for a day before [today] (fetched before midnight). Unparsable: keep it. */
        fun isForEarlierDay(data: TodayData, today: LocalDate): Boolean {
            val day = runCatching { LocalDate.parse(data.date.take(10)) }.getOrNull() ?: return false
            return day.isBefore(today)
        }
    }
}
