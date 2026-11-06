package com.msp1974.vacompanion.odysseus.done

import com.msp1974.vacompanion.odysseus.HubFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract C5 against the hub's real answers to the Done routes (HubFixtures). */
class DoneApiHubFixturesTest {

    private fun outcome(code: Int, name: String) = DoneApi.classify(code, HubFixtures.load(name))

    private fun failed(kind: DoneOutcome.Kind, code: Int, name: String) =
        assertEquals(kind, (outcome(code, name) as DoneOutcome.Failed).kind)

    @Test
    fun doneAndUndo() {
        assertEquals(DoneOutcome.Ok(already = false, title = "Call the plumber"), outcome(200, "c5_todo_done.json"))
        assertEquals(DoneOutcome.Ok(already = true, title = "Call the plumber"), outcome(200, "c5_todo_done_already.json"))
        assertEquals(DoneOutcome.Ok(already = false, title = "Call the plumber"), outcome(200, "c5_todo_undo.json"))
        // reminders/done answers a title too (the plan's example has none)
        assertEquals(DoneOutcome.Ok(already = false, title = "Feed the fictional cat"), outcome(200, "c5_reminder_done.json"))
        assertEquals(DoneOutcome.Ok(already = true, title = "Feed the fictional cat"), outcome(200, "c5_reminder_done_already.json"))
        assertEquals(DoneOutcome.Ok(already = false, title = "Send Sam the photos"), outcome(200, "c5_action_item_done.json"))
        assertEquals(DoneOutcome.Ok(already = true, title = "Send Sam the photos"), outcome(200, "c5_action_item_done_already.json"))
    }

    @Test
    fun errors() {
        failed(DoneOutcome.Kind.NOT_FOUND, 404, "c5_not_found_404.json")
        failed(DoneOutcome.Kind.TOO_LATE, 409, "c5_undo_409.json")
        failed(DoneOutcome.Kind.ATHENA_UNAVAILABLE, 503, "c5_athena_unavailable_503.json")
        failed(DoneOutcome.Kind.BAD_REQUEST, 400, "c5_todo_done_400.json")
        failed(DoneOutcome.Kind.BAD_REQUEST, 400, "c5_action_item_done_400.json")
        failed(DoneOutcome.Kind.REFUSED, 401, "c5_no_token_401.json")
        failed(DoneOutcome.Kind.REFUSED, 403, "c5_scope_403.json")
        // a hub without the route answers FastAPI's own 404: "can't do this yet", not "not found"
        failed(DoneOutcome.Kind.NOT_SUPPORTED, 404, "c5_route_missing_404.json")
    }
}
