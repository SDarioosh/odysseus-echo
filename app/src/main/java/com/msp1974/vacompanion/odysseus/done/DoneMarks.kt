package com.msp1974.vacompanion.odysseus.done

// Odysseus fork: the optimistic Done state of the Today page's rows, as plain Kotlin (no Android,
// no Compose) so the rules are unit-tested (DoneMarksTest). today/TodayDone.kt holds it as a
// StateFlow and drives the clock.
//
// A tap marks a row done at once (SENDING). The hub's answer makes it DONE with "Undo" for
// UNDO_MS, or FAILED: the row is open again with a short reason for REASON_MS. When the undo
// window ends the row is HIDDEN until fresh Today data no longer lists it (at most HIDE_MAX_MS).
// While a row is marked it stays where it was, even when a refresh already left it out.

data class DoneMark(
    val target: DoneTarget,
    /** The row's title when it was tapped, to keep showing it if a refresh drops the row. */
    val title: String,
    /** Its place in its list when tapped. */
    val index: Int,
    val phase: Phase,
    /** When this phase ends (device clock, epoch ms); see [DoneMarks.expire]. */
    val untilMs: Long,
    /** "Undo" is offered (DONE). */
    val canUndo: Boolean = false,
    /** A short reason or note shown on the row ("Can't reach Odysseus", "Already done"). */
    val reason: String? = null,
) {
    enum class Phase { SENDING, DONE, UNDOING, FAILED, HIDDEN }

    /** The row is shown ticked (not FAILED, which is open again). */
    val ticked: Boolean get() = phase == Phase.SENDING || phase == Phase.DONE || phase == Phase.UNDOING
}

/** A row as the page shows it: an item from the data (or a held one, [item] null) and its mark. */
data class ShownRow<T>(val target: DoneTarget, val title: String, val item: T?, val mark: DoneMark?)

data class DoneMarks(val marks: Map<DoneTarget, DoneMark> = emptyMap()) {

    operator fun get(target: DoneTarget): DoneMark? = marks[target]

    /** A tap on an open (or failed) row: ticked at once. A row already being handled is left alone. */
    fun tap(target: DoneTarget, title: String, index: Int, nowMs: Long): DoneMarks {
        val m = marks[target]
        if (m != null && m.phase != DoneMark.Phase.FAILED) return this
        return put(DoneMark(target, title, index, DoneMark.Phase.SENDING, nowMs + SENDING_MAX_MS))
    }

    /** The hub answered the done request. */
    fun doneAnswered(target: DoneTarget, outcome: DoneOutcome, nowMs: Long): DoneMarks {
        val m = marks[target]?.takeIf { it.phase == DoneMark.Phase.SENDING } ?: return this
        return put(
            when (outcome) {
                is DoneOutcome.Ok -> if (outcome.already) {
                    m.copy(phase = DoneMark.Phase.DONE, untilMs = nowMs + REASON_MS, canUndo = false, reason = "Already done")
                } else {
                    m.copy(phase = DoneMark.Phase.DONE, untilMs = nowMs + UNDO_MS, canUndo = target.canUndo, reason = null)
                }
                is DoneOutcome.Failed ->
                    m.copy(phase = DoneMark.Phase.FAILED, untilMs = nowMs + REASON_MS, canUndo = false, reason = outcome.reason)
            }
        )
    }

    /** A tap on "Undo": only while it is offered. */
    fun undoTapped(target: DoneTarget, nowMs: Long): DoneMarks {
        val m = marks[target]?.takeIf { it.phase == DoneMark.Phase.DONE && it.canUndo } ?: return this
        return put(m.copy(phase = DoneMark.Phase.UNDOING, reason = null, untilMs = nowMs + SENDING_MAX_MS))
    }

    /** Whether [undoTapped] would start an undo (the controller sends the request only then). */
    fun canUndo(target: DoneTarget): Boolean = marks[target]?.let { it.phase == DoneMark.Phase.DONE && it.canUndo } == true

