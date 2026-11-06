package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: the Done button of a reminder card (contract C5: POST <base>/reminders/done with
// the announcement's `ref`; docs/odysseus/announcements.md). Done: the card goes and the Today
// page is refreshed. Failed: the card stays with a short reason under its buttons (Done can be
// tapped again). There is no undo for a reminder. Logs refs only.

import com.msp1974.vacompanion.odysseus.done.DoneClient
import com.msp1974.vacompanion.odysseus.done.DoneOutcome
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import com.msp1974.vacompanion.odysseus.home.today.TodayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A reminder card's Done state: the request is out, or why the last one failed. */
data class ReminderDoneState(val sending: Boolean = false, val reason: String? = null)

object ReminderDone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DoneClient()

    /** By announcement id. */
    private val _states = MutableStateFlow<Map<String, ReminderDoneState>>(emptyMap())
    val states: StateFlow<Map<String, ReminderDoneState>> = _states.asStateFlow()

    fun done(a: Announcement) {
        val ref = a.ref?.takeIf { a.canMarkDone } ?: return
        if (_states.value[a.id]?.sending == true) return
        _states.update { it + (a.id to ReminderDoneState(sending = true)) }
        val target = DoneTarget.Reminder(ref)
        scope.launch {
            val outcome = client.done(target, Announcements.deviceId.orEmpty())
            when (outcome) {
                is DoneOutcome.Ok -> {
                    Timber.i("Announcements: done %s (%s) -> %s", a.id, ref, if (outcome.already) "already" else "ok")
                    Announcements.dismiss(a.id)
                    _states.update { it - a.id }
                    withContext(Dispatchers.Main) { TodayRepository.refresh() }
                }
                is DoneOutcome.Failed -> {
                    Timber.w("Announcements: done %s (%s) -> %s", a.id, ref, outcome.kind)
                    _states.update { it + (a.id to ReminderDoneState(reason = outcome.reason)) }
                }
            }
        }
    }

    /** Forget a card's state (it was dismissed). */
    fun forget(id: String) = _states.update { it - id }
}
