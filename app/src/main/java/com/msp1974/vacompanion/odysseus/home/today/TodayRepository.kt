package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: the one process-wide holder of the Today page's data, as Compose state.
// TodayRefresh (composed by HomeHost) calls refresh() when the page comes up and every
// REFRESH_MS while the home screen is on. A failed fetch keeps the last result for up to 2 h
// (TodayStore); nothing here ever throws.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate

object TodayRepository {
    const val REFRESH_MS = 15 * 60_000L

    private val lock = Mutex()
    private val store = TodayStore()

    /** Linked to Odysseus (base URL and token saved). Compose state; see [checkLink]. */
    var linked by mutableStateOf(false)
        private set

    /** What the page shows. */
    var view by mutableStateOf<TodayView>(TodayView.NotLinked)
        private set

    /** Re-reads whether the device is linked (after the settings menu closes, before a refresh). */
    fun checkLink() {
        val now = OdysseusLink.isConfigured
        if (now != linked) Timber.i("Today: %s", if (now) "linked to Odysseus" else "not linked")
        linked = now
        if (!now) store.clear()
        publish()
    }

    private fun publish() {
        view = store.view(System.currentTimeMillis(), LocalDate.now(), linked)
    }

    /** Fetches /today unless the last successful fetch is younger than [minAgeMs]. Never throws. */
    suspend fun refresh(minAgeMs: Long = 0L, http: TodayHttp = TodayHttp { OdysseusLink.get(it) }) = lock.withLock {
        checkLink()
        if (!linked) return@withLock
        val now = System.currentTimeMillis()
        if (store.isFresh(now, minAgeMs)) {
            publish()
            return@withLock
        }
        try {
            val data = withContext(Dispatchers.IO) { TodayClient(http).fetch() }
            store.onSuccess(data, now)
            Timber.i(
                "Today: %s, %d events, %d to-dos, %d action items%s",
                data.date, data.events.size, data.todos.size, data.actionItems.size,
                if (data.headline != null) ", headline" else "",
            )
        } catch (e: Exception) {
            val message = (e as? TodayException)?.message ?: "Can't reach Odysseus (${e.message ?: e.javaClass.simpleName})"
            store.onFailure(message, now)
            Timber.w("Today: refresh failed: %s", message)
        }
        publish()
    }
}
