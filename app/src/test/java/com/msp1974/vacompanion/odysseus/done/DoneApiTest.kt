package com.msp1974.vacompanion.odysseus.done

import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DoneApiTest {

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun pathsFollowTheContract() {
        assertEquals("/todos/done", DoneApi.donePath(DoneTarget.Todo("todo:41:2")))
        assertEquals("/reminders/done", DoneApi.donePath(DoneTarget.Reminder("todo:41")))
        assertEquals("/action-items/done", DoneApi.donePath(DoneTarget.ActionItem("ai_17")))
        assertEquals("/todos/undo", DoneApi.undoPath(DoneTarget.Todo("todo:41:2")))
        assertEquals("/action-items/undo", DoneApi.undoPath(DoneTarget.ActionItem("ai_17")))
        assertNull(DoneApi.undoPath(DoneTarget.Reminder("todo:41")))
        assertFalse(DoneTarget.Reminder("todo:41").canUndo)
        assertTrue(DoneTarget.Todo("todo:41:2").canUndo)
    }

    @Test
    fun bodiesCarryRefOrIdAndTheDeviceId() {
        val todo = parse(DoneApi.body(DoneTarget.Todo("todo:41:2"), "dev-1"))
        assertEquals("todo:41:2", todo["ref"]!!.jsonPrimitive.content)
        assertEquals("dev-1", todo["device_id"]!!.jsonPrimitive.content)
        assertNull(todo["id"])

        val ai = parse(DoneApi.body(DoneTarget.ActionItem("ai_17"), "dev-1"))
        assertEquals("ai_17", ai["id"]!!.jsonPrimitive.content)
        assertNull(ai["ref"])

        // Quotes and backslashes are escaped, not spliced in.
        val odd = parse(DoneApi.body(DoneTarget.Reminder("todo:\"41\\"), "d\"1"))
        assertEquals("todo:\"41\\", odd["ref"]!!.jsonPrimitive.content)
        assertEquals("d\"1", odd["device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun okAnswers() {
        assertEquals(
            DoneOutcome.Ok(already = false, title = "Call the plumber"),
            DoneApi.classify(200, """{"ok": true, "already": false, "title": "Call the plumber"}"""),
        )
        assertEquals(DoneOutcome.Ok(already = true), DoneApi.classify(200, """{"ok": true, "already": true}"""))
        // Lenient: no body, extra keys.
        assertEquals(DoneOutcome.Ok(), DoneApi.classify(200, ""))
        assertEquals(DoneOutcome.Ok(), DoneApi.classify(204, """{"ok": true, "extra": 1}"""))
        val no = DoneApi.classify(200, """{"ok": false}""")
        assertTrue(no is DoneOutcome.Failed && no.kind == DoneOutcome.Kind.OTHER)
    }

    private fun kind(code: Int, body: String = "") = (DoneApi.classify(code, body) as DoneOutcome.Failed).kind

    @Test
    fun errorAnswers() {
        assertEquals(DoneOutcome.Kind.NOT_FOUND, kind(404, """{"error": "not found"}"""))
        // A 404 without the hub's error is an older hub without the route.
        assertEquals(DoneOutcome.Kind.NOT_SUPPORTED, kind(404, ""))
        assertEquals(DoneOutcome.Kind.NOT_SUPPORTED, kind(404, "<html>Not Found</html>"))
        assertEquals(DoneOutcome.Kind.NOT_SUPPORTED, kind(405))
        assertEquals(DoneOutcome.Kind.BAD_REQUEST, kind(400, """{"error": "bad ref"}"""))
        assertEquals(DoneOutcome.Kind.REFUSED, kind(401))
        assertEquals(DoneOutcome.Kind.REFUSED, kind(403))
        assertEquals(DoneOutcome.Kind.TOO_LATE, kind(409, """{"error": "too late"}"""))
        assertEquals(DoneOutcome.Kind.ATHENA_UNAVAILABLE, kind(503, """{"error": "athena unavailable"}"""))
        assertEquals(DoneOutcome.Kind.OTHER, kind(503, ""))
        assertEquals(DoneOutcome.Kind.OTHER, kind(500))
        assertEquals("Athena unavailable", (DoneApi.classify(503, """{"error":"Athena unavailable"}""") as DoneOutcome.Failed).reason)
    }

    @Test
    fun reasonsAreShort() {
        val codes = listOf(400, 401, 403, 404, 405, 409, 500, 503)
        for (c in codes) {
            val r = (DoneApi.classify(c, """{"error":"not found"}""") as DoneOutcome.Failed).reason
            assertTrue("$c: $r", r.length <= 28)
        }
        assertTrue(DoneApi.unreachable().reason.length <= 28)
    }

    private class FakeHub(val reply: () -> OdysseusLink.HubReply) : DoneHttp {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun post(path: String, json: String): OdysseusLink.HubReply {
            sent += path to json
            return reply()
        }
    }

    @Test
    fun clientSendsAndClassifies() = runBlocking {
        val hub = FakeHub { OdysseusLink.HubReply(200, """{"ok": true, "already": false}""") }
        val c = DoneClient(hub)
        assertEquals(DoneOutcome.Ok(), c.done(DoneTarget.Todo("todo:41:2"), "dev-1"))
        assertEquals(DoneOutcome.Ok(), c.undo(DoneTarget.ActionItem("ai_17"), "dev-1"))
        assertEquals(listOf("/todos/done", "/action-items/undo"), hub.sent.map { it.first })
        assertEquals("ai_17", parse(hub.sent[1].second)["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun clientNeverSendsAReminderUndo() = runBlocking {
        val hub = FakeHub { OdysseusLink.HubReply(200, "{}") }
        val r = DoneClient(hub).undo(DoneTarget.Reminder("todo:41"), "dev-1")
        assertTrue(r is DoneOutcome.Failed && r.kind == DoneOutcome.Kind.NOT_SUPPORTED)
        assertTrue(hub.sent.isEmpty())
    }

    @Test
    fun networkErrorsAreUnreachable() = runBlocking {
        val hub = FakeHub { throw IOException("timeout") }
        val r = DoneClient(hub).done(DoneTarget.ActionItem("ai_17"), "dev-1")
        assertTrue(r is DoneOutcome.Failed && r.kind == DoneOutcome.Kind.UNREACHABLE)
        val notLinked = FakeHub { throw IllegalStateException("not set up") }
        val r2 = DoneClient(notLinked).done(DoneTarget.ActionItem("ai_17"), "dev-1")
        assertTrue(r2 is DoneOutcome.Failed && r2.kind == DoneOutcome.Kind.UNREACHABLE)
    }

    @Test
    fun cancellationIsPassedOn() {
        val hub = FakeHub { throw CancellationException("gone") }
        try {
            runBlocking { DoneClient(hub).done(DoneTarget.ActionItem("ai_17"), "dev-1") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }
}
