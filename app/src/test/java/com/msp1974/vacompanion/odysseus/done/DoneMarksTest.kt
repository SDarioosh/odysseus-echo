package com.msp1974.vacompanion.odysseus.done

import com.msp1974.vacompanion.odysseus.done.DoneMark.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneMarksTest {
    private val plumber = DoneTarget.Todo("todo:41:2")
    private val milk = DoneTarget.Todo("todo:41:3")
    private val photos = DoneTarget.ActionItem("ai_17")
    private val ok = DoneOutcome.Ok()
    private val offline = DoneApi.unreachable()
    private val t0 = 1_000_000L

    private data class Item(val key: String, val title: String)

    private val items = listOf(Item("todo:41:2", "Call the plumber"), Item("todo:41:3", "Buy milk"))

    private fun DoneMarks.todoRows(list: List<Item>) =
        rows(list, { DoneTarget.Todo(it.key) }, { it.title }, { it is DoneTarget.Todo })

    @Test
    fun tapTicksAtOnce() {
        val m = DoneMarks().tap(plumber, "Call the plumber", 0, t0)
        assertEquals(Phase.SENDING, m[plumber]!!.phase)
        assertTrue(m[plumber]!!.ticked)
        assertFalse(m.canUndo(plumber))
        // A second tap while it is out changes nothing.
        assertSame(m, m.tap(plumber, "Call the plumber", 0, t0 + 10))
    }

    @Test
    fun doneOffersUndoForTenSeconds() {
        val m = DoneMarks().tap(plumber, "Call the plumber", 0, t0).doneAnswered(plumber, ok, t0 + 300)
        val mark = m[plumber]!!
        assertEquals(Phase.DONE, mark.phase)
        assertTrue(mark.canUndo)
        assertEquals(t0 + 300 + DoneMarks.UNDO_MS, mark.untilMs)
        assertEquals(t0 + 300 + DoneMarks.UNDO_MS, m.nextDeadline())

        // Still there just before the end, hidden after it.
        assertSame(m, m.expire(t0 + 300 + DoneMarks.UNDO_MS - 1))
        assertFalse(m.hidesAt(t0 + 300 + DoneMarks.UNDO_MS - 1))
        assertTrue(m.hidesAt(t0 + 300 + DoneMarks.UNDO_MS))
        val later = m.expire(t0 + 300 + DoneMarks.UNDO_MS)
        assertEquals(Phase.HIDDEN, later[plumber]!!.phase)
        assertFalse(later.canUndo(plumber))
    }

    @Test
    fun alreadyDoneHasNoUndo() {
        val m = DoneMarks().tap(plumber, "x", 0, t0).doneAnswered(plumber, DoneOutcome.Ok(already = true), t0)
        assertEquals(Phase.DONE, m[plumber]!!.phase)
        assertFalse(m[plumber]!!.canUndo)
        assertEquals("Already done", m[plumber]!!.reason)
    }

    @Test
    fun failureReopensTheRowWithAReason() {
        val m = DoneMarks().tap(plumber, "x", 0, t0).doneAnswered(plumber, offline, t0 + 100)
        val mark = m[plumber]!!
        assertEquals(Phase.FAILED, mark.phase)
        assertFalse(mark.ticked)
        assertEquals("Can't reach Odysseus", mark.reason)
        // It can be tapped again at once.
        assertEquals(Phase.SENDING, m.tap(plumber, "x", 0, t0 + 200)[plumber]!!.phase)
        // The reason goes after REASON_MS.
        assertNull(m.expire(t0 + 100 + DoneMarks.REASON_MS)[plumber])
    }

    @Test
    fun undoReopens() {
        var m = DoneMarks().tap(photos, "Send Sam the photos", 0, t0).doneAnswered(photos, ok, t0)
        assertTrue(m.canUndo(photos))
        m = m.undoTapped(photos, t0 + 2000)
        assertEquals(Phase.UNDOING, m[photos]!!.phase)
        // A slow undo isn't cut off by the old window.
        assertEquals(Phase.UNDOING, m.expire(t0 + DoneMarks.UNDO_MS + 1)[photos]!!.phase)
        m = m.undoAnswered(photos, ok, t0 + 2500)
        assertNull(m[photos])
    }

    @Test
    fun undoTooLateStaysDoneWithoutUndo() {
        val m = DoneMarks().tap(photos, "x", 0, t0).doneAnswered(photos, ok, t0)
            .undoTapped(photos, t0 + 1000)
            .undoAnswered(photos, DoneApi.classify(409, ""), t0 + 1200)
        val mark = m[photos]!!
        assertEquals(Phase.DONE, mark.phase)
        assertFalse(mark.canUndo)
        assertEquals("Too late to undo", mark.reason)
    }

    @Test
    fun undoUnreachableCanBeTriedAgain() {
        val m = DoneMarks().tap(photos, "x", 0, t0).doneAnswered(photos, ok, t0)
            .undoTapped(photos, t0 + 1000)
            .undoAnswered(photos, offline, t0 + 1200)
        assertTrue(m.canUndo(photos))
        assertEquals("Can't reach Odysseus", m[photos]!!.reason)
        assertEquals(t0 + 1200 + DoneMarks.REASON_MS, m[photos]!!.untilMs)
    }

    @Test
    fun undoOnlyWhenOffered() {
        val sending = DoneMarks().tap(plumber, "x", 0, t0)
        assertSame(sending, sending.undoTapped(plumber, t0))
        assertEquals(DoneMarks(), DoneMarks().undoTapped(plumber, t0))
        // Late answers to requests no longer out are ignored.
        assertEquals(DoneMarks(), DoneMarks().doneAnswered(plumber, ok, t0))
        assertSame(sending, sending.undoAnswered(plumber, ok, t0))
    }

    @Test
    fun noAnswerAtAllGivesUp() {
        val m = DoneMarks().tap(plumber, "x", 0, t0)
        assertNull(m.expire(t0 + DoneMarks.SENDING_MAX_MS)[plumber])
    }

    @Test
    fun hiddenUntilTheDataDropsIt() {
        val hidden = DoneMarks().tap(plumber, "x", 0, t0).doneAnswered(plumber, ok, t0).expire(t0 + DoneMarks.UNDO_MS)
        // The hub still lists it (refresh not back yet): stays hidden.
        assertSame(hidden, hidden.reconcile(setOf(plumber, milk)))
        // Fresh data without it: nothing left to hide.
        assertNull(hidden.reconcile(setOf(milk))[plumber])
        // At most HIDE_MAX_MS.
        assertNull(hidden.expire(t0 + DoneMarks.UNDO_MS + DoneMarks.HIDE_MAX_MS)[plumber])
    }

    @Test
    fun rowsKeepTickedRowsInPlace() {
        val m = DoneMarks().tap(plumber, "Call the plumber", 0, t0).doneAnswered(plumber, ok, t0)
        // Data still has it: marked in place.
        val rows = m.todoRows(items)
        assertEquals(listOf("Call the plumber", "Buy milk"), rows.map { it.title })
        assertEquals(Phase.DONE, rows[0].mark!!.phase)
        assertNull(rows[1].mark)
        // A refresh already dropped it: held at its place, without an item.
        val held = m.todoRows(items.drop(1))
        assertEquals(listOf("Call the plumber", "Buy milk"), held.map { it.title })
        assertNull(held[0].item)
        assertEquals(plumber, held[0].target)
    }

    @Test
    fun rowsLeaveOutHiddenAndOtherLists() {
        val m = DoneMarks()
            .tap(plumber, "Call the plumber", 0, t0).doneAnswered(plumber, ok, t0)
            .tap(photos, "Send Sam the photos", 0, t0).doneAnswered(photos, ok, t0)
        // The action item's mark doesn't show among the to-dos.
        assertEquals(2, m.todoRows(items).size)
        val hidden = m.expire(t0 + DoneMarks.UNDO_MS)
        assertEquals(listOf("Buy milk"), hidden.todoRows(items).map { it.title })
        assertEquals(1, hidden.hiddenIn { it is DoneTarget.Todo })
        assertEquals(1, hidden.hiddenIn { it is DoneTarget.ActionItem })
    }

    @Test
    fun failedRowsAreNotHeldWhenGone() {
        val m = DoneMarks().tap(plumber, "Call the plumber", 0, t0).doneAnswered(plumber, DoneApi.classify(404, """{"error":"not found"}"""), t0)
        assertEquals(listOf("Buy milk"), m.todoRows(items.drop(1)).map { it.title })
        assertEquals("Not found in Odysseus", m.todoRows(items)[0].mark!!.reason)
    }

    @Test
    fun heldIndexIsClamped() {
        val m = DoneMarks().tap(milk, "Buy milk", 5, t0)
        assertEquals(listOf("Call the plumber", "Buy milk"), m.todoRows(items.take(1)).map { it.title })
    }
}
