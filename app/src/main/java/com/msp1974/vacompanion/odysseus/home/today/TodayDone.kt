package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: the Done buttons of the Today page (contract C5, docs/odysseus/home-screen.md
// "Done"). Process-wide, so a done finishes and its undo window closes also when the page is
// swiped away. The rules are in done/DoneMarks (plain Kotlin, tested); this sends the requests
// (done/DoneClient), runs the clock and refreshes the Today data afterwards: once a done's undo
// window has closed, after an undo, and after a failed done (to show the hub's truth).
// Logs refs and ids only, never titles.

import com.msp1974.vacompanion.odysseus.done.DoneClient
import com.msp1974.vacompanion.odysseus.done.DoneMarks
import com.msp1974.vacompanion.odysseus.done.DoneOutcome
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

object TodayDone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DoneClient()

    private val _marks = MutableStateFlow(DoneMarks())
    val marks: StateFlow<DoneMarks> = _marks.asStateFlow()

    private var ticker: Job? = null

    private fun now() = System.currentTimeMillis()

    @Synchronized
    private fun change(f: (DoneMarks) -> DoneMarks): Boolean {
        val before = _marks.value
        val after = f(before)
        if (after === before) return false
        _marks.value = after
        reschedule()
        return true
    }

    /** A tap on an open row. */
    fun markDone(target: DoneTarget, title: String, index: Int, deviceId: String) {
        if (!change { it.tap(target, title, index, now()) }) return
        scope.launch {
            // The row's title: a line that moved in Odysseus since is found by it (409 "changed"
            // when it is gone: the row comes back with "Changed in Odysseus" and Today refreshes).
            val outcome = client.done(target, deviceId, title)
            Timber.i("Today: done %s -> %s", target.label, describe(outcome))
            change { it.doneAnswered(target, outcome, now()) }
            if (outcome is DoneOutcome.Failed) refreshToday()
        }
    }

    /** A tap on "Undo". */
    fun undo(target: DoneTarget, deviceId: String) {
        if (!change { it.undoTapped(target, now()) }) return
        scope.launch {
            val outcome = client.undo(target, deviceId)
            Timber.i("Today: undo %s -> %s", target.label, describe(outcome))
            change { it.undoAnswered(target, outcome, now()) }
            if (outcome is DoneOutcome.Ok) refreshToday()
        }
    }

    /** Called with the lock held (from [change]). */
    private fun reschedule() {
        ticker?.cancel()
        val next = _marks.value.nextDeadline() ?: return
        ticker = scope.launch {
            delay((next - now()).coerceAtLeast(0) + 20)
            tick()
        }
    }

    private fun tick() {
        val t = now()
        val hides = _marks.value.hidesAt(t)
        change { it.expire(t) } // cancels this ticker's job: the refresh runs in its own
        if (hides) scope.launch { refreshToday() }
    }

    private suspend fun refreshToday() {
        // TodayRepository writes Compose state; refresh() fetches on IO itself.
        withContext(Dispatchers.Main) { TodayRepository.refresh() }
        val data = (TodayRepository.view as? TodayView.Ready)?.data ?: return
        change { it.reconcile(listed(data)) }
    }

    private fun listed(data: TodayData): Set<DoneTarget> =
        data.todos.map { todoTarget(it) }.toSet() + data.actionItems.map { actionItemTarget(it) }

    fun todoTarget(t: TodayTodo): DoneTarget = DoneTarget.Todo(t.ref)
    fun actionItemTarget(a: TodayActionItem): DoneTarget = DoneTarget.ActionItem(a.id)

    private fun describe(o: DoneOutcome) = when (o) {
        is DoneOutcome.Ok -> if (o.already) "already" else "ok"
        is DoneOutcome.Failed -> "${o.kind} (${o.reason})"
    }
}