    /**
     * The hub answered the undo request. Undone: the mark goes and the row is open again. Too late
     * or gone: it stays done, without Undo, with the reason. Unreachable or another error: done,
     * with the reason, and Undo offered again for REASON_MS.
     */
    fun undoAnswered(target: DoneTarget, outcome: DoneOutcome, nowMs: Long): DoneMarks {
        val m = marks[target]?.takeIf { it.phase == DoneMark.Phase.UNDOING } ?: return this
        return when (outcome) {
            is DoneOutcome.Ok -> copy(marks = marks - target)
            is DoneOutcome.Failed -> {
                val final = outcome.kind == DoneOutcome.Kind.TOO_LATE || outcome.kind == DoneOutcome.Kind.NOT_FOUND ||
                    outcome.kind == DoneOutcome.Kind.CHANGED
                put(
                    m.copy(
                        phase = DoneMark.Phase.DONE,
                        canUndo = !final,
                        reason = outcome.reason,
                        untilMs = nowMs + REASON_MS,
                    )
                )
            }
        }
    }

    /**
     * Phases whose time is up: DONE becomes HIDDEN (for HIDE_MAX_MS), FAILED and HIDDEN go.
     * SENDING and UNDOING past their limit (no answer at all) go too: the next refresh shows the
     * hub's truth.
     */
    fun expire(nowMs: Long): DoneMarks {
        var changed = false
        val out = marks.mapNotNull { (k, m) ->
            if (m.untilMs > nowMs) return@mapNotNull k to m
            changed = true
            when (m.phase) {
                DoneMark.Phase.DONE -> k to m.copy(phase = DoneMark.Phase.HIDDEN, untilMs = nowMs + HIDE_MAX_MS, canUndo = false)
                else -> null
            }
        }.toMap()
        return if (changed) DoneMarks(out) else this
    }

    /** Some DONE row's window has closed by [nowMs] (a refresh is due after [expire]). */
    fun hidesAt(nowMs: Long): Boolean = marks.values.any { it.phase == DoneMark.Phase.DONE && it.untilMs <= nowMs }

    /** Fresh data: a HIDDEN row it no longer lists has nothing left to hide. */
    fun reconcile(listed: Set<DoneTarget>): DoneMarks {
        val out = marks.filterNot { (k, m) -> m.phase == DoneMark.Phase.HIDDEN && k !in listed }
        return if (out.size == marks.size) this else DoneMarks(out)
    }

    /** When the next mark changes phase by itself, or null. */
    fun nextDeadline(): Long? = marks.values.minOfOrNull { it.untilMs }

    /**
     * The rows of one list as shown: [items] with their marks, HIDDEN ones left out, and the
     * ticked marks of this list ([inList]) that [items] no longer has put back at their place.
     */
    fun <T> rows(
        items: List<T>,
        target: (T) -> DoneTarget,
        title: (T) -> String,
        inList: (DoneTarget) -> Boolean,
    ): List<ShownRow<T>> {
        val out = items.map { ShownRow(target(it), title(it), it, marks[target(it)]) }.toMutableList()
        val present = out.map { it.target }.toSet()
        marks.values
            .filter { inList(it.target) && it.ticked && it.target !in present }
            .sortedBy { it.index }
            .forEach { m -> out.add(m.index.coerceIn(0, out.size), ShownRow(m.target, m.title, null, m)) }
        return out.filterNot { it.mark?.phase == DoneMark.Phase.HIDDEN }
    }

    /** How many rows of a list are hidden (to take off the hub's open count). */
    fun hiddenIn(inList: (DoneTarget) -> Boolean): Int =
        marks.values.count { it.phase == DoneMark.Phase.HIDDEN && inList(it.target) }

    /**
     * How many of [listed] (the targets the current data still lists) are hidden: the ones the
     * hub's open count still includes. A hidden row the data no longer lists is already out of
     * that count, so it isn't taken off twice.
     */
    fun hiddenAmong(listed: Collection<DoneTarget>): Int =
        listed.toSet().count { marks[it]?.phase == DoneMark.Phase.HIDDEN }

    private fun put(m: DoneMark) = DoneMarks(marks + (m.target to m))

    companion object {
        /** "Done · Undo" stays this long. */
        const val UNDO_MS = 10_000L
        /** A reason ("Can't reach Odysseus") stays this long. */
        const val REASON_MS = 5_000L
        /** A row done and out of its undo window stays hidden at most this long, if the hub still lists it. */
        const val HIDE_MAX_MS = 2 * 60_000L
        /** No answer to a request after this long: give up on it (the request itself times out sooner). */
        const val SENDING_MAX_MS = 30_000L
    }
}
